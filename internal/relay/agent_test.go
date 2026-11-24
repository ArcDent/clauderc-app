package relay_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

func TestAgentCorrectKeyGoesOnline(t *testing.T) {
	r := startRelay(t, nil)
	c, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("status = %d, want upgrade", status)
	}
	c.CloseRead(context.Background()) // answer pings
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online && m.Name == "mac-mini"
	})

	// Still online well past the heartbeat timeout while pongs flow.
	time.Sleep(400 * time.Millisecond)
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 went offline although it answers pings")
	}

	reopened, err := store.Open(r.path)
	if err != nil {
		t.Fatal(err)
	}
	ms := reopened.Machines()
	if len(ms) != 1 || ms[0].ID != "m-1" || ms[0].Name != "mac-mini" ||
		ms[0].AgentVersion != "0.1.0" || ms[0].ClaudeVersion != "2.1.323" {
		t.Fatalf("stored machines = %+v", ms)
	}
}

func TestAgentWrongKeyRejected(t *testing.T) {
	r := startRelay(t, nil)
	for name, key := range map[string]string{"wrong": "not-the-key", "missing": "", "prefix": enrollKey[:5]} {
		t.Run(name, func(t *testing.T) {
			if _, status := r.dial(t, agentQuery, key); status != http.StatusUnauthorized {
				t.Fatalf("status = %d, want 401", status)
			}
		})
	}
	if _, ok := r.machine("m-1"); ok {
		t.Fatal("rejected machine is listed")
	}
	if got := r.store.Machines(); len(got) != 0 {
		t.Fatalf("rejected machine stored: %+v", got)
	}
}

func TestAgentMissingIDRejected(t *testing.T) {
	r := startRelay(t, nil)
	if _, status := r.dial(t, "/ws/agent?name=mac-mini", enrollKey); status != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", status)
	}
}

func TestAgentHeartbeatTimeoutMarksOffline(t *testing.T) {
	r := startRelay(t, nil)
	// LastSeenAt is reported in whole seconds.
	before := time.Now().Truncate(time.Second)
	c, _ := r.dial(t, agentQuery, enrollKey)
	_ = c // never read, so pings go unanswered
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})

	var seen time.Time
	eventually(t, 2*time.Second, "m-1 offline", func() bool {
		got, ok := r.machine("m-1")
		seen = got.LastSeenAt
		return ok && !got.Online
	})
	if seen.Before(before) || seen.After(time.Now()) {
		t.Fatalf("LastSeenAt = %v, want between connect and now", seen)
	}

	reopened, err := store.Open(r.path)
	if err != nil {
		t.Fatal(err)
	}
	ms := reopened.Machines()
	if len(ms) != 1 || !ms[0].LastSeenAt.Equal(seen) {
		t.Fatalf("stored machines = %+v, want LastSeenAt %v", ms, seen)
	}
}

// A second agent with the same id is refused while the first still answers
// pings, so two copies of one config cannot keep replacing each other.
func TestAgentDuplicateRefusedWhileAlive(t *testing.T) {
	r := startRelay(t, nil)
	first, _ := r.dial(t, agentQuery, enrollKey)
	first.CloseRead(context.Background()) // answer pings
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	h := http.Header{"Authorization": {"Bearer " + enrollKey}}
	_, resp, err := websocket.Dial(ctx, r.wsURL(agentQuery), &websocket.DialOptions{HTTPHeader: h})
	if err == nil || resp == nil || resp.StatusCode != http.StatusConflict {
		t.Fatalf("second dial: err %v, resp %v; want 409", err, resp)
	}
	var body protocol.Error
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil || body.Code != protocol.CodeMachineInUse {
		t.Fatalf("body %+v (%v), want code %s", body, err, protocol.CodeMachineInUse)
	}

	if err := first.Ping(ctx); err != nil {
		t.Fatalf("first connection was closed: %v", err)
	}
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 went offline")
	}
}

// A connection that no longer answers pings, left behind by a network
// change or a killed agent, is replaced by the new one.
func TestAgentDuplicateReplacesDeadConnection(t *testing.T) {
	// Heartbeats would drop the dead connection on their own; keep them out
	// of the way so the probe, bounded by the timeout, decides.
	r := startRelay(t, func(o *relay.Options) {
		o.HeartbeatInterval = time.Minute
		o.HeartbeatTimeout = 300 * time.Millisecond
	})
	dead, _ := r.dial(t, agentQuery, enrollKey) // never read, so pings go unanswered
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})

	second, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("second dial status = %d, want upgrade", status)
	}
	second.CloseRead(context.Background())
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, _, err := dead.Read(ctx); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("dead connection read: %v, want closed as replaced", err)
	}
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 offline after the replacement")
	}
}

