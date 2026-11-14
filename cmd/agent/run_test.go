package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

const enrollKey = "enroll-key-for-tests"

func startRelay(t *testing.T) (*relay.Server, *httptest.Server) {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	srv := relay.NewServer(relay.Options{EnrollKey: enrollKey, PublicURL: "wss://relay.example.com", Store: st})
	hs := httptest.NewTLSServer(srv)
	t.Cleanup(func() {
		hs.Close()
		srv.Close()
	})
	return srv, hs
}

// writeConfig writes agent.toml with the enroll key in a mode 600 file
// and returns its path.
func writeConfig(t *testing.T, relayURL string) string {
	t.Helper()
	t.Setenv("CLAUDERC_ENROLL_KEY", "")
	dir := t.TempDir()
	key := filepath.Join(dir, "enroll.key")
	if err := os.WriteFile(key, []byte(enrollKey+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	claudeDir := t.TempDir()
	fakeclaude.Setup(t, claudeDir)
	cfg := fmt.Sprintf("relay_url = %q\nname = \"测试机\"\nclaude_path = %q\nclaude_dir = %q\nenroll_key_file = %q\n",
		relayURL, fakeclaude.Path(), claudeDir, key)
	path := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(path, []byte(cfg), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestRunRegistersWithRelay(t *testing.T) {
	srv, hs := startRelay(t)
	cfg := writeConfig(t, hs.URL)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runCmd(ctx, []string{"--config", cfg}, hs.Client(), io.Discard) }()

	deadline := time.Now().Add(5 * time.Second)
	for {
		ms := srv.Machines()
		if len(ms) == 1 && ms[0].Online && ms[0].Name == "测试机" && ms[0].AgentVersion == version {
			break
		}
		select {
		case err := <-done:
			t.Fatalf("run returned early: %v", err)
		default:
		}
		if time.Now().After(deadline) {
			t.Fatalf("machine not online in machines.list: %+v", ms)
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("run after cancel: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("run did not stop")
	}
}

func TestRunRefusesPlainRelayURL(t *testing.T) {
	for _, u := range []string{"http://relay.example.com", "ws://relay.example.com"} {
		cfg := writeConfig(t, u)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		err := runCmd(ctx, []string{"--config", cfg}, nil, io.Discard)
		cancel()
		if err == nil || !strings.Contains(err.Error(), "https://") {
			t.Errorf("%s: run returned %v, want a refusal", u, err)
		}
	}
}

func TestAdminCommands(t *testing.T) {
	_, hs := startRelay(t)
	cfg := writeConfig(t, hs.URL)
	ctx := context.Background()
	var out bytes.Buffer
	if err := adminCmd(ctx, "pair", []string{"--config", cfg}, hs.Client(), &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "clauderc://pair?relay=wss") || !strings.Contains(out.String(), "前有效") {
		t.Errorf("pair output:\n%s", out.String())
	}
	out.Reset()
	if err := adminCmd(ctx, "devices", []string{"--config", cfg}, hs.Client(), &out); err != nil || !strings.Contains(out.String(), "还没有已配对的手机") {
		t.Errorf("devices: %v %q", err, out.String())
	}
	if err := adminCmd(ctx, "revoke", []string{"--config", cfg}, hs.Client(), &out); err == nil {
		t.Error("revoke without an id succeeded")
	}
	if err := adminCmd(ctx, "revoke", []string{"--config", cfg, "nope"}, hs.Client(), &out); err == nil {
		t.Error("revoking an unknown device succeeded")
	}
}
