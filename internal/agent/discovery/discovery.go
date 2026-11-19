// Package discovery lists background sessions and their launch profiles
// from the claude CLI and Claude Code's data directory (design D5), and
// keeps the per-profile launch templates in profiles.json.
//
// ClaudeDir is only ever read; the one file this package writes is
// ProfilesFile in the agent's own config directory. Formats are those of
// Claude Code 2.1.323, plus the `claude agents` output of 2.1.326, which
// lists background sessions without pid and status.
package discovery

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"clauderc/internal/agent/config"
	"clauderc/internal/protocol"
)

// Source reads sessions from one Claude Code installation.
type Source struct {
	// ClaudePath is the claude executable.
	ClaudePath string
	// ClaudeDir is Claude Code's data directory, normally ~/.claude.
	ClaudeDir string
	// ProfilesFile is the launch template cache, normally profiles.json
	// beside agent.toml (config.Config.ProfilesFile). Empty disables the
	// cache: List then reads profiles from jobs/ only and writes nothing.
	ProfilesFile string
}

// agentEntry is one element of `claude agents --json --all`.
type agentEntry struct {
	PID       *int   `json:"pid"`
	ID        string `json:"id"`
	Cwd       string `json:"cwd"`
	Kind      string `json:"kind"`
	StartedAt int64  `json:"startedAt"`
	Name      string `json:"name"`
	Status    string `json:"status"`
	State     string `json:"state"`
	SessionID string `json:"sessionId"`
}

// Target is what the agent needs internally to reach one background
// session. It never goes to the phone.
type Target struct {
	// ID is the short id used in the protocol.
	ID string
	// SessionID is the full session id naming the transcript file.
	SessionID string
	Cwd       string
	// Running is false when the session is stopped: claude reports no pid,
	// or, from 2.1.326 on, no status either and leaves it out of the list
	// of active sessions.
	Running bool
	// Status and State are claude's own status and state of the session.
	// Status is empty from 2.1.326 on; State then tells a working session.
	Status string
	State  string
}

// Blocked reports whether the running session may wait for the user to
// decide on a tool call; only its transcript can tell (design D6).
func (t Target) Blocked() bool {
	return t.Running && t.State == "blocked"
}

// SessionState applies the design D5 table in order. pending tells
// whether the transcript has an unanswered tool call (design D6); it only
// counts while the session is blocked.
func (t Target) SessionState(pending bool) protocol.SessionState {
	switch {
	case !t.Running:
		return protocol.StateStopped
	case t.Blocked() && pending:
		return protocol.StatePending
	case t.Status == "busy", t.Status == "" && t.State == "working":
		return protocol.StateWorking
	default:
		return protocol.StateIdle
	}
}

// jobState is the part of jobs/<id>/state.json this package reads. The
// zero value stands for a missing or unreadable record.
type jobState struct {
	RespawnFlags []string `json:"respawnFlags"`
	// ProviderEnv stays raw so an odd value only affects the template,
	// not the profile.
	ProviderEnv json.RawMessage `json:"providerEnv"`
	Cwd         string          `json:"cwd"`
	UpdatedAt   time.Time       `json:"updatedAt"`
	// Detail is the one-line progress beside the job's state.
	Detail string `json:"detail"`
	// WorktreePath and WorktreeBranch are set once the session entered a
	// worktree; Cwd then stays the original checkout, which OriginCwd
	// names too when Claude Code writes it (2.1.327 does not).
	WorktreePath   string `json:"worktreePath"`
	WorktreeBranch string `json:"worktreeBranch"`
	OriginCwd      string `json:"originCwd"`
	// ForkParentSessionID is the full session id a /fork copy came from;
	// forkSessionId beside it is the fork's own id.
	ForkParentSessionID string `json:"forkParentSessionId"`
}

// worktreesDir is where Claude Code puts the worktrees of a checkout.
const worktreesDir = "/.claude/worktrees/"

