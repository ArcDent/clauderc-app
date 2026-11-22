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
		l, ok := ParseRewindList(screenOf(t, name))
		if !ok || rowsText(l.Rows) != want || l.Above != 0 || l.Below != 0 {
			t.Errorf("%s: %v %q %d above %d below, want %q", name, ok, rowsText(l.Rows), l.Above, l.Below, want)
		}
		if _, ok := ParseRewindOptions(screenOf(t, name)); ok {
			t.Errorf("%s: read as the ways back", name)
		}
	}
}

// A list longer than the screen scrolls: the samples have sixteen
// messages, the fifth on two lines and the tenth wider than the screen.
func TestParseLongRewindList(t *testing.T) {
	long := "只回复 L9，这一句只是为了让消息比屏幕还宽，这一句只是为了让消息比屏幕还宽，这一句只是为了让消息比屏幕还宽，这…"
	l5 := "只回复 L5…"
	for _, c := range []struct {
		name, rows   string
		above, below int
		ups          int
	}{
		// Just opened, the cursor on "(current)".
		{"rewind-long-list.txt", "只回复 L7|只回复 L8|" + long + "|只回复 L10|只回复 L11|只回复 L12|只回复 L13|只回复 L14|只回复 L15|> (current)", 7, 0, 0},
		// Up twice: "(current)" went out of sight below.
		{"rewind-long-scrolled.txt", "只回复 L6|只回复 L7|只回复 L8|" + long + "|只回复 L10|只回复 L11|只回复 L12|只回复 L13|> 只回复 L14", 6, 2, 2},
		// Up fourteen times, to the third message.
		{"rewind-long-top.txt", "只回复 OK|只回复 L1|> 只回复 L2|只回复 L3|只回复 L4|" + l5 + "|只回复 L6|只回复 L7|只回复 L8|" + long, 0, 7, 14},
	} {
		l, ok := ParseRewindList(screenOf(t, c.name))
		if !ok || rowsText(l.Rows) != c.rows || l.Above != c.above || l.Below != c.below {
			t.Errorf("%s: %v %q %d above %d below", c.name, ok, rowsText(l.Rows), l.Above, l.Below)
			continue
		}
		if l.Above+len(l.Rows)+l.Below != 17 {
			t.Errorf("%s: %d rows in all, want 17", c.name, l.Above+len(l.Rows)+l.Below)
		}
		for i, r := range l.Rows {
			if r.Cursor && l.Ups(i) != c.ups {
				t.Errorf("%s: cursor %d rows up, want %d", c.name, l.Ups(i), c.ups)
			}
		}
	}
	if !RewindRowMatches(l5, "只回复 L5\n第二行不用管") {
		t.Errorf("the row of a message on two lines does not match it")
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
		// A row not cut short is the whole message, or its first line.
		{"继续", "继续写测试", false},
		{"第一行", "第一行\n第二行", true},
		{"第一", "第一行\n第二行", false},
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

// A message reading "Rewind" is a row, not the panel's title.
func TestRewindMessageNamedRewind(t *testing.T) {
	screen := strings.Replace(screenOf(t, "rewind-list.txt"), "    只回复 ONE\n", "    Rewind\n", 1)
	l, ok := ParseRewindList(screen)
	if want := "只回复 OK|Rewind|" + rewindTwo + "|只回复 THREE|> (current)"; !ok || rowsText(l.Rows) != want {
		t.Errorf("%v %q, want %q", ok, rowsText(l.Rows), want)
	}
	if RewindShown("  Rewind\n\n  Enter to continue · Esc to cancel\n") {
		t.Errorf("a line reading Rewind without the panel's rule taken for the panel")
	}
}
