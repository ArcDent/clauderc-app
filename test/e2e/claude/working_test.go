//go:build e2e

package claude

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
	"unicode"
	"unicode/utf8"

	"clauderc/internal/protocol"
)

// spinnerGlyphs are the characters Claude Code turns at the start of its
// status line while it works.
const spinnerGlyphs = "·✢✳✶✻✽*"

// statusLine returns the line of a working screen that reads like
// "✻ Incubating… (1m 17s · ↓ 7.5k tokens)".
func statusLine(lines []string) (string, bool) {
	for _, l := range lines {
		t := strings.TrimSpace(l)
		r := []rune(t)
		if len(r) > 2 && strings.ContainsRune(spinnerGlyphs, r[0]) && r[1] == ' ' && strings.Contains(t, "…") {
			return t, true
		}
	}
	return "", false
}

// squeeze drops white space, so text wrapped on screen compares with the
// transcript.
func squeeze(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, s)
}

// While a session writes a long reply its screen shows a status line. The
// screens are saved under .local/e2e-ws/ for the agent's test samples, and
// the test reports whether part of the reply is on screen before the reply
// reaches the transcript.
func TestWorkingScreen(t *testing.T) {
	s := launch(t, "working")
	screen := func() protocol.TermScreen {
		var sc protocol.TermScreen
		s.x.must(t, protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: s.id}, &sc)
		return sc
	}
	defer s.x.call(t, protocol.MethodTermClose, protocol.TermCloseRequest{SessionID: s.id})
	waitFor(t, 30*time.Second, "终端里的输入提示", func() bool {
		_, ok := inputLine(screen().Lines)
		return ok
	})
	s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, nil)
	defer s.x.call(t, protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: s.id})
	s.x.mu.Lock()
	firstEvent := len(s.x.events)
	s.x.mu.Unlock()

	m := s.mark()
	msg := "请写一篇至少 4000 字的中文长文，分 8 段，主题是终端模拟器怎样把字节流画成屏幕，直接输出正文，不要使用任何工具"
	s.send(msg)
	at := s.waitUser(msg, m, 15*time.Second)

	type frame struct {
		at    time.Duration
		lines []string
	}
	var frames []frame
	start := time.Now()
	var last string
	// stale counts the samples in a row at which the chat still passed on a
	// partial reply the transcript already had.
	stale, maxStale := 0, 0
	beforeWrite := firstEvent
	for time.Since(start) < 3*time.Minute {
		var res protocol.ChatOpenResponse
		s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
		s.x.mu.Lock()
		eventCount := len(s.x.events)
		s.x.mu.Unlock()
		written := false
		for _, e := range s.entries()[at:] {
			if e["type"] == "assistant" && text(e) != "" {
				written = true
			}
		}
		if !written {
			beforeWrite = eventCount
		}
		if res.Partial != "" && written {
			stale++
			maxStale = max(maxStale, stale)
		} else {
			stale = 0
		}
		sc := screen()
		joined := strings.Join(sc.Lines, "\n")
		if joined != last {
			frames = append(frames, frame{time.Since(start), sc.Lines})
			last = joined
		}
		if _, working := statusLine(sc.Lines); !working && s.lastIs("assistant") && time.Since(start) > 3*time.Second {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	reply := s.reply(at, 2*time.Minute)
	if reply == "" {
		t.Fatal("没有拿到回复")
	}

	// Part of the reply on a screen that still shows the status line means
	// Claude Code draws the reply as it streams.
	probe := []rune(squeeze(reply))
	var statuses []string
	partial := 0
	seen := map[string]bool{}
	for _, f := range frames {
		line, working := statusLine(f.lines)
		if !working {
			continue
		}
		if !seen[line] {
			seen[line] = true
			statuses = append(statuses, line)
		}
		flat := squeeze(strings.Join(f.lines, ""))
		for i := 0; i+12 <= len(probe); i += 12 {
			if strings.Contains(flat, string(probe[i:i+12])) {
				partial++
				break
			}
		}
	}
	if len(statuses) == 0 {
		t.Errorf("干活期间没读到状态行")
	}
	t.Logf("共 %d 帧不同的画面，带状态行的 %d 种，前几种：%q", len(frames), len(statuses), statuses[:min(len(statuses), 8)])
	t.Logf("状态行还在时屏幕上已有回复文字的帧：%d", partial)

	// The open chat passed the status line, and the reply being drawn, on
	// to the phone.
	var activities, partials []string
	previous, growth := 0, 0
	s.x.mu.Lock()
	for i, e := range s.x.events[firstEvent:] {
		var ev protocol.ChatStateEvent
		if e.Method != protocol.EventChatState || json.Unmarshal(e.Body, &ev) != nil || ev.SessionID != s.id {
			continue
		}
		if ev.Activity != "" {
			activities = append(activities, ev.Activity)
		}
		n := utf8.RuneCountInString(ev.Partial)
		if n != 0 {
			partials = append(partials, fmt.Sprintf("%d 字", n))
		}
		if firstEvent+i < beforeWrite && ev.State == protocol.StateWorking {
			if n*2 < previous {
				t.Errorf("同一轮的半截回复从 %d 字回落到 %d 字，不足上次的一半", previous, n)
			}
			if previous > 0 && n > previous {
				growth++
			}
			previous = n
		}
	}
	s.x.mu.Unlock()
	if growth < 2 {
		t.Errorf("回复写进记录前，chat.state 里的半截回复只增长 %d 次，至少要增长两次", growth)
	}
	if len(activities) == 0 {
		t.Errorf("干活期间没收到带 activity 的 chat.state")
	}
	t.Logf("chat.state 的 activity %d 次，前几次：%q", len(activities), activities[:min(len(activities), 6)])
	t.Logf("chat.state 带半截回复 %d 次：%v", len(partials), partials)
	// One sample may fall between the transcript write and the next poll.
	if maxStale > 1 {
		t.Errorf("回复写进记录后，连续 %d 次取样仍带半截回复", maxStale)
	}

	var sb strings.Builder
	for _, f := range frames {
		sb.WriteString("=== " + f.at.Round(time.Millisecond).String() + "\n")
		sb.WriteString(strings.Join(f.lines, "\n") + "\n")
	}
	out := filepath.Join(s.x.root, ".local", "e2e-ws", "working-screens.txt")
	if err := os.WriteFile(out, []byte(sb.String()), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Logf("画面存在 %s", out)

	waitFor(t, 30*time.Second, "长回复后的会话空闲", func() bool {
		return s.state() == protocol.StateIdle
	})
	const followup = "只回复 OK"
	m = s.mark()
	if _, err := s.x.call(t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: followup}); err != nil {
		t.Fatalf("长回复后发送短消息失败：%v", err)
	}
	s.waitUser(followup, m, 15*time.Second)
}
