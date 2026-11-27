package attach

import (
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Screen is a minimal terminal emulator: it applies the cursor movement
// and erase sequences Claude Code's UI uses to a grid of cells, so the
// text on screen can be read even though the UI repaints only the cells
// that change. Not safe for concurrent use.
type Screen struct {
	rows, cols int
	cells      [][]rune // 0 marks the right half of a wide character
	r, c       int
	rowOutside bool
	pending    []byte // an incomplete sequence or UTF-8 character
	// Another client can resize the session without resizing this attach's PTY.
	alternate                bool
	width, height, parkedRow int
	lastWidth, lastHeight    int
	// hidden is set while the program hides the cursor; Claude Code hides
	// it while it draws and shows it again at the input.
	hidden               bool
	syncOutput, badFrame bool
	frame                *View
	frameUntil           time.Time
}

const (
	frameTimeout = time.Second
	maxRows      = 200
	maxCols      = 512
)

// NewScreen returns a blank screen of the given size.
func NewScreen(rows, cols int) *Screen {
	s := &Screen{rows: rows, cols: cols}
	s.clearScreen()
	return s
}

func blankRow(cols int) []rune {
	row := make([]rune, cols)
	for i := range row {
		row[i] = ' '
	}
	return row
}

// Text returns the screen, one line per row, trailing spaces removed.
func (s *Screen) Text() string {
	s.expireFrame(time.Now())
	if s.frame != nil {
		return strings.Join(s.frame.Lines, "\n")
	}
	return s.text()
}

func (s *Screen) text() string {
	_, height := s.size()
	return strings.Join(s.lines(height), "\n")
}

func (s *Screen) size() (int, int) {
	if !s.alternate {
		return s.cols, s.rows
	}
	// The input rule spans the full width. Claude parks at column 1 on the
	// last row before moving the cursor back to the input.
	if s.width == 0 {
		return s.lastWidth, s.lastHeight
	}
	return s.width, max(s.height, s.parkedRow)
}

func (s *Screen) lines(height int) []string {
	lines := make([]string, height)
	for i, row := range s.cells[:min(height, len(s.cells))] {
		var b strings.Builder
		for _, r := range row {
			if r != 0 {
				b.WriteRune(r)
			}
		}
		lines[i] = strings.TrimRight(b.String(), " ")
	}
	return lines
}

// View is the screen as text with its cursor.
type View struct {
	Width, Height int
	// Lines has one entry per row, trailing spaces removed.
	Lines []string
	// Row and Col place the cursor, 0-based; Col counts the characters of
	// Lines[Row] before it, a wide character being one, and may lie past
	// the line's end.
	Row, Col int
	// Cursor is false while the program hides the cursor.
	Cursor bool
	// Unreliable views can be displayed but must not drive automatic keys.
	Unreliable bool
}

// View returns the screen and its cursor.
func (s *Screen) View() View {
	s.expireFrame(time.Now())
	if s.frame != nil {
		v := *s.frame
		v.Lines = append([]string(nil), v.Lines...)
		v.Unreliable = true
		return v
	}
	return s.currentView()
}

func (s *Screen) currentView() View {
	width, height := s.size()
	row, col := min(s.r, height-1), s.c
	cells := s.row(row)
	for _, r := range cells[:min(col, len(cells))] {
		if r == 0 {
			col--
		}
	}
	return View{Width: width, Height: height, Lines: s.lines(height), Row: row, Col: col, Cursor: !s.hidden, Unreliable: s.unreliable()}
}

func (s *Screen) unreliable() bool { return s.frame != nil || s.syncOutput || s.badFrame }

func (s *Screen) freezeFrame() {
	if s.frameUntil.IsZero() {
		s.frameUntil = time.Now().Add(frameTimeout)
		if !s.badFrame {
			v := s.currentView()
			s.frame = &v
			if s.alternate {
				s.lastWidth, s.lastHeight = v.Width, v.Height
			}
		}
	}
}

func (s *Screen) expireFrame(now time.Time) {
	if !s.frameUntil.IsZero() && !now.Before(s.frameUntil) {
		// Release the display, but never authorize keys on a corrupt grid.
		s.syncOutput, s.frame, s.frameUntil = false, nil, time.Time{}
	}
}

func (s *Screen) resetFrame() {
	s.syncOutput, s.badFrame = false, false
	s.rowOutside = false
	s.finishFrame()
}

func (s *Screen) finishFrame() {
	if !s.syncOutput && !s.badFrame {
		s.height = max(s.height, s.parkedRow)
		s.parkedRow = 0
		s.frame, s.frameUntil = nil, time.Time{}
	}
}

func (s *Screen) markBadFrame() {
	if !s.badFrame {
		s.freezeFrame()
		s.badFrame = true
	}
}

// Write applies terminal output; sequences may be split across calls.
func (s *Screen) Write(p []byte) (int, error) {
	s.expireFrame(time.Now())
	data := append(s.pending, p...)
	s.pending = nil
	i := 0
	for i < len(data) {
		b := data[i]
		switch {
		case b == 0x1b:
			n, ok := s.escape(data[i:])
			if !ok {
				s.pending = append([]byte(nil), data[i:]...)
				return len(p), nil
			}
			i += n
		case b == '\r':
			s.c = 0
			i++
		case b == '\n':
			s.lineFeed()
			i++
		case b == '\b':
			s.c = max(s.c-1, 0)
			i++
		case b == '\t':
			s.c = (s.c/8 + 1) * 8
			s.clampCursor(true)
			if !s.alternate {
				s.c = min(s.c, s.cols-1)
			}
			i++
		case b < 0x20 || b == 0x7f:
			i++
		default:
			if !utf8.FullRune(data[i:]) {
				s.pending = append([]byte(nil), data[i:]...)
				return len(p), nil
			}
			r, n := utf8.DecodeRune(data[i:])
			s.put(r)
			i += n
		}
	}
	return len(p), nil
}

func (s *Screen) lineFeed() {
	if s.alternate {
		s.r++
		s.clampCursor(false)
		return
	}
	if s.r < s.rows-1 {
		s.r++
		return
	}
	s.scrollUp(1)
}

func (s *Screen) scrollUp(n int) {
	if s.alternate {
		s.cells = s.cells[min(n, len(s.cells)):]
		return
	}
	for ; n > 0; n-- {
		s.cells = append(s.cells[1:], blankRow(s.cols))
	}
}

func (s *Screen) put(r rune) {
	w := 1
	if wide(r) {
		w = 2
	}
	if s.alternate {
		end := s.c + w
		if end > maxCols {
			s.markBadFrame()
			return
		}
		if s.r >= len(s.cells) {
			s.cells = append(s.cells, make([][]rune, s.r+1-len(s.cells))...)
		}
		row := s.cells[s.r]
		if end > len(row) {
			s.cells[s.r] = append(row, blankRow(end-len(row))...)
		}
		s.width, s.height = max(s.width, end), max(s.height, s.r+1)
		s.parkedRow = 0
	} else {
		wrap := s.c+w > s.cols
		// Claude parks the cursor below the grid without writing. Only a write
		// there or an implicit wrap can corrupt the layout it already drew, and
		// only inside a synchronized frame, where Claude lays out every row for
		// its own width: plain output outside one wraps at the edge as usual.
		if s.syncOutput && (s.rowOutside || wrap) {
			s.markBadFrame()
		}
		if wrap {
			s.c = 0
			s.lineFeed()
		}
	}
	s.cells[s.r][s.c] = r
	if w == 2 {
		s.cells[s.r][s.c+1] = 0
	}
	s.c += w
}

// escape handles the sequence at the start of data and returns its length,
// or false when data ends before the sequence does.
func (s *Screen) escape(data []byte) (int, bool) {
	if len(data) < 2 {
		return 0, false
	}
	switch data[1] {
	case '[':
		for j := 2; j < len(data); j++ {
			if data[j] >= 0x40 && data[j] <= 0x7e {
				s.csi(string(data[2:j]), data[j])
				return j + 1, true
			}
		}
		return 0, false
	case ']', 'P', '_', '^':
		// String sequences end with BEL or ESC \.
		for j := 2; j < len(data); j++ {
			if data[j] == 0x07 {
				return j + 1, true
			}
			if data[j] == 0x1b && j+1 < len(data) && data[j+1] == '\\' {
				return j + 2, true
			}
		}
		return 0, false
	case '(', ')', '#':
		if len(data) < 3 {
			return 0, false
		}
		return 3, true
	case 'c':
		s.resetFrame()
		s.clearScreen()
		s.r, s.c = 0, 0
		return 2, true
	default:
		return 2, true
	}
}

func (s *Screen) csi(params string, final byte) {
	if strings.HasPrefix(params, "?") {
		// Entering or leaving the alternate screen starts from blank.
		if (final == 'h' || final == 'l') && (params == "?1049" || params == "?47" || params == "?1047") {
			s.alternate = final == 'h'
			s.width, s.height, s.parkedRow = 0, 0, 0
			s.lastWidth, s.lastHeight = s.cols, s.rows
			s.clearScreen()
			s.r, s.c = 0, 0
			s.resetFrame()
		}
		if (final == 'h' || final == 'l') && params == "?25" {
			s.hidden = final == 'l'
			if !s.hidden {
				s.finishFrame()
			}
		}
		if params == "?2026" {
			switch final {
			case 'h':
				s.freezeFrame()
				s.syncOutput = true
			case 'l':
				s.syncOutput = false
				s.finishFrame()
			}
		}
		return
	}
	if params != "" && strings.ContainsAny(params[:1], "<=>") {
		return
	}
	var args []int
	for _, f := range strings.Split(params, ";") {
		n, _ := strconv.Atoi(f)
		args = append(args, n)
	}
	arg := func(i, def int) int {
		if i < len(args) && args[i] > 0 {
			if s.alternate {
				return min(args[i], max(maxRows, maxCols)+1)
			}
			return args[i]
		}
		return def
	}
	switch final {
	case 'H', 'f':
		s.r, s.c = arg(0, 1)-1, arg(1, 1)-1
		s.rowOutside = s.r >= s.rows
	case 'A':
		s.r -= arg(0, 1)
	case 'B', 'e':
		s.r += arg(0, 1)
	case 'C', 'a':
		s.c += arg(0, 1)
	case 'D':
		s.c -= arg(0, 1)
	case 'E':
		s.r, s.c = s.r+arg(0, 1), 0
	case 'F':
		s.r, s.c = s.r-arg(0, 1), 0
	case 'G', '`':
		s.c = arg(0, 1) - 1
	case 'd':
		s.r = arg(0, 1) - 1
		s.rowOutside = s.r >= s.rows
	case 'K':
		row := s.row(s.r)
		from, to := s.c, len(row)
		switch arg(0, 0) {
		case 1:
			from, to = 0, s.c+1
		case 2:
			from = 0
		}
		for j := from; j < min(to, len(row)); j++ {
			row[j] = ' '
		}
	case 'J':
		row := s.row(s.r)
		switch arg(0, 0) {
		case 0:
			for j := s.c; j < len(row); j++ {
				row[j] = ' '
			}
			s.clear(s.r+1, len(s.cells))
		case 1:
			s.clear(0, s.r)
			for j := 0; j <= s.c && j < len(row); j++ {
				row[j] = ' '
			}
		default:
			if arg(0, 0) == 2 {
				s.clearScreen()
				s.badFrame = false
				if !s.hidden {
					s.finishFrame()
				}
			} else {
				s.clear(0, len(s.cells))
			}
		}
	case 'X':
		row := s.row(s.r)
		for j := s.c; j < min(s.c+arg(0, 1), len(row)); j++ {
			row[j] = ' '
		}
	case 'P':
		row := s.row(s.r)
		if s.c < len(row) {
			n := min(arg(0, 1), len(row)-s.c)
			copy(row[s.c:], row[s.c+n:])
			for j := len(row) - n; j < len(row); j++ {
				row[j] = ' '
			}
		}
	case '@':
		row := s.row(s.r)
		if s.c < len(row) {
			width := s.cols
			if s.alternate {
				width = s.width
			}
			n := min(arg(0, 1), width-s.c)
			row = append(row, blankRow(min(n, width-len(row)))...)
			s.cells[s.r] = row
			copy(row[s.c+n:], row[s.c:len(row)-n])
			for j := s.c; j < s.c+n; j++ {
				row[j] = ' '
			}
		}
	case 'S':
		s.scrollUp(arg(0, 1))
	}
	s.clampCursor(strings.ContainsRune("HfCaG`", rune(final)))
	if s.alternate && (final == 'H' || final == 'f') && s.c == 0 && !s.badFrame {
		s.parkedRow = s.r + 1
	}
}

func (s *Screen) clampCursor(movedCol bool) {
	if s.alternate {
		if s.r >= maxRows || movedCol && s.c >= maxCols {
			s.r, s.c = min(s.r, maxRows-1), min(s.c, maxCols-1)
			s.markBadFrame()
		}
		s.r, s.c = max(s.r, 0), max(s.c, 0)
		return
	}
	s.r = min(max(s.r, 0), s.rows-1)
	s.c = min(max(s.c, 0), s.cols)
}

func (s *Screen) row(i int) []rune {
	if i < len(s.cells) {
		return s.cells[i]
	}
	return nil
}

func (s *Screen) clearScreen() {
	if s.alternate {
		if !s.badFrame {
			s.lastWidth, s.lastHeight = s.size()
		}
		s.cells = nil
		s.width, s.height, s.parkedRow = 0, 0, 0
		return
	}
	s.cells = make([][]rune, s.rows)
	for i := range s.cells {
		s.cells[i] = blankRow(s.cols)
	}
}

func (s *Screen) clear(from, to int) {
	for i := from; i < min(to, len(s.cells)); i++ {
		s.cells[i] = blankRow(len(s.cells[i]))
	}
}

// wide reports characters that take two cells: East Asian wide and
// fullwidth ranges, and emoji.
func wide(r rune) bool {
	switch {
	case r >= 0x1100 && r <= 0x115f,
		r >= 0x2e80 && r <= 0x303e,
		r >= 0x3041 && r <= 0x33ff,
		r >= 0x3400 && r <= 0x4dbf,
		r >= 0x4e00 && r <= 0x9fff,
		r >= 0xa000 && r <= 0xa4cf,
		r >= 0xac00 && r <= 0xd7a3,
		r >= 0xf900 && r <= 0xfaff,
		r >= 0xfe30 && r <= 0xfe4f,
		r >= 0xff00 && r <= 0xff60,
		r >= 0xffe0 && r <= 0xffe6,
		r >= 0x1f300 && r <= 0x1f64f,
		r >= 0x1f900 && r <= 0x1f9ff,
		r >= 0x20000 && r <= 0x3fffd:
		return true
	}
	return false
}
