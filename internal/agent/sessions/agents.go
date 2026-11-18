package sessions

import (
	"sync"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// agents keeps a transcript.AgentWatch per running session, so each poll
// only reads what its transcript gained.
type agents struct {
	mu      sync.Mutex
	watches map[string]*transcript.AgentWatch
}

// get returns the subagents of sessionID that still run; nil when there
// are none or its transcript is missing.
func (a *agents) get(claudeDir, sessionID string) []protocol.Subagent {
	a.mu.Lock()
	defer a.mu.Unlock()
	w := a.watches[sessionID]
	if w == nil {
		path, err := transcript.Find(claudeDir, sessionID)
		if err != nil {
			return nil
		}
		w = transcript.WatchAgents(path)
		if a.watches == nil {
			a.watches = map[string]*transcript.AgentWatch{}
		}
		a.watches[sessionID] = w
	}
	running, err := w.Running()
	if err != nil {
		delete(a.watches, sessionID)
		return nil
	}
	return running
}

// keep forgets every session not in ids.
func (a *agents) keep(ids map[string]bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for id := range a.watches {
		if !ids[id] {
			delete(a.watches, id)
		}
	}
}
