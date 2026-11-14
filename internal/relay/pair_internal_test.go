package relay

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/relay/store"
)

// The failure table is memory held for unauthenticated callers. Its size
// has no public view, so this test reads it directly.
func TestPairFailuresForgetIdleSources(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 12, 9, 30, 0, 0, time.UTC)
	s := NewServer(Options{EnrollKey: "k", PublicURL: "wss://relay.example.com", Store: st, Now: func() time.Time { return now }})
	t.Cleanup(s.Close)
	pair := func(i int) int {
		req := httptest.NewRequest("POST", "/api/pair", strings.NewReader(`{"code":"bad","deviceName":"x"}`))
		req.RemoteAddr = fmt.Sprintf("198.51.%d.%d:40000", i/256, i%256)
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, req)
		return rec.Code
	}
	for i := 0; i < 1000; i++ {
		if code := pair(i); code != http.StatusNotFound {
			t.Fatalf("source %d: status %d", i, code)
		}
	}
	now = now.Add(time.Minute + time.Second)
	pair(1000)
	s.pairMu.Lock()
	n := len(s.pairFailures)
	s.pairMu.Unlock()
	if n > 1 {
		t.Fatalf("%d sources kept after the window passed, want only the latest", n)
	}
}
