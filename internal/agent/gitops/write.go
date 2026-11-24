package gitops

import (
	"context"
	"path/filepath"
	"strings"
	"sync"

	"clauderc/internal/protocol"
)

// repoLocks holds a *sync.Mutex per repository, keyed by its common git
// directory, which all worktrees of a repository share.
var repoLocks sync.Map

// locked runs do as one write operation on the repository holding dir.
// Writes to one repository take turns: git refuses to update the index or
// a ref while another git does, and a file staged while a pre-commit hook
// runs would end up in that commit. A write is not stopped by ctx, since
// git cut short leaves its lock files behind: do runs on a context that is
// never cancelled, each git call bounded by its own timeout, and when ctx
// ends first locked returns at once while do carries on.
func locked[T any](ctx context.Context, g Git, dir string, do func(context.Context) (T, error)) (T, error) {
	type outcome struct {
		v   T
		err error
	}
	done := make(chan outcome, 1)
	go func() {
		ctx := context.WithoutCancel(ctx)
		out, err := g.Run(ctx, dir, "rev-parse", "--git-common-dir")
		if err != nil {
			done <- outcome{err: err}
			return
		}
		common := strings.TrimSpace(string(out))
		if !filepath.IsAbs(common) {
			common = filepath.Join(dir, common)
		}
		mu, _ := repoLocks.LoadOrStore(realPath(common), new(sync.Mutex))
		mu.(*sync.Mutex).Lock()
		defer mu.(*sync.Mutex).Unlock()
		v, err := do(ctx)
		done <- outcome{v, err}
	}()
	select {
	case o := <-done:
		return o.v, o.err
	case <-ctx.Done():
		var zero T
		return zero, ctx.Err()
	}
}

// Stage stages one file, including its deletion. Paths are relative to
// the repository root, as git status reports them.
func (g Git) Stage(ctx context.Context, dir, path string) error {
	if err := checkPath(path); err != nil {
		return err
	}
	_, err := locked(ctx, g, dir, func(ctx context.Context) ([]byte, error) {
		return g.Run(ctx, dir, "add", "--", pathspec(path))
	})
	return err
}

// Unstage takes one file out of the index, keeping the working tree. It
// uses reset with a path, which unlike restore also works before the
// first commit; nothing else is reset.
func (g Git) Unstage(ctx context.Context, dir, path string) error {
	if err := checkPath(path); err != nil {
		return err
	}
	_, err := locked(ctx, g, dir, func(ctx context.Context) ([]byte, error) {
		return g.Run(ctx, dir, "reset", "-q", "--", pathspec(path))
	})
	return err
}

// Commit commits the staged changes with message and returns the new
// commit's hash. An empty message or an empty index is refused before
// git commit runs; hooks run as usual.
func (g Git) Commit(ctx context.Context, dir, message string) (string, error) {
	if strings.TrimSpace(message) == "" {
		return "", &protocol.Error{Code: protocol.CodeInvalidParams, Message: "提交说明不能为空"}
	}
	return locked(ctx, g, dir, func(ctx context.Context) (string, error) {
		r, err := g.exec(ctx, dir, "diff", "--cached", "--quiet")
		if err != nil {
			return "", err
		}
		switch r.code {
		case 0:
			return "", &protocol.Error{Code: protocol.CodeInvalidParams, Message: "没有已暂存的改动"}
		case 1:
		default:
			return "", failure(dir, r)
		}
		if _, err := g.Run(ctx, dir, "commit", "-q", "--message="+message); err != nil {
			return "", err
		}
		out, err := g.Run(ctx, dir, "rev-parse", "HEAD")
		return strings.TrimSpace(string(out)), err
	})
}

// Pull fast-forwards the current branch to its upstream and fails, with
// the branch untouched, when that is not possible.
func (g Git) Pull(ctx context.Context, dir string) error {
	_, err := locked(ctx, g, dir, func(ctx context.Context) ([]byte, error) {
		return g.Run(ctx, dir, "pull", "--ff-only")
	})
	return err
}

// Push pushes the current branch to its upstream, once and never forced.
// Without an upstream it answers CodeNeedsConfirmation unless
// setUpstream is true, in which case it pushes to the branch of the same
// name on origin and makes that the upstream.
func (g Git) Push(ctx context.Context, dir string, setUpstream bool) error {
	_, err := locked(ctx, g, dir, func(ctx context.Context) ([]byte, error) {
		r, err := g.exec(ctx, dir, "symbolic-ref", "--quiet", "--short", "HEAD")
		switch {
		case err != nil:
			return nil, err
		case r.code == 1:
			return nil, &protocol.Error{Code: protocol.CodeGitFailed, Message: "当前不在任何分支上，无法推送"}
		case r.code != 0:
			return nil, failure(dir, r)
		}
		branch := strings.TrimSpace(string(r.stdout))
		r, err = g.exec(ctx, dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
		if err != nil {
			return nil, err
		}
		if r.code == 0 {
			return g.Run(ctx, dir, "push")
		}
		if !setUpstream {
			return nil, &protocol.Error{Code: protocol.CodeNeedsConfirmation, Message: "当前分支 " + branch + " 没有上游，确认后推送到 origin/" + branch + " 并设为上游"}
		}
		return g.Run(ctx, dir, "push", "--set-upstream", "origin", branch)
	})
	return err
}

// Switch switches to a local branch, or creates a local branch tracking
// the remote branch of that name when only a remote has it. git refuses,
// listing the files, when uncommitted changes would be overwritten.
func (g Git) Switch(ctx context.Context, dir, branch string) error {
	invalid := &protocol.Error{Code: protocol.CodeInvalidParams, Message: "分支名无效：" + branch}
	if branch == "" || strings.HasPrefix(branch, "-") {
		return invalid
	}
	if r, err := g.exec(ctx, dir, "check-ref-format", "--branch", branch); err != nil {
		return err
	} else if r.code != 0 {
		return invalid
	}
	_, err := locked(ctx, g, dir, func(ctx context.Context) ([]byte, error) {
		return g.Run(ctx, dir, "switch", branch)
	})
	return err
}
