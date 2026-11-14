package conn_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/agent"
	"clauderc/internal/agent/conn"
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
	r.http = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path == protocol.PathAgentWS {
			r.mu.Lock()
			r.dials = append(r.dials, time.Now())
			r.mu.Unlock()
		}
		r.srv.ServeHTTP(w, req)
	}))
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

// runConn starts a Conn against r with the echo handler.
func runConn(t *testing.T, r *testRelay, edit func(*conn.Options)) *echo {
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
	if err := c.Register(e); err != nil {
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

// Another connection with the same machine id replaces ours on the relay;
// the agent reconnects, forgetting every phone each time.
func TestReconnectAfterDrop(t *testing.T) {
	r := startRelay(t)
	e := runConn(t, r, nil)
	eventually(t, "machine online", func() bool { return r.online("m-1") })
	_, before := e.snapshot()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	intruder, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(r.http.URL, "https")+protocol.PathAgentWS+"?id=m-1", &websocket.DialOptions{
		HTTPClient: r.http.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + enrollKey}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer intruder.CloseNow()

	eventually(t, "the agent to dial again", func() bool { return len(r.dialTimes()) >= 3 })
	eventually(t, "machine online again", func() bool { return r.online("m-1") })
	if _, after := e.snapshot(); after < before+2 {
		t.Errorf("AllClientsGone ran %d more times, want at least 2 (drop and reconnect)", after-before)
	}
}
