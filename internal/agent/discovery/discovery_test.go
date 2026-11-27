package discovery_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/protocol"
)

// sampleSource points both the fake claude and the data dir at a sample
// directory under testdata, so the real ~/.claude and claude are never used.
func sampleSource(t *testing.T, sample string) discovery.Source {
	t.Helper()
	dir, err := filepath.Abs(filepath.Join("testdata", sample))
	if err != nil {
		t.Fatal(err)
	}
	return sourceAt(t, dir)
}

// sourceAt runs the fake claude against the data dir claudeDir.
func sourceAt(t *testing.T, claudeDir string) discovery.Source {
	t.Helper()
	fakeclaude.Setup(t, claudeDir)
	return discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: claudeDir}
}

// copySample copies a testdata sample into a temp dir the test may change.
func copySample(t *testing.T, sample string) string {
	t.Helper()
	dst := filepath.Join(t.TempDir(), "claude")
	if err := os.CopyFS(dst, os.DirFS(filepath.Join("testdata", sample))); err != nil {
		t.Fatal(err)
	}
	return dst
}

func TestSessions(t *testing.T) {
	src := sampleSource(t, "basic")
	got, err := src.Sessions(context.Background())
	if err != nil {
		t.Fatalf("Sessions: %v", err)
	}
	type want struct {
		id, name, cwd string
		state         protocol.SessionState
		lastActive    string
	}
	wants := []want{
		// pid null wins over state blocked.
		{"stop0001", "old run", "/w/stopped", protocol.StateStopped, "2026-11-11T12:26:40Z"},
		// pid missing wins over status busy.
		{"stop0002", "no pid field", "/w/nopid", protocol.StateStopped, "2026-11-11T12:26:40Z"},
		{"pend0001", "needs ok", "/w/pending", protocol.StatePending, "2026-11-13T00:31:11Z"},
		// last activity from jobs/work0001/state.json updatedAt.
		{"work0001", "busy one", "/w/working", protocol.StateWorking, "2026-11-13T06:09:16Z"},
		{"idle0001", "", "/w/idle", protocol.StateIdle, "2026-11-13T00:31:11Z"},
		// fore0001 is interactive and must be filtered out.
	}
	if len(got) != len(wants) {
		t.Fatalf("got %d sessions %+v, want %d", len(got), got, len(wants))
	}
	for i, w := range wants {
		g := got[i]
		if g.ID != w.id || g.Name != w.name || g.Cwd != w.cwd || g.State != w.state {
			t.Errorf("session %d = %+v, want %+v", i, g, w)
		}
		at, _ := time.Parse(time.RFC3339, w.lastActive)
		if !g.LastActiveAt.Equal(at) || g.LastActiveAt.Location() != time.UTC {
			t.Errorf("session %s LastActiveAt = %v, want %s in UTC", w.id, g.LastActiveAt, w.lastActive)
		}
	}
}

func TestSessionsClaudeFails(t *testing.T) {
	src := sampleSource(t, "basic")
	src.ClaudePath = filepath.Join(t.TempDir(), "missing-claude")
	if _, err := src.Sessions(context.Background()); err == nil {
		t.Fatal("missing claude executable did not fail")
	}
}

func TestProfileOf(t *testing.T) {
	src := sampleSource(t, "profiles")
	cases := map[string]protocol.Profile{
		// no --settings
		"dflt0001": {ID: "default", Kind: protocol.ProfileDefault},
		"anth0001": {ID: "api.anthropic.com", Kind: protocol.ProfileEndpoint},
		"anyr0001": {ID: "anyrouter.top", Kind: protocol.ProfileEndpoint},
		// truncated state.json
		"bad00001": {ID: "other", Kind: protocol.ProfileOther},
		// no jobs/miss0001 at all
		"miss0001": {ID: "other", Kind: protocol.ProfileOther},
	}
	for id, want := range cases {
		if got := src.ProfileOf(id); !reflect.DeepEqual(got, want) {
			t.Errorf("ProfileOf(%s) = %+v, want %+v", id, got, want)
		}
	}
}

