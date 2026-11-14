package gitops

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"clauderc/internal/protocol"
)

func sortFiles(fs []protocol.FileStatus) []protocol.FileStatus {
	sort.Slice(fs, func(i, j int) bool { return fs[i].Path < fs[j].Path })
	return fs
}

func TestStatus(t *testing.T) {
	s := newRemote(t)
	ctx := context.Background()
	// b moves origin/main two commits ahead; a commits once and fetches.
	commitFile(t, s.b, "b1.txt", "1\n", "b1")
	commitFile(t, s.b, "b2.txt", "2\n", "b2")
	git(t, s.b, "push", "-q")
	for _, f := range []string{"mod.txt", "staged.txt", "both.txt", "gone.txt", "old.txt"} {
		writeFile(t, filepath.Join(s.a, f), f+"\n")
	}
	git(t, s.a, "add", ".")
	git(t, s.a, "commit", "-q", "-m", "files")
	git(t, s.a, "fetch", "-q")

	writeFile(t, filepath.Join(s.a, "mod.txt"), "changed\n")
	writeFile(t, filepath.Join(s.a, "staged.txt"), "changed\n")
	writeFile(t, filepath.Join(s.a, "both.txt"), "staged\n")
	writeFile(t, filepath.Join(s.a, "new.txt"), "new\n")
	writeFile(t, filepath.Join(s.a, "dir", "untracked.txt"), "u\n")
	git(t, s.a, "add", "staged.txt", "both.txt", "new.txt")
	writeFile(t, filepath.Join(s.a, "both.txt"), "unstaged too\n")
	if err := os.Remove(filepath.Join(s.a, "gone.txt")); err != nil {
		t.Fatal(err)
	}
	git(t, s.a, "mv", "old.txt", "renamed.txt")

	got, err := Git{}.Status(ctx, s.a)
	if err != nil {
		t.Fatal(err)
	}
	if got.Branch != "main" || got.Upstream != "origin/main" || got.Ahead != 1 || got.Behind != 2 {
		t.Errorf("branch = %q upstream = %q ahead = %d behind = %d, want main origin/main 1 2", got.Branch, got.Upstream, got.Ahead, got.Behind)
	}
	wantStaged := []protocol.FileStatus{
		{Path: "both.txt", Change: protocol.ChangeModified},
		{Path: "new.txt", Change: protocol.ChangeAdded},
		{Path: "renamed.txt", OldPath: "old.txt", Change: protocol.ChangeRenamed},
		{Path: "staged.txt", Change: protocol.ChangeModified},
	}
	wantUnstaged := []protocol.FileStatus{
		{Path: "both.txt", Change: protocol.ChangeModified},
		{Path: "dir/untracked.txt", Change: protocol.ChangeUntracked},
		{Path: "gone.txt", Change: protocol.ChangeDeleted},
		{Path: "mod.txt", Change: protocol.ChangeModified},
	}
	if s := sortFiles(got.Staged); !reflect.DeepEqual(s, wantStaged) {
		t.Errorf("staged = %+v\nwant %+v", s, wantStaged)
	}
	if u := sortFiles(got.Unstaged); !reflect.DeepEqual(u, wantUnstaged) {
		t.Errorf("unstaged = %+v\nwant %+v", u, wantUnstaged)
	}
}

func TestStatusCleanWithoutUpstream(t *testing.T) {
	dir := newRepo(t)
	got, err := Git{}.Status(context.Background(), dir)
	if err != nil {
		t.Fatal(err)
	}
	want := protocol.GitStatusResponse{Branch: "main", Staged: []protocol.FileStatus{}, Unstaged: []protocol.FileStatus{}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("status of a fresh repo = %+v, want %+v", got, want)
	}
	commitFile(t, dir, "a.txt", "a\n", "a")
	git(t, dir, "switch", "-q", "--detach")
	if got, err = (Git{}).Status(context.Background(), dir); err != nil || got.Branch != "" {
		t.Errorf("detached: branch %q, err %v; want empty branch", got.Branch, err)
	}
}

func TestNotGitRepo(t *testing.T) {
	isolate(t)
	dir := t.TempDir()
	ctx := context.Background()
	_, err := Git{}.Status(ctx, dir)
	wantCode(t, err, protocol.CodeNotGitRepo)
	if err.(*protocol.Error).Message != dir+" 不是 git 仓库" {
		t.Errorf("message = %q", err.(*protocol.Error).Message)
	}
	_, err = Git{}.Branches(ctx, dir)
	wantCode(t, err, protocol.CodeNotGitRepo)
	_, err = Git{}.Log(ctx, dir, 0)
	wantCode(t, err, protocol.CodeNotGitRepo)
}

func TestBranches(t *testing.T) {
	s := newRemote(t)
	git(t, s.b, "switch", "-q", "-c", "feat-y")
	git(t, s.b, "push", "-q", "origin", "feat-y")
	git(t, s.a, "branch", "feat-x")
	git(t, s.a, "fetch", "-q")

	got, err := Git{}.Branches(context.Background(), s.a)
	if err != nil {
		t.Fatal(err)
	}
	want := protocol.GitBranchesResponse{
		Current: "main",
		Local:   []string{"feat-x", "main"},
		Remote:  []protocol.RemoteBranch{{Remote: "origin", Name: "feat-y"}, {Remote: "origin", Name: "main"}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("branches = %+v\nwant %+v", got, want)
	}
}
