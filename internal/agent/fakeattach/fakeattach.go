// Package fakeattach turns a test binary into a stand-in for
// `claude attach <id>`: it puts its terminal in raw mode, asks for the
// cursor position, optionally prints for a while, and appends every byte
// it receives to a log file. With a transcript configured it also plays
// the session's side of sending a message: pasted text followed by Enter
// becomes a user record, or an enqueue record, appended to the transcript.
// It draws the input box between two rules, a status line that Shift+Tab
// cycles and Ctrl+L redraws, a /model picker (the one recorded from
// Claude Code 2.1.323) that arrows move in, s picks from and Esc closes,
// and with RewindEnv a /rewind panel laid out as Claude Code 2.1.330 does.
// Only tests import this package; their TestMain calls RunIfFake first.
package fakeattach

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	envFlag  = "CLAUDERC_FAKE_ATTACH"
	envLog   = "FAKE_ATTACH_LOG"
	envNoise = "FAKE_ATTACH_NOISE_MS"
	envTrans = "FAKE_ATTACH_TRANSCRIPT"
	envDeliv = "FAKE_ATTACH_DELIVER"
	envModes = "FAKE_ATTACH_MODES"
	envConf  = "FAKE_ATTACH_CONFIRM"
	envRew   = "FAKE_ATTACH_REWIND"
	envRewD  = "FAKE_ATTACH_REWIND_DELAY_MS"
)

// Command is the argv of a fake attach to session id: the running test
// binary, which RunIfFake diverts when Env is set.
func Command(id string) []string {
	return []string{os.Args[0], "-test.run=^$", "fake-attach", id}
}

// Env makes the test binary act as the fake and log received bytes to log.
func Env(log string) []string {
	return []string{envFlag + "=1", envLog + "=" + log}
}

// NoiseEnv makes the fake print continuously for d after it starts.
func NoiseEnv(d time.Duration) string {
	return envNoise + "=" + strconv.FormatInt(d.Milliseconds(), 10)
}

// TranscriptEnv makes the fake append what it is sent to the transcript.
func TranscriptEnv(path string) []string {
	return []string{envTrans + "=" + path}
}

// DeliverEnv picks what a sent message turns into: "user" (the default,
// a <bash-input> record for a "!" command as Claude Code writes it),
// "enqueue" as for a busy session, which like Claude Code leaves out the
// text of a slash command, "enqueue-bare", an enqueue without text
// whatever was sent, "unknown", Claude Code's warning that it has no such
// slash command, "working", nothing recorded but a status line on screen
// as while /compact runs, or "none".
func DeliverEnv(mode string) string {
	return envDeliv + "=" + mode
}

// ModesEnv sets the permission modes Shift+Tab cycles through, by their
// transcript names; the default is default, acceptEdits, plan, auto.
func ModesEnv(modes ...string) string {
	return envModes + "=" + strings.Join(modes, ",")
}

// ConfirmEnv makes picking a model ask "Switch model?", as for a session
// that already has a conversation.
func ConfirmEnv() string {
	return envConf + "=1"
}

// RewindEnv makes /rewind, or Esc twice on the empty input, open a panel
// listing rows, the user's messages oldest first, whose ways back are
// options. Choosing one that takes the conversation back puts the message
// into the input box; like Claude Code, the panel writes nothing to the
// transcript.
func RewindEnv(rows, options []string) string {
	b, _ := json.Marshal(map[string][]string{"rows": rows, "options": options})
	return envRew + "=" + string(b)
}

// RewindDelayEnv makes the rewind panel put the message into the input
// box d after it closes, as Claude Code does while it restores files.
func RewindDelayEnv(d time.Duration) string {
	return envRewD + "=" + strconv.FormatInt(d.Milliseconds(), 10)
}

// Gone reports whether no process with pid exists.
func Gone(pid int) bool {
	return errors.Is(syscall.Kill(pid, 0), syscall.ESRCH)
}

// RunIfFake runs the fake and exits when the environment asks for it, and
// returns otherwise.
func RunIfFake() {
	if os.Getenv(envFlag) != "1" {
		return
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "fakeattach:", err)
		os.Exit(1)
	}
	os.Exit(0)
}

