// Package usage serves usage.get: the 5-hour and weekly limits of the
// Claude subscription the machine is logged in with. Like CodexBar, it
// reads Claude Code's own login and asks Anthropic's OAuth usage endpoint.
// The login is only read, never renewed or written back: renewing would
// use up the refresh token Claude Code holds and log it out. The token is
// sent to Anthropic only, never to the relay or the log.
package usage

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/protocol"
)

// Endpoint is Anthropic's OAuth usage endpoint, the one Claude Code's
// /usage reads.
const Endpoint = "https://api.anthropic.com/api/oauth/usage"

// betaHeader is required by the OAuth endpoints.
const betaHeader = "oauth-2025-04-20"

// DefaultTTL is how long one answer is reused before asking again.
const DefaultTTL = time.Minute

// requestTimeout bounds one call to the endpoint.
const requestTimeout = 15 * time.Second

// ErrNoLogin means the machine has no Claude subscription login to read.
var ErrNoLogin = errors.New("no Claude subscription login")

// Credential is the part of Claude Code's login the endpoint needs.
type Credential struct {
	AccessToken string
	// ExpiresAt is zero when unknown.
	ExpiresAt time.Time
	// Scopes is empty when unknown.
	Scopes []string
}

// Options configures a Handler.
type Options struct {
	// ClaudeDir holds Claude Code's .credentials.json.
	ClaudeDir string
	// Endpoint defaults to Endpoint.
	Endpoint   string
	HTTPClient *http.Client
	// Credential reads the login; nil reads Claude Code's own.
	Credential func(ctx context.Context) (Credential, error)
	UserAgent  string
	// Now defaults to time.Now.
	Now func() time.Time
	// TTL defaults to DefaultTTL.
	TTL    time.Duration
	Logger *slog.Logger
}

// Handler implements agent.Handler for usage.get.
type Handler struct {
	opts Options

	mu     sync.Mutex
	asked  time.Time
	answer protocol.UsageResponse
	// last is the latest successful read, kept to show while the login is expired.
	last *protocol.UsageResponse
}

// New returns a Handler.
func New(opts Options) *Handler {
	if opts.Endpoint == "" {
		opts.Endpoint = Endpoint
	}
	if opts.HTTPClient == nil {
		opts.HTTPClient = http.DefaultClient
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.TTL == 0 {
		opts.TTL = DefaultTTL
	}
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	if opts.Credential == nil {
		dir := opts.ClaudeDir
		opts.Credential = func(ctx context.Context) (Credential, error) { return ReadCredential(ctx, dir, keychain) }
	}
	return &Handler{opts: opts}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method { return []protocol.Method{protocol.MethodUsageGet} }

// Handle implements agent.Handler.
func (h *Handler) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	if req.Method != protocol.MethodUsageGet {
		return agent.FailUnknown(req)
	}
	return agent.Reply(req, h.Get(ctx))
}

// Get answers from the last read when it is younger than the TTL, and
// reads again otherwise.
func (h *Handler) Get(ctx context.Context) protocol.UsageResponse {
	h.mu.Lock()
	defer h.mu.Unlock()
	now := h.opts.Now()
	if !h.asked.IsZero() && now.Sub(h.asked) < h.opts.TTL {
		return h.answer
	}
	h.asked = now
	h.answer = h.read(ctx, now)
	return h.answer
}

func (h *Handler) read(ctx context.Context, now time.Time) protocol.UsageResponse {
	cred, err := h.opts.Credential(ctx)
	if errors.Is(err, ErrNoLogin) || (err == nil && len(cred.Scopes) > 0 && !slices.Contains(cred.Scopes, "user:profile")) {
		return protocol.UsageResponse{}
	}
	if err != nil {
		h.opts.Logger.Warn("usage: reading the Claude login failed", "err", err)
		return h.stale(protocol.UsageFailed)
	}
	if !cred.ExpiresAt.IsZero() && !now.Before(cred.ExpiresAt) {
		return h.stale(protocol.UsageExpired)
	}
	ctx, cancel := context.WithTimeout(ctx, requestTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, h.opts.Endpoint, nil)
	if err != nil {
		return h.stale(protocol.UsageFailed)
	}
	req.Header.Set("Authorization", "Bearer "+cred.AccessToken)
	req.Header.Set("anthropic-beta", betaHeader)
	req.Header.Set("Accept", "application/json")
	if h.opts.UserAgent != "" {
		req.Header.Set("User-Agent", h.opts.UserAgent)
	}
	res, err := h.opts.HTTPClient.Do(req)
	if err != nil {
		h.opts.Logger.Warn("usage: request failed", "err", err)
		return h.stale(protocol.UsageFailed)
	}
	defer res.Body.Close()
	switch res.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized:
		return h.stale(protocol.UsageExpired)
	default:
		// The body is not logged: it is Anthropic's, and it is never needed to tell what went wrong.
		h.opts.Logger.Warn("usage: unexpected response", "status", res.StatusCode)
		return h.stale(protocol.UsageFailed)
	}
	answer, err := decode(io.LimitReader(res.Body, 1<<20), now)
	if err != nil {
		h.opts.Logger.Warn("usage: unreadable response", "err", err)
		return h.stale(protocol.UsageFailed)
	}
	if answer.Available {
		h.last = &answer
	}
	return answer
}

