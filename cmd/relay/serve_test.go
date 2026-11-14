package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func buildRelay(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "relay")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}
	return bin
}

func freeAddr(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().String()
}

func writeServeConfig(t *testing.T, listen string) string {
	t.Helper()
	dir := t.TempDir()
	key := filepath.Join(dir, "enroll.key")
	if err := os.WriteFile(key, []byte("test-enroll-key\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := filepath.Join(dir, "relay.toml")
	body := fmt.Sprintf("listen = %q\npublic_url = \"wss://relay.example.com\"\nenroll_key_file = %q\nstate_file = %q\n",
		listen, key, filepath.Join(dir, "state.json"))
	if err := os.WriteFile(cfg, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return cfg
}

func TestServeHealthAndListenAddress(t *testing.T) {
	bin := buildRelay(t)
	addr := freeAddr(t)
	cmd := exec.Command(bin, "serve", "--config", writeServeConfig(t, addr))
	var stderr strings.Builder
	cmd.Stderr = &stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cmd.Process.Kill()
		cmd.Wait()
	})

	deadline := time.Now().Add(5 * time.Second)
	for {
		resp, err := http.Get("http://" + addr + "/healthz")
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("health status %d, want 200", resp.StatusCode)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("health check never answered: %v\nstderr:\n%s", err, stderr.String())
		}
		time.Sleep(50 * time.Millisecond)
	}

	if _, err := exec.LookPath("lsof"); err != nil {
		t.Skip("lsof not available to list listening sockets")
	}
	out, err := exec.Command("lsof", "-nP", "-a", "-p", fmt.Sprint(cmd.Process.Pid), "-iTCP", "-sTCP:LISTEN", "-Fn").Output()
	if err != nil {
		t.Fatalf("lsof: %v", err)
	}
	var listening []string
	for _, line := range strings.Split(string(out), "\n") {
		if name, ok := strings.CutPrefix(line, "n"); ok {
			listening = append(listening, name)
		}
	}
	if len(listening) != 1 || listening[0] != addr {
		t.Fatalf("listening on %v, want only %s", listening, addr)
	}
}

func TestServeRequiresConfig(t *testing.T) {
	bin := buildRelay(t)
	for name, args := range map[string][]string{
		"no flag":    {"serve"},
		"bad config": {"serve", "--config", filepath.Join(t.TempDir(), "missing.toml")},
	} {
		t.Run(name, func(t *testing.T) {
			if err := exec.Command(bin, args...).Run(); err == nil {
				t.Fatal("serve exited 0")
			}
		})
	}
}
