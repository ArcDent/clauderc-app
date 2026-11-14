package fakeclaude_test

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/fakeclaude"
)

// run executes the fake claude in dir with extra environment, giving up
// after 3 seconds so a hanging script fails the test instead of the suite.
func run(t *testing.T, dir string, env []string, args ...string) (string, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, fakeclaude.Path(), args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), env...)
	out, err := cmd.Output()
	return string(out), err
}

// exitCode returns the process exit code, or -1 when it was killed or did
// not run.
func exitCode(err error) int {
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return ee.ExitCode()
	}
	if err == nil {
		return 0
	}
	return -1
}

func TestFakeClaude(t *testing.T) {
	sample := t.TempDir()
	work := t.TempDir()
	logDir := fakeclaude.Setup(t, sample)
	t.Setenv("FAKE_CLAUDE_BG_ID", "f00d0001")
	agentsJSON := `[{"id":"abc12345","kind":"background","pid":42}]` + "\n"
	if err := os.WriteFile(filepath.Join(sample, "agents.json"), []byte(agentsJSON), 0o600); err != nil {
		t.Fatal(err)
	}

	out, err := run(t, work, nil, "agents", "--json", "--all")
	if err != nil || out != agentsJSON {
		t.Fatalf("agents: out %q err %v", out, err)
	}

	prompt := "first line\nsecond line"
	bgArgs := []string{"--settings", `{"env":{"X":"a b"}}`, "--bg", "-n", "my task", prompt}
	out, err = run(t, work, []string{"ANTHROPIC_MODEL=claude-opus-5-5"}, bgArgs...)
	if err != nil || out != "backgrounded · f00d0001 · my task\n" {
		t.Fatalf("--bg: out %q err %v", out, err)
	}

	for _, sub := range []string{"stop", "rm"} {
		if out, err := run(t, work, nil, sub, "abc12345"); err != nil || out != "" {
			t.Fatalf("%s: out %q err %v", sub, out, err)
		}
	}

	if _, err := run(t, work, nil, "frobnicate"); exitCode(err) != 2 {
		t.Fatalf("unknown subcommand: err %v, want exit 2", err)
	}

	realWork, err := filepath.EvalSymlinks(work)
	if err != nil {
		t.Fatal(err)
	}
	calls := fakeclaude.Calls(t, logDir)
	wantArgs := [][]string{
		{"agents", "--json", "--all"},
		bgArgs,
		{"stop", "abc12345"},
		{"rm", "abc12345"},
		{"frobnicate"},
	}
	if len(calls) != len(wantArgs) {
		t.Fatalf("recorded %d calls, want %d", len(calls), len(wantArgs))
	}
	for i, c := range calls {
		if !reflect.DeepEqual(c.Args, wantArgs[i]) {
			t.Errorf("call %d args = %q, want %q", i, c.Args, wantArgs[i])
		}
		if c.Cwd != realWork {
			t.Errorf("call %d cwd = %q, want %q", i, c.Cwd, realWork)
		}
	}
	const model = "ANTHROPIC_MODEL=claude-opus-5-5"
	if !slices.Contains(calls[1].Env, model) {
		t.Errorf("--bg call env lacks ANTHROPIC_MODEL")
	}
	if slices.Contains(calls[0].Env, model) {
		t.Errorf("agents call env has ANTHROPIC_MODEL it was not given")
	}
}

