//go:build e2e

package claude

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// A new session in a directory with one server in .mcp.json stops on the
// MCP approval dialog before reading its first message. The phone gets
// the dialog as a pending prompt; answering it lets the session go on.
func TestMCPDialog(t *testing.T) {
	x := h(t)
	tag := make([]byte, 3)
	_, _ = rand.Read(tag)
	ws := filepath.Join(x.root, ".local", "e2e-ws", "mcp_dialog-"+hex.EncodeToString(tag))
	if err := os.MkdirAll(ws, 0o755); err != nil {
		t.Fatal(err)
	}
	// The server never runs: the session is told to go on without it.
	mcp := `{"mcpServers":{"probe":{"command":"true","args":[]}}}`
	if err := os.WriteFile(filepath.Join(ws, ".mcp.json"), []byte(mcp), 0o644); err != nil {
		t.Fatal(err)
	}
	id := x.create(t, plain, protocol.SessionCreateRequest{
		Cwd: ws, Name: "e2e-mcp_dialog-" + hex.EncodeToString(tag), Prompt: "只回复 OK",
	})
	createdMu.Lock()
	created = append(created, id)
	createdMu.Unlock()
	s := &session{x: x, t: t, id: id, ws: ws}
	t.Cleanup(s.cleanup)
	waitFor(t, 60*time.Second, "会话在运行", func() bool {
		tg, ok := x.target(s.id)
		s.sessionID = tg.SessionID
		return ok && tg.Running && tg.SessionID != ""
	})
	x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, nil)

	p := s.pending(protocol.PendingDialog, 60*time.Second)
	want := []string{"Use this MCP server", "Use this and all future MCP servers in this project", "Continue without using this MCP server"}
	if len(p.Questions) != 1 || p.Questions[0].Question != "New MCP server found in this project: probe" {
		t.Fatalf("dialog %+v", p)
	}
	var got []string
	for _, o := range p.Questions[0].Options {
		got = append(got, o.Label)
	}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("options %q, want %q", got, want)
	}
	x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{
		SessionID: s.id, ToolUseID: p.ToolUseID, Answers: []protocol.Answer{{Selected: []int{2}}},
	}, nil)

	waitFor(t, 60*time.Second, "会话记录文件", func() bool {
		path, err := transcript.Find(x.src.ClaudeDir, s.sessionID)
		s.path = path
		return err == nil
	})
	s.waitIdle(120 * time.Second)
	m := s.mark()
	s.send("只回复 AFTER")
	if r := s.reply(s.waitUser("只回复 AFTER", m, 15*time.Second), 120*time.Second); !strings.Contains(r, "AFTER") {
		t.Errorf("回复 %q", r)
	}
}
