package lifecycle_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/agent/lifecycle"
	"clauderc/internal/protocol"
)

var (
	_ agent.Handler = (*lifecycle.Handler)(nil)
	_ chat.Waker    = (*lifecycle.Handler)(nil)
)

const anySettings = `{"env":{"ANTHROPIC_BASE_URL":"https://anyrouter.top","ANTHROPIC_AUTH_TOKEN":"redacted"}}`

type fixture struct {
	claudeDir string
	dir       string
	logDir    string
	src       discovery.Source
	h         *lifecycle.Handler
}

// newFixture runs a Handler against the fake claude with an empty Claude
// data directory, a template cache and a launch directory in their own
// temp directories.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{claudeDir: t.TempDir(), dir: t.TempDir()}
	f.logDir = fakeclaude.Setup(t, f.claudeDir)
	f.src = discovery.Source{
		ClaudePath:   fakeclaude.Path(),
		ClaudeDir:    f.claudeDir,
		ProfilesFile: filepath.Join(t.TempDir(), "profiles.json"),
	}
	f.h = lifecycle.New(lifecycle.Options{Source: f.src, Dir: f.dir, WakeTimeout: 5 * time.Second, PollInterval: 20 * time.Millisecond})
	return f
}

func writeJSON(t *testing.T, path string, v any) {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
}

// session is one entry of the fake `claude agents --json --all`.
type session struct {
	id, sessionID, cwd string
	running            bool
}

func (f *fixture) setAgents(t *testing.T, sessions ...session) {
	t.Helper()
	writeJSON(t, filepath.Join(f.claudeDir, "agents.json"), agentEntries(sessions...))
}

func agentEntries(sessions ...session) []map[string]any {
	entries := []map[string]any{}
	for _, s := range sessions {
		e := map[string]any{"id": s.id, "sessionId": s.sessionID, "cwd": s.cwd, "kind": "background",
			"startedAt": 1794529871000, "name": "task", "status": "idle", "state": "done", "pid": nil}
		if s.running {
			e["pid"] = 42
		}
		entries = append(entries, e)
	}
	return entries
}

// job writes jobs/<id>/state.json.
func (f *fixture) job(t *testing.T, id string, flags []string, env map[string]string, updatedAt string) {
	t.Helper()
	writeJSON(t, filepath.Join(f.claudeDir, "jobs", id, "state.json"), map[string]any{
		"respawnFlags": flags, "providerEnv": env, "cwd": "/w/" + id, "updatedAt": updatedAt,
	})
}

func (f *fixture) calls(t *testing.T) []fakeclaude.Call {
	t.Helper()
	return fakeclaude.Calls(t, f.logDir)
}

// launches are the recorded calls other than `claude agents`.
func (f *fixture) launches(t *testing.T) []fakeclaude.Call {
	t.Helper()
	var out []fakeclaude.Call
	for _, c := range f.calls(t) {
		if len(c.Args) == 0 || c.Args[0] != "agents" {
			out = append(out, c)
		}
	}
	return out
}