func TestList(t *testing.T) {
	src := sampleSource(t, "profiles")
	got, err := src.List(context.Background())
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	wantProfileOf := map[string]string{
		"dflt0001": "default",
		"anth0001": "api.anthropic.com",
		"anyr0001": "anyrouter.top",
		"bad00001": "other",
		"miss0001": "other",
	}
	if len(got.Sessions) != len(wantProfileOf) {
		t.Fatalf("got %d sessions, want %d", len(got.Sessions), len(wantProfileOf))
	}
	for _, s := range got.Sessions {
		if s.ProfileID != wantProfileOf[s.ID] {
			t.Errorf("session %s ProfileID = %q, want %q", s.ID, s.ProfileID, wantProfileOf[s.ID])
		}
	}
	// relay.example.net only appears in the jobs/hist0001 history record;
	// order is default, endpoints by id, other. Models come from each
	// profile's most recent session.
	wantProfiles := []protocol.Profile{
		{ID: "default", Kind: protocol.ProfileDefault, Models: map[protocol.ModelTier]string{protocol.ModelMain: "claude-opus-5-5"}},
		{ID: "anyrouter.top", Kind: protocol.ProfileEndpoint},
		{ID: "api.anthropic.com", Kind: protocol.ProfileEndpoint, Models: map[protocol.ModelTier]string{protocol.ModelMain: "claude-opus-5-5"}},
		{ID: "relay.example.net", Kind: protocol.ProfileEndpoint},
		{ID: "other", Kind: protocol.ProfileOther},
	}
	if len(got.Profiles) != len(wantProfiles) {
		t.Fatalf("profiles = %+v, want %+v", got.Profiles, wantProfiles)
	}
	for i := range wantProfiles {
		if !reflect.DeepEqual(got.Profiles[i], wantProfiles[i]) {
			t.Errorf("profiles = %+v, want %+v", got.Profiles, wantProfiles)
			break
		}
	}
}

// Without any background session or job record the list is still valid
// JSON with empty arrays, never null.
func TestListEmpty(t *testing.T) {
	src := sampleSource(t, "empty")
	got, err := src.List(context.Background())
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if got.Sessions == nil || got.Profiles == nil || len(got.Sessions)+len(got.Profiles) != 0 {
		t.Fatalf("List = %#v, want empty non-nil slices", got)
	}
}

func writeFile(t *testing.T, path, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestRecentDirs(t *testing.T) {
	home := t.TempDir()
	work := t.TempDir()
	d := func(i int) string { return filepath.Join(work, fmt.Sprintf("d%02d", i)) }
	base := time.Date(2026, 11, 12, 8, 0, 0, 0, time.UTC)
	// d00..d24 exist; job jNN used dNN at base+NN minutes.
	for i := 0; i <= 24; i++ {
		if err := os.Mkdir(d(i), 0o700); err != nil {
			t.Fatal(err)
		}
		at := base.Add(time.Duration(i) * time.Minute).Format("2006-01-02T15:04:05.000Z")
		writeFile(t, filepath.Join(home, "jobs", fmt.Sprintf("j%02d", i), "state.json"),
			fmt.Sprintf(`{"cwd": %q, "updatedAt": %q}`, d(i), at))
	}
	// sessions/*.json use millisecond timestamps: d03 is the newest of all,
	// d24 shows up again with an older time and must not repeat.
	writeFile(t, filepath.Join(home, "sessions", "501.json"),
		fmt.Sprintf(`{"cwd": %q, "updatedAt": %d, "kind": "bg"}`, d(3), base.Add(100*time.Minute).UnixMilli()))
	writeFile(t, filepath.Join(home, "sessions", "502.json"),
		fmt.Sprintf(`{"cwd": %q, "updatedAt": %d, "kind": "bg"}`, d(24), base.Add(-time.Hour).UnixMilli()))
	// The newest record of all points at a directory that is gone.
	writeFile(t, filepath.Join(home, "jobs", "gone", "state.json"),
		fmt.Sprintf(`{"cwd": %q, "updatedAt": "2026-11-13T00:00:00.000Z"}`, filepath.Join(work, "deleted")))
	// Broken records are skipped.
	writeFile(t, filepath.Join(home, "jobs", "broken", "state.json"), "{")
	writeFile(t, filepath.Join(home, "sessions", "503.json"), "")

	src := discovery.Source{ClaudeDir: home}
	got := src.RecentDirs()

	want := []string{d(3)}
	for i := 24; i >= 6; i-- {
		want = append(want, d(i))
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("RecentDirs =\n%q\nwant\n%q", got, want)
	}
}

func TestRecentDirsEmpty(t *testing.T) {
	got := discovery.Source{ClaudeDir: t.TempDir()}.RecentDirs()
	if got == nil || len(got) != 0 {
		t.Fatalf("RecentDirs = %#v, want empty non-nil slice", got)
	}
}

// An endpoint whose host happens to be "default" or "other" is still an
// endpoint profile, never the fixed profile of the same name.
func TestListEndpointNamedLikeFixedProfile(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "agents.json"),
		`[{"pid": 1, "id": "host0001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "name": "x", "status": "idle", "state": "done"}]`)
	writeFile(t, filepath.Join(dir, "jobs", "host0001", "state.json"),
		`{"respawnFlags": ["--settings", "{\"env\":{\"ANTHROPIC_BASE_URL\":\"http://other\"}}"]}`)
	writeFile(t, filepath.Join(dir, "jobs", "host0002", "state.json"),
		`{"respawnFlags": ["--settings", "{\"env\":{\"ANTHROPIC_BASE_URL\":\"http://default\"}}"]}`)
	got, err := sourceAt(t, dir).List(context.Background())
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(got.Sessions) != 1 || got.Sessions[0].ProfileID != "other" {
		t.Fatalf("sessions = %+v", got.Sessions)
	}
	want := []protocol.Profile{
		{ID: "default", Kind: protocol.ProfileEndpoint},
		{ID: "other", Kind: protocol.ProfileEndpoint},
	}
	if !reflect.DeepEqual(got.Profiles, want) {
		t.Fatalf("profiles = %+v, want %+v", got.Profiles, want)
	}
}

