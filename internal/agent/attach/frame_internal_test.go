package attach

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestAlternateScreenLimits(t *testing.T) {
	for _, out := range []string{"x", fmt.Sprintf("\x1b[%dG", maxCols+1), fmt.Sprintf("\x1b[%dd", maxRows+1)} {
		s := NewScreen(Rows, Cols)
		line := strings.Repeat("─", maxCols)
		s.Write(fmt.Appendf(nil, "\x1b[?1047h\x1b[?2026h\x1b[%d;1H%s\x1b[?2026l", maxRows, line))
		if v := s.View(); v.Unreliable || v.Width != maxCols || v.Height != maxRows || v.Lines[maxRows-1] != line {
			t.Fatalf("screen at limits: %d×%d, unreliable=%v", v.Width, v.Height, v.Unreliable)
		}
		s.Write([]byte("\x1b[?2026h" + out + "\x1b[?2026l"))
		if v := s.View(); !v.Unreliable || v.Lines[maxRows-1] != line {
			t.Errorf("overflow %q did not freeze the last good frame", out)
		}
	}
}

func TestAlternateScreenErasesAcrossExpandedGrid(t *testing.T) {
	s := NewScreen(Rows, Cols)
	s.Write([]byte("\x1b[?1049h\x1b[43d\x1b[123GABCDE\x1b[124G\x1b[2X\x1b[127G\x1b[K"))
	if v := s.View(); v.Unreliable || v.Width != 127 || v.Height != 43 || v.Lines[42] != strings.Repeat(" ", 122)+"A  D" {
		t.Fatalf("erase in expanded row: %+v", v)
	}
	s.Write([]byte("\x1b[42;1H\x1b[J"))
	if line := s.View().Lines[42]; line != "" {
		t.Errorf("erase below the cursor left %q", line)
	}
	s.Write([]byte("\x1b[43d\x1b[127G\x1b[2JZ"))
	if v := s.View(); v.Unreliable || v.Width != 127 || v.Height != 43 || v.Lines[42] != strings.Repeat(" ", 126)+"Z" {
		t.Fatalf("clear must not move the cursor: %+v", v)
	}
}

func TestSendRefusesSmallAlternateScreen(t *testing.T) {
	for _, size := range [][2]int{{100, 20}, {79, 30}} {
		s := NewScreen(Rows, Cols)
		s.Write(fmt.Appendf(nil, "\x1b[?47h%s\x1b[%d;1H\x1b[?25h\x1b[2J\x1b[H", strings.Repeat("─", size[0]), size[1]))
		if v := s.View(); v.Unreliable || v.Width != size[0] || v.Height != size[1] || strings.TrimSpace(s.Text()) != "" {
			t.Fatalf("after clear: %d×%d, unreliable=%v", v.Width, v.Height, v.Unreliable)
		}
		term := Term{c: &Conn{screen: s}, ctx: context.Background()}
		if err := term.Send(AllowKeys()); !errors.Is(err, ErrScreenTooSmall) {
			t.Errorf("Send on %v: %v, want ErrScreenTooSmall", size, err)
		}
	}
}

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
