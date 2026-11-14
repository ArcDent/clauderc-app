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
	logDir    string
	src       discovery.Source
	h         *lifecycle.Handler
}

// newFixture runs a Handler against the fake claude with an empty Claude
// data directory and a template cache in its own temp directory.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{claudeDir: t.TempDir()}
	f.logDir = fakeclaude.Setup(t, f.claudeDir)
	f.src = discovery.Source{
		ClaudePath:   fakeclaude.Path(),
		ClaudeDir:    f.claudeDir,
		ProfilesFile: filepath.Join(t.TempDir(), "profiles.json"),
	}
	f.h = lifecycle.New(lifecycle.Options{Source: f.src, WakeTimeout: 5 * time.Second, PollInterval: 20 * time.Millisecond})
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
	entries := []map[string]any{}
	for _, s := range sessions {
		e := map[string]any{"id": s.id, "sessionId": s.sessionID, "cwd": s.cwd, "kind": "background",
			"startedAt": 1794529871000, "name": "task", "status": "idle", "state": "done", "pid": nil}
		if s.running {
			e["pid"] = 42
		}
		entries = append(entries, e)
	}
	writeJSON(t, filepath.Join(f.claudeDir, "agents.json"), entries)
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

func TestCreateUsesLatestSessionOfProfile(t *testing.T) {
	f := newFixture(t)
	f.job(t, "anyr0001", []string{"--settings", anySettings, "--model", "sonnet"},
		map[string]string{"ANTHROPIC_MODEL": "older"}, "2026-11-12T08:00:00.000Z")
	f.job(t, "anyr0002", []string{"--settings", anySettings, "-n", "prev", "--resume", "8f0c2a5e-0000-4000-8000-000000000001", "--model", "opus", "--name=old"},
		map[string]string{"ANTHROPIC_MODEL": "claude-opus-test", "ANTHROPIC_SMALL_FAST_MODEL": "haiku-test"}, "2026-11-13T08:00:00.000Z")
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	t.Setenv("FAKE_CLAUDE_BG_ID", "1234abcd")
	work := t.TempDir()

	id, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{
		ProfileID: "anyrouter.top", Cwd: work, Name: "check tests", Prompt: "检查测试是否通过",
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
	wantEqual(t, "args", c.Args, []string{"--settings", anySettings, "--model", "opus", "--bg", "-n", "check tests", "--", "检查测试是否通过"})
	for _, kv := range []string{"ANTHROPIC_MODEL=claude-opus-test", "ANTHROPIC_SMALL_FAST_MODEL=haiku-test"} {
		if !slices.Contains(c.Env, kv) {
			t.Errorf("env %q lacks %s", c.Env, kv)
		}
	}
	if c.Cwd != realDir(t, work) {
		t.Errorf("cwd = %q, want %q", c.Cwd, work)
	}
}

func TestCreateWithoutNameAndPrompt(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku", "--effort", "low"}, nil, "2026-11-13T09:00:00.000Z")

	id, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{ProfileID: "default", Cwd: t.TempDir()})
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
	wantEqual(t, "args", calls[0].Args, []string{"--model", "haiku", "--effort", "low", "--bg"})
}

// A first message that looks like a flag still reaches claude as the prompt.
func TestCreatePromptLikeFlag(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	req := protocol.SessionCreateRequest{ProfileID: "default", Cwd: t.TempDir(), Name: "-x", Prompt: "--dangerously-skip-permissions"}
	if _, err := f.h.Create(context.Background(), req); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, []string{"--model", "haiku", "--bg", "-n", "-x", "--", "--dangerously-skip-permissions"})
}

// A profile whose sessions were all removed is created from profiles.json.
func TestCreateFromTemplateCache(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	settings := `{"env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"}}`
	writeJSON(t, f.src.ProfilesFile, map[string]any{"profiles": map[string]any{
		"api.anthropic.com": map[string]any{
			"profile":     protocol.Profile{ID: "api.anthropic.com", Kind: protocol.ProfileEndpoint},
			"flags":       []string{"--settings", settings, "--model", "opus"},
			"providerEnv": map[string]string{"ANTHROPIC_MODEL": "cached-model"},
			"updatedAt":   "2026-11-10T00:00:00Z",
		},
	}})

	if _, err := f.h.Create(context.Background(), protocol.SessionCreateRequest{ProfileID: "api.anthropic.com", Cwd: t.TempDir(), Name: "n"}); err != nil {
		t.Fatal(err)
	}
	calls := f.calls(t)
	if len(calls) != 1 {
		t.Fatalf("claude ran %d times, want once", len(calls))
	}
	wantEqual(t, "args", calls[0].Args, []string{"--settings", settings, "--model", "opus", "--bg", "-n", "n"})
	if !slices.Contains(calls[0].Env, "ANTHROPIC_MODEL=cached-model") {
		t.Errorf("env %q lacks the cached providerEnv", calls[0].Env)
	}
}

