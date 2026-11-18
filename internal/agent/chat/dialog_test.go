package chat_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/chat"
	"clauderc/internal/protocol"
)

// mcpDialog is the MCP approval dialog captured from Claude Code 2.1.326.
func mcpDialog(t *testing.T) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "attach", "testdata", "mcp-dialog.txt"))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// dialogPending waits for chat.pending and requires a dialog.
func dialogPending(t *testing.T, f *fixture) protocol.Pending {
	t.Helper()
	e := f.nextEvent(t, protocol.EventChatPending, 5*time.Second)
	var ev protocol.ChatPendingEvent
	if err := json.Unmarshal(e.Body, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Pending == nil || ev.Pending.Kind != protocol.PendingDialog {
		t.Fatalf("chat.pending = %s, want a dialog", e.Body)
	}
	return *ev.Pending
}

// A blocked session with nothing pending in its transcript and the MCP
// dialog on screen gets a dialog pending prompt and the pending state.
func TestDialogIsPushedAsPending(t *testing.T) {
	f := newFixture(t, setup{status: "waiting", state: "blocked", screen: mcpDialog(t)})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	p := dialogPending(t, f)
	if len(p.Questions) != 1 {
		t.Fatalf("questions %+v", p.Questions)
	}
	q := p.Questions[0]
	if q.Question != "New MCP server found in this project: probe" || q.MultiSelect || len(q.Options) != 3 ||
		q.Options[0].Label != "Use this MCP server" || q.Options[2].Label != "Continue without using this MCP server" {
		t.Errorf("question %+v", q)
	}
	// chat.state went out just before chat.pending; a reopen shows both.
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if res.State != protocol.StatePending || res.Pending == nil || res.Pending.ToolUseID != p.ToolUseID {
		t.Errorf("reopen: state %q pending %+v", res.State, res.Pending)
	}
	if res.UnknownDialog {
		t.Errorf("a known dialog is flagged unknown")
	}
}

// A blocked session showing a panel, with nothing pending in its
// transcript, is flagged; the input box is not.
func TestUnknownDialogIsFlagged(t *testing.T) {
	panel := "\x1b[?25l   Manage MCP servers\n   8 servers\n\n   ❯ ✔ chrome-devtools     26 tools\n\n   ↑/↓ to navigate · Enter to confirm · Esc to cancel"
	f := newFixture(t, setup{status: "waiting", state: "blocked", screen: panel})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	deadline := time.Now().Add(5 * time.Second)
	for {
		e := f.nextEvent(t, protocol.EventChatState, time.Until(deadline))
		var ev protocol.ChatStateEvent
		if err := json.Unmarshal(e.Body, &ev); err != nil {
			t.Fatal(err)
		}
		if ev.UnknownDialog {
			if ev.State == protocol.StatePending {
				t.Errorf("state %q, want the session's own state", ev.State)
			}
			break
		}
	}

	input := "──────────\n❯\u00a0\n──────────\n  ⏸ manual mode on"
	f = newFixture(t, setup{status: "waiting", state: "blocked", screen: input})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	time.Sleep(2 * time.Second)
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if res.UnknownDialog {
		t.Errorf("the input box is flagged as an unknown dialog")
	}
	for len(f.events) > 0 {
		if e := <-f.events; e.Method == protocol.EventChatState && strings.Contains(string(e.Body), "unknownDialog") {
			t.Errorf("chat.state flagged the input box: %s", e.Body)
		}
	}
}

// Picking option 2 with the cursor on option 3 moves up once and confirms.
func TestAnswerDialogMovesCursor(t *testing.T) {
	f := newFixture(t, setup{status: "waiting", state: "blocked", screen: mcpDialog(t)})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	p := dialogPending(t, f)
	f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{
		SessionID: shortID, ToolUseID: p.ToolUseID, Answers: []protocol.Answer{{Selected: []int{1}}},
	})
	// The attach starts with Ctrl+L to read the status line.
	if got, want := strings.TrimPrefix(f.keyLog(), "\f"), "\x1b[A\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
}

