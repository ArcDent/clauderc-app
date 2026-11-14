package discovery

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"clauderc/internal/protocol"
)

// Template is what a new session in a profile starts with (design D5,
// D8): the launch flags of the profile's most recent session without the
// flags bound to that session, plus its providerEnv. It survives
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
}

// templatesMu serialises the read-modify-write of profiles.json between
// concurrent List calls.
var templatesMu sync.Mutex

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
	t, ok = cache[profileID]
	return t, ok, nil
}

// LaunchTemplate returns what a new session in the profile with id
// profileID starts with (design D8): the template of the profile's most
// recent session, read from jobs/ and merged into ProfilesFile so it is
// still there after `claude rm` removed every session of the profile. ok
// is false for "other" and for profiles seen nowhere.
func (s Source) LaunchTemplate(profileID string) (Template, bool) {
	_, latest := s.scanJobs()
	if cache := s.saveTemplates(latest); cache != nil {
		latest = cache
	}
	t, ok := latest[profileID]
	return t, ok
}

// ProviderEnv returns the providerEnv in jobs/<id>/state.json of the
// session with short id id; nil when the record is missing or unreadable.
func (s Source) ProviderEnv(id string) map[string]string {
	t, ok := templateOf(protocol.Profile{}, s.readJob(id))
	if !ok {
		return nil
	}
	return t.ProviderEnv
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
// keeping the newer one per profile id, and returns the merged cache. The
// file is rewritten only when its content or mode would change. Errors
// are swallowed: the cache is best effort and must not break the list.
func (s Source) saveTemplates(found map[string]Template) map[string]Template {
	if s.ProfilesFile == "" {
		return nil
	}
	templatesMu.Lock()
	defer templatesMu.Unlock()
	cache, old, err := readTemplates(s.ProfilesFile)
	if err != nil {
		// A corrupt cache is replaced by what jobs/ shows now.
		cache, old = map[string]Template{}, nil
	}
	for id, t := range found {
		if cur, ok := cache[id]; !ok || !t.UpdatedAt.Before(cur.UpdatedAt) {
			cache[id] = t
		}
	}
	data, err := json.MarshalIndent(templatesFile{Profiles: cache}, "", "  ")
	if err != nil {
		return cache
	}
	data = append(data, '\n')
	if bytes.Equal(data, old) {
		if info, err := os.Stat(s.ProfilesFile); err == nil && info.Mode().Perm() == 0o600 {
			return cache
		}
	}
	_ = writeFileAtomic(s.ProfilesFile, data)
	return cache
}

// readTemplates returns the cache and the raw file; a missing file is an
// empty cache.
func readTemplates(path string) (map[string]Template, []byte, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return map[string]Template{}, nil, nil
	}
	if err != nil {
		return nil, nil, fmt.Errorf("profiles file: %w", err)
	}
	var f templatesFile
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, nil, fmt.Errorf("profiles file %s: %w", path, err)
	}
	if f.Profiles == nil {
		f.Profiles = map[string]Template{}
	}
	return f.Profiles, raw, nil
}

// writeFileAtomic writes data to path with mode 600 through a temp file in
// the same directory, so readers never see half a file.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".profiles-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
