// Package lifecycle serves the session management methods session.create,
// session.stop, session.remove and profile.forget, and wakes stopped sessions for
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
	"sync"
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
	// skillsTrustWait bounds the wait for the trust prompt of
	// userSkillsDir, which comes before the launch itself within the
	// phone's 30 second wait for session.create.
	skillsTrustWait = 15 * time.Second
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
	// Changed is called once a create, stop, remove or forget has gone through,
	// before the phone gets its answer, so the session list can be read
	// again at once; nil calls nothing.
	Changed func()
	// Now is the clock for request deduplication; defaults to time.Now.
	Now func() time.Time
}

// Handler implements agent.Handler for the session management methods.
// It keeps no per-client state.
type Handler struct {
	opts Options
	// keyMu serializes custom endpoint launches so cleanup cannot remove a shared key.
	keyMu sync.Mutex
	// skillsMu lets one create at a time answer the trust prompt of
	// userSkillsDir.
	skillsMu sync.Mutex
	// createDedup deduplicates session.create requests carrying ClientReqID.
	createDedup *agent.Dedup[string]

	// mu guards launching and launched, which tell Wake the sessions
	// Create and Fork start: launching counts the launches under way, and
	// launched holds the short ids of those done, with when, for as long
	// as a Wake may be waiting.
	mu        sync.Mutex
	launching int
	launched  map[string]time.Time
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
	if opts.Now == nil {
		opts.Now = time.Now
	}
	return &Handler{
		opts:        opts,
		createDedup: agent.NewDedup[string](agent.DefaultDedupTTL, opts.Now),
		launched:    map[string]time.Time{},
	}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method {
	return []protocol.Method{
		protocol.MethodSessionCreate,
		protocol.MethodSessionStop,
		protocol.MethodSessionRemove,
		protocol.MethodProfileForget,
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
	case protocol.MethodProfileForget:
		var body protocol.ProfileForgetRequest
		if err := json.Unmarshal(req.Body, &body); err != nil || body.ProfileID == "" {
			return agent.Fail(req, protocol.CodeInvalidParams, agent.BadRequestMessage)
		}
		if err := h.Forget(ctx, body.ProfileID); err != nil {
			return agent.FailErr(req, err)
		}
		return agent.Reply(req, struct{}{})
	default:
		return agent.FailUnknown(req)
	}
}

// Create starts a background session and returns its short id. Its
// launch flags and extra environment come from launchConfig; claude gets
// them plus ExtraFlags, --permission-mode bypassPermissions unless a
// permission mode or --dangerously-skip-permissions is already set or the
// launch does not read user settings, --bg, -n <name> when a name is given,
// -w <worktree> when a worktree is asked for, and "--" and the first
// message when one is given. A missing working directory or a request
// launchConfig refuses fails before claude runs.
func (h *Handler) Create(ctx context.Context, req protocol.SessionCreateRequest) (string, error) {
	return h.createDedup.Do(ctx, req.ClientReqID, func() (string, error) {
		return h.createOnce(ctx, req)
	})
}

func (h *Handler) createOnce(ctx context.Context, req protocol.SessionCreateRequest) (string, error) {
	if err := checkDir(req.Cwd, "目录不存在："+req.Cwd); err != nil {
		return "", err
	}
	if req.Worktree != "" && !worktreeName.MatchString(req.Worktree) {
		return "", invalid("worktree 名只能用字母、数字、点、横线和下划线，不能以点或横线开头")
	}
	var newKeyPath string
	if req.BaseURL != "" {
		h.keyMu.Lock()
		defer h.keyMu.Unlock()
		path := h.keyPath(req.APIKey)
		if _, err := os.Stat(path); os.IsNotExist(err) {
			newKeyPath = path
		}
	}
	flags, env, err := h.launchConfig(ctx, req)
	if err != nil {
		return "", err
	}
	args := append(flags, h.opts.ExtraFlags...)
	if !hasPermissionMode(args) && readsUserSettings(args) {
		args = append(args, "--permission-mode", string(protocol.PermissionBypass))
	}
	args = append(args, "--bg")
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
	var id string
	end := h.startLaunch()
	defer func() { end(id) }()
	out, err := h.run(ctx, req.Cwd, env, "--bg", args)
	defer func() {
		if newKeyPath != "" && claudeExited(err) {
			_ = os.Remove(newKeyPath)
		}
	}()
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
		if message := bypassRefusal(out); message != "" {
			return "", &protocol.Error{Code: protocol.CodeInternal, Message: message}
		}
		return "", h.internal(failed, err)
	}
	id = parseBackgrounded(out)
	if id == "" {
		return "", h.internal(failed, fmt.Errorf("claude --bg: no session id in output: %s", tail(out)))
	}
	h.opts.Changed()
	return id, nil
}

