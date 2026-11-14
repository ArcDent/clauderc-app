package gitops

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

func lines(prefix string, n int) string {
	var b strings.Builder
	for i := 0; i < n; i++ {
		b.WriteString(prefix)
		b.WriteString(strings.Repeat("x", i%7))
		b.WriteString("\n")
	}
	return b.String()
}

func TestShow(t *testing.T) {
	dir := newRepo(t)
	writeFile(t, filepath.Join(dir, "code.go"), "a\nb\nc\n")
	writeFile(t, filepath.Join(dir, "docs", "server.md"), lines("doc ", 20))
	writeFile(t, filepath.Join(dir, "logo.png"), "\x89PNG\x00\x01\x02")
	git(t, dir, "add", ".")
	git(t, dir, "commit", "-q", "-m", "init")
	parent := git(t, dir, "rev-parse", "HEAD")

	writeFile(t, filepath.Join(dir, "code.go"), "a\nB\nc\nd\ne\n")
	git(t, dir, "mv", "docs/server.md", "docs/relay.md")
	writeFile(t, filepath.Join(dir, "docs", "relay.md"), "changed\n"+strings.SplitN(lines("doc ", 20), "\n", 2)[1])
	writeFile(t, filepath.Join(dir, "logo.png"), "\x89PNG\x00\x03")
	git(t, dir, "add", "-A")
	git(t, dir, "commit", "-q", "-m", "修复重连\n\n断线后按逐步拉长的间隔重连。")
	hash := git(t, dir, "rev-parse", "HEAD")

	got, err := Git{}.Show(context.Background(), dir, hash[:7])
	if err != nil {
		t.Fatal(err)
	}
	if got.Hash != hash || !reflect.DeepEqual(got.Parents, []string{parent}) || got.Author != "Tester" {
		t.Errorf("header = %s %v %q", got.Hash, got.Parents, got.Author)
	}
	if got.Message != "修复重连\n\n断线后按逐步拉长的间隔重连。" {
		t.Errorf("message = %q", got.Message)
	}
	sort.Slice(got.Files, func(i, j int) bool { return got.Files[i].Path < got.Files[j].Path })
	want := []protocol.CommitFile{
		{Path: "code.go", Added: 3, Deleted: 1},
		{Path: "docs/relay.md", OldPath: "docs/server.md", Added: 1, Deleted: 1},
		{Path: "logo.png", Binary: true},
	}
	if !reflect.DeepEqual(got.Files, want) {
		t.Errorf("files = %+v\nwant %+v", got.Files, want)
	}

	root, err := Git{}.Show(context.Background(), dir, parent)
	if err != nil {
		t.Fatal(err)
	}
	if len(root.Parents) != 0 || root.Parents == nil || len(root.Files) != 3 {
		t.Errorf("root commit: parents %v, %d files; want none and 3", root.Parents, len(root.Files))
	}
}

func TestDiff(t *testing.T) {
	dir := newRepo(t)
	writeFile(t, filepath.Join(dir, "a.txt"), "one\ntwo\n")
	writeFile(t, filepath.Join(dir, "img.bin"), "\x00\x01")
	git(t, dir, "add", ".")
	git(t, dir, "commit", "-q", "-m", "init")
	writeFile(t, filepath.Join(dir, "a.txt"), "one\n2\n")
	writeFile(t, filepath.Join(dir, "img.bin"), "\x00\x02")
	writeFile(t, filepath.Join(dir, "big.txt"), lines("line ", 1200))
	git(t, dir, "add", ".")
	git(t, dir, "commit", "-q", "-m", "change")
	hash := git(t, dir, "rev-parse", "HEAD")
	writeFile(t, filepath.Join(dir, "a.txt"), "one\n2\nstaged\n")
	git(t, dir, "add", "a.txt")
	writeFile(t, filepath.Join(dir, "a.txt"), "one\n2\nstaged\nworking\n")
	writeFile(t, filepath.Join(dir, "new.txt"), "fresh\n")
	outside := filepath.Join(filepath.Dir(dir), "outside-"+filepath.Base(dir)+".txt")
	writeFile(t, outside, "secret\n")
	t.Cleanup(func() { os.Remove(outside) })

	ctx := context.Background()
	diff := func(req protocol.GitDiffRequest) protocol.GitDiffResponse {
		t.Helper()
		req.Dir = dir
		res, err := Git{}.Diff(ctx, req)
		if err != nil {
			t.Fatalf("diff %+v: %v", req, err)
		}
		return res
	}

	text := diff(protocol.GitDiffRequest{Path: "a.txt", Hash: hash})
	if text.Binary || text.Collapsed || text.Patch != "@@ -1,2 +1,2 @@\n one\n-two\n+2\n" {
		t.Errorf("commit diff = %+v", text)
	}
	if bin := diff(protocol.GitDiffRequest{Path: "img.bin", Hash: hash}); !bin.Binary || bin.Patch != "" {
		t.Errorf("binary diff = %+v, want flagged without patch", bin)
	}
	big := diff(protocol.GitDiffRequest{Path: "big.txt", Hash: hash})
	if !big.Collapsed || strings.Count(big.Patch, "\n+") != 1200 {
		t.Errorf("1200-line diff: collapsed %v with %d added lines; want collapsed and whole", big.Collapsed, strings.Count(big.Patch, "\n+"))
	}
	if staged := diff(protocol.GitDiffRequest{Path: "a.txt", Staged: true}); !strings.HasSuffix(staged.Patch, " 2\n+staged\n") {
		t.Errorf("staged diff = %q", staged.Patch)
	}
	if work := diff(protocol.GitDiffRequest{Path: "a.txt"}); !strings.HasSuffix(work.Patch, " staged\n+working\n") {
		t.Errorf("working tree diff = %q", work.Patch)
	}
	if untracked := diff(protocol.GitDiffRequest{Path: "new.txt"}); untracked.Patch != "@@ -0,0 +1 @@\n+fresh\n" {
		t.Errorf("untracked diff = %q", untracked.Patch)
	}

	for _, p := range []string{"../" + filepath.Base(outside), outside, ""} {
		_, err := Git{}.Diff(ctx, protocol.GitDiffRequest{Dir: dir, Path: p})
		wantCode(t, err, protocol.CodeInvalidParams)
	}
	for _, bad := range []string{"HEAD~1", "--output=/tmp/x", "main"} {
		_, err := Git{}.Diff(ctx, protocol.GitDiffRequest{Dir: dir, Path: "a.txt", Hash: bad})
		wantCode(t, err, protocol.CodeInvalidParams)
		_, err = Git{}.Show(ctx, dir, bad)
		wantCode(t, err, protocol.CodeInvalidParams)
	}
}