// A stale id, a custom answer or two picks type nothing.
func TestAnswerDialogRefused(t *testing.T) {
	f := newFixture(t, setup{status: "waiting", state: "blocked", screen: mcpDialog(t)})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	p := dialogPending(t, f)
	for name, c := range map[string]struct {
		req  protocol.ChatAnswerRequest
		code protocol.ErrorCode
	}{
		"stale id":   {protocol.ChatAnswerRequest{ToolUseID: "dialog-000000000000", Answers: []protocol.Answer{{Selected: []int{0}}}}, protocol.CodePendingChanged},
		"custom":     {protocol.ChatAnswerRequest{ToolUseID: p.ToolUseID, Answers: []protocol.Answer{{Custom: "随便"}}}, protocol.CodeInvalidParams},
		"two picks":  {protocol.ChatAnswerRequest{ToolUseID: p.ToolUseID, Answers: []protocol.Answer{{Selected: []int{0, 1}}}}, protocol.CodeInvalidParams},
		"no option":  {protocol.ChatAnswerRequest{ToolUseID: p.ToolUseID, Answers: []protocol.Answer{{Selected: []int{3}}}}, protocol.CodeInvalidParams},
		"a decision": {protocol.ChatAnswerRequest{ToolUseID: p.ToolUseID, Decision: protocol.DecisionAllow}, protocol.CodeInvalidParams},
	} {
		c.req.SessionID = shortID
		res := f.call(t, "c1", protocol.MethodChatAnswer, c.req)
		if res.Error == nil || res.Error.Code != c.code {
			t.Errorf("%s: error %+v, want %s", name, res.Error, c.code)
		}
	}
	if got := strings.TrimPrefix(f.keyLog(), "\f"); got != "" {
		t.Errorf("keys %q, want none", got)
	}
}

// Without blocked the dialog text on screen is not read.
func TestDialogOnlyWhileBlocked(t *testing.T) {
	f := newFixture(t, setup{status: "idle", state: "done", screen: mcpDialog(t)})
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1500 * time.Millisecond)
	if res.Pending != nil || res.State != protocol.StateIdle {
		t.Errorf("open: pending %+v state %q", res.Pending, res.State)
	}
	if e, ok := f.maybeEvent(protocol.EventChatPending, 500*time.Millisecond); ok {
		t.Errorf("chat.pending pushed: %s", e.Body)
	}
}

// Blocked with a plain prompt on screen stays idle.
func TestBlockedWithoutDialogStaysIdle(t *testing.T) {
	f := newFixture(t, setup{status: "waiting", state: "blocked", replay: "mode-default"})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	if e, ok := f.maybeEvent(protocol.EventChatPending, 2*time.Second); ok {
		t.Errorf("chat.pending pushed: %s", e.Body)
	}
}

// mcpPanel is the /mcp panel as Claude Code 2.1.326 draws it: input box
// gone, cursor hidden.
const mcpPanel = "\x1b[?25l   Manage MCP servers\n   8 servers\n\n   ❯ ✔ chrome-devtools     26 tools\n\n   ↑/↓ to navigate · Enter to confirm · Esc to cancel"

// A panel left open: Claude Code reports the session done, not blocked,
// and the phone is told all the same.
func TestUnknownDialogPanelThoughNotBlocked(t *testing.T) {
	f := newFixture(t, setup{status: "idle", state: "done", screen: mcpPanel})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	deadline := time.Now().Add(5 * time.Second)
	for {
		var ev protocol.ChatStateEvent
		if err := json.Unmarshal(f.nextEvent(t, protocol.EventChatState, time.Until(deadline)).Body, &ev); err != nil {
			t.Fatal(err)
		}
		if ev.UnknownDialog {
			break
		}
	}
}

// One poll without the input box is not enough: it may be a frame caught
// mid-draw. Here the panel is gone again before the second poll.
func TestUnknownDialogNeedsTwoScreens(t *testing.T) {
	s := setup{status: "idle", state: "done"}
	panel := strings.ReplaceAll(mcpPanel, "\n", `\r\n`)
	attachOpts := shellAttach(`printf '` + panel + `'; head -c 1 >/dev/null; printf '\033[2J\033[H──────────\r\n❯\302\240\r\n──────────\033[?25h'; exec cat > "$FAKE_ATTACH_LOG"`)
	s.opts = func(o *chat.Options) {
		attachOpts(o)
		o.StateInterval = 2 * time.Second
	}
	f := newFixture(t, s)
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	// The first poll, 2 seconds in, sees the panel; the second, at 4, the input.
	time.Sleep(3 * time.Second)
	f.ok(t, "c1", protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: shortID})
	f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "1"})
	time.Sleep(3 * time.Second)
	for len(f.events) > 0 {
		if e := <-f.events; e.Method == protocol.EventChatState && strings.Contains(string(e.Body), "unknownDialog") {
			t.Fatalf("flagged after one screen off the input: %s", e.Body)
		}
	}
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if res.UnknownDialog {
		t.Errorf("flagged after one screen off the input")
	}
}
