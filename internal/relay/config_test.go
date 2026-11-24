package relay_test

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/relay"
)

// writeConfig writes a relay.toml plus an enroll key file with the given
// mode into a temp dir. The {{key}} and {{state}} placeholders in body are
// replaced with those paths.
func writeConfig(t *testing.T, body string, keyMode os.FileMode, key string) string {
	t.Helper()
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "enroll.key")
	if err := os.WriteFile(keyPath, []byte(key), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(keyPath, keyMode); err != nil {
		t.Fatal(err)
	}
	body = strings.ReplaceAll(body, "{{key}}", keyPath)
	body = strings.ReplaceAll(body, "{{state}}", filepath.Join(dir, "state.json"))
	cfgPath := filepath.Join(dir, "relay.toml")
	if err := os.WriteFile(cfgPath, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return cfgPath
}

const fullConfig = `
listen = "127.0.0.1:9000"
public_url = "wss://relay.example.com"
enroll_key_file = "{{key}}"
state_file = "{{state}}"
`

func TestLoadConfig(t *testing.T) {
	path := writeConfig(t, fullConfig, 0o600, "k3y-value\n")
	cfg, err := relay.LoadConfig(path)
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	dir := filepath.Dir(path)
	if cfg.Listen != "127.0.0.1:9000" {
		t.Errorf("Listen = %q", cfg.Listen)
	}
	if cfg.PublicURL != "wss://relay.example.com" {
		t.Errorf("PublicURL = %q", cfg.PublicURL)
	}
	if cfg.EnrollKeyFile != filepath.Join(dir, "enroll.key") {
		t.Errorf("EnrollKeyFile = %q", cfg.EnrollKeyFile)
	}
	if cfg.StateFile != filepath.Join(dir, "state.json") {
		t.Errorf("StateFile = %q", cfg.StateFile)
	}
	if cfg.EnrollKey != "k3y-value" {
		t.Errorf("EnrollKey = %q, want trailing newline trimmed", cfg.EnrollKey)
	}
}

func TestLoadConfigDefaultListen(t *testing.T) {
	body := strings.Replace(fullConfig, `listen = "127.0.0.1:9000"`, "", 1)
	cfg, err := relay.LoadConfig(writeConfig(t, body, 0o600, "k"))
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	if cfg.Listen != "127.0.0.1:8787" {
		t.Fatalf("Listen = %q, want default 127.0.0.1:8787", cfg.Listen)
	}
}

func TestLoadConfigKeyFileMode(t *testing.T) {
	for _, mode := range []os.FileMode{0o644, 0o640, 0o604, 0o660, 0o700, 0o500} {
		t.Run(mode.String(), func(t *testing.T) {
			_, err := relay.LoadConfig(writeConfig(t, fullConfig, mode, "k"))
			if err == nil {
				t.Fatalf("mode %v accepted", mode)
			}
			if !strings.Contains(err.Error(), "600") {
				t.Errorf("error %q does not mention 600", err)
			}
		})
	}
	if _, err := relay.LoadConfig(writeConfig(t, fullConfig, 0o400, "k")); err != nil {
		t.Fatalf("mode 0400 rejected: %v", err)
	}
}

func TestLoadConfigMissingField(t *testing.T) {
	for _, field := range []string{"public_url", "enroll_key_file", "state_file"} {
		t.Run(field, func(t *testing.T) {
			var kept []string
			for _, line := range strings.Split(fullConfig, "\n") {
				if !strings.HasPrefix(line, field+" ") {
					kept = append(kept, line)
				}
			}
			_, err := relay.LoadConfig(writeConfig(t, strings.Join(kept, "\n"), 0o600, "k"))
			if err == nil {
				t.Fatal("missing field accepted")
			}
			if !strings.Contains(err.Error(), field) {
				t.Errorf("error %q does not name %s", err, field)
			}
		})
	}
}

// An empty key would make "Bearer " match, so it must be refused.
func TestLoadConfigEmptyKey(t *testing.T) {
	if _, err := relay.LoadConfig(writeConfig(t, fullConfig, 0o600, " \n")); err == nil {
		t.Fatal("empty enroll key accepted")
	}
}

func TestLoadConfigPublicURLMustBeWSS(t *testing.T) {
	for _, u := range []string{
		"https://relay.example.com",
		"ws://relay.example.com",
		"relay.example.com",
		"wss://",
		"wss://relay.example.com?x=1",
		"wss://relay.example.com?",
		"wss://relay.example.com#top",
	} {
		t.Run(u, func(t *testing.T) {
			body := strings.Replace(fullConfig, "wss://relay.example.com", u, 1)
			_, err := relay.LoadConfig(writeConfig(t, body, 0o600, "k"))
			if err == nil {
				t.Fatal("accepted")
			}
			if !strings.Contains(err.Error(), "public_url") {
				t.Errorf("error %q does not name public_url", err)
			}
		})
	}
}

func TestLoadConfigTrustedProxies(t *testing.T) {
	cfg, err := relay.LoadConfig(writeConfig(t, fullConfig, 0o600, "k"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.TrustedProxies != nil {
		t.Errorf("TrustedProxies = %v, want none by default", cfg.TrustedProxies)
	}
	body := fullConfig + `trusted_proxies = ["172.16.0.0/12", "fd00::/8"]` + "\n"
	if cfg, err = relay.LoadConfig(writeConfig(t, body, 0o600, "k")); err != nil {
		t.Fatal(err)
	}
	if got := fmt.Sprint(cfg.TrustedProxies); got != "[172.16.0.0/12 fd00::/8]" {
		t.Errorf("TrustedProxies = %s", got)
	}
	for _, bad := range []string{"172.17.0.1", "docker", "172.16.0.0/40"} {
		body := fullConfig + `trusted_proxies = ["` + bad + `"]` + "\n"
		_, err := relay.LoadConfig(writeConfig(t, body, 0o600, "k"))
		if err == nil || !strings.Contains(err.Error(), "trusted_proxies") {
			t.Errorf("%q: error %v, want one naming trusted_proxies", bad, err)
		}
	}
}
