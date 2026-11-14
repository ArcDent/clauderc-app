package gitops

import (
	"context"
	"fmt"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"clauderc/internal/protocol"
)

// commitHash is what Show and Diff accept as a commit: a hex object name,
// so it can never be taken for an option or a revision expression.
var commitHash = regexp.MustCompile(`^[0-9a-fA-F]{4,64}$`)

// diffArgs make every diff plain text whatever the user's config says;
// showArgs add, for commits, merges against their first parent and no
// signature checks.
var (
	diffArgs = []string{"--no-color", "--no-ext-diff", "--no-textconv"}
	showArgs = append(slices.Clone(diffArgs), "--diff-merges=first-parent", "--no-show-signature")
)

// pathspec makes a path from the phone, which is relative to the
// repository root like every path git status reports, match exactly that
// file from any subdirectory, with no wildcard expansion.
func pathspec(path string) string { return ":(top,literal)" + path }

func checkHash(hash string) error {
	if !commitHash.MatchString(hash) {
		return &protocol.Error{Code: protocol.CodeInvalidParams, Message: "提交哈希无效：" + hash}
	}
	return nil
}

// checkPath accepts a file path relative to the repository root that
// stays inside it.
func checkPath(path string) error {
	if !filepath.IsLocal(path) {
		return &protocol.Error{Code: protocol.CodeInvalidParams, Message: "文件路径无效：" + path}
	}
	return nil
}

// Show returns a commit's full message and its changed files with added
// and deleted line counts; binary files are flagged and counted as 0.
// A merge is compared with its first parent.
func (g Git) Show(ctx context.Context, dir, hash string) (protocol.GitShowResponse, error) {
	if err := checkHash(hash); err != nil {
		return protocol.GitShowResponse{}, err
	}
	head, err := g.Run(ctx, dir, "show", "-s", "--no-show-signature", "--format=%H%x1f%P%x1f%an%x1f%aI%x1f%B", hash, "--")
	if err != nil {
		return protocol.GitShowResponse{}, err
	}
	f := strings.SplitN(string(head), "\x1f", 5)
	if len(f) != 5 {
		return protocol.GitShowResponse{}, fmt.Errorf("git show: unexpected output %q", head)
	}
	at, err := time.Parse(time.RFC3339, f[3])
	if err != nil {
		return protocol.GitShowResponse{}, fmt.Errorf("git show: %w", err)
	}
	parents := strings.Fields(f[1])
	if parents == nil {
		parents = []string{}
	}
	stat, err := g.Run(ctx, dir, append(append([]string{"show", "--numstat", "-z", "--format="}, showArgs...), hash, "--")...)
	if err != nil {
		return protocol.GitShowResponse{}, err
	}
	return protocol.GitShowResponse{
		Hash:    f[0],
		Parents: parents,
		Author:  f[2],
		Time:    at.UTC(),
		Message: strings.TrimRight(f[4], "\n"),
		Files:   parseNumstat(stat),
	}, nil
}

// parseNumstat reads `--numstat -z`: "added\tdeleted\tpath" per file, or
// "added\tdeleted\t" followed by the old and new path for a rename, all
// NUL terminated; "-" counts mean a binary file.
func parseNumstat(out []byte) []protocol.CommitFile {
	files := []protocol.CommitFile{}
	tokens := strings.Split(strings.TrimLeft(string(out), "\n"), "\x00")
	for i := 0; i < len(tokens); i++ {
		f := strings.SplitN(tokens[i], "\t", 3)
		if len(f) != 3 {
			continue
		}
		cf := protocol.CommitFile{Path: f[2], Binary: f[0] == "-" && f[1] == "-"}
		if !cf.Binary {
			cf.Added, _ = strconv.Atoi(f[0])
			cf.Deleted, _ = strconv.Atoi(f[1])
		}
		if f[2] == "" && i+2 < len(tokens) {
			cf.OldPath, cf.Path = tokens[i+1], tokens[i+2]
			i += 2
		}
		files = append(files, cf)
	}
	return files
}

// binaryLine is how git reports a binary file instead of a patch.
var binaryLine = regexp.MustCompile(`(?m)^Binary files .* differ$`)

// Diff returns one file's patch: within commit req.Hash when set (against
// its first parent), otherwise between HEAD and the index (req.Staged)
// or between the index and the working tree, where an untracked file is
// shown as entirely added. Patches start at the first hunk; binary files
// carry no patch; patches over protocol.DiffCollapseLines lines are
// flagged collapsed but still sent whole.
func (g Git) Diff(ctx context.Context, req protocol.GitDiffRequest) (protocol.GitDiffResponse, error) {
	if err := checkPath(req.Path); err != nil {
		return protocol.GitDiffResponse{}, err
	}
	var out []byte
	var err error
	switch {
	case req.Hash != "":
		if err := checkHash(req.Hash); err != nil {
			return protocol.GitDiffResponse{}, err
		}
		out, err = g.Run(ctx, req.Dir, append(append([]string{"show", "--format="}, showArgs...), req.Hash, "--", pathspec(req.Path))...)
	case req.Staged:
		out, err = g.Run(ctx, req.Dir, append(append([]string{"diff", "--cached"}, diffArgs...), "--", pathspec(req.Path))...)
	default:
		out, err = g.Run(ctx, req.Dir, append(append([]string{"diff"}, diffArgs...), "--", pathspec(req.Path))...)
		if err == nil && len(out) == 0 {
			out, err = g.untrackedDiff(ctx, req.Dir, req.Path)
		}
	}
	if err != nil {
		return protocol.GitDiffResponse{}, err
	}
	res := protocol.GitDiffResponse{Path: req.Path}
	if binaryLine.Match(out) {
		res.Binary = true
		return res, nil
	}
	patch := string(out)
	if i := strings.Index(patch, "\n@@"); i >= 0 && !strings.HasPrefix(patch, "@@") {
		patch = patch[i+1:]
	}
	res.Patch = patch
	res.Collapsed = strings.Count(patch, "\n") > protocol.DiffCollapseLines
	return res, nil
}

// untrackedDiff shows an untracked file as added. git status confirms the
// path is an untracked file inside the repository first, so no other file
// on the machine can be read this way.
func (g Git) untrackedDiff(ctx context.Context, dir, path string) ([]byte, error) {
	st, err := g.Run(ctx, dir, "status", "--porcelain=v2", "-z", "--untracked-files=all", "--", pathspec(path))
	if err != nil {
		return nil, err
	}
	if string(st) != "? "+path+"\x00" {
		return nil, nil
	}
	top, err := g.Run(ctx, dir, "rev-parse", "--show-toplevel")
	if err != nil {
		return nil, err
	}
	r, err := g.exec(ctx, strings.TrimSpace(string(top)), append(append([]string{"diff", "--no-index"}, diffArgs...), "--", "/dev/null", path)...)
	if err != nil {
		return nil, err
	}
	// --no-index exits 1 when the files differ, which they always do here.
	if r.code > 1 {
		return nil, failure(dir, r)
	}
	return r.stdout, nil
}
