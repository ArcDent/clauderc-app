package sessions_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/agent/sessions"
	"clauderc/internal/protocol"
)

var (
	_ agent.Handler       = (*sessions.Handler)(nil)
	_ agent.ClientTracker = (*sessions.Handler)(nil)
)

type fixture struct {
	sample string
	logDir string
	events chan protocol.Envelope
	h      *sessions.Handler
}

// newFixture runs the handler against the fake claude with a temp sample
// dir and a 1 second poll interval.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{
		sample: t.TempDir(),
		events: make(chan protocol.Envelope, 64),
	}
	f.logDir = fakeclaude.Setup(t, f.sample)
	f.setAgents(t, "idle")
	f.h = sessions.New(sessions.Options{
		Source:   discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: f.sample},
		Push:     agent.PusherFunc(func(e protocol.Envelope) { f.events <- e }),
		Interval: time.Second,
	})
	t.Cleanup(f.h.Close)
	return f
}

// setAgents rewrites the fake claude's agent list with one running
// background session whose status is given.
func (f *fixture) setAgents(t *testing.T, status string) {
	t.Helper()
	body := fmt.Sprintf(`[{"pid": 7, "id": "abc12345", "cwd": "/w/x", "kind": "background", "startedAt": 1794529871000, "name": "task", "status": %q, "state": "working"}]`, status)
	if err := os.WriteFile(filepath.Join(f.sample, "agents.json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func (f *fixture) calls(t *testing.T) int {
	t.Helper()
	return len(fakeclaude.Calls(t, f.logDir))
}

func request(id, client string, method protocol.Method) protocol.Envelope {
	return protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: id, Method: method, Machine: "m-1", Client: client, Body: json.RawMessage(`{}`)}
}

// nextEvents collects sessions.changed events until every wanted client got
// one, failing after 5 seconds.
func (f *fixture) nextEvents(t *testing.T, clients ...string) map[string]protocol.SessionList {
	t.Helper()
	got := map[string]protocol.SessionList{}
	deadline := time.After(5 * time.Second)
	for len(got) < len(clients) {
		select {
		case e := <-f.events:
			if e.Kind != protocol.KindEvent || e.Method != protocol.EventSessionsChanged {
				t.Fatalf("unexpected push %+v", e)
			}
			var list protocol.SessionList
			if err := json.Unmarshal(e.Body, &list); err != nil {
				t.Fatal(err)
			}
			got[e.Client] = list
		case <-deadline:
			t.Fatalf("got events for %v, want %v", got, clients)
		}
	}
	return got
}

func TestSubscribeAndChanges(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()

	res := f.h.Handle(ctx, request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Kind != protocol.KindResponse || res.ID != "r1" || res.Method != protocol.MethodSessionsSubscribe || res.Client != "c1" || res.Error != nil {
		t.Fatalf("subscribe response = %+v", res)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		t.Fatal(err)
	}
	if len(list.Sessions) != 1 || list.Sessions[0].ID != "abc12345" || list.Sessions[0].State != protocol.StateIdle {
		t.Fatalf("subscribe list = %+v", list)
	}
	if res := f.h.Handle(ctx, request("r2", "c2", protocol.MethodSessionsSubscribe)); res.Error != nil {
		t.Fatalf("second subscribe: %+v", res.Error)
	}
	// c3 talks to the agent but never subscribes.
	if res := f.h.Handle(ctx, request("r3", "c3", protocol.MethodDirsRecent)); res.Error != nil {
		t.Fatalf("dirs.recent: %+v", res.Error)
	}

	f.setAgents(t, "busy")
	for c, l := range f.nextEvents(t, "c1", "c2") {
		if c == "c3" {
			t.Fatal("event pushed to unsubscribed c3")
		}
		if len(l.Sessions) != 1 || l.Sessions[0].State != protocol.StateWorking {
			t.Fatalf("event for %s = %+v, want the session working", c, l)
		}
	}

	// After c2's connection is gone only c1 hears about changes.
	f.h.ClientGone("c2")
	f.setAgents(t, "idle")
	for c := range f.nextEvents(t, "c1") {
		if c != "c1" {
			t.Fatalf("event pushed to %s", c)
		}
	}

	res = f.h.Handle(ctx, request("r4", "c1", protocol.MethodSessionsUnsubscribe))
	if res.Error != nil || res.Method != protocol.MethodSessionsUnsubscribe || string(res.Body) != "{}" {
		t.Fatalf("unsubscribe response = %+v body %s", res, res.Body)
	}
	before := f.calls(t)
	time.Sleep(2500 * time.Millisecond)
	if after := f.calls(t); after != before {
		t.Fatalf("claude called %d more times after the last unsubscribe", after-before)
	}
	select {
	case e := <-f.events:
		t.Fatalf("event after unsubscribe: %+v", e)
	default:
	}
}

func TestNoPollingWithoutSubscribers(t *testing.T) {
	f := newFixture(t)
	time.Sleep(1500 * time.Millisecond)
	if n := f.calls(t); n != 0 {
		t.Fatalf("claude called %d times with nobody subscribed", n)
	}
}

func TestDirsRecent(t *testing.T) {
	f := newFixture(t)
	work := t.TempDir()
	job := filepath.Join(f.sample, "jobs", "abc12345")
	if err := os.MkdirAll(job, 0o700); err != nil {
		t.Fatal(err)
	}
	state := fmt.Sprintf(`{"cwd": %q, "updatedAt": "2026-11-12T09:00:00.000Z"}`, work)
	if err := os.WriteFile(filepath.Join(job, "state.json"), []byte(state), 0o600); err != nil {
		t.Fatal(err)
	}
	res := f.h.Handle(context.Background(), request("r9", "c1", protocol.MethodDirsRecent))
	if res.Error != nil || res.Method != protocol.MethodDirsRecent || res.ID != "r9" {
		t.Fatalf("dirs.recent response = %+v", res)
	}
	var body protocol.DirsRecentResponse
	if err := json.Unmarshal(res.Body, &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Dirs) != 1 || body.Dirs[0] != work {
		t.Fatalf("dirs = %q, want [%q]", body.Dirs, work)
	}
}

func TestUnknownMethod(t *testing.T) {
	f := newFixture(t)
	res := f.h.Handle(context.Background(), request("r5", "c1", protocol.MethodChatOpen))
	if res.Kind != protocol.KindResponse || res.Error == nil || res.Error.Code != protocol.CodeUnknownMethod {
		t.Fatalf("response = %+v", res)
	}
}

func TestMethods(t *testing.T) {
	f := newFixture(t)
	want := map[protocol.Method]bool{
		protocol.MethodSessionsSubscribe:   true,
		protocol.MethodSessionsUnsubscribe: true,
		protocol.MethodDirsRecent:          true,
	}
	got := f.h.Methods()
	if len(got) != len(want) {
		t.Fatalf("Methods = %v", got)
	}
	for _, m := range got {
		if !want[m] {
			t.Fatalf("Methods = %v", got)
		}
	}
}

// assertQuiet fails if claude is called or an event is pushed during the
// next 2.5 seconds, i.e. over two poll intervals.
func (f *fixture) assertQuiet(t *testing.T) {
	t.Helper()
	before := f.calls(t)
	time.Sleep(2500 * time.Millisecond)
	if after := f.calls(t); after != before {
		t.Fatalf("claude called %d more times", after-before)
	}
	select {
	case e := <-f.events:
		t.Fatalf("unexpected event %+v", e)
	default:
	}
}

// After the relay connection is lost every phone is gone: nothing is
// pushed any more and polling stops.
func TestAllClientsGone(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	for _, c := range []string{"c1", "c2"} {
		if res := f.h.Handle(ctx, request("r-"+c, c, protocol.MethodSessionsSubscribe)); res.Error != nil {
			t.Fatalf("subscribe %s: %+v", c, res.Error)
		}
	}
	f.h.AllClientsGone()
	f.setAgents(t, "busy")
	f.assertQuiet(t)

	// A phone that subscribes again starts polling afresh.
	if res := f.h.Handle(ctx, request("r9", "c3", protocol.MethodSessionsSubscribe)); res.Error != nil {
		t.Fatalf("subscribe after reset: %+v", res.Error)
	}
	f.setAgents(t, "idle")
	f.nextEvents(t, "c3")
}

// Unsubscribing cancels a claude call in flight instead of waiting for it.
func TestUnsubscribeCancelsSlowPoll(t *testing.T) {
	f := newFixture(t)
	if res := f.h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe)); res.Error != nil {
		t.Fatalf("subscribe: %+v", res.Error)
	}
	if err := os.WriteFile(filepath.Join(f.sample, "agents.delay"), []byte("5\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for f.calls(t) < 2 {
		if time.Now().After(deadline) {
			t.Fatal("the poller never called claude")
		}
		time.Sleep(50 * time.Millisecond)
	}
	time.Sleep(100 * time.Millisecond) // the poll is now inside the delay

	done := make(chan struct{})
	start := time.Now()
	go func() {
		f.h.ClientGone("c1")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("ClientGone still blocked on the slow claude call after 2s")
	}
	if d := time.Since(start); d > 1500*time.Millisecond {
		t.Fatalf("ClientGone took %v", d)
	}
}

// Close is final: a later subscribe is refused and starts no polling.
func TestSubscribeAfterClose(t *testing.T) {
	f := newFixture(t)
	f.h.Close()
	res := f.h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Kind != protocol.KindResponse || res.ID != "r1" || res.Error == nil {
		t.Fatalf("subscribe after Close = %+v, want an error response", res)
	}
	if n := f.calls(t); n != 0 {
		t.Fatalf("claude called %d times after Close", n)
	}
	f.setAgents(t, "busy")
	f.assertQuiet(t)
}

// A blocked session is pending only while its transcript has an unanswered
// tool call; otherwise it is idle (design D5).
func TestBlockedWithoutToolCallIsIdle(t *testing.T) {
	f := newFixture(t)
	agents := `[
  {"pid": 7, "id": "blk00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000a1", "name": "asks", "status": "waiting", "state": "blocked"},
  {"pid": 8, "id": "blk00002", "cwd": "/w/b", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000a2", "name": "answered", "status": "idle", "state": "blocked"},
  {"pid": 9, "id": "blk00003", "cwd": "/w/c", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000a3", "name": "no transcript", "status": "idle", "state": "blocked"}
]`
	if err := os.WriteFile(filepath.Join(f.sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	proj := filepath.Join(f.sample, "projects", "-w-a")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	for id, sample := range map[string]string{
		"8f0c2a5e-0000-4000-8000-0000000000a1": "pending-bash.jsonl",
		"8f0c2a5e-0000-4000-8000-0000000000a2": "answered-bash.jsonl",
	} {
		raw, err := os.ReadFile(filepath.Join("..", "transcript", "testdata", sample))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(proj, id+".jsonl"), raw, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	res := f.h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Error != nil {
		t.Fatalf("subscribe: %+v", res.Error)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		t.Fatal(err)
	}
	want := map[string]protocol.SessionState{
		"blk00001": protocol.StatePending,
		"blk00002": protocol.StateIdle,
		"blk00003": protocol.StateIdle,
	}
	if len(list.Sessions) != len(want) {
		t.Fatalf("sessions = %+v", list.Sessions)
	}
	for _, s := range list.Sessions {
		if s.State != want[s.ID] {
			t.Errorf("%s state %q, want %q", s.ID, s.State, want[s.ID])
		}
	}
}

// A blocked session with nothing pending in its transcript is pending when
// its screen shows a dialog, and only then; the screen is only checked for
// such sessions.
func TestBlockedWithDialogIsPending(t *testing.T) {
	sample := t.TempDir()
	fakeclaude.Setup(t, sample)
	agents := `[
  {"pid": 7, "id": "dlg00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b1", "name": "mcp", "status": "waiting", "state": "blocked"},
  {"pid": 8, "id": "blk00002", "cwd": "/w/b", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b2", "name": "plain", "status": "idle", "state": "blocked"},
  {"pid": 9, "id": "wrk00003", "cwd": "/w/c", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b3", "name": "busy", "status": "busy", "state": "working"}
]`
	if err := os.WriteFile(filepath.Join(sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var asked []string
	h := sessions.New(sessions.Options{
		Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: sample},
		Push:   agent.PusherFunc(func(protocol.Envelope) {}),
		Dialog: func(_ context.Context, id string) bool {
			mu.Lock()
			defer mu.Unlock()
			asked = append(asked, id)
			return id == "dlg00001"
		},
	})
	t.Cleanup(h.Close)
	res := h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Error != nil {
		t.Fatalf("subscribe: %+v", res.Error)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		t.Fatal(err)
	}
	want := map[string]protocol.SessionState{
		"dlg00001": protocol.StatePending,
		"blk00002": protocol.StateIdle,
		"wrk00003": protocol.StateWorking,
	}
	for _, s := range list.Sessions {
		if s.State != want[s.ID] {
			t.Errorf("%s state %q, want %q", s.ID, s.State, want[s.ID])
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if strings.Join(asked, ",") != "dlg00001,blk00002" {
		t.Errorf("screens checked for %v, want the two blocked sessions", asked)
	}
}

// Design D5 in the list: blocked without an unanswered tool call falls
// through to the status, so a busy one is working.
func TestStateFollowsDesignD5(t *testing.T) {
	f := newFixture(t)
	agents := `[
  {"pid": 7, "id": "blk00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b1", "name": "asks", "status": "busy", "state": "blocked"},
  {"pid": 8, "id": "blk00002", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b2", "name": "busy", "status": "busy", "state": "blocked"},
  {"pid": 9, "id": "blk00003", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b3", "name": "idle", "status": "idle", "state": "blocked"},
  {"pid": 10, "id": "blk00004", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b4", "name": "waiting", "status": "waiting", "state": "blocked"},
  {"pid": null, "id": "stp00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000b5", "name": "stopped", "status": "busy", "state": "blocked"}
]`
	if err := os.WriteFile(filepath.Join(f.sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	proj := filepath.Join(f.sample, "projects", "-w-a")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	for id, sample := range map[string]string{
		"8f0c2a5e-0000-4000-8000-0000000000b1": "pending-bash.jsonl",
		"8f0c2a5e-0000-4000-8000-0000000000b2": "answered-bash.jsonl",
		"8f0c2a5e-0000-4000-8000-0000000000b5": "pending-bash.jsonl",
	} {
		raw, err := os.ReadFile(filepath.Join("..", "transcript", "testdata", sample))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(proj, id+".jsonl"), raw, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	res := f.h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Error != nil {
		t.Fatalf("subscribe: %+v", res.Error)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		t.Fatal(err)
	}
	want := map[string]protocol.SessionState{
		"blk00001": protocol.StatePending,
		"blk00002": protocol.StateWorking,
		"blk00003": protocol.StateIdle,
		"blk00004": protocol.StateIdle,
		"stp00001": protocol.StateStopped,
	}
	if len(list.Sessions) != len(want) {
		t.Fatalf("sessions = %+v", list.Sessions)
	}
	for _, s := range list.Sessions {
		if s.State != want[s.ID] {
			t.Errorf("%s state %q, want %q", s.ID, s.State, want[s.ID])
		}
	}
}

// A running session lists its subagents that still run; a stopped one none,
// though the sample's meta files never say finished. The sample is the
// sanitized real transcript of transcript/testdata/agents.jsonl.
func TestRunningSubagents(t *testing.T) {
	f := newFixture(t)
	agents := `[
  {"pid": 7, "id": "run00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000c1", "name": "run", "status": "busy", "state": "working"},
  {"pid": null, "id": "stp00001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000c2", "name": "stopped", "status": "idle", "state": "done"},
  {"pid": 8, "id": "run00002", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "8f0c2a5e-0000-4000-8000-0000000000c3", "name": "plain", "status": "idle", "state": "idle"}
]`
	if err := os.WriteFile(filepath.Join(f.sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	proj := filepath.Join(f.sample, "projects", "-w-a")
	src := filepath.Join("..", "transcript", "testdata")
	copyTo := func(from, to string) {
		t.Helper()
		raw, err := os.ReadFile(from)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Dir(to), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(to, raw, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, id := range []string{"8f0c2a5e-0000-4000-8000-0000000000c1", "8f0c2a5e-0000-4000-8000-0000000000c2"} {
		copyTo(filepath.Join(src, "agents.jsonl"), filepath.Join(proj, id+".jsonl"))
		for _, a := range []string{"ab360a15e1da6a482", "abf8fcd189003562d"} {
			name := "agent-" + a + ".meta.json"
			copyTo(filepath.Join(src, "agents", "subagents", name), filepath.Join(proj, id, "subagents", name))
		}
	}
	copyTo(filepath.Join(src, "answered-bash.jsonl"), filepath.Join(proj, "8f0c2a5e-0000-4000-8000-0000000000c3.jsonl"))

	res := f.h.Handle(context.Background(), request("r1", "c1", protocol.MethodSessionsSubscribe))
	if res.Error != nil {
		t.Fatalf("subscribe: %+v", res.Error)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		t.Fatal(err)
	}
	got := map[string][]protocol.Subagent{}
	for _, s := range list.Sessions {
		got[s.ID] = s.Agents
	}
	if a := got["run00001"]; len(a) != 1 || a[0].ID != "ab360a15e1da6a482" || a[0].Description != "梳理 Go 端未提交改动" || a[0].Type != "general-purpose" || !a[0].Running {
		t.Errorf("running session agents = %+v, want the Go agent only", a)
	}
	if a := got["stp00001"]; len(a) != 0 {
		t.Errorf("stopped session agents = %+v, want none", a)
	}
	if a := got["run00002"]; len(a) != 0 {
		t.Errorf("session without Agent calls has agents %+v", a)
	}

	// Once its meta says finished, the next poll drops it.
	meta := filepath.Join(proj, "8f0c2a5e-0000-4000-8000-0000000000c1", "subagents", "agent-ab360a15e1da6a482.meta.json")
	if err := os.WriteFile(meta, []byte(`{"agentType":"general-purpose","toolUseId":"chatcmpl-tool-8a7bea8a8b0bea75","finished":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, s := range f.nextEvents(t, "c1")["c1"].Sessions {
		if len(s.Agents) != 0 {
			t.Errorf("%s still lists %+v after it finished", s.ID, s.Agents)
		}
	}
}
