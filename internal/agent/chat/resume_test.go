package chat_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"clauderc/internal/agent/chat"
	"clauderc/internal/protocol"
)

// limitHit is when the session ran into its limit in these tests; Denver
// is UTC-7 then, so "resets 10:40pm" is 05:40 UTC.
var limitHit = time.Date(2026, 11, 19, 2, 0, 0, 0, time.UTC)

var limitResets = time.Date(2026, 11, 19, 5, 40, 0, 0, time.UTC)

func limitLine(text string) string {
	b, _ := json.Marshal(map[string]any{
		"type": "assistant", "isApiErrorMessage": true, "error": "rate_limit", "timestamp": limitHit.Format(time.RFC3339Nano),
		"message": map[string]any{"model": "<synthetic>", "role": "assistant", "content": []any{map[string]any{"type": "text", "text": text}}},
	})
	return string(b) + "\n"
}

const denverLimit = "You've hit your 5-hour limit · resets 10:40pm (America/Denver)"

// clock is a settable Options.Now.
type clock struct{ t atomic.Int64 }

func newClock(t time.Time) *clock {
	c := &clock{}
	c.set(t)
	return c
}
func (c *clock) set(t time.Time) { c.t.Store(t.UnixNano()) }
func (c *clock) now() time.Time  { return time.Unix(0, c.t.Load()) }
func resumeOpts(c *clock, file string, saved *chat.Options) func(*chat.Options) {
	return func(o *chat.Options) {
		o.Now = c.now
		o.ResumeFile = file
		o.ResumeInterval = 50 * time.Millisecond
		if saved != nil {
			*saved = *o
		}
	}
}

func (f *fixture) openLimit(t *testing.T) *protocol.Limit {
	t.Helper()
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	return res.Limit
}

func (f *fixture) setResume(t *testing.T, on bool, text string) *protocol.Limit {
	t.Helper()
	var res protocol.ChatAutoResumeResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatAutoResume, protocol.ChatAutoResumeRequest{SessionID: shortID, On: on, Text: text}), &res); err != nil {
		t.Fatal(err)
	}
	return res.Limit
}

// waitKeys waits up to 5s for the fake attach to receive want.
func (f *fixture) waitKeys(t *testing.T, want string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !strings.Contains(f.keyLog(), want) {
		if time.Now().After(deadline) {
			t.Fatalf("keys = %q, want them to hold %q", f.keyLog(), want)
		}
	}
}

func TestLimitInChatStatus(t *testing.T) {
	c := newClock(limitHit.Add(time.Minute))
	f := newFixture(t, setup{opts: resumeOpts(c, "", nil)})
	f.appendTranscript(t, limitLine(denverLimit))
	l := f.openLimit(t)
	if l == nil || l.Window != "fiveHour" || l.Text != denverLimit || !l.ResetsAt.Equal(limitResets) || l.AutoResume != nil {
		t.Fatalf("limit = %+v, want the 5-hour limit resetting at %v", l, limitResets)
	}
	// Once lifted with nothing waiting, the phone is told no more.
	c.set(limitResets)
	deadline := time.After(3 * time.Second)
	for {
		e, ok := f.maybeEvent(protocol.EventChatState, 3*time.Second)
		var st protocol.ChatStateEvent
		if ok && json.Unmarshal(e.Body, &st) == nil && st.Limit == nil {
			break
		}
		select {
		case <-deadline:
			t.Fatal("the phone was not told the limit was lifted")
		default:
		}
	}
}

// The message goes out once, a minute after the reset, and the limit is
// gone after it.
func TestAutoResumeSendsOnce(t *testing.T) {
	c := newClock(limitHit.Add(time.Minute))
	f := newFixture(t, setup{opts: resumeOpts(c, filepath.Join(t.TempDir(), "resume.json"), nil)})
	f.appendTranscript(t, limitLine(denverLimit))
	l := f.setResume(t, true, "继续")
	if l == nil || l.AutoResume == nil || l.AutoResume.Text != "继续" || !l.AutoResume.At.Equal(limitResets.Add(time.Minute)) {
		t.Fatalf("limit = %+v, want 继续 at a minute past the reset", l)
	}
	if got := f.openLimit(t); got == nil || got.AutoResume == nil {
		t.Fatalf("chat.open limit = %+v, want the message waiting", got)
	}
	c.set(limitResets.Add(59 * time.Second))
	time.Sleep(300 * time.Millisecond)
	if strings.Contains(f.keyLog(), "继续") {
		t.Fatal("sent before its time")
	}
	c.set(limitResets.Add(time.Minute))
	f.waitKeys(t, "\x1b[200~继续\x1b[201~\r")
	time.Sleep(500 * time.Millisecond)
	if n := strings.Count(f.keyLog(), "继续"); n != 1 {
		t.Fatalf("sent %d times, want once", n)
	}
	if got := f.openLimit(t); got != nil {
		t.Fatalf("limit after the message = %+v", got)
	}
}

