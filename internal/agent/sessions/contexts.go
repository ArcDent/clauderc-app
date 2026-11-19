package sessions

import (
	"os"
	"sync"
	"time"

	"clauderc/internal/agent/transcript"
)

// contexts keeps what each session's transcript ends with, its context
// window use and git branch, with the size and time of the transcript it
// was read from, so an unchanged transcript is not read again on every
// poll.
type contexts struct {
	mu      sync.Mutex
	entries map[string]contextEntry
}

type contextEntry struct {
	path string
	size int64
	mod  time.Time
	tail transcript.Tail
}

// get returns the end of sessionID's transcript; its Context is nil when
// the transcript is missing or holds no reply yet. A transcript that moved,
// as when the session entered a worktree, is looked up again.
func (c *contexts) get(claudeDir, sessionID string) transcript.Tail {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[sessionID]
	var fi os.FileInfo
	var err error
	if ok {
		fi, err = os.Stat(e.path)
	}
	if !ok || err != nil {
		path, err := transcript.Find(claudeDir, sessionID)
		if err == nil {
			fi, err = os.Stat(path)
		}
		if err != nil {
			delete(c.entries, sessionID)
			return transcript.Tail{}
		}
		e = contextEntry{path: path}
	} else if fi.Size() == e.size && fi.ModTime().Equal(e.mod) {
		return e.tail
	}
	tail, err := transcript.TailOf(e.path)
	if err != nil {
		return transcript.Tail{}
	}
	if c.entries == nil {
		c.entries = map[string]contextEntry{}
	}
	c.entries[sessionID] = contextEntry{path: e.path, size: fi.Size(), mod: fi.ModTime(), tail: tail}
	return tail
}

// keep forgets every session not in ids.
func (c *contexts) keep(ids map[string]bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for id := range c.entries {
		if !ids[id] {
			delete(c.entries, id)
		}
	}
}
