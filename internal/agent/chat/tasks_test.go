package chat_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// taskDir makes the task directory of Claude Code session id in the
// fixture's Claude directory.
func (f *fixture) taskDir(t *testing.T, id string) string {
	t.Helper()
	dir := filepath.Join(f.sample, "tasks", id)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	return dir
}

func putTask(t *testing.T, dir, id, subject, status string) {
	t.Helper()
	body := fmt.Sprintf(`{"id":%q,"subject":%q,"description":"","status":%q,"blocks":[],"blockedBy":[]}`, id, subject, status)
	if err := os.WriteFile(filepath.Join(dir, id+".json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func taskText(tasks []protocol.ChatTask) string {
	var out []string
	for _, t := range tasks {
		out = append(out, t.Subject+":"+string(t.Status))
	}
	return strings.Join(out, ",")
}

func (f *fixture) tasksEvent(t *testing.T, within time.Duration) protocol.ChatTasksEvent {
	t.Helper()
	e := f.nextEvent(t, protocol.EventChatTasks, within)
	if !strings.Contains(string(e.Body), `"tasks":[`) {
		t.Errorf("tasks left out: %s", e.Body)
	}
	var ev protocol.ChatTasksEvent
	if err := json.Unmarshal(e.Body, &ev); err != nil {
		t.Fatal(err)
	}
	return ev
}

func TestChatTasks(t *testing.T) {
	f := newFixture(t, setup{})
	dir := f.taskDir(t, sessionID)
	putTask(t, dir, "1", "甲", "in_progress")
	putTask(t, dir, "2", "乙", "pending")
	putTask(t, dir, "3", "丙", "pending")
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if got := taskText(res.Tasks); got != "甲:in_progress,乙:pending,丙:pending" {
		t.Errorf("chat.open tasks %q", got)
	}

	putTask(t, dir, "1", "甲", "completed")
	if ev := f.tasksEvent(t, time.Second); ev.SessionID != shortID || taskText(ev.Tasks) != "甲:completed,乙:pending,丙:pending" {
		t.Errorf("after a change %+v", ev)
	}
	if e, ok := f.maybeEvent(protocol.EventChatTasks, 1500*time.Millisecond); ok {
		t.Errorf("nothing changed, still pushed %s", e.Body)
	}

	for _, id := range []string{"1", "2", "3"} {
		if err := os.Remove(filepath.Join(dir, id+".json")); err != nil {
			t.Fatal(err)
		}
	}
	if ev := f.tasksEvent(t, time.Second); len(ev.Tasks) != 0 {
		t.Errorf("all files gone %+v", ev)
	}

	// /clear starts another Claude Code session, with a list of its own.
	const next = "8f0c2a5e-0000-4000-8000-0000000000c2"
	putTask(t, f.taskDir(t, next), "1", "清空后的任务", "pending")
	f.writeAgents(t, "agents.json", f.agent(shortID, next, "idle", "done", "7"))
	if ev := f.tasksEvent(t, 3*time.Second); taskText(ev.Tasks) != "清空后的任务:pending" {
		t.Errorf("after /clear %+v", ev)
	}
}

// A subagent's chat shows no task list.
func TestSubagentChatHasNoTasks(t *testing.T) {
	f := newFixture(t, setup{status: "busy", state: "working"})
	f.addSubagents(t)
	dir := f.taskDir(t, sessionID)
	putTask(t, dir, "1", "甲", "in_progress")
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: goAgent}), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Tasks) != 0 {
		t.Errorf("subagent chat.open tasks %+v", res.Tasks)
	}
	putTask(t, dir, "1", "甲", "completed")
	if e, ok := f.maybeEvent(protocol.EventChatTasks, 1500*time.Millisecond); ok {
		t.Errorf("subagent chat got %s", e.Body)
	}
}
