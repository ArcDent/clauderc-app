package store

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// After the rename the parent directory is synced, or a power loss could
// bring back the old file.
func TestWriteSyncsParentDir(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.json")
	var synced []string
	orig := syncDir
	syncDir = func(d string) error {
		// The sync must come after the rename has put the file in place.
		if _, err := os.Stat(path); err != nil {
			t.Errorf("dir synced before the rename: %v", err)
		}
		synced = append(synced, d)
		return orig(d)
	}
	t.Cleanup(func() { syncDir = orig })

	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.AddDevice(Device{ID: "d-1", Name: "Pixel 9", CreatedAt: time.Now()}, "tok"); err != nil {
		t.Fatal(err)
	}
	if len(synced) != 1 || synced[0] != dir {
		t.Fatalf("synced %q, want [%q]", synced, dir)
	}
}