func realDir(t *testing.T, dir string) string {
	t.Helper()
	d, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func codeOf(err error) protocol.ErrorCode {
	var e *protocol.Error
	if errors.As(err, &e) {
		return e.Code
	}
	return ""
}

func wantEqual(t *testing.T, what string, got, want []string) {
	t.Helper()
	if !slices.Equal(got, want) {
		t.Errorf("%s = %q, want %q", what, got, want)
	}
}

// accountFlags are what an account login launch starts with in f.
func (f *fixture) accountFlags() []string {
	return []string{"--setting-sources", "project,local", "--settings", filepath.Join(f.dir, "account-settings.json")}
}

// envHas reports whether env sets name, whatever the value.
func envHas(env []string, name string) bool {
	return slices.ContainsFunc(env, func(kv string) bool { return strings.HasPrefix(kv, name+"=") })
}

// unsetEnv clears name for the test and restores it afterwards.
func unsetEnv(t *testing.T, name string) {
	t.Helper()
	t.Setenv(name, "")
	os.Unsetenv(name)
}

func mode(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.Mode().Perm()
}

// Both fields set: the endpoint goes in --settings and its key in a file
// apiKeyHelper reads; the model tiers given become their variables.
func TestCreateCustomEndpoint(t *testing.T) {
	f := newFixture(t)
	for _, name := range []string{"ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"} {
		unsetEnv(t, name)
	}
	t.Setenv("FAKE_CLAUDE_BG_ID", "1234abcd")
	work := t.TempDir()
	const key = "sk-test-0123456789"

	id, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{
		Cwd: work, Name: "check tests", Prompt: "检查测试是否通过",
		BaseURL: "https://anyrouter.example/v1", APIKey: key,
		Models: map[protocol.ModelTier]string{protocol.ModelOpus: "claude-opus-5-5[1M]"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if id != "1234abcd" {
		t.Errorf("id = %q, want 1234abcd", id)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once: %+v", len(calls), calls)
	}
	c := calls[0]
	keys, _ := filepath.Glob(filepath.Join(f.dir, "keys", "*.key"))
	if len(keys) != 1 {
		t.Fatalf("key files = %q, want one", keys)
	}
	if got, _ := os.ReadFile(keys[0]); string(got) != key {
		t.Errorf("key file holds %q, want the key", got)
	}
	if m := mode(t, keys[0]); m != 0o600 {
		t.Errorf("key file mode = %#o, want 0600", m)
	}
	settings, _ := json.Marshal(map[string]any{
		"env":          map[string]string{"ANTHROPIC_BASE_URL": "https://anyrouter.example/v1"},
		"apiKeyHelper": "cat '" + keys[0] + "'",
	})
	wantEqual(t, "args", c.Args, []string{"--settings", string(settings), "--bg", "-n", "check tests", "--", "检查测试是否通过"})
	for _, a := range c.Args {
		if strings.Contains(a, key) {
			t.Errorf("argument %q carries the key", a)
		}
	}
	if !slices.Contains(c.Env, "ANTHROPIC_DEFAULT_OPUS_MODEL=claude-opus-5-5[1M]") {
		t.Errorf("env %q lacks the opus tier", c.Env)
	}
	for _, name := range []string{"ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"} {
		if envHas(c.Env, name) {
			t.Errorf("env sets %s, a tier left empty", name)
		}
	}
	if c.Cwd != realDir(t, work) {
		t.Errorf("cwd = %q, want %q", c.Cwd, work)
	}
}

// Both fields empty: the user settings are left out and a copy without
// the gateway is passed, the rest of it unchanged.
func TestCreateAccountLogin(t *testing.T) {
	f := newFixture(t)
	user := map[string]any{
		"apiKeyHelper": "/home/me/.claude/router-key.sh",
		"env": map[string]any{
			"ANTHROPIC_BASE_URL": "https://route.example", "ANTHROPIC_AUTH_TOKEN": "t", "ANTHROPIC_API_KEY": "k",
			"ANTHROPIC_BEDROCK_BASE_URL": "https://b.example", "CLAUDE_CODE_USE_BEDROCK": "1", "KEEP_ME": "yes",
		},
		"hooks":    map[string]any{"Stop": []any{map[string]any{"hooks": []any{map[string]any{"type": "command", "command": "notify"}}}}},
		"model":    "haiku",
		"worktree": map[string]any{"bgIsolation": "none"},
		"big":      json.Number("12345678901234567890"),
	}
	writeJSON(t, filepath.Join(f.claudeDir, "settings.json"), user)

	if _, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()}); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, append(f.accountFlags(), "--bg"))
	path := filepath.Join(f.dir, "account-settings.json")
	if m := mode(t, path); m != 0o600 {
		t.Errorf("settings copy mode = %#o, want 0600", m)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&got); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{}
	for k, v := range user {
		want[k] = v
	}
	delete(want, "apiKeyHelper")
	want["env"] = map[string]any{"KEEP_ME": "yes", "ANTHROPIC_BASE_URL": "https://api.anthropic.com"}
	wantJSON, _ := json.Marshal(want)
	gotJSON, _ := json.Marshal(got)
	if !bytes.Equal(gotJSON, wantJSON) {
		t.Errorf("settings copy = %s\nwant %s", gotJSON, wantJSON)
	}
}

// Without user settings the copy only names the first-party API.
func TestCreateAccountLoginWithoutSettings(t *testing.T) {
	f := newFixture(t)
	if _, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()}); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(f.dir, "account-settings.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if s, _ := json.Marshal(got); string(s) != `{"env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"}}` {
		t.Errorf("settings copy = %s", s)
	}
}

func TestCreateWithoutNameAndPrompt(t *testing.T) {
	f := newFixture(t)
	id, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if id != "f00dcafe" {
		t.Errorf("id = %q, want the fake's default f00dcafe", id)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, append(f.accountFlags(), "--bg"))
}

// ExtraFlags go after the launch flags, before --bg.
func TestCreateExtraFlags(t *testing.T) {
	f := newFixture(t)
	h := lifecycle.New(lifecycle.Options{Source: f.src, Dir: f.dir, ExtraFlags: []string{"--permission-mode", "manual"}})
	if _, err := h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()}); err != nil {
		t.Fatal(err)
	}
	wantEqual(t, "args", f.calls(t)[0].Args, append(f.accountFlags(), "--permission-mode", "manual", "--bg"))
}

// A first message that looks like a flag still reaches claude as the prompt.
func TestCreatePromptLikeFlag(t *testing.T) {
	f := newFixture(t)
	req := protocol.SessionCreateRequest{Cwd: t.TempDir(), Name: "-x", Prompt: "--dangerously-skip-permissions"}
	if _, err := f.h.Create(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, append(f.accountFlags(), "--bg", "-n", "-x", "--", "--dangerously-skip-permissions"))
}

