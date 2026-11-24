package chat

import (
	"context"
	"encoding/json"
	"errors"
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
	h          *Handler
	id         string // short session id
	ctx        context.Context
	cancel     context.CancelFunc
	done       chan struct{}
	pushMu     sync.Mutex
	reattachMu sync.Mutex

	mu      sync.Mutex
	conn    *attach.Conn
	held    bool
	clients map[string]string // client -> subID
	rev     int64
	last    protocol.TermScreen // the screen last pushed
}

func (h *Handler) termOpen(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.TermOpenRequest
	if err := json.Unmarshal(req.Body, &body); err != nil || body.SessionID == "" {
		return failWith(req, errNoSessionID)
	}
	octx, attempt, finish := h.beginOpen(ctx, openTerm, req.Client, body.SessionID)
	defer finish()
	target, err := h.lookup(octx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	if !target.Running {
		return failWith(req, errStopped)
	}
	subID := h.nextSubID()
	var fresh *termView
	for {
		h.mu.Lock()
		if h.closed || attempt.canceled {
			closed := h.closed
			h.mu.Unlock()
			if fresh != nil {
				fresh.discard()
			}
			if closed {
				return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
			}
			return agent.Fail(req, protocol.CodeInternal, "请求已取消")
		}
		v := h.terms[target.ID]
		if v == nil && fresh != nil {
			v, fresh = fresh, nil
			h.terms[target.ID] = v
			go v.run()
		}
		if v != nil {
			v.mu.Lock()
			v.clients[req.Client] = subID
			v.mu.Unlock()
			h.mu.Unlock()
			if fresh != nil {
				// Another phone opened it meanwhile.
				fresh.discard()
			}
			return agent.Reply(req, v.snapshot(subID))
		}
		h.mu.Unlock()
		// Attaching may take seconds; it runs without the lock held.
		conn, err := h.pool.Acquire(octx, target.ID)
		if err != nil {
			return failWith(req, errAttach)
		}
		vctx, cancel := context.WithCancel(context.Background())
		fresh = &termView{
			h: h, id: target.ID, conn: conn, held: true, ctx: vctx, cancel: cancel,
			done: make(chan struct{}), clients: map[string]string{}, rev: 1,
			last: screenOf(target.ID, conn),
		}
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
		_, open = v.clients[req.Client]
		v.mu.Unlock()
	}
	h.mu.Unlock()
	if !open {
		return failWith(req, errTermClosed)
	}
	conn, err := v.ensureConn(ctx)
	if err != nil {
		return failWith(req, errAttach)
	}
	if err := conn.Send(ctx, []attach.Step{{Keys: keys}}); err != nil {
		if errors.Is(err, attach.ErrClosed) {
			if conn, err = v.ensureConn(ctx); err == nil {
				err = conn.Send(ctx, []attach.Step{{Keys: keys}})
			}
		}
		if err != nil {
			return failWith(req, errAttach)
		}
	}
	return agent.Reply(req, struct{}{})
}

func (h *Handler) termClose(req protocol.Envelope) protocol.Envelope {
	var body protocol.TermCloseRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	h.leaveTerm(req.Client, body.SessionID, body.SubID)
	return agent.Reply(req, struct{}{})
}

// leaveTerm drops client from the terminal view of session id when subID
// matches (or is empty), closing it when it was the last one. An empty subID
// also cancels an in-flight term.open of this client for this session.
func (h *Handler) leaveTerm(client, id, subID string) {
	h.mu.Lock()
	if subID == "" {
		for a := range h.opening {
			if a.kind == openTerm && a.client == client && a.id == id {
				a.canceled = true
				a.cancel()
			}
		}
	}
	var stop *termView
	if v := h.terms[id]; v != nil {
		v.mu.Lock()
		if cur, ok := v.clients[client]; ok && (subID == "" || cur == subID) {
			delete(v.clients, client)
			if len(v.clients) == 0 {
				delete(h.terms, id)
				stop = v
			}
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

func screenOf(id string, conn *attach.Conn) protocol.TermScreen {
	view := conn.View()
	return protocol.TermScreen{SessionID: id, Lines: view.Lines, CursorRow: view.Row, CursorCol: view.Col, Cursor: view.Cursor}
}

// snapshot returns the current screen for term.open without updating v.last.
func (v *termView) snapshot(subID string) protocol.TermScreen {
	v.pushMu.Lock()
	defer v.pushMu.Unlock()
	v.mu.Lock()
	conn := v.conn
	rev := v.rev
	last := v.last
	v.mu.Unlock()
	s := last
	if conn != nil {
		s = screenOf(v.id, conn)
	}
	s.Rev = rev
	s.SubID = subID
	return s
}

// ensureConn returns a live attach for v, releasing and re-acquiring when the
// previous attach process has exited while the session still runs.
func (v *termView) ensureConn(ctx context.Context) (*attach.Conn, error) {
	v.mu.Lock()
	c := v.conn
	v.mu.Unlock()
	if c != nil && !c.Ended() {
		return c, nil
	}
	v.reattachMu.Lock()
	defer v.reattachMu.Unlock()
	v.mu.Lock()
	c = v.conn
	if c != nil && !c.Ended() {
		v.mu.Unlock()
		return c, nil
	}
	hadHeld := v.held
	v.held = false
	v.conn = nil
	v.mu.Unlock()
	if hadHeld {
		v.h.pool.Release(v.id)
	}
	if v.ctx.Err() != nil {
		return nil, attach.ErrClosed
	}
	acqCtx, cancel := context.WithCancel(v.ctx)
	defer cancel()
	stopWatch := context.AfterFunc(ctx, cancel)
	defer stopWatch()
	target, err := v.h.lookup(acqCtx, v.id)
	if err != nil || !target.Running {
		return nil, attach.ErrClosed
	}
	nc, err := v.h.pool.Acquire(acqCtx, v.id)
	if err != nil {
		return nil, err
	}
	v.mu.Lock()
	if v.ctx.Err() != nil {
		v.mu.Unlock()
		v.h.pool.Release(v.id)
		return nil, attach.ErrClosed
	}
	v.conn = nc
	v.held = true
	v.mu.Unlock()
	return nc, nil
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
		conn, err := v.ensureConn(v.ctx)
		if err != nil {
			continue
		}
		v.pushMu.Lock()
		s := screenOf(v.id, conn)
		v.mu.Lock()
		changed := !sameScreen(s, v.last)
		if changed {
			v.last = s
			v.rev++
			s.Rev = v.rev
		}
		clients := make([]string, 0, len(v.clients))
		for c := range v.clients {
			clients = append(clients, c)
		}
		v.mu.Unlock()
		if changed {
			sort.Strings(clients)
			for _, c := range clients {
				_ = agent.PushEvent(v.h.opts.Push, c, protocol.EventTermScreen, s)
			}
		}
		v.pushMu.Unlock()
	}
}

// stop ends the pushes and gives the attach back to the pool.
func (v *termView) stop() {
	v.cancel()
	<-v.done
	v.reattachMu.Lock()
	v.mu.Lock()
	held := v.held
	v.held = false
	v.conn = nil
	v.mu.Unlock()
	v.reattachMu.Unlock()
	if held {
		v.h.pool.Release(v.id)
	}
}

// discard gives back the attach of a view that never ran.
func (v *termView) discard() {
	v.cancel()
	v.mu.Lock()
	held := v.held
	v.held = false
	v.conn = nil
	v.mu.Unlock()
	if held {
		v.h.pool.Release(v.id)
	}
}
