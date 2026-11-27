package chat

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// spinnerGlyphs are the characters Claude Code turns at the start of its
// status line while it works (2.1.327).
const spinnerGlyphs = "·✢✳✶✻✽"

// statusLinePattern matches a status line after its glyph: a verb ending
// in "…", then optionally details in parentheses, as in
// "Whirring… (20s · ↓ 2.4k tokens)". A finished turn's line, such as
// "Sautéed for 12s · done", has no "…".
var statusLinePattern = regexp.MustCompile(`^(\S.{0,40}?…)(?: \((.*)\))?$`)

// keyHint matches a detail that only names a key, such as
// "esc to interrupt", which older versions put in the status line.
var keyHint = regexp.MustCompile(`(?i)^(esc|tab|enter|space|ctrl\+\S+|shift\+\S+) to `)

// statusLine finds Claude Code's status line on screen, searching from the
// bottom, and returns its index and text without glyph and key hints.
func statusLine(lines []string) (int, string) {
	for i := len(lines) - 1; i >= 0; i-- {
		t := strings.TrimSpace(lines[i])
		r := []rune(t)
		if len(r) < 3 || !strings.ContainsRune(spinnerGlyphs, r[0]) || r[1] != ' ' {
			continue
		}
		m := statusLinePattern.FindStringSubmatch(string(r[2:]))
		if m == nil {
			continue
		}
		var details []string
		for _, d := range strings.Split(m[2], " · ") {
			if d = strings.TrimSpace(d); d != "" && !keyHint.MatchString(d) {
				details = append(details, d)
			}
		}
		if len(details) == 0 {
			return i, m[1]
		}
		return i, m[1] + " (" + strings.Join(details, " · ") + ")"
	}
	return -1, ""
}

// screenActivity is the status line of a working session, or "".
func screenActivity(lines []string) string {
	_, s := statusLine(lines)
	return s
}

// toolBlock matches the first line of a tool call or a folded group of
// them, which are not reply text: "⏺ Bash(ls)", "⏺ Read 2 files (ctrl+o to
// expand)".
var toolBlock = regexp.MustCompile(`^⏺ ([\w.:-]+\(|.*\(ctrl\+o to expand\))`)

// screenPartial returns the reply Claude Code is drawing on screen: the
// last block that starts with "⏺" after the last prompt line, up to the
// status line, a run of two blank lines outside code, or the input box.
// The bool distinguishes reaching a user prompt from a missing reply.
func screenPartial(lines []string, previous string, endFenced bool) (partial string, atPrompt, fenced bool) {
	input := inputRule(lines)
	end := input
	if status, _ := statusLine(lines[:input]); status >= 0 {
		end = status
	}
	start := -1
	for i := end - 1; i >= 0; i-- {
		if strings.HasPrefix(lines[i], "❯") {
			return "", true, false
		}
		if strings.HasPrefix(lines[i], "⏺") {
			start = i
			break
		}
	}
	if start < 0 {
		if previous != "" && end < len(lines) {
			text, fenced := extendScreenReply(previous, lines[:end], endFenced)
			return text, false, fenced
		}
		return "", false, false
	}
	if toolBlock.MatchString(lines[start]) {
		return "", false, false
	}
	text, fenced := formatScreenReply(lines[start:end], true, false)
	return text, false, fenced
}

func formatScreenReply(block []string, marked, fenced bool) (string, bool) {
	var reply strings.Builder
	var lastLiteral, paragraphBreak bool
	for i, l := range block {
		if i == 0 && marked {
			l = strings.TrimPrefix(strings.TrimPrefix(l, "⏺"), " ")
		} else {
			if l != "" && !strings.HasPrefix(l, "  ") && !strings.HasPrefix(l, "\t") {
				break
			}
			if !fenced && l == "" && (i+1 == len(block) || block[i+1] == "") {
				break
			}
			l = strings.TrimPrefix(l, "  ")
		}
		if l == "" && !fenced {
			paragraphBreak = true
			continue
		}
		first, _ := utf8.DecodeRuneInString(l)
		fence := strings.HasPrefix(l, "```")
		// Code and tables have meaningful line breaks, unlike wrapped prose.
		literal := fenced || fence || strings.HasPrefix(l, "    ") || first == '\t' || strings.ContainsRune("│|┌├└┐┤┘┬┼┴─", first)
		if !fenced {
			if !literal && strings.HasPrefix(strings.TrimSpace(l), "⎿") {
				return "", false
			}
			if text := strings.TrimLeft(l, " "); strings.HasPrefix(text, "• ") {
				l = l[:len(l)-len(text)] + "- " + strings.TrimPrefix(text, "• ")
			}
		}
		if reply.Len() > 0 {
			last, _ := utf8.DecodeLastRuneInString(reply.String())
			switch {
			case paragraphBreak:
				reply.WriteString("\n\n")
			case literal || lastLiteral || listItem(l):
				reply.WriteByte('\n')
			case narrow(last) && narrow(first):
				reply.WriteByte(' ')
			}
		}
		reply.WriteString(l)
		paragraphBreak, lastLiteral = false, literal
		if fence {
			fenced = !fenced
		}
	}
	return reply.String(), fenced
}

