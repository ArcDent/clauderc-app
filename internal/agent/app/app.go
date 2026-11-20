// Package app assembles the agent behind `agent run`: the relay
// connection with every protocol handler registered on it.
package app

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os/exec"
	"strings"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/config"
	"clauderc/internal/agent/conn"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/gitops"
	"clauderc/internal/agent/lifecycle"
	"clauderc/internal/agent/sessions"
	"clauderc/internal/agent/usage"
)

// Options configures Run.
type Options struct {
	Config config.Config
	// Version is the agent version reported to the relay.
	Version string
	// HTTPClient dials the relay; nil uses the default client.
	HTTPClient *http.Client
	Logger     *slog.Logger
	// Attach replaces the hidden attach settings; tests point it at a
	// fake. The zero value attaches with the configured claude.
	Attach attach.Options
}

// Run serves the relay until ctx ends; it returns nil then.
func Run(ctx context.Context, o Options) error {
	cfg := o.Config
	log := o.Logger
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	src := discovery.Source{ClaudePath: cfg.ClaudePath, ClaudeDir: cfg.ClaudeDir, ProfilesFile: cfg.ProfilesFile}
	c, err := conn.New(conn.Options{
		RelayURL:      cfg.RelayURL,
		EnrollKey:     cfg.EnrollKey,
		MachineID:     cfg.MachineID,
		Name:          cfg.Name,
		AgentVersion:  o.Version,
		ClaudeVersion: claudeVersion(ctx, cfg.ClaudePath),
		HTTPClient:    o.HTTPClient,
		Logger:        log,
	})
	if err != nil {
		return err
	}
	// list is set below, before any request can arrive.
	var list *sessions.Handler
	life := lifecycle.New(lifecycle.Options{Source: src, Dir: cfg.LaunchDir, Logger: log, Changed: func() { list.Refresh() }})
	plan := usage.New(usage.Options{ClaudeDir: cfg.ClaudeDir, UserAgent: "clauderc/" + o.Version, Logger: log})
	chats := chat.New(chat.Options{Source: src, Push: c, Waker: life, Attach: o.Attach, ResumeFile: cfg.ResumeFile, Usage: plan.Get, Logger: log})
	defer chats.Close()
	list = sessions.New(sessions.Options{Source: src, Push: c, Dialog: chats.HasDialog})
	defer list.Close()
	if err := c.Register(list, chats, life, gitops.New(gitops.Options{Source: src, Logger: log}), plan); err != nil {
		return err
	}
	log.Info("agent starting", "version", o.Version, "machine", cfg.MachineID, "name", cfg.Name)
	if err := c.Run(ctx); !errors.Is(err, context.Canceled) {
		return err
	}
	return nil
}

// claudeVersion asks claude for its version, e.g. "2.1.323", or returns
// "" when that fails.
func claudeVersion(ctx context.Context, claudePath string) string {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, claudePath, "--version")
	cmd.Env = config.Environ()
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	if f := strings.Fields(string(out)); len(f) > 0 {
		return f[0]
	}
	return ""
}