// stale answers with the last successful read, marked with why it is old.
func (h *Handler) stale(why protocol.UsageStale) protocol.UsageResponse {
	answer := protocol.UsageResponse{Available: true, Stale: why}
	if h.last != nil {
		answer.FiveHour, answer.SevenDay, answer.FetchedAt = h.last.FiveHour, h.last.SevenDay, h.last.FetchedAt
	}
	return answer
}

type window struct {
	Utilization *float64 `json:"utilization"`
	ResetsAt    *string  `json:"resets_at"`
}

// decode maps the endpoint's answer; an account without either window,
// such as an API key login, is not Available.
func decode(r io.Reader, now time.Time) (protocol.UsageResponse, error) {
	var body struct {
		FiveHour          *window `json:"five_hour"`
		SevenDay          *window `json:"seven_day"`
		SevenDayOAuthApps *window `json:"seven_day_oauth_apps"`
	}
	if err := json.NewDecoder(r).Decode(&body); err != nil {
		return protocol.UsageResponse{}, err
	}
	answer := protocol.UsageResponse{FiveHour: body.FiveHour.toProtocol(), SevenDay: body.SevenDay.toProtocol()}
	if answer.SevenDay == nil {
		answer.SevenDay = body.SevenDayOAuthApps.toProtocol()
	}
	if answer.FiveHour == nil && answer.SevenDay == nil {
		return protocol.UsageResponse{}, nil
	}
	at := now.UTC().Truncate(time.Second)
	answer.Available, answer.FetchedAt = true, &at
	return answer, nil
}

func (w *window) toProtocol() *protocol.UsageWindow {
	if w == nil || w.Utilization == nil {
		return nil
	}
	out := &protocol.UsageWindow{Utilization: *w.Utilization}
	if w.ResetsAt != nil {
		if t, err := time.Parse(time.RFC3339Nano, *w.ResetsAt); err == nil {
			t = t.UTC().Truncate(time.Second)
			out.ResetsAt = &t
		}
	}
	return out
}

// keychainService names the macOS keychain item Claude Code keeps its login in.
const keychainService = "Claude Code-credentials"

// keychain returns the keychain item's content, or ErrNoLogin when there is
// none or the system has no keychain.
func keychain(ctx context.Context) ([]byte, error) {
	if runtime.GOOS != "darwin" {
		return nil, ErrNoLogin
	}
	out, err := exec.CommandContext(ctx, "security", "find-generic-password", "-s", keychainService, "-w").Output()
	if err != nil {
		return nil, ErrNoLogin
	}
	return out, nil
}

// ReadCredential reads Claude Code's login: on macOS from the keychain
// first (read with keychain), then from claudeDir/.credentials.json.
func ReadCredential(ctx context.Context, claudeDir string, keychain func(context.Context) ([]byte, error)) (Credential, error) {
	if data, err := keychain(ctx); err == nil {
		if cred, err := parseCredential(data); err == nil {
			return cred, nil
		}
	}
	data, err := os.ReadFile(filepath.Join(claudeDir, ".credentials.json"))
	if errors.Is(err, os.ErrNotExist) {
		return Credential{}, ErrNoLogin
	}
	if err != nil {
		return Credential{}, err
	}
	return parseCredential(data)
}

func parseCredential(data []byte) (Credential, error) {
	var file struct {
		OAuth *struct {
			AccessToken string   `json:"accessToken"`
			ExpiresAt   int64    `json:"expiresAt"`
			Scopes      []string `json:"scopes"`
		} `json:"claudeAiOauth"`
	}
	if err := json.Unmarshal(data, &file); err != nil {
		// The error would quote the content, which holds the token.
		return Credential{}, fmt.Errorf("credentials are not valid JSON")
	}
	if file.OAuth == nil || file.OAuth.AccessToken == "" {
		return Credential{}, ErrNoLogin
	}
	cred := Credential{AccessToken: file.OAuth.AccessToken, Scopes: file.OAuth.Scopes}
	if file.OAuth.ExpiresAt > 0 {
		cred.ExpiresAt = time.UnixMilli(file.OAuth.ExpiresAt)
	}
	return cred, nil
}
