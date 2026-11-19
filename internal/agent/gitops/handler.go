package gitops

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	"clauderc/internal/agent"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// Options configures a Handler.
type Options struct {
	Git Git
	// Source lists the sessions dir.busy looks at.
	Source discovery.Source
	// Logger receives the errors behind internal failures, which the
	// phone only sees as a fixed message; nil discards them.
	Logger *slog.Logger
}

// Handler implements agent.Handler for the git.* methods and dir.busy. It
// keeps no per-client state.
type Handler struct {
	opts Options
}

// New returns a Handler.
func New(opts Options) *Handler {
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	return &Handler{opts: opts}
}

// Methods implements agent.Handler.
func (h *Handler) Methods() []protocol.Method {
	return []protocol.Method{
		protocol.MethodGitStatus, protocol.MethodGitLog, protocol.MethodGitShow, protocol.MethodGitDiff,
		protocol.MethodGitBranches, protocol.MethodGitStage, protocol.MethodGitUnstage, protocol.MethodGitCommit,
		protocol.MethodGitPull, protocol.MethodGitPush, protocol.MethodGitSwitch, protocol.MethodDirBusy,
	}
}

// Handle implements agent.Handler.
func (h *Handler) Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	res, err := h.handle(ctx, req)
	var pe *protocol.Error
	switch {
	case err == errUnknownMethod:
		return agent.FailUnknown(req)
	case errors.As(err, &pe):
		return agent.FailErr(req, err)
	case err != nil:
		h.opts.Logger.Error("git request failed", "method", req.Method, "err", err)
		return agent.FailErr(req, err)
	}
	return agent.Reply(req, res)
}

// errUnknownMethod marks a method this handler does not serve.
var errUnknownMethod = errors.New("unknown method")

// empty is the body of responses that carry nothing.
type empty struct{}

func (h *Handler) handle(ctx context.Context, req protocol.Envelope) (any, error) {
	g := h.opts.Git
	switch req.Method {
	case protocol.MethodGitStatus:
		var b protocol.GitStatusRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return g.Status(ctx, b.Dir) })
	case protocol.MethodGitLog:
		var b protocol.GitLogRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return g.Log(ctx, b.Dir, b.Page) })
	case protocol.MethodGitShow:
		var b protocol.GitShowRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return g.Show(ctx, b.Dir, b.Hash) })
	case protocol.MethodGitDiff:
		var b protocol.GitDiffRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return g.Diff(ctx, b) })
	case protocol.MethodGitBranches:
		var b protocol.GitBranchesRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return g.Branches(ctx, b.Dir) })
	case protocol.MethodGitStage:
		var b protocol.GitStageRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return empty{}, g.Stage(ctx, b.Dir, b.Path) })
	case protocol.MethodGitUnstage:
		var b protocol.GitUnstageRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return empty{}, g.Unstage(ctx, b.Dir, b.Path) })
	case protocol.MethodGitCommit:
		var b protocol.GitCommitRequest
		return withDir(req, &b, &b.Dir, func() (any, error) {
			hash, err := g.Commit(ctx, b.Dir, b.Message)
			return protocol.GitCommitResponse{Hash: hash}, err
		})
	case protocol.MethodGitPull:
		var b protocol.GitPullRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return empty{}, g.Pull(ctx, b.Dir) })
	case protocol.MethodGitPush:
		var b protocol.GitPushRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return empty{}, g.Push(ctx, b.Dir, b.SetUpstream) })
	case protocol.MethodGitSwitch:
		var b protocol.GitSwitchRequest
		return withDir(req, &b, &b.Dir, func() (any, error) { return empty{}, g.Switch(ctx, b.Dir, b.Branch) })
	case protocol.MethodDirBusy:
		var b protocol.DirBusyRequest
		return withDir(req, &b, &b.Dir, func() (any, error) {
			busy, err := h.Busy(ctx, b.Dir)
			return protocol.DirBusyResponse{Busy: busy}, err
		})
	default:
		return nil, errUnknownMethod
	}
}

// withDir decodes the request body into body, checks that *dir is an
// existing absolute directory, then runs do.
func withDir(req protocol.Envelope, body any, dir *string, do func() (any, error)) (any, error) {
	if err := json.Unmarshal(req.Body, body); err != nil {
		return nil, &protocol.Error{Code: protocol.CodeInvalidParams, Message: agent.BadRequestMessage}
	}
	if !filepath.IsAbs(*dir) {
		return nil, &protocol.Error{Code: protocol.CodeInvalidParams, Message: "目录必须是绝对路径"}
	}
	if info, err := os.Stat(*dir); err != nil || !info.IsDir() {
		return nil, &protocol.Error{Code: protocol.CodeDirNotFound, Message: "目录不存在：" + *dir}
	}
	res, err := do()
	if err != nil {
		return nil, err
	}
	return res, nil
}

// Busy reports whether a working background session runs in dir or one
// of its subdirectories (design D9). A session in a git worktree only
// counts for the worktree itself and the directories inside it: it works
// on a checkout of its own, so the checkout it came from is not busy. A
// blocked session is settled against its transcript first, as for the
// session list (design D5). Symbolic links are resolved on both sides so
// /tmp and /private/tmp compare equal.
func (h *Handler) Busy(ctx context.Context, dir string) (bool, error) {
	targets, err := h.opts.Source.Targets(ctx)
	if err != nil {
		return false, err
	}
	root := realPath(dir)
	for _, t := range targets {
		pending := t.Blocked() && transcript.HasPending(h.opts.Source.ClaudeDir, t.SessionID)
		if t.SessionState(pending) != protocol.StateWorking {
			continue
		}
		if t.Worktree != "" && !within(root, realPath(t.Worktree)) {
			continue
		}
		if within(realPath(t.Cwd), root) {
			return true, nil
		}
	}
	return false, nil
}

// within reports whether path is base or lies under it.
func within(path, base string) bool {
	return path == base || strings.HasPrefix(path, strings.TrimSuffix(base, "/")+"/")
}

func realPath(p string) string {
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return r
	}
	return filepath.Clean(p)
}
