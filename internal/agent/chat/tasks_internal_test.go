package chat

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

func writeTask(t *testing.T, dir, id, body string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, id+".json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func task(id, subject, status string) string {
	return fmt.Sprintf(`{"id":%q,"subject":%q,"description":%q,"activeForm":"正在%s","status":%q,"blocks":[],"blockedBy":[]}`, id, subject, subject, subject, status)
}

func ids(tasks []protocol.ChatTask) string {
	var out []string
	for _, t := range tasks {
		out = append(out, t.ID+":"+string(t.Status))
	}
	return strings.Join(out, ",")
}

func TestTaskList(t *testing.T) {
	dir := t.TempDir()
	var l taskList
	if tasks, _ := l.read(filepath.Join(dir, "none")); tasks == nil || len(tasks) != 0 {
		t.Errorf("no directory: %v", tasks)
	}

	// Ordered by number, Claude Code's own files and internal tasks left out.
	writeTask(t, dir, "10", task("10", "丙", "pending"))
	writeTask(t, dir, "2", task("2", "甲", "in_progress"))
	writeTask(t, dir, "3", `{"id":"3","subject":"乙","status":"pending"}`)
	writeTask(t, dir, "4", `{"id":"4","subject":"内部","status":"pending","metadata":{"_internal":true}}`)
	for _, name := range []string{".lock", ".highwatermark"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("3"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	tasks, changed := l.read(dir)
	if ids(tasks) != "2:in_progress,3:pending,10:pending" || !changed {
		t.Fatalf("tasks %s, changed %v", ids(tasks), changed)
	}
	if tasks[0].ActiveForm != "正在甲" || tasks[1].ActiveForm != "" || tasks[1].Subject != "乙" {
		t.Errorf("fields %+v", tasks)
	}
	if _, changed := l.read(dir); changed {
		t.Errorf("read again unchanged: changed")
	}

	// A file halfway written keeps what it held.
	writeTask(t, dir, "2", `{"id":"2","subject":"甲","sta`)
	if tasks, changed := l.read(dir); ids(tasks) != "2:in_progress,3:pending,10:pending" || changed {
		t.Errorf("half written: %s, changed %v", ids(tasks), changed)
	}
	writeTask(t, dir, "2", task("2", "甲", "completed"))
	if tasks, changed := l.read(dir); ids(tasks) != "2:completed,3:pending,10:pending" || !changed {
		t.Errorf("written: %s, changed %v", ids(tasks), changed)
	}
}

// Claude Code deletes the files of a list all completed one by one: the
// list is taken as cleared at the first one gone.
func TestTaskListClearing(t *testing.T) {
	dir := t.TempDir()
	var l taskList
	for _, id := range []string{"1", "2", "3"} {
		writeTask(t, dir, id, task(id, "任务"+id, "completed"))
	}
	if tasks, _ := l.read(dir); ids(tasks) != "1:completed,2:completed,3:completed" {
		t.Fatalf("all done %s", ids(tasks))
	}
	for _, id := range []string{"1", "2"} {
		if err := os.Remove(filepath.Join(dir, id+".json")); err != nil {
			t.Fatal(err)
		}
		if tasks, _ := l.read(dir); len(tasks) != 0 {
			t.Errorf("after %s.json went: %s", id, ids(tasks))
		}
	}
	// A new list after it shows.
	writeTask(t, dir, "4", task("4", "新任务", "pending"))
	if tasks, _ := l.read(dir); ids(tasks) != "3:completed,4:pending" {
		t.Errorf("new list %s", ids(tasks))
	}
}

func TestTaskListCap(t *testing.T) {
	dir := t.TempDir()
	for i := 1; i <= 60; i++ {
		writeTask(t, dir, fmt.Sprint(i), task(fmt.Sprint(i), "任务", "pending"))
	}
	var l taskList
	tasks, _ := l.read(dir)
	if len(tasks) != protocol.MaxChatTasks || tasks[0].ID != "1" || tasks[49].ID != "50" {
		t.Errorf("%d tasks, %s to %s", len(tasks), tasks[0].ID, tasks[len(tasks)-1].ID)
	}
}

// A list read from another directory, after /clear, is not taken for the
// old one being cleared.
func TestTaskListOfAnotherSession(t *testing.T) {
	a, b := t.TempDir(), t.TempDir()
	for _, id := range []string{"1", "2", "3"} {
		writeTask(t, a, id, task(id, "旧任务", "completed"))
	}
	writeTask(t, b, "1", task("1", "新任务", "completed"))
	var l taskList
	l.read(a)
	if tasks, _ := l.read(b); ids(tasks) != "1:completed" {
		t.Errorf("new session's list %s", ids(tasks))
	}
}

// The refill is seen only in an input box on screen: a screen caught
// without it, as while it redraws, is not one with the message back.
func TestInputFilled(t *testing.T) {
	rule := strings.Repeat("─", 20)
	for _, c := range []struct {
		screen string
		want   bool
	}{
		{"⏺ 回复\n", false},
		{rule + "\n❯\u00a0\n" + rule, false},
		{rule + "\n❯\u00a0只回复 TWO\n" + rule, true},
	} {
		if got := inputFilled(c.screen); got != c.want {
			t.Errorf("inputFilled(%q) = %v", c.screen, got)
		}
	}
}
