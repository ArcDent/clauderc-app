package chat_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeattach"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/agent/lifecycle"
	"clauderc/internal/protocol"
)

var (
	_ agent.Handler       = (*chat.Handler)(nil)
	_ agent.ClientTracker = (*chat.Handler)(nil)
)

func TestMain(m *testing.M) {
	fakeattach.RunIfFake()
	os.Exit(m.Run())
}

const (
	shortID   = "abc12345"
	sessionID = "8f0c2a5e-0000-4000-8000-0000000000c1"
)

type fixture struct {
	sample     string
	transcript string
	keys       string // bytes the fake attach received
	uploads    string
	events     chan protocol.Envelope
	h          *chat.Handler
	cwd        string // the session's working directory
	claudeLog  string // the fake claude's call log
}

type setup struct {
	status, state string // claude agents status and state of the session
	pid           string // "null" for a stopped session
	attachEnv     []string
	// replay, when set, replaces the fake attach with the output recorded
	// from a real `claude attach` up to this mark of the attach package's
	// testdata; received bytes still go to the key log.
	replay string
	// screen, when set, replaces the fake attach with one that prints this
	// text and logs received bytes to the key log.
	screen string
	opts   func(*chat.Options)
	// cwd is the session's working directory; it defaults to /w/x.
	cwd string
}

// replayCommand prints the recorded attach output up to mark, then logs
// every byte it receives to keys.
func replayCommand(t *testing.T, mark, keys string) func(string) []string {
	t.Helper()
	dir, err := filepath.Abs(filepath.Join("..", "attach", "testdata"))
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(dir, "recorded.marks.json"))
	if err != nil {
		t.Fatal(err)
	}
	var marks map[string]int
	if err := json.Unmarshal(b, &marks); err != nil {
		t.Fatal(err)
	}
	n, ok := marks[mark]
	if !ok {
		t.Fatalf("no mark %q", mark)
	}
	raw := filepath.Join(dir, "recorded.raw")
	return func(string) []string {
		return []string{"/bin/sh", "-c", `stty raw -echo; head -c "$1" "$2"; exec cat > "$3"`, "sh", strconv.Itoa(n), raw, keys}
	}
}

// screenCommand prints text, one line per row, then logs every byte it
// receives to keys.
func screenCommand(t *testing.T, text, keys string) func(string) []string {
	t.Helper()
	file := filepath.Join(t.TempDir(), "screen.txt")
	if err := os.WriteFile(file, []byte(strings.ReplaceAll(text, "\n", "\r\n")), 0o600); err != nil {
		t.Fatal(err)
	}
	return func(string) []string {
		return []string{"/bin/sh", "-c", `stty raw -echo; cat "$1"; exec cat > "$2"`, "sh", file, keys}
	}
}

// newFixture runs the handler against the fake claude, listing one
// background session whose transcript holds 60 messages "m1".."m60", and
// the fake attach.
func newFixture(t *testing.T, s setup) *fixture {
	t.Helper()
	if s.status == "" {
		s.status, s.state = "idle", "done"
	}
	if s.pid == "" {
		s.pid = "7"
	}
	if s.cwd == "" {
		s.cwd = "/w/x"
	}
	f := &fixture{sample: t.TempDir(), uploads: filepath.Join(t.TempDir(), "uploads"), events: make(chan protocol.Envelope, 256), cwd: s.cwd}
	claude, claudeLog := fakeclaude.Command(t, f.sample)
	f.claudeLog = claudeLog
	f.setAgents(t, s.status, s.state, s.pid)
	proj := filepath.Join(f.sample, "projects", "-w-x")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	f.transcript = filepath.Join(proj, sessionID+".jsonl")
	var sb strings.Builder
	sb.WriteString(`{"type":"permission-mode","permissionMode":"default"}` + "\n")
	sb.WriteString(`{"type":"attachment","attachment":{"type":"model","identity":{"modelId":"claude-opus-5-5"}}}` + "\n")
	for i := 1; i <= 60; i++ {
		if i%2 == 1 {
			sb.WriteString(userLine(fmt.Sprintf("m%d", i)))
		} else {
			sb.WriteString(assistantLine(fmt.Sprintf("m%d", i)))
		}
	}
	if err := os.WriteFile(f.transcript, []byte(sb.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	f.keys = filepath.Join(t.TempDir(), "keys.log")
	opts := chat.Options{
		Source: discovery.Source{ClaudePath: claude, ClaudeDir: f.sample},
		Push:   agent.PusherFunc(func(e protocol.Envelope) { f.events <- e }),
		Attach: attach.Options{
			Command: fakeattach.Command,
			Env:     append(append(fakeattach.Env(f.keys), fakeattach.TranscriptEnv(f.transcript)...), s.attachEnv...),
		},
		UploadDir:       f.uploads,
		ImageWait:       50 * time.Millisecond,
		PollInterval:    200 * time.Millisecond,
		StateInterval:   500 * time.Millisecond,
		DeliveryTimeout: 2 * time.Second,
	}
	if s.replay != "" {
		opts.Attach = attach.Options{Command: replayCommand(t, s.replay, f.keys)}
	}
	if s.screen != "" {
		opts.Attach = attach.Options{Command: screenCommand(t, s.screen, f.keys)}
	}
	if s.opts != nil {
		s.opts(&opts)
	}
	f.h = chat.New(opts)
	t.Cleanup(f.h.Close)
	return f
}

func (f *fixture) setAgents(t *testing.T, status, state, pid string) {
	t.Helper()
	f.writeAgents(t, "agents.json", f.agent(shortID, sessionID, status, state, pid))
}

// agent is one entry of the fake `claude agents --json --all` in the
// session's working directory.
func (f *fixture) agent(id, session, status, state, pid string) string {
	return fmt.Sprintf(`{"pid": %s, "id": %q, "cwd": %q, "kind": "background", "startedAt": 1794529871000, "sessionId": %q, "name": "t", "status": %q, "state": %q}`,
		pid, id, f.cwd, session, status, state)
}

// writeAgents writes entries as the list file name in the sample directory.
func (f *fixture) writeAgents(t *testing.T, name string, entries ...string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(f.sample, name), []byte("["+strings.Join(entries, ",")+"]"), 0o600); err != nil {
		t.Fatal(err)
	}
}

func userLine(text string) string {
	b, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user", "content": text}})
	return string(b) + "\n"
}

func assistantLine(text string) string {
	b, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "text", "text": text}}}})
	return string(b) + "\n"
}

