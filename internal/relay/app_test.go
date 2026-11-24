package relay_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

// wsClient reads in the background so pings are answered and a test can
// wait for, or rule out, messages without cancelling a Read.
type wsClient struct {
	t      *testing.T
	conn   *websocket.Conn
	msgs   chan protocol.Envelope
	closed chan error
}

func newClient(t *testing.T, c *websocket.Conn) *wsClient {
	w := &wsClient{t: t, conn: c, msgs: make(chan protocol.Envelope, 32), closed: make(chan error, 1)}
	go func() {
		for {
			_, data, err := c.Read(context.Background())
			if err != nil {
				w.closed <- err
				return
			}
			env, err := protocol.Decode(data)
			if err != nil {
				w.closed <- err
				return
			}
			w.msgs <- env
		}
	}()
	return w
}

func (w *wsClient) send(env protocol.Envelope) {
	w.t.Helper()
	raw, err := protocol.Encode(env)
	if err != nil {
		w.t.Fatal(err)
	}
	if err := w.conn.Write(context.Background(), websocket.MessageText, raw); err != nil {
		w.t.Fatalf("write: %v", err)
	}
}

func (w *wsClient) next() protocol.Envelope {
	w.t.Helper()
	select {
	case env := <-w.msgs:
		return env
	case err := <-w.closed:
		w.t.Fatalf("connection closed: %v", err)
	case <-time.After(2 * time.Second):
		w.t.Fatal("timed out waiting for a message")
	}
	return protocol.Envelope{}
}

func (w *wsClient) expectNone(d time.Duration) {
	w.t.Helper()
	select {
	case env := <-w.msgs:
		w.t.Fatalf("unexpected message: %+v", env)
	case <-time.After(d):
	}
}

// waitClosed fails unless the relay closes the connection within d.
func (w *wsClient) waitClosed(d time.Duration) error {
	w.t.Helper()
	deadline := time.After(d)
	for {
		select {
		case <-w.msgs:
		case err := <-w.closed:
			return err
		case <-deadline:
			w.t.Fatalf("connection still open after %v", d)
		}
	}
}

func (r *testRelay) agent(t *testing.T, id string) *wsClient {
	t.Helper()
	c, status := r.dial(t, "/ws/agent?id="+id+"&name="+id, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("agent %s: status %d", id, status)
	}
	w := newClient(t, c)
	eventually(t, time.Second, id+" online", func() bool {
		m, ok := r.machine(id)
		return ok && m.Online
	})
	return w
}

func (r *testRelay) pairDevice(t *testing.T, id, token string) {
	t.Helper()
	if err := r.store.AddDevice(store.Device{ID: id, Name: id, CreatedAt: time.Now()}, token); err != nil {
		t.Fatal(err)
	}
}

func (r *testRelay) phone(t *testing.T, token string) *wsClient {
	t.Helper()
	c, status := r.dial(t, "/ws/app", token)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("phone: status %d", status)
	}
	return newClient(t, c)
}

func TestAppInvalidTokenRejected(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "good-token")
	for name, tok := range map[string]string{"wrong": "bad-token", "missing": "", "enroll key": enrollKey} {
		t.Run(name, func(t *testing.T) {
			if _, status := r.dial(t, "/ws/app", tok); status != http.StatusUnauthorized {
				t.Fatalf("status = %d, want 401", status)
			}
		})
	}
}

func TestAppForwardsByMachineAndClient(t *testing.T) {
	r := startRelay(t, nil)
	a := r.agent(t, "m-a")
	b := r.agent(t, "m-b")
	r.pairDevice(t, "d-1", "tok-1")
	r.pairDevice(t, "d-2", "tok-2")
	p1 := r.phone(t, "tok-1")
	p2 := r.phone(t, "tok-2")

	body := json.RawMessage(`{"sessionId":"a1","text":"hi"}`)
	p1.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-1", Method: protocol.MethodChatSend, Machine: "m-a", Body: body})
	got := a.next()
	if got.Kind != protocol.KindRequest || got.ID != "r-1" || got.Method != protocol.MethodChatSend ||
		got.Machine != "m-a" || string(got.Body) != string(body) {
		t.Fatalf("agent A got %+v", got)
	}
	client1 := got.Client
	if client1 == "" {
		t.Fatal("forwarded request has no client")
	}

	p2.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-2", Method: protocol.MethodSessionsSubscribe, Machine: "m-a", Body: json.RawMessage(`{}`)})
	if got := a.next(); got.Client == "" || got.Client == client1 {
		t.Fatalf("second phone client = %q, want non-empty and != %q", got.Client, client1)
	}
	b.expectNone(100 * time.Millisecond)

	a.send(protocol.Envelope{V: 1, Kind: protocol.KindResponse, ID: "r-1", Method: protocol.MethodChatSend, Client: client1, Body: json.RawMessage(`{}`)})
	res := p1.next()
	if res.Kind != protocol.KindResponse || res.ID != "r-1" || res.Machine != "m-a" {
		t.Fatalf("phone 1 got %+v", res)
	}
	a.send(protocol.Envelope{V: 1, Kind: protocol.KindEvent, Method: protocol.EventChatState, Client: client1, Body: json.RawMessage(`{"sessionId":"a1","state":"idle"}`)})
	if evt := p1.next(); evt.Kind != protocol.KindEvent || evt.Method != protocol.EventChatState || evt.Machine != "m-a" {
		t.Fatalf("phone 1 got %+v", evt)
	}
	p2.expectNone(100 * time.Millisecond)

	p1.conn.Close(websocket.StatusNormalClosure, "")
	gone := a.next()
	if gone.Kind != protocol.KindEvent || gone.Method != protocol.EventClientGone || gone.Client != client1 {
		t.Fatalf("agent A got %+v, want client.gone for %s", gone, client1)
	}
}

