package chat

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// Defaults of the automatic message sent once a usage limit is lifted.
const (
	// resumeDelay is how long after the limit is lifted the message goes
	// out, so it does not land right on the edge and get refused again.
	resumeDelay = time.Minute
	// DefaultResumeInterval is how often due messages are looked for.
	DefaultResumeInterval = 15 * time.Second
	// resumeAttempts is how many times a message that failed to go out
	// is tried, a resumeDelay apart.
	resumeAttempts = 3
	// resumeTimeout bounds one attempt, waking the session included.
	resumeTimeout = 2 * time.Minute
)

// resumeEntry is one message waiting for a limit to be lifted.
type resumeEntry struct {
	// SessionID is the full session id, naming the transcript.
	SessionID string    `json:"sessionId"`
	Text      string    `json:"text"`
	At        time.Time `json:"at"`
	// LimitAt is the timestamp of the limit record the message waits on;
	// a transcript that no longer ends on it means something else was
	// sent, and the message is dropped.
	LimitAt  time.Time `json:"limitAt"`
	Attempts int       `json:"attempts,omitempty"`
}

// resumes holds the waiting messages by short session id and keeps them in
// a file, so they survive a restart of the agent.
type resumes struct {
	path string // empty keeps them in memory only

	mu      sync.Mutex
	entries map[string]resumeEntry
	sending map[string]bool
}

// loadResumes reads path; a missing or unreadable file starts empty.
func loadResumes(path string) *resumes {
	r := &resumes{path: path, entries: map[string]resumeEntry{}, sending: map[string]bool{}}
	if path == "" {
		return r
	}
	if b, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(b, &r.entries)
		if r.entries == nil {
			r.entries = map[string]resumeEntry{}
		}
	}
	return r
}

func (r *resumes) get(id string) (resumeEntry, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[id]
	return e, ok
}

func (r *resumes) set(id string, e resumeEntry) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.entries[id] = e
	return r.saveLocked()
}

func (r *resumes) drop(id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.entries[id]; !ok {
		return nil
	}
	delete(r.entries, id)
	return r.saveLocked()
}

// update replaces the entry of id with next, or drops it when next is
// nil, only while the entry is still was: a message being sent leaves
// alone what chat.autoResume set or turned off meanwhile.
func (r *resumes) update(id string, was resumeEntry, next *resumeEntry) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if cur, ok := r.entries[id]; !ok || !cur.same(was) {
		return nil
	}
	if next == nil {
		delete(r.entries, id)
	} else {
		r.entries[id] = *next
	}
	return r.saveLocked()
}

func (e resumeEntry) same(o resumeEntry) bool {
	return e.SessionID == o.SessionID && e.Text == o.Text && e.At.Equal(o.At) && e.LimitAt.Equal(o.LimitAt) && e.Attempts == o.Attempts
}

// due marks the entries whose time has come as being sent and returns
// them; done must be called for each.
func (r *resumes) due(now time.Time) map[string]resumeEntry {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]resumeEntry{}
	for id, e := range r.entries {
		if !r.sending[id] && !now.Before(e.At) {
			r.sending[id] = true
			out[id] = e
		}
	}
	return out
}

func (r *resumes) done(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.sending, id)
}

// saveLocked writes the entries to a temporary file readable only by the
// agent's user and renames it over path; r.mu must be held.
func (r *resumes) saveLocked() error {
	if r.path == "" {
		return nil
	}
	b, err := json.Marshal(r.entries)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(r.path), 0o700); err != nil {
		return err
	}
	tmp := r.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, r.path)
}

// limitStatus is what the phone is told about limit l of session id:
// nothing when its reset time is unknown, or once it has been lifted with
// no message waiting for it. reset stands in for an unknown l.ResetsAt.
func (h *Handler) limitStatus(id string, l *transcript.Limit, reset time.Time) *protocol.Limit {
	if l == nil {
		return nil
	}
	if !l.ResetsAt.IsZero() {
		reset = l.ResetsAt
	}
	if reset.IsZero() {
		return nil
	}
	out := &protocol.Limit{Window: l.Window, Text: l.Text, ResetsAt: reset.UTC()}
	if e, ok := h.resumes.get(id); ok && e.LimitAt.Equal(l.At) {
		out.AutoResume = &protocol.AutoResume{Text: e.Text, At: e.At.UTC()}
	} else if !h.opts.Now().Before(reset) {
		return nil
	}
	return out
}

// usageReset is the reset time usage.get gives for the window of l, the
// stand-in when the limit message gives none; zero when unknown.
func (h *Handler) usageReset(ctx context.Context, l *transcript.Limit) time.Time {
	if h.opts.Usage == nil || l == nil || !l.ResetsAt.IsZero() {
		return time.Time{}
	}
	u := h.opts.Usage(ctx)
	w := u.FiveHour
	switch l.Window {
	case transcript.WindowWeekly:
		w = u.SevenDay
	case transcript.WindowFiveHour:
	default:
		return time.Time{}
	}
	if w == nil || w.ResetsAt == nil {
		return time.Time{}
	}
	return *w.ResetsAt
}

// sameLimit reports whether a and b tell the phone the same.
func sameLimit(a, b *protocol.Limit) bool {
	if a == nil || b == nil {
		return a == b
	}
	if a.Window != b.Window || a.Text != b.Text || !a.ResetsAt.Equal(b.ResetsAt) {
		return false
	}
	if a.AutoResume == nil || b.AutoResume == nil {
		return a.AutoResume == b.AutoResume
	}
	return a.AutoResume.Text == b.AutoResume.Text && a.AutoResume.At.Equal(b.AutoResume.At)
}