// placeOf returns the directory a session in cwd is listed under and its
// worktree, if any: from the job record when it names one, else from a
// cwd inside <checkout>/.claude/worktrees/<name>.
func placeOf(cwd string, job jobState) (string, *protocol.SessionWorktree) {
	if job.WorktreePath != "" {
		dir := job.OriginCwd
		if dir == "" {
			dir = job.Cwd
		}
		if dir == "" {
			dir, _ = splitWorktree(job.WorktreePath)
		}
		if dir == "" {
			dir = cwd
		}
		return dir, &protocol.SessionWorktree{Name: filepath.Base(job.WorktreePath), Path: job.WorktreePath}
	}
	if dir, name := splitWorktree(cwd); dir != "" {
		return dir, &protocol.SessionWorktree{Name: name, Path: dir + worktreesDir + name}
	}
	return cwd, nil
}

// splitWorktree splits <checkout>/.claude/worktrees/<name>[/...] into the
// checkout and the worktree name; both are empty for any other path.
func splitWorktree(path string) (string, string) {
	i := strings.Index(path, worktreesDir)
	if i <= 0 {
		return "", ""
	}
	name, _, _ := strings.Cut(path[i+len(worktreesDir):], "/")
	if name == "" {
		return "", ""
	}
	return path[:i], name
}

// sessionFile is the part of sessions/<pid>.json this package reads;
// unlike jobs, its updatedAt is in milliseconds.
type sessionFile struct {
	Cwd       string `json:"cwd"`
	UpdatedAt int64  `json:"updatedAt"`
}

// maxRecentDirs caps dirs.recent (spec: at most 20).
const maxRecentDirs = 20

// shortID matches the session ids of `claude agents` (8 lowercase hex
// digits in practice; samples use other lowercase letters too).
var shortID = regexp.MustCompile(`^[0-9a-z]{8}$`)

// claudeWaitDelay bounds how long a cancelled claude call may keep its
// output pipes open before Sessions gives up on it.
const claudeWaitDelay = time.Second

// Fixed profiles; endpoint profiles use the API host as their id.
var (
	profileDefault = protocol.Profile{ID: "default", Kind: protocol.ProfileDefault}
	profileOther   = protocol.Profile{ID: "other", Kind: protocol.ProfileOther}
)

// Sessions runs `claude agents --json --all` and returns the background
// sessions in the CLI's order; foreground sessions are dropped.
func (s Source) Sessions(ctx context.Context) ([]protocol.Session, error) {
	sessions, _, _, err := s.sessions(ctx)
	return sessions, err
}

// Targets runs `claude agents --json --all` and returns the Target of each
// background session in the CLI's order.
func (s Source) Targets(ctx context.Context) ([]Target, error) {
	_, _, targets, err := s.sessions(ctx)
	return targets, err
}

