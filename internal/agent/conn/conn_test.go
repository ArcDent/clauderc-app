package conn_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/agent"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/conn"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

const enrollKey = "enroll-key-for-tests"

// testRelay is a relay on an httptest TLS server that records when agents
// dial in.
type testRelay struct {
	srv  *relay.Server
	http *httptest.Server

	mu    sync.Mutex
	dials []time.Time
	conns []net.Conn
}

// Accept records each TCP connection so a test can cut them all.
type recordingListener struct {
	net.Listener
	r *testRelay
}

func (l recordingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err == nil {
		l.r.mu.Lock()
		l.r.conns = append(l.r.conns, c)
		l.r.mu.Unlock()
	}
	return c, err
}

// cut closes every connection to the relay, as a network change would.
func (r *testRelay) cut() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, c := range r.conns {
		c.Close()
	}
	r.conns = nil
}

func startRelay(t *testing.T) *testRelay {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	r := &testRelay{srv: relay.NewServer(relay.Options{
		EnrollKey:         enrollKey,
		PublicURL:         "wss://relay.example.com",
		Store:             st,
		HeartbeatInterval: 50 * time.Millisecond,
		HeartbeatTimeout:  time.Second,
	})}
	r.http = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == protocol.PathAgentWS {
			r.mu.Lock()
			r.dials = append(r.dials, time.Now())
			r.mu.Unlock()
		}
		r.srv.ServeHTTP(w, req)
	}))
	r.http.Listener = recordingListener{Listener: r.http.Listener, r: r}
	r.http.StartTLS()
	t.Cleanup(func() {
		r.http.Close()
		r.srv.Close()
	})
	return r
}

func (r *testRelay) dialTimes() []time.Time {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]time.Time(nil), r.dials...)
}

func (r *testRelay) online(id string) bool {
	for _, m := range r.srv.Machines() {
		if m.ID == id {
			return m.Online
		}
	}
	return false
}

// post sends a JSON request to the relay's HTTP API and decodes the reply.
func (r *testRelay) post(t *testing.T, path, bearer string, in, out any) {
	t.Helper()
	body, _ := json.Marshal(in)
	req, _ := http.NewRequest(http.MethodPost, r.http.URL+path, bytes.NewReader(body))
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	res, err := r.http.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("POST %s: %s", path, res.Status)
	}
	if err := json.NewDecoder(res.Body).Decode(out); err != nil {
		t.Fatal(err)
	}
}

// phone pairs a device and opens its /ws/app connection.
func (r *testRelay) phone(t *testing.T) *websocket.Conn {
	t.Helper()
	var code protocol.PairCodeResponse
	r.post(t, protocol.PathAdminPairCodes, enrollKey, struct{}{}, &code)
	var pair protocol.PairResponse
	r.post(t, protocol.PathPair, "", protocol.PairRequest{Code: code.Code, DeviceName: "test"}, &pair)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(r.http.URL, "https")+protocol.PathAppWS, &websocket.DialOptions{
		HTTPClient: r.http.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + pair.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ws.CloseNow() })
	return ws
}

// echo answers test.echo with the client id after pushing an event to
// that client, and records what the connection layer tells it.
type echo struct {
	push agent.Pusher

	mu       sync.Mutex
	gone     []string
	allGone  int
	requests int
}

func (e *echo) Methods() []protocol.Method { return []protocol.Method{"test.echo"} }

func (e *echo) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	e.mu.Lock()
	e.requests++
	e.mu.Unlock()
	_ = agent.PushEvent(e.push, req.Client, "test.before", struct{}{})
	return agent.Reply(req, map[string]string{"client": req.Client})
}

func (e *echo) ClientGone(client string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.gone = append(e.gone, client)
}

func (e *echo) AllClientsGone() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.allGone++
}

func (e *echo) snapshot() (gone []string, allGone int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]string(nil), e.gone...), e.allGone
}

