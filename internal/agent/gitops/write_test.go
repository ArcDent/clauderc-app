package gitops

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

func TestStageUnstageCommit(t *testing.T) {
	dir := newRepo(t)
	ctx := context.Background()
	g := Git{}

	_, err := g.Commit(ctx, dir, "nothing yet")
	wantCode(t, err, protocol.CodeInvalidParams)

	writeFile(t, filepath.Join(dir, "a.txt"), "a\n")
	if err := g.Stage(ctx, dir, "a.txt"); err != nil {
		t.Fatal(err)
	}
	if err := g.Unstage(ctx, dir, "a.txt"); err != nil {
		t.Fatalf("unstage before the first commit: %v", err)
	}
	if st, _ := g.Status(ctx, dir); len(st.Staged) != 0 || len(st.Unstaged) != 1 {
		t.Fatalf("after unstage: %+v", st)
	}
	if err := g.Stage(ctx, dir, "a.txt"); err != nil {
		t.Fatal(err)
	}
	for _, msg := range []string{"", "  \n\t"} {
		_, err := g.Commit(ctx, dir, msg)
		wantCode(t, err, protocol.CodeInvalidParams)
	}
	hash, err := g.Commit(ctx, dir, "修复重连")
	if err != nil {
		t.Fatal(err)
	}
	log, err := g.Log(ctx, dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	if log.Commits[0].Hash != hash || log.Commits[0].Subject != "修复重连" {
		t.Errorf("newest commit = %+v, want %s 修复重连", log.Commits[0], hash)
	}
	if st, _ := g.Status(ctx, dir); len(st.Staged) != 0 {
		t.Errorf("staged after commit: %+v", st.Staged)
	}
	_, err = g.Commit(ctx, dir, "again")
	wantCode(t, err, protocol.CodeInvalidParams)

	// Deletions stage too, and unstaging keeps the working tree.
	if err := os.Remove(filepath.Join(dir, "a.txt")); err != nil {
		t.Fatal(err)
	}
	if err := g.Stage(ctx, dir, "a.txt"); err != nil {
		t.Fatal(err)
	}
	if st, _ := g.Status(ctx, dir); len(st.Staged) != 1 || st.Staged[0].Change != protocol.ChangeDeleted {
		t.Errorf("staged deletion: %+v", st.Staged)
	}
	writeFile(t, filepath.Join(dir, "b.txt"), "b\n")
	if err := g.Stage(ctx, dir, "b.txt"); err != nil {
		t.Fatal(err)
	}
	if err := g.Unstage(ctx, dir, "b.txt"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "b.txt")); err != nil {
		t.Errorf("unstage touched the working tree: %v", err)
	}
	wantCode(t, g.Stage(ctx, dir, ""), protocol.CodeInvalidParams)
}

func TestPull(t *testing.T) {
	s := newRemote(t)
	ctx := context.Background()
	theirs := commitFile(t, s.b, "b.txt", "b\n", "theirs")
	git(t, s.b, "push", "-q")

	if err := (Git{}).Pull(ctx, s.a); err != nil {
		t.Fatal(err)
	}
	if head := git(t, s.a, "rev-parse", "HEAD"); head != theirs {
		t.Errorf("after pull HEAD = %s, want %s", head, theirs)
	}

	commitFile(t, s.b, "b.txt", "b2\n", "theirs again")
	git(t, s.b, "push", "-q")
	ours := commitFile(t, s.a, "a.txt", "a\n", "ours")
	err := Git{}.Pull(ctx, s.a)
	wantCode(t, err, protocol.CodeGitFailed)
	if !strings.Contains(err.Error(), "fast-forward") {
		t.Errorf("message %q does not say why", err)
	}
	if head := git(t, s.a, "rev-parse", "HEAD"); head != ours {
		t.Errorf("failed pull moved HEAD to %s", head)
	}
}

