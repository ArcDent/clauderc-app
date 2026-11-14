package relay_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

const enrollKey = "enroll-key-for-tests"

type testRelay struct {
	srv   *relay.Server
	http  *httptest.Server
	store *store.Store
	path  string
}

// startRelay runs a relay on httptest with short heartbeats; edit lets a
// test adjust the options before the server starts.
func startRelay(t *testing.T, edit func(*relay.Options)) *testRelay {
	t.Helper()
	path := filepath.Join(t.TempDir(), "state.json")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	opts := relay.Options{
		EnrollKey:         enrollKey,
		PublicURL:         "wss://relay.example.com",
		Store:             st,
		HeartbeatInterval: 20 * time.Millisecond,
		HeartbeatTimeout:  150 * time.Millisecond,
	}
	if edit != nil {
		edit(&opts)
	}
	srv := relay.NewServer(opts)
	hs := httptest.NewServer(srv)
	t.Cleanup(func() {
		hs.Close()
		srv.Close()
	})
	return &testRelay{srv: srv, http: hs, store: st, path: path}
}

func (r *testRelay) wsURL(p string) string {
	return "ws" + strings.TrimPrefix(r.http.URL, "http") + p
}

// dial opens a WebSocket with an optional bearer token. On a refused
// upgrade it returns the HTTP status.
func (r *testRelay) dial(t *testing.T, path, bearer string) (*websocket.Conn, int) {
	t.Helper()
	h := http.Header{}
	if bearer != "" {
		h.Set("Authorization", "Bearer "+bearer)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	c, resp, err := websocket.Dial(ctx, r.wsURL(path), &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		if resp == nil {
			t.Fatalf("dial %s: %v", path, err)
		}
		return nil, resp.StatusCode
	}
	t.Cleanup(func() { c.CloseNow() })
	return c, http.StatusSwitchingProtocols
}

const agentQuery = "/ws/agent?id=m-1&name=mac-mini&agentVersion=0.1.0&claudeVersion=2.1.323"

// machine returns the relay's view of one machine.
func (r *testRelay) machine(id string) (protocol.Machine, bool) {
	for _, m := range r.srv.Machines() {
		if m.ID == id {
			return m, true
		}
	}
	return protocol.Machine{}, false
}

// eventually polls cond until it holds or the timeout passes.
func eventually(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}
