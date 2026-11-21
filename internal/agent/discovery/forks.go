package discovery

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"

	"clauderc/internal/keyfile"
)

// forksName is the file, beside ProfilesFile, recording the source of each
// copy the agent forked itself: Claude Code writes forkParentSessionId
// only for /fork, and a session launched with --setting-sources refuses
// /fork, so the agent forks those with --resume --fork-session.
const forksName = "forks.json"

// forksMu serialises the read-modify-write of forks.json.
var forksMu sync.Mutex

// forksPath is where the fork records live, "" without ProfilesFile.
func (s Source) forksPath() string {
	if s.ProfilesFile == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(s.ProfilesFile), forksName)
}

// forks returns the recorded sources, full session ids keyed by the short
// id of the copy; a missing or unreadable file records none.
func (s Source) forks() map[string]string {
	m := map[string]string{}
	if p := s.forksPath(); p != "" {
		if raw, err := os.ReadFile(p); err == nil {
			_ = json.Unmarshal(raw, &m)
		}
	}
	return m
}

// RecordFork notes that the session with short id copy was forked from
// the session with full id parent, dropping the records of copies whose
// job record is gone. It does nothing without ProfilesFile.
func (s Source) RecordFork(copy, parent string) error {
	p := s.forksPath()
	if p == "" {
		return nil
	}
	forksMu.Lock()
	defer forksMu.Unlock()
	m := s.forks()
	for id := range m {
		if _, err := os.Stat(filepath.Join(s.ClaudeDir, "jobs", id)); err != nil {
			delete(m, id)
		}
	}
	m[copy] = parent
	raw, err := json.Marshal(m)
	if err != nil {
		return err
	}
	return keyfile.Write(p, raw)
}
