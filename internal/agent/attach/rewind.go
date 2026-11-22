package attach

import (
	"regexp"
	"strconv"
	"strings"
)

// Claude Code's /rewind panel (2.1.330) has two screens under a "Rewind"
// title. The list names each user message of the current branch, oldest
// first, with a line of its code changes under it, then "(current)",
// where the cursor starts:
//
//	  Restore the code and/or conversation to the point before…
//
//	    只回复 ONE
//	    No code changes
//
//	  ❯ (current)
//
//	  Enter to continue · Esc to cancel
//
// Enter on a message shows the ways back, numbered, the cursor on the
// first; Esc goes back to the list and Esc again closes the panel:
//
//	  Restore to the point before you sent this message:
//
//	  ❯ 1. Restore code and conversation
//	    2. Restore conversation
//	    3. Restore code
//	    4. Summarize from here
//	    5. Summarize up to here
//	    6. Never mind
//
// A list longer than the screen scrolls, keeping the cursor in sight, and
// says how many rows are out of sight above and below it:
//
//	    ↑ 6 more
//	    只回复 L6
//	    No code changes
//	  …
//	  ❯ 只回复 L14
//	    No code changes
//
//	    ↓ 2 more
//
// A message on several lines shows its first line ending in "…". When
// neither the message nor anything after it changed code, the ways back
// leave out the two choices with code. The panel takes no keys for about
// a second after it shows up.

// RewindCurrent is the list's last row, where the cursor starts.
const RewindCurrent = "(current)"

// RewindRow is one row of the list: a message's first line as the panel
// shows it, possibly cut short with "…", or RewindCurrent.
type RewindRow struct {
	Text   string
	Cursor bool
}

// RewindOption is one numbered way back on the second screen.
type RewindOption struct {
	Number int
	Label  string
	Cursor bool
}

// RewindList is the list screen as it shows: Rows are the rows on screen,
// Above and Below count those scrolled out of sight above and below them,
// "(current)" being one of those below while it does not show.
type RewindList struct {
	Rows         []RewindRow
	Above, Below int
}

// Ups returns how many rows above "(current)" Rows[i] is.
func (l RewindList) Ups(i int) int { return len(l.Rows) - 1 - i + l.Below }

var (
	rewindRow    = regexp.MustCompile(`^  (❯ |  )(\S.*?)\s*$`)
	rewindMore   = regexp.MustCompile(`^([↑↓]) (\d+) more$`)
	rewindOption = regexp.MustCompile(`^  (❯ |  )(\d+)\. (.+?)\s*$`)
)

// rewindPanel returns the panel's lines below its title, or nil when the
// screen shows no panel.
func rewindPanel(screen string) []string {
	lines := strings.Split(screen, "\n")
	if i := rewindTitle(lines); i >= 0 {
		return lines[i+1:]
	}
	return nil
}

// rewindTitle is the index of the panel's title, "Rewind" under the
// panel's top rule, which a message reading "Rewind" is not; -1 for none.
func rewindTitle(lines []string) int {
	for i := len(lines) - 1; i > 0; i-- {
		if strings.TrimSpace(lines[i]) == "Rewind" && strings.HasPrefix(strings.TrimSpace(lines[i-1]), "▔") {
			return i
		}
	}
	return -1
}

// ParseRewindList reads the list screen of the panel. It shows down to
// "(current)", or says how many rows below are out of sight.
func ParseRewindList(screen string) (RewindList, bool) {
	var l RewindList
	lines := rewindPanel(screen)
	if !strings.Contains(strings.Join(lines, "\n"), "Enter to continue") {
		return l, false
	}
	for i := 0; i < len(lines); i++ {
		m := rewindRow.FindStringSubmatch(lines[i])
		if m == nil || strings.HasPrefix(m[2], "Restore the code") || strings.HasPrefix(m[2], "Enter to continue") {
			continue
		}
		if more := rewindMore.FindStringSubmatch(m[2]); more != nil && m[1] != "❯ " {
			n, _ := strconv.Atoi(more[2])
			if more[1] == "↑" {
				l.Above = n
			} else {
				l.Below = n
			}
			continue
		}
		l.Rows = append(l.Rows, RewindRow{Text: m[2], Cursor: m[1] == "❯ "})
		if m[2] == RewindCurrent {
			break
		}
		i++ // the code changes line
	}
	if len(l.Rows) == 0 || l.Rows[len(l.Rows)-1].Text != RewindCurrent && l.Below == 0 {
		return RewindList{}, false
	}
	return l, true
}