// runConn starts a Conn against r with the echo handler, then more.
func runConn(t *testing.T, r *testRelay, edit func(*conn.Options), more ...agent.Handler) *echo {
	t.Helper()
	opts := conn.Options{
		RelayURL:          r.http.URL,
		EnrollKey:         enrollKey,
		MachineID:         "m-1",
		Name:              "测试机",
		HTTPClient:        r.http.Client(),
		MinBackoff:        20 * time.Millisecond,
		MaxBackoff:        100 * time.Millisecond,
		HeartbeatInterval: 50 * time.Millisecond,
		HeartbeatTimeout:  time.Second,
	}
	if edit != nil {
		edit(&opts)
	}
	c, err := conn.New(opts)
	if err != nil {
		t.Fatal(err)
	}
	e := &echo{push: c}
	if err := c.Register(append([]agent.Handler{e}, more...)...); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); c.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-done })
	return e
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func read(t *testing.T, ws *websocket.Conn) protocol.Envelope {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, data, err := ws.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	env, err := protocol.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	return env
}

func send(t *testing.T, ws *websocket.Conn, env protocol.Envelope) {
	t.Helper()
	data, err := protocol.Encode(env)
	if err != nil {
		t.Fatal(err)
	}
	if err := ws.Write(context.Background(), websocket.MessageText, data); err != nil {
		t.Fatal(err)
	}
}

func TestNewRefusesPlainURLs(t *testing.T) {
	for _, u := range []string{"http://relay.example.com", "ws://relay.example.com", "relay.example.com"} {
		if _, err := conn.New(conn.Options{RelayURL: u, MachineID: "m-1"}); err == nil {
			t.Errorf("New accepted %q", u)
		}
	}
}

