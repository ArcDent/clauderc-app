// Package chat serves the chat methods (design D3): chat.open and
// chat.close with the chat.items, chat.pending and chat.state events, and
// the operations typed into the session through its hidden attach.
//
// Transcript and screen content is never logged.
package chat

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// Defaults of Options (design D6, D7).
const (
	DefaultPollInterval    = time.Second
	DefaultStateInterval   = 2 * time.Second
	DefaultDeliveryTimeout = 5 * time.Second
	// stateTimeout bounds one `claude agents` run.
	stateTimeout = 30 * time.Second
)

// Options configures a Handler.
type Options struct {
	Source discovery.Source
	Push   agent.Pusher
	// Attach configures the hidden attaches; Command defaults to
	// {Source.ClaudePath, "attach", id}.
	Attach attach.Options
	// UploadDir receives pictures sent with messages; it defaults to
	// ~/.cache/clauderc/uploads.
	UploadDir string
	// ImageWait is the pause after pasting each picture path; it defaults
	// to attach.DefaultImageWait.
	ImageWait time.Duration
	// PollInterval is how often an open chat's transcript is read.
	PollInterval time.Duration
	// StateInterval is how often an open chat's session state is read.
	StateInterval time.Duration
	// DeliveryTimeout is how long chat.send waits for the message to show
	// up in the transcript.
	DeliveryTimeout time.Duration
	// Waker resumes stopped sessions for chat.send; nil refuses to send to
	// them.
	Waker Waker
}

// Waker resumes a stopped session, named by its full session id, so a
// message can be sent to it. It returns once the session runs.
type Waker interface {
	Wake(ctx context.Context, sessionID string) error
}

// Handler implements agent.Handler and agent.ClientTracker for chats.
type Handler struct {
	opts Options
	pool *attach.Pool

	mu     sync.Mutex
	chats  map[string]*session // by short session id
	closed bool
}

// New returns a Handler; call Close when done.
func New(opts Options) *Handler {
	if opts.Attach.Command == nil {
		claude := opts.Source.ClaudePath
		opts.Attach.Command = func(id string) []string { return []string{claude, "attach", id} }
	}
	if opts.ImageWait <= 0 {
		opts.ImageWait = attach.DefaultImageWait
	}
	if opts.PollInterval <= 0 {
		opts.PollInterval = DefaultPollInterval
	}
	if opts.StateInterval <= 0 {
		opts.StateInterval = DefaultStateInterval
	}
	if opts.DeliveryTimeout <= 0 {
		opts.DeliveryTimeout = DefaultDeliveryTimeout
	}
	return &Handler{opts: opts, pool: attach.NewPool(opts.Attach), chats: map[string]*session{}}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method {
	return []protocol.Method{
		protocol.MethodChatOpen,
		protocol.MethodChatClose,
		protocol.MethodChatSend,
		protocol.MethodChatAnswer,
		protocol.MethodChatInterrupt,
		protocol.MethodChatModels,
		protocol.MethodChatSetModel,
		protocol.MethodChatSetPermissionMode,
		protocol.MethodChatCommands,
	}
}

// Handle implements agent.Handler.
func (h *Handler) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	switch req.Method {
	case protocol.MethodChatOpen:
		return h.open(ctx, req)
	case protocol.MethodChatClose:
		var body protocol.ChatCloseRequest
		if err := json.Unmarshal(req.Body, &body); err != nil {
			return failWith(req, errBadRequest)
		}
		h.leave(req.Client, body.SessionID)
		return agent.Reply(req, struct{}{})
	case protocol.MethodChatSend:
		return h.send(ctx, req)
	case protocol.MethodChatAnswer:
		return h.answer(ctx, req)
	case protocol.MethodChatInterrupt:
		return h.interrupt(ctx, req)
	case protocol.MethodChatModels:
		return h.models(ctx, req)
	case protocol.MethodChatSetModel:
		return h.setModel(ctx, req)
	case protocol.MethodChatSetPermissionMode:
		return h.setPermissionMode(ctx, req)
	case protocol.MethodChatCommands:
		return h.commands(ctx, req)
	default:
		return agent.Fail(req, protocol.CodeUnknownMethod, "目标机不支持这个操作："+string(req.Method))
	}
}

// errNotFound means the session is not a listed background session.
var errNotFound = errors.New("no such session")

var errListFailed = &opError{protocol.CodeInternal, "读取会话列表失败，请检查目标机上的 claude 能否运行"}

// lookup finds the background session with short id id.
func (h *Handler) lookup(ctx context.Context, id string) (discovery.Target, error) {
	ctx, cancel := context.WithTimeout(ctx, stateTimeout)
	defer cancel()
	targets, err := h.opts.Source.Targets(ctx)
	if err != nil {
		return discovery.Target{}, errListFailed
	}
	for _, t := range targets {
		if t.ID == id {
			return t, nil
		}
	}
	return discovery.Target{}, errNotFound
}

