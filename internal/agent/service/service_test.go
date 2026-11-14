package service

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestPlistLints(t *testing.T) {
	plutil, err := exec.LookPath("plutil")
	if err != nil {
		t.Skip("plutil is only on macOS")
	}
	home := t.TempDir()
	s := Spec{Exe: "/Applications/My Tools/agent", Config: home + "/.config/clauderc/a&b <x>.toml", Home: home}
	var out bytes.Buffer
	if err := Install(&out, "darwin", s, false); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(home, "Library", "LaunchAgents", "clauderc.agent.plist")
	if lint, err := exec.Command(plutil, "-lint", path).CombinedOutput(); err != nil {
		t.Fatalf("plutil -lint: %v\n%s", err, lint)
	}
	args, err := exec.Command(plutil, "-extract", "ProgramArguments", "json", "-o", "-", path).Output()
	if err != nil {
		t.Fatal(err)
	}
	want := `["\/Applications\/My Tools\/agent","run","--config","` + strings.ReplaceAll(s.Config, "/", `\/`) + `"]`
	if strings.TrimSpace(string(args)) != want {
		t.Errorf("ProgramArguments = %s, want %s", args, want)
	}
	if !strings.Contains(out.String(), "launchctl bootstrap gui/$(id -u) "+path) {
		t.Errorf("output does not give the enable command:\n%s", out.String())
	}
}

func TestUnit(t *testing.T) {
	got := string(Unit(Spec{Exe: "/home/me/bin/agent", Config: "/home/me/.config/clauderc/agent.toml"}))
	want := `[Unit]
Description=clauderc agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/home/me/bin/agent run --config /home/me/.config/clauderc/agent.toml
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
`
	if got != want {
		t.Errorf("unit =\n%s\nwant\n%s", got, want)
	}
	odd := string(Unit(Spec{Exe: "/opt/my tools/agent", Config: `/c/100% "x".toml`}))
	if !strings.Contains(odd, `ExecStart="/opt/my tools/agent" run --config "/c/100%% \"x\".toml"`) {
		t.Errorf("paths with spaces, quotes and %% are not escaped:\n%s", odd)
	}
}

func TestInstallLinux(t *testing.T) {
	home := t.TempDir()
	s := Spec{Exe: "/usr/local/bin/agent", Config: home + "/agent.toml", Home: home}
	var out bytes.Buffer
	if err := Install(&out, "linux", s, true); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(home, ".config", "systemd", "user", "clauderc-agent.service")
	if _, err := os.Stat(path); err == nil {
		t.Error("dry run wrote the unit")
	}
	if !strings.Contains(out.String(), string(Unit(s))) {
		t.Errorf("dry run does not print the unit:\n%s", out.String())
	}
	out.Reset()
	if err := Install(&out, "linux", s, false); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != string(Unit(s)) {
		t.Fatalf("unit file = %q, %v", data, err)
	}
	if !strings.Contains(out.String(), "systemctl --user daemon-reload && systemctl --user enable --now clauderc-agent.service") {
		t.Errorf("output does not give the enable command:\n%s", out.String())
	}
	if err := Install(&out, "windows", s, false); err == nil {
		t.Error("windows was accepted")
	}
}
