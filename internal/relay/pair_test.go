package relay_test

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
)

type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.now = c.now.Add(d)
	c.mu.Unlock()
}

func startPairRelay(t *testing.T) (*testRelay, *fakeClock) {
	clock := &fakeClock{now: time.Date(2026, 11, 12, 9, 30, 0, 0, time.UTC)}
	return startRelay(t, func(o *relay.Options) { o.Now = clock.Now }), clock
}

// do sends a request to the relay handler and decodes a JSON reply into out.
func (r *testRelay) do(t *testing.T, method, path, bearer, body string, header map[string]string, out any) int {
	t.Helper()
	req, err := http.NewRequest(method, r.http.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if out != nil && len(raw) > 0 {
		if err := json.Unmarshal(raw, out); err != nil {
			t.Fatalf("%s %s: decode %q: %v", method, path, raw, err)
		}
	}
	return resp.StatusCode
}

func (r *testRelay) newPairCode(t *testing.T) protocol.PairCodeResponse {
	t.Helper()
	var pc protocol.PairCodeResponse
	if status := r.do(t, "POST", "/api/admin/pair-codes", enrollKey, "", nil, &pc); status != 200 {
		t.Fatalf("pair-codes status %d", status)
	}
	return pc
}

// pair redeems code from source (sent as X-Forwarded-For over loopback).
func (r *testRelay) pair(t *testing.T, code, source string) (int, protocol.PairResponse, protocol.Error) {
	t.Helper()
	raw, _ := json.Marshal(map[string]string{"code": code, "deviceName": "Pixel 9"})
	var resp json.RawMessage
	status := r.do(t, "POST", "/api/pair", "", string(raw), map[string]string{"X-Forwarded-For": source}, &resp)
	var ok protocol.PairResponse
	var e protocol.Error
	if status == 200 {
		json.Unmarshal(resp, &ok)
	} else {
		json.Unmarshal(resp, &e)
	}
	return status, ok, e
}

func TestPairCodesNeedEnrollKey(t *testing.T) {
	r, _ := startPairRelay(t)
	for name, key := range map[string]string{"missing": "", "wrong": "nope"} {
		t.Run(name, func(t *testing.T) {
			var e protocol.Error
			if status := r.do(t, "POST", "/api/admin/pair-codes", key, "", nil, &e); status != 401 {
				t.Fatalf("status = %d, want 401", status)
			}
			if e.Code != "unauthenticated" {
				t.Fatalf("code = %q", e.Code)
			}
		})
	}
}

func TestPairCodeExchangedForToken(t *testing.T) {
	r, _ := startPairRelay(t)
	pc := r.newPairCode(t)
	if len(pc.Code) != 32 {
		t.Fatalf("code %q is not 128 bits of hex", pc.Code)
	}
	if want := time.Date(2026, 11, 12, 9, 35, 0, 0, time.UTC); !pc.ExpiresAt.Equal(want) {
		t.Fatalf("expiresAt = %v, want %v", pc.ExpiresAt, want)
	}
	if want := "clauderc://pair?relay=wss://relay.example.com&code=" + pc.Code; pc.URI != want {
		t.Fatalf("uri = %q, want %q", pc.URI, want)
	}

	status, got, _ := r.pair(t, pc.Code, "203.0.113.5")
	if status != 200 || got.DeviceID == "" || got.Token == "" {
		t.Fatalf("pair = %d %+v", status, got)
	}
	devs := r.store.Devices()
	if len(devs) != 1 || devs[0].ID != got.DeviceID || devs[0].Name != "Pixel 9" {
		t.Fatalf("stored devices = %+v", devs)
	}
	if _, status := r.dial(t, "/ws/app", got.Token); status != http.StatusSwitchingProtocols {
		t.Fatalf("new token rejected on /ws/app: %d", status)
	}

	status, _, e := r.pair(t, pc.Code, "203.0.113.5")
	if status != 409 || e.Code != "pairCodeUsed" {
		t.Fatalf("second use = %d %+v, want 409 pairCodeUsed", status, e)
	}
}

// A retry of a redeemed code tells the user to make a new QR code, until
// the code would have expired anyway.
func TestPairCodeUsedTwice(t *testing.T) {
	r, clock := startPairRelay(t)
	pc := r.newPairCode(t)
	if status, _, _ := r.pair(t, pc.Code, "203.0.113.5"); status != 200 {
		t.Fatalf("first use: status %d", status)
	}
	clock.Advance(4 * time.Minute)
	status, _, e := r.pair(t, pc.Code, "203.0.113.5")
	if status != 409 || e.Code != "pairCodeUsed" || e.Message != "这个二维码已经配对过，请在目标机上重新生成" {
		t.Fatalf("second use = %d %+v", status, e)
	}
	clock.Advance(time.Minute + time.Second)
	if status, _, e := r.pair(t, pc.Code, "203.0.113.5"); status != 404 || e.Code != "pairCodeInvalid" || e.Message != "配对码无效或已使用" {
		t.Fatalf("use after the code's lifetime = %d %+v", status, e)
	}
	if n := len(r.store.Devices()); n != 1 {
		t.Fatalf("%d devices stored, want 1", n)
	}
}

// A pairing whose credential cannot be saved must not use up the code.
func TestPairCodeKeptWhenSaveFails(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root ignores directory permissions")
	}
	r, _ := startPairRelay(t)
	pc := r.newPairCode(t)
	dir := filepath.Dir(r.path)
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(dir, 0o700) })
	if status, _, e := r.pair(t, pc.Code, "203.0.113.5"); status != 500 || e.Code != "internal" {
		t.Fatalf("pair with a read-only state dir = %d %+v, want 500 internal", status, e)
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	status, got, e := r.pair(t, pc.Code, "203.0.113.5")
	if status != 200 || got.Token == "" {
		t.Fatalf("retry after the failed save = %d %+v", status, e)
	}
	if devs := r.store.Devices(); len(devs) != 1 || devs[0].ID != got.DeviceID {
		t.Fatalf("stored devices = %+v", devs)
	}
}

