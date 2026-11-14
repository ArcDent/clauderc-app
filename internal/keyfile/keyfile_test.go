package keyfile_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/keyfile"
)

func writeKey(t *testing.T, body string, mode os.FileMode) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "enroll.key")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestReadMode(t *testing.T) {
	for _, mode := range []os.FileMode{0o644, 0o640, 0o604, 0o660, 0o700, 0o500, 0o610, 0o601} {
		t.Run(mode.String(), func(t *testing.T) {
			_, err := keyfile.Read(writeKey(t, "k", mode))
			if err == nil {
				t.Fatalf("mode %v accepted", mode)
			}
			if !strings.Contains(err.Error(), "600") {
				t.Errorf("error %q does not mention 600", err)
			}
		})
	}
	for _, mode := range []os.FileMode{0o600, 0o400} {
		key, err := keyfile.Read(writeKey(t, " secret\n", mode))
		if err != nil {
			t.Fatalf("mode %v rejected: %v", mode, err)
		}
		if key != "secret" {
			t.Fatalf("key = %q, want trimmed secret", key)
		}
	}
}

func TestReadEmptyOrMissing(t *testing.T) {
	if _, err := keyfile.Read(writeKey(t, " \n", 0o600)); err == nil {
		t.Fatal("empty key file accepted")
	}
	if _, err := keyfile.Read(filepath.Join(t.TempDir(), "missing.key")); err == nil {
		t.Fatal("missing key file accepted")
	}
}
