package attach

import (
	"fmt"
	"os"
	"strings"
	"testing"
)

// The screens are from real sessions rewound through /rewind (test/e2e/claude TestRewind).
func screenOf(t *testing.T, name string) string {
	t.Helper()
	raw, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

const rewindTwo = "请用 Edit 工具把 note.txt 里的 v1 改成 v2，然后只回复 TWO"

func rowsText(rows []RewindRow) string {
	var out []string
	for _, r := range rows {
		s := r.Text
		if r.Cursor {
			s = "> " + s
		}
		out = append(out, s)
	}
	return strings.Join(out, "|")
}

func TestParseRewindList(t *testing.T) {
	for name, want := range map[string]string{
		"rewind-list.txt":   "只回复 OK|只回复 ONE|" + rewindTwo + "|只回复 THREE|> (current)",
		"rewind-target.txt": "只回复 OK|只回复 ONE|> " + rewindTwo + "|只回复 THREE|(current)",
	} {
		rows, ok := ParseRewindList(screenOf(t, name))
		if !ok || rowsText(rows) != want {
			t.Errorf("%s: %v %q, want %q", name, ok, rowsText(rows), want)
		}
		if _, ok := ParseRewindOptions(screenOf(t, name)); ok {
			t.Errorf("%s: read as the ways back", name)
		}
	}
}

func TestParseRewindOptions(t *testing.T) {
	for name, want := range map[string]string{
		"rewind-options.txt":        "> 1 Restore code and conversation|2 Restore conversation|3 Restore code|4 Summarize from here|5 Summarize up to here|6 Never mind",
		"rewind-options-nocode.txt": "> 1 Restore conversation|2 Summarize from here|3 Summarize up to here|4 Never mind",
	} {
		opts, ok := ParseRewindOptions(screenOf(t, name))
		var got []string
		for _, o := range opts {
			s := fmt.Sprintf("%d %s", o.Number, o.Label)
			if o.Cursor {
				s = "> " + s
			}
			got = append(got, s)
		}
		if !ok || strings.Join(got, "|") != want {
			t.Errorf("%s: %v %q", name, ok, got)
		}
		if _, ok := ParseRewindList(screenOf(t, name)); ok {
			t.Errorf("%s: read as the list", name)
		}
	}
}

func TestRewindPanelGoneAfterwards(t *testing.T) {
	for _, name := range []string{"rewind-after-both.txt", "rewind-after-code.txt", "rewind-after-conversation.txt"} {
		if RewindOpen(screenOf(t, name)) {
			t.Errorf("%s: panel still read as open", name)
		}
	}
	for _, name := range []string{"rewind-list.txt", "rewind-options.txt"} {
		if !RewindOpen(screenOf(t, name)) {
			t.Errorf("%s: panel not seen", name)
		}
	}
}

func TestRewindRowMatches(t *testing.T) {
	for _, c := range []struct {
		row, text string
		want      bool
	}{
		{rewindTwo, rewindTwo, true},
		{"第一行 第二行", "第一行\n第二行", true},
		{"把重连退避的上限改成…", "把重连退避的上限改成 60 秒，测试一起改", true},
		{"只回复 ONE", "只回复 THREE", false},
		{"(current)", "(current)", false},
		{"…", "随便什么", false},
	} {
		if got := RewindRowMatches(c.row, c.text); got != c.want {
			t.Errorf("RewindRowMatches(%q, %q) = %v", c.row, c.text, got)
		}
	}
}

func TestRewindQuote(t *testing.T) {
	for name, want := range map[string]string{"rewind-options.txt": rewindTwo, "rewind-options-nocode.txt": "只回复 TWO", "rewind-list.txt": ""} {
		if got := RewindQuote(screenOf(t, name)); got != want {
			t.Errorf("%s: %q, want %q", name, got, want)
		}
	}
}
