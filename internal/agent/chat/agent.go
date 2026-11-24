package chat

import (
	"context"
	"sort"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

var (
	errBadAgentID    = &opError{protocol.CodeInvalidParams, "子代理 id 格式不对"}
	errAgentNotFound = &opError{protocol.CodeNotFound, "找不到这个子代理的记录"}
)

// agentKey names the chat of subagent agentID of session id in
// Handler.agents.
func agentKey(id, agentID string) string {
	return id + "/" + agentID
}

// openAgent answers chat.open with an AgentID: the subagent's transcript,
// read only. The id is checked before anything else is looked at.
func (h *Handler) openAgent(ctx context.Context, req protocol.Envelope, body protocol.ChatOpenRequest) protocol.Envelope {
	if !transcript.ValidAgentID(body.AgentID) {
		return failWith(req, errBadAgentID)
	}
	if body.Before > 0 {
		target, err := h.lookup(ctx, body.SessionID)
		if err != nil {
			return failWith(req, err)
		}
		main, err := transcript.Find(h.opts.Source.ClaudeDir, target.SessionID)
		if err != nil {
			return failWith(req, errAgentNotFound)
		}
		f, err := transcript.Open(transcript.SubagentPath(main, body.AgentID))
		if err != nil {
			return failWith(req, errAgentNotFound)
		}
		items, more := f.Page(body.Before, protocol.ChatPageSize)
		return agent.Reply(req, protocol.ChatOpenResponse{Items: agentItems(items), HasMore: more})
	}

	key := agentKey(body.SessionID, body.AgentID)
	octx, attempt, finish := h.beginOpen(ctx, openAgentChat, req.Client, key)
	defer finish()
	target, err := h.lookup(octx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	main, err := transcript.Find(h.opts.Source.ClaudeDir, target.SessionID)
	if err != nil {
		return failWith(req, errAgentNotFound)
	}
	path := transcript.SubagentPath(main, body.AgentID)

	subID := h.nextSubID()
	var fresh *agentChat
	for {
		h.mu.Lock()
		if h.closed || attempt.canceled {
			closed := h.closed
			h.mu.Unlock()
			if fresh != nil {
				fresh.cancel()
			}
			if closed {
				return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
			}
			return agent.Fail(req, protocol.CodeInternal, "请求已取消")
		}
		c := h.agents[key]
		if c == nil && fresh != nil {
			c, fresh = fresh, nil
			h.agents[key] = c
			go c.run(h.statePolls(c.ctx, c.pollTarget))
		}
		if c != nil {
			c.mu.Lock()
			c.clients[req.Client] = subID
			c.target = target
			c.mu.Unlock()
			h.mu.Unlock()
			if fresh != nil {
				// Another phone opened it meanwhile.
				fresh.cancel()
			}
			return agent.Reply(req, c.snapshot(subID))
		}
		h.mu.Unlock()
		// The transcript is read without the lock held.
		f, err := transcript.Open(path)
		if err != nil {
			return failWith(req, errAgentNotFound)
		}
		fresh = h.newAgentChat(target, body.AgentID, f, transcript.WatchAgents(main))
	}
}

// allAgentItems is the whole transcript of subagent agentID of a session:
// the open chat's, or, without one, read from the file.
func (h *Handler) allAgentItems(ctx context.Context, sessionID, agentID string) ([]transcript.Item, error) {
	if !transcript.ValidAgentID(agentID) {
		return nil, errBadAgentID
	}
	target, err := h.lookup(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	h.mu.Lock()
	var f *transcript.Follower
	if c := h.agents[agentKey(target.ID, agentID)]; c != nil {
		c.mu.Lock()
		f = c.follower
		c.mu.Unlock()
	}
	h.mu.Unlock()
	if f == nil {
		main, err := transcript.Find(h.opts.Source.ClaudeDir, target.SessionID)
		if err != nil {
			return nil, errAgentNotFound
		}
		if f, err = transcript.Open(transcript.SubagentPath(main, agentID)); err != nil {
			return nil, errAgentNotFound
		}
	}
	items, _ := f.Page(0, int(^uint(0)>>1))
	return items, nil
}

// leaveAgent drops client from the subagent chat key when subID matches (or
// is empty), ending it when it was the last one. An empty subID also cancels
// an in-flight open of this client for this subagent.
func (h *Handler) leaveAgent(client, key, subID string) {
	h.mu.Lock()
	if subID == "" {
		for a := range h.opening {
			if a.kind == openAgentChat && a.client == client && a.id == key {
				a.canceled = true
				a.cancel()
			}
		}
	}
	var stop *agentChat
	if c := h.agents[key]; c != nil {
		c.mu.Lock()
		if cur, ok := c.clients[client]; ok && (subID == "" || cur == subID) {
			delete(c.clients, client)
			if len(c.clients) == 0 {
				delete(h.agents, key)
				stop = c
			}
		}
		c.mu.Unlock()
	}
	h.mu.Unlock()
	if stop != nil {
		stop.stop()
	}
}

// agentItems maps a subagent's items to the wire: its first item, the task
// the session gave it, becomes a notice titled 任务.
func agentItems(items []transcript.Item) []protocol.ChatItem {
	out := toProtocol(items)
	for i, it := range out {
		if it.Seq == 1 && it.Kind == protocol.ItemUser {
			out[i] = protocol.ChatItem{Seq: 1, Kind: protocol.ItemNotice, Title: "任务", Text: it.Text}
		}
	}
	return out
}

// agentChat is the open chat of a subagent. It never attaches: the phone
// only reads it.
type agentChat struct {
	h       *Handler
	id      string // short session id
	agentID string
	cancel  context.CancelFunc
	ctx     context.Context
	done    chan struct{}
	pushMu  sync.Mutex

	mu       sync.Mutex
	clients  map[string]string
	rev      int64
	follower *transcript.Follower
	// watch follows the session's own transcript, which tells whether the
	// subagent still runs.
	watch  *transcript.AgentWatch
	target discovery.Target
	// state last sent to the phones
	state protocol.SessionState
}

func (h *Handler) newAgentChat(t discovery.Target, agentID string, f *transcript.Follower, w *transcript.AgentWatch) *agentChat {
	ctx, cancel := context.WithCancel(context.Background())
	c := &agentChat{h: h, id: t.ID, agentID: agentID, ctx: ctx, cancel: cancel, done: make(chan struct{}),
		clients: map[string]string{}, rev: 1, follower: f, watch: w, target: t}
	c.state = c.computeState()
	return c
}

// computeState is working while the subagent runs, idle once it ended and
// stopped with the session.
func (c *agentChat) computeState() protocol.SessionState {
	c.mu.Lock()
	target := c.target
	c.mu.Unlock()
	if !target.Running {
		return protocol.StateStopped
	}
	if running, _ := c.watch.IsRunning(c.agentID); running {
		return protocol.StateWorking
	}
	return protocol.StateIdle
}

func (c *agentChat) snapshot(subID string) protocol.ChatOpenResponse {
	c.pushMu.Lock()
	defer c.pushMu.Unlock()
	state := c.computeState()
	items, more := c.follower.Page(0, protocol.ChatPageSize)
	c.mu.Lock()
	rev := c.rev
	c.mu.Unlock()
	return protocol.ChatOpenResponse{Items: agentItems(items), HasMore: more,
		ChatStatus: protocol.ChatStatus{State: state, Model: c.follower.Info().Model},
		Rev:        rev, SubID: subID}
}

func (c *agentChat) stop() {
	c.cancel()
	<-c.done
}

// run polls the subagent's transcript until the chat ends; polled signals
// each state poll done apart (see statePolls).
func (c *agentChat) run(polled <-chan struct{}) {
	defer close(c.done)
	poll := time.NewTicker(c.h.opts.PollInterval)
	defer poll.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-poll.C:
			c.pushMu.Lock()
			if items, err := c.follower.Poll(); err == nil && len(items) > 0 {
				c.mu.Lock()
				c.rev++
				rev := c.rev
				c.mu.Unlock()
				c.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{SessionID: c.id, AgentID: c.agentID, Items: agentItems(items), Rev: rev})
			}
			c.pushMu.Unlock()
			c.pushState()
		case <-polled:
			c.pushState()
		}
	}
}

