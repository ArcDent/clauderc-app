package sessions

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestContextsFollowTheTranscript(t *testing.T) {
	dir := t.TempDir()
	const id = "0f8b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d"
	path := filepath.Join(dir, "projects", "-p-x", id+".jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	line := func(read int) string {
		return `{"type":"assistant","requestedModel":"claude-opus-4-5","message":{"model":"claude-opus-4-5","content":[],"usage":{"input_tokens":1,"cache_read_input_tokens":` +
			strconv.Itoa(read) + `}}}` + "\n"
	}
	if err := os.WriteFile(path, []byte(line(1000)), 0o600); err != nil {
		t.Fatal(err)
	}
	var c contexts
	if got := c.get(dir, id).Context; got == nil || got.UsedTokens != 1001 || got.WindowTokens != 200000 {
		t.Fatalf("first read = %+v", got)
	}
	fh, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = fh.WriteString(line(5000))
	fh.Close()
	_ = os.Chtimes(path, time.Now(), time.Now().Add(time.Second))
	if got := c.get(dir, id).Context; got == nil || got.UsedTokens != 5001 {
		t.Fatalf("after a new reply = %+v", got)
	}
	if got := c.get(dir, "not-a-session-id"); got.Context != nil || got.Branch != "" {
		t.Errorf("unknown session = %+v", got)
	}
	c.keep(map[string]bool{})
	if len(c.entries) != 0 {
		t.Errorf("keep left %d entries", len(c.entries))
	}
}

// writeTranscript writes lines as the transcript of id under projects/dir.
func writeTranscript(t *testing.T, claudeDir, project, id string, lines ...string) string {
	t.Helper()
	path := filepath.Join(claudeDir, "projects", project, id+".jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	data := ""
	for _, l := range lines {
		data += l + "\n"
	}
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestContextsBranch(t *testing.T) {
	dir := t.TempDir()
	const id = "0f8b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d"
	reply := `{"type":"assistant","gitBranch":"main","message":{"model":"claude-opus-4-5","content":[],"usage":{"input_tokens":10}}}`
	switched := `{"type":"user","gitBranch":"dev","message":{"role":"user","content":"hi"}}`
	// A subagent line on another branch does not count.
	side := `{"type":"assistant","isSidechain":true,"gitBranch":"side","message":{"model":"m","content":[]}}`
	path := writeTranscript(t, dir, "-p-x", id, reply, switched, side, `{"type":"last-prompt"}`)
	var c contexts
	got := c.get(dir, id)
	if got.Branch != "dev" || got.Context == nil || got.Context.UsedTokens != 10 {
		t.Fatalf("get = %+v, want branch dev and 10 tokens", got)
	}

	// Same size and time: the cached tail comes back without a read, even
	// though the file now says something else.
	fi, _ := os.Stat(path)
	changed := strings.Replace(switched, `"dev"`, `"xyz"`, 1)
	if len(changed) != len(switched) {
		t.Fatal("test lines must keep their length")
	}
	writeTranscript(t, dir, "-p-x", id, reply, changed, side, `{"type":"last-prompt"}`)
	_ = os.Chtimes(path, fi.ModTime(), fi.ModTime())
	if got := c.get(dir, id); got.Branch != "dev" {
		t.Errorf("unchanged size and time: branch %q, want the cached dev", got.Branch)
	}
	_ = os.Chtimes(path, time.Now(), fi.ModTime().Add(time.Second))
	if got := c.get(dir, id); got.Branch != "xyz" {
		t.Errorf("after a change: branch %q, want xyz", got.Branch)
	}

	// The transcript moving to a worktree's project dir is followed.
	_ = os.Remove(path)
	writeTranscript(t, dir, "-p-x--claude-worktrees-fox", id, reply, `{"type":"user","gitBranch":"worktree-fox","message":{"role":"user","content":"x"}}`)
	if got := c.get(dir, id); got.Branch != "worktree-fox" {
		t.Errorf("after the move: branch %q, want worktree-fox", got.Branch)
	}
}

func TestContextsNoBranchOutsideGit(t *testing.T) {
	dir := t.TempDir()
	const id = "1f8b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d"
	writeTranscript(t, dir, "-p-y", id, `{"type":"user","gitBranch":"HEAD","message":{"role":"user","content":"hi"}}`)
	var c contexts
	if got := c.get(dir, id); got.Branch != "" {
		t.Errorf("branch %q outside git, want none", got.Branch)
	}
}
