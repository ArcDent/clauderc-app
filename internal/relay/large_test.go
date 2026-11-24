package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

func TestWriteTimeoutScalesWithLength(t *testing.T) {
	small := writeTimeout(1024)
	if small != 10*time.Second {
		t.Fatalf("writeTimeout(1KB) = %v, want 10s", small)
	}
	large := writeTimeout(14 << 20)
	if large <= 2*time.Minute {
		t.Fatalf("writeTimeout(14MB) = %v, want > 2m", large)
	}
	huge := writeTimeout(100 << 20)
	if huge != 5*time.Minute {
		t.Fatalf("writeTimeout(100MB) = %v, want 5m cap", huge)
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
	if len(b) > wsWriteChunk {
		b = b[:wsWriteChunk]
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
		chunk := b[:min(len(b), wsWriteChunk)]
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

type rateListener struct {
	net.Listener
	bytesPerSec int64
}

func (l *rateListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return &rateConn{
		Conn: c,
		r:    pacer{bytesPerSec: l.bytesPerSec},
		w:    pacer{bytesPerSec: l.bytesPerSec},
	}, nil
}

// Sending and receiving a 14 MB message over a rate-limited link that takes
// longer than HeartbeatTimeout while both sides ping keeps the connections
// alive and delivers the full payload intact.
func TestLargeMessageOnRateLimitedConnSurvivesHeartbeats(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	const enroll = "enroll-large"
	srv := NewServer(Options{
		EnrollKey:         enroll,
		PublicURL:         "wss://relay.example.com",
		Store:             st,
		HeartbeatInterval: 250 * time.Millisecond,
		HeartbeatTimeout:  1200 * time.Millisecond,
	})
	ts := httptest.NewUnstartedServer(srv)
	// 4.5 MB/s -> a 14 MB transfer takes ~3.1s, well past HeartbeatTimeout (1.2s).
	ts.Listener = &rateListener{Listener: ts.Listener, bytesPerSec: 4_500_000}
	ts.StartTLS()
	t.Cleanup(func() {
		ts.Close()
		srv.Close()
	})

	wsURL := "wss" + strings.TrimPrefix(ts.URL, "https")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Connect agent.
	agentWS, _, err := websocket.Dial(ctx, wsURL+protocol.PathAgentWS+"?id=m-1&name=slow", &websocket.DialOptions{
		HTTPClient: ts.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + enroll}},
	})
	if err != nil {
		t.Fatal(err)
	}
	agentWS.SetReadLimit(protocol.MaxMessageBytes)
	t.Cleanup(func() { agentWS.CloseNow() })

	// Pair and connect phone.
	post := func(path, bearer string, in, out any) {
		t.Helper()
		raw, _ := json.Marshal(in)
		req, _ := http.NewRequest(http.MethodPost, ts.URL+path, bytes.NewReader(raw))
		if bearer != "" {
			req.Header.Set("Authorization", "Bearer "+bearer)
		}
		res, err := ts.Client().Do(req)
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
	var code protocol.PairCodeResponse
	post(protocol.PathAdminPairCodes, enroll, struct{}{}, &code)
	var pair protocol.PairResponse
	post(protocol.PathPair, "", protocol.PairRequest{Code: code.Code, DeviceName: "phone"}, &pair)

	phoneWS, _, err := websocket.Dial(ctx, wsURL+protocol.PathAppWS, &websocket.DialOptions{
		HTTPClient: ts.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + pair.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	phoneWS.SetReadLimit(protocol.MaxMessageBytes)
	t.Cleanup(func() { phoneWS.CloseNow() })

	// Periodically ping the relay from the phone while transfers run.
	pingDone := make(chan struct{})
	go func() {
		defer close(pingDone)
		tk := time.NewTicker(300 * time.Millisecond)
		defer tk.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tk.C:
				pctx, pcancel := context.WithTimeout(ctx, 2*time.Second)
				_ = phoneWS.Ping(pctx)
				pcancel()
			}
		}
	}()

	// Agent echoes every request body back as its response (using fragmented writes
	// for large payloads, matching agent/conn).
	agentErr := make(chan error, 1)
	go func() {
		for {
			_, data, err := agentWS.Read(ctx)
			if err != nil {
				agentErr <- err
				return
			}
			env, err := protocol.Decode(data)
			if err != nil {
				agentErr <- err
				return
			}
			if env.Kind != protocol.KindRequest {
				continue
			}
			reply, err := protocol.Encode(protocol.Envelope{
				V:       protocol.Version,
				Kind:    protocol.KindResponse,
				ID:      env.ID,
				Method:  env.Method,
				Machine: env.Machine,
				Client:  env.Client,
				Body:    env.Body,
			})
			if err != nil {
				agentErr <- err
				return
			}
			if err := writeMessage(ctx, agentWS, reply); err != nil {
				agentErr <- err
				return
			}
		}
	}()

	// Send a 14 MB request from phone (as a single unfragmented frame so relay's
	// read side stays inside Read for >3s, exercising activityConn liveness).
	payload := `{"blob":"` + strings.Repeat("A", 14<<20) + `"}`
	reqData, err := protocol.Encode(protocol.Envelope{
		V:       protocol.Version,
		Kind:    protocol.KindRequest,
		ID:      "r-large",
		Method:  "test.large",
		Machine: "m-1",
		Body:    json.RawMessage(payload),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := phoneWS.Write(ctx, websocket.MessageText, reqData); err != nil {
		t.Fatalf("phone write 14MB: %v", err)
	}

	// Read until the matching 14 MB response comes back.
	var got protocol.Envelope
	for {
		_, data, err := phoneWS.Read(ctx)
		if err != nil {
			t.Fatalf("phone read 14MB response: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil {
			t.Fatal(err)
		}
		if env.ID == "r-large" {
			got = env
			break
		}
	}
	if got.Error != nil {
		t.Fatalf("14MB response error: %+v", got.Error)
	}
	if string(got.Body) != payload {
		t.Fatalf("14MB response len = %d, want %d", len(got.Body), len(payload))
	}

	// Follow-up message proves both connections stayed alive.
	smallReq, _ := protocol.Encode(protocol.Envelope{
		V:       protocol.Version,
		Kind:    protocol.KindRequest,
		ID:      "r-after",
		Method:  "test.small",
		Machine: "m-1",
		Body:    json.RawMessage(`{"ok":true}`),
	})
	if err := phoneWS.Write(ctx, websocket.MessageText, smallReq); err != nil {
		t.Fatalf("follow-up write failed: %v", err)
	}
	for {
		_, data, err := phoneWS.Read(ctx)
		if err != nil {
			t.Fatalf("follow-up read failed: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil {
			t.Fatal(err)
		}
		if env.ID == "r-after" {
			if env.Error != nil || string(env.Body) != `{"ok":true}` {
				t.Fatalf("follow-up response = %+v", env)
			}
			break
		}
	}
	cancel()
	<-pingDone
	select {
	case err := <-agentErr:
		if ctx.Err() == nil && err != nil {
			t.Fatalf("agent loop exited early: %v", err)
		}
	default:
	}
}
