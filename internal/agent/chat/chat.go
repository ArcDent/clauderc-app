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
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// Defaults of Options (design D6, D7).
const (
	// DefaultPollInterval is short because a poll of an unchanged
	// transcript only looks at file sizes and modification times.
	DefaultPollInterval    = 200 * time.Millisecond
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
	// Forker forks for chat.send the sessions that refuse /fork; nil
	// types /fork into them all the same.
	Forker Forker
	// ResumeFile keeps the messages waiting for a usage limit to be lifted
	// (chat.autoResume) across restarts; empty keeps them in memory.
	ResumeFile string
	// ResumeInterval is how often due messages are looked for; it defaults
	// to DefaultResumeInterval.
	ResumeInterval time.Duration
	// Usage reads the plan usage, whose reset times stand in when a limit
	// message gives none; nil has no stand-in.
	Usage func(ctx context.Context) protocol.UsageResponse
	// Now defaults to time.Now.
	Now func() time.Time
	// Logger receives the failures of automatic messages; nil discards
	// them.
	Logger *slog.Logger
}

// Waker resumes a stopped session, named by its full session id, so a
// message can be sent to it. It returns once the session runs. hold keeps
// the hidden attach of the session with short id id until release is
// called; attaching is what runs a stopped session again.
type Waker interface {
	Wake(ctx context.Context, sessionID string, hold func(ctx context.Context, id string) (release func(), err error)) error
}

// Forker starts a copy of the session with short id id that carries on
// its conversation, with instruction, when given, as its first message.
type Forker interface {
	Fork(ctx context.Context, id, instruction string) error
}

// hold is the hold given to Waker.Wake. It always starts a new attach: one
// an open chat held from before the session stopped stays up but does not
// run it again. That one goes on working once the session runs.
func (h *Handler) hold(ctx context.Context, id string) (func(), error) {
	if _, err := h.wakes.Acquire(ctx, id); err != nil {
		return nil, err
	}
	return func() { h.wakes.Release(id) }, nil
}

// wakeLinger is how long a waking attach outlives its hold; chat.send
// types into an attach of the main pool.
const wakeLinger = time.Second

type openKind int

const (
	openChat openKind = iota
	openAgentChat
	openTerm
)

// openAttempt tracks an in-flight chat.open or term.open so ClientGone,
// AllClientsGone or a matching close cancels it before it can register a
// subscription.
type openAttempt struct {
	kind     openKind
	client   string
	id       string // short session id, or agentKey for openAgentChat
	cancel   context.CancelFunc
	canceled bool
}

// Handler implements agent.Handler and agent.ClientTracker for chats.
type Handler struct {
	opts  Options
	pool  *attach.Pool
	wakes *attach.Pool // see hold

	subSeq atomic.Int64
	// sendDedup deduplicates chat.send requests carrying ClientReqID within 10 minutes.
	sendDedup *agent.Dedup[protocol.ChatSendResponse]

	mu      sync.Mutex
	chats   map[string]*session   // by short session id
	agents  map[string]*agentChat // by agentKey
	terms   map[string]*termView  // by short session id
	opening map[*openAttempt]struct{}
	// sending holds, by short session id, the chat.send under way (see
	// lockSend); autoResuming serializes chat.autoResume per session;
	// driving counts the operations of withTerm under way.
	sending      map[string]chan struct{}
	autoResuming map[string]chan struct{}
	driving      map[string]int
	closed       bool

	resumes    *resumes
	stopResume context.CancelFunc
	resumeDone chan struct{}
	// bg counts the state polls of open chats (see statePolls) and the
	// automatic messages being sent; Close waits for them.
	bg sync.WaitGroup
}

func (h *Handler) nextSubID() string {
	return "sub-" + strconv.FormatInt(h.subSeq.Add(1), 10)
}

