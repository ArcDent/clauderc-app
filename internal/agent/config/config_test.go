package config_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/agent/config"
)

// writeConfig writes agent.toml plus an enroll key file with the given mode
// into a fresh temp dir. {{key}} in body is replaced with the key file path.
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
	cfgPath := filepath.Join(dir, "agent.toml")
	if err := os.WriteFile(cfgPath, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return cfgPath
}

const fullConfig = `
relay_url = "wss://relay.example.com"
name = "studio"
claude_path = "/opt/claude/bin/claude"
enroll_key_file = "{{key}}"
claude_dir = "/data/claude-home"
`

func TestLoad(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	path := writeConfig(t, fullConfig, 0o600, "file-key\n")
	cfg, err := config.Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.RelayURL != "wss://relay.example.com" {
		t.Errorf("RelayURL = %q", cfg.RelayURL)
	}
	if cfg.Name != "studio" {
		t.Errorf("Name = %q", cfg.Name)
	}
	if cfg.ClaudePath != "/opt/claude/bin/claude" {
		t.Errorf("ClaudePath = %q", cfg.ClaudePath)
	}
	if cfg.ClaudeDir != "/data/claude-home" {
		t.Errorf("ClaudeDir = %q", cfg.ClaudeDir)
	}
	if cfg.EnrollKey != "file-key" {
		t.Errorf("EnrollKey = %q, want trailing newline trimmed", cfg.EnrollKey)
	}
	if cfg.MachineID == "" {
		t.Error("MachineID is empty")
	}
	if want := filepath.Join(filepath.Dir(path), "profiles.json"); cfg.ProfilesFile != want {
		t.Errorf("ProfilesFile = %q, want %q beside agent.toml", cfg.ProfilesFile, want)
	}
}

func TestLoadDefaults(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	home := t.TempDir()
	t.Setenv("HOME", home)
	body := `
relay_url = "https://relay.example.com"
enroll_key_file = "{{key}}"
`
	cfg, err := config.Load(writeConfig(t, body, 0o600, "k"))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.ClaudePath != "claude" {
		t.Errorf("ClaudePath = %q, want PATH lookup name claude", cfg.ClaudePath)
	}
	if cfg.ClaudeDir != filepath.Join(home, ".claude") {
		t.Errorf("ClaudeDir = %q, want ~/.claude", cfg.ClaudeDir)
	}
	host, _ := os.Hostname()
	if cfg.Name != host {
		t.Errorf("Name = %q, want hostname %q", cfg.Name, host)
	}
}

func TestLoadKeyFileMode(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	for _, mode := range []os.FileMode{0o644, 0o640, 0o604, 0o660, 0o700, 0o500} {
		t.Run(mode.String(), func(t *testing.T) {
			_, err := config.Load(writeConfig(t, fullConfig, mode, "k"))
			if err == nil {
				t.Fatalf("mode %v accepted", mode)
			}
			if !strings.Contains(err.Error(), "600") {
				t.Errorf("error %q does not mention 600", err)
			}
		})
	}
	if _, err := config.Load(writeConfig(t, fullConfig, 0o400, "k")); err != nil {
		t.Fatalf("mode 0400 rejected: %v", err)
	}
}

func TestLoadEnvKeyWins(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "env-key")
	cfg, err := config.Load(writeConfig(t, fullConfig, 0o600, "file-key"))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.EnrollKey != "env-key" {
		t.Fatalf("EnrollKey = %q, want env-key", cfg.EnrollKey)
	}
}

// With the key in the environment the file is not needed at all, so a
// loose file mode or a missing file must not block startup.
func TestLoadEnvKeyWithoutFile(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "env-key")
	body := strings.Replace(fullConfig, `enroll_key_file = "{{key}}"`, "", 1)
	cfg, err := config.Load(writeConfig(t, body, 0o600, "unused"))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.EnrollKey != "env-key" {
		t.Fatalf("EnrollKey = %q", cfg.EnrollKey)
	}
}

func TestLoadNoKey(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	body := strings.Replace(fullConfig, `enroll_key_file = "{{key}}"`, "", 1)
	if _, err := config.Load(writeConfig(t, body, 0o600, "unused")); err == nil {
		t.Fatal("config without any enroll key accepted")
	}
	if _, err := config.Load(writeConfig(t, fullConfig, 0o600, " \n")); err == nil {
		t.Fatal("empty enroll key file accepted")
	}
}

func TestLoadRelayURL(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	for _, u := range []string{"https://relay.example.com", "wss://relay.example.com"} {
		body := strings.Replace(fullConfig, "wss://relay.example.com", u, 1)
		if _, err := config.Load(writeConfig(t, body, 0o600, "k")); err != nil {
			t.Errorf("%s rejected: %v", u, err)
		}
	}
	for _, u := range []string{
		"http://relay.example.com",
		"ws://relay.example.com",
		"relay.example.com",
		"ftp://relay.example.com",
		"wss://",
		"",
	} {
		t.Run(u, func(t *testing.T) {
			body := strings.Replace(fullConfig, "wss://relay.example.com", u, 1)
			_, err := config.Load(writeConfig(t, body, 0o600, "k"))
			if err == nil {
				t.Fatal("accepted")
			}
			if !strings.Contains(err.Error(), "relay_url") {
				t.Errorf("error %q does not name relay_url", err)
			}
		})
	}
}

func TestMachineIDStable(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "")
	path := writeConfig(t, fullConfig, 0o600, "k")
	first, err := config.Load(path)
	if err != nil {
		t.Fatalf("first Load: %v", err)
	}
	second, err := config.Load(path)
	if err != nil {
		t.Fatalf("second Load: %v", err)
	}
	if first.MachineID == "" || first.MachineID != second.MachineID {
		t.Fatalf("machine id changed: %q then %q", first.MachineID, second.MachineID)
	}

	idFile := filepath.Join(filepath.Dir(path), "machine-id")
	info, err := os.Stat(idFile)
	if err != nil {
		t.Fatalf("machine-id file beside agent.toml: %v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Errorf("machine-id mode = %#o, want 0600", mode)
	}

	// A different config dir means a different machine.
	other, err := config.Load(writeConfig(t, fullConfig, 0o600, "k"))
	if err != nil {
		t.Fatal(err)
	}
	if other.MachineID == first.MachineID {
		t.Errorf("two config dirs share machine id %q", first.MachineID)
	}
}

func TestEnvironDropsEnrollKey(t *testing.T) {
	t.Setenv(config.EnrollKeyEnv, "enroll-secret")
	t.Setenv("CLAUDERC_OTHER", "kept")
	env := strings.Join(config.Environ(), "\n")
	if strings.Contains(env, config.EnrollKeyEnv+"=") || strings.Contains(env, "enroll-secret") {
		t.Error("Environ kept the enroll key")
	}
	if !strings.Contains(env, "CLAUDERC_OTHER=kept") {
		t.Error("Environ dropped another variable")
	}
}