func TestPairCodeExpires(t *testing.T) {
	r, clock := startPairRelay(t)
	early := r.newPairCode(t)
	late := r.newPairCode(t)
	clock.Advance(4*time.Minute + 59*time.Second)
	if status, _, _ := r.pair(t, early.Code, "203.0.113.5"); status != 200 {
		t.Fatalf("code at 4m59s: status %d, want 200", status)
	}
	clock.Advance(2 * time.Second)
	status, _, e := r.pair(t, late.Code, "203.0.113.5")
	if status != 410 || e.Code != "pairCodeExpired" {
		t.Fatalf("code at 5m01s = %d %+v, want 410 pairCodeExpired", status, e)
	}
	if n := len(r.store.Devices()); n != 1 {
		t.Fatalf("%d devices stored, want 1", n)
	}
}

func TestPairUnknownCode(t *testing.T) {
	r, _ := startPairRelay(t)
	status, _, e := r.pair(t, "00000000000000000000000000000000", "203.0.113.5")
	if status != 404 || e.Code != "pairCodeInvalid" {
		t.Fatalf("= %d %+v, want 404 pairCodeInvalid", status, e)
	}
}

func TestPairBadBody(t *testing.T) {
	r, _ := startPairRelay(t)
	var e protocol.Error
	if status := r.do(t, "POST", "/api/pair", "", "{", nil, &e); status != 400 || e.Code != "invalidParams" {
		t.Fatalf("= %d %+v, want 400 invalidParams", status, e)
	}
}

func TestPairRateLimitedPerSource(t *testing.T) {
	r, clock := startPairRelay(t)
	for i := 0; i < 10; i++ {
		if status, _, _ := r.pair(t, "bad-code", "203.0.113.5"); status != 404 {
			t.Fatalf("failure %d: status %d, want 404", i+1, status)
		}
		clock.Advance(time.Second)
	}
	pc := r.newPairCode(t)
	status, _, e := r.pair(t, pc.Code, "203.0.113.5")
	if status != 429 || e.Code != "rateLimited" {
		t.Fatalf("after 10 failures = %d %+v, want 429 rateLimited", status, e)
	}
	if status, _, _ := r.pair(t, pc.Code, "203.0.113.6"); status != 200 {
		t.Fatalf("other source: status %d, want 200", status)
	}

	// The first failure leaves the one-minute window after 60s.
	pc = r.newPairCode(t)
	clock.Advance(51 * time.Second)
	if status, _, _ := r.pair(t, pc.Code, "203.0.113.5"); status != 200 {
		t.Fatalf("after window: status %d, want 200", status)
	}
}

