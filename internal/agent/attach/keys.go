package attach

import (
	"bytes"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"clauderc/internal/protocol"
)

// Step is one write to the attach, followed by a pause that lets the UI
// catch up before the next write.
type Step struct {
	Keys []byte
	Wait time.Duration
}

// Key bytes, as measured in scripts/e2e-claude/RESULTS.md.
var (
	keyCtrlU    = []byte("\x15")
	keyCtrlL    = []byte("\x0c")
	keyEnter    = []byte("\r")
	keyEsc      = []byte("\x1b")
	keyUp       = []byte("\x1b[A")
	keyDown     = []byte("\x1b[B")
	keyRight    = []byte("\x1b[C")
	keyShiftTab = []byte("\x1b[Z")
	pasteStart  = []byte("\x1b[200~")
	pasteEnd    = []byte("\x1b[201~")
)

// Pauses between writes, from the e2e script that measured the sequences.
const (
	clearWait = 400 * time.Millisecond
	pasteWait = 500 * time.Millisecond
	keyWait   = 500 * time.Millisecond
	// DefaultImageWait is the pause after pasting an image path, until the
	// input shows [Image #n].
	DefaultImageWait = 800 * time.Millisecond
)

// paste wraps text in paste markers after cleaning it, so nothing in it
// can end the paste early.
func paste(text string) []byte {
	return bytes.Join([][]byte{pasteStart, []byte(CleanText(text)), pasteEnd}, nil)
}

// CleanText drops what a terminal would take as keys rather than text:
// C0 control characters except line feed and tab, DEL, and C1 control
// characters (U+0080 to U+009F). ESC is among them, so no escape sequence
// survives. Every piece of user text typed into an attach goes through it.
func CleanText(s string) string {
	return clean(s, func(r rune) bool { return r == '\n' || r == '\t' })
}

// cleanTyped is CleanText for text typed key by key, where a line break
// would be an Enter and a tab would move the focus: line breaks become
// spaces and tabs are dropped.
func cleanTyped(s string) string {
	return clean(lineBreaks.Replace(s), func(rune) bool { return false })
}

func clean(s string, keep func(rune) bool) string {
	return strings.Map(func(r rune) rune {
		if (r < 0x20 || r >= 0x7f && r <= 0x9f) && !keep(r) {
			return -1
		}
		return r
	}, s)
}

// SendKeys sends one user message (design D7): Ctrl+U clears the input,
// each image path is pasted on its own followed by imageWait, then the
// text is pasted (after a space when images precede it) and Enter sent.
// The text and paths are cleaned with CleanText.
func SendKeys(text string, imagePaths []string, imageWait time.Duration) []Step {
	steps := []Step{{Keys: keyCtrlU, Wait: clearWait}}
	for _, p := range imagePaths {
		steps = append(steps, Step{Keys: paste(p), Wait: imageWait})
	}
	if len(imagePaths) > 0 {
		text = " " + text
	}
	return append(steps, Step{Keys: paste(text), Wait: pasteWait}, Step{Keys: keyEnter})
}

// AllowKeys allows a permission request.
func AllowKeys() []Step { return []Step{{Keys: keyEnter}} }

// DenyKeys denies a permission request.
func DenyKeys() []Step { return []Step{{Keys: keyEsc}} }

// InterruptKeys interrupts the current turn.
func InterruptKeys() []Step { return []Step{{Keys: keyEsc}} }

// CycleModeKeys moves to the next permission mode (Shift+Tab).
func CycleModeKeys() []Step { return []Step{{Keys: keyShiftTab, Wait: keyWait}} }

// RedrawKeys makes the UI repaint the whole screen (Ctrl+L).
func RedrawKeys() []Step { return []Step{{Keys: keyCtrlL}} }

// OpenModelPickerKeys runs /model without an argument, which opens the
// picker. /model <name> would save the model as the default for all new
// sessions in ~/.claude/settings.json, so it is never sent.
func OpenModelPickerKeys() []Step {
	return []Step{{Keys: keyCtrlU, Wait: clearWait}, {Keys: paste("/model"), Wait: pasteWait}, {Keys: keyEnter}}
}

