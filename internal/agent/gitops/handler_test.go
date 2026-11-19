package gitops

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/protocol"
)

var _ agent.Handler = (*Handler)(nil)

// setAgents makes the fake claude list one background session per cwd,
// with the given status ("busy" is working).
func setAgents(t *testing.T, claudeDir string, sessions map[string]string) {
	t.Helper()
	var entries []string
	i := 0
	for cwd, status := range sessions {
		i++
		entries = append(entries, fmt.Sprintf(`{"pid": %d, "id": "sess%04d", "cwd": %q, "kind": "background", "startedAt": 1794529871000, "name": "s", "status": %q, "state": "working"}`, i, i, cwd, status))
	}
	body := "[" + strings.Join(entries, ",") + "]"
	if err := os.WriteFile(filepath.Join(claudeDir, "agents.json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestDirBusy(t *testing.T) {
	claudeDir := t.TempDir()
	fakeclaude.Setup(t, claudeDir)
	root := t.TempDir()
	repo := filepath.Join(root, "repo")
	for _, d := range []string{"repo/sub/deeper", "repo-other", "idle"} {
		if err := os.MkdirAll(filepath.Join(root, d), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	setAgents(t, claudeDir, map[string]string{
		filepath.Join(repo, "sub"):  "busy",
		filepath.Join(root, "idle"): "idle",
	})
	h := New(Options{Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: claudeDir}})
	cases := map[string]bool{
		repo:                                 true, // a subdirectory is working
		filepath.Join(repo, "sub"):           true, // the directory itself
		filepath.Join(repo, "sub", "deeper"): false,
		filepath.Join(root, "repo-other"):    false, // shares a name prefix only
		filepath.Join(root, "idle"):          false,
	}
	for dir, want := range cases {
		got, err := h.Busy(context.Background(), dir)
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Errorf("Busy(%s) = %v, want %v", strings.TrimPrefix(dir, root), got, want)
		}
	}
}

// A session working in a git worktree keeps the worktree busy, not the
// checkout it came from.
func TestDirBusyWorktree(t *testing.T) {
	claudeDir := t.TempDir()
	fakeclaude.Setup(t, claudeDir)
	root := t.TempDir()
	repo := filepath.Join(root, "repo")
	wt := filepath.Join(repo, ".claude", "worktrees", "x")
	if err := os.MkdirAll(filepath.Join(wt, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	h := New(Options{Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: claudeDir}})
	check := func(cases map[string]bool) {
		t.Helper()
		for dir, want := range cases {
			got, err := h.Busy(context.Background(), dir)
			if err != nil {
				t.Fatal(err)
			}
			if got != want {
				t.Errorf("Busy(%s) = %v, want %v", strings.TrimPrefix(dir, root), got, want)
			}
		}
	}
	setAgents(t, claudeDir, map[string]string{wt: "busy"})
	check(map[string]bool{
		repo:                      false, // only a worktree session works
		wt:                        true,
		filepath.Join(wt, "sub"):  false,
		filepath.Join(root):       false,
		filepath.Join(repo, ".."): false,
	})
	setAgents(t, claudeDir, map[string]string{wt: "busy", repo: "busy"})
	check(map[string]bool{repo: true, wt: true})
}

func sampleRequest(t *testing.T, name string) protocol.Envelope {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "protocol", "testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	env, err := protocol.Decode(raw)
	if err != nil {
		t.Fatal(err)
	}
	return env
}

// withBody replaces fields of a request body, keeping the rest.
func withBody(t *testing.T, env protocol.Envelope, set map[string]any) protocol.Envelope {
	t.Helper()
	body := map[string]any{}
	if err := json.Unmarshal(env.Body, &body); err != nil {
		t.Fatal(err)
	}
	for k, v := range set {
		body[k] = v
	}
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	env.Body = raw
	return env
}

// strictDecode decodes a response body into v, rejecting unknown fields.
func strictDecode(t *testing.T, body json.RawMessage, v any) {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		t.Fatalf("body %s: %v", body, err)
	}
}

// Every git request sample from internal/protocol/testdata is answered
// against a real repository, in an order where each one succeeds.
func TestHandleSamples(t *testing.T) {
	s := newRemote(t)
	dir := s.a
	git(t, s.b, "switch", "-q", "-c", "feat-y")
	git(t, s.b, "push", "-q", "origin", "feat-y")
	git(t, dir, "fetch", "-q")
	hash := commitFile(t, dir, "internal/agent/conn/conn.go", "package conn\n", "conn")
	writeFile(t, filepath.Join(dir, "internal/protocol/chat.go"), "package protocol\n")
	git(t, dir, "add", "internal/protocol/chat.go")
	writeFile(t, filepath.Join(dir, "notes.txt"), "notes\n")
	claudeDir := t.TempDir()
	fakeclaude.Setup(t, claudeDir)
	h := New(Options{Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: claudeDir}})

	type emptyBody struct{}
	steps := []struct {
		sample string
		set    map[string]any
		into   any
	}{
		{"git.status.req.json", nil, &protocol.GitStatusResponse{}},
		{"git.log.req.json", nil, &protocol.GitLogResponse{}},
		{"git.show.req.json", map[string]any{"hash": hash}, &protocol.GitShowResponse{}},
		{"git.diff.req.json", map[string]any{"hash": hash}, &protocol.GitDiffResponse{}},
		{"git.diff.req.staged.json", nil, &protocol.GitDiffResponse{}},
		{"git.branches.req.json", nil, &protocol.GitBranchesResponse{}},
		{"git.unstage.req.json", nil, &emptyBody{}},
		{"git.stage.req.json", nil, &emptyBody{}},
		{"git.commit.req.json", nil, &protocol.GitCommitResponse{}},
		{"git.pull.req.json", nil, &emptyBody{}},
		{"git.push.req.json", nil, &emptyBody{}},
		{"git.push.req.set-upstream.json", nil, &emptyBody{}},
		{"git.switch.req.json", nil, &emptyBody{}},
		{"dir.busy.req.json", nil, &protocol.DirBusyResponse{}},
	}
	for _, st := range steps {
		set := map[string]any{"dir": dir}
		for k, v := range st.set {
			set[k] = v
		}
		req := withBody(t, sampleRequest(t, st.sample), set)
		res := h.Handle(context.Background(), req)
		if res.Kind != protocol.KindResponse || res.ID != req.ID || res.Method != req.Method || res.Machine != req.Machine || res.Client != req.Client {
			t.Errorf("%s: response header %+v", st.sample, res)
		}
		if res.Error != nil {
			t.Errorf("%s: error %+v", st.sample, res.Error)
			continue
		}
		strictDecode(t, res.Body, st.into)
		if _, err := protocol.Encode(res); err != nil {
			t.Errorf("%s: %v", st.sample, err)
		}
	}
	if cur := git(t, dir, "branch", "--show-current"); cur != "feat-y" {
		t.Errorf("after the switch sample the branch is %q", cur)
	}
	commits := steps[1].into.(*protocol.GitLogResponse).Commits
	if len(commits) != 2 || commits[0].Hash != hash {
		t.Errorf("log sample answered %+v", commits)
	}
	if got := steps[3].into.(*protocol.GitDiffResponse); got.Path != "internal/agent/conn/conn.go" || got.Patch != "@@ -0,0 +1 @@\n+package conn\n" {
		t.Errorf("diff sample answered %+v", got)
	}
}

func TestHandleErrors(t *testing.T) {
	isolate(t)
	plain := t.TempDir()
	h := New(Options{})
	req := func(method protocol.Method, body string) protocol.Envelope {
		return protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-1", Method: method, Client: "c-1", Body: json.RawMessage(body)}
	}
	cases := []struct {
		req  protocol.Envelope
		want protocol.ErrorCode
	}{
		{req(protocol.MethodGitStatus, fmt.Sprintf(`{"dir":%q}`, plain)), protocol.CodeNotGitRepo},
		{req(protocol.MethodGitStatus, `{"dir":"/nonexistent/clauderc"}`), protocol.CodeDirNotFound},
		{req(protocol.MethodGitStatus, `{"dir":"relative"}`), protocol.CodeInvalidParams},
		{req(protocol.MethodGitLog, `{"dir":1}`), protocol.CodeInvalidParams},
		{req(protocol.MethodChatSend, `{}`), protocol.CodeUnknownMethod},
	}
	for _, c := range cases {
		res := h.Handle(context.Background(), c.req)
		if res.Error == nil || res.Error.Code != c.want || res.Method != c.req.Method {
			t.Errorf("%s %s: %+v, want error %s", c.req.Method, c.req.Body, res, c.want)
		}
	}
	if got := h.Methods(); len(got) != 12 {
		t.Errorf("Methods() lists %d methods, want 12", len(got))
	}
}

// A blocked session counts as working unless its transcript shows a tool
// call waiting for the user (design D5).
func TestDirBusyBlocked(t *testing.T) {
	claudeDir := t.TempDir()
	fakeclaude.Setup(t, claudeDir)
	root := t.TempDir()
	waiting, thinking := filepath.Join(root, "waiting"), filepath.Join(root, "thinking")
	const waitingID, thinkingID = "11111111-2222-4333-8444-555555555555", "11111111-2222-4333-8444-666666666666"
	body := fmt.Sprintf(`[
		{"pid": 1, "id": "wait0001", "cwd": %q, "kind": "background", "startedAt": 1794529871000, "sessionId": %q, "name": "w", "status": "busy", "state": "blocked"},
		{"pid": 2, "id": "thnk0001", "cwd": %q, "kind": "background", "startedAt": 1794529871000, "sessionId": %q, "name": "t", "status": "busy", "state": "blocked"}
	]`, waiting, waitingID, thinking, thinkingID)
	if err := os.WriteFile(filepath.Join(claudeDir, "agents.json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	sample, err := os.ReadFile(filepath.Join("..", "transcript", "testdata", "pending-bash.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(claudeDir, "projects", "p"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(claudeDir, "projects", "p", waitingID+".jsonl"), sample, 0o600); err != nil {
		t.Fatal(err)
	}
	h := New(Options{Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: claudeDir}})
	for dir, want := range map[string]bool{waiting: false, thinking: true} {
		got, err := h.Busy(context.Background(), dir)
		if err != nil {
			t.Fatal(err)
		}
		if got != want {
			t.Errorf("Busy(%s) = %v, want %v", filepath.Base(dir), got, want)
		}
	}
}
