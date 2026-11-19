// Package attach keeps hidden `claude attach <id>` processes in
// pseudo-terminals and types into them (design D7, ADR 0002): it encodes
// each chat operation as keys, serializes the operations per session, and
// reads the screen back through a small terminal emulator.
//
// Screen content is never logged.
package attach

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"time"

	"github.com/creack/pty"

	"clauderc/internal/agent/config"
)

// Terminal geometry and limits of design D7.
const (
	Rows = 40
	Cols = 120
	// DefaultQuiet is how long the output must stay quiet before the
	// attach counts as ready.
	DefaultQuiet = 500 * time.Millisecond
	// DefaultReadyTimeout caps the wait for readiness; typing starts then
	// anyway.
	DefaultReadyTimeout = 6 * time.Second
	// DefaultLinger is how long an attach outlives its last holder.
	DefaultLinger = 5 * time.Minute
	// writeChunk is the most written to the terminal at once, so a long
	// paste can be cancelled between chunks.
	writeChunk = 4 << 10
)

// ErrClosed is returned when the attach process has ended.
var ErrClosed = errors.New("attach: process ended")

// Options configures a Pool.
type Options struct {
	// Command returns the argv that attaches to session id, normally
	// {claude_path, "attach", id}.
	Command func(id string) []string
	// Env is added to the agent's own environment, before TERM.
	Env []string
	// Quiet, ReadyTimeout and Linger default to the constants above.
	Quiet        time.Duration
	ReadyTimeout time.Duration
	Linger       time.Duration
}

// Pool shares one attach per session among its holders and ends it Linger
// after the last holder released it.
type Pool struct {
	opts Options

	mu      sync.Mutex
	entries map[string]*entry
	closed  bool
}

// entry is the one attach of a session. Holds count against the entry, not
// against a process: when the process ends on its own, the next Acquire
// starts a new one in the same entry.
type entry struct {
	refs  int
	timer *time.Timer
	run   *run
}

// run is one start of the attach process.
type run struct {
	ready chan struct{} // closed once conn or err is set
	conn  *Conn
	err   error
}

// usable reports whether r is starting or runs; p.mu must be held.
func (r *run) usable() bool {
	select {
	case <-r.ready:
		return r.err == nil && !r.conn.ended()
	default:
		return true
	}
}

// NewPool returns a Pool; call Close when done.
func NewPool(opts Options) *Pool {
	if opts.Quiet <= 0 {
		opts.Quiet = DefaultQuiet
	}
	if opts.ReadyTimeout <= 0 {
		opts.ReadyTimeout = DefaultReadyTimeout
	}
	if opts.Linger <= 0 {
		opts.Linger = DefaultLinger
	}
	return &Pool{opts: opts, entries: map[string]*entry{}}
}

// Acquire returns the ready attach of session id, starting it when there
// is none or its process has ended. Every successful Acquire must be
// paired with a Release.
func (p *Pool) Acquire(ctx context.Context, id string) (*Conn, error) {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil, ErrClosed
	}
	e := p.entries[id]
	if e == nil {
		e = &entry{}
		p.entries[id] = e
	}
	var stale *Conn
	if e.run == nil || !e.run.usable() {
		if e.run != nil {
			stale = e.run.conn
		}
		e.run = &run{ready: make(chan struct{})}
		go p.start(e.run, id)
	}
	r := e.run
	e.refs++
	if e.timer != nil {
		e.timer.Stop()
		e.timer = nil
	}
	p.mu.Unlock()
	if stale != nil {
		// Its process is gone; this frees the pseudo-terminal.
		stale.Close()
	}

	select {
	case <-r.ready:
	case <-ctx.Done():
		p.Release(id)
		return nil, ctx.Err()
	}
	if r.err != nil {
		p.Release(id)
		return nil, r.err
	}
	return r.conn, nil
}

