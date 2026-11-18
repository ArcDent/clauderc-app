//go:build e2e

package claude

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/sessions"
	"clauderc/internal/protocol"
)

// listed is the session as the phone's session list shows it, subagents
// included.
func (s *session) listed(h *sessions.Handler) protocol.Session {
	s.t.Helper()
	res := h.Handle(context.Background(), protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "list", Method: protocol.MethodSessionsSubscribe, Client: client, Body: json.RawMessage(`{}`)})
	h.Handle(context.Background(), protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: "unlist", Method: protocol.MethodSessionsUnsubscribe, Client: client, Body: json.RawMessage(`{}`)})
	if res.Error != nil {
		s.t.Fatalf("sessions.subscribe: %+v", res.Error)
	}
	var list protocol.SessionList
	if err := json.Unmarshal(res.Body, &list); err != nil {
		s.t.Fatal(err)
	}
	for _, it := range list.Sessions {
		if it.ID == s.id {
			return it
		}
	}
	return protocol.Session{}
}

// A background subagent shows in the session list while it runs, its own
// transcript opens with chat.open and its id, and it leaves the list once
// it ended. It reads a chain of files one at a time, which keeps it
// running for a while without any tool that asks for permission.
func TestSubagent(t *testing.T) {
	s := launch(t, "subagent")
	const n = 8
	if err := os.MkdirAll(filepath.Join(s.ws, "chain"), 0o755); err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= n; i++ {
		body := fmt.Sprintf("下一个文件：chain/%d.txt\n", i+1)
		if i == n {
			body = "END 口令：蓝鲸\n"
		}
		if err := os.WriteFile(filepath.Join(s.ws, "chain", fmt.Sprintf("%d.txt", i)), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	list := sessions.New(sessions.Options{Source: s.x.src})
	defer list.Close()

	m := s.mark()
	s.send("请用 Agent 工具派一个后台子代理，subagent_type 用 Explore，把下面这段话原样作为它的任务：" +
		"「从 chain/1.txt 开始，每次只读一个文件，按文件里写的下一个文件名接着读，直到读到写着 END 的文件，只回复那个文件里的口令。」" +
		"你自己不要读这些文件。派出后直接结束这一轮，只回复「已派出」。")
	call := s.toolUse("Agent", "chain/1.txt", m, 120*time.Second)
	s.allowUntilDone(call, 60*time.Second)

	var agent protocol.Subagent
	waitFor(t, 90*time.Second, "会话数据列出子代理", func() bool {
		for _, a := range s.listed(list).Agents {
			if a.Running && a.ID != "" {
				agent = a
				return true
			}
		}
		return false
	})
	t.Logf("在跑的子代理：%+v", agent)
	if agent.Type != "Explore" {
		t.Errorf("子代理类型 %q，want Explore", agent.Type)
	}

	var res protocol.ChatOpenResponse
	waitFor(t, 30*time.Second, "子代理记录里有任务", func() bool {
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id, AgentID: agent.ID}, &res)
		return len(res.Items) > 0
	})
	if first := res.Items[0]; first.Kind != protocol.ItemNotice || first.Title != "任务" || !strings.Contains(first.Text, "chain/1.txt") {
		t.Errorf("子代理的第一项 %+v，want 标题为「任务」的灰字", first)
	}
	if res.State != protocol.StateWorking {
		t.Errorf("子代理在跑时 state = %q，want working", res.State)
	}

	waitFor(t, 5*time.Minute, "子代理结束后不再列出", func() bool {
		for _, a := range s.listed(list).Agents {
			if a.ID == agent.ID {
				return false
			}
		}
		return true
	})
	waitFor(t, 30*time.Second, "子代理页变为已结束", func() bool {
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id, AgentID: agent.ID}, &res)
		return res.State != protocol.StateWorking
	})
	s.x.call(t, protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: s.id, AgentID: agent.ID})
	// The session takes in the subagent's result and replies.
	s.waitIdle(3 * time.Minute)
}

