package chat_test

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/chat"
	"clauderc/internal/protocol"
)

func running() setup { return setup{status: "busy", state: "working"} }

func (f *fixture) openTerm(t *testing.T, client string) protocol.TermScreen {
	t.Helper()
	var s protocol.TermScreen
	if err := json.Unmarshal(f.ok(t, client, protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: shortID}), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

// shellAttach replaces the attach with script, run by sh after the
// terminal is put in raw mode; the fake's environment stays, so the key
// log is $FAKE_ATTACH_LOG.
func shellAttach(script string) func(*chat.Options) {
	return func(o *chat.Options) {
		o.Attach.Command = func(string) []string { return []string{"/bin/sh", "-c", "stty raw -echo; " + script} }
		o.Attach.ReadyTimeout = time.Second
	}
}

func TestTermOpenReturnsScreen(t *testing.T) {
	t.Parallel()
	s := running()
	s.screen = "Claude Code\n❯ hello"
	f := newFixture(t, s)
	got := f.openTerm(t, "c1")
	if got.SessionID != shortID || len(got.Lines) != attach.Rows {
		t.Fatalf("screen of %q has %d lines, want %d", got.SessionID, len(got.Lines), attach.Rows)
	}
	if got.Lines[0] != "Claude Code" || got.Lines[1] != "❯ hello" {
		t.Errorf("lines = %q", got.Lines[:3])
	}
	if !got.Cursor || got.CursorRow != 1 || got.CursorCol != len([]rune("❯ hello")) {
		t.Errorf("cursor %v at %d,%d, want shown after ❯ hello", got.Cursor, got.CursorRow, got.CursorCol)
	}
}

func TestTermOpenStoppedSession(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{pid: "null"})
	res := f.call(t, "c1", protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: shortID})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("error = %+v, want invalidParams", res.Error)
	}
}

func TestTermKeys(t *testing.T) {
	t.Parallel()
	s := running()
	s.screen = "❯ "
	f := newFixture(t, s)

	res := f.call(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: protocol.TermKeyUp})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("key before term.open: error %+v, want invalidParams", res.Error)
	}
	f.openTerm(t, "c1")
	keys := []protocol.TermKey{"up", "down", "left", "right", "enter", "esc", "tab", "backspace", "1", "0"}
	for _, k := range keys {
		f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: k})
	}
	if got, want := f.keyLog(), "\x1b[A\x1b[B\x1b[D\x1b[C\r\x1b\t\x7f10"; got != want {
		t.Errorf("typed %q, want %q", got, want)
	}
	for _, k := range []protocol.TermKey{"space", "a", "10", "", "ctrl+c"} {
		res := f.call(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: k})
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
			t.Errorf("key %q: error %+v, want invalidParams", k, res.Error)
		}
	}
	// Another phone that has not opened the view cannot press keys.
	res = f.call(t, "c2", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "up"})
	if res.Error == nil {
		t.Errorf("key from a phone without the view was accepted")
	}
}

type pushed struct {
	at     time.Time
	screen protocol.TermScreen
}

// screens collects term.screen events for d.
func (f *fixture) screens(t *testing.T, d time.Duration) []pushed {
	t.Helper()
	var out []pushed
	deadline := time.After(d)
	for {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventTermScreen {
				continue
			}
			var s protocol.TermScreen
			if err := json.Unmarshal(e.Body, &s); err != nil {
				t.Fatal(err)
			}
			out = append(out, pushed{time.Now(), s})
		case <-deadline:
			return out
		}
	}
}

// The screen changes 20 times within about a second: at most 5 pushes in
// any second, and the last one is the final screen.
func TestTermScreenThrottled(t *testing.T) {
	t.Parallel()
	s := running()
	s.opts = shellAttach(`printf 'ready'; head -c 1 >/dev/null; i=1; while [ $i -le 20 ]; do printf '\033[H\033[2Kframe %02d' $i; sleep 0.05; i=$((i+1)); done; exec cat > "$FAKE_ATTACH_LOG"`)
	f := newFixture(t, s)
	if s := f.openTerm(t, "c1"); s.Lines[0] != "ready" {
		t.Fatalf("first line %q, want ready", s.Lines[0])
	}
	f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "1"})
	got := f.screens(t, 2*time.Second)
	if len(got) == 0 {
		t.Fatal("no term.screen")
	}
	if last := got[len(got)-1].screen.Lines[0]; last != "frame 20" {
		t.Errorf("last push shows %q, want frame 20", last)
	}
	for i := 1; i < len(got); i++ {
		if gap := got[i].at.Sub(got[i-1].at); gap < 150*time.Millisecond {
			t.Errorf("pushes %d and %d only %v apart", i-1, i, gap)
		}
	}
	for i := range got {
		n := 0
		for j := i; j < len(got) && got[j].at.Sub(got[i].at) < 990*time.Millisecond; j++ {
			n++
		}
		if n > protocol.TermScreensPerSecond {
			t.Errorf("%d pushes within a second from push %d", n, i)
		}
	}
	t.Logf("%d pushes for 20 changes", len(got))
}

