//go:build e2e

package claude

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// TestCreateWorktree starts a session in a new worktree of the probe
// repository, as the phone's Worktree entry does: the list shows it in the
// worktree, under the repository. Removing it removes the worktree too.
func TestCreateWorktree(t *testing.T) {
	probe := probeRepo(t)
	x := h(t)
	const name = "probe-wt"
	wt := filepath.Join(probe, ".claude", "worktrees", name)
	git := func(args ...string) string {
		out, _ := exec.Command("git", append([]string{"-C", probe}, args...)...).CombinedOutput()
		return string(out)
	}
	if strings.Contains(git("worktree", "list"), wt) {
		t.Fatalf("%s 已经有 worktree %s，先清掉", probe, name)
	}
	tag := make([]byte, 3)
	_, _ = rand.Read(tag)
	var res protocol.SessionCreateResponse
	x.must(t, protocol.MethodSessionCreate, protocol.SessionCreateRequest{
		ProfileID: profileID, Cwd: probe, Name: "e2e-worktree-" + hex.EncodeToString(tag), Prompt: "只回复 OK", Worktree: name,
	}, &res)
	createdMu.Lock()
	created = append(created, res.SessionID)
	createdMu.Unlock()
	s := &session{x: x, t: t, id: res.SessionID, ws: probe, keep: true}
	t.Cleanup(func() {
		s.cleanup()
		if strings.Contains(git("worktree", "list"), wt) {
			t.Errorf("移出会话后 worktree %s 还在，已清掉", name)
			git("worktree", "unlock", wt)
			git("worktree", "remove", "--force", wt)
			git("branch", "-D", "worktree-"+name)
		}
	})

	var got protocol.Session
	waitFor(t, 60*time.Second, "会话出现在列表里，带着 worktree", func() bool {
		list, _, err := x.src.ListTargets(context.Background())
		if err != nil {
			return false
		}
		for _, ss := range list.Sessions {
			if ss.ID == res.SessionID && ss.Worktree != nil {
				got = ss
				return true
			}
		}
		return false
	})
	if real(got.Worktree.Path) != real(wt) || got.Worktree.Name != name {
		t.Errorf("worktree = %+v, want %s at %s", got.Worktree, name, wt)
	}
	if real(got.Dir) != real(probe) {
		t.Errorf("dir = %s, want the repository %s", got.Dir, probe)
	}
	if !strings.Contains(git("worktree", "list"), wt) {
		t.Errorf("git 不知道 worktree %s：\n%s", wt, git("worktree", "list"))
	}
	// Removed mid-launch, claude leaves the worktree locked; the phone
	// removes sessions that are done.
	tg, _ := x.target(s.id)
	s.sessionID = tg.SessionID
	waitFor(t, 60*time.Second, "会话记录文件", func() bool {
		p, err := transcript.Find(x.src.ClaudeDir, s.sessionID)
		s.path = p
		return err == nil
	})
	s.waitIdle(120 * time.Second)
}

// real resolves symbolic links, so /tmp and /private/tmp compare equal.
func real(p string) string {
	if r, err := filepath.EvalSymlinks(p); err == nil {
		return r
	}
	return filepath.Clean(p)
}