const (
	replyOverlapWindow  = attach.Rows * attach.Cols
	replyOverlapLetters = 24
)

func extendScreenReply(previous string, block []string, endFenced bool) (string, bool) {
	for len(block) > 0 && block[0] == "" {
		block = block[1:]
	}
	start := len(previous)
	for range replyOverlapWindow {
		if start == 0 {
			break
		}
		_, size := utf8.DecodeLastRuneInString(previous[:start])
		start -= size
	}
	window := previous[start:]
	flat := withoutSpace(window)
	for _, fenced := range []bool{false, true} {
		visible, nextFenced := formatScreenReply(block, false, fenced)
		text := withoutSpace(visible)
		letters := 0
		for i, r := range text {
			if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
				continue
			}
			letters++
			if letters < replyOverlapLetters {
				continue
			}
			// The anchor must lead into the same suffix, not just repeat
			// elsewhere in the reply. Keep the new text's original breaks.
			anchor := text[:i+utf8.RuneLen(r)]
			for next := 0; next < len(flat); {
				i := strings.Index(flat[next:], anchor)
				if i < 0 {
					break
				}
				pos := next + i
				next = pos + 1
				overlap := flat[pos:]
				if !strings.HasPrefix(text, overlap) {
					continue
				}
				suffix := window[afterNonSpaceBytes(window, pos):]
				if (endFenced != (strings.Count("\n"+suffix, "\n```")%2 != 0)) == fenced {
					if len(overlap) == len(text) {
						return "", false
					}
					return strings.TrimRightFunc(previous, unicode.IsSpace) + visible[afterNonSpaceBytes(visible, len(overlap)):], nextFenced
				}
			}
			break
		}
	}
	return "", false
}

// screenUserCount counts matching prompts if the newest prompt matches.
func screenUserCount(lines []string, want string) int {
	lines = lines[:inputRule(lines)]
	count := 0
	newest := true
	for i := len(lines) - 1; i >= 0; i-- {
		if text, ok := strings.CutPrefix(lines[i], "❯"); ok {
			var user strings.Builder
			user.WriteString(text)
			for _, line := range lines[i+1:] {
				if !strings.HasPrefix(line, "  ") {
					break
				}
				user.WriteString(line)
			}
			if withoutSpace(user.String()) == want {
				count++
			} else if newest {
				return 0
			}
			newest = false
		}
	}
	return count
}

func withoutSpace(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, s)
}

func afterNonSpaceBytes(s string, n int) int {
	for i, r := range s {
		if n == 0 {
			return i
		}
		if !unicode.IsSpace(r) {
			n -= utf8.RuneLen(r)
		}
	}
	return len(s)
}

// inputRule is the index of the rule above the input box, or len(lines).
func inputRule(lines []string) int {
	for i := len(lines) - 1; i > 0; i-- {
		if strings.HasPrefix(strings.TrimSpace(lines[i-1]), "─") && (strings.HasPrefix(lines[i], "❯") || strings.HasPrefix(lines[i], "!")) {
			return i - 1
		}
	}
	return len(lines)
}

// listItem reports whether a screen line starts a list item or a heading,
// which keeps its own line when wrapped lines are joined.
var listItemPattern = regexp.MustCompile(`^\s*([-•*]|\d+\.|#+) `)

func listItem(l string) bool { return listItemPattern.MatchString(l) }

// narrow reports whether r is a narrow character, such as a Latin letter,
// across whose line break the screen dropped a space.
func narrow(r rune) bool {
	return r < 0x1100 && !unicode.IsSpace(r)
}

const landedPrefix = 24

func replyFingerprint(s string) string {
	var out strings.Builder
	n := 0
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			out.WriteRune(r)
			n++
			if n == landedPrefix {
				break
			}
		}
	}
	return out.String()
}

// landed reports whether the transcript's latest reply begins like the
// reply drawn on screen, so the screen shows nothing the phone lacks.
func landed(f *transcript.Follower, fingerprint string) string {
	if f == nil || fingerprint == "" {
		return ""
	}
	items, _ := f.Page(0, 20)
	for i := len(items) - 1; i >= 0; i-- {
		it := items[i]
		if it.Kind == protocol.ItemUser && !it.Queued {
			break
		}
		if it.Kind == protocol.ItemAssistant && it.Text != "" {
			if recorded := replyFingerprint(it.Text); strings.HasPrefix(recorded, fingerprint) {
				return recorded
			}
			break
		}
	}
	return ""
}
