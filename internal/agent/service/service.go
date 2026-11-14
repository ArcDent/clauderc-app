// Package service writes the user-level service definition that keeps
// `agent run` going (design D12): a launchd agent on macOS, a systemd user
// unit on Linux. It only writes the file and prints how to enable it; it
// never runs launchctl or systemctl itself.
package service

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Label names the launchd job and, with .service, the systemd unit.
const Label = "clauderc.agent"

// unitName is the systemd user unit's file name.
const unitName = "clauderc-agent.service"

// Spec is what the service runs.
type Spec struct {
	// Exe is the absolute path of the agent binary.
	Exe string
	// Config is the absolute path of agent.toml.
	Config string
	// Home is the user's home directory.
	Home string
}

// Plist returns the launchd agent for s: run at login, restarted when it
// exits, output to ~/Library/Logs/clauderc-agent.log.
func Plist(s Spec) []byte {
	esc := func(v string) string {
		var b bytes.Buffer
		_ = xml.EscapeText(&b, []byte(v))
		return b.String()
	}
	log := filepath.Join(s.Home, "Library", "Logs", "clauderc-agent.log")
	return []byte(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>` + Label + `</string>
	<key>ProgramArguments</key>
	<array>
		<string>` + esc(s.Exe) + `</string>
		<string>run</string>
		<string>--config</string>
		<string>` + esc(s.Config) + `</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>ThrottleInterval</key>
	<integer>10</integer>
	<key>StandardOutPath</key>
	<string>` + esc(log) + `</string>
	<key>StandardErrorPath</key>
	<string>` + esc(log) + `</string>
</dict>
</plist>
`)
}

// Unit returns the systemd user unit for s, restarted on failure.
func Unit(s Spec) []byte {
	return []byte(`[Unit]
Description=clauderc agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=` + quote(s.Exe) + ` run --config ` + quote(s.Config) + `
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
`)
}

// quote writes one ExecStart word, quoted when it has spaces or quotes;
// "%" is doubled because systemd expands specifiers in ExecStart.
func quote(word string) string {
	word = strings.ReplaceAll(word, "%", "%%")
	if !strings.ContainsAny(word, " \t\"'\\") {
		return word
	}
	return `"` + strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(word) + `"`
}

// Install writes the service definition for goos ("darwin" or "linux")
// under s.Home and tells w where it went and which command enables it.
// With dryRun it prints the file instead of writing it.
func Install(w io.Writer, goos string, s Spec, dryRun bool) error {
	var path, enable string
	var data []byte
	switch goos {
	case "darwin":
		path = filepath.Join(s.Home, "Library", "LaunchAgents", Label+".plist")
		data = Plist(s)
		enable = "launchctl bootstrap gui/$(id -u) " + path
	case "linux":
		path = filepath.Join(s.Home, ".config", "systemd", "user", unitName)
		data = Unit(s)
		enable = "systemctl --user daemon-reload && systemctl --user enable --now " + unitName
	default:
		return fmt.Errorf("不支持 %s，只能在 macOS 和 Linux 上安装服务", goos)
	}
	if dryRun {
		_, err := fmt.Fprintf(w, "# %s\n%s\n启用命令：\n  %s\n", path, data, enable)
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return err
	}
	_, err := fmt.Fprintf(w, "已写入 %s\n执行下面的命令启用：\n  %s\n", path, enable)
	return err
}