func (h *Handler) open(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatOpenRequest
	if err := json.Unmarshal(req.Body, &body); err != nil || body.SessionID == "" {
		return failWith(req, errNoSessionID)
	}
	target, err := h.lookup(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}

	if body.Before > 0 {
		// An older page; the chat's subscription is unchanged.
		var items []transcript.Item
		var more bool
		if f := openFollower(h.opts.Source.ClaudeDir, target.SessionID); f != nil {
			items, more = f.Page(body.Before, protocol.ChatPageSize)
		}
		return agent.Reply(req, protocol.ChatOpenResponse{Items: toProtocol(items), HasMore: more})
	}

	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
	}
	s := h.chats[target.ID]
	if s == nil {
		s = h.newSession(target)
		h.chats[target.ID] = s
	}
	s.mu.Lock()
	s.clients[req.Client] = true
	s.target = target
	s.mu.Unlock()
	h.mu.Unlock()

	return agent.Reply(req, s.snapshot())
}

// leave drops client from the chat of session id, ending the chat when it
// was the last one.
func (h *Handler) leave(client, id string) {
	h.mu.Lock()
	s := h.chats[id]
	var stop *session
	if s != nil {
		s.mu.Lock()
		delete(s.clients, client)
		if len(s.clients) == 0 {
			delete(h.chats, id)
			stop = s
		}
		s.mu.Unlock()
	}
	h.mu.Unlock()
	if stop != nil {
		stop.stop()
	}
}

// ClientGone implements agent.ClientTracker.
func (h *Handler) ClientGone(client string) {
	h.mu.Lock()
	var stops []*session
	for id, s := range h.chats {
		s.mu.Lock()
		delete(s.clients, client)
		if len(s.clients) == 0 {
			delete(h.chats, id)
			stops = append(stops, s)
		}
		s.mu.Unlock()
	}
	h.mu.Unlock()
	for _, s := range stops {
		s.stop()
	}
}

// AllClientsGone implements agent.ClientTracker.
func (h *Handler) AllClientsGone() {
	h.mu.Lock()
	chats := h.chats
	h.chats = map[string]*session{}
	h.mu.Unlock()
	for _, s := range chats {
		s.stop()
	}
}

// Close ends every chat and attach for good.
func (h *Handler) Close() {
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
	h.AllClientsGone()
	h.pool.Close()
}

// openFollower opens the session's transcript, or returns nil while there
// is none.
func openFollower(claudeDir, sessionID string) *transcript.Follower {
	path, err := transcript.Find(claudeDir, sessionID)
	if err != nil {
		return nil
	}
	f, err := transcript.Open(path)
	if err != nil {
		return nil
	}
	return f
}

// toProtocol maps transcript items to the wire. The protocol has no
// command kind yet: a command becomes a tool row named after it, with its
// output as the result and a tool_use id starting with "command-", which
// the phone draws as the user's own bubble.
func toProtocol(items []transcript.Item) []protocol.ChatItem {
	out := make([]protocol.ChatItem, 0, len(items))
	for _, it := range items {
		ci := it.ChatItem
		if it.Kind == transcript.ItemCommand && it.Command != nil {
			c := it.Command
			input, _ := json.Marshal(map[string]string{"args": c.Args})
			tc := &protocol.ToolCall{
				ToolUseID: "command-" + strconv.FormatInt(it.Seq, 10),
				ToolName:  c.Name,
				Summary:   strings.TrimSpace(c.Name + " " + c.Args),
				Input:     input,
			}
			if c.Output != "" {
				tc.Result = &protocol.ToolResult{Content: c.Output}
			}
			ci = protocol.ChatItem{Seq: it.Seq, Kind: protocol.ItemTool, Tool: tc}
		}
		out = append(out, ci)
	}
	return out
}

// session is one open chat.
type session struct {
	h         *Handler
	id        string
	sessionID string
	cancel    context.CancelFunc
	done      chan struct{}

	mu       sync.Mutex
	clients  map[string]bool
	follower *transcript.Follower
	// target is the session as claude last reported it.
	target discovery.Target
	// screenMode is the permission mode last read from the status line;
	// it beats the transcript, which only learns the mode at stop.
	screenMode protocol.PermissionMode
	attached   bool
	attaching  bool
	// last status and pending prompt sent to the phones
	status  protocol.ChatStatus
	pending *protocol.Pending
}

func (h *Handler) newSession(t discovery.Target) *session {
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{
		h: h, id: t.ID, sessionID: t.SessionID, cancel: cancel, done: make(chan struct{}),
		clients:  map[string]bool{},
		follower: openFollower(h.opts.Source.ClaudeDir, t.SessionID),
		target:   t,
	}
	s.status, s.pending = s.compute()
	go s.run(ctx)
	return s
}

