package relay

import (
	"encoding/json"
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

// Redeemed codes are kept only for one code lifetime, so the table of used
// codes does not grow with every pairing.
func TestPairUsedCodesForgotten(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 12, 9, 30, 0, 0, time.UTC)
	s := NewServer(Options{EnrollKey: "k", PublicURL: "wss://relay.example.com", Store: st, Now: func() time.Time { return now }})
	t.Cleanup(s.Close)
	newCode := func() string {
		req := httptest.NewRequest("POST", "/api/admin/pair-codes", nil)
		req.Header.Set("Authorization", "Bearer k")
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, req)
		var pc struct{ Code string }
		if err := json.Unmarshal(rec.Body.Bytes(), &pc); err != nil || pc.Code == "" {
			t.Fatalf("pair code: %d %s", rec.Code, rec.Body)
		}
		return pc.Code
	}
	for i := 0; i < 20; i++ {
		req := httptest.NewRequest("POST", "/api/pair", strings.NewReader(`{"code":"`+newCode()+`","deviceName":"x"}`))
		req.RemoteAddr = "198.51.100.7:40000"
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("pair %d: status %d", i, rec.Code)
		}
	}
	now = now.Add(5*time.Minute + time.Second)
	newCode()
	s.pairMu.Lock()
	n := len(s.pairUsed)
	s.pairMu.Unlock()
	if n != 0 {
		t.Fatalf("%d used codes kept after their lifetime", n)
	}
}
