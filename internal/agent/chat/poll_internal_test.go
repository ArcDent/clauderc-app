package chat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/protocol"
)

// An open chat with the default poll interval looks at its files often but
// reads only those that changed: the transcript when it grew, a meta file
// when it was written.
func TestPollReadsOnlyChangedFiles(t *testing.T) {
	dir := t.TempDir()
	const sid = "8f0c2a5e-0000-4000-8000-0000000000d1"
	proj := filepath.Join(dir, "projects", "-w-x")
	sub := filepath.Join(proj, sid, "subagents")
	if err := os.MkdirAll(sub, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(proj, sid+".jsonl")
	call := `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_bg","name":"Agent","input":{"description":"查一下"}}]}}` + "\n"
	if err := os.WriteFile(path, []byte(call), 0o600); err != nil {
		t.Fatal(err)
	}
	meta := filepath.Join(sub, "agent-a1b2c3d4e5f60718.meta.json")
	if err := os.WriteFile(meta, []byte(`{"agentType":"Explore","toolUseId":"toolu_bg"}`), 0o600); err != nil {
		t.Fatal(err)
	}

	events := make(chan protocol.Envelope, 64)
	h := New(Options{Source: discovery.Source{ClaudeDir: dir}, Push: agent.PusherFunc(func(e protocol.Envelope) { events <- e })})
	if h.opts.PollInterval != 200*time.Millisecond {
		t.Fatalf("default poll interval = %v", h.opts.PollInterval)
	}
	h.mu.Lock()
	s := h.newSession(discovery.Target{ID: "abc12345", SessionID: sid})
	s.clients["phone"] = true
	h.chats[s.id] = s
	h.mu.Unlock()
	t.Cleanup(h.Close)

	f := s.follower
	if f == nil {
		t.Fatal("no follower")
	}
	before := f.Reads()
	time.Sleep(2 * time.Second)
	if got := f.Reads(); got != before {
		t.Errorf("reads over 2 s of unchanged files = %d, want 0", got-before)
	}

	appendLine(t, path, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"新回复"}]}}`+"\n")
	items := waitItems(t, events, 500*time.Millisecond, func(it protocol.ChatItem) bool { return it.Text == "新回复" })
	if items == 0 {
		t.Error("the appended reply was not pushed within 500 ms")
	}

	if err := os.WriteFile(meta, []byte(`{"agentType":"Explore","toolUseId":"toolu_bg","finished":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	ended := waitItems(t, events, 500*time.Millisecond, func(it protocol.ChatItem) bool {
		return it.Tool != nil && it.Tool.Agent != nil && it.Tool.ToolUseID == "toolu_bg" && !it.Tool.Agent.Running
	})
	if ended == 0 {
		t.Error("the finished subagent was not pushed within 500 ms")
	}
}

func appendLine(t *testing.T, path, line string) {
	t.Helper()
	fh, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fh.Close()
	if _, err := fh.WriteString(line); err != nil {
		t.Fatal(err)
	}
}

// waitItems counts the chat.items pushed within timeout holding an item
// that matches; it returns at the first match.
func waitItems(t *testing.T, events chan protocol.Envelope, timeout time.Duration, match func(protocol.ChatItem) bool) int {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case e := <-events:
			if e.Method != protocol.EventChatItems {
				continue
			}
			var body protocol.ChatItemsEvent
			if err := json.Unmarshal(e.Body, &body); err != nil {
				t.Fatal(err)
			}
			for _, it := range body.Items {
				if match(it) {
					return 1
				}
			}
		case <-deadline:
			return 0
		}
	}
}
