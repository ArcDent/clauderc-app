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
	target, err := h.lookup(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	main, err := transcript.Find(h.opts.Source.ClaudeDir, target.SessionID)
	if err != nil {
		return failWith(req, errAgentNotFound)
	}
	path := transcript.SubagentPath(main, body.AgentID)

	if body.Before > 0 {
		f, err := transcript.Open(path)
		if err != nil {
			return failWith(req, errAgentNotFound)
		}
		items, more := f.Page(body.Before, protocol.ChatPageSize)
		return agent.Reply(req, protocol.ChatOpenResponse{Items: agentItems(items), HasMore: more})
	}

	key := agentKey(target.ID, body.AgentID)
	var fresh *agentChat
	for {
		h.mu.Lock()
		if h.closed {
			h.mu.Unlock()
			if fresh != nil {
				fresh.cancel()
			}
			return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
		}
		c := h.agents[key]
		if c == nil && fresh != nil {
			c, fresh = fresh, nil
			h.agents[key] = c
			go c.run()
		}
		if c != nil {
			c.mu.Lock()
			c.clients[req.Client] = true
			c.target = target
			c.mu.Unlock()
			h.mu.Unlock()
			if fresh != nil {
				// Another phone opened it meanwhile.
				fresh.cancel()
			}
			return agent.Reply(req, c.snapshot())
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

// leaveAgent drops client from the subagent chat key, ending it when it was
// the last one.
func (h *Handler) leaveAgent(client, key string) {
	h.mu.Lock()
	var stop *agentChat
	if c := h.agents[key]; c != nil {
		c.mu.Lock()
		delete(c.clients, client)
		if len(c.clients) == 0 {
			delete(h.agents, key)
			stop = c
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

	mu       sync.Mutex
	clients  map[string]bool
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
		clients: map[string]bool{}, follower: f, watch: w, target: t}
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

func (c *agentChat) snapshot() protocol.ChatOpenResponse {
	state := c.computeState()
	items, more := c.follower.Page(0, protocol.ChatPageSize)
	c.mu.Lock()
	c.state = state
	c.mu.Unlock()
	return protocol.ChatOpenResponse{Items: agentItems(items), HasMore: more,
		ChatStatus: protocol.ChatStatus{State: state, Model: c.follower.Info().Model}}
}

func (c *agentChat) stop() {
	c.cancel()
	<-c.done
}

func (c *agentChat) run() {
	defer close(c.done)
	poll := time.NewTicker(c.h.opts.PollInterval)
	defer poll.Stop()
	state := time.NewTicker(c.h.opts.StateInterval)
	defer state.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-poll.C:
			if items, err := c.follower.Poll(); err == nil && len(items) > 0 {
				c.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{SessionID: c.id, AgentID: c.agentID, Items: agentItems(items)})
			}
			c.pushState()
		case <-state.C:
			if t, err := c.h.lookup(c.ctx, c.id); err == nil {
				c.mu.Lock()
				c.target = t
				c.mu.Unlock()
			}
			c.pushState()
		}
	}
}

// pushState sends chat.state when the state changed.
func (c *agentChat) pushState() {
	state := c.computeState()
	c.mu.Lock()
	changed := state != c.state
	c.state = state
	c.mu.Unlock()
	if changed {
		c.pushAll(protocol.EventChatState, protocol.ChatStateEvent{SessionID: c.id, AgentID: c.agentID,
			ChatStatus: protocol.ChatStatus{State: state, Model: c.follower.Info().Model}})
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
