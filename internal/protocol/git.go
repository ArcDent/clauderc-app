package protocol

import "time"

// GitLogPageSize is how many commits one git.log page holds (git-workspace spec).
const GitLogPageSize = 100

// DiffCollapseLines is the line count above which a diff starts collapsed.
const DiffCollapseLines = 1000

// FileChange is how a file differs in the working tree or index.
type FileChange string

const (
	ChangeAdded     FileChange = "added"
	ChangeModified  FileChange = "modified"
	ChangeDeleted   FileChange = "deleted"
	ChangeRenamed   FileChange = "renamed"
	ChangeUntracked FileChange = "untracked"
)

// FileStatus is one uncommitted file.
type FileStatus struct {
	Path string `json:"path"`
	// OldPath is set for renames.
	OldPath string     `json:"oldPath,omitempty"`
	Change  FileChange `json:"change"`
}

// GitStatusRequest asks for the current state of a directory's repository.
type GitStatusRequest struct {
	Dir string `json:"dir"`
}

// GitStatusResponse answers git.status.
type GitStatusResponse struct {
	// Branch is empty on a detached HEAD.
	Branch string `json:"branch"`
	// Upstream is empty when the branch has no upstream.
	Upstream string       `json:"upstream,omitempty"`
	Ahead    int          `json:"ahead"`
	Behind   int          `json:"behind"`
	Staged   []FileStatus `json:"staged"`
	Unstaged []FileStatus `json:"unstaged"`
}

// GitLogRequest asks for one page of history across all local branches.
type GitLogRequest struct {
	Dir string `json:"dir"`
	// Page is zero-based; each page holds GitLogPageSize commits.
	Page int `json:"page"`
}

// RefKind is the kind of a ref pointing at a commit.
type RefKind string

const (
	RefBranch       RefKind = "branch"
	RefRemoteBranch RefKind = "remoteBranch"
	RefTag          RefKind = "tag"
)

// Ref is a branch or tag name shown next to a commit.
type Ref struct {
	Name string  `json:"name"`
	Kind RefKind `json:"kind"`
}

// Edge is a timeline line from lane From on this commit's row to lane To on
// the next row down.
type Edge struct {
	From int `json:"from"`
	To   int `json:"to"`
}

// Commit is one timeline node with its precomputed layout (design D9).
type Commit struct {
	Hash    string    `json:"hash"`
	Parents []string  `json:"parents"`
	Author  string    `json:"author"`
	Time    time.Time `json:"time"`
	Subject string    `json:"subject"`
	Refs    []Ref     `json:"refs,omitempty"`
	// Lane is the column of this commit's node, starting at 0.
	Lane  int    `json:"lane"`
	Edges []Edge `json:"edges"`
}

// GitLogResponse answers git.log, newest first in topological order.
type GitLogResponse struct {
	Commits []Commit `json:"commits"`
	HasMore bool     `json:"hasMore"`
}

// GitShowRequest asks for one commit's details.
type GitShowRequest struct {
	Dir  string `json:"dir"`
	Hash string `json:"hash"`
}

// CommitFile is one file changed by a commit.
type CommitFile struct {
	Path    string `json:"path"`
	OldPath string `json:"oldPath,omitempty"`
	Added   int    `json:"added"`
	Deleted int    `json:"deleted"`
	Binary  bool   `json:"binary"`
}

// GitShowResponse answers git.show.
type GitShowResponse struct {
	Hash    string    `json:"hash"`
	Parents []string  `json:"parents"`
	Author  string    `json:"author"`
	Time    time.Time `json:"time"`
	// Message is the full commit message.
	Message string       `json:"message"`
	Files   []CommitFile `json:"files"`
}

// GitDiffRequest asks for one file's diff: within the commit Hash when set,
// otherwise in the index (Staged) or the working tree.
type GitDiffRequest struct {
	Dir    string `json:"dir"`
	Path   string `json:"path"`
	Hash   string `json:"hash,omitempty"`
	Staged bool   `json:"staged"`
}

// GitDiffResponse answers git.diff.
type GitDiffResponse struct {
	Path   string `json:"path"`
	Binary bool   `json:"binary"`
	// Patch is unified diff text; empty for binary files.
	Patch string `json:"patch,omitempty"`
	// Collapsed is true when the diff exceeds DiffCollapseLines lines.
	Collapsed bool `json:"collapsed"`
}

// GitBranchesRequest asks for local and remote branches.
type GitBranchesRequest struct {
	Dir string `json:"dir"`
}

// RemoteBranch is a remote-tracking branch, e.g. origin/feat-x.
type RemoteBranch struct {
	Remote string `json:"remote"`
	Name   string `json:"name"`
}

// GitBranchesResponse answers git.branches.
type GitBranchesResponse struct {
	Current string         `json:"current"`
	Local   []string       `json:"local"`
	Remote  []RemoteBranch `json:"remote"`
}

// GitStageRequest stages one file.
type GitStageRequest struct {
	Dir  string `json:"dir"`
	Path string `json:"path"`
}

// GitUnstageRequest unstages one file.
type GitUnstageRequest struct {
	Dir  string `json:"dir"`
	Path string `json:"path"`
}

// GitCommitRequest commits the staged changes.
type GitCommitRequest struct {
	Dir     string `json:"dir"`
	Message string `json:"message"`
}

// GitCommitResponse answers git.commit.
type GitCommitResponse struct {
	Hash string `json:"hash"`
}

// GitPullRequest fast-forwards the current branch.
type GitPullRequest struct {
	Dir string `json:"dir"`
}

// GitPushRequest pushes the current branch. Without an upstream the agent
// answers CodeNeedsConfirmation; the phone retries with SetUpstream after the
// user confirms pushing to origin.
type GitPushRequest struct {
	Dir         string `json:"dir"`
	SetUpstream bool   `json:"setUpstream"`
}

// GitSwitchRequest switches to a local branch, or to a remote-only branch by
// its name without the remote prefix.
type GitSwitchRequest struct {
	Dir    string `json:"dir"`
	Branch string `json:"branch"`
}

// DirBusyRequest asks whether a working session is in Dir or below.
type DirBusyRequest struct {
	Dir string `json:"dir"`
}

// DirBusyResponse answers dir.busy.
type DirBusyResponse struct {
	Busy bool `json:"busy"`
}