func (h *Handler) beginOpen(ctx context.Context, kind openKind, client, id string) (context.Context, *openAttempt, func()) {
	octx, cancel := context.WithCancel(ctx)
	a := &openAttempt{kind: kind, client: client, id: id, cancel: cancel}
	h.mu.Lock()
	closed := h.closed
	if closed {
		a.canceled = true
	} else {
		h.opening[a] = struct{}{}
	}
	h.mu.Unlock()
	if closed {
		cancel()
	}
	done := func() {
		h.mu.Lock()
		delete(h.opening, a)
		h.mu.Unlock()
		cancel()
	}
	return octx, a, done
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
	if opts.ResumeInterval <= 0 {
		opts.ResumeInterval = DefaultResumeInterval
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	wake := opts.Attach
	wake.Linger = wakeLinger
	h := &Handler{opts: opts, pool: attach.NewPool(opts.Attach), wakes: attach.NewPool(wake),
		sendDedup: agent.NewDedup[protocol.ChatSendResponse](agent.DefaultDedupTTL, opts.Now),
		chats:     map[string]*session{}, agents: map[string]*agentChat{}, terms: map[string]*termView{},
		opening: map[*openAttempt]struct{}{}, sending: map[string]chan struct{}{}, autoResuming: map[string]chan struct{}{}, driving: map[string]int{}}
	h.resumes = loadResumes(opts.ResumeFile)
	ctx, cancel := context.WithCancel(context.Background())
	h.stopResume, h.resumeDone = cancel, make(chan struct{})
	go h.resumeLoop(ctx, h.resumeDone)
	return h
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
		protocol.MethodChatToolOutput,
		protocol.MethodChatImage,
		protocol.MethodChatAutoResume,
		protocol.MethodChatRewind,
		protocol.MethodTermOpen,
		protocol.MethodTermKey,
		protocol.MethodTermClose,
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
		if body.AgentID != "" {
			h.leaveAgent(req.Client, agentKey(body.SessionID, body.AgentID), body.SubID)
		} else {
			h.leave(req.Client, body.SessionID, body.SubID)
		}
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
	case protocol.MethodChatToolOutput:
		return h.toolOutput(ctx, req)
	case protocol.MethodChatImage:
		return h.image(ctx, req)
	case protocol.MethodChatAutoResume:
		return h.autoResume(ctx, req)
	case protocol.MethodChatRewind:
		return h.rewind(ctx, req)
	case protocol.MethodTermOpen:
		return h.termOpen(ctx, req)
	case protocol.MethodTermKey:
		return h.termKey(ctx, req)
	case protocol.MethodTermClose:
		return h.termClose(req)
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
	if body.AgentID != "" {
		return h.openAgent(ctx, req, body)
	}
	if body.Before > 0 {
		target, err := h.lookup(ctx, body.SessionID)
		if err != nil {
			return failWith(req, err)
		}
		// An older page; the chat's subscription is unchanged.
		var items []transcript.Item
		var more bool
		if f := openFollower(h.opts.Source.ClaudeDir, target.SessionID); f != nil {
			items, more = f.Page(body.Before, protocol.ChatPageSize)
		}
		return agent.Reply(req, protocol.ChatOpenResponse{Items: toProtocol(items), HasMore: more})
	}

	octx, attempt, finish := h.beginOpen(ctx, openChat, req.Client, body.SessionID)
	defer finish()
	target, err := h.lookup(octx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}

	subID := h.nextSubID()
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return agent.Fail(req, protocol.CodeInternal, "目标机程序正在退出，请稍后重试")
	}
	if attempt.canceled {
		h.mu.Unlock()
		return agent.Fail(req, protocol.CodeInternal, "请求已取消")
	}
	s := h.chats[target.ID]
	if s == nil {
		s = h.newSession(target)
		h.chats[target.ID] = s
	}
	s.mu.Lock()
	s.clients[req.Client] = subID
	s.target = target
	s.mu.Unlock()
	h.mu.Unlock()

	return agent.Reply(req, s.snapshot(subID))
}