// slowHijack holds up a WebSocket upgrade between the relay's check for a
// connected agent and its registering the new one, and closes entered
// when it starts doing so.
type slowHijack struct {
	http.ResponseWriter
	entered chan struct{}
	delay   time.Duration
}

func (s slowHijack) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	close(s.entered)
	time.Sleep(s.delay)
	return http.NewResponseController(s.ResponseWriter).Hijack()
}

// Two connections of one machine arriving together are admitted one at a
// time, so the one left is the live one: a dead connection still being
// upgraded when a live one arrives cannot replace the live one afterwards.
func TestAgentConcurrentHandshakesKeepTheLiveOne(t *testing.T) {
	entered := make(chan struct{})
	r := startRelayWrapped(t, func(o *relay.Options) {
		// Heartbeats would drop the dead connection on their own.
		o.HeartbeatInterval = time.Minute
		o.HeartbeatTimeout = 300 * time.Millisecond
	}, func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			if req.URL.Query().Get("slow") != "" {
				w = slowHijack{ResponseWriter: w, entered: entered, delay: 300 * time.Millisecond}
			}
			next.ServeHTTP(w, req)
		})
	})
	type dialed struct {
		conn *websocket.Conn
		err  error
	}
	deadc := make(chan dialed, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		h := http.Header{"Authorization": {"Bearer " + enrollKey}}
		c, _, err := websocket.Dial(ctx, r.wsURL(agentQuery+"&slow=1"), &websocket.DialOptions{HTTPHeader: h})
		deadc <- dialed{c, err}
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("the first connection never reached its upgrade")
	}

	conn, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("live dial status = %d, want upgrade", status)
	}
	live := newClient(t, conn) // answers pings
	d := <-deadc
	if d.err != nil {
		t.Fatalf("dead dial: %v", d.err)
	}
	dead := d.conn // never read until now, so pings went unanswered
	t.Cleanup(func() { dead.CloseNow() })

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, _, err := dead.Read(ctx); websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("dead connection read: %v, want closed as replaced", err)
	}
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-1", Method: protocol.MethodSessionsSubscribe, Machine: "m-1", Body: json.RawMessage(`{}`)})
	if got := live.next(); got.ID != "r-1" {
		t.Fatalf("live connection got %+v, want the phone's request", got)
	}
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 offline")
	}
}

// The ping that decides whether the connected agent is alive has the
// relay's own timeout: the new connection's request being cancelled does
// not make a live agent look dead and get it replaced.
func TestAgentProbeOutlivesTheNewRequest(t *testing.T) {
	r := startRelayWrapped(t, nil, func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			if req.URL.Query().Get("cancelled") != "" {
				ctx, cancel := context.WithCancel(req.Context())
				cancel()
				req = req.WithContext(ctx)
			}
			next.ServeHTTP(w, req)
		})
	})
	first, _ := r.dial(t, agentQuery, enrollKey)
	first.CloseRead(context.Background()) // answer pings
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})

	if _, status := r.dial(t, agentQuery+"&cancelled=1", enrollKey); status != http.StatusConflict {
		t.Fatalf("second dial status = %d, want 409 while the first answers pings", status)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := first.Ping(ctx); err != nil {
		t.Fatalf("first connection was closed: %v", err)
	}
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 went offline")
	}
}

// After Close no new connection may start: it would outlive Close and
// write to the store behind its back.
func TestClosedServerRefusesConnections(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	r.srv.Close()
	if _, status := r.dial(t, agentQuery, enrollKey); status != http.StatusServiceUnavailable {
		t.Fatalf("agent after Close: status %d, want 503", status)
	}
	if _, status := r.dial(t, "/ws/app", "tok-1"); status != http.StatusServiceUnavailable {
		t.Fatalf("phone after Close: status %d, want 503", status)
	}
	if got := r.store.Machines(); len(got) != 0 {
		t.Fatalf("machine stored after Close: %+v", got)
	}
}

// Connections arriving while Close runs are either waited for or refused;
// nothing touches the store once Close has returned. Run with -race.
func TestCloseWhileConnecting(t *testing.T) {
	r := startRelay(t, nil)
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			h := http.Header{"Authorization": {"Bearer " + enrollKey}}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			c, _, err := websocket.Dial(ctx, r.wsURL(fmt.Sprintf("/ws/agent?id=m-%d", i)), &websocket.DialOptions{HTTPHeader: h})
			if err == nil {
				c.CloseRead(context.Background())
			}
		}()
	}
	time.Sleep(time.Millisecond)
	r.srv.Close()
	after := r.store.Machines()
	for _, m := range r.srv.Machines() {
		if m.Online {
			t.Fatalf("%s online after Close", m.ID)
		}
	}
	wg.Wait()
	time.Sleep(50 * time.Millisecond)
	if got := r.store.Machines(); len(got) != len(after) {
		t.Fatalf("store changed after Close: %d machines, then %d", len(after), len(got))
	}
}

