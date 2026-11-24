// Package sessions serves the session list methods: sessions.subscribe,
// sessions.unsubscribe, dirs.recent and the sessions.changed event.
package sessions

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
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

// DefaultListTimeout bounds one read of the list, its `claude agents`
// runs included, for sessions.subscribe and for each poll.
const DefaultListTimeout = 30 * time.Second

// errListFailed answers a subscribe whose read failed other than by
// running out of time.
var errListFailed = &protocol.Error{Code: protocol.CodeInternal, Message: "读取会话列表失败，请检查目标机上的 claude 能否运行"}

// Options configures a Handler.
type Options struct {
	Source discovery.Source
	Push   agent.Pusher
	// Interval defaults to DefaultInterval.
	Interval time.Duration
	// ListTimeout defaults to DefaultListTimeout.
	ListTimeout time.Duration
	// Dialog reports whether running session id shows a dialog such as
	// the MCP server approval; a blocked session that does is pending even
	// with nothing pending in its transcript. Nil checks nothing.
	Dialog func(ctx context.Context, id string) bool
}

// subAttempt tracks an in-flight sessions.subscribe so ClientGone,
// AllClientsGone or sessions.unsubscribe cancels it before it can register.
type subAttempt struct {
	client   string
	cancel   context.CancelFunc
	canceled bool
}

// Handler implements agent.Handler and agent.ClientTracker for the session
// list. It polls the source only while at least one phone connection is
// subscribed and pushes sessions.changed to subscribers when the list
// differs from the last one.
type Handler struct {
	opts Options

	mu      sync.Mutex
	subs    map[string]bool
	opening map[*subAttempt]struct{}
	last    []byte // JSON of the list subscribers last saw (without Rev)
	rev     int64  // bumped with every change pushed or observed
	closed  bool
	cancel  context.CancelFunc // stops the poller; nil while not polling
	done    chan struct{}      // closed once the poller has returned
	kick    chan struct{}      // wakes the poller for a read right away
	gen     uint64             // bumped by Refresh; a read begun before is dropped

	contexts contexts
	agents   agents
}

// New returns a Handler; call Close when done.
func New(opts Options) *Handler {
	if opts.Interval <= 0 {
		opts.Interval = DefaultInterval
	}
	if opts.ListTimeout <= 0 {
		opts.ListTimeout = DefaultListTimeout
	}
	return &Handler{opts: opts, subs: map[string]bool{}, opening: map[*subAttempt]struct{}{}, kick: make(chan struct{}, 1)}
}

// Refresh has the poller read the list again right away, after a session
// was created, stopped or removed, and push it when it changed. A read
// that began before the call is dropped, as it may still show the session
// as it was. Without subscribers nothing is read.
func (h *Handler) Refresh() {
	h.mu.Lock()
	h.gen++
	h.mu.Unlock()
	select {
	case h.kick <- struct{}{}:
	default:
	}
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
	octx, cancel := context.WithCancel(ctx)
	attempt := &subAttempt{client: req.Client, cancel: cancel}
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		cancel()
		return closedErr
	}
	startRev := h.rev
	h.opening[attempt] = struct{}{}
	h.mu.Unlock()
	defer func() {
		h.mu.Lock()
		delete(h.opening, attempt)
		h.mu.Unlock()
		cancel()
	}()

	list, err := h.list(octx)
	if err != nil {
		if !errors.As(err, new(*protocol.Error)) {
			err = errListFailed
		}
		return agent.FailErr(req, err)
	}
	list.Rev = 0
	plain, err := json.Marshal(list)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, agent.InternalMessage)
	}
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return closedErr
	}
	if attempt.canceled {
		h.mu.Unlock()
		return agent.Fail(req, protocol.CodeInternal, "请求已取消")
	}
	var replyRev int64
	if len(h.subs) == 0 {
		h.rev++
		replyRev = h.rev
		h.last = plain
		pollCtx, pcancel := context.WithCancel(context.Background())
		h.cancel, h.done = pcancel, make(chan struct{})
		go h.poll(pollCtx, h.done)
	} else {
		if h.rev > startRev {
			// A poll pushed a newer list while this subscribe read: tag this
			// snapshot with the rev from before that push so phones that got
			// the push drop it, and clear h.last if it differs so the next
			// poll still brings any new subscriber up to date.
			if startRev > 0 {
				replyRev = startRev
			} else {
				replyRev = 1
			}
			if !bytes.Equal(plain, h.last) {
				h.last = nil
			}
		} else {
			if !bytes.Equal(plain, h.last) {
				h.rev++
				// Clear the baseline so the next poll still pushes to every
				// other subscriber (and re-syncs if the state changes again).
				h.last = nil
			}
			replyRev = h.rev
		}
	}
	h.subs[req.Client] = true
	h.mu.Unlock()

	list.Rev = replyRev
	data, err := json.Marshal(list)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, agent.InternalMessage)
	}
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
	for a := range h.opening {
		a.canceled = true
		a.cancel()
	}
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
	for a := range h.opening {
		a.canceled = true
		a.cancel()
	}
	h.subs = map[string]bool{}
	cancel, done := h.takePoller()
	h.mu.Unlock()
	halt(cancel, done)
}

