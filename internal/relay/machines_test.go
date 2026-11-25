package relay_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
)

func listMachines(t *testing.T, p *wsClient) (protocol.Envelope, map[string]protocol.Machine) {
	t.Helper()
	p.send(protocol.Envelope{V: 1, Kind: protocol.KindRequest, ID: "r-list", Method: protocol.MethodMachinesList, Body: json.RawMessage(`{}`)})
	res := p.next()
	if res.Kind != protocol.KindResponse || res.ID != "r-list" || res.Method != protocol.MethodMachinesList || res.Error != nil {
		t.Fatalf("machines.list response = %+v", res)
	}
	var body protocol.MachinesListResponse
	if err := json.Unmarshal(res.Body, &body); err != nil {
		t.Fatal(err)
	}
	byID := map[string]protocol.Machine{}
	for _, m := range body.Machines {
		byID[m.ID] = m
	}
	return res, byID
}

func TestMachinesListEmpty(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	res, _ := listMachines(t, r.phone(t, "tok-1"))
	assertJSON(t, res.Body, `{"machines":[]}`)
}

func TestMachinesListOnlineAndOffline(t *testing.T) {
	r := startRelay(t, nil)
	gone := r.agent(t, "m-gone")
	gone.conn.Close(websocket.StatusNormalClosure, "")
	eventually(t, time.Second, "m-gone offline", func() bool {
		m, _ := r.machine("m-gone")
		return !m.Online
	})
	c, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("status %d", status)
	}
	newClient(t, c)
	eventually(t, time.Second, "m-1 online", func() bool {
		m, _ := r.machine("m-1")
		return m.Online
	})

	r.pairDevice(t, "d-1", "tok-1")
	_, got := listMachines(t, r.phone(t, "tok-1"))
	if len(got) != 2 {
		t.Fatalf("machines = %+v, want m-gone and m-1", got)
	}
	m1 := got["m-1"]
	if !m1.Online || m1.Name != "mac-mini" || m1.AgentVersion != "0.1.0" || m1.ClaudeVersion != "2.1.323" {
		t.Fatalf("m-1 = %+v", m1)
	}
	g := got["m-gone"]
	if g.Online || g.LastSeenAt.IsZero() {
		t.Fatalf("m-gone = %+v, want offline with last seen time", g)
	}
}

func TestMachineStatusBroadcast(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	r.pairDevice(t, "d-2", "tok-2")
	phones := []*wsClient{r.phone(t, "tok-1"), r.phone(t, "tok-2")}

	c, status := r.dial(t, agentQuery, enrollKey)
	if status != http.StatusSwitchingProtocols {
		t.Fatalf("status %d", status)
	}
	newClient(t, c)
	for i, p := range phones {
		m := statusEvent(t, p)
		if m.ID != "m-1" || !m.Online || m.Name != "mac-mini" || m.ClaudeVersion != "2.1.323" {
			t.Fatalf("phone %d online event = %+v", i, m)
		}
	}

	c.Close(websocket.StatusNormalClosure, "")
	for i, p := range phones {
		m := statusEvent(t, p)
		if m.ID != "m-1" || m.Online || m.LastSeenAt.IsZero() {
			t.Fatalf("phone %d offline event = %+v", i, m)
		}
	}
}

// A machine that comes online while machines.list is answered has its
// machine.status queued after the reply: a phone never gets a reply that
// still lists it offline after the event that put it online.
func TestMachinesListComesBeforeALaterStatus(t *testing.T) {
	// The hook holds the phone's read loop longer than the usual test
	// heartbeat timeout.
	r := startRelay(t, func(o *relay.Options) { o.HeartbeatTimeout = 5 * time.Second })
	r.agent(t, "m-1").conn.Close(websocket.StatusNormalClosure, "")
	eventually(t, time.Second, "m-1 offline", func() bool {
		m, _ := r.machine("m-1")
		return !m.Online
	})
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")

	dialed := make(chan *wsClient, 1)
	r.srv.SetListedHook(func() {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			h := http.Header{"Authorization": {"Bearer " + enrollKey}}
			c, _, err := websocket.Dial(ctx, r.wsURL("/ws/agent?id=m-1&name=m-1"), &websocket.DialOptions{HTTPHeader: h})
			if err != nil {
				dialed <- nil
				return
			}
			dialed <- newClient(t, c)
		}()
		// Time for m-1's online event to reach the phone, which it must
		// not before the reply; len leaves the event in the queue.
		for deadline := time.Now().Add(300 * time.Millisecond); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
			if len(p.msgs) > 0 {
				return
			}
		}
	})

	_, got := listMachines(t, p)
	if got["m-1"].Online {
		t.Fatalf("m-1 = %+v in the reply, want offline", got["m-1"])
	}
	if m := statusEvent(t, p); m.ID != "m-1" || !m.Online {
		t.Fatalf("event after the reply = %+v, want m-1 online", m)
	}
	a := <-dialed
	if a == nil {
		t.Fatal("agent m-1 could not connect")
	}
	t.Cleanup(func() { a.conn.CloseNow() })
}

func statusEvent(t *testing.T, p *wsClient) protocol.Machine {
	t.Helper()
	env := p.next()
	if env.Kind != protocol.KindEvent || env.Method != protocol.EventMachineStatus {
		t.Fatalf("got %+v, want machine.status event", env)
	}
	var m protocol.Machine
	if err := json.Unmarshal(env.Body, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

// machineEvent is the machine a machine.status event carries.
func machineEvent(env protocol.Envelope) protocol.Machine {
	var m protocol.Machine
	json.Unmarshal(env.Body, &m)
	return m
}

func assertJSON(t *testing.T, got []byte, want string) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("got is not JSON: %s", got)
	}
	if err := json.Unmarshal([]byte(want), &w); err != nil {
		t.Fatal(err)
	}
	gj, _ := json.Marshal(g)
	wj, _ := json.Marshal(w)
	if string(gj) != string(wj) {
		t.Fatalf("JSON = %s, want %s", gj, wj)
	}
}

// A machine that drops and reconnects at once must end up online: the
// old connection's offline event may not arrive after the new online one.
func TestQuickReconnectEndsOnline(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	p := r.phone(t, "tok-1")
	dial := func() *websocket.Conn {
		c, status := r.dial(t, agentQuery, enrollKey)
		if status != http.StatusSwitchingProtocols {
			t.Fatalf("status %d", status)
		}
		c.CloseRead(context.Background())
		return c
	}
	cur := dial()
	for i := 0; i < 30; i++ {
		cur.CloseNow()
		cur = dial()
		// The new connection's online event may come well after the old
		// one's offline event, so wait for it rather than for a quiet
		// moment; then anything that still follows must not be an offline.
		var last protocol.Machine
		deadline := time.After(5 * time.Second)
		for !last.Online {
			select {
			case env := <-p.msgs:
				last = machineEvent(env)
			case <-deadline:
				t.Fatalf("round %d: no online event, last %+v", i, last)
			}
		}
		for drained := false; !drained; {
			select {
			case env := <-p.msgs:
				last = machineEvent(env)
			case <-time.After(50 * time.Millisecond):
				drained = true
			}
		}
		if !last.Online {
			t.Fatalf("round %d: last event %+v, want online", i, last)
		}
		if m, _ := r.machine("m-1"); !m.Online {
			t.Fatalf("round %d: m-1 offline", i)
		}
	}
}
