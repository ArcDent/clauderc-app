//go:build e2e

package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// TestTasks has Claude Code make a task list of three, one in progress,
// and then finish it, with the chat open: chat.tasks follows the task
// directory, and once Claude Code clears the list it says so.
func TestTasks(t *testing.T) {
	s := launch(t, "tasks")
	dir := filepath.Join(s.x.src.ClaudeDir, "tasks", s.sessionID)
	m := s.eventMark()
	s.turnAllowing("用 TaskCreate 工具建三个任务，标题依次是「甲」「乙」「丙」，再用 TaskUpdate 把「甲」设为 in_progress。不要做别的，最后只回复 OK", 3*time.Minute)
	var got string
	waitFor(t, 10*time.Second, "chat.tasks 带三条、甲在进行中", func() bool {
		evs := s.tasksSince(m)
		if len(evs) == 0 {
			return false
		}
		got = taskLine(evs[len(evs)-1].Tasks)
		return got == "甲:in_progress,乙:pending,丙:pending"
	})
	if files := taskFiles(t, dir); files != got {
		t.Errorf("任务目录 %q，chat.tasks %q", files, got)
	}

	m = s.eventMark()
	s.turnAllowing("用 TaskUpdate 把「甲」「乙」「丙」三个任务都设为 completed。不要做别的，最后只回复 DONE", 3*time.Minute)
	waitFor(t, 10*time.Second, "清单清空后收到 tasks: []", func() bool {
		evs := s.tasksSince(m)
		return len(evs) > 0 && len(evs[len(evs)-1].Tasks) == 0
	})
	t.Logf("第二轮收到的 chat.tasks：%v", func() (out []string) {
		for _, ev := range s.tasksSince(m) {
			out = append(out, "["+taskLine(ev.Tasks)+"]")
		}
		return
	}())
}

// turnAllowing sends msg and allows each permission prompt until the turn
// ends.
func (s *session) turnAllowing(msg string, timeout time.Duration) {
	s.t.Helper()
	m := s.mark()
	s.send(msg)
	s.waitUser(msg, m, 15*time.Second)
	answered := map[string]bool{}
	waitFor(s.t, timeout, "这一轮结束", func() bool {
		if p := s.latestPending(); p != nil && p.Kind == protocol.PendingPermission && !answered[p.ToolUseID] {
			answered[p.ToolUseID] = true
			s.x.call(s.t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID, Decision: protocol.DecisionAllow})
		}
		return s.state() == protocol.StateIdle && s.lastIs("assistant")
	})
}

// eventMark is where the events pushed from now on start.
func (s *session) eventMark() int {
	s.x.mu.Lock()
	defer s.x.mu.Unlock()
	return len(s.x.events)
}

// tasksSince returns the session's chat.tasks events pushed since mark.
func (s *session) tasksSince(mark int) []protocol.ChatTasksEvent {
	s.x.mu.Lock()
	defer s.x.mu.Unlock()
	var out []protocol.ChatTasksEvent
	for _, e := range s.x.events[mark:] {
		var ev protocol.ChatTasksEvent
		if e.Method == protocol.EventChatTasks && json.Unmarshal(e.Body, &ev) == nil && ev.SessionID == s.id {
			out = append(out, ev)
		}
	}
	return out
}

func taskLine(tasks []protocol.ChatTask) string {
	var out []string
	for _, t := range tasks {
		out = append(out, t.Subject+":"+string(t.Status))
	}
	return strings.Join(out, ",")
}

// taskFiles reads the task directory as the terminal shows it.
func taskFiles(t *testing.T, dir string) string {
	t.Helper()
	entries, _ := os.ReadDir(dir)
	var tasks []protocol.ChatTask
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		var task protocol.ChatTask
		if err := json.Unmarshal(raw, &task); err != nil {
			t.Fatalf("%s: %v", e.Name(), err)
		}
		tasks = append(tasks, task)
	}
	sort.Slice(tasks, func(i, j int) bool {
		a, _ := strconv.Atoi(tasks[i].ID)
		b, _ := strconv.Atoi(tasks[j].ID)
		return a < b
	})
	return taskLine(tasks)
}
