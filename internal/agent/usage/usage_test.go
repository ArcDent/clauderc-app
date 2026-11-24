package usage

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

const token = "sk-ant-oat01-secret-token-for-tests"

const sample = `{
  "five_hour": {"utilization": 12.5, "resets_at": "2026-11-14T22:00:00.282668+00:00"},
  "seven_day": {"utilization": 30, "resets_at": "2026-11-18T00:00:00.000Z"},
  "seven_day_sonnet": {"utilization": 5},
  "extra_usage": null
}`

type fixture struct {
	h      *Handler
	now    time.Time
	status atomic.Int32
	hits   atomic.Int32
	cred   Credential
	err    error
	logs   bytes.Buffer
	// gate, when set, holds each request until it is closed.
	gate atomic.Pointer[chan struct{}]
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{now: time.Date(2026, 11, 14, 18, 0, 0, 0, time.UTC)}
	f.status.Store(http.StatusOK)
	f.cred = Credential{AccessToken: token, ExpiresAt: f.now.Add(time.Hour), Scopes: []string{"user:inference", "user:profile"}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.hits.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+token || r.Header.Get("anthropic-beta") != betaHeader {
			http.Error(w, "bad headers", http.StatusBadRequest)
			return
		}
		if gate := f.gate.Load(); gate != nil {
			select {
			case <-*gate:
			case <-r.Context().Done():
				return
			}
		}
		status := int(f.status.Load())
		w.WriteHeader(status)
		if status == http.StatusOK {
			_, _ = w.Write([]byte(sample))
		} else {
			// An error body that echoes the token must still stay out of the log.
			_, _ = w.Write([]byte(`{"error":"` + token + `"}`))
		}
	}))
	t.Cleanup(srv.Close)
	f.h = New(Options{
		Endpoint:   srv.URL,
		Credential: func(context.Context) (Credential, error) { return f.cred, f.err },
		Now:        func() time.Time { return f.now },
		Logger:     slog.New(slog.NewTextHandler(&f.logs, nil)),
	})
	t.Cleanup(func() {
		if strings.Contains(f.logs.String(), token) {
			t.Errorf("the token reached the log:\n%s", f.logs.String())
		}
	})
	return f
}

// later moves past the TTL so the next Get reads again.
func (f *fixture) later() { f.now = f.now.Add(DefaultTTL + time.Second) }

// hold keeps the endpoint's answers back until release is called, at the
// latest when the test ends.
func (f *fixture) hold(t *testing.T) (release func()) {
	gate := make(chan struct{})
	f.gate.Store(&gate)
	var once sync.Once
	release = func() { once.Do(func() { close(gate) }) }
	t.Cleanup(release)
	return release
}