// Ids that are not plain short ids never reach the file system: each of
// these would otherwise resolve to a readable default-profile record.
func TestProfileOfRejectsBadIDs(t *testing.T) {
	dir := t.TempDir()
	valid := `{"respawnFlags": []}`
	writeFile(t, filepath.Join(dir, "x", "state.json"), valid)
	writeFile(t, filepath.Join(dir, "jobs", "state.json"), valid)
	writeFile(t, filepath.Join(dir, "jobs", "a", "b", "state.json"), valid)
	writeFile(t, filepath.Join(dir, "jobs", "toolongid1", "state.json"), valid)
	writeFile(t, filepath.Join(dir, "jobs", "ok000001", "state.json"), valid)
	writeFile(t, filepath.Join(dir, "agents.json"),
		`[{"pid": 1, "id": "../x", "kind": "background", "startedAt": 1794529871000, "status": "idle"}]`)
	src := sourceAt(t, dir)
	other := protocol.Profile{ID: "other", Kind: protocol.ProfileOther}
	for _, id := range []string{"../x", "", "a/b", "toolongid1"} {
		if got := src.ProfileOf(id); !reflect.DeepEqual(got, other) {
			t.Errorf("ProfileOf(%q) = %+v, want other", id, got)
		}
	}
	if got := src.ProfileOf("ok000001"); got.Kind != protocol.ProfileDefault {
		t.Fatalf("ProfileOf(ok000001) = %+v, want default", got)
	}
	sessions, err := src.Sessions(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(sessions) != 1 || sessions[0].ProfileID != "other" {
		t.Fatalf("sessions = %+v, want the ../x session in other", sessions)
	}
}

// A base URL that is not a string cannot be parsed, so the record is
// "other" rather than default.
func TestProfileOfNonStringBaseURL(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "jobs", "num00001", "state.json"),
		`{"respawnFlags": ["--settings", "{\"env\":{\"ANTHROPIC_BASE_URL\":123,\"ANTHROPIC_AUTH_TOKEN\":\"t\"}}"]}`)
	if got := (discovery.Source{ClaudeDir: dir}).ProfileOf("num00001"); got.Kind != protocol.ProfileOther {
		t.Fatalf("ProfileOf = %+v, want other", got)
	}
}

