package attach

import (
	"strings"
	"testing"
	"time"
)

func TestFrozenFrameReset(t *testing.T) {
	for _, reset := range []string{"\x1bc", "\x1b[?1049h", "\x1b[?47l", "\x1b[?1047h"} {
		s := NewScreen(2, 10)
		s.Write([]byte("old\x1b[?2026h\x1b[3;1Hbad"))
		s.Write([]byte(reset + "new"))
		if v := s.View(); v.Unreliable || v.Lines[0] != "new" {
			t.Errorf("reset %q: %+v", reset, v)
		}
	}
}

func TestOversizedFrameClearWithVisibleCursor(t *testing.T) {
	s := NewScreen(2, 10)
	s.Write([]byte("old\x1b[?2026h\x1b[3;1Hbad\x1b[?2026l"))
	if v := s.View(); !v.Unreliable || v.Lines[0] != "old" || v.Lines[1] != "" {
		t.Fatalf("snapshot: %+v", v)
	}
	s.Write([]byte("\x1b[2J\x1b[Hnew"))
	if v := s.View(); v.Unreliable || v.Lines[0] != "new" {
		t.Errorf("clear: %+v", v)
	}
}

func TestCursorParkingAndAutomaticWrap(t *testing.T) {
	s := NewScreen(2, 4)
	s.Write([]byte("\x1b[3;1H\x1b[4;1H"))
	if s.View().Unreliable {
		t.Fatal("parking the cursor alone made the screen unreliable")
	}
	s.Write([]byte("\x1b[?2026h\x1b[Habcd\x1b[?2026l"))
	if v := s.View(); v.Unreliable || v.Lines[0] != "abcd" {
		t.Fatalf("filling the row: %+v", v)
	}
	s.Write([]byte("\x1b[?2026h \x1b[?2026l"))
	if v := s.View(); !v.Unreliable || v.Lines[0] != "abcd" {
		t.Fatalf("automatically wrapped padding: %+v", v)
	}
	// Plain output outside a synchronized frame wraps at the edge as a terminal does.
	plain := NewScreen(2, 4)
	plain.Write([]byte("abcdef"))
	if v := plain.View(); v.Unreliable || v.Lines[0] != "abcd" || v.Lines[1] != "ef" {
		t.Errorf("plain wrap: %+v", v)
	}
}

func TestFrozenFrameTimeout(t *testing.T) {
	for _, c := range []struct {
		name, output string
		bad          bool
	}{
		{"sync without end", "\x1b[?2026h\x1b[2;1Hnew", false},
		{"oversized frame", "\x1b[?2026h\x1b[3;1Hnew", true},
	} {
		t.Run(c.name, func(t *testing.T) {
			s := NewScreen(2, 10)
			s.Write([]byte("old" + c.output))
			if v := s.View(); !v.Unreliable || v.Lines[1] != "" {
				t.Fatalf("first view: %+v", v)
			}
			s.frameUntil = time.Now().Add(-time.Millisecond)
			if v := s.View(); v.Unreliable != c.bad || v.Lines[1] != "new" {
				t.Errorf("expired view: %+v", v)
			}
			if c.bad {
				s.Write([]byte("\x1b[4;1Hnext"))
				if text := s.Text(); !strings.Contains(text, "next") || !s.View().Unreliable {
					t.Errorf("repeated oversized redraw froze again: %q", text)
				}
			}
		})
	}
}