// ParseRewindOptions reads the ways back on the panel's second screen.
func ParseRewindOptions(screen string) ([]RewindOption, bool) {
	lines := rewindPanel(screen)
	if !strings.Contains(strings.Join(lines, "\n"), "Restore to the point before") {
		return nil, false
	}
	var opts []RewindOption
	for _, l := range lines {
		if m := rewindOption.FindStringSubmatch(l); m != nil {
			n := 0
			for _, d := range m[2] {
				n = n*10 + int(d-'0')
			}
			opts = append(opts, RewindOption{Number: n, Label: m[3], Cursor: m[1] == "❯ "})
		}
	}
	return opts, len(opts) > 0
}

// RewindShown reports whether the panel shows, by its title under the
// panel's top rule, whatever its screens look like.
func RewindShown(screen string) bool {
	return rewindTitle(strings.Split(screen, "\n")) >= 0
}

// RewindOpen reports whether either screen of the panel is showing.
func RewindOpen(screen string) bool {
	if _, ok := ParseRewindList(screen); ok {
		return true
	}
	_, ok := ParseRewindOptions(screen)
	return ok
}

// RewindRowMatches reports whether a list row, or the quote on the second
// screen, shows the message text: a row cut short with "…" starts the
// message, any other is the whole message or its whole first line. Line
// breaks and runs of spaces count as single spaces.
func RewindRowMatches(row, text string) bool {
	squeeze := func(s string) string { return strings.Join(strings.Fields(s), " ") }
	row = strings.TrimSpace(row)
	if cut, ok := strings.CutSuffix(row, "…"); ok {
		r := squeeze(cut)
		return r != "" && strings.HasPrefix(squeeze(text), r)
	}
	r := squeeze(row)
	first, _, _ := strings.Cut(strings.TrimSpace(text), "\n")
	return r != "" && r != RewindCurrent && (r == squeeze(text) || r == squeeze(first))
}

// OpenRewindKeys clears the input and runs /rewind, which opens the panel.
func OpenRewindKeys() []Step {
	return []Step{{Keys: keyCtrlU, Wait: clearWait}, {Keys: paste("/rewind"), Wait: pasteWait}, {Keys: keyEnter}}
}

// UpKeys and DownKeys move a list or menu cursor one row; EnterKeys
// presses Enter. Callers read the screen between presses.
func UpKeys() []Step    { return []Step{{Keys: keyUp}} }
func DownKeys() []Step  { return []Step{{Keys: keyDown}} }
func EnterKeys() []Step { return []Step{{Keys: keyEnter}} }

// ClearLineKeys deletes the input's last line and the line break before
// it (Ctrl+U, then Backspace); pressed until the input is empty, it
// clears a message of several lines.
func ClearLineKeys() []Step {
	return []Step{{Keys: keyCtrlU, Wait: clearWait}, {Keys: []byte{0x7f}, Wait: clearWait}}
}

// RewindQuote is the message the panel's second screen shows the ways
// back for, its first line as the panel quotes it, or "".
func RewindQuote(screen string) string {
	if _, ok := ParseRewindOptions(screen); !ok {
		return ""
	}
	for _, l := range rewindPanel(screen) {
		if q, ok := strings.CutPrefix(strings.TrimSpace(l), "│ "); ok {
			return strings.TrimSpace(q)
		}
	}
	return ""
}