func (f *fixture) appendTranscript(t *testing.T, s string) {
	t.Helper()
	fh, err := os.OpenFile(f.transcript, os.O_APPEND|os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer fh.Close()
	if _, err := fh.WriteString(s); err != nil {
		t.Fatal(err)
	}
}

func req(id, client string, method protocol.Method, body any) protocol.Envelope {
	raw, _ := json.Marshal(body)
	return protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: id, Method: method, Machine: "m-1", Client: client, Body: raw}
}

func (f *fixture) call(t *testing.T, client string, method protocol.Method, body any) protocol.Envelope {
	t.Helper()
	res := f.h.Handle(context.Background(), req("r-"+string(method), client, method, body))
	if res.Kind != protocol.KindResponse || res.Method != method || res.Client != client {
		t.Fatalf("%s response envelope = %+v", method, res)
	}
	return res
}

func (f *fixture) ok(t *testing.T, client string, method protocol.Method, body any) json.RawMessage {
	t.Helper()
	res := f.call(t, client, method, body)
	if res.Error != nil {
		t.Fatalf("%s failed: %+v", method, res.Error)
	}
	return res.Body
}

// nextEvent waits up to timeout for an event of method.
func (f *fixture) nextEvent(t *testing.T, method protocol.Method, timeout time.Duration) protocol.Envelope {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case e := <-f.events:
			if e.Method == method {
				return e
			}
		case <-deadline:
			t.Fatalf("no %s event within %v", method, timeout)
		}
	}
}

// maybeEvent is nextEvent that reports a missing event instead of failing.
func (f *fixture) maybeEvent(method protocol.Method, timeout time.Duration) (protocol.Envelope, bool) {
	deadline := time.After(timeout)
	for {
		select {
		case e := <-f.events:
			if e.Method == method {
				return e, true
			}
		case <-deadline:
			return protocol.Envelope{}, false
		}
	}
}

// keyLog returns what the fake attach received after its cursor reply,
// once the log has not changed for 300ms: the fake logs asynchronously.
func (f *fixture) keyLog() string {
	b, _ := os.ReadFile(f.keys)
	for {
		time.Sleep(300 * time.Millisecond)
		next, _ := os.ReadFile(f.keys)
		if bytes.Equal(next, b) {
			break
		}
		b = next
	}
	return strings.TrimPrefix(string(b), "\x1b[1;1R")
}

func TestOpenLatestPageAndFollow(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Items) != 50 || res.Items[0].Seq != 11 || res.Items[0].Text != "m11" || res.Items[49].Text != "m60" || !res.HasMore {
		t.Fatalf("latest page: %d items, first %+v, hasMore %v", len(res.Items), res.Items[0], res.HasMore)
	}
	if res.State != protocol.StateIdle || res.Model != "claude-opus-5-5" || res.PermissionMode != protocol.PermissionDefault || res.Pending != nil {
		t.Fatalf("status = %+v pending %+v", res.ChatStatus, res.Pending)
	}

	var older protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, Before: 11}), &older); err != nil {
		t.Fatal(err)
	}
	if len(older.Items) != 10 || older.Items[0].Text != "m1" || older.Items[9].Text != "m10" || older.HasMore {
		t.Fatalf("older page: %d items, hasMore %v", len(older.Items), older.HasMore)
	}

	f.appendTranscript(t, userLine("from the computer"))
	e := f.nextEvent(t, protocol.EventChatItems, 2*time.Second)
	var items protocol.ChatItemsEvent
	if err := json.Unmarshal(e.Body, &items); err != nil {
		t.Fatal(err)
	}
	if e.Client != "c1" || items.SessionID != shortID || len(items.Items) != 1 || items.Items[0].Seq != 61 || items.Items[0].Text != "from the computer" {
		t.Fatalf("chat.items = %+v to %s", items, e.Client)
	}

	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID})
	f.appendTranscript(t, userLine("after close"))
	time.Sleep(time.Second)
	for len(f.events) > 0 {
		if e := <-f.events; e.Method == protocol.EventChatItems {
			t.Fatalf("chat.items after close: %s", e.Body)
		}
	}
}

// A queued message goes to the phone marked queued; its formal entry then
// replaces it under the same seq without the mark.
func TestQueuedItemIsMarkedUntilItsEntry(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	next := func() protocol.ChatItem {
		t.Helper()
		var ev protocol.ChatItemsEvent
		if err := json.Unmarshal(f.nextEvent(t, protocol.EventChatItems, 2*time.Second).Body, &ev); err != nil || len(ev.Items) != 1 {
			t.Fatalf("chat.items = %+v, %v", ev, err)
		}
		return ev.Items[0]
	}
	f.appendTranscript(t, `{"type":"queue-operation","operation":"enqueue","content":"排队的消息"}`+"\n")
	if it := next(); it.Seq != 61 || it.Text != "排队的消息" || !it.Queued {
		t.Fatalf("queued item = %+v", it)
	}
	f.appendTranscript(t, userLine("排队的消息"))
	if it := next(); it.Seq != 61 || it.Text != "排队的消息" || it.Queued {
		t.Fatalf("formal item = %+v", it)
	}
}