// sessions is Sessions plus the full profile and the Target of each
// session, index for index, so List never has to guess a profile back
// from its id.
func (s Source) sessions(ctx context.Context) ([]protocol.Session, []protocol.Profile, []Target, error) {
	entries, err := s.agents(ctx, "--json", "--all")
	if err != nil {
		return nil, nil, nil, err
	}
	// From 2.1.326 on a background session has neither pid nor status;
	// only the list without --all tells the running ones.
	var active map[string]bool
	for _, e := range entries {
		if e.Kind == "background" && e.PID == nil && e.Status == "" {
			running, err := s.agents(ctx, "--json")
			if err != nil {
				return nil, nil, nil, err
			}
			active = map[string]bool{}
			for _, r := range running {
				active[r.ID] = true
			}
			break
		}
	}
	sessions := []protocol.Session{}
	profiles := []protocol.Profile{}
	targets := []Target{}
	// forkParents holds each fork's source session id, index for index.
	forkParents := []string{}
	for _, e := range entries {
		if e.Kind != "background" {
			continue
		}
		job := s.readJob(e.ID)
		last := time.UnixMilli(e.StartedAt)
		if !job.UpdatedAt.IsZero() {
			last = job.UpdatedAt
		}
		p := profileOfJob(job)
		running := e.PID != nil || e.Status == "" && active[e.ID]
		t := Target{ID: e.ID, SessionID: e.SessionID, Cwd: e.Cwd, Running: running, Status: e.Status, State: e.State}
		dir, worktree := placeOf(e.Cwd, job)
		branch := ""
		if worktree != nil {
			branch = job.WorktreeBranch
		}
		sessions = append(sessions, protocol.Session{
			ID:   e.ID,
			Name: e.Name,
			Cwd:  e.Cwd,
			// Before the transcript check a blocked session counts as
			// pending; callers settle it with Target.SessionState.
			State:        t.SessionState(t.Blocked()),
			ProfileID:    p.ID,
			LastActiveAt: last.UTC().Truncate(time.Second),
			JobState:     e.State,
			Detail:       strings.TrimSpace(job.Detail),
			Dir:          dir,
			Branch:       branch,
			Worktree:     worktree,
		})
		profiles = append(profiles, p)
		targets = append(targets, t)
		forkParents = append(forkParents, job.ForkParentSessionID)
	}
	short := make(map[string]string, len(targets))
	for _, t := range targets {
		short[t.SessionID] = t.ID
	}
	for i, parent := range forkParents {
		if parent == "" {
			continue
		}
		// A source no longer listed keeps Claude Code's own short id, the
		// first 8 characters of its session id.
		id, ok := short[parent]
		if !ok && len(parent) >= 8 {
			id = parent[:8]
		}
		sessions[i].ForkOf = id
	}
	return sessions, profiles, targets, nil
}

// agents runs `claude agents` with args and parses its JSON list.
func (s Source) agents(ctx context.Context, args ...string) ([]agentEntry, error) {
	cmd := exec.CommandContext(ctx, s.ClaudePath, append([]string{"agents"}, args...)...)
	cmd.Env = config.Environ()
	cmd.WaitDelay = claudeWaitDelay
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("claude agents: %w", err)
	}
	var entries []agentEntry
	if err := json.Unmarshal(out, &entries); err != nil {
		return nil, fmt.Errorf("claude agents: parse output: %w", err)
	}
	return entries, nil
}

// readJob reads the record of the session with short id id. Anything that
// is not a plain short id is refused without touching the file system, so
// an id from the CLI or a phone can never point outside jobs/.
func (s Source) readJob(id string) jobState {
	if filepath.Base(id) != id || !shortID.MatchString(id) {
		return jobState{}
	}
	return s.readJobDir(id)
}

// readJobDir reads jobs/<name>/state.json for a name listed by ReadDir.
func (s Source) readJobDir(name string) jobState {
	var job jobState
	raw, err := os.ReadFile(filepath.Join(s.ClaudeDir, "jobs", name, "state.json"))
	if err != nil || json.Unmarshal(raw, &job) != nil {
		return jobState{}
	}
	return job
}

// ProfileOf identifies the launch profile of the session with short id
// jobID from jobs/<jobID>/state.json.
func (s Source) ProfileOf(jobID string) protocol.Profile {
	return profileOfJob(s.readJob(jobID))
}

// List returns the background sessions with their profiles, plus every
// profile seen in the jobs history or the template cache, so groups
// without sessions stay listed. The "other" profile is listed only while
// a session falls into it. Templates found in jobs/ are saved to
// ProfilesFile first; failing to save them does not fail List.
func (s Source) List(ctx context.Context) (protocol.SessionList, error) {
	list, _, err := s.ListTargets(ctx)
	return list, err
}

// ListTargets is List plus the Target of each listed session, index for
// index.
func (s Source) ListTargets(ctx context.Context) (protocol.SessionList, []Target, error) {
	sessions, sessionProfiles, targets, err := s.sessions(ctx)
	if err != nil {
		return protocol.SessionList{}, nil, err
	}
	seen, latest := s.scanJobs()
	for _, p := range sessionProfiles {
		seen[p] = true
	}
	for _, t := range s.saveTemplates(latest) {
		seen[t.Profile] = true
	}

	profiles := []protocol.Profile{}
	for p := range seen {
		profiles = append(profiles, p)
	}
	rank := map[protocol.ProfileKind]int{protocol.ProfileDefault: 0, protocol.ProfileEndpoint: 1, protocol.ProfileOther: 2}
	sort.Slice(profiles, func(i, j int) bool {
		a, b := profiles[i], profiles[j]
		if rank[a.Kind] != rank[b.Kind] {
			return rank[a.Kind] < rank[b.Kind]
		}
		return a.ID < b.ID
	})
	return protocol.SessionList{Sessions: sessions, Profiles: profiles}, targets, nil
}