// A worktree name goes to claude as -w; one that is no plain name is
// refused before claude runs.
func TestCreateInWorktree(t *testing.T) {
	f := newFixture(t)
	req := protocol.SessionCreateRequest{Cwd: t.TempDir(), Name: "wt", Prompt: "开始", Worktree: "probe-wt"}
	if _, err := f.h.Create(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, append(f.accountFlags(), "--bg", "-n", "wt", "-w", "probe-wt", "--", "开始"))
	for _, name := range []string{"has space", "a/b", "-x", ".hidden", "../up", strings.Repeat("a", 65)} {
		req.Worktree = name
		if _, err := f.h.Create(context.Background(), req); codeOf(err) != protocol.CodeInvalidParams {
			t.Errorf("worktree %q: %v, want %s", name, err, protocol.CodeInvalidParams)
		}
	}
	if n := len(f.calls(t)); n != 1 {
		t.Errorf("claude ran %d times, want only the first launch", n)
	}
}

// FromSession copies that session's own flags and environment, without
// the ones bound to it.
func TestCreateFromSession(t *testing.T) {
	f := newFixture(t)
	f.job(t, "src00001", []string{"--settings", anySettings, "-n", "prev", "--resume", "8f0c2a5e-0000-4000-8000-000000000001", "--model", "opus", "--name=old"},
		map[string]string{"ANTHROPIC_MODEL": "claude-opus-test", "CLAUDE_CONFIG_DIR": "/x"}, "2026-11-13T08:00:00.000Z")
	req := protocol.SessionCreateRequest{Cwd: t.TempDir(), FromSession: "src00001", Worktree: "wt-1", Prompt: "先跑测试"}
	if _, err := f.h.Create(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, []string{"--settings", anySettings, "--model", "opus", "--bg", "-w", "wt-1", "--", "先跑测试"})
	for _, kv := range []string{"ANTHROPIC_MODEL=claude-opus-test", "CLAUDE_CONFIG_DIR=/x"} {
		if !slices.Contains(calls[0].Env, kv) {
			t.Errorf("env %q lacks %s", calls[0].Env, kv)
		}
	}
}

// A preset reuses an existing profile's endpoint: of its template only
// --settings and providerEnv without the model variables are kept, and the
// models are the request's; "api.anthropic.com" is the account login.
func TestCreatePreset(t *testing.T) {
	for _, name := range discovery.ModelEnv {
		unsetEnv(t, name)
	}
	f := newFixture(t)
	f.job(t, "anyr0001", []string{"--settings", anySettings, "--agent", "claude", "--permission-mode", "bypassPermissions", "--model", "claude-opus-5-5[1M]"},
		map[string]string{"ANTHROPIC_MODEL": "claude-opus-5-5[1M]", "ANTHROPIC_DEFAULT_HAIKU_MODEL": "claude-haiku-5-5", "CLAUDE_BG_SESSION_CREDENTIAL": "none"}, "2026-11-13T08:00:00.000Z")
	f.job(t, "dflt0001", []string{"--permission-mode", "auto", "--model", "hy-4-flash[1M]"},
		map[string]string{"ANTHROPIC_MODEL": "hy-4-flash[1M]", "CLAUDE_BG_SESSION_CREDENTIAL": "none"}, "2026-11-13T08:00:00.000Z")
	f.setAgents(t)
	// Listing saves the templates, and runs `claude agents` itself.
	if _, err := f.src.List(context.Background()); err != nil {
		t.Fatal(err)
	}
	listed := len(f.calls(t))
	haiku := map[protocol.ModelTier]string{protocol.ModelHaiku: "hy-4-flash[1M]"}
	for _, preset := range []string{"anyrouter.top", "default", "api.anthropic.com"} {
		if _, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir(), Preset: preset, Models: haiku}); err != nil {
			t.Fatalf("preset %s: %v", preset, err)
		}
	}
	calls := f.calls(t)[listed:]
	if len(calls) != 3 {
		t.Fatalf("claude ran %d times, want 3", len(calls))
	}
	wantEqual(t, "anyrouter.top args", calls[0].Args, []string{"--settings", anySettings, "--bg"})
	wantEqual(t, "default args", calls[1].Args, []string{"--bg"})
	wantEqual(t, "api.anthropic.com args", calls[2].Args, append(f.accountFlags(), "--bg"))
	for i, c := range calls {
		if !slices.Contains(c.Env, "ANTHROPIC_DEFAULT_HAIKU_MODEL=hy-4-flash[1M]") {
			t.Errorf("call %d env %q lacks the requested haiku model", i, c.Env)
		}
		if envHas(c.Env, "ANTHROPIC_MODEL") {
			t.Errorf("call %d env %q keeps the template's main model", i, c.Env)
		}
	}
	for _, c := range calls[:2] {
		if !slices.Contains(c.Env, "CLAUDE_BG_SESSION_CREDENTIAL=none") {
			t.Errorf("env %q lacks the template's providerEnv", c.Env)
		}
	}
}