func TestOpenUnknownSession(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	res := f.call(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: "zzz99999"})
	if res.Error == nil || res.Error.Code != protocol.CodeSessionNotFound {
		t.Fatalf("response = %+v", res.Error)
	}
}

func TestSendClearsInputThenPastes(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "第一行\n第二行"})
	if got, want := f.keyLog(), "\x15\x1b[200~第一行\n第二行\x1b[201~\r"; got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
}

// A busy session takes the message right away; Claude Code queues it and
// writes an enqueue record, which counts as delivered.
func TestSendWhileBusyIsDeliveredByEnqueue(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "busy", state: "working", attachEnv: []string{fakeattach.DeliverEnv("enqueue")}})
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "排队的消息"})
	if got, want := f.keyLog(), "\x15\x1b[200~排队的消息\x1b[201~\r"; got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
	b, _ := os.ReadFile(f.transcript)
	if !bytes.Contains(b, []byte(`"operation":"enqueue"`)) {
		t.Fatal("fake did not enqueue")
	}
}

// Claude Code queues a slash command with an enqueue record that has no
// text; for a slash command that counts as delivered, and queued.
func TestSendCommandWhileBusyIsDeliveredByBareEnqueue(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "busy", state: "working", attachEnv: []string{fakeattach.DeliverEnv("enqueue")}})
	body := f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/todolist-workflow 先用这个"})
	if got, want := string(body), `{"queued":true}`; got != want {
		t.Fatalf("body = %s, want %s", got, want)
	}
}

// A "!" command shows up as a command item, not as the text sent.
func TestSendBashCommandIsDelivered(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	body := f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "!ls"})
	if got, want := string(body), `{}`; got != want {
		t.Fatalf("body = %s, want %s", got, want)
	}
}

// An enqueue without text is never taken for a plain message.
func TestSendTextNotDeliveredByBareEnqueue(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "busy", state: "working", attachEnv: []string{fakeattach.DeliverEnv("enqueue-bare")}})
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "你好"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Fatalf("response error = %+v", res.Error)
	}
}

// Two chat.send at once are confirmed each by its own record: the one
// typed second gets no credit for the record of the first, whatever
// Claude Code writes a moment after an Enter.
func TestSendsAtOnceConfirmedEachByItsOwn(t *testing.T) {
	t.Parallel()
	const user = `{"type":"user","message":{"role":"user","content":"same"}}`
	const enqueue = `{"type":"queue-operation","operation":"enqueue"}`
	cases := []struct {
		name   string
		s      setup
		a, b   string
		record string // what an Enter after text matching match writes
		match  string
		bOK    bool
	}{
		// Only one record shows up for the same text sent twice.
		{"same text", setup{}, "same", "same", user, "*same*", false},
		// Claude Code queues a slash command with an enqueue without its name.
		{"second command not queued", running(), "/foo 1", "/bar 2", enqueue, "*foo*", false},
		{"both commands queued", running(), "/foo 1", "/bar 2", enqueue, "*/*", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := c.s
			// Records are written by the first Enter after text that matches.
			s.opts = shellAttach(`printf '────────\r\n❯\302\240'; cr=$(printf '\r'); n=0; buf=; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; buf="$buf$c"; if [ "$c" = "$cr" ]; then case "$buf" in ` + c.match + `) n=$((n+1)); { [ "` + c.a + `" != "` + c.b + `" ] || [ $n -eq 1 ]; } && printf '%s\n' '` + c.record + `' >> "$FAKE_ATTACH_TRANSCRIPT";; esac; buf=; fi; done`)
			f := newFixture(t, s)
			first := make(chan protocol.Envelope, 1)
			go func() {
				first <- f.h.Handle(context.Background(), req("r-a", "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: c.a}))
			}()
			// The second goes once the first is being typed.
			for b, _ := os.ReadFile(f.keys); !strings.Contains(string(b), c.a); b, _ = os.ReadFile(f.keys) {
				time.Sleep(10 * time.Millisecond)
			}
			second := f.call(t, "c2", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: c.b})
			if res := <-first; res.Error != nil {
				t.Errorf("first: error %+v", res.Error)
			}
			if c.bOK && second.Error != nil || !c.bOK && (second.Error == nil || second.Error.Code != protocol.CodeNotDelivered) {
				t.Errorf("second: error %+v, want delivered %v", second.Error, c.bOK)
			}
		})
	}
}

func TestSendNotDelivered(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{attachEnv: []string{fakeattach.DeliverEnv("none")}})
	start := time.Now()
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "lost"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Fatalf("response error = %+v", res.Error)
	}
	if want := "消息发出后 2 秒内没有出现在会话里，可能没有送达"; res.Error.Message != want {
		t.Errorf("message %q, want %q", res.Error.Message, want)
	}
	if d := time.Since(start); d < 2*time.Second {
		t.Fatalf("gave up after %v, before the delivery timeout", d)
	}
}

// forker records the forks chat.send asks for.
type forker struct {
	mu    sync.Mutex
	calls [][2]string
}

func (k *forker) Fork(_ context.Context, id, instruction string) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.calls = append(k.calls, [2]string{id, instruction})
	return nil
}