func TestCreateRefusedBeforeClaude(t *testing.T) {
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	f.job(t, "bad00001", nil, nil, "2026-11-13T09:00:00.000Z") // no respawnFlags: "other"
	work := t.TempDir()
	file := filepath.Join(work, "file")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name string
		req  protocol.SessionCreateRequest
		want protocol.ErrorCode
	}{
		{"missing dir", protocol.SessionCreateRequest{ProfileID: "default", Cwd: filepath.Join(work, "nope")}, protocol.CodeDirNotFound},
		{"file", protocol.SessionCreateRequest{ProfileID: "default", Cwd: file}, protocol.CodeDirNotFound},
		{"relative dir", protocol.SessionCreateRequest{ProfileID: "default", Cwd: "code"}, protocol.CodeInvalidParams},
		{"other profile", protocol.SessionCreateRequest{ProfileID: "other", Cwd: work}, protocol.CodeInvalidParams},
		{"unknown profile", protocol.SessionCreateRequest{ProfileID: "example.com", Cwd: work}, protocol.CodeInvalidParams},
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
			h := lifecycle.New(lifecycle.Options{Source: src, Logger: slog.New(slog.NewTextHandler(&logged, nil))})
			_, err := h.Create(context.Background(), protocol.SessionCreateRequest{ProfileID: "default", Cwd: t.TempDir()})
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

// The enroll key never reaches claude, nor what a session runs.
func TestClaudeGetsNoEnrollKey(t *testing.T) {
	t.Setenv("CLAUDERC_ENROLL_KEY", "enroll-secret")
	f := newFixture(t)
	f.job(t, "dflt0001", []string{"--model", "haiku"}, nil, "2026-11-13T09:00:00.000Z")
	envFile := filepath.Join(t.TempDir(), "env")
	src := f.src
	src.ClaudePath = writeScript(t, "env >"+envFile+"\necho 'backgrounded · 1234abcd · x'\n")
	if _, err := lifecycle.New(lifecycle.Options{Source: src}).Create(context.Background(), protocol.SessionCreateRequest{ProfileID: "default", Cwd: t.TempDir()}); err != nil {
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
	h := lifecycle.New(lifecycle.Options{Source: src})
	id, err := h.Create(context.Background(), protocol.SessionCreateRequest{ProfileID: "default", Cwd: t.TempDir()})
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

const wakeUUID = "8f0c2a5e-0000-4000-8000-000000000042"

func TestWake(t *testing.T) {
	f := newFixture(t)
	work := t.TempDir()
	f.setAgents(t, session{"stp00001", wakeUUID, work, false})
	f.job(t, "stp00001", []string{"--settings", anySettings, "--model", "opus"},
		map[string]string{"ANTHROPIC_MODEL": "claude-opus-test"}, "2026-11-13T08:00:00.000Z")

	done := make(chan error, 1)
	go func() { done <- f.h.Wake(context.Background(), wakeUUID) }()

	// Once the resume call is recorded, the CLI lists the session as running.
	deadline := time.Now().Add(5 * time.Second)
	for len(f.launches(t)) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("no resume call within 5 seconds")
		}
		select {
		case err := <-done:
			t.Fatalf("Wake returned %v before resuming", err)
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

	launches := f.launches(t)
	if len(launches) != 1 {
		t.Fatalf("calls besides agents = %+v, want one resume", launches)
	}
	c := launches[0]
	wantEqual(t, "args", c.Args, []string{"--bg", "--resume", wakeUUID})
	if c.Cwd != realDir(t, work) {
		t.Errorf("cwd = %q, want %q", c.Cwd, work)
	}
	if !slices.Contains(c.Env, "ANTHROPIC_MODEL=claude-opus-test") {
		t.Errorf("env %q lacks the session's providerEnv", c.Env)
	}
}

func TestWakeRunningSession(t *testing.T) {
	f := newFixture(t)
	f.setAgents(t, session{"run00001", wakeUUID, t.TempDir(), true})
	if err := f.h.Wake(context.Background(), wakeUUID); err != nil {
		t.Fatal(err)
	}
	if launches := f.launches(t); len(launches) != 0 {
		t.Errorf("a running session was resumed: %+v", launches)
	}
}

func TestWakeFailures(t *testing.T) {
	t.Run("unknown session", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t)
		if err := f.h.Wake(context.Background(), wakeUUID); codeOf(err) != protocol.CodeSessionNotFound {
			t.Errorf("error = %v, want %s", err, protocol.CodeSessionNotFound)
		}
	})
	t.Run("working directory gone", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t, session{"stp00001", wakeUUID, filepath.Join(t.TempDir(), "gone"), false})
		if err := f.h.Wake(context.Background(), wakeUUID); codeOf(err) != protocol.CodeDirNotFound {
			t.Errorf("error = %v, want %s", err, protocol.CodeDirNotFound)
		}
		if launches := f.launches(t); len(launches) != 0 {
			t.Errorf("claude was called: %+v", launches)
		}
	})
	t.Run("never comes back", func(t *testing.T) {
		f := newFixture(t)
		f.setAgents(t, session{"stp00001", wakeUUID, t.TempDir(), false})
		h := lifecycle.New(lifecycle.Options{Source: f.src, WakeTimeout: 200 * time.Millisecond, PollInterval: 20 * time.Millisecond})
		if err := h.Wake(context.Background(), wakeUUID); codeOf(err) != protocol.CodeTimeout {
			t.Errorf("error = %v, want %s", err, protocol.CodeTimeout)
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

	createBody, _ := json.Marshal(protocol.SessionCreateRequest{ProfileID: "default", Cwd: work, Prompt: "hi"})
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
