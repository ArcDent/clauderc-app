package attach_test

import (
	"strings"
	"testing"

	"clauderc/internal/agent/attach"
	"clauderc/internal/protocol"
)

// joined concatenates the bytes of all steps.
func joined(steps []attach.Step) string {
	var s string
	for _, st := range steps {
		s += string(st.Keys)
	}
	return s
}

// Expected sequences are copied from scripts/e2e-claude/RESULTS.md.
func TestKeyEncodings(t *testing.T) {
	q := func(multi bool, n int) protocol.Question {
		opts := make([]protocol.Option, n)
		for i := range opts {
			opts[i] = protocol.Option{Label: string(rune('a' + i))}
		}
		return protocol.Question{MultiSelect: multi, Options: opts}
	}
	answer := func(qs []protocol.Question, as []protocol.Answer) string {
		steps, err := attach.AnswerKeys(qs, as)
		if err != nil {
			t.Fatalf("AnswerKeys: %v", err)
		}
		return joined(steps)
	}
	cases := []struct{ name, got, want string }{
		{"send", joined(attach.SendKeys("你好", nil, 0)), "\x15\x1b[200~你好\x1b[201~\r"},
		{"multiline", joined(attach.SendKeys("a\nb\nc", nil, 0)), "\x15\x1b[200~a\nb\nc\x1b[201~\r"},
		{"images", joined(attach.SendKeys("看图", []string{"/abs/a.png", "/abs/b.jpg"}, 0)),
			"\x15\x1b[200~/abs/a.png\x1b[201~\x1b[200~/abs/b.jpg\x1b[201~\x1b[200~ 看图\x1b[201~\r"},
		{"allow", joined(attach.AllowKeys()), "\r"},
		{"deny", joined(attach.DenyKeys()), "\x1b"},
		{"interrupt", joined(attach.InterruptKeys()), "\x1b"},
		{"single choice, third option", answer([]protocol.Question{q(false, 3)}, []protocol.Answer{{Selected: []int{2}}}), "\x1b[B\x1b[B\r"},
		{"multi select 2nd and 4th", answer([]protocol.Question{q(true, 4)}, []protocol.Answer{{Selected: []int{1, 3}}}), "\x1b[B\r\x1b[B\x1b[B\r\x1b[C\r"},
		{"two questions, 2nd option each", answer([]protocol.Question{q(false, 3), q(false, 2)}, []protocol.Answer{{Selected: []int{1}}, {Selected: []int{1}}}), "\x1b[B\r\x1b[B\r\r"},
		// Design D7: move to "Type something" (after the options), type, Enter.
		{"custom answer", answer([]protocol.Question{q(false, 3)}, []protocol.Answer{{Custom: "都不喜欢"}}), "\x1b[B\x1b[B\x1b[B都不喜欢\r"},
		// Typing ticks the row in a multi-select question: Up leaves it ticked, then Right and the review's Enter.
		{"multi select 1st, 3rd and typed", answer([]protocol.Question{q(true, 3)}, []protocol.Answer{{Selected: []int{0, 2}, Custom: "南瓜"}}), "\r\x1b[B\x1b[B\r\x1b[B南瓜\x1b[A\x1b[C\r"},
		{"cycle mode", joined(attach.CycleModeKeys()), "\x1b[Z"},
		{"redraw", joined(attach.RedrawKeys()), "\x0c"},
		{"open model picker", joined(attach.OpenModelPickerKeys()), "\x15\x1b[200~/model\x1b[201~\r"},
		{"picker 6 to 2, session only", joined(attach.PickModelKeys(6, 2)), "\x1b[A\x1b[A\x1b[A\x1b[A" + "s"},
		{"picker 1 to 3, session only", joined(attach.PickModelKeys(1, 3)), "\x1b[B\x1b[B" + "s"},
		{"confirm switch", joined(attach.ConfirmKeys()), "\r"},
		{"cancel picker", joined(attach.CancelKeys()), "\x1b"},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, c.got, c.want)
		}
	}
}

