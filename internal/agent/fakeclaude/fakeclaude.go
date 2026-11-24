// Package fakeclaude drives the claude test double at
// internal/agent/testdata/fake-claude: it locates the script, points it at
// a sample directory and reads back the calls it recorded. Only tests
// import this package.
package fakeclaude

import (
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// Call is one invocation recorded by the fake claude.
type Call struct {
	// Args are the arguments after the program name.
	Args []string
	// Env holds NAME=value lines for the allowlisted variables only (see
	// the script header); names containing TOKEN, KEY, SECRET or PASSWORD
	// are never recorded.
	Env []string
	// Cwd is the physical working directory; compare it against
	// filepath.EvalSymlinks of the expected one.
	Cwd string
}

// Path returns the absolute path of the fake claude script, found from
// this source file so it works from any test package.
func Path() string {
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		panic("fakeclaude: cannot locate source file")
	}
	return filepath.Join(filepath.Dir(file), "..", "testdata", "fake-claude")
}

// Setup points the fake claude at sample (FAKE_CLAUDE_DIR) for the rest of
// the test and returns a fresh directory that receives its call records
// (FAKE_CLAUDE_LOG).
func Setup(t testing.TB, sample string) (logDir string) {
	t.Helper()
	logDir = t.TempDir()
	t.Setenv("FAKE_CLAUDE_DIR", sample)
	t.Setenv("FAKE_CLAUDE_LOG", logDir)
	return logDir
}

// Command is Setup without the process environment, so tests using it may
// run in parallel: it writes a claude of the test's own that runs the fake
// claude on sample, and returns its path and the directory that receives
// its call records.
func Command(t testing.TB, sample string) (path, logDir string) {
	t.Helper()
	logDir = t.TempDir()
	path = filepath.Join(t.TempDir(), "claude")
	script := "#!/bin/sh\nFAKE_CLAUDE_DIR=" + strconv.Quote(sample) + " FAKE_CLAUDE_LOG=" + strconv.Quote(logDir) +
		" exec " + strconv.Quote(Path()) + ` "$@"` + "\n"
	if err := os.WriteFile(path, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return path, logDir
}

// Calls reads the calls recorded under logDir, in call order. A call that
// is still being recorded is left out; the record is complete before the
// fake claude does anything else, including the agents.delay sleep.
func Calls(t testing.TB, logDir string) []Call {
	t.Helper()
	entries, err := os.ReadDir(logDir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		if _, err := os.Stat(filepath.Join(logDir, e.Name(), "done")); err == nil {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	calls := []Call{}
	for _, n := range names {
		read := func(f string) string {
			b, err := os.ReadFile(filepath.Join(logDir, n, f))
			if err != nil {
				t.Fatal(err)
			}
			return string(b)
		}
		args := strings.Split(read("args"), "\x00")
		args = args[:len(args)-1] // every arg is NUL terminated
		var env []string
		if e := strings.TrimSuffix(read("env"), "\n"); e != "" {
			env = strings.Split(e, "\n")
		}
		calls = append(calls, Call{
			Args: args,
			Env:  env,
			Cwd:  strings.TrimSuffix(read("cwd"), "\n"),
		})
	}
	return calls
}
