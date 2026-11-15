// Package sessions serves the session list methods: sessions.subscribe,
// sessions.unsubscribe, dirs.recent and the sessions.changed event.
package sessions

import (
	"bytes"
	"context"
	"encoding/json"
	"sort"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// DefaultInterval is how often the list is polled while someone is
// subscribed (design D5).
const DefaultInterval = 5 * time.Second

// pollTimeout bounds one `claude agents` run during polling.
const pollTimeout = 30 * time.Second

// Options configures a Handler.
type Options struct {
	Source discovery.Source
	Push   agent.Pusher
	// Interval defaults to DefaultInterval.
	Interval time.Duration
}

// Handler implements agent.Handler and agent.ClientTracker for the session
// list. It polls the source only while at least one phone connection is
// subscribed and pushes sessions.changed to subscribers when the list
// differs from the last one.
type Handler struct {
	opts Options

	mu     sync.Mutex
	subs   map[string]bool
	last   []byte // JSON of the list subscribers last saw
	closed bool
	cancel context.CancelFunc // stops the poller; nil while not polling
	done   chan struct{}      // closed once the poller has returned

	contexts contexts
}

// New returns a Handler; call Close when done.
func New(opts Options) *Handler {
	if opts.Interval <= 0 {
		opts.Interval = DefaultInterval
	}
	return &Handler{opts: opts, subs: map[string]bool{}}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method {
	return []protocol.Method{
		protocol.MethodSessionsSubscribe,
		protocol.MethodSessionsUnsubscribe,
		protocol.MethodDirsRecent,
	}
}

// Handle implements agent.Handler.
func (h *Handler) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	switch req.Method {
	case protocol.MethodSessionsSubscribe:
		return h.subscribe(ctx, req)
	case protocol.MethodSessionsUnsubscribe:
		h.unsubscribe(req.Client)
		return agent.Reply(req, struct{}{})
	case protocol.MethodDirsRecent:
		return agent.Reply(req, protocol.DirsRecentResponse{Dirs: h.opts.Source.RecentDirs()})
	default:
		return agent.FailUnknown(req)
	}
}

func (h *Handler) subscribe(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	closedErr := agent.Fail(req, protocol.CodeInternal, "会话列表已关闭，请重新连接")
	h.mu.Lock()
	closed := h.closed
	h.mu.Unlock()
	if closed {
		return closedErr
	}
	list, err := h.list(ctx)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, "读取会话列表失败，请检查目标机上的 claude 能否运行")
	}
	data, err := json.Marshal(list)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, agent.InternalMessage)
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return closedErr
	}
	if len(h.subs) == 0 {
		h.last = data
		pollCtx, cancel := context.WithCancel(context.Background())
		h.cancel, h.done = cancel, make(chan struct{})
		go h.poll(pollCtx, h.done)
	}
	h.subs[req.Client] = true
	return agent.Reply(req, json.RawMessage(data))
}

// ClientGone implements agent.ClientTracker.
func (h *Handler) ClientGone(client string) {
	h.unsubscribe(client)
}

// AllClientsGone implements agent.ClientTracker: it drops every
// subscription and stops polling; later subscribes work as usual.
func (h *Handler) AllClientsGone() {
	h.mu.Lock()
	h.subs = map[string]bool{}
	cancel, done := h.takePoller()
	h.mu.Unlock()
	halt(cancel, done)
}

// Close stops polling and drops all subscriptions for good: later
// subscribes get an error response.
func (h *Handler) Close() {
	h.mu.Lock()
	h.closed = true
	h.subs = map[string]bool{}
	cancel, done := h.takePoller()
	h.mu.Unlock()
	halt(cancel, done)
}

// unsubscribe removes client and, when it was the last subscriber, stops
// the poller, cancelling any claude call in flight.
func (h *Handler) unsubscribe(client string) {
	h.mu.Lock()
	var cancel context.CancelFunc
	var done chan struct{}
	if h.subs[client] {
		delete(h.subs, client)
		if len(h.subs) == 0 {
			cancel, done = h.takePoller()
		}
	}
	h.mu.Unlock()
	halt(cancel, done)
}

// takePoller detaches the running poller, if any; h.mu must be held.
func (h *Handler) takePoller() (context.CancelFunc, chan struct{}) {
	cancel, done := h.cancel, h.done
	h.cancel, h.done = nil, nil
	return cancel, done
}

// halt cancels a detached poller and waits until it has returned.
func halt(cancel context.CancelFunc, done chan struct{}) {
	if cancel == nil {
		return
	}
	cancel()
	<-done
}

func (h *Handler) poll(ctx context.Context, done chan<- struct{}) {
	defer close(done)
	ticker := time.NewTicker(h.opts.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
		callCtx, cancel := context.WithTimeout(ctx, pollTimeout)
		list, err := h.list(callCtx)
		cancel()
		if err != nil {
			continue
		}
		data, err := json.Marshal(list)
		if err != nil {
			continue
		}
		h.mu.Lock()
		if ctx.Err() != nil {
			h.mu.Unlock()
			return
		}
		if bytes.Equal(data, h.last) {
			h.mu.Unlock()
			continue
		}
		h.last = data
		clients := make([]string, 0, len(h.subs))
		for c := range h.subs {
			clients = append(clients, c)
		}
		h.mu.Unlock()
		sort.Strings(clients)
		for _, c := range clients {
			// data is valid JSON, so encoding cannot fail.
			_ = agent.PushEvent(h.opts.Push, c, protocol.EventSessionsChanged, json.RawMessage(data))
		}
	}
}

// list reads the session list, settles each blocked session against its
// transcript (design D5, D6) and adds each session's context window use.
func (h *Handler) list(ctx context.Context) (protocol.SessionList, error) {
	list, targets, err := h.opts.Source.ListTargets(ctx)
	if err != nil {
		return list, err
	}
	seen := make(map[string]bool, len(targets))
	for i, t := range targets {
		if t.Blocked() {
			list.Sessions[i].State = t.SessionState(transcript.HasPending(h.opts.Source.ClaudeDir, t.SessionID))
		}
		list.Sessions[i].Context = h.contexts.get(h.opts.Source.ClaudeDir, t.SessionID)
		seen[t.SessionID] = true
	}
	h.contexts.keep(seen)
	return list, nil
}
