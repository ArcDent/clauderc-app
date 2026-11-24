//go:build e2e

package claude

import (
	"context"
	"encoding/json"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// commandOutput waits for the chat item of slash command name and returns
// its output.
func (s *session) commandOutput(t *testing.T, name string) string {
	t.Helper()
	var out string
	waitFor(t, 30*time.Second, name+" 的输出", func() bool {
		var res protocol.ChatOpenResponse
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
		for _, it := range res.Items {
			if it.Tool != nil && it.Tool.ToolName == name && it.Tool.Result != nil && it.Tool.Result.Content != "" {
				out = it.Tool.Result.Content
				return true
			}
		}
		return false
	})
	return out
}

// TestSlashCommon sends /fork and /plan, offered in completion since
// 2.1.328, from the phone to a background session of the probe repository:
// each is delivered and shows its output in the chat, /fork starts a copy
// listed under its source and /plan turns plan mode on. Why /btw, /fast,
// /branch and /stop stay left out is noted at transcript.UnofferedCommands.
func TestSlashCommon(t *testing.T) {
	probe := probeRepo(t)
	t.Run("fork", func(t *testing.T) {
		s := launchAt(t, "slash-fork", plain, probe, true)
		s.send("/fork 只回复 FORKED")
		// Claude Code refuses /fork in an account login session, launched
		// with --setting-sources: the agent forks it itself, and no /fork
		// item shows in its chat.
		if tmpl, ok := s.x.src.SessionTemplate(s.id); !ok || !slices.Contains(tmpl.Flags, "--setting-sources") {
			if out := s.commandOutput(t, "/fork"); !strings.Contains(out, "⑂") {
				t.Errorf("/fork 输出 %q，want 新会话的名字里带 ⑂", out)
			}
		}
		waitFor(t, 30*time.Second, "fork 出的会话出现在列表里，挂在来源下", func() bool {
			list, _, err := s.x.src.ListTargets(context.Background())
			if err != nil {
				return false
			}
			for _, ss := range list.Sessions {
				if ss.ForkOf == s.id {
					createdMu.Lock()
					created = append(created, ss.ID)
					createdMu.Unlock()
					return true
				}
			}
			return false
		})
	})
	t.Run("plan", func(t *testing.T) {
		s := launchAt(t, "slash-plan", plain, probe, true)
		s.send("/plan")
		if out := s.commandOutput(t, "/plan"); !strings.Contains(strings.ToLower(out), "plan mode") {
			t.Errorf("/plan 输出 %q，want 打开计划模式", out)
		}
		waitFor(t, 30*time.Second, "权限模式变成 plan", func() bool {
			var res protocol.ChatOpenResponse
			s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
			return res.PermissionMode == protocol.PermissionPlan
		})
	})
	// Claude Code only warns about a command it has no command or skill
	// for, so chat.send says so at once instead of "not delivered".
	t.Run("unknown", func(t *testing.T) {
		s := launchAt(t, "slash-unknown", plain, probe, true)
		_, e := s.x.call(t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: "/clauderc-no-such-command 参数"})
		if want := "会话不认识 /clauderc-no-such-command 这个命令"; e == nil || e.Code != protocol.CodeInvalidParams || e.Message != want {
			t.Errorf("chat.send 错误 = %+v，want invalid_params %s", e, want)
		}
	})
	// /compact is written to the transcript only once the compaction is
	// done, long after the delivery timeout; the screen tells it went out.
	t.Run("compact", func(t *testing.T) {
		s := launchAt(t, "slash-compact", plain, probe, true)
		s.send("/compact")
		waitFor(t, 5*time.Minute, "会话记录出现 compact_boundary", func() bool {
			raw, err := os.ReadFile(s.path)
			return err == nil && strings.Contains(string(raw), `"compact_boundary"`)
		})
	})
	// /clear starts a new transcript under the same background session; the
	// open chat switches to it and delivers messages and replies sent afterwards.
	t.Run("clear", func(t *testing.T) {
		s := launchAt(t, "slash-clear", plain, probe, true)
		oldSID := s.sessionID
		s.x.mu.Lock()
		evStart := len(s.x.events)
		s.x.mu.Unlock()

		s.send("/clear")
		waitFor(t, 30*time.Second, "/clear 后 sessionId 切换或推送 reset", func() bool {
			if tg, ok := s.x.target(s.id); ok && tg.SessionID != "" && tg.SessionID != oldSID {
				return true
			}
			return false
		})
		if tg, ok := s.x.target(s.id); ok {
			t.Logf("/clear 后 sessionId: %s -> %s", oldSID, tg.SessionID)
		}
		// Wait for the open chat's state poll to pick up the new transcript.
		waitFor(t, 30*time.Second, "手机收到 /clear 后的 reset 推送", func() bool {
			s.x.mu.Lock()
			defer s.x.mu.Unlock()
			for _, ev := range s.x.events[evStart:] {
				if ev.Method != protocol.EventChatItems {
					continue
				}
				var body protocol.ChatItemsEvent
				if json.Unmarshal(ev.Body, &body) == nil && body.SessionID == s.id && body.Reset {
					return true
				}
			}
			return false
		})

		s.send("清空后的消息：只回复 CLEARED_OK")
		waitFor(t, 120*time.Second, "手机聊天收到 /clear 后的消息和回复", func() bool {
			s.x.mu.Lock()
			defer s.x.mu.Unlock()
			var sawUser, sawReply bool
			for _, ev := range s.x.events[evStart:] {
				if ev.Method != protocol.EventChatItems {
					continue
				}
				var body protocol.ChatItemsEvent
				if json.Unmarshal(ev.Body, &body) != nil || body.SessionID != s.id {
					continue
				}
				for _, it := range body.Items {
					if it.Kind == protocol.ItemUser && strings.Contains(it.Text, "CLEARED_OK") {
						sawUser = true
					}
					if it.Kind == protocol.ItemAssistant && strings.Contains(it.Text, "CLEARED_OK") {
						sawReply = true
					}
				}
			}
			return sawUser && sawReply
		})
	})
}
