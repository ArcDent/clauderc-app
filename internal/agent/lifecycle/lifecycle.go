// Package lifecycle serves the session management methods session.create,
// session.stop and session.remove, and wakes stopped sessions for
// chat.send (design D8). Every claude call gets its arguments as a list,
// never through a shell.
package lifecycle

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/config"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/protocol"
)

// DefaultWakeTimeout bounds how long Wake waits for the CLI to list a
// resumed session as running.
const DefaultWakeTimeout = 30 * time.Second

const (
	// defaultPollInterval is how often Wake re-reads the session list.
	defaultPollInterval = 500 * time.Millisecond
	// claudeTimeout bounds one claude call; starting a background session
	// takes a few seconds on a real machine.
	claudeTimeout = 90 * time.Second
	// outputTail caps how much claude output goes into an error message.
	outputTail = 300
)

// Options configures a Handler.
type Options struct {
	Source discovery.Source
	// WakeTimeout defaults to DefaultWakeTimeout.
	WakeTimeout time.Duration
	// PollInterval is how often Wake re-reads the session list; tests
	// shorten it.
	PollInterval time.Duration
	// Logger receives the errors behind internal failures, which the
	// phone only sees as a fixed message; nil discards them.
	Logger *slog.Logger
}

// Handler implements agent.Handler for the session management methods.
// It keeps no per-client state.
type Handler struct {
	opts Options
}

// New returns a Handler.
func New(opts Options) *Handler {
	if opts.WakeTimeout <= 0 {
		opts.WakeTimeout = DefaultWakeTimeout
	}
	if opts.PollInterval <= 0 {
		opts.PollInterval = defaultPollInterval
	}
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	return &Handler{opts: opts}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method {
	return []protocol.Method{
		protocol.MethodSessionCreate,
		protocol.MethodSessionStop,
		protocol.MethodSessionRemove,
	}
}

// Handle implements agent.Handler.
func (h *Handler) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	switch req.Method {
	case protocol.MethodSessionCreate:
		var body protocol.SessionCreateRequest
		if err := json.Unmarshal(req.Body, &body); err != nil {
			return agent.Fail(req, protocol.CodeInvalidParams, agent.BadRequestMessage)
		}
		id, err := h.Create(ctx, body)
		if err != nil {
			return agent.FailErr(req, err)
		}
		return agent.Reply(req, protocol.SessionCreateResponse{SessionID: id})
	case protocol.MethodSessionStop:
		var body protocol.SessionStopRequest
		if err := json.Unmarshal(req.Body, &body); err != nil {
			return agent.Fail(req, protocol.CodeInvalidParams, agent.BadRequestMessage)
		}
		if err := h.Stop(ctx, body.SessionID); err != nil {
			return agent.FailErr(req, err)
		}
		return agent.Reply(req, struct{}{})
	case protocol.MethodSessionRemove:
		var body protocol.SessionRemoveRequest
		if err := json.Unmarshal(req.Body, &body); err != nil {
			return agent.Fail(req, protocol.CodeInvalidParams, agent.BadRequestMessage)
		}
		if err := h.Remove(ctx, body.SessionID); err != nil {
			return agent.FailErr(req, err)
		}
		return agent.Reply(req, struct{}{})
	default:
		return agent.FailUnknown(req)
	}
}

// Create starts a background session in the requested profile and returns
// its short id. The launch flags and providerEnv come from the profile's
// most recent session, or from the template cache once jobs/ has none
// left; claude gets the flags plus --bg, -n <name> when a name is given,
// and "--" and the first message when one is given. A missing working directory
// or a profile without a template fails before claude runs.
func (h *Handler) Create(ctx context.Context, req protocol.SessionCreateRequest) (string, error) {
	if err := checkDir(req.Cwd, "目录不存在："+req.Cwd); err != nil {
		return "", err
	}
	t, ok := h.opts.Source.LaunchTemplate(req.ProfileID)
	if !ok {
		return "", &protocol.Error{Code: protocol.CodeInvalidParams, Message: "这个档位不能新建会话"}
	}
	args := append(slices.Clone(t.Flags), "--bg")
	if req.Name != "" {
		args = append(args, "-n", req.Name)
	}
	if req.Prompt != "" {
		// "--" keeps a message starting with "-" from being read as a flag.
		args = append(args, "--", req.Prompt)
	}
	const failed = "启动 claude 失败，请在目标机上查看日志"
	out, err := h.run(ctx, req.Cwd, t.ProviderEnv, "--bg", args)
	if err != nil {
		return "", h.internal(failed, err)
	}
	id := parseBackgrounded(out)
	if id == "" {
		return "", h.internal(failed, fmt.Errorf("claude --bg: no session id in output: %s", tail(out)))
	}
	return id, nil
}

// Stop stops the running session with short id id; a session that is
// already stopped is left alone.
func (h *Handler) Stop(ctx context.Context, id string) error {
	t, err := h.target(ctx, id)
	if err != nil || !t.Running {
		return err
	}
	if _, err := h.run(ctx, "", nil, "stop", []string{"stop", t.ID}); err != nil {
		return h.internal("停止会话失败，请在目标机上查看日志", err)
	}
	return nil
}

// Remove removes the stopped session with short id id from the list. The
// state is checked against the CLI right before, and a running session is
// refused without calling `claude rm`.
func (h *Handler) Remove(ctx context.Context, id string) error {
	t, err := h.target(ctx, id)
	if err != nil {
		return err
	}
	if t.Running {
		return &protocol.Error{Code: protocol.CodeSessionRunning, Message: "会话仍在运行，先停止再移出"}
	}
	if _, err := h.run(ctx, "", nil, "rm", []string{"rm", t.ID}); err != nil {
		return h.internal("移出会话失败，请在目标机上查看日志", err)
	}
	return nil
}

