package gitops

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"clauderc/internal/protocol"
)

// logFormat separates fields with US and ends each commit with RS; %D
// with --decorate=full gives full ref names so their kinds are known.
const logFormat = "--format=%H%x1f%P%x1f%an%x1f%aI%x1f%s%x1f%D%x1e"

// Log returns page (zero-based) of the history across all local branches
// in topological order, protocol.GitLogPageSize commits per page, with
// the lane layout of each commit. The layout is computed from the newest
// commit down to the end of the page every time (design D9), so pages
// join up.
func (g Git) Log(ctx context.Context, dir string, page int) (protocol.GitLogResponse, error) {
	if page < 0 {
		return protocol.GitLogResponse{}, &protocol.Error{Code: protocol.CodeInvalidParams, Message: "页码不能为负"}
	}
	end := (page + 1) * protocol.GitLogPageSize
	out, err := g.Run(ctx, dir, "log", "--branches", "--topo-order", "--decorate=full", "--no-show-signature", "--no-color",
		"--max-count="+strconv.Itoa(end+1), logFormat)
	if err != nil {
		return protocol.GitLogResponse{}, err
	}
	commits, err := parseLog(out)
	if err != nil {
		return protocol.GitLogResponse{}, err
	}
	res := protocol.GitLogResponse{Commits: []protocol.Commit{}, HasMore: len(commits) > end}
	commits = commits[:min(end, len(commits))]
	layout(commits)
	if start := page * protocol.GitLogPageSize; start < len(commits) {
		res.Commits = commits[start:]
	}
	return res, nil
}

// parseLog reads output in logFormat.
func parseLog(out []byte) ([]protocol.Commit, error) {
	commits := []protocol.Commit{}
	for _, rec := range strings.Split(string(out), "\x1e") {
		rec = strings.TrimLeft(rec, "\n")
		if rec == "" {
			continue
		}
		f := strings.Split(rec, "\x1f")
		if len(f) != 6 {
			return nil, fmt.Errorf("git log: unexpected record %q", rec)
		}
		at, err := time.Parse(time.RFC3339, f[3])
		if err != nil {
			return nil, fmt.Errorf("git log: %w", err)
		}
		parents := strings.Fields(f[1])
		if parents == nil {
			parents = []string{}
		}
		commits = append(commits, protocol.Commit{
			Hash:    f[0],
			Parents: parents,
			Author:  f[2],
			Time:    at.UTC(),
			Subject: f[4],
			Refs:    parseRefs(f[5]),
		})
	}
	return commits, nil
}

// parseRefs reads a full-name %D decoration such as
// "HEAD -> refs/heads/main, refs/remotes/origin/main, tag: refs/tags/v1".
// HEAD itself and symbolic refs like origin/HEAD are left out.
func parseRefs(decoration string) []protocol.Ref {
	var refs []protocol.Ref
	for _, d := range strings.Split(decoration, ", ") {
		d = strings.TrimPrefix(d, "HEAD -> ")
		if name, ok := strings.CutPrefix(d, "tag: refs/tags/"); ok {
			refs = append(refs, protocol.Ref{Name: name, Kind: protocol.RefTag})
		} else if name, ok := strings.CutPrefix(d, "refs/heads/"); ok {
			refs = append(refs, protocol.Ref{Name: name, Kind: protocol.RefBranch})
		} else if name, ok := strings.CutPrefix(d, "refs/remotes/"); ok && !strings.HasSuffix(name, "/HEAD") {
			refs = append(refs, protocol.Ref{Name: name, Kind: protocol.RefRemoteBranch})
		}
	}
	return refs
}

// layout assigns each commit, newest first, its lane and the edges from
// its row to the next one. Each lane remembers the commit it leads to.
// A commit takes the lane waiting for it, or the first free one. Its
// first parent continues in that lane; when another lane already waits
// for that parent, the line joins it if it is further left, or is pulled
// over into this lane if it is further right. Further parents of a merge
// join the lane waiting for them or open one. Lanes not involved simply
// continue straight down.
func layout(commits []protocol.Commit) {
	var lanes []string // lanes[i] is the hash lane i leads to, "" when free
	for i := range commits {
		c := &commits[i]
		lane := index(lanes, c.Hash)
		if lane < 0 {
			lane = index(lanes, "")
			if lane < 0 {
				lane = len(lanes)
				lanes = append(lanes, "")
			}
		}
		c.Lane = lane
		c.Edges = []protocol.Edge{}
		for j, h := range lanes {
			if j != lane && h != "" {
				c.Edges = append(c.Edges, protocol.Edge{From: j, To: j})
			}
		}
		lanes[lane] = ""
		for k, p := range c.Parents {
			to := index(lanes, p)
			switch {
			case to > lane && k == 0:
				// Pull the line already waiting for p over into this lane,
				// so the main line does not drift right.
				lanes[lane], lanes[to] = p, ""
				for e := range c.Edges {
					if c.Edges[e] == (protocol.Edge{From: to, To: to}) {
						c.Edges[e].To = lane
					}
				}
				to = lane
			case to >= 0:
			case k == 0:
				to = lane
				lanes[lane] = p
			default:
				to = index(lanes, "")
				if to < 0 {
					to = len(lanes)
					lanes = append(lanes, "")
				}
				lanes[to] = p
			}
			c.Edges = append(c.Edges, protocol.Edge{From: lane, To: to})
		}
		for len(lanes) > 0 && lanes[len(lanes)-1] == "" {
			lanes = lanes[:len(lanes)-1]
		}
		sort.Slice(c.Edges, func(a, b int) bool {
			ea, eb := c.Edges[a], c.Edges[b]
			return ea.From < eb.From || ea.From == eb.From && ea.To < eb.To
		})
	}
}

func index(lanes []string, hash string) int {
	for i, h := range lanes {
		if h == hash {
			return i
		}
	}
	return -1
}
