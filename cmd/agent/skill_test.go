package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSkillInstallWritesOnceAndThenSaysUpToDate(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".claude", "skills", "clauderc-show", "SKILL.md")
	var out bytes.Buffer
	if err := skillCmd(home, &out); err != nil {
		t.Fatal(err)
	}
	text, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(text), "clauderc show") || !strings.Contains(string(text), "name: clauderc-show") {
		t.Fatalf("SKILL.md: %v, %q", err, text)
	}
	if !strings.Contains(out.String(), path) {
		t.Errorf("output %q, want the full path %s", out.String(), path)
	}
	past := time.Now().Add(-time.Hour)
	os.Chtimes(path, past, past)
	out.Reset()
	if err := skillCmd(home, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "已是最新") {
		t.Errorf("second run said %q, want 已是最新", out.String())
	}
	if info, _ := os.Stat(path); !info.ModTime().Equal(past) {
		t.Error("the second run wrote the file again")
	}
}

func TestSkillInstallUpdatesItsOwnButLeavesOthersAlone(t *testing.T) {
	home := t.TempDir()
	path := filepath.Join(home, ".claude", "skills", "clauderc-show", "SKILL.md")
	os.MkdirAll(filepath.Dir(path), 0o755)

	os.WriteFile(path, []byte("---\nname: clauderc-show\n---\n"+skillMark+"\n旧版\n"), 0o644)
	var out bytes.Buffer
	if err := skillCmd(home, &out); err != nil || !strings.Contains(out.String(), "已更新") {
		t.Fatalf("an older copy: %v, %q", err, out.String())
	}
	if text, _ := os.ReadFile(path); string(text) != skillText {
		t.Error("the older copy was not replaced")
	}

	mine := "---\nname: clauderc-show\ndescription: 用户自己写的\n---\n"
	os.WriteFile(path, []byte(mine), 0o644)
	out.Reset()
	err := skillCmd(home, &out)
	if err == nil || !strings.Contains(err.Error(), "不是 clauderc 写的") {
		t.Errorf("someone else's skill: error %v, want a refusal saying why", err)
	}
	if text, _ := os.ReadFile(path); string(text) != mine {
		t.Error("someone else's skill was overwritten")
	}
}