// Wake resumes the stopped session whose full session id is sessionID
// with exactly `claude --bg --resume <sessionID>` in its original working
// directory, adding the providerEnv of its job record, and returns once
// the CLI lists it as running. Any further flag would start a copy under
// a new id instead (design D8). A session that is running is left alone.
// It implements the chat package's Waker.
func (h *Handler) Wake(ctx context.Context, sessionID string) error {
	bySessionID := func(t discovery.Target) bool { return t.SessionID == sessionID }
	t, err := h.find(ctx, sessionID, bySessionID)
	if err != nil || t.Running {
		return err
	}
	if err := checkDir(t.Cwd, "会话的工作目录已不存在："+t.Cwd); err != nil {
		return err
	}
	env := h.opts.Source.ProviderEnv(t.ID)
	if _, err := h.run(ctx, t.Cwd, env, "--bg --resume", []string{"--bg", "--resume", sessionID}); err != nil {
		return h.internal("claude 没能接着运行这个会话，请在目标机上查看日志", err)
	}
	ctx, cancel := context.WithTimeout(ctx, h.opts.WakeTimeout)
	defer cancel()
	ticker := time.NewTicker(h.opts.PollInterval)
	defer ticker.Stop()
	for {
		if t, err := h.find(ctx, sessionID, bySessionID); err == nil && t.Running {
			return nil
		}
		select {
		case <-ctx.Done():
			return &protocol.Error{Code: protocol.CodeTimeout, Message: "会话唤醒后没有恢复运行"}
		case <-ticker.C:
		}
	}
}

// target finds the background session with short id id in the CLI list.
func (h *Handler) target(ctx context.Context, id string) (discovery.Target, error) {
	return h.find(ctx, id, func(t discovery.Target) bool { return t.ID == id })
}

// find returns the first background session in the CLI list that match
// accepts; name is the id quoted when there is none.
func (h *Handler) find(ctx context.Context, name string, match func(discovery.Target) bool) (discovery.Target, error) {
	targets, err := h.opts.Source.Targets(ctx)
	if err != nil {
		return discovery.Target{}, h.internal("读取会话列表失败，请检查目标机上的 claude 能否运行", err)
	}
	for _, t := range targets {
		if match(t) {
			return t, nil
		}
	}
	return discovery.Target{}, &protocol.Error{Code: protocol.CodeSessionNotFound, Message: "找不到会话：" + name}
}

// internal logs err on the agent and returns msg for the phone: claude's
// output can hold paths or settings that must not leave the machine.
func (h *Handler) internal(msg string, err error) error {
	h.opts.Logger.Error(msg, "err", err)
	return &protocol.Error{Code: protocol.CodeInternal, Message: msg}
}

// checkDir requires dir to be an existing absolute directory.
func checkDir(dir, missing string) error {
	if !filepath.IsAbs(dir) {
		return &protocol.Error{Code: protocol.CodeInvalidParams, Message: "工作目录必须是绝对路径"}
	}
	if info, err := os.Stat(dir); err != nil || !info.IsDir() {
		return &protocol.Error{Code: protocol.CodeDirNotFound, Message: missing}
	}
	return nil
}

// run calls claude with args in dir (the agent's own when empty), adding
// env on top of the agent's environment, and returns its combined output.
// label names the call in errors; args are left out because launch flags
// may carry credentials.
func (h *Handler) run(ctx context.Context, dir string, env map[string]string, label string, args []string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, claudeTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, h.opts.Source.ClaudePath, args...)
	cmd.Dir = dir
	cmd.Env = environ(env)
	// A background launch might leave its output pipes to the daemon it
	// starts; the launch itself has finished by then.
	cmd.WaitDelay = time.Second
	out, err := cmd.CombinedOutput()
	if err != nil && !errors.Is(err, exec.ErrWaitDelay) {
		return out, fmt.Errorf("claude %s: %w: %s", label, err, tail(out))
	}
	return out, nil
}

// environ is the agent's environment without the enroll key, with extra
// added; exec keeps the last value of a repeated name, so extra wins.
// Names that cannot be environment names are skipped.
func environ(extra map[string]string) []string {
	env := config.Environ()
	names := make([]string, 0, len(extra))
	for k := range extra {
		if k != "" && !strings.ContainsAny(k, "=\x00") && !strings.Contains(extra[k], "\x00") {
			names = append(names, k)
		}
	}
	sort.Strings(names)
	for _, k := range names {
		env = append(env, k+"="+extra[k])
	}
	return env
}

var (
	ansi         = regexp.MustCompile(`\x1b\[[0-9;?]*[A-Za-z]`)
	backgrounded = regexp.MustCompile(`backgrounded\s*·\s*([0-9a-z]+)`)
)

// parseBackgrounded finds the short id in claude --bg output, which reads
// "backgrounded · <id> · <name>", possibly with colour codes.
func parseBackgrounded(out []byte) string {
	m := backgrounded.FindSubmatch(ansi.ReplaceAll(out, nil))
	if m == nil {
		return ""
	}
	return string(m[1])
}

// tail is the end of claude's output without colour codes, for errors.
func tail(out []byte) string {
	s := strings.TrimSpace(string(ansi.ReplaceAll(out, nil)))
	if r := []rune(s); len(r) > outputTail {
		s = "…" + string(r[len(r)-outputTail:])
	}
	return s
}
