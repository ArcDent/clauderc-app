package transcript

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

func TestWindowOf(t *testing.T) {
	for model, want := range map[string]int{
		"claude-opus-5-5":            longWindow,
		"claude-opus-4-7":            longWindow,
		"claude-fable-5-2":           longWindow,
		"anthropic/claude-fable-5.2": longWindow,
		"claude-sonnet-5-5-20261001": longWindow,
		"claude-opus-4-5":            standardWindow,
		"claude-opus-4-5[1m]":        longWindow,
		"claude-sonnet-4-6":          standardWindow,
		"claude-opus-4-70":           standardWindow,
		"deepseek-v4.1-flash":        standardWindow,
		"deepseek-v4.1-flash[1m]":    longWindow,
		"":                           standardWindow,
	} {
		if got := WindowOf(model); got != want {
			t.Errorf("WindowOf(%q) = %d, want %d", model, got, want)
		}
	}
}

func reply(model, requested string, input, write, read int, sidechain bool) string {
	side := "false"
	if sidechain {
		side = "true"
	}
	req := ""
	if requested != "" {
		req = `"requestedModel":"` + requested + `",`
	}
	return `{"type":"assistant","isSidechain":` + side + `,` + req + `"message":{"model":"` + model + `","content":[],"usage":{"input_tokens":` +
		strconv.Itoa(input) + `,"cache_creation_input_tokens":` + strconv.Itoa(write) + `,"cache_read_input_tokens":` + strconv.Itoa(read) + `,"output_tokens":9}}}`
}

func writeLines(t *testing.T, lines ...string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "s.jsonl")
	if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestContextOfTakesTheLastMainReply(t *testing.T) {
	path := writeLines(t,
		`{"type":"user","message":{"role":"user","content":"hi"}}`,
		reply("claude-opus-4-5", "", 10, 20, 30, false),
		reply("deepseek-v4.1-flash", "deepseek-v4.1-flash[1m]", 2, 1118, 148085, false),
		// A subagent's reply and Claude Code's own error reply do not count.
		reply("claude-haiku-4-5", "", 1, 1, 900000, true),
		`{"type":"assistant","message":{"model":"<synthetic>","content":[],"usage":{"input_tokens":0,"output_tokens":0}}}`,
		`{"type":"system","subtype":"turn_duration"}`,
	)
	got, err := ContextOf(path)
	want := protocol.SessionContext{UsedTokens: 149205, WindowTokens: longWindow}
	if err != nil || got == nil || *got != want {
		t.Fatalf("ContextOf = %+v, %v; want %+v", got, err, want)
	}
}

func TestContextOfWithoutReply(t *testing.T) {
	path := writeLines(t, `{"type":"user","message":{"role":"user","content":"hi"}}`)
	if got, err := ContextOf(path); err != nil || got != nil {
		t.Errorf("ContextOf = %+v, %v; want nil", got, err)
	}
	if _, err := ContextOf(filepath.Join(t.TempDir(), "missing.jsonl")); err == nil {
		t.Error("missing file: no error")
	}
}

func TestContextOfLooksFurtherBehindLongLines(t *testing.T) {
	// A pasted image makes a user line longer than the first tail.
	big := `{"type":"user","message":{"role":"user","content":"` + strings.Repeat("x", int(contextTails[0])+10) + `"}}`
	path := writeLines(t, reply("claude-opus-5-5", "claude-opus-5-5", 5, 0, 70000, false), big)
	got, err := ContextOf(path)
	if err != nil || got == nil || got.UsedTokens != 70005 || got.WindowTokens != longWindow {
		t.Errorf("ContextOf = %+v, %v", got, err)
	}
}
