package chat

import (
	"regexp"
	"strings"
	"unicode"

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
// status line, a run of two blank lines, or the input box. It is "" when
// that block is a tool call or there is none.
func screenPartial(lines []string) string {
	end, _ := statusLine(lines)
	if end < 0 {
		end = inputRule(lines)
	}
	start := -1
	for i := end - 1; i >= 0; i-- {
		if strings.HasPrefix(lines[i], "❯") {
			return ""
		}
		if strings.HasPrefix(lines[i], "⏺") {
			start = i
			break
		}
	}
	if start < 0 || toolBlock.MatchString(lines[start]) {
		return ""
	}
	// The reply's lines after the first are indented by two spaces; a
	// line that is not, such as a custom status line, ends it, and so do
	// two blank lines.
	block := lines[start:end]
	for i := 1; i < len(block); i++ {
		if block[i] == "" && (i+1 == len(block) || block[i+1] == "") || block[i] != "" && !strings.HasPrefix(block[i], "  ") {
			block = block[:i]
			break
		}
	}
	for len(block) > 1 && block[len(block)-1] == "" {
		block = block[:len(block)-1]
	}
	var paras []string
	var cur strings.Builder
	for i, l := range block {
		if i == 0 {
			l = strings.TrimPrefix(strings.TrimPrefix(l, "⏺"), " ")
		} else {
			if strings.HasPrefix(strings.TrimSpace(l), "⎿") {
				return ""
			}
			l = strings.TrimPrefix(l, "  ")
		}
		switch {
		case l == "":
			if cur.Len() > 0 {
				paras = append(paras, cur.String())
				cur.Reset()
			}
		case cur.Len() == 0:
			cur.WriteString(l)
		case listItem(l):
			cur.WriteString("\n" + l)
		default:
			if prev := cur.String(); narrow(lastRune(prev)) && narrow([]rune(l)[0]) {
				cur.WriteString(" ")
			}
			cur.WriteString(l)
		}
	}
	if cur.Len() > 0 {
		paras = append(paras, cur.String())
	}
	return strings.Join(paras, "\n\n")
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

func lastRune(s string) rune {
	r := []rune(s)
	return r[len(r)-1]
}

// narrow reports whether r is a narrow character, such as a Latin letter,
// across whose line break the screen dropped a space.
func narrow(r rune) bool {
	return r < 0x1100 && !unicode.IsSpace(r)
}

// lettersOnly keeps letters and digits, so screen text compares with the
// markdown it was drawn from.
func lettersOnly(s string) []rune {
	var out []rune
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			out = append(out, r)
		}
	}
	return out
}

// landedPrefix is how many letters of a partial reply must match the
// transcript's latest reply for it to count as written there.
const landedPrefix = 24

// landed reports whether the transcript's latest reply begins like the
// reply drawn on screen, so the screen shows nothing the phone lacks.
func landed(f *transcript.Follower, partial string) bool {
	if f == nil {
		return false
	}
	p := lettersOnly(partial)
	p = p[:min(len(p), landedPrefix)]
	if len(p) == 0 {
		return true
	}
	items, _ := f.Page(0, 20)
	for i := len(items) - 1; i >= 0; i-- {
		if it := items[i]; it.Kind == protocol.ItemAssistant && it.Text != "" {
			return strings.HasPrefix(string(lettersOnly(it.Text)), string(p))
		}
	}
	return false
}