func TestAppRequestToOfflineMachine(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-9", Method: protocol.MethodChatOpen, Machine: "m-x", Body: json.RawMessage(`{"sessionId":"a1"}`)})
	res := p.next()
	if res.Kind != protocol.KindResponse || res.ID != "r-9" || res.Method != protocol.MethodChatOpen || res.Machine != "m-x" {
		t.Fatalf("got %+v", res)
	}
	if res.Error == nil || res.Error.Code != protocol.CodeMachineOffline {
		t.Fatalf("error = %+v, want machineOffline", res.Error)
	}
}

// A machine whose send queue is full keeps its connection, which every
// phone shares: only the request that does not fit fails, answered to the
// phone that sent it.
func TestAppRequestToBusyMachine(t *testing.T) {
	r := startRelay(t, func(o *relay.Options) {
		// The stalled agent must not be dropped for missing pongs.
		o.HeartbeatInterval = time.Minute
		o.HeartbeatTimeout = time.Minute
	})
	conn, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("agent: status %d", status)
	}
	conn.SetReadLimit(protocol.MaxMessageBytes)
	eventually(t, time.Second, "m-1 online", func() bool {
		m, ok := r.machine("m-1")
		return ok && m.Online
	})
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")

	// The agent reads nothing yet. A message larger than the socket buffers
	// stalls the relay's writer to it, then small ones fill the queue.
	big := json.RawMessage(`{"sessionId":"a1","text":"` + strings.Repeat("x", 10<<20) + `"}`)
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-big", Method: protocol.MethodChatSend, Machine: "m-1", Body: big})
	var refused protocol.Envelope
fill:
	for i := 0; ; i++ {
		if i == 500 {
			t.Fatal("the queue to the agent never filled")
		}
		p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: fmt.Sprintf("r-%d", i), Method: protocol.MethodChatSend, Machine: "m-1", Body: json.RawMessage(`{"sessionId":"a1","text":"hi"}`)})
		select {
		case refused = <-p.msgs:
			break fill
		case err := <-p.closed:
			t.Fatalf("phone connection closed: %v", err)
		case <-time.After(5 * time.Millisecond):
		}
	}
	if refused.Kind != protocol.KindResponse || !strings.HasPrefix(refused.ID, "r-") || refused.Method != protocol.MethodChatSend ||
		refused.Machine != "m-1" || refused.Error == nil || *refused.Error != (protocol.Error{Code: protocol.CodeMachineBusy, Message: "目标机暂时忙不过来，请稍后重试"}) {
		t.Fatalf("phone got %+v, want the request refused as machineBusy", refused)
	}
	if m, _ := r.machine("m-1"); !m.Online {
		t.Fatal("m-1 went offline")
	}

	// Once the agent reads again, the connection works both ways.
	agent := newClient(t, conn)
	var req protocol.Envelope
	select {
	case req = <-agent.msgs:
	case err := <-agent.closed:
		t.Fatalf("agent connection closed: %v", err)
	case <-time.After(10 * time.Second):
		t.Fatal("the agent got nothing")
	}
	if req.ID != "r-big" {
		t.Fatalf("agent got %s first, want r-big", req.ID)
	}
	agent.send(protocol.Envelope{V: 1, Kind: protocol.KindResponse, ID: "r-big", Method: protocol.MethodChatSend, Client: req.Client, Body: json.RawMessage(`{}`)})
	deadline := time.After(10 * time.Second)
	for {
		select {
		case env := <-p.msgs:
			if env.ID == "r-big" {
				if env.Error != nil {
					t.Fatalf("r-big answered %+v", env.Error)
				}
				return
			}
			if env.Error == nil || env.Error.Code != protocol.CodeMachineBusy {
				t.Fatalf("phone got %+v", env)
			}
		case <-deadline:
			t.Fatal("the agent's answer never reached the phone")
		}
	}
}
