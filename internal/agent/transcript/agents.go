package transcript

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"clauderc/internal/protocol"
)

// agentTools name the tool that starts a subagent; Task is its older name.
var agentTools = map[string]bool{"Agent": true, "Task": true}

// agentIDPattern matches a subagent id as Claude Code names its files,
// subagents/agent-<id>.jsonl, e.g. abf8fcd189003562d.
var agentIDPattern = regexp.MustCompile(`^[0-9A-Za-z]{1,64}$`)

// ValidAgentID reports whether id can be a subagent id. Anything else is
// never put into a path.
func ValidAgentID(id string) bool {
	return agentIDPattern.MatchString(id)
}

var (
	noticeToolUseID = regexp.MustCompile(`<tool-use-id>([^<]*)</tool-use-id>`)
	noticeTaskID    = regexp.MustCompile(`<task-id>([^<]*)</task-id>`)
)

// agentLog follows the Agent calls of a transcript. A background subagent's
// call gets its result at once, with status async_launched; the subagent
// ended when a task notification names the call, or its meta file says
// finished or stopped by the user. A foreground call ends with its result.
type agentLog struct {
	// calls maps a tool_use id to its subagent.
	calls map[string]*protocol.Subagent
	// order lists the tool_use ids in transcript order.
	order []string
	// meta keeps the meta files as last read.
	meta metaCache
}

// feed applies one record and returns the tool_use ids of the subagents it
// added or changed.
func (a *agentLog) feed(r *record) []string {
	switch r.Type {
	case "assistant":
		if r.Message != nil {
			return a.started(r.Message.Content, r.Timestamp)
		}
	case "user":
		if r.Message == nil {
			return nil
		}
		if s := textOf(r.Message.Content); s != "" {
			return a.notified(s)
		}
		return a.results(r.Message.Content, r.ToolUseResult)
	case "attachment":
		if r.Attachment != nil && r.Attachment.Type == "queued_command" {
			return a.notified(textOf(r.Attachment.Prompt))
		}
	case "queue-operation":
		return a.notified(textOf(r.Content))
	}
	return nil
}

func (a *agentLog) started(content json.RawMessage, timestamp string) []string {
	var blocks []block
	if json.Unmarshal(content, &blocks) != nil {
		return nil
	}
	var changed []string
	for _, b := range blocks {
		if b.Type != "tool_use" || !agentTools[b.Name] || a.calls[b.ID] != nil {
			continue
		}
		var in struct {
			Description  string `json:"description"`
			SubagentType string `json:"subagent_type"`
		}
		_ = json.Unmarshal(b.Input, &in)
		at, _ := time.Parse(time.RFC3339Nano, timestamp)
		if a.calls == nil {
			a.calls = map[string]*protocol.Subagent{}
		}
		a.calls[b.ID] = &protocol.Subagent{Type: in.SubagentType, Description: in.Description, Running: true, StartedAt: at}
		a.order = append(a.order, b.ID)
		changed = append(changed, b.ID)
	}
	return changed
}

// results applies the tool_results of Agent calls. toolUseResult, the
// record's structured result, only belongs to a result when it is the
// record's only one.
func (a *agentLog) results(content, toolUseResult json.RawMessage) []string {
	var blocks []block
	if len(a.calls) == 0 || json.Unmarshal(content, &blocks) != nil {
		return nil
	}
	results := 0
	for _, b := range blocks {
		if b.Type == "tool_result" {
			results++
		}
	}
	var changed []string
	for _, b := range blocks {
		c := a.calls[b.ToolUseID]
		if b.Type != "tool_result" || c == nil {
			continue
		}
		var u struct {
			Status  string `json:"status"`
			AgentID string `json:"agentId"`
			IsAsync bool   `json:"isAsync"`
		}
		if results == 1 {
			_ = json.Unmarshal(toolUseResult, &u)
		}
		if c.ID == "" && ValidAgentID(u.AgentID) {
			c.ID = u.AgentID
		}
		async := u.Status == "async_launched" || u.IsAsync || strings.HasPrefix(flatten(b.Content), "Async agent launched")
		if b.IsError || !async {
			c.Running = false
		}
		changed = append(changed, b.ToolUseID)
	}
	return changed
}

