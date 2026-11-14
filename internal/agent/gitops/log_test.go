package gitops

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

// The protocol sample's layout is the reference the phone draws from.
func TestLayoutMatchesSample(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "protocol", "testdata", "git.log.res.json"))
	if err != nil {
		t.Fatal(err)
	}
	var env struct {
		Body protocol.GitLogResponse `json:"body"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatal(err)
	}
	want := env.Body.Commits
	got := make([]protocol.Commit, len(want))
	for i, c := range want {
		got[i] = protocol.Commit{Hash: c.Hash, Parents: c.Parents}
	}
	layout(got)
	for i := range want {
		if got[i].Lane != want[i].Lane || !reflect.DeepEqual(got[i].Edges, want[i].Edges) {
			t.Errorf("%s: lane %d edges %v, want lane %d edges %v", want[i].Hash, got[i].Lane, got[i].Edges, want[i].Lane, want[i].Edges)
		}
	}
}

// Two branches open from one commit and merge back: each merge node has
// two lines into it, the branches use different lanes, and the main line
// ends in lane 0 again.
func TestLayoutBranches(t *testing.T) {
	c := func(hash string, parents ...string) protocol.Commit {
		return protocol.Commit{Hash: hash, Parents: append([]string{}, parents...)}
	}
	commits := []protocol.Commit{
		c("m2", "m1", "y1"), // merge of y
		c("y1", "base"),
		c("m1", "a1", "x1"), // merge of x
		c("x1", "base"),
		c("a1", "base"),
		c("base"),
	}
	layout(commits)
	type row struct {
		lane  int
		edges []protocol.Edge
	}
	edges := func(p ...int) []protocol.Edge {
		out := []protocol.Edge{}
		for i := 0; i+1 < len(p); i += 2 {
			out = append(out, protocol.Edge{From: p[i], To: p[i+1]})
		}
		return out
	}
	want := []row{
		{0, edges(0, 0, 0, 1)},
		{1, edges(0, 0, 1, 1)},
		{0, edges(0, 0, 0, 2, 1, 1)},
		{2, edges(0, 0, 1, 1, 2, 1)},
		{0, edges(0, 0, 1, 0)}, // base's line comes back to lane 0
		{0, edges()},
	}
	for i, w := range want {
		if commits[i].Lane != w.lane || !reflect.DeepEqual(commits[i].Edges, w.edges) {
			t.Errorf("%s: lane %d edges %v, want lane %d edges %v", commits[i].Hash, commits[i].Lane, commits[i].Edges, w.lane, w.edges)
		}
	}
}

func TestLogMergeRepo(t *testing.T) {
	dir := newRepo(t)
	commitFile(t, dir, "a.txt", "1\n", "init")
	fork := commitFile(t, dir, "a.txt", "2\n", "second")
	git(t, dir, "switch", "-q", "-c", "feat-x")
	feat := commitFile(t, dir, "f.txt", "f\n", "feature")
	git(t, dir, "switch", "-q", "main")
	mainTip := commitFile(t, dir, "a.txt", "3\n", "third")
	git(t, dir, "merge", "-q", "--no-ff", "-m", "Merge branch 'feat-x'", "feat-x")
	git(t, dir, "tag", "-a", "-m", "release", "v0.2.0")
	git(t, dir, "tag", "light", fork)

	res, err := Git{}.Log(context.Background(), dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	if res.HasMore || len(res.Commits) != 5 {
		t.Fatalf("got %d commits, hasMore %v; want 5, false", len(res.Commits), res.HasMore)
	}
	byHash := map[string]protocol.Commit{}
	for _, c := range res.Commits {
		byHash[c.Hash] = c
	}
	merge := res.Commits[0]
	if merge.Subject != "Merge branch 'feat-x'" || !slices.Equal(merge.Parents, []string{mainTip, feat}) {
		t.Fatalf("first commit = %+v, want the merge of %s and %s", merge, mainTip, feat)
	}
	var into []int
	for _, e := range merge.Edges {
		if e.From == merge.Lane {
			into = append(into, e.To)
		}
	}
	if len(into) != 2 || into[0] == into[1] {
		t.Errorf("merge edges %v: want two lines from lane %d to different lanes", merge.Edges, merge.Lane)
	}
	if byHash[feat].Lane == byHash[mainTip].Lane {
		t.Errorf("feature and main commits share lane %d", byHash[feat].Lane)
	}
	wantRefs := map[string][]protocol.Ref{
		merge.Hash: {{Name: "main", Kind: protocol.RefBranch}, {Name: "v0.2.0", Kind: protocol.RefTag}},
		feat:       {{Name: "feat-x", Kind: protocol.RefBranch}},
		fork:       {{Name: "light", Kind: protocol.RefTag}},
	}
	for _, c := range res.Commits {
		if !reflect.DeepEqual(c.Refs, wantRefs[c.Hash]) {
			t.Errorf("%s (%s): refs %v, want %v", c.Hash[:7], c.Subject, c.Refs, wantRefs[c.Hash])
		}
		if c.Author != "Tester" || c.Time.Location().String() != "UTC" {
			t.Errorf("%s: author %q time %v", c.Hash[:7], c.Author, c.Time)
		}
	}
}

func TestLogRemoteBranchRef(t *testing.T) {
	s := newRemote(t)
	res, err := Git{}.Log(context.Background(), s.a, 0)
	if err != nil {
		t.Fatal(err)
	}
	want := []protocol.Ref{{Name: "main", Kind: protocol.RefBranch}, {Name: "origin/main", Kind: protocol.RefRemoteBranch}}
	if len(res.Commits) != 1 || !reflect.DeepEqual(res.Commits[0].Refs, want) {
		t.Errorf("commits = %+v, want one with refs %v", res.Commits, want)
	}
}

// linearRepo makes a repository with n commits "c1" … "cn" on main
// through fast-import, much faster than n git commit runs.
func linearRepo(t *testing.T, n int) string {
	t.Helper()
	dir := newRepo(t)
	var b strings.Builder
	for i := 1; i <= n; i++ {
		msg := fmt.Sprintf("c%d", i)
		fmt.Fprintf(&b, "commit refs/heads/main\ncommitter Tester <tester@example.com> %d +0000\ndata %d\n%s\n\n", 1700000000+i*60, len(msg), msg)
	}
	cmd := exec.Command("git", "-C", dir, "fast-import", "--quiet")
	cmd.Stdin = strings.NewReader(b.String())
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("fast-import: %v\n%s", err, out)
	}
	return dir
}

func TestLogPages(t *testing.T) {
	dir := linearRepo(t, 205)
	ctx := context.Background()
	cases := []struct {
		page    int
		n       int
		first   string
		hasMore bool
	}{
		{0, 100, "c205", true},
		{1, 100, "c105", true}, // the 101st commit
		{2, 5, "c5", false},
		{3, 0, "", false},
	}
	for _, c := range cases {
		res, err := Git{}.Log(ctx, dir, c.page)
		if err != nil {
			t.Fatal(err)
		}
		if len(res.Commits) != c.n || res.HasMore != c.hasMore {
			t.Errorf("page %d: %d commits, hasMore %v; want %d, %v", c.page, len(res.Commits), res.HasMore, c.n, c.hasMore)
			continue
		}
		if c.n > 0 && res.Commits[0].Subject != c.first {
			t.Errorf("page %d starts at %q, want %q", c.page, res.Commits[0].Subject, c.first)
		}
	}
	_, err := Git{}.Log(ctx, dir, -1)
	wantCode(t, err, protocol.CodeInvalidParams)
}

func TestLogEmptyRepo(t *testing.T) {
	res, err := Git{}.Log(context.Background(), newRepo(t), 0)
	if err != nil {
		t.Fatal(err)
	}
	if res.Commits == nil || len(res.Commits) != 0 || res.HasMore {
		t.Errorf("empty repo log = %+v, want no commits", res)
	}
}