// inputLine is what Claude Code's input box holds, the ❯ line right under
// a rule, and whether the screen shows one.
func inputLine(lines []string) (string, bool) {
	for i := len(lines) - 1; i > 0; i-- {
		if rest, ok := strings.CutPrefix(lines[i], "❯"); ok && strings.HasPrefix(strings.TrimSpace(lines[i-1]), "─") {
			return strings.TrimSpace(strings.ReplaceAll(rest, " ", " ")), true
		}
	}
	return "", false
}

// The terminal view shows the input prompt with the cursor in it; a panel
// command sent from the chat opens its panel there, and Esc pressed in the
// view closes it again.
func TestTerminal(t *testing.T) {
	s := launch(t, "terminal")
	screen := func() protocol.TermScreen {
		var sc protocol.TermScreen
		s.x.must(t, protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: s.id}, &sc)
		return sc
	}
	defer s.x.call(t, protocol.MethodTermClose, protocol.TermCloseRequest{SessionID: s.id})

	var sc protocol.TermScreen
	waitFor(t, 30*time.Second, "终端里的输入提示", func() bool {
		sc = screen()
		_, ok := inputLine(sc.Lines)
		return ok && sc.Cursor && strings.HasPrefix(sc.Lines[sc.CursorRow], "❯")
	})
	if len(sc.Lines) != 40 {
		t.Errorf("屏幕 %d 行，want 40", len(sc.Lines))
	}

	// chat.send confirms a panel command once it left the input.
	s.send("/mcp")
	waitFor(t, 15*time.Second, "/mcp 的面板", func() bool {
		sc = screen()
		_, ok := inputLine(sc.Lines)
		return !ok
	})
	t.Logf("面板：\n%s", strings.Join(sc.Lines, "\n"))
	if sc.Cursor {
		t.Errorf("面板开着时光标仍显示在第 %d 行", sc.CursorRow)
	}
	if tg, ok := s.x.target(s.id); ok {
		t.Logf("面板开着时会话状态：%s", tg.State)
	}

	// Off the input, a message is refused and nothing reaches the panel.
	panel := strings.Join(sc.Lines, "\n")
	_, e := s.x.call(t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: "看一下重连的退避"})
	if e == nil || e.Code != protocol.CodeInvalidParams {
		t.Errorf("面板开着时 chat.send 返回 %+v，want invalidParams", e)
	} else {
		t.Logf("chat.send 被拒：%s", e.Message)
	}
	time.Sleep(time.Second)
	if now := strings.Join(screen().Lines, "\n"); now != panel {
		t.Errorf("被拒的消息动了面板：\n%s", now)
	}
	// The chat tells the phone the session is off its input.
	waitFor(t, 20*time.Second, "聊天的 unknownDialog", func() bool {
		var res protocol.ChatOpenResponse
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
		return res.UnknownDialog
	})

	// An Enter in the panel acts on what it has selected: here it opens
	// the first server's page. This is what the refusal keeps away.
	s.x.must(t, protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: s.id, Key: protocol.TermKeyEnter}, nil)
	waitFor(t, 10*time.Second, "回车后面板换了画面", func() bool {
		return strings.Join(screen().Lines, "\n") != panel
	})
	t.Logf("面板里按回车后：\n%s", strings.Join(screen().Lines, "\n"))

	// Esc goes back a level at a time until the input is there again.
	for i := 0; i < 4; i++ {
		s.x.must(t, protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: s.id, Key: protocol.TermKeyEsc}, nil)
		deadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(deadline) {
			sc = screen()
			if text, ok := inputLine(sc.Lines); ok && text == "" && sc.Cursor {
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
	}
	t.Fatalf("按了 4 次 Esc 还没回到输入提示：\n%s", strings.Join(sc.Lines, "\n"))
}
