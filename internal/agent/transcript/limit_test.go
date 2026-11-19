package transcript

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// limitRecord is Claude Code's record of a request refused for a usage
// limit, as in real transcripts.
func limitRecord(t *testing.T, text, at string) string {
	t.Helper()
	b, err := json.Marshal(map[string]any{
		"type": "assistant", "isApiErrorMessage": true, "error": "rate_limit", "apiErrorStatus": 429, "timestamp": at,
		"message": map[string]any{"model": "<synthetic>", "role": "assistant", "content": []map[string]any{{"type": "text", "text": text}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func parseLines(t *testing.T, lines ...string) *Transcript {
	t.Helper()
	tr, err := Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	return tr
}

func TestLimit(t *testing.T) {
	cases := []struct {
		text, at string
		window   string
		resets   string // RFC 3339; empty for no time
	}{
		// Denver is UTC-7 in November: 19:00 local, so 22:40 the same day.
		{"You've hit your 5-hour limit · resets 10:40pm (America/Denver)", "2026-11-19T02:00:00.000Z", WindowFiveHour, "2026-11-19T05:40:00Z"},
		// Midnight in Los Angeles: 23:30 is the same day.
		{"You've hit your session limit · resets 11:30pm (America/Los_Angeles)", "2026-11-19T08:00:00.000Z", WindowFiveHour, "2026-11-20T07:30:00Z"},
		// 11am has passed in Denver (13:00 local): the next day.
		{"You've hit your weekly limit · resets 11am (America/Denver)", "2026-11-19T20:00:00.000Z", WindowWeekly, "2026-11-20T18:00:00Z"},
		{"You've hit your weekly limit · resets Nov 4 at 10am (America/Los_Angeles)", "2026-11-01T12:00:00.000Z", WindowWeekly, "2026-11-04T18:00:00Z"},
		// A date in January, read in late December.
		{"You've hit your weekly limit · resets Jan 2 at 9am (UTC)", "2026-12-30T12:00:00.000Z", WindowWeekly, "2027-01-02T09:00:00Z"},
		{"You've hit your 5-hour limit · resets soon (Mars/Base)", "2026-11-19T02:00:00.000Z", WindowFiveHour, ""},
	}
	for _, c := range cases {
		info := parseLines(t, limitRecord(t, c.text, c.at)).Info()
		if info.Limit == nil {
			t.Errorf("%q: no limit", c.text)
			continue
		}
		l := info.Limit
		if l.Text != c.text || l.Window != c.window {
			t.Errorf("%q: text %q window %q, want window %q", c.text, l.Text, l.Window, c.window)
		}
		var want time.Time
		if c.resets != "" {
			want, _ = time.Parse(time.RFC3339, c.resets)
		}
		if !l.ResetsAt.Equal(want) {
			t.Errorf("%q: resets %v, want %v", c.text, l.ResetsAt.UTC(), want)
		}
		if at, _ := time.Parse(time.RFC3339Nano, c.at); !l.At.Equal(at) {
			t.Errorf("%q: at %v, want %v", c.text, l.At, at)
		}
	}
}

// A 429 from Anthropic's side is no plan limit, and a later message ends
// the limit.
func TestLimitOnlyAtTheEnd(t *testing.T) {
	refused := limitRecord(t, "API Error: Request rejected (429) · Service Unavailable", "2026-11-19T02:00:00.000Z")
	if l := parseLines(t, refused).Info().Limit; l != nil {
		t.Errorf("Request rejected (429) gave limit %+v", l)
	}
	hit := limitRecord(t, "You've hit your 5-hour limit · resets 10:40pm (America/Denver)", "2026-11-19T02:00:00.000Z")
	user := `{"type":"user","message":{"role":"user","content":"继续"},"timestamp":"2026-11-19T06:00:00.000Z"}`
	meta := `{"type":"user","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}`
	reply := `{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"好"}]}}`
	if l := parseLines(t, hit, meta).Info().Limit; l == nil {
		t.Error("a meta record ended the limit")
	}
	if l := parseLines(t, hit, user).Info().Limit; l != nil {
		t.Errorf("limit after a user message: %+v", l)
	}
	if l := parseLines(t, hit, reply).Info().Limit; l != nil {
		t.Errorf("limit after a reply: %+v", l)
	}
}
