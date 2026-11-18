package transcript_test

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// testdata/agents.jsonl is a sanitized piece of a real transcript: two
// background Agent calls, the second ended by its task notification, and
// testdata/agents/subagents holds their meta files, neither finished.
const (
	goCall      = "chatcmpl-tool-8a7bea8a8b0bea75"
	goAgent     = "ab360a15e1da6a482"
	androidCall = "chatcmpl-tool-ac9ffe122df77f2c"
	androidID   = "abf8fcd189003562d"
)

// copyAgents copies the sample into a temp dir and returns the transcript
// path; its subagents directory sits next to it.
func copyAgents(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "agents.jsonl")
	copyFile(t, "testdata/agents.jsonl", path)
	sub := filepath.Join(dir, "agents", "subagents")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{goAgent, androidID} {
		name := "agent-" + id + ".meta.json"
		copyFile(t, filepath.Join("testdata/agents/subagents", name), filepath.Join(sub, name))
	}
	return path
}

func copyFile(t *testing.T, from, to string) {
	t.Helper()
	data, err := os.ReadFile(from)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(to, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func writeMeta(t *testing.T, path, id, body string) {
	t.Helper()
	p := filepath.Join(filepath.Dir(path), "agents", "subagents", "agent-"+id+".meta.json")
	if err := os.WriteFile(p, []byte(body+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
}

// agentOf returns the subagent of the tool item of call.
func agentOf(t *testing.T, items []transcript.Item, call string) protocol.Subagent {
	t.Helper()
	for _, it := range items {
		if it.Tool != nil && it.Tool.ToolUseID == call {
			if it.Tool.Agent == nil {
				t.Fatalf("tool item %s has no agent", call)
			}
			return *it.Tool.Agent
		}
	}
	t.Fatalf("no tool item %s in %d items", call, len(items))
	return protocol.Subagent{}
}

func TestAgentCallsOfSample(t *testing.T) {
	f, err := transcript.Open(copyAgents(t))
	if err != nil {
		t.Fatal(err)
	}
	items, _ := f.Page(0, 100)
	// The time of the record with the Go agent's call.
	started, _ := time.Parse(time.RFC3339, "2026-11-17T07:59:55.751Z")
	if got, want := agentOf(t, items, goCall), (protocol.Subagent{
		ID: goAgent, Type: "general-purpose", Description: "梳理 Go 端未提交改动", Running: true, StartedAt: started,
	}); !got.StartedAt.Equal(want.StartedAt) || got.ID != want.ID || got.Type != want.Type || got.Description != want.Description || !got.Running {
		t.Errorf("Go agent = %+v, want %+v", got, want)
	}
	if a := agentOf(t, items, androidCall); a.ID != androidID || a.Description != "梳理 Android 端未提交改动" || a.Running {
		t.Errorf("Android agent = %+v, want id %s and ended by its notification", a, androidID)
	}
}

func TestAgentEndsByMeta(t *testing.T) {
	for _, flag := range []string{`"finished":true`, `"stoppedByUser":true`} {
		t.Run(flag, func(t *testing.T) {
			path := copyAgents(t)
			f, err := transcript.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			writeMeta(t, path, goAgent, `{"agentType":"general-purpose","toolUseId":"`+goCall+`",`+flag+`}`)
			changed, err := f.Poll()
			if err != nil {
				t.Fatal(err)
			}
			if a := agentOf(t, changed, goCall); a.Running {
				t.Errorf("after %s the Go agent still runs", flag)
			}
		})
	}
}

func TestAgentEndsByNotification(t *testing.T) {
	path := copyAgents(t)
	f, err := transcript.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	// An idle session gets the notification as a user record.
	appendTo(t, path, `{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":`+
		jsonString("<task-notification>\n<task-id>"+goAgent+"</task-id>\n<tool-use-id>"+goCall+"</tool-use-id>\n<status>completed</status>\n<summary>Agent \"梳理 Go 端未提交改动\" completed</summary>\n</task-notification>")+`}}`+"\n")
	changed, err := f.Poll()
	if err != nil {
		t.Fatal(err)
	}
	if a := agentOf(t, changed, goCall); a.Running {
		t.Errorf("Go agent still runs after its notification")
	}
}

func TestAgentForeground(t *testing.T) {
	call := assistantTool("toolu_fg", "Agent", `{"description":"查一下","subagent_type":"Explore"}`)
	tr := parseLines(t, call)
	if a := *tr.Items()[0].Tool.Agent; !a.Running || a.Type != "Explore" || a.ID != "" {
		t.Errorf("before its result: %+v, want running Explore without id", a)
	}
	// A foreground call's result is the subagent's answer: it ended.
	tr = parseLines(t, call, `{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_fg","content":"查到了"}]},"toolUseResult":{"status":"completed","agentId":"a0123456789abcdef"}}`)
	if a := *tr.Items()[0].Tool.Agent; a.Running || a.ID != "a0123456789abcdef" {
		t.Errorf("after its result: %+v, want ended with its id", a)
	}
	// An id that is not one is never taken.
	tr = parseLines(t, call, `{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_fg","content":"x"}]},"toolUseResult":{"status":"async_launched","agentId":"../etc"}}`)
	if a := *tr.Items()[0].Tool.Agent; a.ID != "" || !a.Running {
		t.Errorf("bad agentId: %+v, want no id, still running", a)
	}
}

func TestAgentIDFromMeta(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "s.jsonl")
	if err := os.WriteFile(path, []byte(assistantTool("toolu_bg", "Agent", `{"description":"查一下"}`)+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(dir, "s", "subagents"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "s", "subagents", "agent-a1b2c3d4e5f60718.meta.json"), []byte(`{"agentType":"Explore","toolUseId":"toolu_bg"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	f, err := transcript.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	items, _ := f.Page(0, 10)
	if a := agentOf(t, items, "toolu_bg"); a.ID != "a1b2c3d4e5f60718" || a.Type != "Explore" || !a.Running {
		t.Errorf("agent = %+v, want id and type from its meta file", a)
	}
}

func TestAgentWatch(t *testing.T) {
	path := copyAgents(t)
	w := transcript.WatchAgents(path)
	running, err := w.Running()
	if err != nil {
		t.Fatal(err)
	}
	if len(running) != 1 || running[0].ID != goAgent || running[0].Description != "梳理 Go 端未提交改动" {
		t.Fatalf("running = %+v, want only the Go agent", running)
	}
	writeMeta(t, path, goAgent, `{"toolUseId":"`+goCall+`","finished":true}`)
	if running, _ = w.Running(); len(running) != 0 {
		t.Errorf("after finished: running = %+v, want none", running)
	}
	if ok, _ := w.IsRunning(goAgent); ok {
		t.Errorf("IsRunning(%s) after finished", goAgent)
	}

	// A session without Agent calls or a subagents directory has none.
	if running, err := transcript.WatchAgents("testdata/session.jsonl").Running(); err != nil || len(running) != 0 {
		t.Errorf("session.jsonl: running = %+v, %v, want none", running, err)
	}
}

func TestValidAgentID(t *testing.T) {
	for id, want := range map[string]bool{
		goAgent: true, "": false, "../x": false, "a/b": false, "..": false, "a.b": false,
	} {
		if got := transcript.ValidAgentID(id); got != want {
			t.Errorf("ValidAgentID(%q) = %v, want %v", id, got, want)
		}
	}
}

func assistantTool(id, name, input string) string {
	return `{"type":"assistant","timestamp":"2026-11-17T08:00:00.000Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"` + id + `","name":"` + name + `","input":` + input + `}]}}`
}
