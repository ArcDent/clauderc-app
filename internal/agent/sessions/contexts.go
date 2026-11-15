package sessions

import (
	"os"
	"sync"
	"time"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// contexts keeps each session's context window use with the size and time
// of the transcript it was read from, so an unchanged transcript is not
// read again on every poll.
type contexts struct {
	mu      sync.Mutex
	entries map[string]contextEntry
}

type contextEntry struct {
	path string
	size int64
	mod  time.Time
	ctx  *protocol.SessionContext
}

// get returns the context of sessionID, nil when its transcript is missing
// or holds no reply yet.
func (c *contexts) get(claudeDir, sessionID string) *protocol.SessionContext {
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
			return nil
		}
		e = contextEntry{path: path}
	} else if fi.Size() == e.size && fi.ModTime().Equal(e.mod) {
		return e.ctx
	}
	ctx, err := transcript.ContextOf(e.path)
	if err != nil {
		return nil
	}
	if c.entries == nil {
		c.entries = map[string]contextEntry{}
	}
	c.entries[sessionID] = contextEntry{path: e.path, size: fi.Size(), mod: fi.ModTime(), ctx: ctx}
	return ctx
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
