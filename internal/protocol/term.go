package protocol

// Terminal view methods: the screen of a running session's hidden attach,
// read only but for a few keys. Text is typed through chat.send.
const (
	// MethodTermOpen answers with the current screen and starts the
	// term.screen events.
	MethodTermOpen Method = "term.open"
	// MethodTermKey presses one key of TermKeys.
	MethodTermKey Method = "term.key"
	// MethodTermClose stops the events.
	MethodTermClose Method = "term.close"
	// EventTermScreen carries the screen whenever it changed, at most
	// TermScreensPerSecond times a second; the last one is always the
	// latest screen.
	EventTermScreen Method = "term.screen"
)

// TermScreensPerSecond bounds the term.screen events of one session.
const TermScreensPerSecond = 5

// TermKey names a key the terminal view can press.
type TermKey string

const (
	TermKeyUp        TermKey = "up"
	TermKeyDown      TermKey = "down"
	TermKeyLeft      TermKey = "left"
	TermKeyRight     TermKey = "right"
	TermKeyEnter     TermKey = "enter"
	TermKeyEsc       TermKey = "esc"
	TermKeyTab       TermKey = "tab"
	TermKeyBackspace TermKey = "backspace"
	// The digits "0" to "9" are keys too.
)

// TermOpenRequest opens the terminal view of a running session.
type TermOpenRequest struct {
	SessionID string `json:"sessionId"`
}

// TermScreen is what the session's terminal shows, the answer to
// term.open and the body of term.screen.
type TermScreen struct {
	SessionID string `json:"sessionId"`
	// Lines has one entry per row of the 40 by 120 terminal, trailing
	// spaces removed.
	Lines []string `json:"lines"`
	// CursorRow and CursorCol place the cursor, 0-based; CursorCol counts
	// the characters of Lines[CursorRow] before it, a wide one being one,
	// and may lie past the line's end.
	CursorRow int `json:"cursorRow"`
	CursorCol int `json:"cursorCol"`
	// Cursor is false while Claude Code hides the cursor, as in a panel.
	Cursor bool `json:"cursor"`
	// Rev orders term.open answers against term.screen pushes.
	Rev int64 `json:"rev,omitempty"`
	// SubID identifies this phone's subscription on term.open responses.
	SubID string `json:"subId,omitempty"`
}

// TermKeyRequest presses Key in the session's terminal view, which must be
// open.
type TermKeyRequest struct {
	SessionID string  `json:"sessionId"`
	Key       TermKey `json:"key"`
}

// TermCloseRequest closes the terminal view; SubID matches term.open's.
type TermCloseRequest struct {
	SessionID string `json:"sessionId"`
	SubID     string `json:"subId,omitempty"`
}
