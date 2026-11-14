package attach_test

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/fakeattach"
)

// envNamesFile makes the fake attach write the names, never the values,
// of its environment variables to the named file before it starts.
const envNamesFile = "ATTACH_TEST_ENV_NAMES"

func TestMain(m *testing.M) {
	if path := os.Getenv(envNamesFile); path != "" {
		var names []string
		for _, kv := range os.Environ() {
			name, _, _ := strings.Cut(kv, "=")
			names = append(names, name)
		}
		if err := os.WriteFile(path, []byte(strings.Join(names, "\n")), 0o600); err != nil {
			os.Exit(1)
		}
	}
	fakeattach.RunIfFake()
	os.Exit(m.Run())
}

// newPool returns a pool whose attach command is the fake; it records the
// bytes it receives in the returned log file.
func newPool(t *testing.T, linger time.Duration, env ...string) (*attach.Pool, string) {
	t.Helper()
	log := filepath.Join(t.TempDir(), "keys.log")
	p := attach.NewPool(attach.Options{
		Command: func(id string) []string { return fakeattach.Command(id) },
		Env:     append(fakeattach.Env(log), env...),
		Linger:  linger,
	})
	t.Cleanup(p.Close)
	return p, log
}

// waitLog waits until the fake has logged something containing want and
// returns the whole log.
func waitLog(t *testing.T, log, want string) string {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		b, _ := os.ReadFile(log)
		if strings.Contains(string(b), want) {
			return string(b)
		}
		if time.Now().After(deadline) {
			t.Fatalf("log %q never contained %q", b, want)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestReadyAfterQuietOutput(t *testing.T) {
	// The fake prints for 1 second after it starts, then goes quiet.
	p, log := newPool(t, time.Minute, fakeattach.NoiseEnv(time.Second))
	start := time.Now()
	c, err := p.Acquire(context.Background(), "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	took := time.Since(start)
	if took < 1400*time.Millisecond || took > 4*time.Second {
		t.Fatalf("ready after %v, want about 1s of output plus 500ms of quiet", took)
	}
	// The fake asked for the cursor position at start; the pool answered.
	waitLog(t, log, "\x1b[1;1R")
	if err := c.Send(context.Background(), attach.AllowKeys()); err != nil {
		t.Fatal(err)
	}
	waitLog(t, log, "\x1b[1;1R\r")
	p.Release("abc12345")
}

func TestConcurrentSendsAreSerialized(t *testing.T) {
	p, log := newPool(t, time.Minute)
	c, err := p.Acquire(context.Background(), "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	defer p.Release("abc12345")
	seq := func(tag string) []attach.Step {
		return []attach.Step{
			{Keys: []byte("<" + tag), Wait: 150 * time.Millisecond},
			{Keys: []byte(tag + ">")},
		}
	}
	var wg sync.WaitGroup
	for _, tag := range []string{"A", "B", "C"} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := c.Send(context.Background(), seq(tag)); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	got := waitLog(t, log, ">")
	deadline := time.Now().Add(3 * time.Second)
	for strings.Count(got, ">") < 3 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
		b, _ := os.ReadFile(log)
		got = string(b)
	}
	got = got[strings.Index(got, "<"):]
	for _, tag := range []string{"A", "B", "C"} {
		if !strings.Contains(got, "<"+tag+tag+">") {
			t.Fatalf("sends interleaved: %q", got)
		}
	}
}

func TestLastReleaseEndsAttachAfterLinger(t *testing.T) {
	p, _ := newPool(t, 300*time.Millisecond)
	ctx := context.Background()
	c1, err := p.Acquire(ctx, "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	// A second holder, then both leave; a quick return keeps the attach.
	if _, err := p.Acquire(ctx, "abc12345"); err != nil {
		t.Fatal(err)
	}
	p.Release("abc12345")
	p.Release("abc12345")
	time.Sleep(100 * time.Millisecond)
	c2, err := p.Acquire(ctx, "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	if c2 != c1 {
		t.Fatal("re-acquiring within the linger time started a new attach")
	}
	time.Sleep(500 * time.Millisecond)
	if fakeattach.Gone(c1.PID()) {
		t.Fatal("attach ended while still held")
	}
	p.Release("abc12345")
	waitGone(t, c1.PID(), "attach process still running 3s after the last release")
	c3, err := p.Acquire(ctx, "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	if c3 == c1 {
		t.Fatal("got the ended attach back")
	}
	p.Release("abc12345")
}

// waitGone waits up to 3 seconds for process pid to be gone.
func waitGone(t *testing.T, pid int, msg string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !fakeattach.Gone(pid) {
		if time.Now().After(deadline) {
			t.Fatal(msg)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// openFDs counts the test process's open file descriptors.
func openFDs(t *testing.T) int {
	t.Helper()
	entries, err := os.ReadDir("/dev/fd")
	if err != nil {
		t.Fatal(err)
	}
	return len(entries)
}

// The enrollment key must not reach claude through the attach's inherited
// environment. Only variable names are compared, never values.
func TestEnrollKeyNotInherited(t *testing.T) {
	t.Setenv("CLAUDERC_ENROLL_KEY", "dummy-for-test")
	names := filepath.Join(t.TempDir(), "env-names")
	p, _ := newPool(t, time.Minute, envNamesFile+"="+names)
	if _, err := p.Acquire(context.Background(), "abc12345"); err != nil {
		t.Fatal(err)
	}
	defer p.Release("abc12345")
	b, err := os.ReadFile(names)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, n := range strings.Split(string(b), "\n") {
		got[n] = true
	}
	if got["CLAUDERC_ENROLL_KEY"] {
		t.Error("CLAUDERC_ENROLL_KEY was passed to the attach process")
	}
	// The rest of the environment is still inherited.
	for _, n := range []string{"PATH", "TERM", envNamesFile} {
		if !got[n] {
			t.Errorf("%s missing from the attach environment", n)
		}
	}
}

// An attach whose process ended on its own is started again under the
// same hold: the old pseudo-terminal is closed right away, and the new
// process still ends once the last holder has been gone for the linger
// time.
func TestRestartedAttachClosesOldAndLingers(t *testing.T) {
	p, _ := newPool(t, 300*time.Millisecond)
	ctx := context.Background()
	c1, err := p.Acquire(ctx, "abc12345") // held, as by an open chat
	if err != nil {
		t.Fatal(err)
	}
	fds := openFDs(t)
	if err := syscall.Kill(c1.PID(), syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}
	waitGone(t, c1.PID(), "killed attach process still there")
	c2, err := p.Acquire(ctx, "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	if c2 == c1 || c2.PID() == c1.PID() {
		t.Fatal("the ended attach was handed out again")
	}
	if n := openFDs(t); n > fds {
		t.Errorf("%d open files after the restart, %d before: the old pseudo-terminal was left open", n, fds)
	}
	p.Release("abc12345")
	p.Release("abc12345")
	waitGone(t, c2.PID(), "restarted attach still running 3s after the last release")
}

// A write into a terminal nobody reads blocks; closing the attach must not
// wait for it.
func TestCloseWhileWriteBlocked(t *testing.T) {
	p := attach.NewPool(attach.Options{
		Command: func(string) []string { return []string{"/bin/sh", "-c", "stty raw -echo; exec sleep 600"} },
		Quiet:   100 * time.Millisecond,
	})
	c, err := p.Acquire(context.Background(), "abc12345")
	if err != nil {
		p.Close()
		t.Fatal(err)
	}
	sent := make(chan error, 1)
	go func() {
		sent <- c.Send(context.Background(), []attach.Step{{Keys: bytes.Repeat([]byte("x"), 1<<20)}})
	}()
	select {
	case err := <-sent:
		t.Fatalf("1 MB went into a terminal nobody reads: %v", err)
	case <-time.After(500 * time.Millisecond):
	}
	closed := make(chan struct{})
	go func() {
		p.Close()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("Close blocked behind a stuck write")
	}
	if !fakeattach.Gone(c.PID()) {
		t.Errorf("process %d still exists after Close", c.PID())
	}
}

// A program that asks for the cursor position again and again without
// reading its input fills the terminal's input buffer with the answers.
// Answering must not stop the output from being read, or the program
// blocks on its output and the attach hangs.
func TestOutputReadWhileInputBlocked(t *testing.T) {
	p := attach.NewPool(attach.Options{
		Command: func(string) []string {
			return []string{"/bin/sh", "-c", `stty raw -echo; i=0; while [ $i -lt 3000 ]; do printf '\033[6n'; i=$((i+1)); done; printf 'all-printed'; exec sleep 600`}
		},
		Quiet:        300 * time.Millisecond,
		ReadyTimeout: 2 * time.Second,
	})
	t.Cleanup(p.Close)
	c, err := p.Acquire(context.Background(), "abc12345")
	if err != nil {
		t.Fatal(err)
	}
	defer p.Release("abc12345")
	var printed bool
	_ = c.Do(context.Background(), func(term *attach.Term) error {
		printed = term.WaitFor(func(s string) bool { return strings.Contains(s, "all-printed") }, 3*time.Second)
		return nil
	})
	if !printed {
		t.Fatal("output stopped being read while cursor answers waited for the input buffer")
	}
}
