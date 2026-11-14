// Package store persists the relay's only durable state: registered
// machines and paired devices (design D4). Conversation data never goes here.
package store

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

// Machine is a registered target machine.
type Machine struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	AgentVersion  string    `json:"agentVersion"`
	ClaudeVersion string    `json:"claudeVersion"`
	LastSeenAt    time.Time `json:"lastSeenAt"`
}

// Device is a paired phone. Its token is kept only as a SHA-256 digest.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
}

type deviceRecord struct {
	Device
	TokenSHA256 string `json:"tokenSha256"`
}

type state struct {
	Machines []Machine      `json:"machines"`
	Devices  []deviceRecord `json:"devices"`
}

// Store is a JSON state file guarded by a mutex. Every change is written to
// a temp file and renamed over the old one, so a failed write leaves the
// previous file intact.
type Store struct {
	path string
	mu   sync.Mutex
	st   state
}

// Open loads the state file at path; a missing file yields an empty store.
func Open(path string) (*Store, error) {
	s := &Store{path: path, st: state{Machines: []Machine{}, Devices: []deviceRecord{}}}
	raw, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, fmt.Errorf("store: %w", err)
	}
	if err := json.Unmarshal(raw, &s.st); err != nil {
		return nil, fmt.Errorf("store: parse %s: %w", path, err)
	}
	return s, nil
}

// Machines returns all registered machines.
func (s *Store) Machines() []Machine {
	s.mu.Lock()
	defer s.mu.Unlock()
	return slices.Clone(s.st.Machines)
}

// SaveMachine inserts or replaces the machine with m.ID.
func (s *Store) SaveMachine(m Machine) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.st
	next.Machines = slices.Clone(s.st.Machines)
	i := slices.IndexFunc(next.Machines, func(x Machine) bool { return x.ID == m.ID })
	if i >= 0 {
		next.Machines[i] = m
	} else {
		next.Machines = append(next.Machines, m)
	}
	return s.commit(next)
}

// Devices returns all paired devices.
func (s *Store) Devices() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, 0, len(s.st.Devices))
	for _, d := range s.st.Devices {
		out = append(out, d.Device)
	}
	return out
}

// AddDevice records a paired device; only the digest of token is stored.
func (s *Store) AddDevice(d Device, token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.st
	next.Devices = append(slices.Clone(s.st.Devices), deviceRecord{Device: d, TokenSHA256: digest(token)})
	return s.commit(next)
}

// VerifyToken returns the device whose token matches.
func (s *Store) VerifyToken(token string) (Device, bool) {
	sum := []byte(digest(token))
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.st.Devices {
		if subtle.ConstantTimeCompare(sum, []byte(d.TokenSHA256)) == 1 {
			return d.Device, true
		}
	}
	return Device{}, false
}

// RemoveDevice deletes the device with id and reports whether it existed.
func (s *Store) RemoveDevice(id string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.st
	next.Devices = slices.DeleteFunc(slices.Clone(s.st.Devices), func(d deviceRecord) bool { return d.ID == id })
	if len(next.Devices) == len(s.st.Devices) {
		return false, nil
	}
	return true, s.commit(next)
}

func digest(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// commit writes next to disk and adopts it only if the write succeeded.
func (s *Store) commit(next state) error {
	raw, err := json.MarshalIndent(next, "", "  ")
	if err != nil {
		return fmt.Errorf("store: %w", err)
	}
	if err := writeAtomic(s.path, raw); err != nil {
		return fmt.Errorf("store: write %s: %w", s.path, err)
	}
	s.st = next
	return nil
}

func writeAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name) // no-op after a successful rename
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}
