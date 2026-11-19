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
	// JobState is Claude Code's own reading of the session, as its agent
	// view shows it, passed through: working, blocked, done or failed.
	// Blocked with State not pending means the session asked the user for
	// a reply (a "needs input:" line). Absent when claude reports none.
	JobState string `json:"jobState,omitempty"`
	// Detail is the one-line progress Claude Code keeps beside JobState,
	// e.g. the question of a session that needs a reply; absent when none.
	Detail string `json:"detail,omitempty"`
	// Agents are the subagents of a running session that still run, in
	// the order they were started; absent when none.
	Agents []Subagent `json:"agents,omitempty"`
	// Dir is the directory the session is listed under: for a session in a
	// git worktree the checkout the worktree was made from, else Cwd.
	// Older agents leave it out; use Cwd then.
	Dir string `json:"dir,omitempty"`
	// Branch is the git branch of the session's last transcript entry, or
	// the worktree's branch until the transcript is read; absent outside a
	// git repository.
	Branch string `json:"branch,omitempty"`
	// Worktree is set while the session works in a git worktree.
	Worktree *SessionWorktree `json:"worktree,omitempty"`
	// ForkOf is the short id of the session this one was forked from with
	// /fork; absent for a session that is not a fork.
	ForkOf string `json:"forkOf,omitempty"`
}

// SessionWorktree is the git worktree a session works in.
type SessionWorktree struct {
	// Name is the worktree's directory name under .claude/worktrees.
	Name string `json:"name"`
	// Path is the worktree's absolute path; git methods take it as cwd.
	Path string `json:"path"`
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
	// Trust answers yes to Claude Code's trust prompt for Cwd when the
	// launch finds the directory untrusted, then launches again; without
	// it such a launch fails with CodeNotTrusted.
	Trust bool `json:"trust,omitempty"`
	// Worktree, when set, starts the session in a new git worktree of Cwd
	// by that name (claude -w); letters, digits, ".", "_" and "-" only.
	Worktree string `json:"worktree,omitempty"`
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