// leave drops client from the chat of session id when subID matches (or is
// empty), ending the chat when it was the last one. An empty subID also
// cancels any in-flight open of this client for this session.
func (h *Handler) leave(client, id, subID string) {
	h.mu.Lock()
	if subID == "" {
		for a := range h.opening {
			if a.kind == openChat && a.client == client && a.id == id {
				a.canceled = true
				a.cancel()
			}
		}
	}
	s := h.chats[id]
	var stop *session
	if s != nil {
		s.mu.Lock()
		if cur, ok := s.clients[client]; ok && (subID == "" || cur == subID) {
			delete(s.clients, client)
			if len(s.clients) == 0 {
				delete(h.chats, id)
				stop = s
			}
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
	for a := range h.opening {
		if a.client == client {
			a.canceled = true
			a.cancel()
		}
	}
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
	var agentStops []*agentChat
	for key, c := range h.agents {
		c.mu.Lock()
		delete(c.clients, client)
		if len(c.clients) == 0 {
			delete(h.agents, key)
			agentStops = append(agentStops, c)
		}
		c.mu.Unlock()
	}
	termStops := h.dropTermClient(client)
	h.mu.Unlock()
	for _, s := range stops {
		s.stop()
	}
	for _, c := range agentStops {
		c.stop()
	}
	for _, v := range termStops {
		v.stop()
	}
}

// AllClientsGone implements agent.ClientTracker.
func (h *Handler) AllClientsGone() {
	h.mu.Lock()
	for a := range h.opening {
		a.canceled = true
		a.cancel()
	}
	chats, agents, terms := h.chats, h.agents, h.terms
	h.chats, h.agents, h.terms = map[string]*session{}, map[string]*agentChat{}, map[string]*termView{}
	h.mu.Unlock()
	for _, s := range chats {
		s.stop()
	}
	for _, c := range agents {
		c.stop()
	}
	for _, v := range terms {
		v.stop()
	}
}

// Close ends every chat and attach for good.
func (h *Handler) Close() {
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
	h.stopResume()
	<-h.resumeDone
	h.AllClientsGone()
	// An automatic message being sent may still use the attaches.
	h.bg.Wait()
	h.pool.Close()
	h.wakes.Close()
}

// statePolls runs poll every StateInterval on a goroutine of its own until
// ctx ends, and signals on the returned channel after each run: a slow
// `claude agents` or usage request holds up neither a chat's transcript
// polls nor its stop. It is started where the chat is added, under h.mu
// and not once closed, so Close's wait counts it.
func (h *Handler) statePolls(ctx context.Context, poll func(context.Context)) <-chan struct{} {
	polled := make(chan struct{}, 1)
	h.bg.Add(1)
	go func() {
		defer h.bg.Done()
		tick := time.NewTicker(h.opts.StateInterval)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
			poll(ctx)
			select {
			case polled <- struct{}{}:
			default:
			}
		}
	}()
	return polled
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
	key := rewindKey(claudeDir, sessionID)
	if v, ok := rewinds.Load(key); ok {
		if m := v.(rewindMark); m.last == f.Last() {
			f.Rewind(m.uuid)
		} else {
			rewinds.CompareAndDelete(key, v)
		}
	}
	return f
}

// toProtocol maps transcript items to the wire, tool results cut to
// protocol.MaxToolOutputBytes (see truncated).
func toProtocol(items []transcript.Item) []protocol.ChatItem {
	return convert(items, true)
}

// convert maps transcript items to the wire, cutting long tool results
// when cut is set. The protocol has no command kind yet: a command becomes
// a tool row named after it, with its output as the result and a tool_use
// id starting with "command-", which the phone draws as the user's own
// bubble.
func convert(items []transcript.Item, cut bool) []protocol.ChatItem {
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
		if cut && ci.Tool != nil && ci.Tool.Result != nil {
			tc := *ci.Tool
			tc.Result = truncated(tc.Result)
			ci.Tool = &tc
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
	// pushMu serializes snapshots and pushes so rev orders them strictly;
	// taken before mu.
	pushMu sync.Mutex

	mu       sync.Mutex
	clients  map[string]string // client -> subID
	rev      int64
	follower *transcript.Follower
	// target is the session as claude last reported it.
	target discovery.Target
	// screenMode is the permission mode last read from the status line;
	// it beats the transcript, which only learns the mode at stop.
	screenMode protocol.PermissionMode
	attached   bool
	attaching  bool
	// conn is the held attach once attached.
	conn *attach.Conn
	// dialog is what the screen showed at the last state poll while the
	// session was blocked with nothing pending in the transcript.
	dialog *attach.Dialog
	// offInput counts the state polls in a row at which the running
	// session, with nothing pending, showed neither its input box nor a
	// known dialog: a panel or a dialog the agent cannot read.
	offInput int
	// activity and partial are the status line and the reply being drawn,
	// as last read off the screen (see pollScreen).
	activity, partial string
	// limitReset is usage.get's reset time for the limit of the record at
	// limitFor, read once when the limit message gives none.
	limitReset, limitFor time.Time
	limitRead            bool
	// last status and pending prompt sent to the phones
	status  protocol.ChatStatus
	pending *protocol.Pending
	// rw follows the /rewind panel on the screen; only pollScreen uses it.
	rw rewindWatch
	// tasks reads the session's task list (see tasks.go).
	tasks taskList
}

// screenInterval is how often an open chat reads the status line and the
// reply being drawn off its screen; Claude Code's timer there counts
// seconds.
const screenInterval = time.Second

func (h *Handler) newSession(t discovery.Target) *session {
	ctx, cancel := context.WithCancel(context.Background())
	s := &session{
		h: h, id: t.ID, sessionID: t.SessionID, cancel: cancel, done: make(chan struct{}),
		clients:  map[string]string{},
		rev:      1,
		follower: openFollower(h.opts.Source.ClaudeDir, t.SessionID),
		target:   t,
	}
	s.status, s.pending = s.compute()
	s.tasks.read(h.taskDir(t.SessionID))
	go s.run(ctx, h.statePolls(ctx, s.pollState))
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
	if pending == nil && s.dialog != nil && s.target.Blocked() {
		pending = dialogPending(*s.dialog)
	}
	state := s.target.SessionState(pending != nil)
	mode := info.PermissionMode
	if s.screenMode != "" {
		mode = s.screenMode
	}
	unknown := s.offInput >= offInputPolls && pending == nil && s.target.Running
	status := protocol.ChatStatus{State: state, Model: info.Model, PermissionMode: mode, UnknownDialog: unknown}
	if info.Limit != nil {
		reset := time.Time{}
		if s.limitRead && s.limitFor.Equal(info.Limit.At) {
			reset = s.limitReset
		}
		status.Limit = s.h.limitStatus(s.id, info.Limit, reset)
	}
	if state == protocol.StateWorking {
		status.Activity = s.activity
		if s.partial != "" && !landed(s.follower, s.partial) {
			status.Partial = s.partial
		}
	}
	return status, pending
}

// snapshot is the chat.open answer for the latest page. It does not update
// s.status or s.pending, so a second phone opening while a change arrived
// never suppresses the push to phones already watching.
func (s *session) snapshot(subID string) protocol.ChatOpenResponse {
	s.pushMu.Lock()
	defer s.pushMu.Unlock()
	s.switchTranscriptLocked()
	s.mu.Lock()
	status, pending := s.compute()
	rev := s.rev
	var items []transcript.Item
	var more bool
	if s.follower != nil {
		items, more = s.follower.Page(0, protocol.ChatPageSize)
	}
	tasks := s.tasks.current()
	s.mu.Unlock()
	return protocol.ChatOpenResponse{
		Items: toProtocol(items), HasMore: more, ChatStatus: status, Pending: pending, Tasks: tasks,
		Rev: rev, SubID: subID,
	}
}

func (s *session) stop() {
	s.cancel()
	<-s.done
	s.mu.Lock()
	attached := s.attached
	s.attached = false
	s.conn = nil
	s.mu.Unlock()
	if attached {
		s.h.pool.Release(s.id)
	}
}

// run polls the chat until ctx ends; polled signals each state poll done
// apart (see statePolls).
func (s *session) run(ctx context.Context, polled <-chan struct{}) {
	defer close(s.done)
	poll := time.NewTicker(s.h.opts.PollInterval)
	defer poll.Stop()
	screen := time.NewTicker(screenInterval)
	defer screen.Stop()
	s.ensureAttach(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-poll.C:
			s.pollTranscript()
			s.pollTasks()
		case <-polled:
			s.ensureAttach(ctx)
			s.pollTranscript()
			s.pollTasks()
			s.pollDialog()
		case <-screen.C:
			s.pollScreen(ctx)
		}
		s.pushChanges()
	}
}

// pollScreen reads the permission mode, the status line and the reply
// being drawn off the screen of the hidden attach; compute passes them on while the session
// works. A screen without the status line is taken for a frame caught
// mid-draw and keeps what the last one showed: once the turn ends,
// compute drops them anyway.
func (s *session) pollScreen(ctx context.Context) {
	s.mu.Lock()
	conn := s.conn
	s.mu.Unlock()
	if conn != nil && conn.Ended() {
		s.ensureAttach(ctx)
		return
	}
	if conn == nil {
		return
	}
	driven := s.h.driven(s.id)
	lines := conn.View().Lines
	// chat.rewind takes its messages away itself, and a screen of it would
	// only mislead the watch afterwards.
	if driven || s.h.driven(s.id) {
		s.rw = rewindWatch{}
	} else {
		s.watchRewind(strings.Join(lines, "\n"))
	}
	// A mode changed on the session itself, such as by /plan, shows in
	// its status line.
	if mode, ok := attach.ParseMode(strings.Join(lines, "\n")); ok {
		s.mu.Lock()
		s.screenMode = mode
		s.mu.Unlock()
	}
	activity := screenActivity(lines)
	if activity == "" {
		s.mu.Lock()
		if s.target.SessionState(false) != protocol.StateWorking {
			s.activity, s.partial = "", ""
		}
		s.mu.Unlock()
		return
	}
	partial := screenPartial(lines)
	s.mu.Lock()
	s.activity, s.partial = activity, partial
	s.mu.Unlock()
}

// switchTranscriptLocked switches the open chat to a new transcript when
// /clear replaced the Claude Code session id, pushing a reset page to
// every watching phone. s.pushMu must be held.
func (s *session) switchTranscriptLocked() bool {
	s.mu.Lock()
	targetSID := s.target.SessionID
	curSID := s.sessionID
	s.mu.Unlock()
	if targetSID == "" || targetSID == curSID {
		return false
	}
	nf := openFollower(s.h.opts.Source.ClaudeDir, targetSID)
	var items []transcript.Item
	if nf != nil {
		items, _ = nf.Page(0, protocol.ChatPageSize)
	}
	s.mu.Lock()
	s.sessionID = targetSID
	s.follower = nf
	s.limitRead = false
	s.limitReset = time.Time{}
	s.limitFor = time.Time{}
	s.activity, s.partial = "", ""
	s.rev++
	rev := s.rev
	s.mu.Unlock()
	s.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{
		SessionID: s.id,
		Items:     toProtocol(items),
		Rev:       rev,
		Reset:     true,
	})
	return true
}

