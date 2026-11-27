package attach_test

import (
	"bytes"
	"os"
	"reflect"
	"regexp"
	"strings"
	"testing"
	"unicode"

	"clauderc/internal/agent/attach"
)

var replyFrames = regexp.MustCompile(`\x1b\[\?(?:2026[hl]|25h)`)

func TestLongReplyReplayReliability(t *testing.T) {
	raw, err := os.ReadFile("testdata/long-reply.raw")
	if err != nil {
		t.Fatal(err)
	}
	s := attach.NewScreen(attach.Rows, attach.Cols)
	const beforeRedraw, afterWrap, afterSync = 35408, 35992, 40822
	s.Write(raw[:beforeRedraw])
	before := s.View()
	if before.Unreliable {
		t.Fatal("screen before oversized redraw is unreliable")
	}
	s.Write(raw[beforeRedraw:afterWrap])
	if v := s.View(); !v.Unreliable || !reflect.DeepEqual(v.Lines, before.Lines) {
		t.Error("oversized redraw exposed the in-progress grid")
	}
	s.Write(raw[afterWrap:afterSync])
	if v := s.View(); !v.Unreliable || !reflect.DeepEqual(v.Lines, before.Lines) {
		t.Error("oversized redraw was published at the end of synchronized output")
	}
	beforeExit := bytes.Index(raw, []byte("\x1b[?1049l"))
	s.Write(raw[afterSync:beforeExit])
	if s.View().Unreliable {
		t.Error("screen before leaving the alternate screen is unreliable")
	}
	s.Write(raw[beforeExit:])
	if s.View().Unreliable {
		t.Error("screen after replaying all output is unreliable")
	}
}

func TestLongReplyReplay(t *testing.T) {
	raw, err := os.ReadFile("testdata/long-reply.raw")
	if err != nil {
		t.Fatal(err)
	}
	text, err := os.ReadFile("testdata/long-reply.txt")
	if err != nil {
		t.Fatal(err)
	}
	compact := func(s string) string {
		return strings.Map(func(r rune) rune {
			if unicode.IsSpace(r) || r == '*' || r == '`' {
				return -1
			}
			return r
		}, s)
	}
	want := compact(string(text))
	start := bytes.Index(raw, []byte("终端模拟器做的事"))
	end := bytes.Index(raw, []byte("\x1b[?1049l"))
	s := attach.NewScreen(attach.Rows, attach.Cols)
	prev, frames, checked := 0, 0, 0
	var saved []string
	var exposedPartialFrame bool
	for _, m := range replyFrames.FindAllIndex(raw[:end], -1) {
		for prev < m[0] {
			next := min(prev+127, m[0])
			s.Write(raw[prev:next])
			prev = next
			if saved != nil && !exposedPartialFrame && !reflect.DeepEqual(s.View().Lines, saved) {
				t.Errorf("byte %d: exposed a synchronized redraw before it ended", prev)
				exposedPartialFrame = true
			}
		}
		marker := string(raw[m[0]:m[1]])
		if marker == "\x1b[?2026h" {
			saved = s.View().Lines
		}
		for prev < m[1] {
			next := min(prev+3, m[1])
			s.Write(raw[prev:next])
			prev = next
		}
		if marker == "\x1b[?2026l" {
			saved = nil
		}
		if saved != nil || prev < start {
			continue
		}
		frames++
		lines := s.View().Lines
		first := 0
		for i, l := range lines {
			if strings.HasPrefix(strings.TrimSpace(strings.TrimPrefix(l, "⏺")), "终端模拟器做的事") {
				first = i
				break
			}
		}
		for i := first; i < len(lines); i++ {
			l := lines[i]
			if l == "" {
				continue
			}
			if strings.HasPrefix(l, "────") || strings.ContainsRune("·✢✳✶✻✽", []rune(l)[0]) {
				break
			}
			l = strings.TrimPrefix(strings.TrimPrefix(l, "⏺ "), "  ")
			checked++
			if !strings.Contains(want, compact(l)) {
				t.Errorf("byte %d, row %d: not in transcript: %q", prev, i+1, l)
				break
			}
		}
	}
	t.Logf("checked %d frames, %d reply lines", frames, checked)
}
