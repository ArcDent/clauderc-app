package attach_test

import (
	"encoding/json"
	"os"
	"strings"
	"testing"

	"clauderc/internal/agent/attach"
	"clauderc/internal/protocol"
)

// recorded replays the output of a real `claude attach` (Claude Code
// 2.1.323, 120x40) up to the named mark of testdata/recorded.marks.json
// and returns the screen text.
func recorded(t *testing.T, mark string) string {
	t.Helper()
	return recordedScreen(t, mark).Text()
}

func recordedScreen(t *testing.T, mark string) *attach.Screen {
	t.Helper()
	raw, err := os.ReadFile("testdata/recorded.raw")
	if err != nil {
		t.Fatal(err)
	}
	var marks map[string]int
	b, err := os.ReadFile("testdata/recorded.marks.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &marks); err != nil {
		t.Fatal(err)
	}
	s := attach.NewScreen(40, 120)
	// Feed in small chunks: sequences split across reads must still work.
	data := raw[:marks[mark]]
	for len(data) > 0 {
		n := min(7, len(data))
		s.Write(data[:n])
		data = data[n:]
	}
	return s
}

func TestRecordedAfterEscIsReliable(t *testing.T) {
	if recordedScreen(t, "after-esc").View().Unreliable {
		t.Error("screen after Esc is unreliable")
	}
}

func TestParseModeFromRecordedScreens(t *testing.T) {
	cases := map[string]protocol.PermissionMode{
		"mode-default":     protocol.PermissionDefault,
		"mode-acceptEdits": protocol.PermissionAcceptEdits,
		"mode-plan":        protocol.PermissionPlan,
		"mode-auto":        protocol.PermissionAuto,
		"mode-default2":    protocol.PermissionDefault,
	}
	for mark, want := range cases {
		got, ok := attach.ParseMode(recorded(t, mark))
		if !ok || got != want {
			t.Errorf("%s: mode %q %v, want %q", mark, got, ok, want)
		}
	}
	if _, ok := attach.ParseMode("no footer here"); ok {
		t.Error("ParseMode found a mode in plain text")
	}
}

func TestParsePickerFromRecordedScreens(t *testing.T) {
	want := []attach.PickerItem{
		{Number: 1, Label: "Default (recommended)", Description: "Use the default model (currently deepseek-v4.1-flash[1m]) · Set by ANTHROPIC_DEFAULT_MODEL"},
		{Number: 2, Label: "Opus 5.5", Description: "Custom Opus model (claude-opus-5-5[1M])"},
		{Number: 3, Label: "gpt-6-astra-cc-format[1M]", Description: "Custom Fable model"},
		{Number: 4, Label: "deepseek-v4.1-flash[1M]", Description: "Custom Sonnet model (1M context)"},
		{Number: 5, Label: "glm-5.3-flash[1M]", Description: "Custom Haiku model"},
		{Number: 6, Label: "deepseek-v4.1-flash[1M]", Description: "Custom model", Current: true},
	}
	for mark, cursor := range map[string]int{"picker": 6, "picker-down": 1, "picker-up2": 5} {
		items, ok := attach.ParsePicker(recorded(t, mark))
		if !ok || len(items) != len(want) {
			t.Fatalf("%s: picker %+v %v", mark, items, ok)
		}
		for i, w := range want {
			w.Cursor = w.Number == cursor
			if items[i] != w {
				t.Errorf("%s: item %d = %+v, want %+v", mark, i, items[i], w)
			}
		}
	}
	if _, ok := attach.ParsePicker(recorded(t, "mode-default")); ok {
		t.Error("ParsePicker found a picker on the prompt screen")
	}
}

// Enter on the /model picker saves the default model (RESULTS.md), so
// the screens recorded with it open must be told apart from the prompt.
func TestPickerOpenOnRecordedScreens(t *testing.T) {
	for _, mark := range []string{"picker", "picker-down", "picker-up2"} {
		if !attach.PickerOpen(recorded(t, mark)) {
			t.Errorf("%s: picker not detected", mark)
		}
	}
	for _, mark := range []string{"mode-default", "mode-acceptEdits", "mode-plan", "mode-auto", "mode-default2"} {
		if attach.PickerOpen(recorded(t, mark)) {
			t.Errorf("%s: picker detected on the prompt screen", mark)
		}
	}
	// Talking about the picker in the conversation is not the picker.
	if attach.PickerOpen("❯ 说说 Select model 界面和 s to use this session only 的意思\n  ⏸ manual mode on") {
		t.Error("picker detected in conversation text")
	}
}