func TestCreateRefusedBeforeClaude(t *testing.T) {
	f := newFixture(t)
	f.job(t, "src00001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	f.job(t, "bad00001", nil, nil, "2026-11-13T09:00:00.000Z") // no respawnFlags
	work := t.TempDir()
	file := filepath.Join(work, "file")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	const url, key = "https://anyrouter.example", "sk-1"
	opus := func(m string) map[protocol.ModelTier]string {
		return map[protocol.ModelTier]string{protocol.ModelOpus: m}
	}
	cases := []struct {
		name string
		req  protocol.SessionCreateRequest
		want protocol.ErrorCode
	}{
		{"missing dir", protocol.SessionCreateRequest{Cwd: filepath.Join(work, "nope")}, protocol.CodeDirNotFound},
		{"file", protocol.SessionCreateRequest{Cwd: file}, protocol.CodeDirNotFound},
		{"relative dir", protocol.SessionCreateRequest{Cwd: "code"}, protocol.CodeInvalidParams},
		{"url only", protocol.SessionCreateRequest{Cwd: work, BaseURL: url}, protocol.CodeInvalidParams},
		{"key only", protocol.SessionCreateRequest{Cwd: work, APIKey: key}, protocol.CodeInvalidParams},
		{"other scheme", protocol.SessionCreateRequest{Cwd: work, BaseURL: "ftp://anyrouter.example", APIKey: key}, protocol.CodeInvalidParams},
		{"no host", protocol.SessionCreateRequest{Cwd: work, BaseURL: "https://", APIKey: key}, protocol.CodeInvalidParams},
		{"url with space", protocol.SessionCreateRequest{Cwd: work, BaseURL: "https://a b.example", APIKey: key}, protocol.CodeInvalidParams},
		{"key with newline", protocol.SessionCreateRequest{Cwd: work, BaseURL: url, APIKey: "sk-1\nx"}, protocol.CodeInvalidParams},
		{"unknown tier", protocol.SessionCreateRequest{Cwd: work, Models: map[protocol.ModelTier]string{"turbo": "m"}}, protocol.CodeInvalidParams},
		{"model with space", protocol.SessionCreateRequest{Cwd: work, Models: opus("claude opus")}, protocol.CodeInvalidParams},
		{"empty model", protocol.SessionCreateRequest{Cwd: work, Models: opus("")}, protocol.CodeInvalidParams},
		{"long model", protocol.SessionCreateRequest{Cwd: work, Models: opus(strings.Repeat("m", 129))}, protocol.CodeInvalidParams},
		{"copy with url", protocol.SessionCreateRequest{Cwd: work, FromSession: "src00001", BaseURL: url, APIKey: key}, protocol.CodeInvalidParams},
		{"copy with models", protocol.SessionCreateRequest{Cwd: work, FromSession: "src00001", Models: opus("m")}, protocol.CodeInvalidParams},
		{"copy without record", protocol.SessionCreateRequest{Cwd: work, FromSession: "bad00001"}, protocol.CodeInvalidParams},
		{"copy of unknown", protocol.SessionCreateRequest{Cwd: work, FromSession: "nope0001"}, protocol.CodeInvalidParams},
		{"copy with preset", protocol.SessionCreateRequest{Cwd: work, FromSession: "src00001", Preset: "default"}, protocol.CodeInvalidParams},
		{"preset with url", protocol.SessionCreateRequest{Cwd: work, Preset: "default", BaseURL: url, APIKey: key}, protocol.CodeInvalidParams},
		{"preset other", protocol.SessionCreateRequest{Cwd: work, Preset: "other"}, protocol.CodeInvalidParams},
		{"preset without template", protocol.SessionCreateRequest{Cwd: work, Preset: "relay.example.net"}, protocol.CodeInvalidParams},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := f.h.Create(context.Background(), c.req)
			if got := codeOf(err); got != c.want {
				t.Errorf("error = %v (code %q), want code %q", err, got, c.want)
			}
		})
	}
	if calls := f.calls(t); len(calls) != 0 {
		t.Errorf("claude ran %d times, want never: %+v", len(calls), calls)
	}
	if keys, _ := filepath.Glob(filepath.Join(f.dir, "keys", "*")); len(keys) != 0 {
		t.Errorf("refused requests left key files %q", keys)
	}
}

// Without Options.Dir nothing can be launched.
func TestCreateWithoutDir(t *testing.T) {
	f := newFixture(t)
	h := lifecycle.New(lifecycle.Options{Source: f.src})
	if _, err := h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()}); codeOf(err) != protocol.CodeInternal {
		t.Errorf("error = %v, want %s", err, protocol.CodeInternal)
	}
	if calls := f.calls(t); len(calls) != 0 {
		t.Errorf("claude ran %d times, want never", len(calls))
	}
}

// writeScript writes an executable shell script standing in for claude.
func writeScript(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "claude")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body), 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestCreateClaudeFailures(t *testing.T) {
	cases := map[string]string{
		"exit status":   "echo 'Error: bad flag' >&2; exit 1\n",
		"no id printed": "echo started\n",
	}
	for name, script := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
			src := f.src
			src.ClaudePath = writeScript(t, script)
			var logged bytes.Buffer
			h := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir, Logger: slog.New(slog.NewTextHandler(&logged, nil))})
			_, err := h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()})
			var pe *protocol.Error
			if !errors.As(err, &pe) || pe.Code != protocol.CodeInternal || pe.Message != "启动 claude 失败，请在目标机上查看日志" {
				t.Fatalf("error = %v, want the fixed internal message", err)
			}
			// claude's own output stays in the agent's log.
			for _, raw := range []string{"bad flag", "started"} {
				if strings.Contains(script, raw) && !strings.Contains(logged.String(), raw) {
					t.Errorf("log %q lacks claude's output %q", logged.String(), raw)
				}
			}
		})
	}
}

