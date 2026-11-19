package attach

import (
	"context"
	"errors"
	"strings"
	"time"
)

// Errors of Trust.
var (
	// ErrNoTrustPrompt: claude ended, or the context ran out, before its
	// trust prompt showed.
	ErrNoTrustPrompt = errors.New("attach: no trust prompt")
	// ErrTrustNotAnswered: the prompt stayed on screen after the answer.
	ErrTrustNotAnswered = errors.New("attach: trust prompt not answered")
)

// Waits of Trust: how long the prompt may stay after Enter, and how long
// one move of the selection may take to show, tried a few times.
const (
	trustAnswerWait = 10 * time.Second
	trustMoveWait   = 2 * time.Second
	trustMoveTries  = 6
)

// Trust says yes to Claude Code's workspace trust prompt, as the user would
// by running claude in dir once: argv, the plain claude command, runs in dir
// in a hidden terminal; once the prompt shows, the selection moves to "Yes,
// I trust this folder" and Enter is pressed; the process is ended when the
// prompt is gone. Claude Code records the trust itself. ctx bounds the wait
// for the prompt.
func Trust(ctx context.Context, dir string, argv, env []string) error {
	c, err := start(argv, dir, env, 0, 0)
	if err != nil {
		return err
	}
	defer c.Close()
	return c.Do(ctx, func(t *Term) error {
		if !t.WaitFor(func(string) bool {
			_, _, ok := trustRows(t.View().Lines)
			return ok
		}, time.Until(deadlineOf(ctx))) {
			return ErrNoTrustPrompt
		}
		// The prompt may be drawn before Claude Code reads keys; let the
		// screen settle, then move one row at a time and check each move
		// before pressing Enter, so it never lands on "No, exit".
		t.WaitQuiet(DefaultQuiet, 3*time.Second)
		for tries := 0; ; tries++ {
			at, yes, ok := trustRows(t.View().Lines)
			if !ok {
				return ErrNoTrustPrompt
			}
			if at == yes {
				break
			}
			if tries == trustMoveTries {
				return ErrTrustNotAnswered
			}
			key := keyDown
			if at > yes {
				key = keyUp
			}
			if err := t.Send([]Step{{Keys: key}}); err != nil {
				return err
			}
			t.WaitFor(func(string) bool {
				now, _, _ := trustRows(t.View().Lines)
				return now != at
			}, trustMoveWait)
		}
		if err := t.Send([]Step{{Keys: keyEnter}}); err != nil {
			return err
		}
		gone := func(string) bool {
			_, _, ok := trustRows(t.View().Lines)
			return !ok
		}
		if !t.WaitFor(gone, trustAnswerWait) && !c.ended() {
			return ErrTrustNotAnswered
		}
		// Give Claude Code a moment to finish writing its config.
		t.WaitQuiet(DefaultQuiet, 2*time.Second)
		return nil
	})
}

// deadlineOf is ctx's deadline, or a minute from now without one.
func deadlineOf(ctx context.Context) time.Time {
	if d, ok := ctx.Deadline(); ok {
		return d
	}
	return time.Now().Add(time.Minute)
}

// trustRows finds the trust prompt's options on screen: the row the
// selection mark ❯ is on and the row of the yes option, which reads "Yes, I
// trust this folder" in Claude Code 2.1.327 and "Yes, proceed" before.
// Spaces are ignored, as a terminal may draw them as cursor moves.
func trustRows(lines []string) (at, yes int, ok bool) {
	at, yes = -1, -1
	no := -1
	for i, l := range lines {
		flat := strings.ReplaceAll(l, " ", "")
		switch {
		case strings.Contains(flat, "Yes,Itrustthisfolder"), strings.Contains(flat, "Yes,proceed"):
			yes = i
		case strings.Contains(flat, "No,exit"):
			no = i
		default:
			continue
		}
		if strings.HasPrefix(strings.TrimSpace(l), "❯") {
			at = i
		}
	}
	return at, yes, yes >= 0 && no >= 0 && at >= 0
}
