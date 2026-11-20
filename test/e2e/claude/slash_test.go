//go:build e2e

package claude

import (
	"context"
	"regexp"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// slashEntry is one line of Claude Code's slash command popup.
type slashEntry struct {
	name, description string
}

// popupCursor is the popup entry under the cursor; the input line "❯ /"
// has nothing after the slash, so it never matches.
var popupCursor = regexp.MustCompile(`^(\s*)❯ /(\S+)(\s{2,})(.*)$`)

// readCursor returns the entry under the popup cursor with its wrapped
// description lines joined.
func readCursor(screen string) (slashEntry, bool) {
	lines := strings.Split(screen, "\n")
	for i, l := range lines {
		m := popupCursor.FindStringSubmatch(l)
		if m == nil {
			continue
		}
		col := len([]rune(l)) - len([]rune(m[4]))
		desc := []string{strings.TrimSpace(m[4])}
		for _, next := range lines[i+1:] {
			r := []rune(next)
			if len(r) <= col || strings.TrimSpace(string(r[:col])) != "" {
				break
			}
			desc = append(desc, strings.TrimSpace(string(r[col:])))
		}
		return slashEntry{name: m[2], description: strings.Join(desc, " ")}, true
	}
	return slashEntry{}, false
}

// slashPopup types "/" into the session's attach, walks the popup with
// the down arrow until it wraps around, and clears the input again.
func slashPopup(t *testing.T, s *session) []slashEntry {
	t.Helper()
	pool := attach.NewPool(attach.Options{Command: func(id string) []string { return []string{s.x.src.ClaudePath, "attach", id} }, Linger: time.Second})
	defer pool.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	conn, err := pool.Acquire(ctx, s.id)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Release(s.id)
	var entries []slashEntry
	err = conn.Do(ctx, func(term *attach.Term) error {
		defer func() {
			_ = term.Send([]attach.Step{{Keys: []byte("\x1b"), Wait: 500 * time.Millisecond}, {Keys: []byte("\x15"), Wait: 500 * time.Millisecond}})
		}()
		if err := term.Send([]attach.Step{{Keys: []byte("/"), Wait: time.Second}}); err != nil {
			return err
		}
		var cur slashEntry
		if !term.WaitFor(func(sc string) bool { e, ok := readCursor(sc); cur = e; return ok }, 5*time.Second) {
			t.Fatalf("没有出现斜杠命令弹窗：\n%s", term.Screen())
		}
		seen := map[string]bool{}
		for len(entries) < 500 && !seen[cur.name] {
			seen[cur.name] = true
			entries = append(entries, cur)
			prev := cur.name
			if err := term.Send([]attach.Step{{Keys: []byte("\x1b[B"), Wait: 60 * time.Millisecond}}); err != nil {
				return err
			}
			if !term.WaitFor(func(sc string) bool { e, ok := readCursor(sc); cur = e; return ok && e.name != prev }, 3*time.Second) {
				t.Fatalf("按下方向键后光标没动，停在 /%s", prev)
			}
			term.WaitQuiet(80*time.Millisecond, time.Second)
			cur, _ = readCursor(term.Screen())
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return entries
}

// The popup of a --safe-mode session lists Claude Code's own commands
// only; each is offered on the phone or deliberately left out, so an
// upgrade that adds a command fails here. /reload-skills then runs from
// the phone and the input comes back.
func TestSlashList(t *testing.T) {
	s := launchIn(t, "slash_list", safe)
	known := map[string]bool{}
	for _, name := range transcript.UnofferedCommands {
		known[name] = true
	}
	offered := map[string]bool{}
	for _, c := range append(append([]protocol.Command{}, transcript.BuiltinCommands...), transcript.PanelCommands...) {
		known[c.Name] = true
		offered[c.Name] = true
	}
	// Claude Code's own skills reach the phone through the transcript's
	// skill listing, like any other skill.
	var listed protocol.ChatCommandsResponse
	s.x.must(t, protocol.MethodChatCommands, protocol.ChatCommandsRequest{SessionID: s.id}, &listed)
	for _, c := range listed.Commands {
		known[c.Name] = true
	}
	popup := map[string]bool{}
	for _, e := range slashPopup(t, s) {
		popup[e.name] = true
		if !known[e.name] {
			t.Errorf("新的内置命令 /%s：%s；加进 BuiltinCommands、PanelCommands 或 UnofferedCommands", e.name, e.description)
		}
	}
	for name := range offered {
		if !popup[name] {
			t.Errorf("BuiltinCommands 或 PanelCommands 里的 /%s 不在弹窗里", name)
		}
	}
	for _, name := range transcript.UnofferedCommands {
		if !popup[name] {
			t.Logf("UnofferedCommands 里的 /%s 不在弹窗里", name)
		}
	}

	s.send("/reload-skills")
	var out string
	waitFor(t, 30*time.Second, "/reload-skills 的输出", func() bool {
		var res protocol.ChatOpenResponse
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
		for _, it := range res.Items {
			if it.Tool != nil && it.Tool.ToolName == "/reload-skills" && it.Tool.Result != nil {
				out = it.Tool.Result.Content
				return out != ""
			}
		}
		return false
	})
	t.Logf("/reload-skills 输出：%q", out)
	pool := attach.NewPool(attach.Options{Command: func(id string) []string { return []string{s.x.src.ClaudePath, "attach", id} }, Linger: time.Second})
	defer pool.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := pool.Acquire(ctx, s.id)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Release(s.id)
	_ = conn.Do(ctx, func(term *attach.Term) error {
		ok := term.WaitFor(func(sc string) bool {
			_, popupOpen := readCursor(sc)
			_, mode := attach.ParseMode(sc)
			return mode && !popupOpen && emptyPrompt(sc)
		}, 10*time.Second)
		if !ok {
			t.Errorf("输入框没有回到可输入状态：\n%s", term.Screen())
		}
		return nil
	})
}

// emptyPrompt reports whether the screen has the input line with nothing
// typed; the cursor cell is a no-break space, hence TrimSpace.
func emptyPrompt(screen string) bool {
	for _, l := range strings.Split(screen, "\n") {
		if strings.TrimSpace(l) == "❯" {
			return true
		}
	}
	return false
}