func (p *Pool) start(r *run, id string) {
	c, err := open(p.opts, id)
	p.mu.Lock()
	r.conn, r.err = c, err
	p.mu.Unlock()
	close(r.ready)
}

// Release drops one hold on session id's attach and, when it was the last
// one, schedules the attach's end.
func (p *Pool) Release(id string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	e := p.entries[id]
	if e == nil || e.refs == 0 {
		return
	}
	e.refs--
	if e.refs > 0 || e.timer != nil {
		return
	}
	e.timer = time.AfterFunc(p.opts.Linger, func() {
		p.mu.Lock()
		if p.entries[id] != e || e.refs > 0 {
			p.mu.Unlock()
			return
		}
		delete(p.entries, id)
		r := e.run
		p.mu.Unlock()
		r.close()
	})
}

// close waits for r's start and ends its process.
func (r *run) close() {
	<-r.ready
	if r.conn != nil {
		r.conn.Close()
	}
}

// Close ends every attach; later Acquires fail.
func (p *Pool) Close() {
	p.mu.Lock()
	p.closed = true
	entries := p.entries
	p.entries = map[string]*entry{}
	for _, e := range entries {
		if e.timer != nil {
			e.timer.Stop()
		}
	}
	p.mu.Unlock()
	for _, e := range entries {
		e.run.close()
	}
}

// Conn is one attach process.
type Conn struct {
	cmd  *exec.Cmd
	ptmx *os.File
	done chan struct{}
	// replies wakes the goroutine that answers cursor position queries.
	replies chan struct{}

	ops     sync.Mutex // serializes operations
	writeMu sync.Mutex // serializes writes to the terminal

	mu      sync.Mutex
	screen  *Screen
	lastOut time.Time

	closeOnce sync.Once
}

// childEnv is the agent's environment without the enrollment key, plus
// extra and TERM.
func childEnv(extra []string) []string {
	return append(append(config.Environ(), extra...), "TERM=xterm-256color")
}

func open(opts Options, id string) (*Conn, error) {
	return start(opts.Command(id), "", opts.Env, opts.Quiet, opts.ReadyTimeout)
}

// start runs argv in dir (the agent's own when empty) in a new terminal
// and waits until its output stays quiet for quiet, at most ready.
func start(argv []string, dir string, env []string, quiet, ready time.Duration) (*Conn, error) {
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = dir
	cmd.Env = childEnv(env)
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: Rows, Cols: Cols})
	if err != nil {
		return nil, err
	}
	c := &Conn{cmd: cmd, ptmx: ptmx, done: make(chan struct{}), replies: make(chan struct{}, 1), screen: NewScreen(Rows, Cols), lastOut: time.Now()}
	go func() {
		_ = cmd.Wait()
		close(c.done)
	}()
	go c.pump()
	go c.answerCursor()
	c.waitQuiet(context.Background(), quiet, ready)
	return c, nil
}

var (
	// cursorQuery is the terminal's "report cursor position" request.
	cursorQuery = []byte("\x1b[6n")
	// cursorReply is the fixed position given back (design D7).
	cursorReply = []byte("\x1b[1;1R")
)

// pump reads the output into the screen. It never writes: a write can
// block while the program does not read its input, and the program may be
// waiting for its output to be read.
func (c *Conn) pump() {
	buf := make([]byte, 32<<10)
	for {
		n, err := c.ptmx.Read(buf)
		if n > 0 {
			chunk := buf[:n]
			c.mu.Lock()
			c.screen.Write(chunk)
			c.lastOut = time.Now()
			c.mu.Unlock()
			if bytes.Contains(chunk, cursorQuery) {
				select {
				case c.replies <- struct{}{}:
				default: // an answer is already due
				}
			}
		}
		if err != nil {
			return
		}
	}
}

// answerCursor answers cursor position queries until the process ends.
func (c *Conn) answerCursor() {
	for {
		select {
		case <-c.done:
			return
		case <-c.replies:
			_ = c.write(context.Background(), cursorReply)
		}
	}
}