// A --settings value may be a file, as for the account login's settings
// copy: its base URL names the profile, and a file that cannot be read is
// "other".
// A profile lists the models of its most recent session: --model wins
// over ANTHROPIC_MODEL for the main tier, the other tiers come from
// providerEnv, and "other" has none.
func TestListProfileModels(t *testing.T) {
	dir := copySample(t, "profiles")
	raw, _ := json.Marshal(map[string]any{
		"state":        "done",
		"respawnFlags": []string{"--settings", `{"env":{"ANTHROPIC_BASE_URL":"https://anyrouter.top"}}`, "--permission-mode", "auto", "--model", "claude-opus-5-5[1M]"},
		"providerEnv":  map[string]string{"ANTHROPIC_MODEL": "x", "ANTHROPIC_DEFAULT_HAIKU_MODEL": "hy-4-flash[1M]", "CLAUDE_BG_SESSION_CREDENTIAL": "none"},
		"cwd":          "/w/c",
		"updatedAt":    "2026-11-14T08:00:00.000Z",
	})
	writeFile(t, filepath.Join(dir, "jobs", "anyr0003", "state.json"), string(raw))
	got, err := sourceAt(t, dir).List(context.Background())
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	want := map[protocol.ModelTier]string{protocol.ModelMain: "claude-opus-5-5[1M]", protocol.ModelHaiku: "hy-4-flash[1M]"}
	seen := map[string]bool{}
	for _, p := range got.Profiles {
		seen[p.ID] = true
		switch p.ID {
		case "anyrouter.top":
			if !reflect.DeepEqual(p.Models, want) {
				t.Errorf("anyrouter.top models = %v, want %v", p.Models, want)
			}
		case "other":
			if p.Models != nil {
				t.Errorf("other models = %v, want none", p.Models)
			}
		}
	}
	if !seen["anyrouter.top"] || !seen["other"] {
		t.Fatalf("profiles = %+v, want anyrouter.top and other", got.Profiles)
	}
}

func TestProfileOfSettingsFile(t *testing.T) {
	dir := t.TempDir()
	copyPath := filepath.Join(t.TempDir(), "account-settings.json")
	writeFile(t, copyPath, `{"hooks":{},"env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"}}`)
	work := filepath.Join(t.TempDir(), "work")
	writeFile(t, filepath.Join(work, "rel.json"), `{"env":{"ANTHROPIC_BASE_URL":"https://relay.example.net/v1"}}`)
	job := func(id, settings, cwd string) {
		raw, _ := json.Marshal(map[string]any{"respawnFlags": []string{"--setting-sources", "project,local", "--settings", settings}, "cwd": cwd})
		writeFile(t, filepath.Join(dir, "jobs", id, "state.json"), string(raw))
	}
	job("file0001", copyPath, "/w")
	job("file0002", "rel.json", work)
	job("file0003", filepath.Join(t.TempDir(), "gone.json"), "/w")
	job("file0004", t.TempDir(), "/w")
	src := discovery.Source{ClaudeDir: dir}
	cases := map[string]protocol.Profile{
		"file0001": {ID: "api.anthropic.com", Kind: protocol.ProfileEndpoint},
		"file0002": {ID: "relay.example.net", Kind: protocol.ProfileEndpoint},
		"file0003": {ID: "other", Kind: protocol.ProfileOther},
		"file0004": {ID: "other", Kind: protocol.ProfileOther},
	}
	for id, want := range cases {
		if got := src.ProfileOf(id); !reflect.DeepEqual(got, want) {
			t.Errorf("ProfileOf(%s) = %+v, want %+v", id, got, want)
		}
	}
}

// tree lists every file under dir with its content, to prove the data dir
// is never written.
func tree(t *testing.T, dir string) map[string]string {
	t.Helper()
	files := map[string]string{}
	err := filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, err := os.ReadFile(path)
		files[path] = string(b)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return files
}

