package gitops

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

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
