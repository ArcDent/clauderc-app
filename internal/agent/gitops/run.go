// Package gitops runs git for the phone's git screens (design D9): status,
// branches, the timeline with its lane layout, commit details and diffs,
// the few write operations the spec allows, and dir.busy. git always gets
// its arguments as a list, never through a shell, and can never prompt
// for credentials.
package gitops

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"

	"clauderc/internal/agent/config"
	"clauderc/internal/protocol"
)

// DefaultTimeout bounds every git call (design D9: network operations
// give up after 30 seconds).
const DefaultTimeout = 30 * time.Second

// Git runs git commands.
type Git struct {
	// Path is the git executable; empty means "git" from PATH.
	Path string
	// Timeout defaults to DefaultTimeout.
	Timeout time.Duration
}

// result is a finished git process.
type result struct {
	stdout, stderr []byte
	code           int
}

// Run runs `git -C dir args...` and returns its standard output. A failure
// is a *protocol.Error: CodeTimeout, CodeNotGitRepo, CodeGitCredentialsMissing,
// or CodeGitFailed carrying git's raw error output.
func (g Git) Run(ctx context.Context, dir string, args ...string) ([]byte, error) {
	r, err := g.exec(ctx, dir, args...)
	if err != nil {
		return nil, err
	}
	if r.code != 0 {
		return nil, failure(dir, r)
	}
	return r.stdout, nil
}

// exec runs git and reports a non-zero exit in result.code; err is only
// for git not starting or not finishing in time.
func (g Git) exec(ctx context.Context, dir string, args ...string) (result, error) {
	timeout := g.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	path := g.Path
	if path == "" {
		path = "git"
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, path, append([]string{"-C", dir}, args...)...)
	cmd.Env = environ()
	// git may leave ssh or a credential helper behind; kill the group.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error { return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }
	cmd.WaitDelay = time.Second
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return result{}, &protocol.Error{Code: protocol.CodeTimeout, Message: fmt.Sprintf("git %s 超过 %d 秒未完成", subcommand(args), int(timeout.Seconds()))}
	}
	if ctxErr := ctx.Err(); ctxErr != nil {
		return result{}, ctxErr
	}
	var exit *exec.ExitError
	if err != nil && !errors.As(err, &exit) {
		return result{}, fmt.Errorf("git %s: %w", subcommand(args), err)
	}
	return result{stdout: stdout.Bytes(), stderr: stderr.Bytes(), code: cmd.ProcessState.ExitCode()}, nil
}

// environ is the agent's environment without the enroll key, made safe
// for unattended git: no terminal or askpass prompts, ssh in batch mode, English messages so
// failures can be recognised, and no optional index locks that would get
// in the way of a claude session using git in the same repository.
func environ() []string {
	ssh := os.Getenv("GIT_SSH_COMMAND")
	if ssh == "" {
		ssh = "ssh"
	}
	return append(config.Environ(),
		"GIT_TERMINAL_PROMPT=0",
		"GIT_ASKPASS=",
		"GIT_SSH_COMMAND="+ssh+" -o BatchMode=yes",
		"GCM_INTERACTIVE=never",
		"GIT_OPTIONAL_LOCKS=0",
		"LC_ALL=C",
	)
}

// subcommand names the git command in messages: the first argument that
// is not an option.
func subcommand(args []string) string {
	for _, a := range args {
		if !strings.HasPrefix(a, "-") {
			return a
		}
	}
	return ""
}

// credentialHints are git and ssh messages meaning credentials would have
// to be typed in, which the environment forbids.
var credentialHints = []string{
	"terminal prompts disabled",
	"could not read Username",
	"could not read Password",
	"Authentication failed",
	"Permission denied (publickey",
	"Host key verification failed",
}

// failure turns a non-zero git exit into a protocol error.
func failure(dir string, r result) error {
	msg := strings.TrimSpace(string(r.stderr))
	if msg == "" {
		msg = strings.TrimSpace(string(r.stdout))
	}
	if strings.Contains(msg, "not a git repository") {
		return &protocol.Error{Code: protocol.CodeNotGitRepo, Message: dir + " 不是 git 仓库"}
	}
	for _, h := range credentialHints {
		if strings.Contains(msg, h) {
			return &protocol.Error{Code: protocol.CodeGitCredentialsMissing, Message: msg}
		}
	}
	return &protocol.Error{Code: protocol.CodeGitFailed, Message: msg}
}