// hasPermissionMode reports whether args set or inherit a permission mode or skip permissions.
func hasPermissionMode(args []string) bool {
	for _, arg := range args {
		if arg == "--permission-mode" || strings.HasPrefix(arg, "--permission-mode=") ||
			arg == "--inherit-permission-mode" || strings.HasPrefix(arg, "--inherit-permission-mode=") ||
			arg == "--dangerously-skip-permissions" {
			return true
		}
	}
	return false
}

// readsUserSettings reports whether args load user settings, where Claude Code
// keeps bypassPermissions consent. Restricted launches ignore user settings;
// otherwise the last --setting-sources controls whether they are read.
func readsUserSettings(args []string) bool {
	if slices.Contains(args, "--restricted") {
		return false
	}
	for i := len(args) - 1; i >= 0; i-- {
		sources, ok := strings.CutPrefix(args[i], "--setting-sources=")
		if args[i] == "--setting-sources" {
			if i+1 == len(args) {
				return true
			}
			sources, ok = args[i+1], true
		}
		if ok {
			return slices.ContainsFunc(strings.Split(sources, ","), func(source string) bool {
				return strings.TrimSpace(source) == "user"
			})
		}
	}
	return true
}

// claudeExited reports whether err is claude exiting on its own with a
// failure code. A timeout, a cancel or a signal kills claude --bg instead, and
// the background session it was starting may already be running.
func claudeExited(err error) bool {
	var exit *exec.ExitError
	return errors.As(err, &exit) && exit.Exited()
}

// startLaunch notes a launch of a background session under way. The
// function it returns ends it, given the short id of the session started
// or "".
func (h *Handler) startLaunch() func(id string) {
	h.mu.Lock()
	h.launching++
	h.mu.Unlock()
	return func(id string) {
		h.mu.Lock()
		defer h.mu.Unlock()
		h.launching--
		now := time.Now()
		for k, at := range h.launched {
			if now.Sub(at) > h.opts.WakeTimeout+claudeTimeout {
				delete(h.launched, k)
			}
		}
		if id != "" {
			h.launched[id] = now
		}
	}
}

// Fork starts a copy of the session with short id id that carries on its
// conversation, for a session that refuses /fork: Claude Code refuses it
// in a session launched with --setting-sources, as the account login is,
// since the copy would not inherit it (2.1.329). claude runs where the
// session works with its own launch flags, --bg, --resume <session id>
// --fork-session and instruction, when given, as the copy's first
// message; the copy's source is recorded with RecordFork, since Claude
// Code records it only for /fork. It implements the chat package's
// Forker.
func (h *Handler) Fork(ctx context.Context, id, instruction string) error {
	t, err := h.target(ctx, id)
	if err != nil {
		return err
	}
	tmpl, ok := h.opts.Source.SessionTemplate(t.ID)
	if !ok {
		return h.internal("读取会话的启动记录失败，请在目标机上查看日志", fmt.Errorf("no launch record for %s", t.ID))
	}
	dir := t.Cwd
	if t.Worktree != "" {
		dir = t.Worktree
	}
	args := append(slices.Clone(tmpl.Flags), "--bg", "--resume", t.SessionID, "--fork-session")
	if instruction != "" {
		args = append(args, "--", instruction)
	}
	const failed = "Fork 失败，请在目标机上查看日志"
	var copyID string
	end := h.startLaunch()
	defer func() { end(copyID) }()
	out, err := h.run(ctx, dir, tmpl.ProviderEnv, "--bg", args)
	if err != nil {
		return h.internal(failed, err)
	}
	copyID = parseBackgrounded(out)
	if copyID == "" {
		return h.internal(failed, fmt.Errorf("claude --bg --fork-session: no session id in output: %s", tail(out)))
	}
	if err := h.opts.Source.RecordFork(copyID, t.SessionID); err != nil {
		h.opts.Logger.Error("record fork", "err", err)
	}
	h.opts.Changed()
	return nil
}