// PickModelKeys moves the picker cursor from entry from to entry to and
// presses s, "use this session only". Enter on the list would save the
// default, so it is never pressed there.
func PickModelKeys(from, to int) []Step {
	var steps []Step
	key := keyDown
	if to < from {
		key = keyUp
	}
	for i := 0; i < abs(to-from); i++ {
		steps = append(steps, Step{Keys: key, Wait: keyWait})
	}
	return append(steps, Step{Keys: []byte("s"), Wait: keyWait})
}

// ConfirmKeys accepts a confirmation dialog such as "Switch model?".
func ConfirmKeys() []Step { return []Step{{Keys: keyEnter}} }

// CancelKeys leaves a picker without choosing.
func CancelKeys() []Step { return []Step{{Keys: keyEsc}} }

// Reasons AnswerKeys refuses answers; the returned errors wrap them.
var (
	ErrAnswerCount  = errors.New("attach: one answer per question needed")
	ErrOptionRange  = errors.New("attach: option out of range")
	ErrNoAnswer     = errors.New("attach: question has no answer")
	ErrSingleAnswer = errors.New("attach: question takes one answer")
)

// AnswerKeys answers an AskUserQuestion prompt, one answer per question
// (design D7). Single-select: Down to the option, Enter, which moves on.
// Multi-select: Enter toggles each chosen option, Right moves on. A custom
// answer moves to "Type something", right after the options, types the
// text cleaned by cleanTyped and presses Enter; in a multi-select question,
// where it goes along with the options, it presses Up instead. More than one
// question, or a multi-select one, ends on a review page that Enter submits.
func AnswerKeys(questions []protocol.Question, answers []protocol.Answer) ([]Step, error) {
	if len(questions) == 0 || len(answers) != len(questions) {
		return nil, fmt.Errorf("%w: %d answers for %d questions", ErrAnswerCount, len(answers), len(questions))
	}
	var steps []Step
	key := func(k []byte) { steps = append(steps, Step{Keys: k, Wait: keyWait}) }
	review := len(questions) > 1
	for qi, q := range questions {
		a := answers[qi]
		custom := cleanTyped(a.Custom)
		for _, i := range a.Selected {
			if i < 0 || i >= len(q.Options) {
				return nil, fmt.Errorf("%w: option %d of question %d", ErrOptionRange, i, qi)
			}
		}
		if custom == "" && len(a.Selected) == 0 {
			return nil, fmt.Errorf("%w: question %d", ErrNoAnswer, qi)
		}
		if !q.MultiSelect && (len(a.Selected) > 1 || (custom != "" && len(a.Selected) > 0)) {
			return nil, fmt.Errorf("%w: question %d", ErrSingleAnswer, qi)
		}
		cur := 0
		moveTo := func(i int) {
			for ; cur < i; cur++ {
				key(keyDown)
			}
		}
		if q.MultiSelect {
			review = true
			for _, i := range sortedUnique(a.Selected) {
				moveTo(i)
				key(keyEnter)
			}
		} else if len(a.Selected) == 1 {
			moveTo(a.Selected[0])
			key(keyEnter)
		}
		if custom != "" {
			moveTo(len(q.Options))
			key([]byte(custom))
			if q.MultiSelect {
				// Typing ticks the row in a multi-select question and Enter would untick it,
				// while Right only moves the cursor in the text. Up leaves the field, still
				// ticked, so Right below moves on (Claude Code 2.1.327).
				key(keyUp)
			} else {
				key(keyEnter)
			}
		}
		if q.MultiSelect {
			key(keyRight)
		}
	}
	if review {
		key(keyEnter)
	}
	return steps, nil
}

// lineBreaks turns line breaks into spaces.
var lineBreaks = strings.NewReplacer("\r\n", " ", "\r", " ", "\n", " ")

func sortedUnique(xs []int) []int {
	seen := map[int]bool{}
	var out []int
	for _, x := range xs {
		if !seen[x] {
			seen[x] = true
			out = append(out, x)
		}
	}
	sort.Ints(out)
	return out
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
