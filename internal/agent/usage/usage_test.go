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
