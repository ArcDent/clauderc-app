package attach

import (
	"regexp"
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
// A message that changed no code leaves out the two choices with code. The
// panel takes no keys for about a second after it shows up.

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

var (
	rewindRow    = regexp.MustCompile(`^  (❯ |  )(\S.*?)\s*$`)
	rewindOption = regexp.MustCompile(`^  (❯ |  )(\d+)\. (.+?)\s*$`)
)

// rewindPanel returns the panel's lines below its title, or nil when the
// screen shows no panel.
func rewindPanel(screen string) []string {
	lines := strings.Split(screen, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) == "Rewind" {
			return lines[i+1:]
		}
	}
	return nil
}

// ParseRewindList reads the list screen of the panel.
func ParseRewindList(screen string) ([]RewindRow, bool) {
	lines := rewindPanel(screen)
	if !strings.Contains(strings.Join(lines, "\n"), "Enter to continue") {
		return nil, false
	}
	var rows []RewindRow
	for i := 0; i < len(lines); i++ {
		m := rewindRow.FindStringSubmatch(lines[i])
		if m == nil || strings.HasPrefix(m[2], "Restore the code") || strings.HasPrefix(m[2], "Enter to continue") {
			continue
		}
		rows = append(rows, RewindRow{Text: m[2], Cursor: m[1] == "❯ "})
		if m[2] == RewindCurrent {
			break
		}
		i++ // the code changes line
	}
	if len(rows) == 0 || rows[len(rows)-1].Text != RewindCurrent {
		return nil, false
	}
	return rows, true
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

// RewindOpen reports whether either screen of the panel is showing.
func RewindOpen(screen string) bool {
	if _, ok := ParseRewindList(screen); ok {
		return true
	}
	_, ok := ParseRewindOptions(screen)
	return ok
}

// RewindRowMatches reports whether a list row shows the message text: the
// row, without the "…" a long one ends in, starts the message with line
// breaks and runs of spaces made single spaces.
func RewindRowMatches(row, text string) bool {
	squeeze := func(s string) string { return strings.Join(strings.Fields(s), " ") }
	r := squeeze(strings.TrimSuffix(strings.TrimSpace(row), "…"))
	return r != "" && r != RewindCurrent && strings.HasPrefix(squeeze(text), r)
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