func (s *session) pollTranscript() {
	s.pushMu.Lock()
	defer s.pushMu.Unlock()
	if s.switchTranscriptLocked() {
		return
	}
	s.mu.Lock()
	f := s.follower
	sid := s.sessionID
	s.mu.Unlock()
	var items []transcript.Item
	if f == nil {
		f = openFollower(s.h.opts.Source.ClaudeDir, sid)
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
		s.mu.Lock()
		s.rev++
		rev := s.rev
		s.mu.Unlock()
		s.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{SessionID: s.id, Items: toProtocol(items), Rev: rev})
	}
}

// pollTasks pushes the session's task list when it changed. The list goes
// by the Claude Code session, which /clear replaces.
func (s *session) pollTasks() {
	s.pushMu.Lock()
	defer s.pushMu.Unlock()
	s.mu.Lock()
	id := s.target.SessionID
	s.mu.Unlock()
	if tasks, changed := s.tasks.read(s.h.taskDir(id)); changed {
		s.mu.Lock()
		s.rev++
		rev := s.rev
		s.mu.Unlock()
		s.pushAll(protocol.EventChatTasks, protocol.ChatTasksEvent{SessionID: s.id, Tasks: tasks, Rev: rev})
	}
}

// pollState reads the session's state and the reset time of its limit; it
// runs apart from run (see statePolls).
func (s *session) pollState(ctx context.Context) {
	s.readLimitReset(ctx)
	t, err := s.h.lookup(ctx, s.id)
	if err != nil {
		return
	}
	s.mu.Lock()
	s.target = t
	s.mu.Unlock()
}