// sameStatus reports whether a and b tell the phone the same.
func sameStatus(a, b protocol.ChatStatus) bool {
	la, lb := a.Limit, b.Limit
	a.Limit, b.Limit = nil, nil
	return a == b && sameLimit(la, lb)
}

var (
	errNotLimited = &opError{protocol.CodeInvalidParams, "会话没有停在用量限额上"}
	errNoReset    = &opError{protocol.CodeInvalidParams, "读不到这次限额的重置时间"}
)

// lockAutoResume serializes chat.autoResume per session so an earlier edit
// waiting on usage.get cannot re-enable auto-resume after a later turn-off.
func (h *Handler) lockAutoResume(ctx context.Context, id string) (func(), error) {
	h.mu.Lock()
	ch := h.autoResuming[id]
	if ch == nil {
		ch = make(chan struct{}, 1)
		h.autoResuming[id] = ch
	}
	h.mu.Unlock()
	select {
	case ch <- struct{}{}:
		return func() { <-ch }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// autoResume serves chat.autoResume.
func (h *Handler) autoResume(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatAutoResumeRequest
	if err := json.Unmarshal(req.Body, &body); err != nil || body.SessionID == "" {
		return failWith(req, errBadRequest)
	}
	target, err := h.lookup(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	unlock, err := h.lockAutoResume(ctx, target.ID)
	if err != nil {
		return failWith(req, err)
	}
	defer unlock()
	var limit *transcript.Limit
	if path, err := transcript.Find(h.opts.Source.ClaudeDir, target.SessionID); err == nil {
		if t, err := transcript.ReadFile(path); err == nil {
			limit = t.Info().Limit
		}
	}
	if !body.On {
		if err := h.resumes.drop(target.ID); err != nil {
			return agent.Fail(req, protocol.CodeInternal, "保存设定失败，请在目标机上查看日志")
		}
	} else {
		body.Text = attach.CleanText(body.Text)
		if msg := checkMessage(protocol.ChatSendRequest{SessionID: body.SessionID, Text: body.Text}); msg != "" {
			return agent.Fail(req, protocol.CodeInvalidParams, msg)
		}
		if limit == nil {
			return failWith(req, errNotLimited)
		}
		reset := limit.ResetsAt
		if reset.IsZero() {
			reset = h.usageReset(ctx, limit)
		}
		if reset.IsZero() {
			return failWith(req, errNoReset)
		}
		e := resumeEntry{SessionID: target.SessionID, Text: strings.TrimSpace(body.Text), At: reset.Add(resumeDelay), LimitAt: limit.At}
		if err := h.resumes.set(target.ID, e); err != nil {
			return agent.Fail(req, protocol.CodeInternal, "保存设定失败，请在目标机上查看日志")
		}
	}
	h.mu.Lock()
	s := h.chats[target.ID]
	h.mu.Unlock()
	var reset time.Time
	if s != nil {
		s.pushChanges()
		s.mu.Lock()
		reset = s.limitReset
		s.mu.Unlock()
	}
	if limit != nil && limit.ResetsAt.IsZero() && reset.IsZero() {
		reset = h.usageReset(ctx, limit)
	}
	return agent.Reply(req, protocol.ChatAutoResumeResponse{Limit: h.limitStatus(target.ID, limit, reset)})
}

// resumeLoop sends each waiting message once its time has come.
func (h *Handler) resumeLoop(ctx context.Context, done chan<- struct{}) {
	defer close(done)
	tick := time.NewTicker(h.opts.ResumeInterval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		for id, e := range h.resumes.due(h.opts.Now()) {
			// Close waits for it before it closes the attaches.
			h.bg.Add(1)
			go func() {
				defer h.bg.Done()
				defer h.resumes.done(id)
				h.resume(ctx, id, e)
			}()
		}
	}
}

// resume sends the waiting message of session id, unless its transcript
// no longer ends on the limit it waited for. A failed send is tried again
// a resumeDelay later, resumeAttempts times in all. What it writes back
// goes only over e as it was: chat.autoResume may change or drop it while
// the message is being sent.
func (h *Handler) resume(ctx context.Context, id string, e resumeEntry) {
	path, err := transcript.Find(h.opts.Source.ClaudeDir, e.SessionID)
	if err != nil {
		_ = h.resumes.update(id, e, nil)
		return
	}
	t, err := transcript.ReadFile(path)
	if err != nil {
		return
	}
	if l := t.Info().Limit; l == nil || !l.At.Equal(e.LimitAt) {
		_ = h.resumes.update(id, e, nil)
		return
	}
	sendCtx, cancel := context.WithTimeout(ctx, resumeTimeout)
	defer cancel()
	body, _ := json.Marshal(protocol.ChatSendRequest{SessionID: id, Text: e.Text})
	res := h.send(sendCtx, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "auto-resume", Method: protocol.MethodChatSend, Body: body})
	if res.Error == nil {
		_ = h.resumes.update(id, e, nil)
		return
	}
	if ctx.Err() != nil {
		// The agent is closing: the message waits for its next start.
		return
	}
	h.opts.Logger.Error("auto resume failed", "session", id, "code", res.Error.Code)
	next := e
	next.Attempts++
	if next.Attempts >= resumeAttempts {
		_ = h.resumes.update(id, e, nil)
		return
	}
	next.At = h.opts.Now().Add(resumeDelay)
	_ = h.resumes.update(id, e, &next)
}