// Claude Code refuses /fork in a session launched with --setting-sources,
// so the agent forks it itself instead of typing it; other sessions get
// /fork typed as before.
func TestSendForkWhereClaudeRefusesIt(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name   string
		flags  []string
		forked bool
	}{
		{"account login", []string{"--setting-sources", "project,local", "--settings", "/x.json"}, true},
		{"plain", []string{"--model", "opus"}, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			k := &forker{}
			f := newFixture(t, setup{opts: func(o *chat.Options) { o.Forker = k }})
			job, _ := json.Marshal(map[string]any{"respawnFlags": c.flags})
			if err := os.MkdirAll(filepath.Join(f.sample, "jobs", shortID), 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(f.sample, "jobs", shortID, "state.json"), job, 0o600); err != nil {
				t.Fatal(err)
			}
			f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/fork 只回复 FORKED"})
			keys, _ := os.ReadFile(f.keys)
			typed := strings.Contains(string(keys), "/fork")
			want := [][2]string(nil)
			if c.forked {
				want = [][2]string{{shortID, "只回复 FORKED"}}
			}
			if !slices.Equal(k.calls, want) || typed == c.forked {
				t.Errorf("forks %v, typed %v; want forks %v, typed %v", k.calls, typed, want, !c.forked)
			}
		})
	}
}

// /compact is recorded only once the compaction is done, so a slash
// command missing from the transcript counts as delivered when the screen
// shows it taken: gone from the input box with a status line up. Without
// the status line, or for plain text, it stays not delivered.
func TestSendCommandScreen(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name, deliver, text string
		ok                  bool
	}{
		{"working", "working", "/compact", true},
		{"no status line", "none", "/compact", false},
		{"plain text", "working", "你好", false},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := newFixture(t, setup{attachEnv: []string{fakeattach.DeliverEnv(c.deliver)}})
			res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: c.text})
			switch {
			case c.ok && res.Error != nil:
				t.Fatalf("response error = %+v, want delivered", res.Error)
			case !c.ok && (res.Error == nil || res.Error.Code != protocol.CodeNotDelivered):
				t.Fatalf("response error = %+v, want not delivered", res.Error)
			}
		})
	}
}

// A slash command Claude Code has no command or skill for, such as a
// personal skill in a session that does not load them, is reported as
// unknown at once instead of as not delivered.
func TestSendUnknownCommand(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{attachEnv: []string{fakeattach.DeliverEnv("unknown")}})
	start := time.Now()
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "/todolist-workflow 先用这个"})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("response error = %+v", res.Error)
	}
	if want := "会话不认识 /todolist-workflow 这个命令"; res.Error.Message != want {
		t.Errorf("message %q, want %q", res.Error.Message, want)
	}
	if d := time.Since(start); d >= 2*time.Second {
		t.Errorf("answered after %v, not before the delivery timeout", d)
	}
}

func TestSendImages(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	png := []byte("\x89PNG fake")
	jpg := []byte("\xff\xd8\xff fake")
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "这是什么", Images: []protocol.Image{
		{MediaType: "image/png", Data: png}, {MediaType: "image/jpeg", Data: jpg},
	}})
	entries, err := os.ReadDir(f.uploads)
	if err != nil || len(entries) != 2 {
		t.Fatalf("uploads = %v, %v", entries, err)
	}
	var paths []string
	for _, want := range []struct {
		ext  string
		data []byte
	}{{".png", png}, {".jpg", jpg}} {
		var found string
		for _, e := range entries {
			if filepath.Ext(e.Name()) == want.ext {
				found = filepath.Join(f.uploads, e.Name())
			}
		}
		if found == "" {
			t.Fatalf("no %s upload in %v", want.ext, entries)
		}
		fi, _ := os.Stat(found)
		if fi.Mode().Perm() != 0o600 {
			t.Errorf("%s mode %v, want 0600", found, fi.Mode().Perm())
		}
		if b, _ := os.ReadFile(found); !bytes.Equal(b, want.data) {
			t.Errorf("%s content differs", found)
		}
		paths = append(paths, found)
	}
	want := "\x15\x1b[200~" + paths[0] + "\x1b[201~\x1b[200~" + paths[1] + "\x1b[201~\x1b[200~ 这是什么\x1b[201~\r"
	if got := f.keyLog(); got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
}

// There is no count limit; only the total size is checked.
func TestSendManyImages(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	six := make([]protocol.Image, 6)
	for i := range six {
		six[i] = protocol.Image{MediaType: "image/png", Data: []byte("\x89PNG fake")}
	}
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "这些是什么", Images: six})
	if entries, err := os.ReadDir(f.uploads); err != nil || len(entries) != 6 {
		t.Fatalf("uploads = %v, %v", entries, err)
	}
}

// Uploads are named by 32 random hex digits plus .png or .jpg; only such
// files older than 24 hours are removed, anything else in the directory
// is left alone.
func TestSendRemovesOldUploads(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	if err := os.MkdirAll(f.uploads, 0o700); err != nil {
		t.Fatal(err)
	}
	oldJPG := filepath.Join(f.uploads, "0123456789abcdef0123456789abcdef.jpg")
	oldPNG := filepath.Join(f.uploads, "fedcba9876543210fedcba9876543210.png")
	recent := filepath.Join(f.uploads, "00000000000000000000000000000000.jpg")
	foreign := []string{
		filepath.Join(f.uploads, "notes.txt"),
		filepath.Join(f.uploads, "old.jpg"),
		filepath.Join(f.uploads, "0123456789abcdef0123456789abcdeg.jpg"),
		filepath.Join(f.uploads, "0123456789abcdef0123456789abcdef.gif"),
		filepath.Join(f.uploads, "0123456789abcdef0123456789abcdef0.png"),
	}
	for _, p := range append([]string{oldJPG, oldPNG, recent}, foreign...) {
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
		age := 25 * time.Hour
		if p == recent {
			age = 23 * time.Hour
		}
		if err := os.Chtimes(p, time.Now().Add(-age), time.Now().Add(-age)); err != nil {
			t.Fatal(err)
		}
	}
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "hi"})
	for _, p := range []string{oldJPG, oldPNG} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Errorf("upload older than 24h still there: %s %v", filepath.Base(p), err)
		}
	}
	for _, p := range append([]string{recent}, foreign...) {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("%s removed: %v", filepath.Base(p), err)
		}
	}
}

