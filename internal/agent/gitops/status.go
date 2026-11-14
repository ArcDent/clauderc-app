package gitops

import (
	"bytes"
	"context"
	"strconv"
	"strings"

	"clauderc/internal/protocol"
)

// Status returns the current branch, its upstream with ahead and behind
// counts, and the uncommitted files split into staged and unstaged.
// Untracked files are listed one by one under unstaged; a file with both
// staged and unstaged changes is in both lists.
func (g Git) Status(ctx context.Context, dir string) (protocol.GitStatusResponse, error) {
	out, err := g.Run(ctx, dir, "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all")
	if err != nil {
		return protocol.GitStatusResponse{}, err
	}
	return parseStatus(out), nil
}

// parseStatus reads `git status --porcelain=v2 --branch -z` output.
func parseStatus(out []byte) protocol.GitStatusResponse {
	res := protocol.GitStatusResponse{Staged: []protocol.FileStatus{}, Unstaged: []protocol.FileStatus{}}
	fields := strings.Split(string(out), "\x00")
	for i := 0; i < len(fields); i++ {
		f := fields[i]
		switch {
		case strings.HasPrefix(f, "# branch.head "):
			if head := strings.TrimPrefix(f, "# branch.head "); head != "(detached)" {
				res.Branch = head
			}
		case strings.HasPrefix(f, "# branch.upstream "):
			res.Upstream = strings.TrimPrefix(f, "# branch.upstream ")
		case strings.HasPrefix(f, "# branch.ab "):
			var ahead, behind string
			if ab := strings.Fields(strings.TrimPrefix(f, "# branch.ab ")); len(ab) == 2 {
				ahead, behind = ab[0], ab[1]
			}
			res.Ahead, _ = strconv.Atoi(strings.TrimPrefix(ahead, "+"))
			res.Behind, _ = strconv.Atoi(strings.TrimPrefix(behind, "-"))
		case strings.HasPrefix(f, "1 "):
			// 1 XY sub mH mI mW hH hI path
			if p := strings.SplitN(f, " ", 9); len(p) == 9 {
				addXY(&res, p[1], p[8], "")
			}
		case strings.HasPrefix(f, "2 "):
			// 2 XY sub mH mI mW hH hI Xscore path, then origPath
			if p := strings.SplitN(f, " ", 10); len(p) == 10 && i+1 < len(fields) {
				i++
				addXY(&res, p[1], p[9], fields[i])
			}
		case strings.HasPrefix(f, "u "):
			// u XY sub m1 m2 m3 mW h1 h2 h3 path: unmerged, shown as modified.
			if p := strings.SplitN(f, " ", 11); len(p) == 11 {
				res.Unstaged = append(res.Unstaged, protocol.FileStatus{Path: p[10], Change: protocol.ChangeModified})
			}
		case strings.HasPrefix(f, "? "):
			res.Unstaged = append(res.Unstaged, protocol.FileStatus{Path: f[2:], Change: protocol.ChangeUntracked})
		}
	}
	return res
}

// addXY files one changed entry by its XY code: X is the index side, Y
// the working tree side, "." meaning unchanged.
func addXY(res *protocol.GitStatusResponse, xy, path, oldPath string) {
	if len(xy) != 2 {
		return
	}
	res.Staged = addChange(res.Staged, xy[0], path, oldPath)
	res.Unstaged = addChange(res.Unstaged, xy[1], path, oldPath)
}

// addChange appends the entry when code is a change letter.
func addChange(list []protocol.FileStatus, code byte, path, oldPath string) []protocol.FileStatus {
	c, ok := changes[code]
	if !ok {
		return list
	}
	fs := protocol.FileStatus{Path: path, Change: c}
	if c == protocol.ChangeRenamed {
		fs.OldPath = oldPath
	}
	return append(list, fs)
}

// changes maps porcelain status letters; a copy is a new file and an
// unmerged or type-changed file counts as modified.
var changes = map[byte]protocol.FileChange{
	'A': protocol.ChangeAdded,
	'C': protocol.ChangeAdded,
	'M': protocol.ChangeModified,
	'T': protocol.ChangeModified,
	'U': protocol.ChangeModified,
	'D': protocol.ChangeDeleted,
	'R': protocol.ChangeRenamed,
}

// Branches lists local branches and remote-tracking branches, leaving out
// symbolic refs such as origin/HEAD. Current is empty on a detached HEAD.
func (g Git) Branches(ctx context.Context, dir string) (protocol.GitBranchesResponse, error) {
	out, err := g.Run(ctx, dir, "for-each-ref", "--format=%(HEAD)%00%(refname)%00%(symref)", "refs/heads", "refs/remotes")
	if err != nil {
		return protocol.GitBranchesResponse{}, err
	}
	res := protocol.GitBranchesResponse{Local: []string{}, Remote: []protocol.RemoteBranch{}}
	for _, line := range bytes.Split(bytes.TrimSpace(out), []byte("\n")) {
		f := strings.Split(string(line), "\x00")
		if len(f) != 3 || f[2] != "" {
			continue
		}
		if name, ok := strings.CutPrefix(f[1], "refs/heads/"); ok {
			res.Local = append(res.Local, name)
			if f[0] == "*" {
				res.Current = name
			}
		} else if rest, ok := strings.CutPrefix(f[1], "refs/remotes/"); ok {
			if remote, name, ok := strings.Cut(rest, "/"); ok {
				res.Remote = append(res.Remote, protocol.RemoteBranch{Remote: remote, Name: name})
			}
		}
	}
	return res, nil
}
