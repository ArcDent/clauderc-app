package attach

import (
	"regexp"
	"strings"
)

// Dialog is a Claude Code dialog that holds a session before it reads any
// message, such as the approval of a new project MCP server. It is not in
// the transcript, so it is read off the screen (Claude Code 2.1.326).
type Dialog struct {
	// Title is the dialog's first line as on screen, e.g. "New MCP server
	// found in this project: probe".
	Title string
	// Options are the choices top to bottom, as on screen.
	Options []string
	// Cursor is the index of the option under ❯.
	Cursor int
}

// mcpDialogTitle is the first line of the dialog for one new server in
// .mcp.json; several servers get a different, multi-select dialog that is
// not read.
var mcpDialogTitle = regexp.MustCompile(`^New MCP server found in this project: \S`)

// dialogFooter starts the hint line under the options.
const dialogFooter = "Enter to confirm"

// dialogSpan is how many lines below its title the footer may be.
const dialogSpan = 12

// ParseDialog reads a known startup dialog from a screen: its title, then
// description lines at the title's indent, then options indented deeper or
// marked ❯, then the footer. Anything else, such as the title quoted in
// the conversation, is not a dialog.
func ParseDialog(screen string) (Dialog, bool) {
	lines := strings.Split(screen, "\n")
	for i, l := range lines {
		title := strings.TrimSpace(l)
		if !mcpDialogTitle.MatchString(title) {
			continue
		}
		indent := indentOf(l)
		d := Dialog{Title: title, Cursor: -1}
		for _, next := range lines[i+1 : min(i+1+dialogSpan, len(lines))] {
			text := strings.TrimSpace(next)
			switch {
			case text == "":
				continue
			case strings.HasPrefix(text, dialogFooter):
				if len(d.Options) >= 2 && d.Cursor >= 0 {
					return d, true
				}
				return Dialog{}, false
			case strings.HasPrefix(text, "❯"):
				if d.Cursor >= 0 {
					return Dialog{}, false
				}
				d.Cursor = len(d.Options)
				d.Options = append(d.Options, strings.TrimSpace(strings.TrimPrefix(text, "❯")))
			case indentOf(next) > indent:
				d.Options = append(d.Options, text)
			case len(d.Options) > 0:
				// A line at the title's indent after the options.
				return Dialog{}, false
			}
		}
		return Dialog{}, false
	}
	return Dialog{}, false
}

func indentOf(s string) int {
	return len(s) - len(strings.TrimLeft(s, " "))
}

// DialogKeys moves the cursor of a Dialog from option from to option to
// and confirms with Enter.
func DialogKeys(from, to int) []Step {
	var steps []Step
	key := keyDown
	if to < from {
		key = keyUp
	}
	for i := 0; i < abs(to-from); i++ {
		steps = append(steps, Step{Keys: key, Wait: keyWait})
	}
	return append(steps, Step{Keys: keyEnter})
}