func TestSendRejects(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	big := make([]byte, protocol.MaxImageBytes+1)
	half := make([]byte, 6<<20)
	cases := []struct {
		name string
		body protocol.ChatSendRequest
		code protocol.ErrorCode
	}{
		{"empty", protocol.ChatSendRequest{SessionID: shortID}, protocol.CodeInvalidParams},
		{"images too big together", protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "image/png", Data: half}, {MediaType: "image/png", Data: half}}}, protocol.CodeInvalidParams},
		{"image too big", protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "image/png", Data: big}}}, protocol.CodeInvalidParams},
		{"not an image", protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "text/html", Data: []byte("x")}}}, protocol.CodeInvalidParams},
		{"unknown session", protocol.ChatSendRequest{SessionID: "zzz99999", Text: "x"}, protocol.CodeSessionNotFound},
	}
	for _, c := range cases {
		res := f.call(t, "c1", protocol.MethodChatSend, c.body)
		if res.Error == nil || res.Error.Code != c.code {
			t.Errorf("%s: error %+v, want %s", c.name, res.Error, c.code)
		}
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent for rejected messages: %q", got)
	}
}

// /model <name> saves the model as the default in ~/.claude/settings.json,
// and a bare /model opens the picker where the next Enter does the same
// (RESULTS.md), so neither is typed.
func TestSendRefusesModelCommand(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	for _, text := range []string{"/model sonnet", "/model", "  /model  opus\n", "\n/model\tsonnet", "/MODEL sonnet"} {
		res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: text})
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || !strings.Contains(res.Error.Message, "切换模型") {
			t.Errorf("%q: error %+v, want invalidParams pointing to model switching", text, res.Error)
		}
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent for /model: %q", got)
	}
	// Other text mentioning /model is a plain message.
	for _, text := range []string{"/models", "说说 /model sonnet 的作用"} {
		f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: text})
	}
}

// Waking a stopped session belongs to chat.send later (task 8.2); until
// then it is refused without touching the session.
func TestSendToStoppedSession(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "idle", state: "done", pid: "null"})
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
		t.Fatalf("error = %+v", res.Error)
	}
	if _, err := os.Stat(f.keys); !os.IsNotExist(err) {
		t.Fatal("an attach was started for a stopped session")
	}
}