// trustScript is a fake claude for the trust tests. --bg fails as Claude
// Code does while the marker file exists, and launches otherwise; run
// without arguments it acts as interactive mode does on prompt: "show"
// draws the 2.1.327 trust prompt, moves the selection to yes on Down and
// removes the marker on Enter there, "deaf" does so too but drops the
// first key, "silent" never draws it, "exit" ends at once. Every call appends
// "bg" or "ui" to the log file.
func trustScript(t *testing.T, prompt string) (claude, marker, log string) {
	t.Helper()
	dir := t.TempDir()
	marker, log = filepath.Join(dir, "untrusted"), filepath.Join(dir, "log")
	if err := os.WriteFile(marker, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	claude = writeScript(t, `log=`+log+`
marker=`+marker+`
for a in "$@"; do
	if [ "$a" = --bg ]; then
		echo bg >>"$log"
		if [ -f "$marker" ]; then
			echo 'Error: Workspace not trusted. Please run claude in this directory first.' >&2
			exit 1
		fi
		echo 'backgrounded · 7a7a7a7a · x'
		exit 0
	fi
done
echo ui >>"$log"
pwd -P >"$log.cwd"
case "`+prompt+`" in
exit) exit 1 ;;
silent) sleep 30; exit 0 ;;
esac
stty raw -echo
draw() {
	printf '\033[2J\033[HAccessing workspace:\r\n\r\n %s\r\n\r\nQuick safety check\r\n\r\n%s No, exit\r\n%s Yes, I trust this folder\r\n\r\nEnter to confirm\r\n' "$PWD" "$1" "$2"
}
mark=$(printf '\342\235\257')
draw "$mark" ' '
# "deaf" drops the first key, as Claude Code may before it reads keys.
[ "`+prompt+`" = deaf ] && dd bs=1 count=3 >/dev/null 2>&1
[ "$(dd bs=1 count=3 2>/dev/null | od -An -c | tr -d ' \n')" = '033[B' ] || exit 1
draw ' ' "$mark"
if [ "$(dd bs=1 count=1 2>/dev/null | od -An -c | tr -d ' \n')" = '\r' ]; then
	rm -f "$marker"
	printf '\033[2J\033[H> \r\n'
	sleep 30
fi
exit 1
`)
	return claude, marker, log
}

func readLog(t *testing.T, path string) string {
	t.Helper()
	b, _ := os.ReadFile(path)
	return strings.Join(strings.Fields(string(b)), " ")
}

// Without trust, an untrusted directory fails with its own code and
// claude is not run again.
func TestCreateUntrustedDir(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	src := f.src
	var log string
	src.ClaudePath, _, log = trustScript(t, "show")
	work := t.TempDir()
	_, err := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir}).Create(context.Background(), protocol.SessionCreateRequest{Cwd: work})
	var pe *protocol.Error
	if !errors.As(err, &pe) || pe.Code != protocol.CodeNotTrusted || pe.Message != "这个目录还没被 Claude Code 信任："+work+"。请先在目标机上进入这个目录运行一次 claude，接受信任提示后再试" {
		t.Fatalf("error = %v, want notTrusted naming %s", err, work)
	}
	if got := readLog(t, log); got != "bg" {
		t.Errorf("calls = %q, want one launch", got)
	}
}

// With trust, the agent says yes on the trust prompt, in the directory,
// then launches again; a key the prompt missed is pressed again.
func TestCreateTrustsFirst(t *testing.T) {
	for _, prompt := range []string{"show", "deaf"} {
		t.Run(prompt, func(t *testing.T) { createTrustsFirst(t, prompt) })
	}
}

func createTrustsFirst(t *testing.T, prompt string) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	src := f.src
	var marker, log string
	src.ClaudePath, marker, log = trustScript(t, prompt)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	work := t.TempDir()
	id, err := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir}).Create(ctx, protocol.SessionCreateRequest{Cwd: work, Trust: true})
	if err != nil || id != "7a7a7a7a" {
		t.Fatalf("Create = %q, %v", id, err)
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Error("the trust prompt was not answered with yes")
	}
	if got := readLog(t, log); got != "bg ui bg" {
		t.Errorf("calls = %q, want launch, trust prompt, launch", got)
	}
	want, _ := filepath.EvalSymlinks(work)
	if got := readLog(t, log+".cwd"); got != want {
		t.Errorf("trust prompt ran in %q, want %q", got, want)
	}
}

// No prompt, or claude ending before it, fails the create without a
// second launch.
func TestCreateTrustFails(t *testing.T) {
	for _, prompt := range []string{"silent", "exit"} {
		t.Run(prompt, func(t *testing.T) {
			f := newFixture(t)
			f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
			src := f.src
			var log string
			src.ClaudePath, _, log = trustScript(t, prompt)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_, err := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir}).Create(ctx, protocol.SessionCreateRequest{Cwd: t.TempDir(), Trust: true})
			var pe *protocol.Error
			if !errors.As(err, &pe) || pe.Code != protocol.CodeInternal || !strings.Contains(pe.Message, "Yes, I trust this folder") {
				t.Fatalf("error = %v, want the trust failure", err)
			}
			if got := readLog(t, log); got != "bg ui" {
				t.Errorf("calls = %q, want no second launch", got)
			}
		})
	}
}

