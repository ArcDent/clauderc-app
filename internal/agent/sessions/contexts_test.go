package sessions

import (
	"os"
	"path/filepath"
	"strconv"
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
	if got := c.get(dir, id); got == nil || got.UsedTokens != 1001 || got.WindowTokens != 200000 {
		t.Fatalf("first read = %+v", got)
	}
	fh, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = fh.WriteString(line(5000))
	fh.Close()
	_ = os.Chtimes(path, time.Now(), time.Now().Add(time.Second))
	if got := c.get(dir, id); got == nil || got.UsedTokens != 5001 {
		t.Fatalf("after a new reply = %+v", got)
	}
	if got := c.get(dir, "not-a-session-id"); got != nil {
		t.Errorf("unknown session = %+v", got)
	}
	c.keep(map[string]bool{})
	if len(c.entries) != 0 {
		t.Errorf("keep left %d entries", len(c.entries))
	}
}