// Only allowlisted variables reach the record, and nothing whose name
// looks like a credential.
func TestFakeClaudeEnvAllowlist(t *testing.T) {
	logDir := fakeclaude.Setup(t, t.TempDir())
	t.Setenv("HOME", "/home/tester")
	env := []string{
		"ANTHROPIC_MODEL=m1",
		"ANTHROPIC_BASE_URL=https://anyrouter.top",
		"CLAUDE_CONFIG_DIR=/cfg",
		"ANTHROPIC_AUTH_TOKEN=tok-1",
		"ANTHROPIC_API_KEY=key-1",
		"CLAUDE_CODE_OAUTH_TOKEN=tok-2",
		"MY_SECRET=s-1",
		"DB_PASSWORD=p-1",
		"UNRELATED_VAR=u-1",
	}
	if _, err := run(t, t.TempDir(), env, "agents"); err != nil {
		t.Fatal(err)
	}
	got := fakeclaude.Calls(t, logDir)[0].Env
	for _, want := range []string{
		"ANTHROPIC_MODEL=m1",
		"ANTHROPIC_BASE_URL=https://anyrouter.top",
		"CLAUDE_CONFIG_DIR=/cfg",
		"HOME=/home/tester",
		"PATH=" + os.Getenv("PATH"),
	} {
		if !slices.Contains(got, want) {
			name, _, _ := strings.Cut(want, "=")
			t.Errorf("env lacks the expected %s", name)
		}
	}
	// Report only names: a failing record may hold real credentials from
	// the developer's environment.
	for _, kv := range got {
		name, value, _ := strings.Cut(kv, "=")
		for _, bad := range []string{"TOKEN", "KEY", "SECRET", "PASSWORD", "UNRELATED"} {
			if strings.Contains(name, bad) {
				t.Errorf("env record has variable %s", name)
			}
		}
		for _, bad := range []string{"tok-", "key-1", "s-1", "p-1"} {
			if strings.Contains(value, bad) {
				t.Errorf("env record leaks a test credential through %s", name)
			}
		}
	}
}

// A log directory that is missing or read-only makes the call fail fast
// instead of looping forever on mkdir.
func TestFakeClaudeBadLogDir(t *testing.T) {
	readOnly := t.TempDir()
	if err := os.Chmod(readOnly, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(readOnly, 0o700) })
	for name, dir := range map[string]string{
		"missing":   filepath.Join(t.TempDir(), "nope"),
		"read-only": readOnly,
	} {
		t.Run(name, func(t *testing.T) {
			t.Setenv("FAKE_CLAUDE_DIR", t.TempDir())
			t.Setenv("FAKE_CLAUDE_LOG", dir)
			_, err := run(t, t.TempDir(), nil, "agents")
			if code := exitCode(err); code <= 0 {
				t.Fatalf("err %v (exit %d), want a non-zero exit", err, code)
			}
		})
	}
}

// Names are printed verbatim, backslashes included.
func TestFakeClaudeBackslashName(t *testing.T) {
	fakeclaude.Setup(t, t.TempDir())
	t.Setenv("FAKE_CLAUDE_BG_ID", "f00d0002")
	name := `a\tb\nc\\d\c`
	out, err := run(t, t.TempDir(), nil, "--bg", "--name", name)
	if err != nil || out != "backgrounded · f00d0002 · "+name+"\n" {
		t.Fatalf("out %q err %v", out, err)
	}
}

// -n or --name without a value is a usage error.
func TestFakeClaudeNameWithoutValue(t *testing.T) {
	fakeclaude.Setup(t, t.TempDir())
	for _, flag := range []string{"-n", "--name"} {
		if out, err := run(t, t.TempDir(), nil, "--bg", flag); exitCode(err) != 2 {
			t.Errorf("%s last: out %q err %v, want exit 2", flag, out, err)
		}
	}
}

// agents.delay in the sample dir delays `agents` by that many seconds; the
// call is recorded before the delay starts.
func TestFakeClaudeAgentsDelay(t *testing.T) {
	sample := t.TempDir()
	fakeclaude.Setup(t, sample)
	if err := os.WriteFile(filepath.Join(sample, "agents.delay"), []byte("1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	out, err := run(t, t.TempDir(), nil, "agents")
	if err != nil || out != "[]\n" {
		t.Fatalf("out %q err %v", out, err)
	}
	if d := time.Since(start); d < time.Second {
		t.Fatalf("agents returned after %v, want at least 1s", d)
	}
}
