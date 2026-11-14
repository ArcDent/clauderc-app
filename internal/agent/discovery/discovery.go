// Package discovery lists background sessions and their launch profiles
// from the claude CLI and Claude Code's data directory (design D5), and
// keeps the per-profile launch templates in profiles.json.
//
// ClaudeDir is only ever read; the one file this package writes is
// ProfilesFile in the agent's own config directory. Formats are those of
// Claude Code 2.1.323.
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
	// Running is false when claude reports no pid: the session is stopped.
	Running bool
	// Status and State are claude's own status and state of the session.
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
	case t.Status == "busy":
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
	cmd := exec.CommandContext(ctx, s.ClaudePath, "agents", "--json", "--all")
	cmd.Env = config.Environ()
	cmd.WaitDelay = claudeWaitDelay
	out, err := cmd.Output()
	if err != nil {
		return nil, nil, nil, fmt.Errorf("claude agents: %w", err)
	}
	var entries []agentEntry
	if err := json.Unmarshal(out, &entries); err != nil {
		return nil, nil, nil, fmt.Errorf("claude agents: parse output: %w", err)
	}
	sessions := []protocol.Session{}
	profiles := []protocol.Profile{}
	targets := []Target{}
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
		t := Target{ID: e.ID, SessionID: e.SessionID, Cwd: e.Cwd, Running: e.PID != nil, Status: e.Status, State: e.State}
		sessions = append(sessions, protocol.Session{
			ID:   e.ID,
			Name: e.Name,
			Cwd:  e.Cwd,
			// Before the transcript check a blocked session counts as
			// pending; callers settle it with Target.SessionState.
			State:        t.SessionState(t.Blocked()),
			ProfileID:    p.ID,
			LastActiveAt: last.UTC().Truncate(time.Second),
		})
		profiles = append(profiles, p)
		targets = append(targets, t)
	}
	return sessions, profiles, targets, nil
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
