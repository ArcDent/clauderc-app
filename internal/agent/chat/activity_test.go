package chat_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/chat"
	"clauderc/internal/protocol"
)

// workingScreen is Claude Code 2.1.327 drawing a reply, its status line
// below (test/e2e/claude TestWorkingScreen).
func workingScreen(t *testing.T) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "working-tokens.txt"))
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSuffix(string(b), "\n")
}

// nextState waits up to timeout for a chat.state event that matches.
func (f *fixture) nextState(t *testing.T, timeout time.Duration, match func(protocol.ChatStateEvent) bool) protocol.ChatStateEvent {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		var ev protocol.ChatStateEvent
		if err := json.Unmarshal(f.nextEvent(t, protocol.EventChatState, time.Until(deadline)).Body, &ev); err != nil {
			t.Fatal(err)
		}
		if match(ev) {
			return ev
		}
	}
}

// While the session works, chat.state carries the status line and the
// reply drawn so far; the reply goes once the transcript has it.
func TestActivityWhileWorking(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "busy", state: "working", screen: workingScreen(t)})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	ev := f.nextState(t, 5*time.Second, func(e protocol.ChatStateEvent) bool { return e.Activity != "" })
	if ev.Activity != "Whirring… (20s · ↓ 2.4k tokens)" {
		t.Errorf("activity = %q", ev.Activity)
	}
	if !strings.HasPrefix(ev.Partial, "终端模拟器像一台很窄的显示器") {
		t.Errorf("partial = %q", ev.Partial)
	}

	f.appendTranscript(t, assistantLine("终端模拟器像一台很窄的显示器：它从**伪终端**收到一串字节，再把这些字节翻译成屏幕上排列整齐的字符。"))
	ev = f.nextState(t, 3*time.Second, func(e protocol.ChatStateEvent) bool { return e.Partial == "" })
	if ev.Activity == "" {
		t.Errorf("activity dropped with the partial reply: %+v", ev)
	}
}

// An idle session, or one without an attach, has no activity.
func TestNoActivity(t *testing.T) {
	t.Parallel()
	for name, s := range map[string]setup{
		"idle": {status: "idle", state: "done", screen: workingScreen(t)},
		"no attach": {status: "busy", state: "working", opts: func(o *chat.Options) {
			o.Attach = attach.Options{Command: func(string) []string { return []string{"/usr/bin/false"} }}
		}},
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t, s)
			var res protocol.ChatOpenResponse
			if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
				t.Fatal(err)
			}
			if res.Activity != "" || res.Partial != "" {
				t.Errorf("open: activity %q partial %q", res.Activity, res.Partial)
			}
			deadline := time.After(2500 * time.Millisecond)
			for {
				select {
				case e := <-f.events:
					if e.Method == protocol.EventChatState && (strings.Contains(string(e.Body), `"activity"`) || strings.Contains(string(e.Body), `"partial"`)) {
						t.Fatalf("chat.state with activity: %s", e.Body)
					}
				case <-deadline:
					return
				}
			}
		})
	}
}
