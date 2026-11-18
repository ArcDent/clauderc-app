package chat

import (
	"strings"
	"testing"
	"unicode/utf8"

	"clauderc/internal/protocol"
)

func TestCutTextKeepsWholeCharactersAndLines(t *testing.T) {
	// No line break: cut at the limit, never inside 中 (3 bytes).
	s := strings.Repeat("中", 10)
	if got := cutText(s, 10); got != "中中中" || !utf8.ValidString(got) {
		t.Errorf("cutText(%q, 10) = %q, want 中中中", s, got)
	}
	// A line break in the second half: cut after it.
	if got := cutText("aaaaaa\nbbbbbbbbb", 10); got != "aaaaaa\n" {
		t.Errorf("cutText = %q, want the first line", got)
	}
	// A line break only in the first half: keep up to the limit.
	if got := cutText("a\nbbbbbbbbbbbb", 10); got != "a\nbbbbbbbb" {
		t.Errorf("cutText = %q, want 10 bytes", got)
	}
}

func TestCutPatch(t *testing.T) {
	line := "+" + strings.Repeat("y", 99) // 101 bytes with its newline
	big := []protocol.Hunk{
		{OldStart: 1, OldLines: 0, NewStart: 1, NewLines: 60, Lines: repeat(line, 60)},
		{OldStart: 80, OldLines: 0, NewStart: 140, NewLines: 60, Lines: repeat(line, 60)},
	}
	got, cut := cutPatch(big, 8<<10)
	if !cut || len(got) != 2 || len(got[0].Lines) != 60 || len(got[1].Lines) != 81-60 || got[1].NewStart != 140 {
		t.Fatalf("cutPatch: cut %v, %d hunks, lines %v", cut, len(got), hunkLines(got))
	}
	if same, cut := cutPatch(big[:1], 8<<10); cut || len(same[0].Lines) != 60 {
		t.Errorf("a patch within the limit was cut: %v", hunkLines(same))
	}
	long := []protocol.Hunk{{NewStart: 1, NewLines: 1, Lines: []string{"+" + strings.Repeat("z", 9000)}}}
	if got, cut := cutPatch(long, 8<<10); !cut || len(got) != 1 || len(got[0].Lines) != 1 || len(got[0].Lines[0]) != 8<<10 {
		t.Errorf("one long line: cut %v, %v", cut, hunkLines(got))
	}
}

func TestTruncatedLeavesTheOriginal(t *testing.T) {
	res := &protocol.ToolResult{Content: strings.Repeat("a\n", 6000)}
	out := truncated(res)
	if !out.Truncated || len(out.Content) > protocol.MaxToolOutputBytes || res.Truncated || len(res.Content) != 12000 {
		t.Errorf("truncated: out %d bytes %v, original %d bytes %v", len(out.Content), out.Truncated, len(res.Content), res.Truncated)
	}
}

func repeat(s string, n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = s
	}
	return out
}

func hunkLines(p []protocol.Hunk) []int {
	var n []int
	for _, h := range p {
		n = append(n, len(h.Lines))
	}
	return n
}
