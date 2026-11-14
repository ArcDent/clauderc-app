package attach

import (
	"regexp"
	"strconv"
	"strings"

	"clauderc/internal/protocol"
)

// modeFooter matches the status line of each permission mode (RESULTS.md,
// mode_cycle).
var modeFooter = regexp.MustCompile(`(⏸ manual mode on|⏵⏵ accept edits on|⏸ plan mode on|⏵⏵ auto mode on|⏵⏵ bypass permissions on)`)

var footerModes = map[string]protocol.PermissionMode{
	"⏸ manual mode on":         protocol.PermissionDefault,
	"⏵⏵ accept edits on":       protocol.PermissionAcceptEdits,
	"⏸ plan mode on":           protocol.PermissionPlan,
	"⏵⏵ auto mode on":          protocol.PermissionAuto,
	"⏵⏵ bypass permissions on": protocol.PermissionBypass,
}

// ParseMode reads the permission mode from the status line of a screen;
// the lowest status line wins.
func ParseMode(screen string) (protocol.PermissionMode, bool) {
	hits := modeFooter.FindAllString(screen, -1)
	if len(hits) == 0 {
		return "", false
	}
	return footerModes[hits[len(hits)-1]], true
}

// PickerItem is one entry of the /model picker.
type PickerItem struct {
	// Number is the entry's number on screen, starting at 1.
	Number      int
	Label       string
	Description string
	// Current marks the session's model (✔).
	Current bool
	// Cursor marks the entry under the cursor (❯).
	Cursor bool
}

var pickerLine = regexp.MustCompile(`^\s*(❯)?\s*(\d+)\.\s+(.*?)\s*$`)

// ParsePicker reads the entries of the /model picker ("Select model") from
// a screen. Each entry is "<n>. <label>  <description>"; a description may
// wrap onto indented lines below it.
func ParsePicker(screen string) ([]PickerItem, bool) {
	lines := strings.Split(screen, "\n")
	start := -1
	for i, l := range lines {
		if strings.TrimSpace(l) == "Select model" {
			start = i
		}
	}
	if start < 0 {
		return nil, false
	}
	var items []PickerItem
	for _, l := range lines[start+1:] {
		if m := pickerLine.FindStringSubmatch(l); m != nil {
			n, _ := strconv.Atoi(m[2])
			label, desc, _ := strings.Cut(m[3], "  ")
			label = strings.TrimSpace(label)
			it := PickerItem{Number: n, Cursor: m[1] != "", Description: strings.TrimSpace(desc)}
			if l, ok := strings.CutSuffix(label, "✔"); ok {
				label, it.Current = strings.TrimSpace(l), true
			}
			it.Label = label
			items = append(items, it)
			continue
		}
		text := strings.TrimSpace(l)
		if len(items) == 0 {
			continue
		}
		if text == "" {
			break
		}
		last := &items[len(items)-1]
		last.Description = strings.TrimSpace(last.Description + " " + text)
	}
	return items, len(items) > 0
}

// pickerHint is the key help at the bottom of the /model picker.
const pickerHint = "s to use this session only"

// PickerOpen reports whether a screen shows the /model picker, where
// Enter would save the highlighted model as the default for new sessions
// (RESULTS.md). It needs both the "Select model" title on a line of its
// own and the key help, so conversation text quoting either is not taken
// for the picker.
func PickerOpen(screen string) bool {
	title, hint := false, false
	for _, l := range strings.Split(screen, "\n") {
		title = title || strings.TrimSpace(l) == "Select model"
		hint = hint || strings.Contains(l, pickerHint)
	}
	return title && hint
}

// confirmOption is the first option of the "Switch model?" dialog
// (RESULTS.md: "❯ 1. Yes, switch to <model>").
var confirmOption = regexp.MustCompile(`^\s*(❯\s*)?1\.\s+Yes, switch to\b`)

// confirmSpan is how many lines below its title the dialog's first option
// may be.
const confirmSpan = 6

// ConfirmOpen reports whether a screen shows the "Switch model?" dialog: a
// line starting with the title, followed within a few lines by the "1. Yes,
// switch to" option. The title alone, as quoted in the conversation, is
// not the dialog.
func ConfirmOpen(screen string) bool {
	lines := strings.Split(screen, "\n")
	for i, l := range lines {
		if !strings.HasPrefix(strings.TrimSpace(l), "Switch model?") {
			continue
		}
		for _, next := range lines[i+1 : min(i+1+confirmSpan, len(lines))] {
			if confirmOption.MatchString(next) {
				return true
			}
		}
	}
	return false
}