// The MCP approval dialog of Claude Code 2.1.326, captured from a new
// session in a directory with one server in .mcp.json.
func TestParseDialog(t *testing.T) {
	raw, err := os.ReadFile("testdata/mcp-dialog.txt")
	if err != nil {
		t.Fatal(err)
	}
	d, ok := attach.ParseDialog("⏺ 前面的内容\n\n" + string(raw))
	if !ok {
		t.Fatal("dialog not detected")
	}
	want := attach.Dialog{
		Title:   "New MCP server found in this project: probe",
		Options: []string{"Use this MCP server", "Use this and all future MCP servers in this project", "Continue without using this MCP server"},
		Cursor:  2,
	}
	if d.Title != want.Title || strings.Join(d.Options, "|") != strings.Join(want.Options, "|") || d.Cursor != want.Cursor {
		t.Errorf("got %+v, want %+v", d, want)
	}
	noFooter := strings.Replace(string(raw), "Enter to confirm · Esc to cancel", "", 1)
	for name, screen := range map[string]string{
		"title in a message":  "❯ 屏幕上写着 New MCP server found in this project: probe 是什么意思\n  ⏸ manual mode on",
		"title line only":     "⏺ 它会显示\n  New MCP server found in this project: probe\n\n❯ \n  ⏸ manual mode on",
		"no footer":           noFooter,
		"recorded prompt":     recorded(t, "mode-default"),
		"recorded picker":     recorded(t, "picker"),
		"recorded mode cycle": recorded(t, "mode-auto"),
	} {
		if _, ok := attach.ParseDialog(screen); ok {
			t.Errorf("%s: dialog detected", name)
		}
	}
}

// The "Switch model?" dialog is its title plus the "Yes, switch to" option
// (RESULTS.md); the words alone in the conversation are not the dialog.
func TestConfirmOpen(t *testing.T) {
	dialog := "⏺ 好的\n\n   Switch model?\n   Switching mid-conversation may change cost\n\n   ❯ 1. Yes, switch to Opus 5.5\n     2. No, go back\n"
	if !attach.ConfirmOpen(dialog) {
		t.Error("dialog not detected")
	}
	for name, screen := range map[string]string{
		"title in a message":  "❯ 要不要 Switch model? 我拿不准\n\n  ⏸ manual mode on",
		"title line only":     "⏺ 问题是\n  Switch model?\n\n❯ \n  ⏸ manual mode on",
		"option line only":    "⏺ 屏幕上会写\n  1. Yes, switch to Opus 5.5\n❯ \n",
		"option far below":    "  Switch model?\n" + strings.Repeat("⏺ 其他内容\n", 10) + "  1. Yes, switch to Opus 5.5\n",
		"recorded prompt":     recorded(t, "mode-default"),
		"recorded picker":     recorded(t, "picker"),
		"recorded mode cycle": recorded(t, "mode-auto"),
	} {
		if attach.ConfirmOpen(screen) {
			t.Errorf("%s: dialog detected", name)
		}
	}
}

// The cursor is shown where the program leaves it once it shows it again;
// Claude Code 2.1.326 leaves it at its input (test/e2e/claude TestTerminal).
func TestViewCursorCountsCharacters(t *testing.T) {
	s := attach.NewScreen(3, 20)
	s.Write([]byte("\x1b[?25l中文ab\x1b[2;1Hx"))
	if v := s.View(); v.Cursor || v.Row != 1 || v.Col != 1 {
		t.Errorf("hidden cursor: %+v", v)
	}
	s.Write([]byte("\x1b[1;6H\x1b[?25h"))
	if v := s.View(); !v.Cursor || v.Row != 0 || v.Col != 3 || v.Lines[0] != "中文ab" {
		t.Errorf("after two wide characters and a: %+v, want col 3", v)
	}
}