func TestTemplates(t *testing.T) {
	ctx := context.Background()
	claudeDir := copySample(t, "profiles")
	src := sourceAt(t, claudeDir)
	src.ProfilesFile = filepath.Join(t.TempDir(), "clauderc", "profiles.json")
	before := tree(t, claudeDir)

	if _, err := src.List(ctx); err != nil {
		t.Fatalf("List: %v", err)
	}
	info, err := os.Stat(src.ProfilesFile)
	if err != nil {
		t.Fatalf("profiles.json not written: %v", err)
	}
	if mode := info.Mode().Perm(); mode != 0o600 {
		t.Fatalf("profiles.json mode = %#o, want 0600", mode)
	}
	anyrouterSettings := `{"env":{"ANTHROPIC_BASE_URL":"https://anyrouter.top","ANTHROPIC_AUTH_TOKEN":"redacted"}}`
	wants := []discovery.Template{
		{
			// -n, --name=… and --resume … are bound to the old session.
			Profile: protocol.Profile{ID: "api.anthropic.com", Kind: protocol.ProfileEndpoint},
			Flags: []string{"--settings", `{"env":{"ANTHROPIC_BASE_URL":"https://api.anthropic.com"},"apiKeyHelper":""}`,
				"--agent", "claude", "--permission-mode", "bypassPermissions", "--model", "claude-opus-5-5"},
			ProviderEnv: map[string]string{"ANTHROPIC_MODEL": "claude-opus-5-5"},
		},
		{
			// anyr0001 is newer than the anyr0002 history record.
			Profile:     protocol.Profile{ID: "anyrouter.top", Kind: protocol.ProfileEndpoint},
			Flags:       []string{"--settings", anyrouterSettings, "--agent", "claude"},
			ProviderEnv: map[string]string{},
		},
		{
			Profile:     protocol.Profile{ID: "default", Kind: protocol.ProfileDefault},
			Flags:       []string{"--agent", "claude", "--permission-mode", "bypassPermissions", "--model", "claude-opus-5-5"},
			ProviderEnv: map[string]string{},
		},
		{
			// only in the jobs/hist0001 history record
			Profile:     protocol.Profile{ID: "relay.example.net", Kind: protocol.ProfileEndpoint},
			Flags:       []string{"--settings", `{"env":{"ANTHROPIC_BASE_URL":"https://relay.example.net/v1"}}`},
			ProviderEnv: map[string]string{},
		},
	}
	check := func() {
		t.Helper()
		for _, want := range wants {
			got, ok, err := src.Template(want.Profile.ID)
			if err != nil || !ok {
				t.Fatalf("Template(%s): ok %v err %v", want.Profile.ID, ok, err)
			}
			if !reflect.DeepEqual(got.Profile, want.Profile) || !reflect.DeepEqual(got.Flags, want.Flags) || !reflect.DeepEqual(got.ProviderEnv, want.ProviderEnv) {
				t.Errorf("Template(%s) = %+v, want %+v", want.Profile.ID, got, want)
			}
		}
		if _, ok, err := src.Template("other"); ok || err != nil {
			t.Errorf("Template(other): ok %v err %v, want no template", ok, err)
		}
	}
	check()

	// Nothing changed in jobs/, so the file is left alone.
	past := time.Now().Add(-time.Hour).Truncate(time.Second)
	if err := os.Chtimes(src.ProfilesFile, past, past); err != nil {
		t.Fatal(err)
	}
	if _, err := src.List(ctx); err != nil {
		t.Fatalf("List: %v", err)
	}
	if info, err := os.Stat(src.ProfilesFile); err != nil || !info.ModTime().Equal(past) {
		t.Fatalf("profiles.json rewritten without changes (mtime %v, err %v)", info.ModTime(), err)
	}

	if after := tree(t, claudeDir); !reflect.DeepEqual(after, before) {
		t.Fatal("the Claude Code data dir was modified")
	}

	// `claude rm` removes jobs/<id>/: drop every anyrouter.top record and
	// its session; the profile stays listed from the cache.
	for _, id := range []string{"anyr0001", "anyr0002"} {
		if err := os.RemoveAll(filepath.Join(claudeDir, "jobs", id)); err != nil {
			t.Fatal(err)
		}
	}
	removeAgent(t, filepath.Join(claudeDir, "agents.json"), "anyr0001")
	before = tree(t, claudeDir)
	got, err := src.List(ctx)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	for _, s := range got.Sessions {
		if s.ProfileID == "anyrouter.top" {
			t.Fatalf("session %s still in anyrouter.top", s.ID)
		}
	}
	found := false
	for _, p := range got.Profiles {
		found = found || (p.ID == "anyrouter.top" && p.Kind == protocol.ProfileEndpoint)
	}
	if !found {
		t.Fatalf("anyrouter.top missing from %+v after its jobs were removed", got.Profiles)
	}
	check()

	if after := tree(t, claudeDir); !reflect.DeepEqual(after, before) {
		t.Fatal("the Claude Code data dir was modified")
	}
}