// The enroll key never reaches claude, nor what a session runs.
func TestClaudeGetsNoEnrollKey(t *testing.T) {
	t.Setenv("CLAUDERC_ENROLL_KEY", "enroll-secret")
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	envFile := filepath.Join(t.TempDir(), "env")
	src := f.src
	src.ClaudePath = writeScript(t, "env >"+envFile+"\necho 'backgrounded · 1234abcd · x'\n")
	if _, err := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir}).Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()}); err != nil {
		t.Fatal(err)
	}
	env, err := os.ReadFile(envFile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(env), "CLAUDERC_ENROLL_KEY") || strings.Contains(string(env), "enroll-secret") {
		t.Error("claude got the enroll key")
	}
}

func TestCreateParsesColouredOutput(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	src := f.src
	src.ClaudePath = writeScript(t, `printf '\033[2m✓\033[0m backgrounded \033[2m·\033[0m \033[1m9a8b7c6d\033[0m · task\n'`+"\n")
	h := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir})
	id, err := h.Create(context.Background(), protocol.SessionCreateRequest{Cwd: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	if id != "9a8b7c6d" {
		t.Errorf("id = %q, want 9a8b7c6d", id)
	}
}

func TestStop(t *testing.T) {
	f := newFixture(t)
	f.setAgents(t, session{"run00001", "uuid-run", "/w/a", true}, session{"stp00001", "uuid-stp", "/w/b", false})
	ctx := context.Background()

	if err := f.h.Stop(ctx, "run00001"); err != nil {
		t.Fatal(err)
	}
	if err := f.h.Stop(ctx, "stp00001"); err != nil {
		t.Fatalf("stopping a stopped session: %v", err)
	}
	if err := f.h.Stop(ctx, "nope0001"); codeOf(err) != protocol.CodeSessionNotFound {
		t.Errorf("unknown session: %v, want %s", err, protocol.CodeSessionNotFound)
	}
	launches := f.launches(t)
	if len(launches) != 1 {
		t.Fatalf("calls besides agents = %+v, want one stop", launches)
	}
	wantEqual(t, "args", launches[0].Args, []string{"stop", "run00001"})
}

func TestRemove(t *testing.T) {
	f := newFixture(t)
	f.setAgents(t, session{"run00001", "uuid-run", "/w/a", true}, session{"stp00001", "uuid-stp", "/w/b", false})
	ctx := context.Background()

	if err := f.h.Remove(ctx, "run00001"); codeOf(err) != protocol.CodeSessionRunning {
		t.Errorf("removing a running session: %v, want %s", err, protocol.CodeSessionRunning)
	}
	if launches := f.launches(t); len(launches) != 0 {
		t.Fatalf("claude was called for a running session: %+v", launches)
	}
	if err := f.h.Remove(ctx, "nope0001"); codeOf(err) != protocol.CodeSessionNotFound {
		t.Errorf("unknown session: %v, want %s", err, protocol.CodeSessionNotFound)
	}
	if err := f.h.Remove(ctx, "stp00001"); err != nil {
		t.Fatal(err)
	}
	launches := f.launches(t)
	if len(launches) != 1 {
		t.Fatalf("calls besides agents = %+v, want one rm", launches)
	}
	wantEqual(t, "args", launches[0].Args, []string{"rm", "stp00001"})
}

// Changed is called after each create, stop or remove that went through,
// and not after one that failed.
func TestChangedAfterActions(t *testing.T) {
	f := newFixture(t)
	changed := 0
	f.h = lifecycle.New(lifecycle.Options{Source: f.src, Dir: f.dir, Changed: func() { changed++ }})
	f.setAgents(t, session{"run00001", "uuid-run", "/w/a", true}, session{"stp00001", "uuid-stp", "/w/b", false})
	f.job(t, "run00001", []string{"--model", "opus"}, nil, "2026-11-13T10:00:00Z")
	ctx := context.Background()
	steps := []struct {
		what string
		do   func() error
		want int
	}{
		{"stop", func() error { return f.h.Stop(ctx, "run00001") }, 1},
		{"remove", func() error { return f.h.Remove(ctx, "stp00001") }, 2},
		{"create", func() error {
			_, err := f.h.Create(ctx, protocol.SessionCreateRequest{Cwd: t.TempDir()})
			return err
		}, 3},
		{"refused remove", func() error {
			if err := f.h.Remove(ctx, "run00001"); err == nil {
				return errors.New("removing a running session went through")
			}
			return nil
		}, 3},
	}
	for _, s := range steps {
		if err := s.do(); err != nil {
			t.Fatalf("%s: %v", s.what, err)
		}
		if changed != s.want {
			t.Errorf("after %s Changed ran %d times, want %d", s.what, changed, s.want)
		}
	}
}

// claude keeps a session whose worktree still holds work: the phone gets
// claude's reasons, whatever its exit code.
func TestRemoveKept(t *testing.T) {
	const out = "kept stp00001 \u2014 its worktree is still at /w/b/.claude/worktrees/fox\n" +
		"  The worktree has uncommitted changes.\n" +
		"  commit or stash them and run claude rm stp00001 again\n"
	for _, code := range []string{"0", "1"} {
		t.Run("exit "+code, func(t *testing.T) {
			f := newFixture(t)
			changed := 0
			f.h = lifecycle.New(lifecycle.Options{Source: f.src, Dir: f.dir, Changed: func() { changed++ }})
			f.setAgents(t, session{"stp00001", "uuid-stp", "/w/b", false})
			if err := os.WriteFile(filepath.Join(f.claudeDir, "rm.out"), []byte(out), 0o600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(f.claudeDir, "rm.exit"), []byte(code), 0o600); err != nil {
				t.Fatal(err)
			}
			err := f.h.Remove(context.Background(), "stp00001")
			var pe *protocol.Error
			if !errors.As(err, &pe) {
				t.Fatalf("Remove = %v, want a protocol error", err)
			}
			for _, want := range []string{"没有移出", "The worktree has uncommitted changes.", "commit or stash them and run claude rm stp00001 again"} {
				if !strings.Contains(pe.Message, want) {
					t.Errorf("message %q lacks %q", pe.Message, want)
				}
			}
			if changed != 0 {
				t.Errorf("Changed ran %d times for a kept session", changed)
			}
		})
	}
}

const wakeUUID = "8f0c2a5e-0000-4000-8000-000000000042"

// holder stands in for the chat package's hold: it records the short ids
// it attached to and how many holds were released, and runs attached on
// each hold, as the attach runs the stopped session.
type holder struct {
	mu       sync.Mutex
	held     []string
	released int
	attached func()
	err      error
}

func (h *holder) hold(ctx context.Context, id string) (func(), error) {
	h.mu.Lock()
	h.held = append(h.held, id)
	h.mu.Unlock()
	if h.err != nil {
		return nil, h.err
	}
	if h.attached != nil {
		h.attached()
	}
	return func() {
		h.mu.Lock()
		h.released++
		h.mu.Unlock()
	}, nil
}

func (h *holder) state() ([]string, int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return slices.Clone(h.held), h.released
}

func TestWake(t *testing.T) {
	f := newFixture(t)
	work := t.TempDir()
	f.setAgents(t, session{"stp00001", wakeUUID, work, false})
	hd := &holder{}
	done := make(chan error, 1)
	go func() { done <- f.h.Wake(context.Background(), wakeUUID, hd.hold) }()

	// Once attached, the CLI lists the session as running.
	deadline := time.Now().Add(5 * time.Second)
	for held, _ := hd.state(); len(held) == 0; held, _ = hd.state() {
		if time.Now().After(deadline) {
			t.Fatal("no attach within 5 seconds")
		}
		select {
		case err := <-done:
			t.Fatalf("Wake returned %v before attaching", err)
		case <-time.After(10 * time.Millisecond):
		}
	}
	select {
	case err := <-done:
		t.Fatalf("Wake returned %v while the session was still stopped", err)
	case <-time.After(100 * time.Millisecond):
	}
	f.setAgents(t, session{"stp00001", wakeUUID, work, true})
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Wake did not return after the session came back")
	}

	held, released := hd.state()
	wantEqual(t, "attached", held, []string{"stp00001"})
	if released != 1 {
		t.Errorf("released %d holds, want 1", released)
	}
	// No --bg --resume, and nothing to stop or remove.
	if launches := f.launches(t); len(launches) != 0 {
		t.Errorf("calls besides agents = %+v, want none", launches)
	}
}