func TestPendingIsPushedAndCleared(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	f.appendTranscript(t, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_p1","name":"Bash","input":{"command":"touch marker.txt"}}]}}`+"\n")
	f.setAgents(t, "waiting", "blocked", "7")
	e := f.nextEvent(t, protocol.EventChatPending, 3*time.Second)
	var ev protocol.ChatPendingEvent
	if err := json.Unmarshal(e.Body, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Pending == nil || ev.Pending.ToolUseID != "toolu_p1" || ev.Pending.Kind != protocol.PendingPermission || ev.Pending.ToolName != "Bash" {
		t.Fatalf("chat.pending = %s", e.Body)
	}
	// Answered on the computer: the prompt goes away within 2 seconds.
	f.appendTranscript(t, `{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_p1","content":"ok"}]}}`+"\n")
	e = f.nextEvent(t, protocol.EventChatPending, 2*time.Second)
	if err := json.Unmarshal(e.Body, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Pending != nil {
		t.Fatalf("chat.pending after the answer = %s", e.Body)
	}
}

// fakeWaker records Wake calls; wake runs on each call.
type fakeWaker struct {
	mu    sync.Mutex
	calls []string
	wake  func() error
}

func (w *fakeWaker) Wake(ctx context.Context, sessionID string, hold func(context.Context, string) (func(), error)) error {
	w.mu.Lock()
	w.calls = append(w.calls, sessionID)
	w.mu.Unlock()
	return w.wake()
}

func TestSendWakesStoppedSession(t *testing.T) {
	t.Parallel()
	w := &fakeWaker{}
	f := newFixture(t, setup{status: "idle", state: "done", pid: "null", opts: func(o *chat.Options) { o.Waker = w }})
	w.wake = func() error {
		f.setAgents(t, "idle", "done", "7") // the session runs again
		return nil
	}
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if len(w.calls) != 1 || w.calls[0] != sessionID {
		t.Fatalf("Wake calls = %q, want [%q]", w.calls, sessionID)
	}
	if got, want := f.keyLog(), "\x15\x1b[200~继续\x1b[201~\r"; got != want {
		t.Fatalf("keys = %q, want %q", got, want)
	}
}

// launches are the fake claude's calls other than `claude agents`.
func (f *fixture) launches(t *testing.T) [][]string {
	t.Helper()
	var out [][]string
	for _, c := range fakeclaude.Calls(t, f.claudeLog) {
		if len(c.Args) == 0 || c.Args[0] != "agents" {
			out = append(out, c.Args)
		}
	}
	return out
}

// wakeWith makes the lifecycle Handler the Waker, with claude stop
// replacing the list by agents.stopped.json. attached runs as each attach
// starts and may return an argv to run instead of the fake attach.
func wakeWith(t *testing.T, attached func() []string) func(*chat.Options) {
	return func(o *chat.Options) {
		command := o.Attach.Command
		o.Attach.Command = func(id string) []string {
			if argv := attached(); argv != nil {
				return argv
			}
			return command(id)
		}
		src := o.Source
		src.ClaudePath = filepath.Join(t.TempDir(), "claude")
		script := "#!/bin/sh\n" + `if [ "$1" = stop ]; then cp ` +
			strconv.Quote(filepath.Join(src.ClaudeDir, "agents.stopped.json")) + " " + strconv.Quote(filepath.Join(src.ClaudeDir, "agents.json")) +
			"; fi\nexec " + strconv.Quote(o.Source.ClaudePath) + ` "$@"` + "\n"
		if err := os.WriteFile(src.ClaudePath, []byte(script), 0o700); err != nil {
			t.Fatal(err)
		}
		o.Waker = lifecycle.New(lifecycle.Options{Source: src, WakeTimeout: 5 * time.Second, PollInterval: 20 * time.Millisecond})
	}
}

// A stopped session is woken by a new attach, not by claude --bg --resume,
// also while an open chat holds its attach from before the session stopped;
// the message then goes into that held attach.
func TestSendWakesThroughAttach(t *testing.T) {
	t.Parallel()
	var f *fixture
	var mu sync.Mutex
	attaches := 0
	count := func() int {
		mu.Lock()
		defer mu.Unlock()
		return attaches
	}
	f = newFixture(t, setup{cwd: t.TempDir(), opts: wakeWith(t, func() []string {
		mu.Lock()
		defer mu.Unlock()
		if attaches++; attaches == 1 {
			return nil // the open chat's
		}
		f.setAgents(t, "idle", "done", "7") // waking runs the session again
		return []string{"/bin/sh", "-c", "stty raw -echo; exec cat >/dev/null"}
	})})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	for deadline := time.Now().Add(5 * time.Second); count() == 0; time.Sleep(20 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("the open chat did not attach")
		}
	}
	f.setAgents(t, "idle", "done", "null") // claude stop: the held attach stays up
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if n := count(); n != 2 {
		t.Errorf("attaches = %d, want the chat's and one to wake the session", n)
	}
	if l := f.launches(t); len(l) != 0 {
		t.Errorf("claude calls besides agents = %q, want none", l)
	}
	// The chat's attach redrew the screen when it started.
	if got, want := f.keyLog(), "\x15\x1b[200~继续\x1b[201~\r"; !strings.HasSuffix(got, want) {
		t.Fatalf("keys = %q, want them to end in %q", got, want)
	}
}

// A copy that shows up while waking is stopped and removed, and chat.send
// says the session did not wake because of it.
func TestSendWakeRemovesCopy(t *testing.T) {
	t.Parallel()
	var f *fixture
	f = newFixture(t, setup{status: "idle", state: "done", pid: "null", cwd: t.TempDir(), opts: wakeWith(t, func() []string {
		f.writeAgents(t, "agents.json", f.agent(shortID, sessionID, "idle", "done", "null"), f.agent("cpy00001", "uuid-copy", "idle", "blocked", "8"))
		return nil
	})})
	f.writeAgents(t, "agents.stopped.json", f.agent(shortID, sessionID, "idle", "done", "null"), f.agent("cpy00001", "uuid-copy", "idle", "done", "null"))
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if res.Error == nil || !strings.HasPrefix(res.Error.Message, "唤醒会话失败：") || !strings.Contains(res.Error.Message, "另起了会话") {
		t.Fatalf("error = %+v, want 唤醒会话失败： and the copy", res.Error)
	}
	if l, want := f.launches(t), [][]string{{"stop", "cpy00001"}, {"rm", "cpy00001"}}; fmt.Sprint(l) != fmt.Sprint(want) {
		t.Errorf("claude calls besides agents = %q, want %q", l, want)
	}
}

func TestSendWakeFails(t *testing.T) {
	t.Parallel()
	w := &fakeWaker{wake: func() error { return errors.New("resume failed") }}
	f := newFixture(t, setup{status: "idle", state: "done", pid: "null", opts: func(o *chat.Options) { o.Waker = w }})
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if res.Error == nil || res.Error.Code != protocol.CodeInternal {
		t.Fatalf("error = %+v", res.Error)
	}
	if len(w.calls) != 1 {
		t.Fatalf("Wake calls = %q", w.calls)
	}
	if _, err := os.Stat(f.keys); !os.IsNotExist(err) {
		t.Fatal("an attach was started although waking failed")
	}
}

func TestSendWakeFailsWithCode(t *testing.T) {
	t.Parallel()
	w := &fakeWaker{wake: func() error {
		return &protocol.Error{Code: protocol.CodeDirNotFound, Message: "会话的工作目录已不存在：/w/gone"}
	}}
	f := newFixture(t, setup{status: "idle", state: "done", pid: "null", opts: func(o *chat.Options) { o.Waker = w }})
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "继续"})
	if res.Error == nil || res.Error.Code != protocol.CodeDirNotFound || !strings.Contains(res.Error.Message, "/w/gone") {
		t.Fatalf("error = %+v, want the Waker's code and reason", res.Error)
	}
}

// Control bytes in a message would end the paste early and turn the rest
// into keys; they are dropped before the /model check and typing.
func TestSendCleansControlBytes(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	// What is left after cleaning starts with /model, so it is refused.
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "\x1b/model opus"})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || !strings.Contains(res.Error.Message, "切换模型") {
		t.Errorf("ESC before /model: error %+v", res.Error)
	}
	f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "hi\x1b[201~\x15/model opus\r"})
	got := f.keyLog()
	if want := "\x15\x1b[200~hi[201~/model opus\x1b[201~\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	body := strings.TrimSuffix(strings.TrimPrefix(got, "\x15\x1b[200~"), "\x1b[201~\r")
	if strings.ContainsAny(body, "\x1b\x15\r") || strings.Count(got, "\x15") != 1 || strings.Count(got, "\r") != 1 {
		t.Errorf("control bytes typed: %q", got)
	}
}

// A custom answer is typed key by key; Shift+Tab and Ctrl+C in it are not.
func TestAnswerCustomCleansControlBytes(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{screen: questionScreen("颜色？")})
	f.blockedOn(t, "toolu_q1", "AskUserQuestion", `{"questions":[{"question":"颜色？","header":"颜色","multiSelect":false,"options":[{"label":"红","description":""},{"label":"绿","description":""}]}]}`)
	f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Custom: "都\x1b[Z不\x03要"}}})
	got := f.keyLog()
	if want := "\x1b[B\x1b[B都[Z不要\r"; got != want {
		t.Errorf("keys %q, want %q", got, want)
	}
	if strings.Contains(got, "\x1b[Z") || strings.Contains(got, "\x03") {
		t.Errorf("control keys typed: %q", got)
	}
}