// Something else sent before the time drops the message; turning it off
// does too.
func TestAutoResumeDropped(t *testing.T) {
	c := newClock(limitHit.Add(time.Minute))
	f := newFixture(t, setup{opts: resumeOpts(c, "", nil)})
	f.appendTranscript(t, limitLine(denverLimit))
	f.setResume(t, true, "继续")
	f.appendTranscript(t, userLine("我自己发了"))
	c.set(limitResets.Add(2 * time.Minute))
	time.Sleep(500 * time.Millisecond)
	if strings.Contains(f.keyLog(), "继续") {
		t.Fatal("sent although the limit was no longer the last record")
	}

	g := newFixture(t, setup{opts: resumeOpts(newClock(limitHit.Add(time.Minute)), "", nil)})
	g.appendTranscript(t, limitLine(denverLimit))
	g.setResume(t, true, "继续")
	if l := g.setResume(t, false, ""); l == nil || l.AutoResume != nil {
		t.Fatalf("limit after turning it off = %+v, want it without a message", l)
	}
}

// A new agent reads the waiting message back from the file and sends it.
func TestAutoResumeSurvivesRestart(t *testing.T) {
	c := newClock(limitHit.Add(time.Minute))
	file := filepath.Join(t.TempDir(), "resume.json")
	var opts chat.Options
	f := newFixture(t, setup{opts: resumeOpts(c, file, &opts)})
	f.appendTranscript(t, limitLine(denverLimit))
	f.setResume(t, true, "接着做")
	f.h.Close()
	if info, err := os.Stat(file); err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("resume file: %v, mode %v", err, info)
	}
	f.h = chat.New(opts)
	t.Cleanup(f.h.Close)
	c.set(limitResets.Add(time.Minute))
	f.waitKeys(t, "\x1b[200~接着做\x1b[201~\r")
}

// A limit message without a time Go can read falls back on usage.get.
func TestLimitResetFromUsage(t *testing.T) {
	c := newClock(limitHit.Add(time.Minute))
	reset := limitHit.Add(3 * time.Hour)
	f := newFixture(t, setup{opts: func(o *chat.Options) {
		resumeOpts(c, "", nil)(o)
		o.Usage = func(ctx context.Context) protocol.UsageResponse {
			return protocol.UsageResponse{Available: true, FiveHour: &protocol.UsageWindow{ResetsAt: &reset}}
		}
	}})
	f.appendTranscript(t, limitLine("You've hit your 5-hour limit · resets soon (Mars/Base)"))
	if l := f.setResume(t, true, "继续"); l == nil || !l.ResetsAt.Equal(reset) || l.AutoResume == nil || !l.AutoResume.At.Equal(reset.Add(time.Minute)) {
		t.Fatalf("limit = %+v, want it to reset at %v as usage.get says", l, reset)
	}
	// The open chat learns it at its next state poll.
	deadline := time.Now().Add(3 * time.Second)
	for {
		if l := f.openLimit(t); l != nil && l.ResetsAt.Equal(reset) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the open chat never got the reset time from usage.get")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func TestAutoResumeRefused(t *testing.T) {
	f := newFixture(t, setup{opts: resumeOpts(newClock(limitHit), "", nil)})
	res := f.call(t, "c1", protocol.MethodChatAutoResume, protocol.ChatAutoResumeRequest{SessionID: shortID, On: true, Text: "继续"})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("without a limit: %+v, want invalidParams", res.Error)
	}
	f.appendTranscript(t, limitLine(denverLimit))
	res = f.call(t, "c1", protocol.MethodChatAutoResume, protocol.ChatAutoResumeRequest{SessionID: shortID, On: true, Text: "  "})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("empty text: %+v, want invalidParams", res.Error)
	}
}
