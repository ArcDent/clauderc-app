// Package config loads the agent configuration (design D12).
package config

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/BurntSushi/toml"

	"clauderc/internal/keyfile"
)

// EnrollKeyEnv provides the enroll key instead of enroll_key_file and wins
// over it when set.
const EnrollKeyEnv = "CLAUDERC_ENROLL_KEY"

// Environ is the agent's environment without EnrollKeyEnv. Every process
// the agent starts gets it, so claude, git and whatever they run never
// see the enroll key.
func Environ() []string {
	var env []string
	for _, kv := range os.Environ() {
		if name, _, _ := strings.Cut(kv, "="); name != EnrollKeyEnv {
			env = append(env, kv)
		}
	}
	return env
}

// machineIDFile sits beside agent.toml, e.g. ~/.config/clauderc/machine-id.
const machineIDFile = "machine-id"

// profilesFile sits beside agent.toml too (design D5 template cache).
const profilesFile = "profiles.json"

// Config is the agent configuration read from agent.toml.
type Config struct {
	// RelayURL is the relay address; only https:// and wss:// are allowed.
	RelayURL string `toml:"relay_url"`
	// Name is the display name shown on phones; defaults to the hostname.
	Name string `toml:"name"`
	// ClaudePath is the claude executable; defaults to "claude" looked up
	// in PATH.
	ClaudePath string `toml:"claude_path"`
	// EnrollKeyFile holds the enroll key; its mode must be 600 or stricter.
	EnrollKeyFile string `toml:"enroll_key_file"`
	// ClaudeDir is Claude Code's data directory, only ever read; defaults
	// to ~/.claude.
	ClaudeDir string `toml:"claude_dir"`

	// EnrollKey comes from EnrollKeyEnv or the trimmed EnrollKeyFile.
	EnrollKey string `toml:"-"`
	// MachineID is generated on first load and kept beside agent.toml.
	MachineID string `toml:"-"`
	// ProfilesFile is the launch template cache beside agent.toml
	// (discovery.Source.ProfilesFile); it may hold credentials and is
	// written with mode 600.
	ProfilesFile string `toml:"-"`
}

// Load reads agent.toml at path, resolves the enroll key and returns the
// machine id, creating it on first run.
func Load(path string) (Config, error) {
	var cfg Config
	if _, err := toml.DecodeFile(path, &cfg); err != nil {
		return Config{}, fmt.Errorf("agent config %s: %w", path, err)
	}
	if u, err := url.Parse(cfg.RelayURL); err != nil || (u.Scheme != "https" && u.Scheme != "wss") || u.Host == "" {
		return Config{}, fmt.Errorf("agent config %s: relay_url %q must be an https:// or wss:// address", path, cfg.RelayURL)
	}
	if cfg.Name == "" {
		host, err := os.Hostname()
		if err != nil {
			return Config{}, fmt.Errorf("agent config %s: no name and hostname unavailable: %w", path, err)
		}
		cfg.Name = host
	}
	if cfg.ClaudePath == "" {
		cfg.ClaudePath = "claude"
	}
	if cfg.ClaudeDir == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return Config{}, fmt.Errorf("agent config %s: no claude_dir and home unavailable: %w", path, err)
		}
		cfg.ClaudeDir = filepath.Join(home, ".claude")
	}

	if key := strings.TrimSpace(os.Getenv(EnrollKeyEnv)); key != "" {
		cfg.EnrollKey = key
	} else if cfg.EnrollKeyFile != "" {
		key, err := keyfile.Read(cfg.EnrollKeyFile)
		if err != nil {
			return Config{}, fmt.Errorf("enroll %w", err)
		}
		cfg.EnrollKey = key
	} else {
		return Config{}, fmt.Errorf("agent config %s: set enroll_key_file or %s", path, EnrollKeyEnv)
	}

	id, err := machineID(filepath.Join(filepath.Dir(path), machineIDFile))
	if err != nil {
		return Config{}, err
	}
	cfg.MachineID = id
	cfg.ProfilesFile = filepath.Join(filepath.Dir(path), profilesFile)
	return cfg, nil
}

// machineID returns the id stored in path, generating and saving a random
// one with mode 600 when the file does not exist yet.
func machineID(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err == nil {
		if id := strings.TrimSpace(string(raw)); id != "" {
			return id, nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", fmt.Errorf("machine id: %w", err)
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("machine id: %w", err)
	}
	id := hex.EncodeToString(b)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", fmt.Errorf("machine id: %w", err)
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return "", fmt.Errorf("machine id: %w", err)
	}
	return id, nil
}