func TestPushRejected(t *testing.T) {
	s := newRemote(t)
	theirs := commitFile(t, s.b, "b.txt", "b\n", "theirs")
	git(t, s.b, "push", "-q")
	commitFile(t, s.a, "a.txt", "a\n", "ours")

	err := Git{}.Push(context.Background(), s.a, false)
	wantCode(t, err, protocol.CodeGitFailed)
	if !strings.Contains(err.Error(), "rejected") {
		t.Errorf("message %q does not show the rejection", err)
	}
	if tip := git(t, s.remote, "rev-parse", "main"); tip != theirs {
		t.Errorf("remote main = %s, want it untouched at %s", tip, theirs)
	}
}

func TestPushUpstream(t *testing.T) {
	s := newRemote(t)
	ctx := context.Background()
	git(t, s.a, "switch", "-q", "-c", "feat-x")
	tip := commitFile(t, s.a, "f.txt", "f\n", "feature")

	err := Git{}.Push(ctx, s.a, false)
	wantCode(t, err, protocol.CodeNeedsConfirmation)
	if msg := err.(*protocol.Error).Message; msg != "当前分支 feat-x 没有上游，确认后推送到 origin/feat-x 并设为上游" {
		t.Errorf("message = %q", msg)
	}
	if out := git(t, s.remote, "branch", "--list", "feat-x"); out != "" {
		t.Fatalf("pushed before confirmation: %q", out)
	}
	if err := (Git{}).Push(ctx, s.a, true); err != nil {
		t.Fatal(err)
	}
	if up := git(t, s.a, "rev-parse", "--abbrev-ref", "feat-x@{upstream}"); up != "origin/feat-x" {
		t.Errorf("upstream = %q, want origin/feat-x", up)
	}
	if got := git(t, s.remote, "rev-parse", "feat-x"); got != tip {
		t.Errorf("remote feat-x = %s, want %s", got, tip)
	}
	commitFile(t, s.a, "f.txt", "f2\n", "more")
	if err := (Git{}).Push(ctx, s.a, false); err != nil {
		t.Errorf("push with upstream: %v", err)
	}

	git(t, s.a, "switch", "-q", "--detach")
	wantCode(t, Git{}.Push(ctx, s.a, true), protocol.CodeGitFailed)
}

func TestSwitch(t *testing.T) {
	s := newRemote(t)
	ctx := context.Background()
	git(t, s.b, "switch", "-q", "-c", "feat-y")
	commitFile(t, s.b, "y.txt", "y\n", "y")
	git(t, s.b, "push", "-q", "origin", "feat-y")
	git(t, s.a, "fetch", "-q")

	if err := (Git{}).Switch(ctx, s.a, "feat-y"); err != nil {
		t.Fatal(err)
	}
	if cur := git(t, s.a, "branch", "--show-current"); cur != "feat-y" {
		t.Errorf("current branch = %q, want feat-y", cur)
	}
	if up := git(t, s.a, "rev-parse", "--abbrev-ref", "feat-y@{upstream}"); up != "origin/feat-y" {
		t.Errorf("upstream = %q, want origin/feat-y", up)
	}

	// base.txt differs between the branches and has local changes.
	commitFile(t, s.a, "base.txt", "on feat-y\n", "edit base")
	git(t, s.a, "switch", "-q", "main")
	writeFile(t, filepath.Join(s.a, "base.txt"), "local edit\n")
	err := Git{}.Switch(ctx, s.a, "feat-y")
	wantCode(t, err, protocol.CodeGitFailed)
	if !strings.Contains(err.Error(), "base.txt") {
		t.Errorf("message %q does not list the file", err)
	}
	if cur := git(t, s.a, "branch", "--show-current"); cur != "main" {
		t.Errorf("failed switch left branch %q", cur)
	}
	if b, _ := os.ReadFile(filepath.Join(s.a, "base.txt")); string(b) != "local edit\n" {
		t.Errorf("failed switch changed the file to %q", b)
	}

	for _, bad := range []string{"", "-b", "--orphan=x", "a..b", "with space"} {
		wantCode(t, Git{}.Switch(ctx, s.a, bad), protocol.CodeInvalidParams)
	}
}