// firstPartyURL is the API the account login talks to.
const firstPartyURL = "https://api.anthropic.com"

// accountPreset is the profile the account login's sessions fall into; as
// a preset it means the account login, never that profile's template.
const accountPreset = "api.anthropic.com"

// accountSettingsFile, in Options.Dir, is the account login's copy of the
// user settings.
const accountSettingsFile = "account-settings.json"

// userSkillsDir, in Options.Dir, is added to the account login with
// --add-dir: its .claude/skills links to the user's skills, which Claude
// Code leaves out when the user settings are.
const userSkillsDir = "user-skills"

// maxModelLen caps a model name.
const maxModelLen = 128

// launchConfig returns the launch flags and extra environment of a new
// session (design D8): with FromSession those of that session; otherwise
// the custom endpoint's or the account login's flags plus one variable per
// model tier given.
func (h *Handler) launchConfig(ctx context.Context, req protocol.SessionCreateRequest) ([]string, map[string]string, error) {
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
		flags, err = h.accountFlags(ctx)
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

func (h *Handler) keyPath(key string) string {
	sum := sha256.Sum256([]byte(key))
	return filepath.Join(h.opts.Dir, "keys", hex.EncodeToString(sum[:8])+".key")
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
	path := h.keyPath(key)
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
// is passed instead; project settings still apply. The user's skills,
// left out with them, come back through userSkillsDir.
func (h *Handler) accountFlags(ctx context.Context) ([]string, error) {
	data, err := accountSettings(filepath.Join(h.opts.Source.ClaudeDir, "settings.json"))
	if err != nil {
		return nil, h.internal("读取目标机的 Claude Code 设置失败，请在目标机上查看日志", err)
	}
	path := filepath.Join(h.opts.Dir, accountSettingsFile)
	if err := keyfile.Write(path, data); err != nil {
		return nil, h.internal("启动 claude 失败，请在目标机上查看日志", err)
	}
	flags := []string{"--setting-sources", "project,local", "--settings", path}
	dir, ok, err := h.userSkills(ctx)
	if err != nil {
		return nil, err
	}
	if ok {
		flags = append(flags, "--add-dir", dir)
	}
	return flags, nil
}

// userSkills makes userSkillsDir's .claude/skills link to
// <ClaudeDir>/skills and returns userSkillsDir; ok is false when the user
// has no skills directory. claude --bg refuses an --add-dir it does not
// trust, so the first time the folder is untrusted Claude Code's trust
// prompt there is answered with yes, as the user would by starting claude
// in it; the create fails when that does not get the trust recorded.
func (h *Handler) userSkills(ctx context.Context) (dir string, ok bool, err error) {
	const failed = "启动 claude 失败，请在目标机上查看日志"
	target := filepath.Join(h.opts.Source.ClaudeDir, "skills")
	if info, err := os.Stat(target); err != nil || !info.IsDir() {
		return "", false, nil
	}
	dir = filepath.Join(h.opts.Dir, userSkillsDir)
	link := filepath.Join(dir, ".claude", "skills")
	if cur, err := os.Readlink(link); err != nil || cur != target {
		if err := os.MkdirAll(filepath.Dir(link), 0o700); err != nil {
			return "", false, h.internal(failed, err)
		}
		_ = os.Remove(link)
		if err := os.Symlink(target, link); err != nil {
			// A create running at the same time may have made it first.
			if cur, rerr := os.Readlink(link); rerr != nil || cur != target {
				return "", false, h.internal(failed, err)
			}
		}
	}
	config := globalConfig(h.opts.Source.ClaudeDir)
	if trusted(config, dir) {
		return dir, true, nil
	}
	h.skillsMu.Lock()
	defer h.skillsMu.Unlock()
	// A create that held the lock may have trusted it meanwhile.
	if trusted(config, dir) {
		return dir, true, nil
	}
	ctx, cancel := context.WithTimeout(ctx, skillsTrustWait)
	defer cancel()
	err = attach.Trust(ctx, dir, []string{h.opts.Source.ClaudePath}, nil)
	if err == nil && !trusted(config, dir) {
		err = fmt.Errorf("trust of %s not recorded in %s", dir, config)
	}
	if err != nil {
		return "", false, h.internal("没能在目标机上自动信任个人技能目录，请在目标机上进入 "+dir+" 运行一次 claude，选「Yes, I trust this folder」后再试", err)
	}
	return dir, true, nil
}

// globalConfig is Claude Code's global config file, which records the
// folders the user trusts: ~/.claude.json beside the default data
// directory, else inside the data directory, as with CLAUDE_CONFIG_DIR.
func globalConfig(claudeDir string) string {
	if home, err := os.UserHomeDir(); err == nil && claudeDir == filepath.Join(home, ".claude") {
		return filepath.Join(home, ".claude.json")
	}
	return filepath.Join(claudeDir, ".claude.json")
}

// trusted reports whether the global config at config records the trust
// prompt accepted for dir or a folder above it, looked up both as given
// and with symlinks resolved. An unreadable config trusts nothing.
func trusted(config, dir string) bool {
	raw, err := os.ReadFile(config)
	if err != nil {
		return false
	}
	var c struct {
		Projects map[string]struct {
			HasTrustDialogAccepted bool `json:"hasTrustDialogAccepted"`
		} `json:"projects"`
	}
	if json.Unmarshal(raw, &c) != nil {
		return false
	}
	paths := []string{dir}
	if real, err := filepath.EvalSymlinks(dir); err == nil && real != dir {
		paths = append(paths, real)
	}
	for _, p := range paths {
		for {
			if c.Projects[p].HasTrustDialogAccepted {
				return true
			}
			parent := filepath.Dir(p)
			if parent == p {
				break
			}
			p = parent
		}
	}
	return false
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

// Forget deletes an unused launch profile and notifies the session list.
func (h *Handler) Forget(ctx context.Context, profileID string) error {
	err := h.opts.Source.Forget(ctx, profileID)
	switch {
	case errors.Is(err, discovery.ErrProfileFixed):
		return invalid("「默认」和「其他」档位不能删除")
	case errors.Is(err, discovery.ErrProfileInUse):
		return invalid("档位里还有会话，先移出再删除")
	case err != nil:
		return h.internal("删除档位失败，请在目标机上查看日志", err)
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
// that shows up meanwhile is taken for such a copy (see copied): it is
// stopped and removed, and Wake fails. A session that is running is left
// alone. It implements the chat package's Waker.
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
			switch copied, decided := h.copied(ctx, now[i].ID, t.ID); {
			case copied:
				h.removeCopy(ctx, now[i].ID)
				return &protocol.Error{Code: protocol.CodeInternal, Message: "唤醒时 claude 另起了会话，已停止并移出；请在目标机上检查 claude 命令是否原样转发参数"}
			case decided:
				known[now[i].ID] = true
			}
		}
		select {
		case <-ctx.Done():
			return &protocol.Error{Code: protocol.CodeTimeout, Message: "会话唤醒后没有恢复运行"}
		case <-ticker.C:
		}
	}
}

// copied reports whether the session with short id id, new in the working
// directory of the session with short id of while that one wakes, is a
// copy started instead of it. A session Create or Fork launched is not,
// nor a fork of another session, as /fork there starts. decided is false
// while that cannot be told: a launch under way may be what started it, or
// the list does not show it.
func (h *Handler) copied(ctx context.Context, id, of string) (copied, decided bool) {
	h.mu.Lock()
	_, ours := h.launched[id]
	busy := h.launching > 0
	h.mu.Unlock()
	if ours || busy {
		return false, ours
	}
	sessions, err := h.opts.Source.Sessions(ctx)
	i := slices.IndexFunc(sessions, func(s protocol.Session) bool { return s.ID == id })
	if err != nil || i < 0 {
		return false, false
	}
	return sessions[i].ForkOf == "" || sessions[i].ForkOf == of, true
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

// bypassRefusal returns a user-facing message for a known bypassPermissions refusal.
func bypassRefusal(out []byte) string {
	out = ansi.ReplaceAll(out, nil)
	if bytes.Contains(out, []byte("which this launch does not read")) {
		return "这个会话的启动方式不读目标机的用户设置，Claude Code 不允许它以 bypass 模式启动"
	}
	if bytes.Contains(out, []byte("requires accepting the disclaimer")) {
		return "目标机上的 Claude Code 还没同意过 bypass 模式的声明，请先在目标机上交互运行一次 claude --dangerously-skip-permissions，接受提示后再试"
	}
	return ""
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
