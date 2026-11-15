package relay_test

import (
	"context"
	"encoding/json"
	"fmt"
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
