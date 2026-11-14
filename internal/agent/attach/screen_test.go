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
	return s.Text()
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
