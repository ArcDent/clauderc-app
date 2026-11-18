package chat_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/protocol"
)

// goAgent runs in transcript/testdata/agents.jsonl; its own transcript and
// meta file are in transcript/testdata/agents/subagents.
const (
	goAgent = "ab360a15e1da6a482"
	goCall  = "chatcmpl-tool-8a7bea8a8b0bea75"
)

// addSubagents appends the sample's Agent calls to the session's
// transcript and puts the subagents' files next to it. It returns the
// subagents directory.
func (f *fixture) addSubagents(t *testing.T) string {
	t.Helper()
	src := filepath.Join("..", "transcript", "testdata")
	calls, err := os.ReadFile(filepath.Join(src, "agents.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	f.appendTranscript(t, string(calls))
	dir := filepath.Join(filepath.Dir(f.transcript), sessionID, "subagents")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"agent-" + goAgent + ".jsonl", "agent-" + goAgent + ".meta.json", "agent-abf8fcd189003562d.meta.json"} {
		raw, err := os.ReadFile(filepath.Join(src, "agents", "subagents", name))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), raw, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func TestOpenSubagent(t *testing.T) {
	f := newFixture(t, setup{status: "busy", state: "working"})
	dir := f.addSubagents(t)

	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: goAgent}), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Items) != 3 || res.HasMore {
		t.Fatalf("items = %+v, hasMore %v", res.Items, res.HasMore)
	}
	if it := res.Items[0]; it.Kind != protocol.ItemNotice || it.Title != "任务" || it.Text != "只读梳理 internal/agent 下未提交的改动，按模块列出要点，不要改代码。" {
		t.Errorf("first item = %+v, want the task as a notice titled 任务", it)
	}
	if it := res.Items[1]; it.Kind != protocol.ItemAssistant || it.Text != "先看一下改了哪些文件。" {
		t.Errorf("second item = %+v", it)
	}
	if it := res.Items[2]; it.Tool == nil || it.Tool.Summary != "运行 git status --short internal/agent" || it.Tool.Result == nil {
		t.Errorf("third item = %+v, want the Bash call with its result", it)
	}
	if res.State != protocol.StateWorking || res.Pending != nil {
		t.Errorf("state %q pending %+v, want working with nothing pending", res.State, res.Pending)
	}

	// New lines of the subagent's transcript are pushed with its id.
	line := `{"type":"assistant","isSidechain":true,"agentId":"` + goAgent + `","message":{"role":"assistant","content":[{"type":"text","text":"改动集中在 chat 和 transcript。"}]}}` + "\n"
	appendFile(t, filepath.Join(dir, "agent-"+goAgent+".jsonl"), line)
	e := f.nextEvent(t, protocol.EventChatItems, 2*time.Second)
	var items protocol.ChatItemsEvent
	if err := json.Unmarshal(e.Body, &items); err != nil {
		t.Fatal(err)
	}
	if items.SessionID != shortID || items.AgentID != goAgent || len(items.Items) != 1 || items.Items[0].Text != "改动集中在 chat 和 transcript。" {
		t.Fatalf("chat.items = %+v", items)
	}

	// Once it finished, the chat turns idle.
	if err := os.WriteFile(filepath.Join(dir, "agent-"+goAgent+".meta.json"), []byte(`{"toolUseId":"`+goCall+`","finished":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	e = f.nextEvent(t, protocol.EventChatState, 2*time.Second)
	var state protocol.ChatStateEvent
	if err := json.Unmarshal(e.Body, &state); err != nil {
		t.Fatal(err)
	}
	if state.AgentID != goAgent || state.State != protocol.StateIdle {
		t.Fatalf("chat.state = %+v, want idle for %s", state, goAgent)
	}

	// The main chat is not opened by it, and closing it stops the pushes.
	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID, AgentID: goAgent})
	appendFile(t, filepath.Join(dir, "agent-"+goAgent+".jsonl"), line)
	f.appendTranscript(t, userLine("main chat is not open"))
	if e, ok := f.maybeEvent(protocol.EventChatItems, time.Second); ok {
		t.Fatalf("chat.items after close: %s", e.Body)
	}
}

func TestOpenSubagentRejects(t *testing.T) {
	f := newFixture(t, setup{})
	f.addSubagents(t)
	before := len(fakeclaude.Calls(t, f.claudeLog))
	for _, id := range []string{"../" + goAgent, "a/b", "..", "agent-" + goAgent + ".jsonl"} {
		res := f.call(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: id})
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
			t.Errorf("agentId %q: error %+v, want invalidParams", id, res.Error)
		}
	}
	if n := len(fakeclaude.Calls(t, f.claudeLog)); n != before {
		t.Errorf("claude ran %d times for bad agent ids, want none", n-before)
	}
	res := f.call(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: "a0000000000000000"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotFound {
		t.Errorf("unknown subagent: error %+v, want notFound", res.Error)
	}
}

func appendFile(t *testing.T, path, s string) {
	t.Helper()
	fh, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fh.Close()
	if _, err := fh.WriteString(s); err != nil {
		t.Fatal(err)
	}
}