// pairFrom redeems code on the handler directly, as if from peer with the
// given X-Forwarded-For, and returns the status.
func (r *testRelay) pairFrom(peer, xff, code string) int {
	raw, _ := json.Marshal(map[string]string{"code": code, "deviceName": "Pixel 9"})
	req := httptest.NewRequest("POST", "/api/pair", strings.NewReader(string(raw)))
	req.RemoteAddr = peer
	req.Header.Set("X-Forwarded-For", xff)
	rec := httptest.NewRecorder()
	r.srv.ServeHTTP(rec, req)
	return rec.Code
}

// X-Forwarded-For is only trusted from a loopback peer; otherwise the
// direct address is the source, whatever the header says.
func TestPairRateLimitIgnoresForwardedForFromRemotePeer(t *testing.T) {
	r, _ := startPairRelay(t)
	for i := 0; i < 10; i++ {
		if status := r.pairFrom("198.51.100.7:40000", "203.0.113."+string(rune('a'+i)), "bad-code"); status != 404 {
			t.Fatalf("failure %d: status %d", i+1, status)
		}
	}
	if status := r.pairFrom("198.51.100.7:40000", "203.0.113.k", r.newPairCode(t).Code); status != 429 {
		t.Fatalf("status %d, want 429", status)
	}
}

// Behind Docker every request arrives from the bridge gateway. With that
// network in TrustedProxies each phone is limited by its own address, taken
// from the right of X-Forwarded-For so a forged entry on the left is ignored.
func TestPairRateLimitByForwardedForFromTrustedProxy(t *testing.T) {
	const gateway = "172.17.0.1:40000"
	r := startRelay(t, func(o *relay.Options) {
		o.TrustedProxies = []netip.Prefix{netip.MustParsePrefix("172.16.0.0/12")}
	})
	for i := 0; i < 10; i++ {
		// The left entry is forged by the client and differs each time.
		xff := fmt.Sprintf("198.51.100.%d, 203.0.113.5", i)
		if status := r.pairFrom(gateway, xff, "bad-code"); status != 404 {
			t.Fatalf("failure %d: status %d", i+1, status)
		}
	}
	if status := r.pairFrom(gateway, "203.0.113.5", r.newPairCode(t).Code); status != 429 {
		t.Fatalf("limited phone: status %d, want 429", status)
	}
	// A second trusted proxy in the chain is skipped too.
	if status := r.pairFrom(gateway, "203.0.113.5, 172.18.0.5", r.newPairCode(t).Code); status != 429 {
		t.Fatalf("limited phone via two proxies: status %d, want 429", status)
	}
	if status := r.pairFrom(gateway, "203.0.113.6", r.newPairCode(t).Code); status != 200 {
		t.Fatalf("other phone through the same gateway: status %d, want 200", status)
	}
}

// Concurrent failures from one source must not slip past the limit.
func TestPairRateLimitHoldsUnderConcurrency(t *testing.T) {
	r, _ := startPairRelay(t)
	const n = 50
	statuses := make(chan int, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest("POST", r.http.URL+"/api/pair", strings.NewReader(`{"code":"bad-code","deviceName":"Pixel 9"}`))
			req.Header.Set("X-Forwarded-For", "203.0.113.5")
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				statuses <- 0
				return
			}
			resp.Body.Close()
			statuses <- resp.StatusCode
		}()
	}
	wg.Wait()
	close(statuses)
	counts := map[int]int{}
	for s := range statuses {
		counts[s]++
	}
	if counts[404] > 10 || counts[404]+counts[429] != n {
		t.Fatalf("statuses %v, want at most 10 x 404 and the rest 429", counts)
	}
}