// scanJobs reads every jobs/*/state.json and returns the profiles seen
// there, without "other", and the template of each profile's most recent
// session.
func (s Source) scanJobs() (map[protocol.Profile]bool, map[string]Template) {
	seen := map[protocol.Profile]bool{}
	latest := map[string]Template{}
	dirs, _ := os.ReadDir(filepath.Join(s.ClaudeDir, "jobs"))
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		job := s.readJobDir(d.Name())
		p := profileOfJob(job)
		if p.Kind == protocol.ProfileOther {
			continue
		}
		seen[p] = true
		if t, ok := templateOf(p, job); ok {
			if cur, ok := latest[p.ID]; !ok || t.UpdatedAt.After(cur.UpdatedAt) {
				latest[p.ID] = t
			}
		}
	}
	return seen, latest
}

// profileOfJob applies design D5: no --settings is the default profile, a
// --settings JSON with env.ANTHROPIC_BASE_URL is that host's profile, and
// a missing or unreadable record (no respawnFlags) is "other".
func profileOfJob(job jobState) protocol.Profile {
	if job.RespawnFlags == nil {
		return profileOther
	}
	for i, f := range job.RespawnFlags {
		if f != "--settings" {
			continue
		}
		if i+1 >= len(job.RespawnFlags) {
			return profileOther
		}
		// Only the base URL is decoded; tokens beside it in env are skipped.
		var settings struct {
			Env struct {
				BaseURL string `json:"ANTHROPIC_BASE_URL"`
			} `json:"env"`
		}
		if json.Unmarshal([]byte(job.RespawnFlags[i+1]), &settings) != nil {
			return profileOther
		}
		if settings.Env.BaseURL == "" {
			return profileDefault
		}
		u, err := url.Parse(settings.Env.BaseURL)
		if err != nil || u.Host == "" {
			return profileOther
		}
		return protocol.Profile{ID: u.Host, Kind: protocol.ProfileEndpoint}
	}
	return profileDefault
}

// RecentDirs returns working directories from jobs/*/state.json and
// sessions/*.json, most recently updated first, without duplicates, keeping
// only directories that still exist, at most 20.
func (s Source) RecentDirs() []string {
	type use struct {
		dir string
		at  time.Time
	}
	var uses []use
	jobs, _ := os.ReadDir(filepath.Join(s.ClaudeDir, "jobs"))
	for _, d := range jobs {
		if job := s.readJobDir(d.Name()); job.Cwd != "" {
			uses = append(uses, use{job.Cwd, job.UpdatedAt})
		}
	}
	files, _ := filepath.Glob(filepath.Join(s.ClaudeDir, "sessions", "*.json"))
	for _, f := range files {
		var sf sessionFile
		raw, err := os.ReadFile(f)
		if err != nil || json.Unmarshal(raw, &sf) != nil || sf.Cwd == "" {
			continue
		}
		uses = append(uses, use{sf.Cwd, time.UnixMilli(sf.UpdatedAt)})
	}
	sort.SliceStable(uses, func(i, j int) bool { return uses[i].at.After(uses[j].at) })

	dirs := []string{}
	seen := map[string]bool{}
	for _, u := range uses {
		if len(dirs) == maxRecentDirs {
			break
		}
		if seen[u.dir] {
			continue
		}
		seen[u.dir] = true
		if info, err := os.Stat(u.dir); err == nil && info.IsDir() {
			dirs = append(dirs, u.dir)
		}
	}
	return dirs
}