// pollTarget reads the session's state; it runs apart from run.
func (c *agentChat) pollTarget(ctx context.Context) {
	if t, err := c.h.lookup(ctx, c.id); err == nil {
		c.mu.Lock()
		c.target = t
		c.mu.Unlock()
	}
}

// pushState sends chat.state when the state changed.
func (c *agentChat) pushState() {
	c.pushMu.Lock()
	defer c.pushMu.Unlock()
	state := c.computeState()
	c.mu.Lock()
	changed := state != c.state
	var rev int64
	if changed {
		c.state = state
		c.rev++
		rev = c.rev
	}
	c.mu.Unlock()
	if changed {
		c.pushAll(protocol.EventChatState, protocol.ChatStateEvent{SessionID: c.id, AgentID: c.agentID,
			ChatStatus: protocol.ChatStatus{State: state, Model: c.follower.Info().Model}, Rev: rev})
	}
}

func (c *agentChat) pushAll(method protocol.Method, body any) {
	c.mu.Lock()
	clients := make([]string, 0, len(c.clients))
	for cl := range c.clients {
		clients = append(clients, cl)
	}
	c.mu.Unlock()
	sort.Strings(clients)
	for _, cl := range clients {
		_ = agent.PushEvent(c.h.opts.Push, cl, method, body)
	}
}