func TestRequestsEventsAndClientGone(t *testing.T) {
	r := startRelay(t)
	e := runConn(t, r, nil)
	eventually(t, "machine online", func() bool { return r.online("m-1") })
	ws := r.phone(t)

	send(t, ws, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-1", Method: "test.echo", Machine: "m-1", Body: json.RawMessage(`{}`)})
	evt := read(t, ws)
	res := read(t, ws)
	if evt.Kind != protocol.KindEvent || evt.Method != "test.before" {
		t.Fatalf("first message = %+v, want the pushed event", evt)
	}
	var body map[string]string
	if err := json.Unmarshal(res.Body, &body); err != nil || res.ID != "r-1" || res.Error != nil || body["client"] == "" {
		t.Fatalf("response = %+v (%v)", res, err)
	}
	client := body["client"]
	if evt.Client != client || res.Machine != "m-1" {
		t.Errorf("event client %q, response machine %q; want %q, m-1", evt.Client, res.Machine, client)
	}

	send(t, ws, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-2", Method: "nope.method", Machine: "m-1", Body: json.RawMessage(`{}`)})
	if res := read(t, ws); res.ID != "r-2" || res.Error == nil || res.Error.Code != protocol.CodeUnknownMethod {
		t.Errorf("unknown method answered %+v", res)
	}

	ws.Close(websocket.StatusNormalClosure, "bye")
	eventually(t, "client.gone", func() bool {
		gone, _ := e.snapshot()
		return len(gone) == 1 && gone[0] == client
	})
}

func TestReconnectBackoff(t *testing.T) {
	r := startRelay(t)
	runConn(t, r, func(o *conn.Options) {
		o.EnrollKey = "wrong"
		o.MinBackoff = 40 * time.Millisecond
		o.MaxBackoff = 160 * time.Millisecond
	})
	eventually(t, "seven dial attempts", func() bool { return len(r.dialTimes()) >= 7 })
	dials := r.dialTimes()
	want := []time.Duration{40, 80, 160, 160, 160, 160}
	for i, w := range want {
		gap := dials[i+1].Sub(dials[i])
		w *= time.Millisecond
		if gap < w*8/10 || gap > w+150*time.Millisecond {
			t.Errorf("pause %d = %v, want about %v", i+1, gap, w)
		}
	}
	if r.online("m-1") {
		t.Error("a wrong enroll key got the machine online")
	}
}

// After the connection drops the agent reconnects, forgetting every phone
// each time.
func TestReconnectAfterDrop(t *testing.T) {
	r := startRelay(t)
	e := runConn(t, r, nil)
	eventually(t, "machine online", func() bool { return r.online("m-1") })
	_, before := e.snapshot()

	r.cut()
	eventually(t, "the agent to dial again", func() bool { return len(r.dialTimes()) >= 2 })
	eventually(t, "machine online again", func() bool { return r.online("m-1") })
	if _, after := e.snapshot(); after < before+2 {
		t.Errorf("AllClientsGone ran %d more times, want at least 2 (drop and reconnect)", after-before)
	}
}

// runUntilExit runs a Conn with opts in the background and returns what Run
// returns.
func runUntilExit(t *testing.T, opts conn.Options) <-chan error {
	t.Helper()
	opts.MachineID, opts.EnrollKey = "m-1", enrollKey
	opts.MinBackoff, opts.MaxBackoff = 20*time.Millisecond, 100*time.Millisecond
	c, err := conn.New(opts)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done, exited := make(chan error, 1), make(chan struct{})
	go func() {
		done <- c.Run(ctx)
		close(exited)
	}()
	t.Cleanup(func() { cancel(); <-exited })
	return done
}

// Refused because the same machine id is alive elsewhere, the agent stops
// instead of retrying; any other refusal is still retried.
func TestMachineInUseStops(t *testing.T) {
	r := startRelay(t)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	other, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(r.http.URL, "https")+protocol.PathAgentWS+"?id=m-1", &websocket.DialOptions{
		HTTPClient: r.http.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + enrollKey}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer other.CloseNow()
	other.CloseRead(context.Background()) // answer pings
	eventually(t, "machine online", func() bool { return r.online("m-1") })
	before := len(r.dialTimes())

	done := runUntilExit(t, conn.Options{RelayURL: r.http.URL, HTTPClient: r.http.Client()})
	select {
	case err := <-done:
		if !errors.Is(err, conn.ErrMachineInUse) {
			t.Fatalf("Run returned %v, want ErrMachineInUse", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Run kept going after machineInUse")
	}
	if n := len(r.dialTimes()) - before; n != 1 {
		t.Errorf("agent dialed %d times, want 1", n)
	}

	// A 409 with another code is an ordinary failure.
	var dials atomic.Int32
	conflict := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		dials.Add(1)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(protocol.Error{Code: protocol.CodeInternal, Message: "x"})
	}))
	defer conflict.Close()
	runUntilExit(t, conn.Options{RelayURL: conflict.URL, HTTPClient: conflict.Client()})
	eventually(t, "three dial attempts", func() bool { return dials.Load() >= 3 })
}

// A phone that leaves while one of its chats waits for a usage request
// does not hold up the connection: the read loop handles the next message
// within 100 ms.
func TestClientGoneWhileChatWaitsForUsage(t *testing.T) {
	const sid = "8f0c2a5e-0000-4000-8000-0000000000c1"
	sample := t.TempDir()
	fakeclaude.Setup(t, sample)
	agents := `[{"pid": null, "id": "abc12345", "cwd": "/w/x", "kind": "background", "startedAt": 1794529871000, "sessionId": "` + sid + `", "name": "t", "status": "idle", "state": "done"}]`
	if err := os.WriteFile(filepath.Join(sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	// The limit message gives no reset time Go can read, so the open chat
	// asks usage.get for it.
	limit, _ := json.Marshal(map[string]any{
		"type": "assistant", "isApiErrorMessage": true, "error": "rate_limit", "timestamp": "2026-11-19T02:00:00Z",
		"message": map[string]any{"model": "<synthetic>", "role": "assistant", "content": []any{map[string]any{"type": "text", "text": "You've hit your 5-hour limit · resets soon (Mars/Base)"}}},
	})
	proj := filepath.Join(sample, "projects", "-w-x")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(proj, sid+".jsonl"), append(limit, '\n'), 0o600); err != nil {
		t.Fatal(err)
	}
	asked, answer := make(chan struct{}), make(chan struct{})
	var once sync.Once
	chats := chat.New(chat.Options{
		Source:        discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: sample},
		Push:          agent.PusherFunc(func(protocol.Envelope) {}),
		StateInterval: 50 * time.Millisecond,
		Usage: func(context.Context) protocol.UsageResponse {
			once.Do(func() { close(asked) })
			<-answer
			return protocol.UsageResponse{}
		},
	})
	t.Cleanup(chats.Close)
	r := startRelay(t)
	e := runConn(t, r, nil, chats)
	t.Cleanup(func() { close(answer) })
	eventually(t, "machine online", func() bool { return r.online("m-1") })

	leaving, staying := r.phone(t), r.phone(t)
	send(t, leaving, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-open", Method: protocol.MethodChatOpen, Machine: "m-1", Body: json.RawMessage(`{"sessionId":"abc12345"}`)})
	if res := read(t, leaving); res.ID != "r-open" || res.Error != nil {
		t.Fatalf("chat.open answered %+v", res)
	}
	select {
	case <-asked:
	case <-time.After(5 * time.Second):
		t.Fatal("the open chat never asked usage.get")
	}
	leaving.Close(websocket.StatusNormalClosure, "bye")
	// echo is told first; the chat handler next.
	eventually(t, "client.gone", func() bool {
		gone, _ := e.snapshot()
		return len(gone) == 1
	})

	start := time.Now()
	send(t, staying, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-next", Method: "test.echo", Machine: "m-1", Body: json.RawMessage(`{}`)})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for {
		_, data, err := staying.Read(ctx)
		if err != nil {
			t.Fatalf("no answer to the next message within 1 s: %v", err)
		}
		if env, err := protocol.Decode(data); err == nil && env.ID == "r-next" {
			break
		}
	}
	if d := time.Since(start); d > 100*time.Millisecond {
		t.Fatalf("the next message was answered after %v, want within 100 ms", d)
	}
}

// A relay that sends nothing for HeartbeatTimeout, not even a pong to the
// agent's ping, is dropped and dialed again, though the agent's next ping
// is not due yet; while it still pings, the agent keeps it.
func TestSilentRelayDropped(t *testing.T) {
	const timeout = 500 * time.Millisecond
	var (
		mu      sync.Mutex
		conns   []*websocket.Conn
		accepts []time.Time
		pinging atomic.Bool
	)
	pinging.Store(true)
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		ws, err := websocket.Accept(w, req, nil)
		if err != nil {
			return
		}
		mu.Lock()
		conns, accepts = append(conns, ws), append(accepts, time.Now())
		mu.Unlock()
		// It never reads, so no pong reaches its Ping, which still sends
		// one ping each time.
		for pinging.Load() {
			ctx, cancel := context.WithTimeout(context.Background(), timeout/5)
			ws.Ping(ctx)
			cancel()
		}
	}))
	t.Cleanup(func() {
		pinging.Store(false)
		mu.Lock()
		for _, c := range conns {
			c.CloseNow()
		}
		mu.Unlock()
		srv.Close()
	})
	dials := func() []time.Time {
		mu.Lock()
		defer mu.Unlock()
		return append([]time.Time(nil), accepts...)
	}
	runUntilExit(t, conn.Options{RelayURL: srv.URL, HTTPClient: srv.Client(), HeartbeatInterval: time.Minute, HeartbeatTimeout: timeout})
	eventually(t, "the first dial", func() bool { return len(dials()) == 1 })

	time.Sleep(3 * timeout)
	if n := len(dials()); n != 1 {
		t.Fatalf("agent dialed %d times while the relay pinged it, want 1", n)
	}
	pinging.Store(false)
	stopped := time.Now()
	eventually(t, "the agent to dial again", func() bool { return len(dials()) == 2 })
	// The last ping went out at most timeout/5 before stopped.
	if gap := dials()[1].Sub(stopped); gap < timeout-timeout/5 || gap > timeout+2*time.Second {
		t.Errorf("dialed again %v after the relay went silent, want about %v", gap, timeout)
	}
}