// waitFile fails the test unless path appears within timeout.
func waitFile(t *testing.T, path string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		if _, err := os.Stat(path); err == nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s did not appear within %v", filepath.Base(path), timeout)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// Two stages sent at once to one repository run one after the other, so
// the second git add does not find index.lock taken.
func TestStagesInOneRepositoryTakeTurns(t *testing.T) {
	dir := newRepo(t)
	commitFile(t, dir, "base.txt", "base\n", "base")
	writeFile(t, filepath.Join(dir, "a.txt"), "a\n")
	writeFile(t, filepath.Join(dir, "b.txt"), "b\n")
	rec := t.TempDir()
	t.Setenv("REC", rec)
	// The stand-in notes when each git add starts and ends, and holds it open a while.
	h := New(Options{Git: Git{Path: fakeGit(t, `if [ "$3" = add ]; then
	echo "start $5" >>"$REC/log"
	sleep 0.3
	git "$@"; rc=$?
	echo "end $5" >>"$REC/log"
	exit $rc
fi
exec git "$@"
`)}})
	var reqs []protocol.Envelope
	for _, name := range []string{"a.txt", "b.txt"} {
		reqs = append(reqs, withBody(t, sampleRequest(t, "git.stage.req.json"), map[string]any{"dir": dir, "path": name}))
	}
	var wg sync.WaitGroup
	for _, req := range reqs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if res := h.Handle(context.Background(), req); res.Error != nil {
				t.Errorf("stage %s: %+v", req.Body, res.Error)
			}
		}()
	}
	wg.Wait()

	raw, err := os.ReadFile(filepath.Join(rec, "log"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	first, _ := strings.CutPrefix(lines[0], "start ")
	if len(lines) != 4 || lines[1] != "end "+first || !strings.HasPrefix(lines[2], "start ") || lines[3] != "end "+strings.TrimPrefix(lines[2], "start ") {
		t.Errorf("git add ran overlapping:\n%s", raw)
	}
	if staged := git(t, dir, "diff", "--cached", "--name-only"); staged != "a.txt\nb.txt" {
		t.Errorf("staged %q, want both files", staged)
	}
}

// A file staged while the pre-commit hook runs waits for the commit to
// finish and stays out of it.
func TestStageWaitsForCommitHook(t *testing.T) {
	dir := newRepo(t)
	commitFile(t, dir, "base.txt", "base\n", "base")
	started := filepath.Join(t.TempDir(), "hook-started")
	hook := "#!/bin/sh\ntouch '" + started + "'\nsleep 1\n"
	if err := os.WriteFile(filepath.Join(dir, ".git", "hooks", "pre-commit"), []byte(hook), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "a.txt"), "a\n")
	git(t, dir, "add", "a.txt")
	h := New(Options{})
	commitReq := withBody(t, sampleRequest(t, "git.commit.req.json"), map[string]any{"dir": dir, "message": "只提交 a"})
	stageReq := withBody(t, sampleRequest(t, "git.stage.req.json"), map[string]any{"dir": dir, "path": "b.txt"})

	committed := make(chan protocol.Envelope, 1)
	go func() { committed <- h.Handle(context.Background(), commitReq) }()
	waitFile(t, started, 10*time.Second)
	writeFile(t, filepath.Join(dir, "b.txt"), "b\n")
	if res := h.Handle(context.Background(), stageReq); res.Error != nil {
		t.Fatalf("stage: %+v", res.Error)
	}
	res := <-committed
	if res.Error != nil {
		t.Fatalf("commit: %+v", res.Error)
	}
	var body protocol.GitCommitResponse
	strictDecode(t, res.Body, &body)
	if files := git(t, dir, "show", "--name-only", "--format=", body.Hash); files != "a.txt" {
		t.Errorf("the commit holds %q, want only a.txt", files)
	}
	if staged := git(t, dir, "diff", "--cached", "--name-only"); staged != "b.txt" {
		t.Errorf("staged after the commit: %q, want b.txt", staged)
	}
}

