package protocol

import "time"

// SessionState is the displayed state of a background session (design D5).
type SessionState string

const (
	StateWorking SessionState = "working"
	StateIdle    SessionState = "idle"
	StatePending SessionState = "pending"
	StateStopped SessionState = "stopped"
)

// ProfileKind tells how a launch profile was identified (design D5).
type ProfileKind string

const (
	// ProfileDefault: launched without --settings.
	ProfileDefault ProfileKind = "default"
	// ProfileEndpoint: launched with --settings pointing at an API host.
	ProfileEndpoint ProfileKind = "endpoint"
	// ProfileOther: launch flags missing or unreadable; cannot create sessions.
	ProfileOther ProfileKind = "other"
)

// Profile is one launch profile group on a machine. Profiles seen only in
// past job records are listed too, so empty groups stay visible.
type Profile struct {
	// ID is stable per machine: "default", "other", or for ProfileEndpoint
	// the ANTHROPIC_BASE_URL host itself.
	ID   string      `json:"id"`
	Kind ProfileKind `json:"kind"`
}

// Session is one background session.
type Session struct {
	ID    string       `json:"id"`
	Name  string       `json:"name"`
	Cwd   string       `json:"cwd"`
	State SessionState `json:"state"`
	// ProfileID references Profile.ID.
	ProfileID    string    `json:"profileId"`
	LastActiveAt time.Time `json:"lastActiveAt"`
	// Context is how full the context window was after the last reply;
	// absent before the first reply.
	Context *SessionContext `json:"context,omitempty"`
}

// SessionContext is the context window use of a session.
type SessionContext struct {
	// UsedTokens is the prompt size of the last reply: input plus cache
	// reads and writes.
	UsedTokens int `json:"usedTokens"`
	// WindowTokens is the model's context window.
	WindowTokens int `json:"windowTokens"`
}

// SessionList answers sessions.subscribe and is the body of sessions.changed.
type SessionList struct {
	Sessions []Session `json:"sessions"`
	Profiles []Profile `json:"profiles"`
}

// SessionCreateRequest starts a background session in a profile (design D8).
type SessionCreateRequest struct {
	ProfileID string `json:"profileId"`
	Cwd       string `json:"cwd"`
	Name      string `json:"name,omitempty"`
	// Prompt is the optional first message.
	Prompt string `json:"prompt,omitempty"`
}

// SessionCreateResponse answers session.create.
type SessionCreateResponse struct {
	SessionID string `json:"sessionId"`
}

// SessionStopRequest stops a running session.
type SessionStopRequest struct {
	SessionID string `json:"sessionId"`
}

// SessionRemoveRequest removes a stopped session from the list.
type SessionRemoveRequest struct {
	SessionID string `json:"sessionId"`
}

// DirsRecentResponse answers dirs.recent: existing working directories,
// most recently used first, at most 20.
type DirsRecentResponse struct {
	Dirs []string `json:"dirs"`
}