// notified ends the subagents a task notification in s names: by the
// tool_use id of their call, or else by their id, which is the task id.
// Every notification is a final one: completed, failed, killed or stopped.
func (a *agentLog) notified(s string) []string {
	if len(a.calls) == 0 || !strings.Contains(s, "<task-notification>") {
		return nil
	}
	var changed []string
	end := func(id string, c *protocol.Subagent) {
		if c.Running {
			c.Running = false
			changed = append(changed, id)
		}
	}
	if ms := noticeToolUseID.FindAllStringSubmatch(s, -1); ms != nil {
		for _, m := range ms {
			if c := a.calls[m[1]]; c != nil {
				end(m[1], c)
			}
		}
		return changed
	}
	for _, m := range noticeTaskID.FindAllStringSubmatch(s, -1) {
		for _, id := range a.order {
			if c := a.calls[id]; c.ID == m[1] {
				end(id, c)
			}
		}
	}
	return changed
}

// agentMeta is the part of subagents/agent-<id>.meta.json this package
// reads. A subagent the user stopped has stoppedByUser and no finished.
type agentMeta struct {
	AgentType     string `json:"agentType"`
	ToolUseID     string `json:"toolUseId"`
	Finished      bool   `json:"finished"`
	StoppedByUser bool   `json:"stoppedByUser"`
}

// applyMeta reads the meta files in dir, the session's subagents
// directory, for the subagents still running: it fills in an id the
// transcript has not given yet and the type, and ends those marked
// finished or stopped. It returns the tool_use ids it changed.
func (a *agentLog) applyMeta(dir string) []string {
	var byCall map[string]metaFile
	var changed []string
	for _, id := range a.order {
		c := a.calls[id]
		if !c.Running {
			continue
		}
		var mf metaFile
		var ok bool
		if c.ID != "" {
			mf, ok = a.meta.read(dir, c.ID)
		} else {
			if byCall == nil {
				byCall = a.meta.scan(dir)
			}
			mf, ok = byCall[id]
		}
		if !ok {
			continue
		}
		before := *c
		if c.ID == "" {
			c.ID = mf.id
		}
		if c.Type == "" {
			c.Type = mf.meta.AgentType
		}
		if mf.meta.Finished || mf.meta.StoppedByUser {
			c.Running = false
		}
		if *c != before {
			changed = append(changed, id)
		}
	}
	return changed
}

type metaFile struct {
	id   string
	meta agentMeta
}

// metaCache keeps each meta file as last read, with the size and
// modification time it had then, so an unchanged file is not read again.
// A file that could not be read or parsed is not kept: it may have been
// caught half written, and finishing the write need not change its size or
// modification time.
type metaCache struct {
	files map[string]cachedMeta
	reads int // times a meta file was read
}

type cachedMeta struct {
	size int64
	mod  time.Time
	mf   metaFile
}

// read returns the meta file of subagent id in dir.
func (m *metaCache) read(dir, id string) (metaFile, bool) {
	if !ValidAgentID(id) {
		return metaFile{}, false
	}
	path := filepath.Join(dir, "agent-"+id+".meta.json")
	fi, err := os.Stat(path)
	if err != nil {
		delete(m.files, path)
		return metaFile{}, false
	}
	if c, hit := m.files[path]; hit && c.size == fi.Size() && c.mod.Equal(fi.ModTime()) {
		return c.mf, true
	}
	m.reads++
	mf, ok := readMeta(path, id)
	if !ok {
		delete(m.files, path)
		return metaFile{}, false
	}
	if m.files == nil {
		m.files = map[string]cachedMeta{}
	}
	m.files[path] = cachedMeta{size: fi.Size(), mod: fi.ModTime(), mf: mf}
	return mf, true
}