func TestForget(t *testing.T) {
	const id = "relay.example.net"
	src := sourceAt(t, copySample(t, "profiles"))
	src.ProfilesFile = filepath.Join(t.TempDir(), "profiles.json")
	check := func(want bool) time.Time {
		t.Helper()
		list, err := src.List(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		if listed := slices.ContainsFunc(list.Profiles, func(p protocol.Profile) bool { return p.ID == id }); listed != want {
			t.Fatalf("%s listed = %v, want %v", id, listed, want)
		}
		data, err := os.ReadFile(src.ProfilesFile)
		if err != nil {
			t.Fatal(err)
		}
		var cache struct {
			Profiles  map[string]json.RawMessage
			Forgotten map[string]time.Time
		}
		if err := json.Unmarshal(data, &cache); err != nil {
			t.Fatal(err)
		}
		_, cached := cache.Profiles[id]
		at, forgotten := cache.Forgotten[id]
		if cached != want || forgotten == want || forgotten && at.IsZero() {
			t.Fatalf("%s cached = %v, forgotten = %v at %v; want cached = %v and forgotten = %v", id, cached, forgotten, at, want, !want)
		}
		return at
	}
	check(true)
	if err := src.Forget(t.Context(), id); err != nil {
		t.Fatal(err)
	}
	at := check(false)
	writeFile(t, filepath.Join(src.ClaudeDir, "jobs", "hist0003", "state.json"),
		fmt.Sprintf(`{"respawnFlags":["--settings",%q],"updatedAt":%q}`,
			`{"env":{"ANTHROPIC_BASE_URL":"https://relay.example.net/v1"}}`, at.Add(time.Second).Format(time.RFC3339Nano)))
	check(true)
}

func TestForgetRefused(t *testing.T) {
	src := sourceAt(t, copySample(t, "profiles"))
	src.ProfilesFile = filepath.Join(t.TempDir(), "profiles.json")
	if _, err := src.List(t.Context()); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(src.ProfilesFile)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		id   string
		want error
	}{
		{"default", discovery.ErrProfileFixed},
		{"other", discovery.ErrProfileFixed},
		{"anyrouter.top", discovery.ErrProfileInUse},
	} {
		if err := src.Forget(t.Context(), c.id); !errors.Is(err, c.want) {
			t.Errorf("Forget(%q) = %v, want %v", c.id, err, c.want)
		}
		after, err := os.ReadFile(src.ProfilesFile)
		if err != nil || !bytes.Equal(after, before) {
			t.Fatalf("Forget(%q) changed profiles.json: %v", c.id, err)
		}
	}
}

// removeAgent drops the session id from a sample agents.json.
func removeAgent(t *testing.T, path, id string) {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var entries []map[string]any
	if err := json.Unmarshal(raw, &entries); err != nil {
		t.Fatal(err)
	}
	kept := entries[:0]
	for _, e := range entries {
		if e["id"] != id {
			kept = append(kept, e)
		}
	}
	out, err := json.Marshal(kept)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, path, string(out))
}

// Without a profiles file there is nothing cached and nothing written.
func TestTemplateWithoutFile(t *testing.T) {
	src := sampleSource(t, "profiles")
	if _, ok, err := src.Template("default"); ok || err != nil {
		t.Fatalf("Template: ok %v err %v", ok, err)
	}
}

// Design D5, in order: no pid is stopped; blocked with an unanswered tool
// call is pending; otherwise busy is working and anything else idle.
func TestTargetSessionState(t *testing.T) {
	cases := []struct {
		name    string
		target  discovery.Target
		pending bool
		want    protocol.SessionState
	}{
		{"no pid", discovery.Target{Status: "busy", State: "blocked"}, true, protocol.StateStopped},
		{"blocked with a pending call", discovery.Target{Running: true, Status: "waiting", State: "blocked"}, true, protocol.StatePending},
		{"blocked, busy, nothing pending", discovery.Target{Running: true, Status: "busy", State: "blocked"}, false, protocol.StateWorking},
		{"blocked, idle, nothing pending", discovery.Target{Running: true, Status: "idle", State: "blocked"}, false, protocol.StateIdle},
		{"blocked, waiting, nothing pending", discovery.Target{Running: true, Status: "waiting", State: "blocked"}, false, protocol.StateIdle},
		{"busy", discovery.Target{Running: true, Status: "busy", State: "working"}, false, protocol.StateWorking},
		// A pending call only counts while claude reports blocked.
		{"busy, not blocked", discovery.Target{Running: true, Status: "busy", State: "working"}, true, protocol.StateWorking},
		{"idle", discovery.Target{Running: true, Status: "idle", State: "done"}, false, protocol.StateIdle},
		// 2.1.326: no status, state tells working.
		{"no status, working", discovery.Target{Running: true, State: "working"}, false, protocol.StateWorking},
		{"no status, done", discovery.Target{Running: true, State: "done"}, false, protocol.StateIdle},
		{"no status, blocked, pending", discovery.Target{Running: true, State: "blocked"}, true, protocol.StatePending},
		{"no status, blocked, nothing pending", discovery.Target{Running: true, State: "blocked"}, false, protocol.StateIdle},
	}
	for _, c := range cases {
		if got := c.target.SessionState(c.pending); got != c.want {
			t.Errorf("%s: %q, want %q", c.name, got, c.want)
		}
	}
}

