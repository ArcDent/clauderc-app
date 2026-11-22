package chat

import (
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"clauderc/internal/protocol"
)

// A session's Claude Code task list lives in <claude dir>/tasks/<session
// id>/, one <id>.json per task beside dot files of Claude Code's own. The
// terminal shows the list from these files, and Claude Code deletes them
// one by one a moment after every task is completed (decision record
// 20261122-054422-bgh7). A session whose list goes by another name, set by
// CLAUDE_CODE_TASK_LIST_ID or a team, shows none.

// taskDir is where the task list of the Claude Code session sessionID is.
func (h *Handler) taskDir(sessionID string) string {
	if sessionID == "" {
		return ""
	}
	return filepath.Join(h.opts.Source.ClaudeDir, "tasks", sessionID)
}

// taskFile is a task file as last read.
type taskFile struct {
	mod      time.Time
	size     int64
	task     protocol.ChatTask
	internal bool
}

// taskList reads a session's task list, reading again only the files that
// changed. The zero value is ready to use.
type taskList struct {
	mu    sync.Mutex
	dir   string
	files map[string]taskFile
	last  []protocol.ChatTask
	// cleared holds the ids of a list all completed that is taken as
	// cleared while Claude Code deletes its files.
	cleared map[string]bool
}

// read returns the list in dir as it is now, never nil, and whether it
// differs from what read returned last.
func (l *taskList) read(dir string) ([]protocol.ChatTask, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	same := dir == l.dir
	if !same {
		l.dir, l.files, l.cleared = dir, nil, nil
	}
	// Another directory's list says nothing of how this one is cleared.
	tasks := l.scan()
	if same {
		tasks = l.settle(tasks)
	}
	changed := l.last == nil || !slices.Equal(tasks, l.last)
	l.last = tasks
	return tasks, changed
}

// current is what read returned last.
func (l *taskList) current() []protocol.ChatTask {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.last)
}

// scan reads the task files, oldest task first by id, at most
// protocol.MaxChatTasks of them. A file Claude Code is halfway through
// writing keeps the task it held when last read.
func (l *taskList) scan() []protocol.ChatTask {
	tasks := []protocol.ChatTask{}
	entries, err := os.ReadDir(l.dir)
	if l.dir == "" || err != nil {
		l.files = nil
		return tasks
	}
	files := map[string]taskFile{}
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || strings.HasPrefix(name, ".") || !strings.HasSuffix(name, ".json") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		was, seen := l.files[name]
		if seen && was.mod.Equal(info.ModTime()) && was.size == info.Size() {
			files[name] = was
			continue
		}
		if f, ok := readTask(filepath.Join(l.dir, name), info); ok {
			files[name] = f
		} else if seen {
			files[name] = was
		}
	}
	l.files = files
	for _, f := range files {
		if !f.internal {
			tasks = append(tasks, f.task)
		}
	}
	sort.Slice(tasks, func(i, j int) bool { return taskBefore(tasks[i].ID, tasks[j].ID) })
	if len(tasks) > protocol.MaxChatTasks {
		tasks = tasks[:protocol.MaxChatTasks]
	}
	return tasks
}

// settle takes a list Claude Code is clearing for empty: after a list all
// completed, one with fewer of its tasks, still all completed.
func (l *taskList) settle(tasks []protocol.ChatTask) []protocol.ChatTask {
	if l.cleared != nil {
		if allCompleted(tasks) && allIn(tasks, l.cleared) {
			return []protocol.ChatTask{}
		}
		l.cleared = nil
	}
	if allCompleted(l.last) && allCompleted(tasks) && len(tasks) < len(l.last) {
		ids := map[string]bool{}
		for _, t := range l.last {
			ids[t.ID] = true
		}
		if allIn(tasks, ids) {
			l.cleared = ids
			return []protocol.ChatTask{}
		}
	}
	return tasks
}

func allCompleted(tasks []protocol.ChatTask) bool {
	for _, t := range tasks {
		if t.Status != protocol.TaskCompleted {
			return false
		}
	}
	return len(tasks) > 0
}

func allIn(tasks []protocol.ChatTask, ids map[string]bool) bool {
	for _, t := range tasks {
		if !ids[t.ID] {
			return false
		}
	}
	return true
}

// taskBefore orders task ids as numbers, which Claude Code gives them.
func taskBefore(a, b string) bool {
	x, errA := strconv.Atoi(a)
	y, errB := strconv.Atoi(b)
	if errA == nil && errB == nil {
		return x < y
	}
	return a < b
}

// readTask reads one task file; ok is false for one that does not parse,
// such as one being written.
func readTask(path string, info fs.FileInfo) (taskFile, bool) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return taskFile{}, false
	}
	var t struct {
		ID         string `json:"id"`
		Subject    string `json:"subject"`
		ActiveForm string `json:"activeForm"`
		Status     string `json:"status"`
		Metadata   struct {
			Internal any `json:"_internal"`
		} `json:"metadata"`
	}
	if json.Unmarshal(raw, &t) != nil || t.ID == "" {
		return taskFile{}, false
	}
	internal := t.Metadata.Internal != nil && t.Metadata.Internal != false
	task := protocol.ChatTask{ID: t.ID, Subject: t.Subject, ActiveForm: t.ActiveForm, Status: protocol.TaskStatus(t.Status)}
	return taskFile{mod: info.ModTime(), size: info.Size(), task: task, internal: internal}, true
}