func TestTermStopsPushing(t *testing.T) {
	t.Parallel()
	s := running()
	s.opts = shellAttach(`i=0; while true; do i=$((i+1)); printf '\033[H\033[2Ktick %d' $i; sleep 0.1; done`)
	f := newFixture(t, s)
	f.openTerm(t, "c1")
	if len(f.screens(t, 600*time.Millisecond)) == 0 {
		t.Fatal("no term.screen while the screen changes")
	}
	f.ok(t, "c1", protocol.MethodTermClose, protocol.TermCloseRequest{SessionID: shortID})
	f.screens(t, 300*time.Millisecond) // one may be under way
	if got := f.screens(t, 700*time.Millisecond); len(got) != 0 {
		t.Errorf("%d term.screen after term.close", len(got))
	}
	res := f.call(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "up"})
	if res.Error == nil {
		t.Errorf("key after term.close was accepted")
	}

	f.openTerm(t, "c2")
	f.h.ClientGone("c2")
	f.screens(t, 300*time.Millisecond)
	if got := f.screens(t, 700*time.Millisecond); len(got) != 0 {
		t.Errorf("%d term.screen after the phone went away", len(got))
	}
}

// echoAttach draws a rule and the input box, echoes what is typed into it,
// and on Enter runs onEnter.
func echoAttach(onEnter string) func(*chat.Options) {
	return shellAttach(`printf '────────\r\n❯\302\240'; cr=$(printf '\r'); while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "$cr" ]; then ` + onEnter + `; continue; fi; printf '%s' "$c"; done`)
}

func TestSendPanelCommand(t *testing.T) {
	t.Parallel()
	s := running()
	s.opts = echoAttach(`printf '\033[2J\033[H\033[?25l   Manage MCP servers'`)
	f := newFixture(t, s)
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/mcp"})
	if res.Error != nil {
		t.Fatalf("chat.send /mcp: %+v", res.Error)
	}
	var term protocol.TermScreen
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: shortID}), &term); err != nil {
		t.Fatal(err)
	}
	if term.Lines[0] != "   Manage MCP servers" || term.Cursor {
		t.Errorf("terminal after /mcp: %q cursor %v", term.Lines[0], term.Cursor)
	}
}

func TestSendPanelCommandNotDelivered(t *testing.T) {
	t.Parallel()
	// Enter leaves the command in the input.
	s := running()
	s.opts = echoAttach(`:`)
	f := newFixture(t, s)
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/mcp"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Fatalf("error = %+v, want notDelivered", res.Error)
	}

	// The command never shows in the input: Enter is not pressed.
	s = running()
	s.opts = shellAttach(`printf '────────\r\n❯\302\240'; exec cat > "$FAKE_ATTACH_LOG"`)
	f = newFixture(t, s)
	res = f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/help"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Fatalf("error = %+v, want notDelivered", res.Error)
	}
	if log := f.keyLog(); strings.Contains(log, "\r") {
		t.Errorf("Enter was pressed: %q", log)
	}
}

// A permission prompt that comes up while a message is typed would take
// its Enter and allow the tool: the screen is looked at once more before
// Enter is pressed.
func TestSendLooksAtTheScreenBeforeEnter(t *testing.T) {
	t.Parallel()
	const text = "先看一下 relay 的重连"
	prompt := `\033[2J\033[H\033[?25l Bash command\r\n\r\n   rm -rf build\r\n\r\n Do you want to proceed?\r\n ❯ 1. Yes\r\n   2. No`
	s := running()
	// The prompt comes up as the paste ends, on its second ~.
	s.opts = shellAttach(`printf '────────\r\n❯\302\240'; n=0; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "~" ]; then n=$((n+1)); [ $n -eq 2 ] && printf '` + prompt + `'; fi; done`)
	f := newFixture(t, s)
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: text})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || res.Error.Message != "会话弹出了待确认，消息没有发出" {
		t.Fatalf("error = %+v, want the prompt reported", res.Error)
	}
	log := f.keyLog()
	if !strings.Contains(log, text) || strings.Contains(log, "\r") {
		t.Errorf("keys %q: want the message typed and no Enter", log)
	}
	// The input box is not cleared then: the prompt would take the keys.
	if _, after, _ := strings.Cut(log, text); strings.Contains(after, "\x15") {
		t.Errorf("keys %q: want no Ctrl+U after the message", log)
	}

	// Echoed into the input box with no dialog, the message is sent.
	s = running()
	echo := echoAttach(`:`)
	s.opts = func(o *chat.Options) { echo(o); o.DeliveryTimeout = 300 * time.Millisecond }
	f = newFixture(t, s)
	res = f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: text})
	if res.Error != nil && res.Error.Code != protocol.CodeNotDelivered {
		t.Fatalf("error = %+v", res.Error)
	}
	if log := f.keyLog(); !strings.HasSuffix(log, text+"\x1b[201~\r") {
		t.Errorf("keys %q: want the message pasted, then Enter", log)
	}
}

// A panel or dialog on screen takes typed keys, and its Enter acts on what
// it has selected: nothing is typed then, a message or a panel command.
func TestSendRefusedOffTheInput(t *testing.T) {
	t.Parallel()
	s := running()
	s.opts = shellAttach(`printf '` + strings.ReplaceAll(mcpPanel, "\n", `\r\n`) + `'; exec cat > "$FAKE_ATTACH_LOG"`)
	f := newFixture(t, s)
	for _, text := range []string{"先看一下 relay 的重连", "/mcp"} {
		res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: text})
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || res.Error.Message != "会话停在面板或对话框上，先在终端里关掉再发" {
			t.Errorf("chat.send %q: error %+v", text, res.Error)
		}
	}
	if log := f.keyLog(); log != "" {
		t.Errorf("typed %q into the panel", log)
	}
}