// A switch running when the connection drops is finished rather than
// killed halfway, and the request returns at once instead of holding up
// the reconnect.
func TestSwitchOutlivesTheConnection(t *testing.T) {
	dir := newRepo(t)
	commitFile(t, dir, "base.txt", "base\n", "base")
	git(t, dir, "branch", "feat-y")
	rec := t.TempDir()
	t.Setenv("REC", rec)
	h := New(Options{Git: Git{Path: fakeGit(t, `if [ "$3" = switch ]; then
	touch "$REC/started"
	sleep 1
	git "$@"; rc=$?
	touch "$REC/done"
	exit $rc
fi
exec git "$@"
`)}})
	req := withBody(t, sampleRequest(t, "git.switch.req.json"), map[string]any{"dir": dir, "branch": "feat-y"})
	ctx, cancel := context.WithCancel(context.Background())
	answered := make(chan struct{})
	go func() {
		defer close(answered)
		h.Handle(ctx, req)
	}()
	waitFile(t, filepath.Join(rec, "started"), 10*time.Second)
	cancel()
	<-answered
	if _, err := os.Stat(filepath.Join(rec, "done")); err == nil {
		t.Error("the request waited for the switch after the connection dropped")
	}
	waitFile(t, filepath.Join(rec, "done"), 10*time.Second)
	if cur := git(t, dir, "branch", "--show-current"); cur != "feat-y" {
		t.Errorf("current branch = %q, want feat-y", cur)
	}
	if _, err := os.Stat(filepath.Join(dir, ".git", "index.lock")); err == nil {
		t.Error("index.lock left behind")
	}
}

// Paths are relative to the repository root, as git status reports them,
// even when the phone opened a subdirectory, and are never wildcards.
func TestPathsFromSubdirectory(t *testing.T) {
	dir := newRepo(t)
	commitFile(t, dir, "top.txt", "1\n", "init")
	sub := filepath.Join(dir, "sub")
	writeFile(t, filepath.Join(sub, "x.txt"), "x\n")
	writeFile(t, filepath.Join(dir, "top.txt"), "2\n")
	writeFile(t, filepath.Join(dir, "*.txt"), "star\n")
	ctx := context.Background()
	g := Git{}

	if err := g.Stage(ctx, sub, "*.txt"); err != nil {
		t.Fatal(err)
	}
	st, err := g.Status(ctx, sub)
	if err != nil {
		t.Fatal(err)
	}
	if len(st.Staged) != 1 || st.Staged[0].Path != "*.txt" {
		t.Errorf("staging \"*.txt\" staged %+v, want only that file", st.Staged)
	}
	if err := g.Stage(ctx, sub, "top.txt"); err != nil {
		t.Fatal(err)
	}
	staged, err := g.Diff(ctx, protocol.GitDiffRequest{Dir: sub, Path: "top.txt", Staged: true})
	if err != nil || !strings.HasSuffix(staged.Patch, "-1\n+2\n") {
		t.Errorf("staged diff from a subdirectory = %+v, %v", staged, err)
	}
	if err := g.Unstage(ctx, sub, "top.txt"); err != nil {
		t.Fatal(err)
	}
	work, err := g.Diff(ctx, protocol.GitDiffRequest{Dir: sub, Path: "top.txt"})
	if err != nil || !strings.HasSuffix(work.Patch, "-1\n+2\n") {
		t.Errorf("working tree diff from a subdirectory = %+v, %v", work, err)
	}
	untracked, err := g.Diff(ctx, protocol.GitDiffRequest{Dir: sub, Path: "sub/x.txt"})
	if err != nil || untracked.Patch != "@@ -0,0 +1 @@\n+x\n" {
		t.Errorf("untracked diff from a subdirectory = %+v, %v", untracked, err)
	}
}