// Targets keep what claude reported, so the state can be settled later.
func TestTargetsKeepReportedState(t *testing.T) {
	targets, err := sampleSource(t, "basic").Targets(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	type want struct {
		running       bool
		status, state string
		blocked       bool
	}
	wants := map[string]want{
		"stop0001": {false, "idle", "blocked", false},
		"stop0002": {false, "busy", "working", false},
		"pend0001": {true, "waiting", "blocked", true},
		"work0001": {true, "busy", "working", false},
		"idle0001": {true, "idle", "done", false},
	}
	if len(targets) != len(wants) {
		t.Fatalf("targets = %+v", targets)
	}
	for _, g := range targets {
		w := wants[g.ID]
		if g.Running != w.running || g.Status != w.status || g.State != w.state || g.Blocked() != w.blocked {
			t.Errorf("%s = %+v blocked %v, want %+v", g.ID, g, g.Blocked(), w)
		}
	}
}

// From 2.1.326 on background sessions come without pid and status: the
// list of active sessions tells the running ones, state the working one.
func TestSessionsWithoutPID(t *testing.T) {
	src := sampleSource(t, "v2326")
	got, err := src.Sessions(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	wants := map[string]protocol.SessionState{
		"done0001": protocol.StateStopped,
		"work0001": protocol.StateWorking,
		// Blocked counts as pending until the transcript check settles it.
		"block001": protocol.StatePending,
		"idle0001": protocol.StateIdle,
	}
	if len(got) != len(wants) {
		t.Fatalf("got %+v", got)
	}
	for _, g := range got {
		if g.State != wants[g.ID] {
			t.Errorf("%s: %q, want %q", g.ID, g.State, wants[g.ID])
		}
	}
	targets, err := src.Targets(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, tg := range targets {
		if tg.ID == "block001" && (!tg.Blocked() || tg.SessionState(false) != protocol.StateIdle) {
			t.Errorf("block001 = %+v: blocked with nothing pending should be idle", tg)
		}
	}
}

// Each session carries claude's state as jobState and the detail of its
// jobs/<id>/state.json, trimmed; without a record the detail is empty.
func TestSessionsJobStateAndDetail(t *testing.T) {
	got, err := sampleSource(t, "v2326").Sessions(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	type want struct{ jobState, detail string }
	wants := map[string]want{
		"done0001": {"done", ""},
		"work0001": {"working", "正在查 22:04 订阅更新改了什么"},
		"block001": {"blocked", ""},
		"idle0001": {"done", "空白报告已生成于 电子荷质比实验.docx"},
	}
	for _, g := range got {
		if w := wants[g.ID]; g.JobState != w.jobState || g.Detail != w.detail {
			t.Errorf("%s: jobState %q detail %q, want %+v", g.ID, g.JobState, g.Detail, w)
		}
	}
}

// The list of active sessions is only read when the full list has a
// background session without pid and status.
func TestActiveListOnlyForNewFormat(t *testing.T) {
	for _, c := range []struct {
		sample string
		calls  int
	}{{"basic", 1}, {"v2326", 2}} {
		dir, err := filepath.Abs(filepath.Join("testdata", c.sample))
		if err != nil {
			t.Fatal(err)
		}
		logDir := fakeclaude.Setup(t, dir)
		src := discovery.Source{ClaudePath: fakeclaude.Path(), ClaudeDir: dir}
		if _, err := src.Sessions(context.Background()); err != nil {
			t.Fatal(err)
		}
		if got := len(fakeclaude.Calls(t, logDir)); got != c.calls {
			t.Errorf("%s: %d claude calls, want %d", c.sample, got, c.calls)
		}
	}
}

// claude agents runs without the agent's enroll key in its environment.
func TestClaudeAgentsGetsNoEnrollKey(t *testing.T) {
	t.Setenv("CLAUDERC_ENROLL_KEY", "enroll-secret")
	dir := t.TempDir()
	envFile := filepath.Join(dir, "env")
	script := filepath.Join(dir, "claude")
	writeFile(t, script, "#!/bin/sh\nenv >"+envFile+"\necho '[]'\n")
	if err := os.Chmod(script, 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := (discovery.Source{ClaudePath: script, ClaudeDir: dir}).Sessions(context.Background()); err != nil {
		t.Fatal(err)
	}
	env, err := os.ReadFile(envFile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(env), "enroll-secret") {
		t.Error("claude agents got the enroll key")
	}
}

// TestSessionsWorktreeAndFork uses the records of a real worktree session
// and a /fork copy of it (Claude Code 2.1.327, paths replaced).
func TestSessionsWorktreeAndFork(t *testing.T) {
	got, err := sampleSource(t, "v2327").Sessions(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	type want struct {
		id, cwd, dir, branch, forkOf string
		worktree                     *protocol.SessionWorktree
	}
	wants := []want{
		// Running in its worktree: claude reports the worktree as cwd.
		{"wtree001", "/w/repo/.claude/worktrees/fox", "/w/repo", "worktree-fox", "", &protocol.SessionWorktree{Name: "fox", Path: "/w/repo/.claude/worktrees/fox"}},
		{"fork0001", "/w/repo", "/w/repo", "", "wtree001", nil},
		// Stopped: claude reports the original checkout, the record keeps the worktree.
		{"wtstop01", "/w/repo", "/w/repo", "worktree-old", "", &protocol.SessionWorktree{Name: "old", Path: "/w/repo/.claude/worktrees/old"}},
	}
	if len(got) != len(wants) {
		t.Fatalf("got %d sessions, want %d: %+v", len(got), len(wants), got)
	}
	for i, w := range wants {
		g := got[i]
		if g.ID != w.id || g.Cwd != w.cwd || g.Dir != w.dir || g.Branch != w.branch || g.ForkOf != w.forkOf || !reflect.DeepEqual(g.Worktree, w.worktree) {
			t.Errorf("session %d = {id %s cwd %s dir %s branch %q forkOf %q worktree %+v}, want %+v",
				i, g.ID, g.Cwd, g.Dir, g.Branch, g.ForkOf, g.Worktree, w)
		}
	}
}

// TestSessionsWorktreeFromPath covers sessions without a job record: a cwd
// under .claude/worktrees still names the checkout, a fork whose source
// is gone keeps Claude Code's short id of it, and a plain session lists
// under its own cwd.
func TestSessionsWorktreeFromPath(t *testing.T) {
	dir := t.TempDir()
	agents := `[
  {"pid": 1, "id": "path0001", "cwd": "/w/a/.claude/worktrees/b/sub", "kind": "background", "startedAt": 1794529871000, "sessionId": "9a1c2a5e-0000-4000-8000-0000000000b1", "status": "idle", "state": "done"},
  {"pid": 2, "id": "orph0001", "cwd": "/w/a", "kind": "background", "startedAt": 1794529871000, "sessionId": "9a1c2a5e-0000-4000-8000-0000000000b2", "status": "idle", "state": "done"},
  {"pid": 3, "id": "plain001", "cwd": "/w/plain", "kind": "background", "startedAt": 1794529871000, "sessionId": "9a1c2a5e-0000-4000-8000-0000000000b3", "status": "idle", "state": "done"}
]`
	if err := os.WriteFile(filepath.Join(dir, "agents.json"), []byte(agents), 0o600); err != nil {
		t.Fatal(err)
	}
	job := filepath.Join(dir, "jobs", "orph0001")
	if err := os.MkdirAll(job, 0o755); err != nil {
		t.Fatal(err)
	}
	state := `{"state": "done", "cwd": "/w/a", "forkSessionId": "9a1c2a5e-0000-4000-8000-0000000000b2", "forkParentSessionId": "c0ffee11-0000-4000-8000-000000000000"}`
	if err := os.WriteFile(filepath.Join(job, "state.json"), []byte(state), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := sourceAt(t, dir).Sessions(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("got %d sessions", len(got))
	}
	if g := got[0]; g.Dir != "/w/a" || !reflect.DeepEqual(g.Worktree, &protocol.SessionWorktree{Name: "b", Path: "/w/a/.claude/worktrees/b"}) || g.Branch != "" {
		t.Errorf("worktree by path = dir %s worktree %+v branch %q", g.Dir, g.Worktree, g.Branch)
	}
	if g := got[1]; g.ForkOf != "c0ffee11" || g.Worktree != nil {
		t.Errorf("fork of a session no longer listed: forkOf %q worktree %+v", g.ForkOf, g.Worktree)
	}
	if g := got[2]; g.Dir != "/w/plain" || g.Worktree != nil || g.ForkOf != "" {
		t.Errorf("plain session: dir %s worktree %+v forkOf %q", g.Dir, g.Worktree, g.ForkOf)
	}
}