// Each key is written as typed, so a line break in a custom answer would
// be an Enter that submits the prompt early; it is typed as a space.
func TestCustomAnswerLineBreaksAreTypedAsSpaces(t *testing.T) {
	q := protocol.Question{Options: []protocol.Option{{Label: "a"}, {Label: "b"}}}
	steps, err := attach.AnswerKeys([]protocol.Question{q}, []protocol.Answer{{Custom: "第一行\n第二行\r\n第三行\r"}})
	if err != nil {
		t.Fatal(err)
	}
	got := joined(steps)
	if want := "\x1b[B\x1b[B第一行 第二行 第三行 \r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	if n := strings.Count(got, "\r"); n != 1 {
		t.Errorf("%d Enter presses, want only the final one", n)
	}
	if strings.Contains(got, "\n") {
		t.Errorf("line feed typed: %q", got)
	}
}

func TestAnswerKeysRejectsBadAnswers(t *testing.T) {
	q := protocol.Question{Options: []protocol.Option{{Label: "a"}, {Label: "b"}}}
	bad := [][]protocol.Answer{
		nil, // one answer per question needed
		{{Selected: []int{2}}},
		{{Selected: []int{-1}}},
		{{Selected: []int{0, 1}}}, // two picks on a single-select question
		{{}},
	}
	for _, as := range bad {
		if _, err := attach.AnswerKeys([]protocol.Question{q}, as); err == nil {
			t.Errorf("AnswerKeys(%+v) succeeded", as)
		}
	}
}

// Typed text never turns into keys: an ESC[201~ would end the paste early
// and let the rest of the message act as keys (Ctrl+U, Enter). Only the
// encoder's own paste markers and Enter may remain.
func TestSentTextIsCleaned(t *testing.T) {
	got := joined(attach.SendKeys("hi\x1b[201~\x15/model opus\r", nil, 0))
	if want := "\x15\x1b[200~hi[201~/model opus\x1b[201~\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	body := strings.TrimSuffix(strings.TrimPrefix(got, "\x15\x1b[200~"), "\x1b[201~\r")
	if strings.ContainsAny(body, "\x1b\x15\r") {
		t.Errorf("control bytes left in the pasted text: %q", body)
	}
	if strings.Count(got, "\x15") != 1 || strings.Count(got, "\r") != 1 {
		t.Errorf("extra Ctrl+U or Enter: %q", got)
	}

	// C1 controls and DEL go too; line feeds and tabs stay in a message.
	if got, want := joined(attach.SendKeys("a\u009b31mb\x7f\tc\nd\x00", nil, 0)), "\x15\x1b[200~a31mb\tc\nd\x1b[201~\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	// Image paths are pasted the same way.
	if got, want := joined(attach.SendKeys("x", []string{"/a\x1b[201~\rb.png"}, 0)), "\x15\x1b[200~/a[201~b.png\x1b[201~\x1b[200~ x\x1b[201~\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	if got := attach.CleanText("\x1b/model\x07 opus\u0085"); got != "/model opus" {
		t.Errorf("CleanText = %q", got)
	}
}

// A custom answer is typed key by key, so Esc, Ctrl keys and Shift+Tab
// (ESC[Z) in it would act on the prompt.
func TestCustomAnswerIsCleaned(t *testing.T) {
	q := protocol.Question{Options: []protocol.Option{{Label: "a"}, {Label: "b"}}}
	steps, err := attach.AnswerKeys([]protocol.Question{q}, []protocol.Answer{{Custom: "好\x1b[Z的\x03\t了\n"}})
	if err != nil {
		t.Fatal(err)
	}
	got := joined(steps)
	if want := "\x1b[B\x1b[B好[Z的了 \r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	for _, bad := range []string{"\x1b[Z", "\x03", "\t"} {
		if strings.Contains(got, bad) {
			t.Errorf("%q typed: %q", bad, got)
		}
	}
	// Nothing left after cleaning is no answer.
	if _, err := attach.AnswerKeys([]protocol.Question{q}, []protocol.Answer{{Custom: "\x03\x1b"}}); err == nil {
		t.Error("an answer of control bytes only was accepted")
	}
}