// readLimitReset asks usage.get once for the reset time of a limit whose
// message gives none.
func (s *session) readLimitReset(ctx context.Context) {
	s.mu.Lock()
	f := s.follower
	s.mu.Unlock()
	if f == nil {
		return
	}
	l := f.Info().Limit
	if l == nil || !l.ResetsAt.IsZero() {
		return
	}
	s.mu.Lock()
	known := s.limitRead && s.limitFor.Equal(l.At)
	s.mu.Unlock()
	if known {
		return
	}
	reset := s.h.usageReset(ctx, l)
	s.mu.Lock()
	s.limitReset, s.limitFor, s.limitRead = reset, l.At, true
	s.mu.Unlock()
}

// offInputPolls is how many state polls in a row must find the session
// off its input before the phone is told; one alone may be a frame
// caught mid-draw.
const offInputPolls = 2

// pollDialog reads a dialog off the screen while the session is blocked
// and its transcript shows nothing pending, and counts the polls at which
// a running session shows neither its input box nor a known dialog. A
// panel left open is such a screen, and Claude Code reports the session
// done, not blocked, meanwhile (test/e2e/claude TestTerminal). A panel an
// operation of the agent opens does not count.
func (s *session) pollDialog() {
	s.mu.Lock()
	conn, target, f := s.conn, s.target, s.follower
	s.mu.Unlock()
	if conn != nil && conn.Ended() {
		conn = nil
	}
	var d *attach.Dialog
	off := false
	if f == nil || f.Pending(target.Blocked()) == nil {
		d = screenDialog(conn, target)
		off = d == nil && conn != nil && target.Running && !s.h.driven(s.id) && offInput(conn.View())
	}
	s.mu.Lock()
	s.dialog = d
	if off {
		s.offInput++
	} else {
		s.offInput = 0
	}
	s.mu.Unlock()
}