// write writes b in chunks, checking ctx between them. A paste cut short
// is still ended, so the input does not stay in paste mode.
func (c *Conn) write(ctx context.Context, b []byte) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	for off := 0; off < len(b); off += writeChunk {
		if c.ended() {
			return ErrClosed
		}
		if err := ctx.Err(); err != nil {
			if off > 0 && bytes.HasPrefix(b, pasteStart) {
				_, _ = c.ptmx.Write(pasteEnd)
			}
			return err
		}
		if _, err := c.ptmx.Write(b[off:min(off+writeChunk, len(b))]); err != nil {
			return err
		}
	}
	return nil
}

// waitQuiet waits until no output arrived for quiet, at most max.
func (c *Conn) waitQuiet(ctx context.Context, quiet, max time.Duration) {
	deadline := time.Now().Add(max)
	for time.Now().Before(deadline) {
		c.mu.Lock()
		since := time.Since(c.lastOut)
		c.mu.Unlock()
		if since >= quiet || c.ended() {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(20 * time.Millisecond):
		}
	}
}

// Send types steps, one operation at a time per attach.
func (c *Conn) Send(ctx context.Context, steps []Step) error {
	c.ops.Lock()
	defer c.ops.Unlock()
	return c.send(ctx, steps)
}

func (c *Conn) send(ctx context.Context, steps []Step) error {
	for _, st := range steps {
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := c.write(ctx, st.Keys); err != nil {
			return err
		}
		if st.Wait > 0 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-c.done:
				return ErrClosed
			case <-time.After(st.Wait):
			}
		}
	}
	return nil
}

// Screen returns the current screen text without waiting for an
// operation in progress.
func (c *Conn) Screen() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.screen.Text()
}

// View returns the current screen and cursor without waiting for an
// operation in progress.
func (c *Conn) View() View {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.screen.View()
}

// PID is the attach process id.
func (c *Conn) PID() int { return c.cmd.Process.Pid }

func (c *Conn) ended() bool {
	select {
	case <-c.done:
		return true
	default:
		return false
	}
}

// Close ends the attach process; the session itself keeps running.
func (c *Conn) Close() {
	c.closeOnce.Do(func() {
		_ = c.cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-c.done:
		case <-time.After(time.Second):
			_ = c.cmd.Process.Kill()
			<-c.done
		}
		// Not under writeMu: a write may be stuck, and closing an
		// os.File concurrently with it is safe.
		_ = c.ptmx.Close()
	})
}

// Do runs fn as one operation: no other Send or Do on this attach runs
// until it returns.
func (c *Conn) Do(ctx context.Context, fn func(t *Term) error) error {
	c.ops.Lock()
	defer c.ops.Unlock()
	return fn(&Term{c: c, ctx: ctx})
}

// Term is the attach as seen inside Do.
type Term struct {
	c   *Conn
	ctx context.Context
}

// Send types steps.
func (t *Term) Send(steps []Step) error { return t.c.send(t.ctx, steps) }

// Screen returns the current screen text.
func (t *Term) Screen() string {
	t.c.mu.Lock()
	defer t.c.mu.Unlock()
	return t.c.screen.Text()
}

// View returns the current screen and cursor.
func (t *Term) View() View {
	t.c.mu.Lock()
	defer t.c.mu.Unlock()
	return t.c.screen.View()
}

// WaitQuiet waits until no output arrived for quiet, at most max.
func (t *Term) WaitQuiet(quiet, max time.Duration) { t.c.waitQuiet(t.ctx, quiet, max) }

// WaitFor waits up to timeout until cond holds for the screen.
func (t *Term) WaitFor(cond func(screen string) bool, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		if cond(t.Screen()) {
			return true
		}
		if time.Now().After(deadline) || t.ctx.Err() != nil || t.c.ended() {
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}
}