func run() error {
	stty := exec.Command("stty", "raw", "-echo")
	stty.Stdin = os.Stdin
	if err := stty.Run(); err != nil {
		return err
	}
	log, err := os.OpenFile(os.Getenv(envLog), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer log.Close()
	out := os.Stdout
	_, _ = out.WriteString("\x1b[6n")
	if ms, _ := strconv.Atoi(os.Getenv(envNoise)); ms > 0 {
		end := time.Now().Add(time.Duration(ms) * time.Millisecond)
		for time.Now().Before(end) {
			_, _ = out.WriteString(".")
			time.Sleep(50 * time.Millisecond)
		}
	}
	in := &input{out: out, modes: []string{"default", "acceptEdits", "plan", "auto"}, cursor: pickerCurrent}
	if m := os.Getenv(envModes); m != "" {
		in.modes = strings.Split(m, ",")
	}
	if r := os.Getenv(envRew); r != "" {
		var cfg struct{ Rows, Options []string }
		if err := json.Unmarshal([]byte(r), &cfg); err != nil {
			return err
		}
		in.rows, in.options = cfg.Rows, cfg.Options
	}
	in.render()
	buf := make([]byte, 4096)
	for {
		n, err := os.Stdin.Read(buf)
		if n > 0 {
			if _, werr := log.Write(buf[:n]); werr != nil {
				return werr
			}
			if werr := in.feed(buf[:n]); werr != nil {
				return werr
			}
		}
		if err != nil {
			return nil
		}
	}
}

// input interprets the keys the fake receives.
type input struct {
	out     *os.File
	pending []byte
	pastes  []string
	modes   []string
	mode    int
	picker  bool
	cursor  int
	confirm bool
	// status is the status line drawn above the input box, "" for none.
	status string
	// text is what the input box shows, which only the rewind panel puts there.
	text string
	// rows and options make up the rewind panel; rewind is 1 while its list
	// shows, 2 while the ways back for row show; row and option are the
	// cursors.
	rows, options       []string
	rewind, row, option int
	// escAt is when Esc was last pressed on the input box: a second one
	// soon after opens the rewind panel, as in Claude Code.
	escAt time.Time
}

// footers are the status lines of each mode (RESULTS.md, mode_cycle).
var footers = map[string]string{
	"default":     "⏸ manual mode on",
	"acceptEdits": "⏵⏵ accept edits on (shift+tab to cycle)",
	"plan":        "⏸ plan mode on (shift+tab to cycle)",
	"auto":        "⏵⏵ auto mode on (shift+tab to cycle)",
}

// pickerItems is the /model picker recorded from Claude Code 2.1.323;
// desc holds the description lines as wrapped on a 120 column screen.
var pickerItems = []struct {
	label string
	desc  []string
}{
	{"Default (recommended)", []string{"Use the default model (currently deepseek-v4.1-flash[1m]) · Set by", "ANTHROPIC_DEFAULT_MODEL"}},
	{"Opus 5.5", []string{"Custom Opus model (claude-opus-5-5[1M])"}},
	{"gpt-6-astra-cc-format[1M]", []string{"Custom Fable model"}},
	{"deepseek-v4.1-flash[1M]", []string{"Custom Sonnet model (1M context)"}},
	{"glm-5.3-flash[1M]", []string{"Custom Haiku model"}},
	{"deepseek-v4.1-flash[1M]", []string{"Custom model"}},
}

// pickerCurrent is the index of the session's model in pickerItems.
const pickerCurrent = 5

func (in *input) render() {
	var b strings.Builder
	b.WriteString("\x1b[2J\x1b[H")
	switch {
	case in.picker:
		b.WriteString("   Select model\r\n\r\n")
		for i, it := range pickerItems {
			mark, label := " ", it.label
			if i == in.cursor {
				mark = "❯"
			}
			if i == pickerCurrent {
				label += " ✔"
			}
			fmt.Fprintf(&b, "   %s %d. %-27s%s\r\n", mark, i+1, label, it.desc[0])
			for _, d := range it.desc[1:] {
				fmt.Fprintf(&b, "%35s%s\r\n", "", d)
			}
		}
		b.WriteString("\r\n   Enter to set as default · s to use this session only · Esc to cancel\r\n")
	case in.confirm:
		b.WriteString("   Switch model?\r\n   ❯ 1. Yes, switch to the model\r\n")
	case in.rewind == 1:
		b.WriteString(strings.Repeat("▔", 40) + "\r\n  Rewind\r\n\r\n  Restore the code and/or conversation to the point before…\r\n\r\n")
		for i, r := range append(append([]string{}, in.rows...), "(current)") {
			mark := "  "
			if i == in.row {
				mark = "❯ "
			}
			b.WriteString("  " + mark + r + "\r\n")
			if i < len(in.rows) {
				b.WriteString("    No code changes\r\n\r\n")
			}
		}
		b.WriteString("\r\n  Enter to continue · Esc to cancel\r\n")
	case in.rewind == 2:
		b.WriteString(strings.Repeat("▔", 40) + "\r\n  Rewind\r\n\r\n  Restore to the point before you sent this message:\r\n\r\n  │ " + in.rows[in.row] + "\r\n\r\n")
		for i, o := range in.options {
			mark := "  "
			if i == in.option {
				mark = "❯ "
			}
			fmt.Fprintf(&b, "  %s%d. %s\r\n", mark, i+1, o)
		}
	default:
		// The input box between two rules, as Claude Code draws it.
		rule := strings.Repeat("─", 40)
		if in.status != "" {
			b.WriteString(in.status + "\r\n")
		}
		b.WriteString(rule + "\r\n❯\u00a0" + in.text + "\r\n" + rule + "\x1b[40;1H  " + footers[in.modes[in.mode]])
	}
	_, _ = in.out.WriteString(b.String())
}

// key handles one key outside a paste; it reports whether it was used.
func (in *input) key(k string) bool {
	if in.rewind > 0 && in.rewindKey(k) {
		in.render()
		return true
	}
	switch {
	case k == "\x0c":
	case k == "\x1b[Z" && !in.picker:
		in.mode = (in.mode + 1) % len(in.modes)
	case k == "\x1b[A" && in.picker:
		in.cursor = (in.cursor + len(pickerItems) - 1) % len(pickerItems)
	case k == "\x1b[B" && in.picker:
		in.cursor = (in.cursor + 1) % len(pickerItems)
	case k == "\x1b" && in.picker:
		in.picker = false
	case k == "\x1b" && in.rows != nil && !in.confirm && in.text == "":
		if time.Since(in.escAt) < time.Second {
			in.rewind, in.row, in.escAt = 1, len(in.rows), time.Time{}
		} else {
			in.escAt = time.Now()
		}
	case k == "s" && in.picker:
		in.picker = false
		in.confirm = os.Getenv(envConf) == "1"
	case k == "\r" && in.confirm:
		in.confirm = false
	case k == "\r" && len(in.pastes) > 0 && in.pastes[len(in.pastes)-1] == "/model":
		in.pastes = nil
		in.picker, in.cursor = true, pickerCurrent
	case k == "\r" && len(in.pastes) > 0 && in.pastes[len(in.pastes)-1] == "/rewind" && in.rows != nil:
		in.pastes = nil
		in.rewind, in.row = 1, len(in.rows)
	default:
		return false
	}
	in.render()
	return true
}

// rewindKey handles a key while the rewind panel shows.
func (in *input) rewindKey(k string) bool {
	switch {
	case k == "\x1b[A" && in.rewind == 1:
		in.row = max(in.row-1, 0)
	case k == "\x1b[B" && in.rewind == 1:
		in.row = min(in.row+1, len(in.rows))
	case k == "\r" && in.rewind == 1:
		if in.row == len(in.rows) {
			in.rewind = 0
		} else {
			in.rewind, in.option = 2, 0
		}
	case k == "\x1b" && in.rewind == 1:
		in.rewind = 0
	case k == "\x1b[A" && in.rewind == 2:
		in.option = max(in.option-1, 0)
	case k == "\x1b[B" && in.rewind == 2:
		in.option = min(in.option+1, len(in.options)-1)
	case k == "\r" && in.rewind == 2:
		if o := in.options[in.option]; strings.Contains(o, "conversation") {
			text := in.rows[in.row]
			if ms, _ := strconv.Atoi(os.Getenv(envRewD)); ms > 0 {
				time.AfterFunc(time.Duration(ms)*time.Millisecond, func() { in.text = text; in.render() })
			} else {
				in.text = text
			}
		}
		in.rewind = 0
	case k == "\x1b" && in.rewind == 2:
		in.rewind = 1
	default:
		return false
	}
	return true
}

var (
	pasteStart = []byte("\x1b[200~")
	pasteEnd   = []byte("\x1b[201~")
)

func (in *input) feed(b []byte) error {
	in.pending = append(in.pending, b...)
	for len(in.pending) > 0 {
		d := in.pending
		switch {
		case bytes.HasPrefix(d, pasteStart):
			end := bytes.Index(d, pasteEnd)
			if end < 0 {
				return nil
			}
			in.pastes = append(in.pastes, string(d[len(pasteStart):end]))
			in.pending = d[end+len(pasteEnd):]
		case d[0] == 0x1b:
			n := escapeLen(d)
			if n == 0 {
				return nil
			}
			in.pending = d[n:]
			in.key(string(d[:n]))
		case d[0] == '\r':
			in.pending = d[1:]
			if in.key("\r") {
				continue
			}
			if err := in.enter(); err != nil {
				return err
			}
		case d[0] == 0x0c || d[0] == 's':
			in.pending = d[1:]
			in.key(string(d[:1]))
		case d[0] == 0x15:
			in.pending = d[1:]
			in.pastes = nil
			if in.text != "" {
				in.text = ""
				in.render()
			}
		default:
			in.pending = d[1:]
		}
	}
	return nil
}

// escapeLen is the length of the escape sequence at the start of d, or 0
// when it is incomplete.
func escapeLen(d []byte) int {
	if len(d) < 2 {
		if bytes.Equal(d, []byte{0x1b}) {
			return 1 // a lone Esc
		}
		return 0
	}
	if d[1] != '[' {
		return 1
	}
	for i := 2; i < len(d); i++ {
		if d[i] >= 0x40 && d[i] <= 0x7e {
			return i + 1
		}
	}
	return 0
}

// enter submits the pasted text: earlier pastes are image paths.
func (in *input) enter() error {
	pastes := in.pastes
	in.pastes = nil
	path := os.Getenv(envTrans)
	if path == "" || len(pastes) == 0 {
		return nil
	}
	text := strings.TrimSpace(pastes[len(pastes)-1])
	for i := len(pastes) - 2; i >= 0; i-- {
		text = fmt.Sprintf("[Image #%d] ", i+1) + text
	}
	var rec any
	switch os.Getenv(envDeliv) {
	case "none":
		return nil
	case "working":
		// Taken but not recorded yet, as /compact until it is done: the
		// screen shows the session working.
		in.status = "✻ Compacting conversation… (esc to interrupt)"
		in.render()
		return nil
	case "enqueue":
		rec = map[string]any{"type": "queue-operation", "operation": "enqueue", "content": text}
		if strings.HasPrefix(text, "/") {
			rec = map[string]any{"type": "queue-operation", "operation": "enqueue"}
		}
	case "enqueue-bare":
		rec = map[string]any{"type": "queue-operation", "operation": "enqueue"}
	case "unknown":
		// Claude Code's answer to a slash command it has no such command
		// or skill for (2.1.329).
		name, _, _ := strings.Cut(text, " ")
		rec = map[string]any{"type": "system", "subtype": "informational", "level": "warning", "content": "Unknown command: " + name}
	default:
		content := text
		if line, ok := strings.CutPrefix(text, "!"); ok {
			// Claude Code runs a "!" command and records it like this.
			content = "<bash-input>" + strings.TrimSpace(line) + "</bash-input>"
		}
		rec = map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": content}}
	}
	line, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = f.Write(append(line, '\n'))
	return err
}