// Design D5: blocked without an unanswered tool call falls through to the
// status, so a busy session is working and can be interrupted.
func TestBlockedButBusyIsWorking(t *testing.T) {
	t.Parallel()
	// Interrupt first: chat.open starts an attach that types Ctrl+L.
	f := newFixture(t, setup{status: "busy", state: "blocked"})
	f.ok(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	if got := f.keyLog(); got != "\x1b" {
		t.Fatalf("interrupt keys %q, want Esc", got)
	}
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if res.State != protocol.StateWorking || res.Pending != nil {
		t.Fatalf("state %q pending %+v, want working with nothing pending", res.State, res.Pending)
	}

	// With the tool call unanswered it is pending, and Esc would deny it.
	f = newFixture(t, setup{})
	f.appendTranscript(t, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_p1","name":"Bash","input":{"command":"touch x"}}]}}`+"\n")
	f.setAgents(t, "busy", "blocked", "7")
	f.ok(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	if got := f.keyLog(); got != "" {
		t.Fatalf("interrupt keys %q on a pending prompt", got)
	}
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	if res.State != protocol.StatePending || res.Pending == nil {
		t.Fatalf("state %q pending %+v, want pending", res.State, res.Pending)
	}
}

// The phone shows error messages as they are, so they are Chinese.
func TestErrorMessagesAreChinese(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{attachEnv: []string{fakeattach.ModesEnv("default")}})
	big := make([]byte, protocol.MaxImageBytes+1)
	half := make([]byte, 6<<20)
	cases := []struct {
		name   string
		method protocol.Method
		body   any
		want   string
	}{
		{"no session id", protocol.MethodChatOpen, protocol.ChatOpenRequest{}, "缺少会话 id"},
		{"unknown session", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: "zzz99999"}, "找不到这个后台会话，可能已经被移除"},
		{"empty message", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID}, "消息是空的"},
		{"control bytes only", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "\x1b\x15\r"}, "消息是空的"},
		{"images too big together", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "image/png", Data: half}, {MediaType: "image/png", Data: half}}}, "图片加起来不能超过 11 MB"},
		{"image too big", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "image/png", Data: big}}}, "图片不能为空，也不能超过 10 MB"},
		{"not an image", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "x", Images: []protocol.Image{{MediaType: "text/html", Data: []byte("x")}}}, "只支持 PNG 和 JPEG 图片"},
		{"nothing pending", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_x", Decision: protocol.DecisionAllow}, "待确认的内容已变化，请看最新的请求再回答"},
		{"no model", protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID}, "缺少要切换的模型"},
		{"unknown model", protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID, Model: "sonnet"}, "模型列表里没有这个模型，已取消"},
		{"bypass mode", protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: protocol.PermissionBypass}, "这个权限模式不能从手机切换"},
		{"mode never reached", protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: protocol.PermissionAuto}, "按了 4 次切换键仍没到目标权限模式"},
	}
	for _, c := range cases {
		res := f.call(t, "c1", c.method, c.body)
		if res.Error == nil || res.Error.Message != c.want {
			t.Errorf("%s: error %+v, want message %q", c.name, res.Error, c.want)
		}
	}

	// A body that is not JSON.
	bad := req("r-bad", "c1", protocol.MethodChatSend, nil)
	bad.Body = json.RawMessage(`"x"`)
	if res := f.h.Handle(context.Background(), bad); res.Error == nil || res.Error.Message != "请求内容格式不对" {
		t.Errorf("malformed body: error %+v", res.Error)
	}

	// Answers that do not fit the questions.
	f.blockedOn(t, "toolu_q1", "AskUserQuestion", twoQuestionInputForMessages)
	for want, body := range map[string]protocol.ChatAnswerRequest{
		"回答的题数和提问的题数不一致":       {SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Selected: []int{0}}}},
		"选的选项不存在":              {SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Selected: []int{9}}, {Selected: []int{0}}}},
		"有题目还没回答":              {SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{}, {Selected: []int{0}}}},
		"单选题只能选一个答案":           {SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Selected: []int{0, 1}}, {Selected: []int{0}}}},
		"提问要选选项或填写回答，不能选允许或拒绝": {SessionID: shortID, ToolUseID: "toolu_q1", Decision: protocol.DecisionAllow},
	} {
		if res := f.call(t, "c1", protocol.MethodChatAnswer, body); res.Error == nil || res.Error.Message != want {
			t.Errorf("answer %+v: error %+v, want message %q", body, res.Error, want)
		}
	}
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch x"}`)
	if res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: "maybe"}); res.Error == nil || res.Error.Message != "权限请求只能选允许或拒绝" {
		t.Errorf("bad decision: error %+v", res.Error)
	}

	// A stopped session.
	f = newFixture(t, setup{status: "idle", state: "done", pid: "null"})
	for _, m := range []protocol.Method{protocol.MethodChatModels, protocol.MethodChatSend} {
		res := f.call(t, "c1", m, protocol.ChatSendRequest{SessionID: shortID, Text: "x"})
		if res.Error == nil || res.Error.Message != "会话已停止，无法操作" {
			t.Errorf("%s on a stopped session: error %+v", m, res.Error)
		}
	}
}

const twoQuestionInputForMessages = twoQuestionInput