// heldConn waits for hold before each write, so a test can stall the
// agent's writer as a slow uplink would.
type heldConn struct {
	net.Conn
	hold *sync.RWMutex
}

func (c heldConn) Write(b []byte) (int, error) {
	c.hold.RLock()
	c.hold.RUnlock()
	return c.Conn.Write(b)
}

// An event that finds the send queue full drops the connection rather
// than itself: nothing pushed after it reaches the phone on that
// connection, and once the machine is back the phone opens the chat again
// and gets the permission request whose chat.pending it missed.
func TestFullQueueReconnects(t *testing.T) {
	const sid = "8f0c2a5e-0000-4000-8000-0000000000c1"
	sample := t.TempDir()
	fakeclaude.Setup(t, sample)
	// The session runs, blocked; its tool call shows up in the transcript
	// while the queue is full.
	agents := `[{"pid": 7, "id": "abc12345", "cwd": "/w/x", "kind": "background", "startedAt": 1794529871000, "sessionId": "` + sid + `", "name": "t", "status": "waiting", "state": "blocked"}]`
	if err := os.WriteFile(filepath.Join(sample, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	proj := filepath.Join(sample, "projects", "-w-x")
	if err := os.MkdirAll(proj, 0o700); err != nil {
		t.Fatal(err)
	}
	appendLine := func(line string) {
		t.Helper()
		f, err := os.OpenFile(filepath.Join(proj, sid+".jsonl"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		if err != nil {
			t.Fatal(err)
		}
		defer f.Close()
		if _, err := f.WriteString(line + "\n"); err != nil {
			t.Fatal(err)
		}
	}
	appendLine(`{"type":"user","message":{"role":"user","content":"建个文件"}}`)

	var relayed atomic.Value // the Conn once it runs
	pendingPushed := make(chan struct{}, 1)
	chats := chat.New(chat.Options{
		Source: discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: sample},
		Push: agent.PusherFunc(func(evt protocol.Envelope) {
			if p, ok := relayed.Load().(agent.Pusher); ok {
				p.Push(evt)
			}
			if evt.Method == protocol.EventChatPending {
				select {
				case pendingPushed <- struct{}{}:
				default:
				}
			}
		}),
		PollInterval: 20 * time.Millisecond,
	})
	t.Cleanup(chats.Close)

	r := startRelay(t)
	var hold sync.RWMutex
	tr := r.http.Client().Transport.(*http.Transport).Clone()
	tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		c, err := new(net.Dialer).DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		return heldConn{Conn: c, hold: &hold}, nil
	}
	e := runConn(t, r, func(o *conn.Options) { o.HTTPClient = &http.Client{Transport: tr} }, chats)
	relayed.Store(e.push)
	eventually(t, "machine online", func() bool { return r.online("m-1") })
	ws := r.phone(t)
	openChat := func(id string) protocol.ChatOpenResponse {
		t.Helper()
		send(t, ws, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: id, Method: protocol.MethodChatOpen, Machine: "m-1", Body: json.RawMessage(`{"sessionId":"abc12345"}`)})
		for {
			res := read(t, ws)
			if res.ID != id {
				continue
			}
			var body protocol.ChatOpenResponse
			if res.Error != nil || json.Unmarshal(res.Body, &body) != nil {
				t.Fatalf("chat.open answered %+v", res)
			}
			return body
		}
	}
	if res := openChat("r-open"); res.Pending != nil {
		t.Fatalf("pending %+v before the tool call", res.Pending)
	}

	hold.Lock()
	release := sync.OnceFunc(hold.Unlock)
	defer release()
	// The writer is stuck on its first write; this is more than the 256
	// messages the queue holds. The client is gone, so none would reach
	// the phone anyway.
	for i := range 300 {
		if err := agent.PushEvent(e.push, "c-gone", "test.flood", i); err != nil {
			t.Fatal(err)
		}
	}
	appendLine(`{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_p1","name":"Bash","input":{"command":"touch x"}}]}}`)
	select {
	case <-pendingPushed:
	case <-time.After(5 * time.Second):
		t.Fatal("the chat never pushed chat.pending")
	}
	release()
	// The chat pushes chat.items for this after the missed chat.pending.
	appendLine(`{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"等你确认"}]}}`)

	for {
		env := read(t, ws)
		if env.Method != protocol.EventMachineStatus {
			t.Fatalf("%s reached the phone on the connection that dropped an event", env.Method)
		}
		var m protocol.Machine
		if json.Unmarshal(env.Body, &m) == nil && m.ID == "m-1" && m.Online {
			break
		}
	}
	if n := len(r.dialTimes()); n < 2 {
		t.Fatalf("agent dialed %d times, want a reconnect", n)
	}
	res := openChat("r-reopen")
	if res.State != protocol.StatePending || res.Pending == nil || res.Pending.ToolUseID != "toolu_p1" {
		t.Fatalf("reopened chat: state %q pending %+v, want the permission request", res.State, res.Pending)
	}
}

type pacer struct {
	mu          sync.Mutex
	next        time.Time
	bytesPerSec int64
}

func (p *pacer) wait(n int) {
	if n <= 0 || p.bytesPerSec <= 0 {
		return
	}
	dur := time.Duration(int64(n) * int64(time.Second) / p.bytesPerSec)
	p.mu.Lock()
	now := time.Now()
	if p.next.Before(now) {
		p.next = now
	}
	p.next = p.next.Add(dur)
	target := p.next
	p.mu.Unlock()
	if d := time.Until(target); d > 0 {
		time.Sleep(d)
	}
}

type rateConn struct {
	net.Conn
	r, w pacer
}

func (c *rateConn) Read(b []byte) (int, error) {
	if len(b) > 64<<10 {
		b = b[:64<<10]
	}
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.r.wait(n)
	}
	return n, err
}