// scan returns every meta file in dir, by the tool_use id of its call.
func (m *metaCache) scan(dir string) map[string]metaFile {
	out := map[string]metaFile{}
	paths, _ := filepath.Glob(filepath.Join(dir, "agent-*.meta.json"))
	for _, p := range paths {
		id := strings.TrimSuffix(strings.TrimPrefix(filepath.Base(p), "agent-"), ".meta.json")
		if mf, ok := m.read(dir, id); ok && mf.meta.ToolUseID != "" {
			out[mf.meta.ToolUseID] = mf
		}
	}
	return out
}

func readMeta(path, id string) (metaFile, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return metaFile{}, false
	}
	var m agentMeta
	if json.Unmarshal(data, &m) != nil {
		return metaFile{}, false
	}
	return metaFile{id: id, meta: m}, true
}

// running returns the subagents that still run and have an id, in the
// order they were started.
func (a *agentLog) running() []protocol.Subagent {
	var out []protocol.Subagent
	for _, id := range a.order {
		if c := a.calls[id]; c.Running && c.ID != "" {
			out = append(out, *c)
		}
	}
	return out
}

// relevant reports whether line can concern a subagent: an Agent call, a
// task notification, or a tool_result naming a call still running. Other
// lines are not decoded.
func (a *agentLog) relevant(line []byte) bool {
	if bytes.Contains(line, []byte("task-notification")) {
		return true
	}
	if bytes.Contains(line, []byte(`"tool_use"`)) && (bytes.Contains(line, []byte(`"Agent"`)) || bytes.Contains(line, []byte(`"Task"`))) {
		return true
	}
	for id, c := range a.calls {
		if c.Running && bytes.Contains(line, []byte(id)) {
			return true
		}
	}
	return false
}

// subagentsDir is where Claude Code keeps the subagents of the session
// whose transcript is at path: <dir>/<sessionId>/subagents.
func subagentsDir(path string) string {
	return filepath.Join(strings.TrimSuffix(path, ".jsonl"), "subagents")
}

// SubagentPath returns the transcript of subagent id of the session whose
// transcript is at path. The id must pass ValidAgentID.
func SubagentPath(path, id string) string {
	return filepath.Join(subagentsDir(path), "agent-"+id+".jsonl")
}

// syncAgents copies the subagents of the given tool_use ids onto their tool
// items and returns those items' indexes.
func (t *Transcript) syncAgents(ids []string) []int {
	var changed []int
	for _, id := range ids {
		i, ok := t.tools[id]
		if !ok {
			continue
		}
		tc := *t.items[i].Tool
		sub := *t.agents.calls[id]
		tc.Agent = &sub
		t.items[i].Tool = &tc
		changed = append(changed, i)
	}
	return changed
}

// AgentWatch follows the subagents of one session through its transcript
// and their meta files. It only decodes the lines that can concern a
// subagent, so a long transcript costs little to follow. Its methods are
// safe for concurrent use.
type AgentWatch struct {
	mu   sync.Mutex
	tail tail
	dir  string
	log  agentLog
}

// WatchAgents follows the transcript at path; nothing is read until
// Running.
func WatchAgents(path string) *AgentWatch {
	return &AgentWatch{tail: tail{path: path}, dir: subagentsDir(path)}
}

// Running reads what the transcript gained and returns the subagents that
// still run, in the order they were started.
func (w *AgentWatch) Running() ([]protocol.Subagent, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	lines, err := w.tail.lines()
	if err != nil {
		return nil, err
	}
	for _, line := range lines {
		if !w.log.relevant(line) {
			continue
		}
		var r record
		if json.Unmarshal(bytes.TrimSpace(line), &r) == nil {
			w.log.feed(&r)
		}
	}
	w.log.applyMeta(w.dir)
	return w.log.running(), nil
}

// IsRunning reports whether subagent id still runs, as far as the transcript
// and meta files tell; false for a subagent it does not know.
func (w *AgentWatch) IsRunning(id string) (bool, error) {
	running, err := w.Running()
	for _, s := range running {
		if s.ID == id {
			return true, err
		}
	}
	return false, err
}
