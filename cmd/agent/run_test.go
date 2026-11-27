package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/conn"
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

// startRun runs the agent with cfg in the background; stop cancels it and
// waits for it to return.
func startRun(t *testing.T, cfg string, hs *httptest.Server, log io.Writer) (done <-chan error, stop func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	ch, exited := make(chan error, 1), make(chan struct{})
	go func() {
		ch <- runCmd(ctx, []string{"--config", cfg}, hs.Client(), log)
		close(exited)
	}()
	stopped := false
	stop = func() {
		if stopped {
			return
		}
		stopped = true
		cancel()
		select {
		case <-exited:
		case <-time.After(5 * time.Second):
			t.Error("run did not stop")
		}
	}
	t.Cleanup(stop)
	return ch, stop
}

// waitOnline waits until n machines are online at the relay.
func waitOnline(t *testing.T, srv *relay.Server, n int) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); ; {
		online := 0
		for _, m := range srv.Machines() {
			if m.Online {
				online++
			}
		}
		if online == n {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%d machines online, want %d: %+v", online, n, srv.Machines())
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestRunRefusesSecondInstance(t *testing.T) {
	srv, hs := startRelay(t)
	cfg := writeConfig(t, hs.URL)
	_, stopFirst := startRun(t, cfg, hs, io.Discard)
	waitOnline(t, srv, 1)

	second, _ := startRun(t, cfg, hs, io.Discard)
	select {
	case err := <-second:
		if err == nil || !strings.Contains(err.Error(), fmt.Sprintf("进程号 %d", os.Getpid())) {
			t.Fatalf("second run: %v, want the first one's pid", err)
		}
	case <-time.After(time.Second):
		t.Fatal("second run with the same config is still running")
	}

	// Another config directory is another machine and may run alongside.
	_, stopOther := startRun(t, writeConfig(t, hs.URL), hs, io.Discard)
	waitOnline(t, srv, 2)
	stopOther()

	stopFirst()
	waitOnline(t, srv, 0)
	startRun(t, cfg, hs, io.Discard)
	waitOnline(t, srv, 1)
}

// Two config directories sharing one machine id, as after copying the
// directory to another computer: the second is refused and exits instead
// of the two replacing each other at the relay every second.
func TestRunRefusedBySameMachine(t *testing.T) {
	srv, hs := startRelay(t)
	first := writeConfig(t, hs.URL)
	var log bytes.Buffer
	_, stopFirst := startRun(t, first, hs, &log)
	waitOnline(t, srv, 1)

	second := writeConfig(t, hs.URL)
	id, err := os.ReadFile(filepath.Join(filepath.Dir(first), "machine-id"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(filepath.Dir(second), "machine-id"), id, 0o600); err != nil {
		t.Fatal(err)
	}
	done, _ := startRun(t, second, hs, io.Discard)
	select {
	case err := <-done:
		if !errors.Is(err, conn.ErrMachineInUse) {
			t.Fatalf("second run: %v, want ErrMachineInUse", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("second run with the same machine id is still running")
	}

	for end := time.Now().Add(time.Second); time.Now().Before(end); time.Sleep(10 * time.Millisecond) {
		if ms := srv.Machines(); len(ms) != 1 || !ms[0].Online {
			t.Fatalf("machines = %+v, want the first one online throughout", ms)
		}
	}
	stopFirst()
	if n := strings.Count(log.String(), "relay connected"); n != 1 {
		t.Errorf("first run connected %d times, want 1:\n%s", n, log.String())
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
	if err := adminCmd(ctx, "devices", []string{"--config", cfg}, hs.Client(), &out); err != nil || !strings.Contains(out.String(), "还没有已配对的设备") {
		t.Errorf("devices: %v %q", err, out.String())
	}
	if err := adminCmd(ctx, "revoke", []string{"--config", cfg}, hs.Client(), &out); err == nil {
		t.Error("revoke without an id succeeded")
	}
	if err := adminCmd(ctx, "revoke", []string{"--config", cfg, "nope"}, hs.Client(), &out); err == nil {
		t.Error("revoking an unknown device succeeded")
	}
}
