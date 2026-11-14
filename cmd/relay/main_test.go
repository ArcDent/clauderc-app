package main

import (
	"os/exec"
	"path/filepath"
	"testing"
)

func buildAndRunVersion(t *testing.T, ldflags string) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "relay")
	build := exec.Command("go", "build", "-ldflags", ldflags, "-o", bin, ".")
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}
	out, err := exec.Command(bin, "--version").Output()
	if err != nil {
		t.Fatalf("relay --version failed: %v", err)
	}
	return string(out)
}

func TestVersionDefault(t *testing.T) {
	if got, want := buildAndRunVersion(t, ""), "relay dev\n"; got != want {
		t.Fatalf("output = %q, want %q", got, want)
	}
}

func TestVersionOverriddenByLdflags(t *testing.T) {
	if got, want := buildAndRunVersion(t, "-X main.version=1.2.3"), "relay 1.2.3\n"; got != want {
		t.Fatalf("output = %q, want %q", got, want)
	}
}