// hasInputBox reports whether the screen shows Claude Code's input box.
func hasInputBox(screen string) bool {
	_, ok := inputLine(screen)
	return ok
}

// awayFromInput reports whether the session is off its input: no input
// box on screen and the cursor hidden. Claude Code 2.1.326 shows the
// cursor in its input and hides it in a panel; requiring both keeps a
// screen the emulator drew wrong, such as the 2.1.323 recording, from
// counting as off the input.
func awayFromInput(v attach.View) bool {
	return !v.Cursor && !hasInputBox(strings.Join(v.Lines, "\n"))
}

// offInput reports whether the session is off its input and the screen
// shows no dialog the agent can read.
func offInput(v attach.View) bool {
	if !awayFromInput(v) {
		return false
	}
	_, known := attach.ParseDialog(strings.Join(v.Lines, "\n"))
	return !known
}

// ensureAttach starts the hidden attach of a running session in the
// background, or replaces it when its process has exited.
func (s *session) ensureAttach(ctx context.Context) {
	s.mu.Lock()
	if s.attaching || !s.target.Running {
		s.mu.Unlock()
		return
	}
	releaseOld := false
	if s.attached {
		if s.conn == nil || !s.conn.Ended() {
			s.mu.Unlock()
			return
		}
		s.attached = false
		s.conn = nil
		releaseOld = true
	}
	s.attaching = true
	s.mu.Unlock()
	if releaseOld {
		s.h.pool.Release(s.id)
	}
	go func() {
		conn, err := s.h.pool.Acquire(ctx, s.id)
		s.mu.Lock()
		s.attaching = false
		keep := err == nil && ctx.Err() == nil
		if keep {
			s.attached = true
			s.conn = conn
		}
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

// pushChanges sends chat.state and chat.pending when they changed. The
// poll and an operation such as chat.setPermissionMode may call it at
// once; each sends before the next works them out, so what one worked out
// earlier never reaches the phones after what another did later.
func (s *session) pushChanges() {
	s.pushMu.Lock()
	defer s.pushMu.Unlock()
	s.mu.Lock()
	status, pending := s.compute()
	statusChanged := !sameStatus(status, s.status)
	pendingChanged := pendingID(pending) != pendingID(s.pending)
	s.status, s.pending = status, pending
	var stateRev, pendingRev int64
	if statusChanged {
		s.rev++
		stateRev = s.rev
	}
	if pendingChanged {
		s.rev++
		pendingRev = s.rev
	}
	s.mu.Unlock()
	if statusChanged {
		s.pushAll(protocol.EventChatState, protocol.ChatStateEvent{SessionID: s.id, ChatStatus: status, Rev: stateRev})
	}
	if pendingChanged {
		s.pushAll(protocol.EventChatPending, protocol.ChatPendingEvent{SessionID: s.id, Pending: pending, Rev: pendingRev})
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