// A session that shows up in the working directory while waking is a copy
// started instead: it is stopped and removed, and Wake says so.
func TestWakeRemovesCopy(t *testing.T) {
	f := newFixture(t)
	work := t.TempDir()
	orig := session{"stp00001", wakeUUID, work, false}
	before := session{"old00001", "uuid-old", work, true} // there before waking
	f.setAgents(t, orig, before)
	copyOf := func(running bool) session { return session{"cpy00001", "uuid-copy", work, running} }
	hd := &holder{attached: func() { f.setAgents(t, orig, before, copyOf(true)) }}
	// claude stop takes effect at once.
	writeJSON(t, filepath.Join(f.claudeDir, "agents.stopped.json"), agentEntries(orig, before, copyOf(false)))
	src := f.src
	src.ClaudePath = writeScript(t, `if [ "$1" = stop ]; then cp "$FAKE_CLAUDE_DIR/agents.stopped.json" "$FAKE_CLAUDE_DIR/agents.json"; fi
exec "`+fakeclaude.Path()+`" "$@"
`)
	h := lifecycle.New(lifecycle.Options{Source: src, Dir: f.dir, WakeTimeout: 5 * time.Second, PollInterval: 20 * time.Millisecond})

	err := h.Wake(context.Background(), wakeUUID, hd.hold)
	var pe *protocol.Error
	if !errors.As(err, &pe) || !strings.Contains(pe.Message, "另起了会话") {
		t.Fatalf("error = %v, want one saying a copy was started", err)
	}
	launches := f.launches(t)
	if len(launches) != 2 {
		t.Fatalf("calls besides agents = %+v, want stop and rm of the copy", launches)
	}
	wantEqual(t, "first call", launches[0].Args, []string{"stop", "cpy00001"})
	wantEqual(t, "second call", launches[1].Args, []string{"rm", "cpy00001"})
	if _, released := hd.state(); released != 1 {
		t.Errorf("released %d holds, want 1", released)
	}
}

