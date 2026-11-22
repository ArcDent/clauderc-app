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

// Handler implements agent.Handler and agent.ClientTracker for chats.
type Handler struct {
	opts  Options
	pool  *attach.Pool
	wakes *attach.Pool // see hold

	mu     sync.Mutex
	chats  map[string]*session   // by short session id
	agents map[string]*agentChat // by agentKey
	terms  map[string]*termView  // by short session id
	closed bool

	resumes    *resumes
	stopResume context.CancelFunc
	resumeDone chan struct{}
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
	h := &Handler{opts: opts, pool: attach.NewPool(opts.Attach), wakes: attach.NewPool(wake), chats: map[string]*session{}, agents: map[string]*agentChat{}, terms: map[string]*termView{}}
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
			h.leaveAgent(req.Client, agentKey(body.SessionID, body.AgentID))
		} else {
			h.leave(req.Client, body.SessionID)
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
	h.pool.Close()
	h.wakes.Close()
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
		clients:  map[string]bool{},
		follower: openFollower(h.opts.Source.ClaudeDir, t.SessionID),
		target:   t,
	}
	s.status, s.pending = s.compute()
	s.tasks.read(h.taskDir(t.SessionID))
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
	return protocol.ChatOpenResponse{Items: toProtocol(items), HasMore: more, ChatStatus: s.status, Pending: s.pending, Tasks: s.tasks.current()}
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
		case <-state.C:
			s.pollState(ctx)
			s.ensureAttach(ctx)
			s.pollDialog()
		case <-screen.C:
			s.pollScreen()
		}
		s.pushChanges()
	}
}

// pollScreen reads the permission mode, the status line and the reply
// being drawn off the screen of the hidden attach; compute passes them on while the session
// works. A screen without the status line is taken for a frame caught
// mid-draw and keeps what the last one showed: once the turn ends,
// compute drops them anyway.
func (s *session) pollScreen() {
	s.mu.Lock()
	conn := s.conn
	s.mu.Unlock()
	if conn == nil {
		return
	}
	lines := conn.View().Lines
	s.watchRewind(strings.Join(lines, "\n"))
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

// pollTasks pushes the session's task list when it changed. The list goes
// by the Claude Code session, which /clear replaces.
func (s *session) pollTasks() {
	s.mu.Lock()
	id := s.target.SessionID
	s.mu.Unlock()
	if tasks, changed := s.tasks.read(s.h.taskDir(id)); changed {
		s.pushAll(protocol.EventChatTasks, protocol.ChatTasksEvent{SessionID: s.id, Tasks: tasks})
	}
}

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
// done, not blocked, meanwhile (test/e2e/claude TestTerminal).
func (s *session) pollDialog() {
	s.mu.Lock()
	conn, target, f := s.conn, s.target, s.follower
	s.mu.Unlock()
	var d *attach.Dialog
	off := false
	if f == nil || f.Pending(target.Blocked()) == nil {
		d = screenDialog(conn, target)
		off = d == nil && conn != nil && target.Running && offInput(conn.View())
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
		if keep {
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

// pushChanges sends chat.state and chat.pending when they changed.
func (s *session) pushChanges() {
	s.mu.Lock()
	status, pending := s.compute()
	statusChanged := !sameStatus(status, s.status)
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
