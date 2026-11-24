package chat_test

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
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
	t.Parallel()
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
	t.Parallel()
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
	t.Parallel()
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
	t.Parallel()
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
	t.Parallel()
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
	t.Parallel()
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

// wakeFunc is a chat.Waker.
type wakeFunc func(ctx context.Context, sessionID string, hold func(context.Context, string) (func(), error)) error

func (w wakeFunc) Wake(ctx context.Context, sessionID string, hold func(context.Context, string) (func(), error)) error {
	return w(ctx, sessionID, hold)
}

// sendingResume starts sending the waiting message 继续 of a stopped
// session whose limit was lifted, and returns once the Waker has it. The
// Waker then waits for release and returns what wake does.
func sendingResume(t *testing.T, wake func(f *fixture, hold func(context.Context, string) (func(), error)) error) (f *fixture, c *clock, file string, release func()) {
	t.Helper()
	c = newClock(limitHit.Add(time.Minute))
	file = filepath.Join(t.TempDir(), "resume.json")
	waking, released := make(chan struct{}), make(chan struct{})
	var wakeOnce, releaseOnce sync.Once
	f = newFixture(t, setup{status: "idle", state: "done", pid: "null", opts: func(o *chat.Options) {
		resumeOpts(c, file, nil)(o)
		o.Waker = wakeFunc(func(_ context.Context, _ string, hold func(context.Context, string) (func(), error)) error {
			wakeOnce.Do(func() { close(waking) })
			<-released
			return wake(f, hold)
		})
	}})
	release = func() { releaseOnce.Do(func() { close(released) }) }
	t.Cleanup(release)
	f.appendTranscript(t, limitLine(denverLimit))
	f.setResume(t, true, "继续")
	c.set(limitResets.Add(time.Minute))
	select {
	case <-waking:
	case <-time.After(5 * time.Second):
		t.Fatal("the waiting message was not sent")
	}
	return f, c, file, release
}

// resumeTexts reads the texts of the waiting messages from the resume
// file, by short session id.
func resumeTexts(t *testing.T, file string) map[string]string {
	t.Helper()
	var entries map[string]struct {
		Text string `json:"text"`
	}
	if b, err := os.ReadFile(file); err == nil {
		if err := json.Unmarshal(b, &entries); err != nil {
			t.Fatal(err)
		}
	}
	out := map[string]string{}
	for id, e := range entries {
		out[id] = e.Text
	}
	return out
}

// Turned off while its message was being sent, an automatic message that
// then failed to go out does not come back.
func TestAutoResumeTurnedOffWhileSending(t *testing.T) {
	t.Parallel()
	f, _, file, release := sendingResume(t, func(*fixture, func(context.Context, string) (func(), error)) error {
		return errors.New("resume failed")
	})
	f.setResume(t, false, "")
	release()
	time.Sleep(500 * time.Millisecond)
	if got := resumeTexts(t, file); len(got) != 0 {
		t.Fatalf("waiting messages = %v, want none", got)
	}
}

// Changed while its message was being sent, an automatic message that
// then went out keeps the new setting.
func TestAutoResumeChangedWhileSending(t *testing.T) {
	t.Parallel()
	f, c, file, release := sendingResume(t, func(f *fixture, _ func(context.Context, string) (func(), error)) error {
		f.setAgents(t, "idle", "done", "7") // the session runs again
		return nil
	})
	// Not due, the new setting stays to be looked at.
	c.set(limitResets)
	f.setResume(t, true, "换个说法")
	release()
	f.waitKeys(t, "\x1b[200~继续\x1b[201~\r")
	time.Sleep(500 * time.Millisecond)
	if got := resumeTexts(t, file); got[shortID] != "换个说法" {
		t.Fatalf("waiting messages = %v, want 换个说法 kept", got)
	}
}

// Close waits for the automatic message being sent before it closes the
// attaches.
func TestCloseWaitsForAutoResume(t *testing.T) {
	t.Parallel()
	held := make(chan error, 1)
	f, _, _, release := sendingResume(t, func(_ *fixture, hold func(context.Context, string) (func(), error)) error {
		done, err := hold(context.Background(), shortID)
		if err == nil {
			done()
		}
		held <- err
		return errors.New("resume failed")
	})
	closed := make(chan struct{})
	go func() {
		f.h.Close()
		close(closed)
	}()
	select {
	case <-closed:
		t.Fatal("Close returned while the message was being sent")
	case <-time.After(300 * time.Millisecond):
	}
	release()
	select {
	case <-closed:
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not return once the message ended")
	}
	if err := <-held; err != nil {
		t.Fatalf("the attaches were closed before the message ended: %v", err)
	}
}

// A text update that arrives at the agent while waiting on usage.get cannot
// race past a turn-off for the same session and turn auto-resume back on.
func TestAutoResumeSerializedPerSession(t *testing.T) {
	t.Parallel()
	c := newClock(limitHit.Add(time.Minute))
	reset := limitHit.Add(3 * time.Hour)
	file := filepath.Join(t.TempDir(), "resume.json")
	inUsage, releaseUsage := make(chan struct{}), make(chan struct{})
	var once sync.Once
	f := newFixture(t, setup{opts: func(o *chat.Options) {
		resumeOpts(c, file, nil)(o)
		o.Usage = func(ctx context.Context) protocol.UsageResponse {
			once.Do(func() { close(inUsage) })
			<-releaseUsage
			return protocol.UsageResponse{Available: true, FiveHour: &protocol.UsageWindow{ResetsAt: &reset}}
		}
	}})
	f.appendTranscript(t, limitLine("You've hit your 5-hour limit · resets soon (Mars/Base)"))

	editDone := make(chan protocol.Envelope, 1)
	go func() {
		editDone <- f.call(t, "c1", protocol.MethodChatAutoResume, protocol.ChatAutoResumeRequest{SessionID: shortID, On: true, Text: "换个说法"})
	}()
	select {
	case <-inUsage:
	case <-time.After(5 * time.Second):
		t.Fatal("edit request never reached usage.get")
	}

	offDone := make(chan protocol.Envelope, 1)
	go func() {
		offDone <- f.call(t, "c1", protocol.MethodChatAutoResume, protocol.ChatAutoResumeRequest{SessionID: shortID, On: false})
	}()
	select {
	case <-offDone:
		t.Fatal("turn-off bypassed the in-flight edit instead of waiting its turn")
	case <-time.After(150 * time.Millisecond):
	}

	close(releaseUsage)
	if res := <-editDone; res.Error != nil {
		t.Fatalf("edit error: %+v", res.Error)
	}
	offRes := <-offDone
	if offRes.Error != nil {
		t.Fatalf("turn-off error: %+v", offRes.Error)
	}
	var body protocol.ChatAutoResumeResponse
	if err := json.Unmarshal(offRes.Body, &body); err != nil {
		t.Fatal(err)
	}
	if body.Limit == nil || body.Limit.AutoResume != nil {
		t.Fatalf("turn-off reply limit = %+v, want auto-resume off", body.Limit)
	}
	if got := resumeTexts(t, file); len(got) != 0 {
		t.Fatalf("saved resumes = %v, want none", got)
	}
	c.set(reset.Add(2 * time.Minute))
	time.Sleep(300 * time.Millisecond)
	if strings.Contains(f.keyLog(), "换个说法") {
		t.Fatal("turned-off auto-resume still sent a message")
	}
}
