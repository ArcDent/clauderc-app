package transcript_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

func TestPendingPermission(t *testing.T) {
	tr := parseFile(t, "testdata/pending-bash.jsonl")
	p := tr.Pending(true)
	if p == nil {
		t.Fatal("blocked session with an unanswered Bash call: no pending")
	}
	if p.Kind != protocol.PendingPermission || p.ToolName != "Bash" || p.ToolUseID != "call_00_ET_GEktflzLfLDHuagfOR2S4305" {
		t.Fatalf("pending = %+v", p)
	}
	var in struct{ Command string }
	if err := json.Unmarshal(p.Input, &in); err != nil || in.Command != "touch marker_allow.txt" {
		t.Fatalf("pending input = %s", p.Input)
	}
	if p.Questions != nil {
		t.Fatalf("permission request carries questions %+v", p.Questions)
	}

	// Not blocked: the tool is running, nothing to decide.
	if p := tr.Pending(false); p != nil {
		t.Fatalf("unblocked session: pending %+v", p)
	}
	// Once the tool_result is there the prompt is gone.
	if p := parseFile(t, "testdata/answered-bash.jsonl").Pending(true); p != nil {
		t.Fatalf("answered call: pending %+v", p)
	}
}

func TestPendingQuestion(t *testing.T) {
	p := parseFile(t, "testdata/pending-multi.jsonl").Pending(true)
	if p == nil || p.Kind != protocol.PendingQuestion || p.ToolUseID != "call_00_ET_VfpdCrY1z7HgnRxPqS5g1666" {
		t.Fatalf("pending = %+v", p)
	}
	if p.ToolName != "" || p.Input != nil {
		t.Fatalf("question carries tool fields %q %s", p.ToolName, p.Input)
	}
	want := protocol.Question{Question: "你喜欢哪些水果？", Header: "水果", MultiSelect: true, Options: []protocol.Option{
		{Label: "苹果", Description: "苹果"}, {Label: "香蕉", Description: "香蕉"},
		{Label: "樱桃", Description: "樱桃"}, {Label: "葡萄", Description: "葡萄"},
	}}
	if len(p.Questions) != 1 || !sameQuestion(p.Questions[0], want) {
		t.Fatalf("questions = %+v", p.Questions)
	}

	p = parseFile(t, "testdata/pending-questions.jsonl").Pending(true)
	if p == nil || p.Kind != protocol.PendingQuestion || len(p.Questions) != 2 {
		t.Fatalf("pending = %+v", p)
	}
	q0, q1 := p.Questions[0], p.Questions[1]
	if q0.Question != "你喜欢哪种颜色？" || q0.MultiSelect || len(q0.Options) != 3 || q0.Options[1].Label != "绿" {
		t.Fatalf("first question = %+v", q0)
	}
	if q1.Question != "你喜欢哪种动物？" || q1.MultiSelect || len(q1.Options) != 2 || q1.Options[1].Label != "狗" {
		t.Fatalf("second question = %+v", q1)
	}
}

func sameQuestion(a, b protocol.Question) bool {
	if a.Question != b.Question || a.Header != b.Header || a.MultiSelect != b.MultiSelect || len(a.Options) != len(b.Options) {
		return false
	}
	for i := range a.Options {
		if a.Options[i] != b.Options[i] {
			return false
		}
	}
	return true
}

func TestInfo(t *testing.T) {
	info := parseFile(t, "testdata/mode-cycle.jsonl").Info()
	// The session was stopped in acceptEdits; Claude Code wrote it then.
	if info.PermissionMode != protocol.PermissionAcceptEdits {
		t.Errorf("permission mode = %q, want acceptEdits", info.PermissionMode)
	}
	if info.Model != "deepseek-v4.1-flash[1m]" {
		t.Errorf("model = %q", info.Model)
	}
	wantSkills := []protocol.Command{
		{Name: "matt-tdd", Description: `Test-driven development. Use when the user wants to build features or fix bugs test-first, mentions "red-green-refactor", or wants integration te...`},
		{Name: "opsx:apply", Description: "Implement tasks from an OpenSpec change (Experimental)"},
		{Name: "dataviz", Description: "Use this skill whenever you are about to create ANY chart, graph, plot, dashboard, or data visualization, in ANY output medium — an HTML or React ..."},
		{Name: "init", Description: "Initialize a new CLAUDE.md file with codebase documentation"},
	}
	if len(info.Skills) != len(wantSkills) {
		t.Fatalf("skills = %+v", info.Skills)
	}
	for i, s := range wantSkills {
		if info.Skills[i] != s {
			t.Errorf("skill %d = %+v, want %+v", i, info.Skills[i], s)
		}
	}

	// The latest model attachment wins after a /model switch.
	if m := parseFile(t, "testdata/model.jsonl").Info().Model; m != "claude-opus-5-5" {
		t.Errorf("model after switch = %q, want claude-opus-5-5", m)
	}
}

func TestCommands(t *testing.T) {
	skills := parseFile(t, "testdata/mode-cycle.jsonl").Info().Skills
	cmds := transcript.Commands(skills)
	count := map[string]int{}
	for _, c := range cmds {
		count[c.Name]++
		if c.Description == "" {
			t.Errorf("command %q has no description", c.Name)
		}
	}
	for name, n := range count {
		if n > 1 {
			t.Errorf("command %q listed %d times", name, n)
		}
	}
	// Built-in commands, and skills, including one named like a built-in.
	for _, name := range []string{"model", "compact", "clear", "init", "matt-tdd", "opsx:apply", "dataviz"} {
		if count[name] != 1 {
			t.Errorf("command %q listed %d times, want once", name, count[name])
		}
	}

	// Built-in descriptions are shown verbatim on the phone, so they are
	// Chinese; these match design/ui-mockup/index.html word for word. The
	// skill named "init" must yield to the built-in description.
	for _, c := range transcript.BuiltinCommands {
		if !strings.ContainsFunc(c.Description, func(r rune) bool { return unicode.Is(unicode.Han, r) }) {
			t.Errorf("built-in %q description %q is not Chinese", c.Name, c.Description)
		}
	}
	mockup := map[string]string{
		"model": "切换模型", "memory": "编辑记忆文件", "compact": "压缩对话上下文",
		"clear": "清空对话", "context": "查看上下文用量", "init": "生成 CLAUDE.md",
	}
	for _, c := range cmds {
		if want, ok := mockup[c.Name]; ok && c.Description != want {
			t.Errorf("command %q description = %q, want %q", c.Name, c.Description, want)
		}
	}
}

func TestFind(t *testing.T) {
	dir := t.TempDir()
	id := "8f0c2a5e-0000-4000-8000-000000000003"
	proj := filepath.Join(dir, "projects", "-w-pending")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(proj, id+".jsonl")
	if err := os.WriteFile(want, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := transcript.Find(dir, id)
	if err != nil || got != want {
		t.Fatalf("Find = %q, %v; want %q", got, err, want)
	}
	if _, err := transcript.Find(dir, "8f0c2a5e-0000-4000-8000-000000000009"); err == nil {
		t.Fatal("Find of a missing session succeeded")
	}
	for _, bad := range []string{"", "*", "../" + id, "-w-pending/" + id} {
		if _, err := transcript.Find(dir, bad); err == nil {
			t.Errorf("Find(%q) succeeded", bad)
		}
	}
}
