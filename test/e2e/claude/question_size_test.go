//go:build e2e

package claude

import (
	"encoding/json"
	"io"
	"os/exec"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/creack/pty"

	"clauderc/internal/protocol"
)

func TestQuestionOtherSize(t *testing.T) {
	const name = "other_size"
	questions := []protocol.Question{
		{Header: "活动", Question: "如果周末有整整两天可以和朋友一起出门，你最想安排哪些活动，请考虑路上的时间和天气变化，也可以推荐一个不在选项里但适合大家一起参加的活动？", MultiSelect: true, Options: []protocol.Option{
			{Label: "徒步", Description: "沿着森林步道走到观景台"},
			{Label: "看展", Description: "参观新开的摄影展"},
			{Label: "露营", Description: "在湖边营地过夜"},
			{Label: "骑车", Description: "绕湖骑行并在途中休息"},
		}},
		{Header: "晚餐", Question: "活动结束后，想去哪里吃晚餐？", Options: []protocol.Option{
			{Label: "面馆", Description: "有热汤面和小菜"},
			{Label: "食堂", Description: "座位充足，可以各自点菜"},
		}},
		{Header: "交通", Question: "回程选择哪种交通方式？", Options: []protocol.Option{
			{Label: "地铁", Description: "末班车前从湖边站出发"},
			{Label: "公交", Description: "乘坐直达市区的公交"},
		}},
	}
	useQuestionEndpoint(t, map[string]any{name: map[string]any{"questions": questions}})
	s := launch(t, name)
	p := ask(t, s, "QPAT:"+name)

	s.x.mu.Lock()
	firstEvent := len(s.x.events)
	s.x.mu.Unlock()
	const cols, rows = 127, 43
	cmd := exec.Command(s.x.src.ClaudePath, "attach", s.id)
	cmd.Env = append(cmd.Environ(), "TERM=xterm-256color")
	terminal, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: rows, Cols: cols})
	if err != nil {
		t.Fatal(err)
	}
	checkUntil := time.Now().Add(5 * time.Second)
	go io.Copy(io.Discard, terminal)
	exited := make(chan struct{})
	go func() {
		_ = cmd.Wait()
		close(exited)
	}()
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = terminal.Close()
		<-exited
	})

	rule := strings.Repeat("─", cols)
	checkSize := func() bool {
		var screen protocol.TermScreen
		s.x.must(t, protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: s.id}, &screen)
		return len(screen.Lines) == rows && slices.Contains(screen.Lines, rule)
	}
	waitFor(t, 10*time.Second, "提问画面切换为 127 列、43 行", checkSize)
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID,
		Answers: []protocol.Answer{{Selected: []int{0, 2}, Custom: "野餐"}, {Custom: "自己做饭"}, {Selected: []int{1}}}}, nil)
	_, result := s.result(p.ToolUseID)
	got := answers(result)
	for i, want := range []string{"徒步, 露营, 野餐", "自己做饭", "公交"} {
		if answer := got[questions[i].Question]; answer != want {
			t.Errorf("第 %d 题：得到 %q，想要 %q", i+1, answer, want)
		}
	}
	s.waitIdle(20 * time.Second)
	if !checkSize() {
		t.Fatal("回答后没有保留 127 列、43 行的排版")
	}
	mark := s.mark()
	const message = "确认可以继续发消息"
	s.send(message)
	s.waitUser(message, mark, 15*time.Second)

	time.Sleep(time.Until(checkUntil))
	s.x.mu.Lock()
	defer s.x.mu.Unlock()
	for _, e := range s.x.events[firstEvent:] {
		if e.Method != protocol.EventChatState {
			continue
		}
		var state protocol.ChatStateEvent
		if err := json.Unmarshal(e.Body, &state); err != nil {
			t.Fatal(err)
		}
		if state.SessionID == s.id && state.UnknownDialog {
			t.Error("第二个客户端接入后，chat.state 误报未知对话框")
		}
	}
	select {
	case <-exited:
		t.Fatal("第二个客户端提前退出")
	default:
	}
}