func TestAgentReplacementBroadcastsNewConnID(t *testing.T) {
	r := startRelay(t, func(o *relay.Options) {
		o.HeartbeatInterval = time.Minute
		o.HeartbeatTimeout = 300 * time.Millisecond
	})
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")

	dead, _ := r.dial(t, agentQuery, enrollKey) // never reads, so probe sees it dead
	defer dead.CloseNow()
	firstEvt := p.next()
	if firstEvt.Method != protocol.EventMachineStatus {
		t.Fatalf("got %+v, want machine.status", firstEvt)
	}
	var first protocol.Machine
	if err := json.Unmarshal(firstEvt.Body, &first); err != nil {
		t.Fatal(err)
	}
	if !first.Online || first.ConnID == "" {
		t.Fatalf("first machine.status = %+v, want online with non-empty connId", first)
	}

	second, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("second dial status = %d, want upgrade", status)
	}
	second.CloseRead(context.Background())

	secondEvt := p.next()
	if secondEvt.Method != protocol.EventMachineStatus {
		t.Fatalf("got %+v, want machine.status", secondEvt)
	}
	var replaced protocol.Machine
	if err := json.Unmarshal(secondEvt.Body, &replaced); err != nil {
		t.Fatal(err)
	}
	if !replaced.Online || replaced.ConnID == "" || replaced.ConnID == first.ConnID {
		t.Fatalf("replacement machine.status = %+v, want online with connId != %q", replaced, first.ConnID)
	}
}

func TestAgentDisconnectFailsInflightRequests(t *testing.T) {
	r := startRelay(t, nil)
	a := r.agent(t, "m-1")
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")

	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-drop", Method: protocol.MethodChatOpen, Machine: "m-1", Body: json.RawMessage(`{"sessionId":"a1"}`)})
	if req := a.next(); req.ID != "r-drop" {
		t.Fatalf("agent got %+v, want r-drop", req)
	}
	start := time.Now()
	a.conn.Close(websocket.StatusNormalClosure, "bye")

	deadline := time.After(time.Second)
	for {
		select {
		case env := <-p.msgs:
			if env.Kind == protocol.KindEvent {
				continue
			}
			if env.Kind != protocol.KindResponse || env.ID != "r-drop" || env.Method != protocol.MethodChatOpen ||
				env.Machine != "m-1" || env.Error == nil || env.Error.Code != protocol.CodeMachineOffline {
				t.Fatalf("phone got %+v, want machineOffline for r-drop", env)
			}
			if elapsed := time.Since(start); elapsed > time.Second {
				t.Fatalf("took %v, want within 1s", elapsed)
			}
			return
		case err := <-p.closed:
			t.Fatalf("phone closed: %v", err)
		case <-deadline:
			t.Fatal("did not receive machineOffline within 1s after agent disconnect")
		}
	}
}

func TestAgentReplacementFailsQueuedInflightRequests(t *testing.T) {
	r := startRelay(t, func(o *relay.Options) {
		o.HeartbeatInterval = time.Minute
		o.HeartbeatTimeout = 300 * time.Millisecond
	})
	dead, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("dead agent status = %d", status)
	}
	defer dead.CloseNow()
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")

	// Send a large request first so the unreading TCP socket stalls writeLoop,
	// leaving r-q2 sitting unsent in the old connection's send queue.
	big := json.RawMessage(`{"sessionId":"a1","text":"` + string(bytes.Repeat([]byte("x"), 10<<20)) + `"}`)
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-q1", Method: protocol.MethodChatSend, Machine: "m-1", Body: big})
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-q2", Method: protocol.MethodTermOpen, Machine: "m-1", Body: json.RawMessage(`{"sessionId":"a1"}`)})
	// Give the relay a moment to read both from the phone and queue r-q2 behind r-q1.
	time.Sleep(100 * time.Millisecond)

	second, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("replacement dial status = %d", status)
	}
	second.CloseRead(context.Background())

	got := map[string]protocol.ErrorCode{}
	deadline := time.After(2 * time.Second)
	for len(got) < 2 {
		select {
		case env := <-p.msgs:
			if env.Kind == protocol.KindEvent {
				continue
			}
			if env.Kind != protocol.KindResponse || env.Error == nil {
				t.Fatalf("unexpected response %+v", env)
			}
			got[env.ID] = env.Error.Code
		case err := <-p.closed:
			t.Fatalf("phone closed: %v", err)
		case <-deadline:
			t.Fatalf("timed out waiting for failed requests, got %+v", got)
		}
	}
	if got["r-q1"] != protocol.CodeMachineOffline || got["r-q2"] != protocol.CodeMachineOffline {
		t.Fatalf("errors = %+v, want machineOffline for r-q1 and r-q2", got)
	}
}