// waitHits waits until the endpoint has received n requests.
func (f *fixture) waitHits(t *testing.T, n int32) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for f.hits.Load() < n {
		if time.Now().After(deadline) {
			t.Fatalf("%d requests reached the endpoint, want %d", f.hits.Load(), n)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestGetReadsBothWindows(t *testing.T) {
	f := newFixture(t)
	got := f.h.Get(context.Background())
	if !got.Available || got.Stale != "" || got.FiveHour == nil || got.SevenDay == nil || got.FetchedAt == nil {
		t.Fatalf("Get = %+v", got)
	}
	if got.FiveHour.Utilization != 12.5 || !got.FiveHour.ResetsAt.Equal(time.Date(2026, 11, 14, 22, 0, 0, 0, time.UTC)) {
		t.Errorf("five hour = %+v %v", got.FiveHour, got.FiveHour.ResetsAt)
	}
	if got.SevenDay.Utilization != 30 || !got.SevenDay.ResetsAt.Equal(time.Date(2026, 11, 18, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("seven day = %+v %v", got.SevenDay, got.SevenDay.ResetsAt)
	}
	if !got.FetchedAt.Equal(f.now) {
		t.Errorf("fetchedAt = %v, want %v", got.FetchedAt, f.now)
	}
}

func TestUnauthorizedKeepsLastReadAsExpired(t *testing.T) {
	f := newFixture(t)
	first := f.h.Get(context.Background())
	f.later()
	f.status.Store(http.StatusUnauthorized)
	got := f.h.Get(context.Background())
	if !got.Available || got.Stale != protocol.UsageExpired {
		t.Fatalf("Get after 401 = %+v", got)
	}
	if got.FiveHour == nil || got.FiveHour.Utilization != 12.5 || !got.FetchedAt.Equal(*first.FetchedAt) {
		t.Errorf("Get after 401 lost the last read: %+v", got)
	}
}

func TestExpiredLoginIsNotSent(t *testing.T) {
	f := newFixture(t)
	f.cred.ExpiresAt = f.now
	got := f.h.Get(context.Background())
	if !got.Available || got.Stale != protocol.UsageExpired || got.FiveHour != nil || f.hits.Load() != 0 {
		t.Errorf("Get with an expired login = %+v after %d requests", got, f.hits.Load())
	}
}

func TestServerErrorIsFailedNotExpired(t *testing.T) {
	f := newFixture(t)
	f.h.Get(context.Background())
	f.later()
	f.status.Store(http.StatusInternalServerError)
	got := f.h.Get(context.Background())
	if got.Stale != protocol.UsageFailed || got.SevenDay == nil {
		t.Errorf("Get after 500 = %+v", got)
	}
	if !strings.Contains(f.logs.String(), "status=500") {
		t.Errorf("status missing from the log:\n%s", f.logs.String())
	}
}

func TestNoLoginIsUnavailable(t *testing.T) {
	for name, set := range map[string]func(*fixture){
		"no credentials":   func(f *fixture) { f.err = ErrNoLogin },
		"no profile scope": func(f *fixture) { f.cred.Scopes = []string{"user:inference"} },
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			set(f)
			if got := f.h.Get(context.Background()); got.Available || got.FiveHour != nil || f.hits.Load() != 0 {
				t.Errorf("Get = %+v after %d requests", got, f.hits.Load())
			}
		})
	}
}

func TestAnswerIsReusedWithinTTL(t *testing.T) {
	f := newFixture(t)
	f.h.Get(context.Background())
	f.now = f.now.Add(DefaultTTL - time.Second)
	f.h.Get(context.Background())
	if n := f.hits.Load(); n != 1 {
		t.Fatalf("%d requests within the TTL, want 1", n)
	}
	f.now = f.now.Add(2 * time.Second)
	f.h.Get(context.Background())
	if n := f.hits.Load(); n != 2 {
		t.Errorf("%d requests after the TTL, want 2", n)
	}
}

// While one read waits on the endpoint, a usage.get whose ctx ends returns
// at once instead of queueing behind it.
func TestCancelledGetDoesNotWaitForARead(t *testing.T) {
	f := newFixture(t)
	release := f.hold(t)
	first := make(chan protocol.UsageResponse, 1)
	go func() { first <- f.h.Get(context.Background()) }()
	f.waitHits(t, 1)

	ctx, cancel := context.WithCancel(context.Background())
	answered := make(chan struct{})
	go func() {
		defer close(answered)
		f.h.Handle(ctx, protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r2", Method: protocol.MethodUsageGet})
	}()
	cancel()
	select {
	case <-answered:
	case <-time.After(time.Second):
		t.Fatal("usage.get kept waiting for the other read after its ctx ended")
	}

	release()
	if got := <-first; !got.Available || got.Stale != "" || got.FiveHour == nil {
		t.Errorf("the read in progress answered %+v", got)
	}
}

// A read cut short by its caller's ctx is not kept: the next usage.get
// reads again instead of answering with that failure.
func TestCancelledReadIsNotCached(t *testing.T) {
	f := newFixture(t)
	release := f.hold(t)
	ctx, cancel := context.WithCancel(context.Background())
	cut := make(chan struct{})
	go func() {
		defer close(cut)
		f.h.Get(ctx)
	}()
	f.waitHits(t, 1)
	cancel()
	<-cut

	release()
	got := f.h.Get(context.Background())
	if !got.Available || got.Stale != "" || got.FiveHour == nil {
		t.Errorf("Get after a cancelled read = %+v, want a fresh answer", got)
	}
	if n := f.hits.Load(); n != 2 {
		t.Errorf("%d requests, want the cancelled one and a new one", n)
	}
}

func TestHandleAnswersUsageGet(t *testing.T) {
	f := newFixture(t)
	res := f.h.Handle(context.Background(), protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "r1", Method: protocol.MethodUsageGet})
	if res.Error != nil || res.ID != "r1" || !bytes.Contains(res.Body, []byte(`"fiveHour":{"utilization":12.5`)) {
		t.Errorf("Handle = %+v %s", res, res.Body)
	}
}

func TestReadCredential(t *testing.T) {
	noKeychain := func(context.Context) ([]byte, error) { return nil, ErrNoLogin }
	dir := t.TempDir()
	if _, err := ReadCredential(context.Background(), dir, noKeychain); !errors.Is(err, ErrNoLogin) {
		t.Errorf("no file: err = %v, want ErrNoLogin", err)
	}
	file := `{"mcpOAuth":{},"claudeAiOauth":{"accessToken":"` + token + `","refreshToken":"r","expiresAt":1763143200000,"scopes":["user:profile"]}}`
	if err := os.WriteFile(filepath.Join(dir, ".credentials.json"), []byte(file), 0o600); err != nil {
		t.Fatal(err)
	}
	cred, err := ReadCredential(context.Background(), dir, noKeychain)
	if err != nil || cred.AccessToken != token || !cred.ExpiresAt.Equal(time.UnixMilli(1763143200000)) || len(cred.Scopes) != 1 {
		t.Errorf("file: %+v %v", cred, err)
	}
	// The keychain wins over the file; one holding only MCP logins falls back to it.
	fromKeychain := func(context.Context) ([]byte, error) {
		return []byte(`{"claudeAiOauth":{"accessToken":"from-keychain"}}`), nil
	}
	if cred, _ := ReadCredential(context.Background(), dir, fromKeychain); cred.AccessToken != "from-keychain" {
		t.Errorf("keychain: token %q", cred.AccessToken)
	}
	mcpOnly := func(context.Context) ([]byte, error) { return []byte(`{"mcpOAuth":{}}`), nil }
	if cred, _ := ReadCredential(context.Background(), dir, mcpOnly); cred.AccessToken != token {
		t.Errorf("mcp-only keychain: token %q", cred.AccessToken)
	}
	if err := os.WriteFile(filepath.Join(dir, ".credentials.json"), []byte(token), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadCredential(context.Background(), dir, noKeychain); err == nil || strings.Contains(err.Error(), token) {
		t.Errorf("broken file: err = %v", err)
	}
}