// compute derives the status and pending prompt; s.mu must be held.
func (s *session) compute() (protocol.ChatStatus, *protocol.Pending) {
	var info transcript.Info
	var pending *protocol.Pending
	if s.follower != nil {
		info = s.follower.Info()
		pending = s.follower.Pending(s.target.Blocked())
	}
	state := s.target.SessionState(pending != nil)
	mode := info.PermissionMode
	if s.screenMode != "" {
		mode = s.screenMode
	}
	return protocol.ChatStatus{State: state, Model: info.Model, PermissionMode: mode}, pending
}

// snapshot is the chat.open answer for the latest page.
func (s *session) snapshot() protocol.ChatOpenResponse {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status, s.pending = s.compute()
	var items []transcript.Item
	var more bool
	if s.follower != nil {
		items, more = s.follower.Page(0, protocol.ChatPageSize)
	}
	return protocol.ChatOpenResponse{Items: toProtocol(items), HasMore: more, ChatStatus: s.status, Pending: s.pending}
}

func (s *session) stop() {
	s.cancel()
	<-s.done
	s.mu.Lock()
	attached := s.attached
	s.attached = false
	s.mu.Unlock()
	if attached {
		s.h.pool.Release(s.id)
	}
}

func (s *session) run(ctx context.Context) {
	defer close(s.done)
	poll := time.NewTicker(s.h.opts.PollInterval)
	defer poll.Stop()
	state := time.NewTicker(s.h.opts.StateInterval)
	defer state.Stop()
	s.ensureAttach(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-poll.C:
			s.pollTranscript()
		case <-state.C:
			s.pollState(ctx)
			s.ensureAttach(ctx)
		}
		s.pushChanges()
	}
}

func (s *session) pollTranscript() {
	s.mu.Lock()
	f := s.follower
	s.mu.Unlock()
	var items []transcript.Item
	if f == nil {
		f = openFollower(s.h.opts.Source.ClaudeDir, s.sessionID)
		if f == nil {
			return
		}
		items, _ = f.Page(0, int(^uint(0)>>1))
		s.mu.Lock()
		s.follower = f
		s.mu.Unlock()
	} else {
		var err error
		if items, err = f.Poll(); err != nil {
			return
		}
	}
	if len(items) > 0 {
		s.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{SessionID: s.id, Items: toProtocol(items)})
	}
}

func (s *session) pollState(ctx context.Context) {
	t, err := s.h.lookup(ctx, s.id)
	if err != nil {
		return
	}
	s.mu.Lock()
	s.target = t
	s.mu.Unlock()
}

// ensureAttach starts the hidden attach of a running session in the
// background, so typing later does not wait for it.
func (s *session) ensureAttach(ctx context.Context) {
	s.mu.Lock()
	if s.attached || s.attaching || !s.target.Running {
		s.mu.Unlock()
		return
	}
	s.attaching = true
	s.mu.Unlock()
	go func() {
		conn, err := s.h.pool.Acquire(ctx, s.id)
		s.mu.Lock()
		s.attaching = false
		keep := err == nil && ctx.Err() == nil
		s.attached = s.attached || keep
		s.mu.Unlock()
		if err == nil && !keep {
			s.h.pool.Release(s.id)
		}
		if !keep {
			return
		}
		// With an attach the status line gives the permission mode.
		_ = conn.Do(ctx, func(t *attach.Term) error {
			if mode, ok, err := readMode(t); err == nil && ok {
				s.mu.Lock()
				s.screenMode = mode
				s.mu.Unlock()
			}
			return nil
		})
	}()
}

// pushChanges sends chat.state and chat.pending when they changed.
func (s *session) pushChanges() {
	s.mu.Lock()
	status, pending := s.compute()
	statusChanged := status != s.status
	pendingChanged := pendingID(pending) != pendingID(s.pending)
	s.status, s.pending = status, pending
	s.mu.Unlock()
	if statusChanged {
		s.pushAll(protocol.EventChatState, protocol.ChatStateEvent{SessionID: s.id, ChatStatus: status})
	}
	if pendingChanged {
		s.pushAll(protocol.EventChatPending, protocol.ChatPendingEvent{SessionID: s.id, Pending: pending})
	}
}

func pendingID(p *protocol.Pending) string {
	if p == nil {
		return ""
	}
	return p.ToolUseID
}

func (s *session) pushAll(method protocol.Method, body any) {
	s.mu.Lock()
	clients := make([]string, 0, len(s.clients))
	for c := range s.clients {
		clients = append(clients, c)
	}
	s.mu.Unlock()
	sort.Strings(clients)
	for _, c := range clients {
		_ = agent.PushEvent(s.h.opts.Push, c, method, body)
	}
}
