package store_test

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
	"time"

	"clauderc/internal/relay/store"
)

var (
	t0 = time.Date(2026, 11, 12, 9, 30, 0, 0, time.UTC)
	t1 = time.Date(2026, 11, 12, 10, 0, 0, 0, time.UTC)
)

const token = "tok-9f8e7d6c5b4a39281706f5e4d3c2b1a0"

func openTemp(t *testing.T) (*store.Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := store.Open(path)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return s, path
}

func TestOpenMissingFileIsEmpty(t *testing.T) {
	s, _ := openTemp(t)
	if got := s.Machines(); len(got) != 0 {
		t.Fatalf("Machines = %v, want none", got)
	}
	if got := s.Devices(); len(got) != 0 {
		t.Fatalf("Devices = %v, want none", got)
	}
}

func TestOpenCorruptFileFails(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Open(path); err == nil {
		t.Fatal("Open accepted a corrupt file")
	}
}

func TestReopenReadsBack(t *testing.T) {
	s, path := openTemp(t)
	m := store.Machine{ID: "m-7f3a", Name: "mac-mini", AgentVersion: "0.1.0", ClaudeVersion: "2.1.323", LastSeenAt: t0}
	if err := s.SaveMachine(m); err != nil {
		t.Fatalf("SaveMachine: %v", err)
	}
	m.LastSeenAt = t1
	if err := s.SaveMachine(m); err != nil {
		t.Fatalf("SaveMachine update: %v", err)
	}
	d := store.Device{ID: "d-1", Name: "Pixel 9", CreatedAt: t0}
	if err := s.AddDevice(d, token); err != nil {
		t.Fatalf("AddDevice: %v", err)
	}

	r, err := store.Open(path)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	machines := r.Machines()
	if len(machines) != 1 {
		t.Fatalf("Machines = %+v, want one", machines)
	}
	got := machines[0]
	if got.ID != "m-7f3a" || got.Name != "mac-mini" || got.AgentVersion != "0.1.0" ||
		got.ClaudeVersion != "2.1.323" || !got.LastSeenAt.Equal(t1) {
		t.Fatalf("machine = %+v", got)
	}
	devices := r.Devices()
	if len(devices) != 1 || devices[0].ID != "d-1" || devices[0].Name != "Pixel 9" || !devices[0].CreatedAt.Equal(t0) {
		t.Fatalf("Devices = %+v", devices)
	}
	dev, ok := r.VerifyToken(token)
	if !ok || dev.ID != "d-1" {
		t.Fatalf("VerifyToken after reopen = %+v, %v", dev, ok)
	}
	if _, ok := r.VerifyToken(token + "x"); ok {
		t.Fatal("VerifyToken accepted a wrong token")
	}
}

func TestFileModeAndNoPlaintextToken(t *testing.T) {
	s, path := openTemp(t)
	if err := s.AddDevice(store.Device{ID: "d-1", Name: "Pixel 9", CreatedAt: t0}, token); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Fatalf("mode = %#o, want 0600", mode)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, []byte(token)) {
		t.Fatalf("state file contains the plaintext token:\n%s", raw)
	}
	sum := sha256.Sum256([]byte(token))
	if !bytes.Contains(raw, []byte(hex.EncodeToString(sum[:]))) {
		t.Fatalf("state file lacks the SHA-256 hex digest of the token:\n%s", raw)
	}
}

func TestRemoveDevice(t *testing.T) {
	s, path := openTemp(t)
	if err := s.AddDevice(store.Device{ID: "d-1", Name: "Pixel 9", CreatedAt: t0}, token); err != nil {
		t.Fatal(err)
	}
	removed, err := s.RemoveDevice("d-1")
	if err != nil || !removed {
		t.Fatalf("RemoveDevice = %v, %v", removed, err)
	}
	if _, ok := s.VerifyToken(token); ok {
		t.Fatal("token still valid after removal")
	}
	if removed, err := s.RemoveDevice("d-1"); err != nil || removed {
		t.Fatalf("second RemoveDevice = %v, %v, want false, nil", removed, err)
	}
	r, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := r.VerifyToken(token); ok {
		t.Fatal("token valid after reopen")
	}
	if len(r.Devices()) != 0 {
		t.Fatalf("Devices after reopen = %+v", r.Devices())
	}
}

// A failed save must leave the previous file intact and not change what the
// store reports. The directory is made read-only so the save cannot finish;
// an in-place rewrite of the existing 0600 file would still succeed.
func TestFailedSaveKeepsOriginal(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root ignores directory permissions")
	}
	s, path := openTemp(t)
	if err := s.SaveMachine(store.Machine{ID: "m-1", Name: "old", LastSeenAt: t0}); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Dir(path)
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(dir, 0o700) })

	if err := s.SaveMachine(store.Machine{ID: "m-1", Name: "new", LastSeenAt: t1}); err == nil {
		t.Fatal("SaveMachine succeeded in a read-only directory")
	}
	if err := s.AddDevice(store.Device{ID: "d-1", Name: "Pixel 9", CreatedAt: t0}, token); err == nil {
		t.Fatal("AddDevice succeeded in a read-only directory")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, after) {
		t.Fatalf("state file changed\nbefore: %s\nafter:  %s", before, after)
	}
	if got := s.Machines(); len(got) != 1 || got[0].Name != "old" {
		t.Fatalf("Machines after failed save = %+v", got)
	}
	if _, ok := s.VerifyToken(token); ok {
		t.Fatal("token valid although AddDevice failed")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("directory has leftovers: %v", entries)
	}
}
