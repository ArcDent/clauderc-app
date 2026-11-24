package relay

import (
	"fmt"
	"net/netip"
	"net/url"
	"strings"

	"github.com/BurntSushi/toml"

	"clauderc/internal/keyfile"
)

// DefaultListen is used when relay.toml has no listen address (design D12).
const DefaultListen = "127.0.0.1:8787"

// Config is the relay configuration read from relay.toml (design D12).
type Config struct {
	// Listen is the local address to listen on; a reverse proxy in front
	// provides HTTPS.
	Listen string `toml:"listen"`
	// PublicURL is the wss:// address phones and agents use, e.g.
	// "wss://relay.example.com". It goes into pairing URIs.
	PublicURL string `toml:"public_url"`
	// EnrollKeyFile holds the enroll key; its mode must be 600 or stricter.
	EnrollKeyFile string `toml:"enroll_key_file"`
	// StateFile is the JSON state file path.
	StateFile string `toml:"state_file"`
	// TrustedProxies lists networks, as CIDR, whose requests may carry the
	// client address in X-Forwarded-For, e.g. the Docker gateway in front
	// of a containerised relay. Empty means only a loopback peer is trusted.
	TrustedProxies []netip.Prefix `toml:"trusted_proxies"`

	// EnrollKey is the trimmed content of EnrollKeyFile.
	EnrollKey string `toml:"-"`
}

// LoadConfig reads relay.toml at path and the enroll key file it names.
func LoadConfig(path string) (Config, error) {
	var cfg Config
	if _, err := toml.DecodeFile(path, &cfg); err != nil {
		return Config{}, fmt.Errorf("relay config %s: %w", path, err)
	}
	if cfg.Listen == "" {
		cfg.Listen = DefaultListen
	}
	for _, f := range []struct{ name, value string }{
		{"public_url", cfg.PublicURL},
		{"enroll_key_file", cfg.EnrollKeyFile},
		{"state_file", cfg.StateFile},
	} {
		if f.value == "" {
			return Config{}, fmt.Errorf("relay config %s: missing required field %s", path, f.name)
		}
	}
	if u, err := url.Parse(cfg.PublicURL); err != nil || u.Scheme != "wss" || u.Host == "" ||
		strings.ContainsAny(cfg.PublicURL, "?#") {
		return Config{}, fmt.Errorf("relay config %s: public_url %q must be a wss:// address without query or fragment", path, cfg.PublicURL)
	}
	key, err := keyfile.Read(cfg.EnrollKeyFile)
	if err != nil {
		return Config{}, fmt.Errorf("enroll %w", err)
	}
	cfg.EnrollKey = key
	return cfg, nil
}