// A /fork copy starts with its source's history; the grey line where its
// own conversation starts names the source. The transcript is the real
// fork in discovery's v2327 sample.
func TestForkStartsWithSourceLine(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	raw, err := os.ReadFile(filepath.Join("..", "discovery", "testdata", "v2327", "projects", "-w-repo", "9a1c2a5e-0000-4000-8000-0000000000a2.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.transcript, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	at := -1
	for i, it := range res.Items {
		if strings.HasPrefix(it.Text, "以上来自") {
			if at >= 0 {
				t.Fatalf("second source line at %d: %+v", i, it)
			}
			at = i
		}
	}
	if at <= 0 || at+1 >= len(res.Items) {
		t.Fatalf("no source line inside the items: %+v", res.Items)
	}
	line, before, after := res.Items[at], res.Items[at-1], res.Items[at+1]
	if line.Kind != protocol.ItemNotice || line.Text != "以上来自「repo work」" || line.Title != "" {
		t.Errorf("source line = %+v", line)
	}
	if before.Kind != protocol.ItemAssistant || !strings.HasPrefix(before.Text, "已在 README.md 末尾加上") {
		t.Errorf("before the line: %+v, want the source's last reply", before)
	}
	if after.Kind != protocol.ItemUser || after.Text != "只回复 FORKED" {
		t.Errorf("after the line: %+v, want the fork's first message", after)
	}
}

func TestNoSourceLineWithoutFork(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	var res protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	for _, it := range res.Items {
		if strings.HasPrefix(it.Text, "以上来自") {
			t.Fatalf("source line in a session that is no fork: %+v", it)
		}
	}
}

// Entering a worktree moves the transcript to the worktree's project
// directory; an open chat keeps going from there without sending again
// what the phone already has, and a permission prompt after the move
// still reaches it.
func TestChatFollowsMovedTranscript(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	moved := filepath.Join(f.sample, "projects", "-w-x--claude-worktrees-fox", sessionID+".jsonl")
	if err := os.MkdirAll(filepath.Dir(moved), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(f.transcript, moved); err != nil {
		t.Fatal(err)
	}
	f.transcript = moved
	f.appendTranscript(t, userLine("after the move"))
	e := f.nextEvent(t, protocol.EventChatItems, 3*time.Second)
	var items protocol.ChatItemsEvent
	if err := json.Unmarshal(e.Body, &items); err != nil {
		t.Fatal(err)
	}
	if len(items.Items) != 1 || items.Items[0].Seq != 61 || items.Items[0].Text != "after the move" {
		t.Fatalf("chat.items after the move = %s", e.Body)
	}

	f.appendTranscript(t, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_m1","name":"Edit","input":{"file_path":"/w/x/.claude/worktrees/fox/a.txt","old_string":"a","new_string":"b"}}]}}`+"\n")
	f.setAgents(t, "waiting", "blocked", "7")
	e = f.nextEvent(t, protocol.EventChatPending, 3*time.Second)
	var ev protocol.ChatPendingEvent
	if err := json.Unmarshal(e.Body, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.Pending == nil || ev.Pending.ToolUseID != "toolu_m1" || ev.Pending.Kind != protocol.PendingPermission {
		t.Fatalf("chat.pending after the move = %s", e.Body)
	}
	// Nothing from before the move came again.
	for len(f.events) > 0 {
		e := <-f.events
		if e.Method != protocol.EventChatItems {
			continue
		}
		var more protocol.ChatItemsEvent
		_ = json.Unmarshal(e.Body, &more)
		for _, it := range more.Items {
			if it.Seq <= 60 {
				t.Fatalf("item %d sent again after the move: %+v", it.Seq, it)
			}
		}
	}
}

// A `claude agents` that takes 3 seconds each time holds up neither an
// open chat's new records nor a subagent chat's: they still reach the
// phone within a second.
func TestSlowSessionListDoesNotHoldItems(t *testing.T) {
	t.Parallel()
	const text = "列表慢时的新消息"
	for _, sub := range []bool{false, true} {
		name := "session"
		if sub {
			name = "subagent"
		}
		t.Run(name, func(t *testing.T) {
			f := newFixture(t, setup{status: "busy", state: "working"})
			open := protocol.ChatOpenRequest{SessionID: shortID}
			add := func() { f.appendTranscript(t, userLine(text)) }
			if sub {
				dir := f.addSubagents(t)
				open.AgentID = goAgent
				add = func() {
					appendFile(t, filepath.Join(dir, "agent-"+goAgent+".jsonl"), `{"type":"assistant","isSidechain":true,"agentId":"`+goAgent+`","message":{"role":"assistant","content":[{"type":"text","text":"`+text+`"}]}}`+"\n")
				}
			}
			f.ok(t, "c1", protocol.MethodChatOpen, open)
			if err := os.WriteFile(filepath.Join(f.sample, "agents.delay"), []byte("3\n"), 0o600); err != nil {
				t.Fatal(err)
			}
			// A state poll recorded from now on sleeps its 3 seconds.
			before := len(fakeclaude.Calls(t, f.claudeLog))
			for deadline := time.Now().Add(5 * time.Second); len(fakeclaude.Calls(t, f.claudeLog)) == before; time.Sleep(10 * time.Millisecond) {
				if time.Now().After(deadline) {
					t.Fatal("no state poll after the list turned slow")
				}
			}
			start := time.Now()
			add()
			for deadline := time.After(time.Second); ; {
				select {
				case e := <-f.events:
					var ev protocol.ChatItemsEvent
					if e.Method != protocol.EventChatItems || json.Unmarshal(e.Body, &ev) != nil {
						continue
					}
					for _, it := range ev.Items {
						if it.Text == text {
							t.Logf("pushed after %v", time.Since(start))
							return
						}
					}
				case <-deadline:
					t.Fatal("the new record was not pushed within 1 s while claude agents ran")
				}
			}
		})
	}
}