func (c *rateConn) Write(b []byte) (int, error) {
	total := 0
	for len(b) > 0 {
		chunk := b[:min(len(b), 64<<10)]
		c.w.wait(len(chunk))
		n, err := c.Conn.Write(chunk)
		total += n
		if err != nil {
			return total, err
		}
		b = b[n:]
	}
	return total, nil
}

type largeHandler struct{}

func (largeHandler) Methods() []protocol.Method { return []protocol.Method{"test.large"} }

func (largeHandler) Handle(_ context.Context, req protocol.Envelope) protocol.Envelope {
	return protocol.Envelope{
		V:       protocol.Version,
		Kind:    protocol.KindResponse,
		ID:      req.ID,
		Method:  req.Method,
		Machine: req.Machine,
		Client:  req.Client,
		Body:    req.Body,
	}
}

// Sending and receiving a 14 MB message over a rate-limited agent link taking
// longer than HeartbeatTimeout while pings run keeps the single connection
// alive and delivers the full payload intact.
func TestLargeMessageOnRateLimitedConnWithPings(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	r := &testRelay{srv: relay.NewServer(relay.Options{
		EnrollKey:         enrollKey,
		PublicURL:         "wss://relay.example.com",
		Store:             st,
		HeartbeatInterval: 250 * time.Millisecond,
		HeartbeatTimeout:  1200 * time.Millisecond,
	})}
	r.http = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == protocol.PathAgentWS {
			r.mu.Lock()
			r.dials = append(r.dials, time.Now())
			r.mu.Unlock()
		}
		r.srv.ServeHTTP(w, req)
	}))
	r.http.Listener = recordingListener{Listener: r.http.Listener, r: r}
	r.http.StartTLS()
	t.Cleanup(func() {
		r.http.Close()
		r.srv.Close()
	})

	// Throttle only the agent<->relay TCP link to ~4.5 MB/s (~3.1s per 14 MB, > 1.2s HeartbeatTimeout).
	tr := r.http.Client().Transport.(*http.Transport).Clone()
	tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		nc, err := new(net.Dialer).DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		return &rateConn{
			Conn: nc,
			r:    pacer{bytesPerSec: 4_500_000},
			w:    pacer{bytesPerSec: 4_500_000},
		}, nil
	}
	runConn(t, r, func(o *conn.Options) {
		o.HTTPClient = &http.Client{Transport: tr}
		o.HeartbeatInterval = 250 * time.Millisecond
		o.HeartbeatTimeout = 1200 * time.Millisecond
	}, largeHandler{})
	eventually(t, "machine online", func() bool { return r.online("m-1") })

	ws := r.phone(t)
	ws.SetReadLimit(protocol.MaxMessageBytes)

	payload := `{"blob":"` + strings.Repeat("Z", 14<<20) + `"}`
	send(t, ws, protocol.Envelope{
		V:       protocol.Version,
		Kind:    protocol.KindRequest,
		ID:      "r-14m",
		Method:  "test.large",
		Machine: "m-1",
		Body:    json.RawMessage(payload),
	})

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for {
		_, data, err := ws.Read(ctx)
		if err != nil {
			t.Fatalf("read 14MB reply: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil {
			t.Fatal(err)
		}
		if env.ID != "r-14m" {
			continue
		}
		if env.Error != nil {
			t.Fatalf("14MB reply error: %+v", env.Error)
		}
		if string(env.Body) != payload {
			t.Fatalf("14MB reply len = %d, want %d", len(env.Body), len(payload))
		}
		break
	}
	if n := len(r.dialTimes()); n != 1 {
		t.Fatalf("agent dialed %d times during 14MB transfer, want 1", n)
	}
	// Follow-up request still works on the same connection.
	send(t, ws, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r-ok", Method: "test.echo", Machine: "m-1", Body: json.RawMessage(`{}`)})
	for {
		_, data, err := ws.Read(ctx)
		if err != nil {
			t.Fatalf("follow-up read: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil {
			t.Fatal(err)
		}
		if env.ID == "r-ok" {
			if env.Error != nil {
				t.Fatalf("follow-up error: %+v", env.Error)
			}
			break
		}
	}
}
