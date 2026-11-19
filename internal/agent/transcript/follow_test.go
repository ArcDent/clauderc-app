package transcript_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

func userLine(text string) string {
	b, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": text}})
	return string(b) + "\n"
}

func assistantLine(text string) string {
	b, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "text", "text": text}}}})
	return string(b) + "\n"
}

// writeLongSample writes 300 chat records, "m1" .. "m300", alternating user
// and assistant, with a non-chat record between each pair.
func writeLongSample(t *testing.T) string {
	t.Helper()
	var sb strings.Builder
	for i := 1; i <= 300; i++ {
		if i%2 == 1 {
			sb.WriteString(userLine(fmt.Sprintf("m%d", i)))
		} else {
			sb.WriteString(assistantLine(fmt.Sprintf("m%d", i)))
			sb.WriteString(`{"type":"system","subtype":"turn_duration","durationMs":5}` + "\n")
		}
	}
	p := filepath.Join(t.TempDir(), "s.jsonl")
	if err := os.WriteFile(p, []byte(sb.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func texts(items []transcript.Item) []string {
	var out []string
	for _, it := range items {
		out = append(out, it.Text)
	}
	return out
}

func wantRange(t *testing.T, items []transcript.Item, from, to int) {
	t.Helper()
	if len(items) != to-from+1 {
		t.Fatalf("got %d items %v, want m%d..m%d", len(items), texts(items), from, to)
	}
	for k, it := range items {
		n := from + k
		if it.Text != fmt.Sprintf("m%d", n) || it.Seq != int64(n) {
			t.Fatalf("item %d = seq %d %q, want seq %d m%d", k, it.Seq, it.Text, n, n)
		}
	}
}

func TestPage(t *testing.T) {
	f, err := transcript.Open(writeLongSample(t))
	if err != nil {
		t.Fatal(err)
	}
	items, more := f.Page(0, 50)
	wantRange(t, items, 251, 300)
	if !more {
		t.Fatal("latest page: hasMore false")
	}
	items, more = f.Page(items[0].Seq, 50)
	wantRange(t, items, 201, 250)
	if !more {
		t.Fatal("second page: hasMore false")
	}
	items, more = f.Page(51, 50)
	wantRange(t, items, 1, 50)
	if more {
		t.Fatal("first page: hasMore true")
	}
	if items, more = f.Page(1, 50); len(items) != 0 || more {
		t.Fatalf("before the first item: %v %v", texts(items), more)
	}
}

func appendTo(t *testing.T, path, s string) {
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

// pollFor polls f every 200ms, as the chat does, until it returns items or
// d has passed.
func pollFor(t *testing.T, f *transcript.Follower, d time.Duration) []transcript.Item {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		items, err := f.Poll()
		if err != nil {
			t.Fatalf("Poll: %v", err)
		}
		if len(items) > 0 {
			return items
		}
		time.Sleep(200 * time.Millisecond)
	}
	return nil
}

func TestFollow(t *testing.T) {
	p := writeLongSample(t)
	f, err := transcript.Open(p)
	if err != nil {
		t.Fatal(err)
	}

	appendTo(t, p, userLine("new one"))
	items := pollFor(t, f, 2*time.Second)
	if len(items) == 0 {
		t.Fatal("no item within 2 seconds of the append")
	}
	if len(items) != 1 || items[0].Text != "new one" || items[0].Seq != 301 || items[0].Kind != protocol.ItemUser {
		t.Fatalf("followed items = %+v", items)
	}

	// Half a line yields nothing until the rest is written.
	line := assistantLine("split reply")
	appendTo(t, p, line[:len(line)/2])
	if items := pollFor(t, f, 1500*time.Millisecond); items != nil {
		t.Fatalf("half a line produced %+v", items)
	}
	appendTo(t, p, line[len(line)/2:])
	items = pollFor(t, f, 2*time.Second)
	if len(items) != 1 || items[0].Text != "split reply" || items[0].Seq != 302 {
		t.Fatalf("followed items = %+v", items)
	}
	if items, _ := f.Page(0, 2); len(items) != 2 || items[1].Text != "split reply" {
		t.Fatalf("latest page after follow = %v", texts(items))
	}
}

// Claude Code moves a session's transcript to the project directory of its
// worktree when it enters one; the follower picks it up there, sends what
// came after the move once and keeps what it had.
func TestFollowMovedTranscript(t *testing.T) {
	claude := t.TempDir()
	const id = "3c1e2a5e-0000-4000-8000-00000000ab01"
	from := filepath.Join(claude, "projects", "-w-repo", id+".jsonl")
	to := filepath.Join(claude, "projects", "-w-repo--claude-worktrees-fox", id+".jsonl")
	for _, p := range []string{from, to} {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(from, []byte(userLine("m1")+assistantLine("m2")), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := transcript.Open(from)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(from, to); err != nil {
		t.Fatal(err)
	}
	appendTo(t, to, userLine("m3"))
	got, err := f.Poll()
	if err != nil {
		t.Fatalf("Poll after the move: %v", err)
	}
	if s := texts(got); len(s) != 1 || s[0] != "m3" || got[0].Seq != 3 {
		t.Fatalf("after the move got %q seq %d, want m3 alone as seq 3", s, got[0].Seq)
	}
	appendTo(t, to, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_w1","name":"Edit","input":{"file_path":"/w/repo/.claude/worktrees/fox/README.md"}}]}}`+"\n")
	if got := pollFor(t, f, time.Second); len(got) != 1 {
		t.Fatalf("next poll got %d items", len(got))
	}
	if p := f.Pending(true); p == nil || p.ToolUseID != "toolu_w1" {
		t.Fatalf("pending after the move = %+v", p)
	}
	if page, _ := f.Page(0, 10); len(page) != 4 || page[0].Text != "m1" {
		t.Fatalf("page after the move = %q", texts(page))
	}
}

// A transcript that is gone for good still fails the poll.
func TestFollowDeletedTranscript(t *testing.T) {
	claude := t.TempDir()
	path := filepath.Join(claude, "projects", "-w-x", "3c1e2a5e-0000-4000-8000-00000000ab02.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(userLine("m1")), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := transcript.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	_ = os.Remove(path)
	if _, err := f.Poll(); err == nil {
		t.Fatal("Poll of a deleted transcript did not fail")
	}
}
