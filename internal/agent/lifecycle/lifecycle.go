// Package lifecycle serves the session management methods session.create,
// session.stop and session.remove, and wakes stopped sessions for
// chat.send (design D8). Every claude call gets its arguments as a list,
// never through a shell.
package lifecycle

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"time"
	"unicode"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/config"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/keyfile"
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
	// Dir holds the files new sessions launch with, written with mode 600:
	// the account login's copy of the user settings and the keys of custom
	// endpoints; normally config.Config.LaunchDir. Create fails without it.
	Dir string
	// ExtraFlags go to claude before --bg on every create; only tests set
	// them, to pick a permission mode or effort.
	ExtraFlags []string
	// WakeTimeout defaults to DefaultWakeTimeout.
	WakeTimeout time.Duration
	// PollInterval is how often Wake re-reads the session list; tests
	// shorten it.
	PollInterval time.Duration
	// Logger receives the errors behind internal failures, which the
	// phone only sees as a fixed message; nil discards them.
	Logger *slog.Logger
	// Changed is called once a create, stop or remove has gone through,
	// before the phone gets its answer, so the session list can be read
	// again at once; nil calls nothing.
	Changed func()
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
	if opts.Changed == nil {
		opts.Changed = func() {}
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

// Create starts a background session and returns its short id. Its
// launch flags and extra environment come from launchConfig; claude gets
// them plus ExtraFlags, --bg, -n <name> when a name is given, -w
// <worktree> when a worktree is asked for, and "--" and the first message
// when one is given. A missing working directory or a request launchConfig
// refuses fails before claude runs.
func (h *Handler) Create(ctx context.Context, req protocol.SessionCreateRequest) (string, error) {
	if err := checkDir(req.Cwd, "目录不存在："+req.Cwd); err != nil {
		return "", err
	}
	if req.Worktree != "" && !worktreeName.MatchString(req.Worktree) {
		return "", invalid("worktree 名只能用字母、数字、点、横线和下划线，不能以点或横线开头")
	}
	flags, env, err := h.launchConfig(req)
	if err != nil {
		return "", err
	}
	args := append(append(flags, h.opts.ExtraFlags...), "--bg")
	if req.Name != "" {
		args = append(args, "-n", req.Name)
	}
	if req.Worktree != "" {
		args = append(args, "-w", req.Worktree)
	}
	if req.Prompt != "" {
		// "--" keeps a message starting with "-" from being read as a flag.
		args = append(args, "--", req.Prompt)
	}
	const failed = "启动 claude 失败，请在目标机上查看日志"
	out, err := h.run(ctx, req.Cwd, env, "--bg", args)
	if err != nil && untrusted(out) && req.Trust {
		if err := attach.Trust(ctx, req.Cwd, []string{h.opts.Source.ClaudePath}, envList(env)); err != nil {
			h.opts.Logger.Error("trust prompt", "err", err)
			return "", &protocol.Error{Code: protocol.CodeInternal, Message: "没能在目标机上确认信任这个目录，请在目标机上进入 " + req.Cwd + " 运行一次 claude，选「Yes, I trust this folder」后再试"}
		}
		out, err = h.run(ctx, req.Cwd, env, "--bg", args)
	}
	if err != nil {
		if untrusted(out) {
			return "", &protocol.Error{Code: protocol.CodeNotTrusted, Message: "这个目录还没被 Claude Code 信任：" + req.Cwd + "。请先在目标机上进入这个目录运行一次 claude，接受信任提示后再试"}
		}
		return "", h.internal(failed, err)
	}
	id := parseBackgrounded(out)
	if id == "" {
		return "", h.internal(failed, fmt.Errorf("claude --bg: no session id in output: %s", tail(out)))
	}
	h.opts.Changed()
	return id, nil
}

// firstPartyURL is the API the account login talks to.
const firstPartyURL = "https://api.anthropic.com"

// accountPreset is the profile the account login's sessions fall into; as
// a preset it means the account login, never that profile's template.
const accountPreset = "api.anthropic.com"

// accountSettingsFile, in Options.Dir, is the account login's copy of the
// user settings.
const accountSettingsFile = "account-settings.json"

// maxModelLen caps a model name.
const maxModelLen = 128

// launchConfig returns the launch flags and extra environment of a new
// session (design D8): with FromSession those of that session; otherwise
// the custom endpoint's or the account login's flags plus one variable per
// model tier given.
func (h *Handler) launchConfig(req protocol.SessionCreateRequest) ([]string, map[string]string, error) {
	if req.FromSession != "" {
		if req.Preset != "" || req.BaseURL != "" || req.APIKey != "" || len(req.Models) > 0 {
			return nil, nil, invalid("照抄会话时不能再选档位，也不能填接口地址、API key 和模型")
		}
		t, ok := h.opts.Source.SessionTemplate(req.FromSession)
		if !ok {
			return nil, nil, invalid("找不到这个会话的启动记录，不能照抄")
		}
		return slices.Clone(t.Flags), t.ProviderEnv, nil
	}
	env := map[string]string{}
	for tier, model := range req.Models {
		name, ok := discovery.ModelEnv[tier]
		if !ok {
			return nil, nil, invalid("不认识的模型档位：" + string(tier))
		}
		if model == "" || len(model) > maxModelLen || strings.ContainsFunc(model, blank) {
			return nil, nil, invalid("模型名不能为空、不能含空格，最长 128 个字符：" + string(tier))
		}
		env[name] = model
	}
	if req.Preset != "" && (req.BaseURL != "" || req.APIKey != "") {
		return nil, nil, invalid("选了档位就不能再填接口地址和 API key")
	}
	if (req.BaseURL == "") != (req.APIKey == "") {
		return nil, nil, invalid("接口地址和 API key 要么都填，要么都不填")
	}
	if req.Preset != "" && req.Preset != accountPreset {
		return h.presetConfig(req.Preset, env)
	}
	if h.opts.Dir == "" {
		return nil, nil, h.internal("启动 claude 失败，请在目标机上查看日志", errors.New("lifecycle: Options.Dir is not set"))
	}
	var flags []string
	var err error
	if req.BaseURL != "" {
		flags, err = h.endpointFlags(req.BaseURL, req.APIKey)
	} else {
		flags, err = h.accountFlags()
	}
	return flags, env, err
}

// presetConfig starts on an existing profile's endpoint. Of the profile's
// template only --settings and providerEnv are kept, the latter without
// the model variables: the models are the request's, and every other flag
// follows the target machine's settings.
func (h *Handler) presetConfig(id string, models map[string]string) ([]string, map[string]string, error) {
	t, ok, err := h.opts.Source.Template(id)
	if err != nil {
		return nil, nil, h.internal("读取档位的启动记录失败，请在目标机上查看日志", err)
	}
	if !ok {
		return nil, nil, invalid("这个档位没有启动记录，不能用来新建")
	}
	flags := []string{}
	for i, f := range t.Flags {
		if f == "--settings" && i+1 < len(t.Flags) {
			flags = append(flags, f, t.Flags[i+1])
			break
		}
	}
	env := map[string]string{}
	for k, v := range t.ProviderEnv {
		env[k] = v
	}
	for _, name := range discovery.ModelEnv {
		delete(env, name)
	}
	for k, v := range models {
		env[k] = v
	}
	return flags, env, nil
}

// endpointFlags points claude at a custom endpoint: the key goes to a file
// in Options.Dir named after its hash, which apiKeyHelper reads, so it is
// never on a command line or in a job record.
func (h *Handler) endpointFlags(baseURL, key string) ([]string, error) {
	u, err := url.Parse(baseURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || strings.ContainsFunc(baseURL, blank) {
		return nil, invalid("接口地址要以 http:// 或 https:// 开头，不能含空格")
	}
	if strings.ContainsFunc(key, blank) {
		return nil, invalid("API key 不能含空格或换行")
	}
	sum := sha256.Sum256([]byte(key))
	path := filepath.Join(h.opts.Dir, "keys", hex.EncodeToString(sum[:8])+".key")
	if err := keyfile.Write(path, []byte(key)); err != nil {
		return nil, h.internal("保存 API key 失败，请在目标机上查看日志", err)
	}
	settings, err := json.Marshal(map[string]any{
		"env":          map[string]string{"ANTHROPIC_BASE_URL": baseURL},
		"apiKeyHelper": "cat '" + strings.ReplaceAll(path, "'", `'\''`) + "'",
	})
	if err != nil {
		return nil, h.internal("启动 claude 失败，请在目标机上查看日志", err)
	}
	return []string{"--settings", string(settings)}, nil
}

// accountFlags starts claude on the account login. The user settings may
// point every launch at a gateway, which Claude Code then refuses to leave
// (No LLM gateway), so they are left out and a copy without the gateway
// is passed instead; project settings still apply.
func (h *Handler) accountFlags() ([]string, error) {
	data, err := accountSettings(filepath.Join(h.opts.Source.ClaudeDir, "settings.json"))
	if err != nil {
		return nil, h.internal("读取目标机的 Claude Code 设置失败，请在目标机上查看日志", err)
	}
	path := filepath.Join(h.opts.Dir, accountSettingsFile)
	if err := keyfile.Write(path, data); err != nil {
		return nil, h.internal("启动 claude 失败，请在目标机上查看日志", err)
	}
	return []string{"--setting-sources", "project,local", "--settings", path}, nil
}

// accountSettings returns the user settings at path, {} when there are
// none, without what points claude at a gateway: apiKeyHelper, the API key
// and token variables and every *_BASE_URL and CLAUDE_CODE_USE_* variable.
// ANTHROPIC_BASE_URL is then the first-party API, so the session is
// listed under it.
func accountSettings(path string) ([]byte, error) {
	settings := map[string]any{}
	raw, err := os.ReadFile(path)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if err == nil {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		if err := dec.Decode(&settings); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
	}
	delete(settings, "apiKeyHelper")
	env, _ := settings["env"].(map[string]any)
	if env == nil {
		env = map[string]any{}
	}
	for name := range env {
		if name == "ANTHROPIC_API_KEY" || name == "ANTHROPIC_AUTH_TOKEN" || strings.HasSuffix(name, "_BASE_URL") || strings.HasPrefix(name, "CLAUDE_CODE_USE_") {
			delete(env, name)
		}
	}
	env["ANTHROPIC_BASE_URL"] = firstPartyURL
	settings["env"] = env
	return json.MarshalIndent(settings, "", "  ")
}

// blank reports a rune that has no place in a model name, URL or key.
func blank(r rune) bool {
	return unicode.IsSpace(r) || unicode.IsControl(r)
}

// invalid is a CodeInvalidParams error with msg for the phone.
func invalid(msg string) error {
	return &protocol.Error{Code: protocol.CodeInvalidParams, Message: msg}
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
	h.opts.Changed()
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
	out, err := h.run(ctx, "", nil, "rm", []string{"rm", t.ID})
	// claude keeps a session whose worktree still holds work and says why;
	// 2.1.327 exits 0 when it does, later versions 1.
	if why, ok := kept(out); ok {
		return &protocol.Error{Code: protocol.CodeInternal, Message: "没有移出：worktree 里还有没保存的工作。\n" + why}
	}
	if err != nil {
		return h.internal("移出会话失败，请在目标机上查看日志", err)
	}
	h.opts.Changed()
	return nil
}

// kept reports whether `claude rm` output says the session was kept, and
// returns claude's own words on why and what to do, e.g.
//
//	kept 1a2b3c4d — its worktree is still at /w/repo/.claude/worktrees/fox
//	  The worktree has uncommitted changes.
//	  commit or stash them and run claude rm 1a2b3c4d again, …
func kept(out []byte) (string, bool) {
	text := strings.TrimSpace(string(ansi.ReplaceAll(out, nil)))
	if !strings.HasPrefix(text, "kept ") {
		return "", false
	}
	lines := strings.Split(text, "\n")
	for i, l := range lines {
		lines[i] = strings.TrimSpace(l)
	}
	return strings.Join(lines, "\n"), true
}

// Wake resumes the stopped session whose full session id is sessionID by
// holding its attach through hold, which runs it again under its own ids,
// and returns once the CLI lists it as running. `claude --bg --resume`
// went through a wrapper's extra flags, which made Claude Code start a
// copy instead (design D8). A new session in the same working directory
// that shows up meanwhile is taken for such a copy: it is stopped and
// removed, and Wake fails. A session that is running is left alone. It
// implements the chat package's Waker.
func (h *Handler) Wake(ctx context.Context, sessionID string, hold func(ctx context.Context, id string) (func(), error)) error {
	targets, err := h.targets(ctx)
	if err != nil {
		return err
	}
	i := slices.IndexFunc(targets, func(t discovery.Target) bool { return t.SessionID == sessionID })
	if i < 0 {
		return &protocol.Error{Code: protocol.CodeSessionNotFound, Message: "找不到会话：" + sessionID}
	}
	t := targets[i]
	if t.Running {
		return nil
	}
	if err := checkDir(t.Cwd, "会话的工作目录已不存在："+t.Cwd); err != nil {
		return err
	}
	known := map[string]bool{}
	for _, k := range targets {
		known[k.ID] = true
	}
	release, err := hold(ctx, t.ID)
	if err != nil {
		return h.internal("接入会话失败，请在目标机上查看日志", err)
	}
	defer release()
	ctx, cancel := context.WithTimeout(ctx, h.opts.WakeTimeout)
	defer cancel()
	ticker := time.NewTicker(h.opts.PollInterval)
	defer ticker.Stop()
	for {
		now, _ := h.opts.Source.Targets(ctx)
		if slices.ContainsFunc(now, func(n discovery.Target) bool { return n.SessionID == sessionID && n.Running }) {
			return nil
		}
		if i := slices.IndexFunc(now, func(n discovery.Target) bool { return !known[n.ID] && n.SessionID != sessionID && n.Cwd == t.Cwd }); i >= 0 {
			h.removeCopy(ctx, now[i].ID)
			return &protocol.Error{Code: protocol.CodeInternal, Message: "唤醒时 claude 另起了会话，已停止并移出；请在目标机上检查 claude 命令是否原样转发参数"}
		}
		select {
		case <-ctx.Done():
			return &protocol.Error{Code: protocol.CodeTimeout, Message: "会话唤醒后没有恢复运行"}
		case <-ticker.C:
		}
	}
}

// removeCopy stops and removes the session with short id id, waiting for
// the CLI to list it as stopped in between; failures are only logged.
func (h *Handler) removeCopy(ctx context.Context, id string) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), claudeTimeout)
	defer cancel()
	if err := h.Stop(ctx, id); err != nil {
		h.opts.Logger.Error("停止唤醒时另起的会话失败", "id", id, "err", err)
	}
	for {
		var pe *protocol.Error
		err := h.Remove(ctx, id)
		if !errors.As(err, &pe) || pe.Code != protocol.CodeSessionRunning {
			if err != nil {
				h.opts.Logger.Error("移出唤醒时另起的会话失败", "id", id, "err", err)
			}
			return
		}
		select {
		case <-ctx.Done():
			h.opts.Logger.Error("唤醒时另起的会话没有停下", "id", id)
			return
		case <-time.After(h.opts.PollInterval):
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
	targets, err := h.targets(ctx)
	if err != nil {
		return discovery.Target{}, err
	}
	for _, t := range targets {
		if match(t) {
			return t, nil
		}
	}
	return discovery.Target{}, &protocol.Error{Code: protocol.CodeSessionNotFound, Message: "找不到会话：" + name}
}

// targets is the CLI list of background sessions.
func (h *Handler) targets(ctx context.Context) ([]discovery.Target, error) {
	targets, err := h.opts.Source.Targets(ctx)
	if err != nil {
		return nil, h.internal("读取会话列表失败，请检查目标机上的 claude 能否运行", err)
	}
	return targets, nil
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

// untrusted reports whether claude refused to launch in a directory it
// has not been told to trust.
func untrusted(out []byte) bool {
	return bytes.Contains(ansi.ReplaceAll(out, nil), []byte("Workspace not trusted"))
}

// environ is the agent's environment without the enroll key, with extra
// added; exec keeps the last value of a repeated name, so extra wins.
func environ(extra map[string]string) []string {
	return append(config.Environ(), envList(extra)...)
}

// envList is extra as NAME=value lines in name order. Names that cannot
// be environment names are skipped.
func envList(extra map[string]string) []string {
	var env []string
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
	// worktreeName is what session.create accepts as a worktree name: it
	// becomes a directory and a branch name.
	worktreeName = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$`)
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
