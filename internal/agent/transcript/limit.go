package transcript

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Limit is the plan usage limit a session ran into: Claude Code ends the
// turn on a synthetic assistant record with error "rate_limit", such as
// "You've hit your 5-hour limit · resets 10:40pm (America/Denver)".
type Limit struct {
	// Text is Claude Code's message as is.
	Text string
	// Window is "fiveHour" or "weekly", as in usage.get; empty when the
	// text names neither.
	Window string
	// ResetsAt is when the limit is lifted, read off Text; zero when the
	// text gives no time this package can read.
	ResetsAt time.Time
	// At is the record's timestamp; it tells one limit record from
	// another. Zero when the record has none.
	At time.Time
}

// Usage windows a Limit can name, as in protocol.UsageResponse.
const (
	WindowFiveHour = "fiveHour"
	WindowWeekly   = "weekly"
)

// limitText matches Claude Code's limit message; the reset part is what
// follows "resets", with the time zone in parentheses.
var limitText = regexp.MustCompile(`(?i)hit your (.*?)limit\b.*?resets (.+?) \(([^()]+)\)\s*$`)

// resetTime matches the reset part: "10:40pm", "11am", or with a date,
// "Nov 4 at 10am".
var resetTime = regexp.MustCompile(`(?i)^(?:([a-z]{3})[a-z]* (\d{1,2}),? at )?(\d{1,2})(?::(\d{2}))?\s*(am|pm)$`)

// parseLimit reads a limit record's text; at is the record's timestamp,
// against which a reset given as a clock time is the next such time. ok is
// false when text is not a usage limit message, such as Anthropic's own
// "Request rejected (429)".
func parseLimit(text string, at time.Time) (Limit, bool) {
	text = strings.TrimSpace(text)
	m := limitText.FindStringSubmatch(text)
	if m == nil {
		return Limit{}, false
	}
	l := Limit{Text: text, At: at}
	switch kind := strings.ToLower(m[1]); {
	case strings.Contains(kind, "week"):
		l.Window = WindowWeekly
	case strings.Contains(kind, "5-hour"), strings.Contains(kind, "session"):
		l.Window = WindowFiveHour
	}
	if !at.IsZero() {
		l.ResetsAt = resetsAt(strings.TrimSpace(m[2]), m[3], at)
	}
	return l, true
}

// resetsAt turns the reset part of a limit message into a time in zone,
// the first one after at; zero when either cannot be read.
func resetsAt(when, zone string, at time.Time) time.Time {
	loc, err := time.LoadLocation(strings.TrimSpace(zone))
	if err != nil {
		return time.Time{}
	}
	m := resetTime.FindStringSubmatch(when)
	if m == nil {
		return time.Time{}
	}
	hour, _ := strconv.Atoi(m[3])
	minute := 0
	if m[4] != "" {
		minute, _ = strconv.Atoi(m[4])
	}
	if hour < 1 || hour > 12 || minute > 59 {
		return time.Time{}
	}
	hour %= 12
	if strings.EqualFold(m[5], "pm") {
		hour += 12
	}
	local := at.In(loc)
	if m[1] == "" {
		t := time.Date(local.Year(), local.Month(), local.Day(), hour, minute, 0, 0, loc)
		if !t.After(local) {
			t = t.AddDate(0, 0, 1)
		}
		return t
	}
	month, ok := months[strings.ToLower(m[1])]
	day, _ := strconv.Atoi(m[2])
	if !ok || day < 1 || day > 31 {
		return time.Time{}
	}
	t := time.Date(local.Year(), month, day, hour, minute, 0, 0, loc)
	// A date early in the next year, read late in this one.
	if t.Before(local.AddDate(0, 0, -1)) {
		t = t.AddDate(1, 0, 0)
	}
	return t
}

var months = map[string]time.Month{
	"jan": time.January, "feb": time.February, "mar": time.March, "apr": time.April,
	"may": time.May, "jun": time.June, "jul": time.July, "aug": time.August,
	"sep": time.September, "oct": time.October, "nov": time.November, "dec": time.December,
}
