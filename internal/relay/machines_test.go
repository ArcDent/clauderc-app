package relay_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
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
		var last protocol.Machine
		for {
			select {
			case env := <-p.msgs:
				json.Unmarshal(env.Body, &last)
				continue
			case <-time.After(50 * time.Millisecond):
			}
			break
		}
		if !last.Online {
			t.Fatalf("round %d: last event %+v, want online", i, last)
		}
		if m, _ := r.machine("m-1"); !m.Online {
			t.Fatalf("round %d: m-1 offline", i)
		}
	}
}
