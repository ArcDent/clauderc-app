package chat

import (
	"context"
	"encoding/json"
	"slices"
	"sort"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/protocol"
)

// termKeys are the bytes typed for the keys of the terminal view; the
// digits 0 to 9 type themselves.
var termKeys = map[protocol.TermKey][]byte{
	protocol.TermKeyUp:        []byte("\x1b[A"),
	protocol.TermKeyDown:      []byte("\x1b[B"),
	protocol.TermKeyRight:     []byte("\x1b[C"),
	protocol.TermKeyLeft:      []byte("\x1b[D"),
	protocol.TermKeyEnter:     []byte("\r"),
	protocol.TermKeyEsc:       []byte("\x1b"),
	protocol.TermKeyTab:       []byte("\t"),
	protocol.TermKeyBackspace: []byte("\x7f"),
}

// termKeyBytes returns what key types, or false for a key not offered.
func termKeyBytes(key protocol.TermKey) ([]byte, bool) {
	if b, ok := termKeys[key]; ok {
		return b, true
	}
	if len(key) == 1 && key[0] >= '0' && key[0] <= '9' {
		return []byte(key), true
	}
	return nil, false
}

var (
	errBadKey     = &opError{protocol.CodeInvalidParams, "终端视图不支持这个按键"}
	errTermClosed = &opError{protocol.CodeInvalidParams, "终端视图没有打开"}
	errAttach     = &opError{protocol.CodeInternal, "接入会话失败，请稍后重试"}
)

// termInterval is how often an open terminal view compares the screen
// with the one last pushed, which bounds term.screen to
// protocol.TermScreensPerSecond.
const termInterval = time.Second / protocol.TermScreensPerSecond

// termView is the open terminal view of one session. It holds the
// session's hidden attach, shared with the chat, until the last phone
// closes it.
type termView struct {
	h      *Handler
	id     string // short session id
	conn   *attach.Conn
	ctx    context.Context
	cancel context.CancelFunc
	done   chan struct{}

	mu      sync.Mutex
	clients map[string]bool
	last    protocol.TermScreen // the screen last pushed
}

func (h *Handler) termOpen(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.TermOpenRequest
	if err := json.Unmarshal(req.Body, &body); err != nil || body.SessionID == "" {
		return failWith(req, errNoSessionID)
	}
	target, err := h.lookup(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	if !target.Running {
		return failWith(req, errStopped)
	}
	var fresh *termView
	for {
		h.mu.Lock()
		if h.closed {
			h.mu.Unlock()
			if fresh != nil {
				fresh.discard()
			}
			return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
		}
		v := h.terms[target.ID]
		if v == nil && fresh != nil {
			v, fresh = fresh, nil
			h.terms[target.ID] = v
			go v.run()
		}
		if v != nil {
			v.mu.Lock()
			v.clients[req.Client] = true
			v.mu.Unlock()
			h.mu.Unlock()
			if fresh != nil {
				// Another phone opened it meanwhile.
				fresh.discard()
			}
			return agent.Reply(req, v.screen())
		}
		h.mu.Unlock()
		// Attaching may take seconds; it runs without the lock held.
		conn, err := h.pool.Acquire(ctx, target.ID)
		if err != nil {
			return failWith(req, errAttach)
		}
		vctx, cancel := context.WithCancel(context.Background())
		fresh = &termView{h: h, id: target.ID, conn: conn, ctx: vctx, cancel: cancel, done: make(chan struct{}), clients: map[string]bool{}}
	}
}

func (h *Handler) termKey(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.TermKeyRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	keys, ok := termKeyBytes(body.Key)
	if !ok {
		return failWith(req, errBadKey)
	}
	h.mu.Lock()
	v := h.terms[body.SessionID]
	open := false
	if v != nil {
		v.mu.Lock()
		open = v.clients[req.Client]
		v.mu.Unlock()
	}
	h.mu.Unlock()
	if !open {
		return failWith(req, errTermClosed)
	}
	if err := v.conn.Send(ctx, []attach.Step{{Keys: keys}}); err != nil {
		return failWith(req, errAttach)
	}
	return agent.Reply(req, struct{}{})
}

func (h *Handler) termClose(req protocol.Envelope) protocol.Envelope {
	var body protocol.TermCloseRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	h.leaveTerm(req.Client, body.SessionID)
	return agent.Reply(req, struct{}{})
}

// leaveTerm drops client from the terminal view of session id, closing it
// when it was the last one.
func (h *Handler) leaveTerm(client, id string) {
	h.mu.Lock()
	var stop *termView
	if v := h.terms[id]; v != nil {
		v.mu.Lock()
		delete(v.clients, client)
		if len(v.clients) == 0 {
			delete(h.terms, id)
			stop = v
		}
		v.mu.Unlock()
	}
	h.mu.Unlock()
	if stop != nil {
		stop.stop()
	}
}

// dropTermClient drops client from every terminal view and returns the
// views it was the last one of; h.mu must be held.
func (h *Handler) dropTermClient(client string) []*termView {
	var stops []*termView
	for id, v := range h.terms {
		v.mu.Lock()
		delete(v.clients, client)
		if len(v.clients) == 0 {
			delete(h.terms, id)
			stops = append(stops, v)
		}
		v.mu.Unlock()
	}
	return stops
}

// screen is the current screen of the view's attach.
func (v *termView) screen() protocol.TermScreen {
	view := v.conn.View()
	return protocol.TermScreen{SessionID: v.id, Lines: view.Lines, CursorRow: view.Row, CursorCol: view.Col, Cursor: view.Cursor}
}

func sameScreen(a, b protocol.TermScreen) bool {
	return a.CursorRow == b.CursorRow && a.CursorCol == b.CursorCol && a.Cursor == b.Cursor && slices.Equal(a.Lines, b.Lines)
}

func (v *termView) run() {
	defer close(v.done)
	tick := time.NewTicker(termInterval)
	defer tick.Stop()
	for {
		select {
		case <-v.ctx.Done():
			return
		case <-tick.C:
		}
		s := v.screen()
		v.mu.Lock()
		changed := !sameScreen(s, v.last)
		if changed {
			v.last = s
		}
		clients := make([]string, 0, len(v.clients))
		for c := range v.clients {
			clients = append(clients, c)
		}
		v.mu.Unlock()
		if !changed {
			continue
		}
		sort.Strings(clients)
		for _, c := range clients {
			_ = agent.PushEvent(v.h.opts.Push, c, protocol.EventTermScreen, s)
		}
	}
}

// stop ends the pushes and gives the attach back to the pool.
func (v *termView) stop() {
	v.cancel()
	<-v.done
	v.h.pool.Release(v.id)
}

// discard gives back the attach of a view that never ran.
func (v *termView) discard() {
	v.cancel()
	v.h.pool.Release(v.id)
}
