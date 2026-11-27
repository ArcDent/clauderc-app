package discovery

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"clauderc/internal/keyfile"
	"clauderc/internal/protocol"
)

// Template is how the profile's most recent session was launched (design
// D5, D8): its launch flags without the flags bound to that session, plus
// its providerEnv. A new session on the profile as a preset reuses its
// --settings and providerEnv, and phones see its models. It survives
// `claude rm` removing every jobs/<id>/ of the profile.
//
// Flags may carry credentials inside --settings: never log a Template or
// put it in anything sent to phones.
type Template struct {
	Profile protocol.Profile `json:"profile"`
	// Flags are the respawnFlags without -n, --name and --resume.
	Flags []string `json:"flags"`
	// ProviderEnv is added to the environment of the new claude.
	ProviderEnv map[string]string `json:"providerEnv"`
	// UpdatedAt is the updatedAt of the session the template comes from.
	UpdatedAt time.Time `json:"updatedAt"`
}

// templatesFile is the content of profiles.json, keyed by profile id.
type templatesFile struct {
	Profiles map[string]Template `json:"profiles"`
	// Forgotten holds deletion times so old job records cannot restore profiles.
	Forgotten map[string]time.Time `json:"forgotten,omitempty"`
}

// templatesMu serialises the read-modify-write of profiles.json between
// List and Forget calls.
var templatesMu sync.Mutex

// ErrProfileFixed rejects forgetting the default and other profiles.
var ErrProfileFixed = errors.New("cannot forget a fixed profile")

// ErrProfileInUse rejects forgetting a profile that still has sessions.
var ErrProfileInUse = errors.New("cannot forget a profile with sessions")

// Template returns the cached template of the profile with id profileID,
// as a phone names it in session.create. ok is false when the profile has
// no template: always for "other", and when ProfilesFile is empty or has
// never seen the profile. An unreadable cache is an error.
func (s Source) Template(profileID string) (t Template, ok bool, err error) {
	if s.ProfilesFile == "" {
		return Template{}, false, nil
	}
	templatesMu.Lock()
	defer templatesMu.Unlock()
	cache, _, err := readTemplates(s.ProfilesFile)
	if err != nil {
		return Template{}, false, err
	}
	t, ok = cache.Profiles[profileID]
	return t, ok, nil
}

// Forget removes an unused profile's template and records when it was deleted.
func (s Source) Forget(ctx context.Context, profileID string) error {
	if profileID == profileDefault.ID || profileID == profileOther.ID {
		return ErrProfileFixed
	}
	if s.ProfilesFile == "" {
		return errors.New("discovery: ProfilesFile is not set")
	}
	templatesMu.Lock()
	defer templatesMu.Unlock()
	sessions, _, _, err := s.sessions(ctx)
	if err != nil {
		return err
	}
	for _, session := range sessions {
		if session.ProfileID == profileID {
			return ErrProfileInUse
		}
	}
	cache, _, err := readTemplates(s.ProfilesFile)
	if err != nil {
		return err
	}
	delete(cache.Profiles, profileID)
	if cache.Forgotten == nil {
		cache.Forgotten = map[string]time.Time{}
	}
	cache.Forgotten[profileID] = time.Now().UTC()
	data, err := json.MarshalIndent(cache, "", "  ")
	if err != nil {
		return err
	}
	return keyfile.Write(s.ProfilesFile, append(data, '\n'))
}

// SessionTemplate returns what a copy of the session with short id id
// starts with (design D8): its own launch flags without the ones bound to
// it, plus its providerEnv. ok is false when its record is missing or has
// no launch flags.
func (s Source) SessionTemplate(id string) (Template, bool) {
	job := s.readJob(id)
	if job.RespawnFlags == nil {
		return Template{}, false
	}
	return templateOf(profileOfJob(job), job)
}

// templateOf builds the template of profile p from one job record; ok is
// false when its providerEnv is not a string map.
func templateOf(p protocol.Profile, job jobState) (Template, bool) {
	env := map[string]string{}
	if len(job.ProviderEnv) > 0 && string(job.ProviderEnv) != "null" {
		if json.Unmarshal(job.ProviderEnv, &env) != nil {
			return Template{}, false
		}
	}
	return Template{
		Profile:     p,
		Flags:       sessionFreeFlags(job.RespawnFlags),
		ProviderEnv: env,
		UpdatedAt:   job.UpdatedAt,
	}, true
}

