package gitops

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

// isolate points git at an empty global config holding only a test
// identity, and at no system config, so signing, hooks or aliases of the
// machine running the tests stay out of them.
func isolate(t *testing.T) {
	t.Helper()
	cfg := filepath.Join(t.TempDir(), "gitconfig")
	body := "[user]\n\tname = Tester\n\temail = tester@example.com\n[init]\n\tdefaultBranch = main\n"
	if err := os.WriteFile(cfg, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("GIT_CONFIG_GLOBAL", cfg)
	t.Setenv("GIT_CONFIG_NOSYSTEM", "1")
}

// git runs the real git in dir for test setup and returns its trimmed
// output, failing the test on error.
func git(t *testing.T, dir string, args ...string) string {
	t.Helper()
	cmd := exec.Command("git", append([]string{"-C", dir}, args...)...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("git %s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return strings.TrimSpace(string(out))
}

// newRepo returns a fresh repository on branch main.
func newRepo(t *testing.T) string {
	t.Helper()
	isolate(t)
	dir := t.TempDir()
	git(t, dir, "init", "-q", "-b", "main")
	return dir
}

func writeFile(t *testing.T, path, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

// commitFile writes name in dir, commits it with msg and returns the hash.
func commitFile(t *testing.T, dir, name, body, msg string) string {
	t.Helper()
	writeFile(t, filepath.Join(dir, name), body)
	git(t, dir, "add", "--", name)
	git(t, dir, "commit", "-q", "-m", msg)
	return git(t, dir, "rev-parse", "HEAD")
}

// remoteSetup is a bare remote with two clones, a and b, both on main
// tracking origin/main at one shared commit.
type remoteSetup struct {
	remote, a, b string
}

func newRemote(t *testing.T) remoteSetup {
	t.Helper()
	isolate(t)
	root := t.TempDir()
	s := remoteSetup{remote: filepath.Join(root, "remote.git"), a: filepath.Join(root, "a"), b: filepath.Join(root, "b")}
	git(t, root, "init", "-q", "--bare", "-b", "main", s.remote)
	git(t, root, "clone", "-q", s.remote, s.a)
	commitFile(t, s.a, "base.txt", "base\n", "base")
	git(t, s.a, "push", "-q", "-u", "origin", "main")
	git(t, root, "clone", "-q", s.remote, s.b)
	return s
}

func codeOf(err error) protocol.ErrorCode {
	var e *protocol.Error
	if errors.As(err, &e) {
		return e.Code
	}
	return ""
}

func wantCode(t *testing.T, err error, want protocol.ErrorCode) {
	t.Helper()
	if got := codeOf(err); got != want {
		t.Fatalf("error = %v (code %q), want code %q", err, got, want)
	}
}