func TestWakeRunningSession(t *testing.T) {
	f := newFixture(t)
	f.setAgents(t, session{"run00001", wakeUUID, t.TempDir(), true})
	hd := &holder{}
	if err := f.h.Wake(context.Background(), wakeUUID, hd.hold); err != nil {
		t.Fatal(err)
	}
	if held, _ := hd.state(); len(held) != 0 {
		t.Errorf("a running session was attached to: %q", held)
	}
}

func TestWakeFailures(t *testing.T) {
	t.Run("unknown session", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t)
		if err := f.h.Wake(context.Background(), wakeUUID, (&holder{}).hold); codeOf(err) != protocol.CodeSessionNotFound {
			t.Errorf("error = %v, want %s", err, protocol.CodeSessionNotFound)
		}
	})
	t.Run("working directory gone", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t, session{"stp00001", wakeUUID, filepath.Join(t.TempDir(), "gone"), false})
		hd := &holder{}
		if err := f.h.Wake(context.Background(), wakeUUID, hd.hold); codeOf(err) != protocol.CodeDirNotFound {
			t.Errorf("error = %v, want %s", err, protocol.CodeDirNotFound)
		}
		if held, _ := hd.state(); len(held) != 0 {
			t.Errorf("attached although the directory is gone: %q", held)
		}
	})
	t.Run("attach fails", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t, session{"stp00001", wakeUUID, t.TempDir(), false})
		if err := f.h.Wake(context.Background(), wakeUUID, (&holder{err: errors.New("pty")}).hold); codeOf(err) != protocol.CodeInternal {
			t.Errorf("error = %v, want %s", err, protocol.CodeInternal)
		}
	})
	t.Run("never comes back", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t, session{"stp00001", wakeUUID, t.TempDir(), false})
		h := lifecycle.New(lifecycle.Options{Source: f.src, WakeTimeout: 200 * time.Millisecond, PollInterval: 20 * time.Millisecond})
		hd := &holder{}
		if err := h.Wake(context.Background(), wakeUUID, hd.hold); codeOf(err) != protocol.CodeTimeout {
			t.Errorf("error = %v, want %s", err, protocol.CodeTimeout)
		}
		if _, released := hd.state(); released != 1 {
			t.Errorf("released %d holds, want 1", released)
		}
	})
}

func request(method protocol.Method, body string) protocol.Envelope {
	return protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-1", Method: method, Machine: "m-1", Client: "c-1", Body: json.RawMessage(body)}
}

func TestHandle(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	f.setAgents(t, session{"run00001", "uuid-run", "/w/a", true}, session{"stp00001", "uuid-stp", "/w/b", false})
	work := t.TempDir()
	ctx := context.Background()

	createBody, _ := json.Marshal(protocol.SessionCreateRequest{Cwd: work, Prompt: "hi"})
	cases := []struct {
		name     string
		req      protocol.Envelope
		wantBody string
		wantCode protocol.ErrorCode
	}{
		{"create", request(protocol.MethodSessionCreate, string(createBody)), `{"sessionId":"f00dcafe"}`, ""},
		{"create missing dir", request(protocol.MethodSessionCreate, `{"profileId":"default","cwd":"/nonexistent/clauderc"}`), "", protocol.CodeDirNotFound},
		{"create bad body", request(protocol.MethodSessionCreate, `[]`), "", protocol.CodeInvalidParams},
		{"stop", request(protocol.MethodSessionStop, `{"sessionId":"run00001"}`), `{}`, ""},
		{"stop unknown", request(protocol.MethodSessionStop, `{"sessionId":"nope0001"}`), "", protocol.CodeSessionNotFound},
		{"remove running", request(protocol.MethodSessionRemove, `{"sessionId":"run00001"}`), "", protocol.CodeSessionRunning},
		{"remove", request(protocol.MethodSessionRemove, `{"sessionId":"stp00001"}`), `{}`, ""},
		{"unknown method", request(protocol.MethodChatSend, `{}`), "", protocol.CodeUnknownMethod},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			res := f.h.Handle(ctx, c.req)
			if res.Kind != protocol.KindResponse || res.ID != c.req.ID || res.Method != c.req.Method || res.Client != c.req.Client {
				t.Errorf("response header %+v does not answer %+v", res, c.req)
			}
			if c.wantCode != "" {
				if res.Error == nil || res.Error.Code != c.wantCode {
					t.Errorf("error = %+v, want code %s", res.Error, c.wantCode)
				}
				return
			}
			if res.Error != nil {
				t.Fatalf("error = %+v", res.Error)
			}
			if string(res.Body) != c.wantBody {
				t.Errorf("body = %s, want %s", res.Body, c.wantBody)
			}
		})
	}
}
