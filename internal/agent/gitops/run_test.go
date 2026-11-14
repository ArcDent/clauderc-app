package gitops

import (
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// fakeGit writes a stand-in git script and returns its path.
func fakeGit(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "git")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body), 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestRunEnvironmentAndArgs(t *testing.T) {
	rec := t.TempDir()
	t.Setenv("REC", rec)
	t.Setenv("GIT_ASKPASS", "/usr/local/bin/some-askpass")
	t.Setenv("GIT_SSH_COMMAND", "ssh -i /k/id")
	t.Setenv("CLAUDERC_ENROLL_KEY", "enroll-secret")
	g := Git{Path: fakeGit(t, `for a in "$@"; do printf '%s\000' "$a"; done >"$REC/args"
env >"$REC/env"
`)}
	dir := t.TempDir()
	hostile := []string{"; rm -rf /", "$(touch " + filepath.Join(rec, "pwned") + ")", "`id`", "a b"}
	if _, err := g.Run(context.Background(), dir, append([]string{"commit"}, hostile...)...); err != nil {
		t.Fatal(err)
	}

	raw, err := os.ReadFile(filepath.Join(rec, "args"))
	if err != nil {
		t.Fatal(err)
	}
	args := strings.Split(strings.TrimSuffix(string(raw), "\x00"), "\x00")
	want := append([]string{"-C", dir, "commit"}, hostile...)
	if !slices.Equal(args, want) {
		t.Errorf("args = %q, want %q", args, want)
	}
	if _, err := os.Stat(filepath.Join(rec, "pwned")); err == nil {
		t.Error("an argument was run by a shell")
	}

	raw, err = os.ReadFile(filepath.Join(rec, "env"))
	if err != nil {
		t.Fatal(err)
	}
	env := strings.Split(string(raw), "\n")
	for _, kv := range []string{"GIT_TERMINAL_PROMPT=0", "GIT_ASKPASS=", "GIT_SSH_COMMAND=ssh -i /k/id -o BatchMode=yes"} {
		if !slices.Contains(env, kv) {
			t.Errorf("environment lacks %q", kv)
		}
	}
	if strings.Contains(string(raw), "enroll-secret") {
		t.Error("git got the enroll key")
	}
}

func TestRunTimeout(t *testing.T) {
	// The script's own child keeps the output pipe open, like ssh would.
	g := Git{Path: fakeGit(t, "sleep 30\n"), Timeout: 200 * time.Millisecond}
	start := time.Now()
	_, err := g.Run(context.Background(), t.TempDir(), "push")
	wantCode(t, err, protocol.CodeTimeout)
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("Run took %v after a 200ms timeout", d)
	}
	if !strings.Contains(err.Error(), "git push") {
		t.Errorf("message %q does not name the command", err)
	}
}

func TestFailureKinds(t *testing.T) {
	cases := []struct {
		stderr string
		want   protocol.ErrorCode
	}{
		{"fatal: not a git repository (or any of the parent directories): .git", protocol.CodeNotGitRepo},
		{"fatal: could not read Username for 'https://github.com': terminal prompts disabled", protocol.CodeGitCredentialsMissing},
		{"git@github.com: Permission denied (publickey).\nfatal: Could not read from remote repository.", protocol.CodeGitCredentialsMissing},
		{"Host key verification failed.", protocol.CodeGitCredentialsMissing},
		{"fatal: Not possible to fast-forward, aborting.", protocol.CodeGitFailed},
	}
	for _, c := range cases {
		err := failure("/w", result{stderr: []byte(c.stderr + "\n"), code: 128})
		if got := codeOf(err); got != c.want {
			t.Errorf("%q: code %q, want %q", c.stderr, got, c.want)
		}
		if c.want != protocol.CodeNotGitRepo && err.(*protocol.Error).Message != c.stderr {
			t.Errorf("%q: message %q is not git's raw output", c.stderr, err.(*protocol.Error).Message)
		}
	}
}