// ModelEnv names the environment variable behind each model tier.
var ModelEnv = map[protocol.ModelTier]string{
	protocol.ModelMain:     "ANTHROPIC_MODEL",
	protocol.ModelDefault:  "ANTHROPIC_DEFAULT_MODEL",
	protocol.ModelOpus:     "ANTHROPIC_DEFAULT_OPUS_MODEL",
	protocol.ModelSonnet:   "ANTHROPIC_DEFAULT_SONNET_MODEL",
	protocol.ModelHaiku:    "ANTHROPIC_DEFAULT_HAIKU_MODEL",
	protocol.ModelFable:    "ANTHROPIC_DEFAULT_FABLE_MODEL",
	protocol.ModelSubagent: "CLAUDE_CODE_SUBAGENT_MODEL",
}

// modelsOf returns the models a template starts with: each tier's
// variable in ProviderEnv, the main tier taken from a --model flag first
// since claude prefers it over ANTHROPIC_MODEL. Nil when there is none.
func modelsOf(t Template) map[protocol.ModelTier]string {
	models := map[protocol.ModelTier]string{}
	for tier, name := range ModelEnv {
		if v := t.ProviderEnv[name]; v != "" {
			models[tier] = v
		}
	}
	for i, f := range t.Flags {
		if f == "--model" && i+1 < len(t.Flags) && t.Flags[i+1] != "" {
			models[protocol.ModelMain] = t.Flags[i+1]
		} else if m, ok := strings.CutPrefix(f, "--model="); ok && m != "" {
			models[protocol.ModelMain] = m
		}
	}
	if len(models) == 0 {
		return nil
	}
	return models
}

// sessionFreeFlags drops the flags that tie a launch to one session:
// -n/--name with its value and --resume/-r with its optional value, in
// both the separate and the --flag=value form.
func sessionFreeFlags(flags []string) []string {
	out := []string{}
	for i := 0; i < len(flags); i++ {
		f := flags[i]
		switch {
		case f == "-n" || f == "--name":
			i++
		case f == "--resume" || f == "-r":
			if i+1 < len(flags) && !strings.HasPrefix(flags[i+1], "-") {
				i++
			}
		case strings.HasPrefix(f, "--name=") || strings.HasPrefix(f, "--resume="):
		default:
			out = append(out, f)
		}
	}
	return out
}

// saveTemplates merges the templates found in jobs/ into ProfilesFile,
// keeping the newer one per profile id, and returns the merged templates.
// The caller holds templatesMu from reading the cache through this write.
// Errors are swallowed: the cache is best effort and must not break the list.
func (s Source) saveTemplates(found map[string]Template, cache templatesFile, old []byte) map[string]Template {
	if cache.Profiles == nil {
		// A corrupt cache is replaced by what jobs/ shows now.
		cache.Profiles = map[string]Template{}
	}
	for id, t := range found {
		if at, ok := cache.Forgotten[id]; ok {
			if !t.UpdatedAt.After(at) {
				continue
			}
			delete(cache.Forgotten, id)
		}
		if cur, ok := cache.Profiles[id]; !ok || !t.UpdatedAt.Before(cur.UpdatedAt) {
			cache.Profiles[id] = t
		}
	}
	data, err := json.MarshalIndent(cache, "", "  ")
	if err != nil {
		return cache.Profiles
	}
	data = append(data, '\n')
	if bytes.Equal(data, old) {
		if info, err := os.Stat(s.ProfilesFile); err == nil && info.Mode().Perm() == 0o600 {
			return cache.Profiles
		}
	}
	_ = keyfile.Write(s.ProfilesFile, data)
	return cache.Profiles
}

// readTemplates returns the cache and the raw file; a missing file is an
// empty cache.
func readTemplates(path string) (templatesFile, []byte, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return templatesFile{Profiles: map[string]Template{}}, nil, nil
	}
	if err != nil {
		return templatesFile{}, nil, fmt.Errorf("profiles file: %w", err)
	}
	var f templatesFile
	if err := json.Unmarshal(raw, &f); err != nil {
		return templatesFile{}, nil, fmt.Errorf("profiles file %s: %w", path, err)
	}
	if f.Profiles == nil {
		f.Profiles = map[string]Template{}
	}
	return f, raw, nil
}