// unsubscribe removes client and, when it was the last subscriber, stops
// the poller, cancelling any claude call in flight.
func (h *Handler) unsubscribe(client string) {
	h.mu.Lock()
	for a := range h.opening {
		if a.client == client {
			a.canceled = true
			a.cancel()
		}
	}
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
		case <-h.kick:
		}
		h.mu.Lock()
		gen := h.gen
		h.mu.Unlock()
		list, err := h.list(ctx)
		if err != nil {
			continue
		}
		list.Rev = 0
		plain, err := json.Marshal(list)
		if err != nil {
			continue
		}
		h.mu.Lock()
		if ctx.Err() != nil {
			h.mu.Unlock()
			return
		}
		// Refresh was called meanwhile: this read may be stale, and the
		// kick it left reads again on the next turn.
		if h.gen != gen || bytes.Equal(plain, h.last) {
			h.mu.Unlock()
			continue
		}
		h.last = plain
		h.rev++
		rev := h.rev
		clients := make([]string, 0, len(h.subs))
		for c := range h.subs {
			clients = append(clients, c)
		}
		h.mu.Unlock()
		list.Rev = rev
		data, err := json.Marshal(list)
		if err != nil {
			continue
		}
		sort.Strings(clients)
		for _, c := range clients {
			// data is valid JSON, so encoding cannot fail.
			_ = agent.PushEvent(h.opts.Push, c, protocol.EventSessionsChanged, json.RawMessage(data))
		}
	}
}

// list reads the session list, settles each blocked session against its
// transcript (design D5, D6) and adds each session's context window use,
// git branch and, for a running session, its subagents that still run. A
// stopped session has none, even when a meta file was left unfinished.
// A read that runs past ListTimeout fails with CodeTimeout.
func (h *Handler) list(ctx context.Context) (protocol.SessionList, error) {
	ctx, cancel := context.WithTimeout(ctx, h.opts.ListTimeout)
	defer cancel()
	list, targets, err := h.opts.Source.ListTargets(ctx)
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			err = &protocol.Error{Code: protocol.CodeTimeout, Message: fmt.Sprintf("读取会话列表超过 %g 秒未完成", h.opts.ListTimeout.Seconds())}
		}
		return list, err
	}
	seen := make(map[string]bool, len(targets))
	running := map[string]bool{}
	for i, t := range targets {
		if t.Running {
			list.Sessions[i].Agents = h.agents.get(h.opts.Source.ClaudeDir, t.SessionID)
			running[t.SessionID] = true
		}
		if t.Blocked() {
			pending := transcript.HasPending(h.opts.Source.ClaudeDir, t.SessionID)
			if !pending && h.opts.Dialog != nil {
				pending = h.opts.Dialog(ctx, t.ID)
			}
			list.Sessions[i].State = t.SessionState(pending)
		}
		tail := h.contexts.get(h.opts.Source.ClaudeDir, t.SessionID)
		list.Sessions[i].Context = tail.Context
		// The transcript knows the branch the session is on now; the
		// worktree branch from discovery only stands in until it is read.
		if tail.Branch != "" {
			list.Sessions[i].Branch = tail.Branch
		}
		seen[t.SessionID] = true
	}
	h.contexts.keep(seen)
	h.agents.keep(running)
	return list, nil
}
