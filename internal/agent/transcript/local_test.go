package transcript_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/agent/transcript"
)

// Enabled plugins add their commands and skills as "<plugin>:<name>";
// disabled and uninstalled ones add nothing.
func TestLocalCommandsIncludeEnabledPlugins(t *testing.T) {
	claudeDir := t.TempDir()
	write := func(path, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	demo := filepath.Join(claudeDir, "plugins", "cache", "mk", "demo", "1.0.0")
	off := filepath.Join(claudeDir, "plugins", "cache", "mk", "off", "1.0.0")
	write(filepath.Join(demo, "commands", "run.md"), "---\ndescription: 运行演示\n---\nbody\n")
	write(filepath.Join(demo, "skills", "check", "SKILL.md"), "---\nname: check\ndescription: 检查一下\n---\n")
	write(filepath.Join(demo, "skills", "inner", "SKILL.md"), "---\nname: inner\nuser-invocable: false\ndescription: 只给模型用\n---\n")
	write(filepath.Join(off, "commands", "x.md"), "---\ndescription: 不该出现\n---\n")
	write(filepath.Join(claudeDir, "settings.json"), `{"enabledPlugins":{"demo@mk":true,"off@mk":false,"gone@mk":true}}`)
	write(filepath.Join(claudeDir, "plugins", "installed_plugins.json"),
		`{"version":2,"plugins":{"demo@mk":[{"scope":"user","installPath":"`+demo+`"}],"off@mk":[{"scope":"user","installPath":"`+off+`"}]}}`)

	got := map[string]string{}
	for _, c := range transcript.LocalCommands(claudeDir, "", true, true) {
		got[c.Name] = c.Description
	}
	want := map[string]string{"demo:run": "运行演示", "demo:check": "检查一下"}
	for name, desc := range want {
		if got[name] != desc {
			t.Errorf("%s = %q, want %q (all: %v)", name, got[name], desc, got)
		}
	}
	for name := range got {
		if strings.HasPrefix(name, "off:") || name == "demo:inner" {
			t.Errorf("%s listed", name)
		}
	}
}

// Without settings or the install record no plugin is read.
func TestLocalCommandsWithoutPluginFiles(t *testing.T) {
	if got := transcript.LocalCommands(t.TempDir(), "", true, true); len(got) != 0 {
		t.Errorf("got %v", got)
	}
}
