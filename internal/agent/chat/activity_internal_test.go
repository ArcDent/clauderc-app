package chat

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"

	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

func screenSample(t *testing.T, name string) []string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return strings.Split(strings.TrimSuffix(string(b), "\n"), "\n")
}

func TestLongScreenReply(t *testing.T) {
	raw, err := os.ReadFile("../attach/testdata/long-reply.raw")
	if err != nil {
		t.Fatal(err)
	}
	text, err := os.ReadFile("../attach/testdata/long-reply.txt")
	if err != nil {
		t.Fatal(err)
	}
	terminal := attach.NewScreen(attach.Rows, attach.Cols)
	s := session{target: discovery.Target{Running: true, Status: "busy"}}
	markers := regexp.MustCompile(`\x1b\[\?(?:2026[hl]|25h|1049l)`)
	last, longest, marked := 0, "", ""
	var longestAt int
	var synced bool
	for _, m := range markers.FindAllIndex(raw, -1) {
		marker := string(raw[m[0]:m[1]])
		if marker == "\x1b[?1049l" {
			break
		}
		terminal.Write(raw[last:m[1]])
		last = m[1]
		switch marker {
		case "\x1b[?2026h":
			synced = true
		case "\x1b[?2026l":
			synced = false
		}
		if synced {
			continue
		}
		lines := terminal.View().Lines
		if partial, _, _ := screenPartial(lines, "", false); len(partial) > len(marked) {
			marked = partial
		}
		s.updateScreenReply(lines)
		if len(s.partial) > len(longest) {
			longest, longestAt = s.partial, last
		}
	}
	t.Logf("byte %d: longest reply %d runes (%d without stitching), transcript %d", longestAt, utf8.RuneCountInString(longest), utf8.RuneCountInString(marked), utf8.RuneCountInString(strings.TrimSuffix(string(text), "\n")))
	if len(longest) <= len(marked) || withoutSpace(longest) != withoutSpace(string(text)) {
		t.Errorf("stitched reply did not reach the end of the transcript: %q", longest)
	}
}

func TestScreenActivity(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ sample, want string }{
		{"working-start.txt", "Whirring…"},
		{"working-thinking.txt", "Whirring… (4s · thinking with low effort)"},
		{"working-tokens.txt", "Whirring… (20s · ↓ 2.4k tokens)"},
		// A frame caught mid-draw has no status line.
		{"working-partial.txt", ""},
	} {
		if got := screenActivity(screenSample(t, c.sample)); got != c.want {
			t.Errorf("%s: activity = %q, want %q", c.sample, got, c.want)
		}
	}
	for _, c := range []struct{ line, want string }{
		// Older versions put the key hint in the status line.
		{"✻ Incubating… (1m 17s · ↓ 7.5k tokens · esc to interrupt)", "Incubating… (1m 17s · ↓ 7.5k tokens)"},
		{"✶ Incubating… (esc to interrupt)", "Incubating…"},
		// A finished turn and other lines are not a status line.
		{"✻ Sautéed for 12s · done 4:27 PM", ""},
		{"  ⏸ manual mode on · esc to interrupt", ""},
		{"· 一个列表项里的省略号…不算", ""},
	} {
		if got := screenActivity([]string{"❯ hi", "", c.line, "", "────", "❯ "}); got != c.want {
			t.Errorf("%q: activity = %q, want %q", c.line, got, c.want)
		}
	}
}

func TestScreenPartial(t *testing.T) {
	t.Parallel()
	got, _, _ := screenPartial(screenSample(t, "working-partial.txt"), "", false)
	want := "终端模拟器像一台很窄的显示器：它从伪终端收到一串字节，再把这些字节翻译成屏幕上排列整齐的字符。程序写出的不只是文字，还" +
		"有换行、回车、退格，以及以 ESC 开头的控制指令。模拟器先按 UTF-8" +
		"把字节拼成字符；遇到普通文字，就放进当前光标所在的格子；遇到控制指令，就移动光标、清行、换颜色，或者切到另一块缓冲区。"
	if got != want {
		t.Errorf("partial =\n%q\nwant\n%q", got, want)
	}

	full, _, _ := screenPartial(screenSample(t, "working-tokens.txt"), "", false)
	if !strings.HasPrefix(full, want) || !strings.HasSuffix(full, "变成像素。\n\n喵呜~") {
		t.Errorf("whole reply on screen =\n%q", full)
	}
	// Before any reply the last block above the status line is the prompt.
	if got, _, _ := screenPartial(screenSample(t, "working-thinking.txt"), "", false); got != "" {
		t.Errorf("thinking: partial = %q, want none", got)
	}

	for _, c := range []struct {
		name, screen, want string
	}{
		{"tool call", "❯ 看看\n\n⏺ Bash(ls)\n  ⎿  a.txt\n\n✻ Whirring… (3s)\n\n────\n❯ ", ""},
		{"folded reads", "❯ 看看\n\n⏺ Read 2 files (ctrl+o to expand)\n\n✻ Whirring… (3s)\n\n────\n❯ ", ""},
		{"words joined with a space", "❯ hi\n\n⏺ The screen wraps long\n  lines at word breaks.\n\n  Second paragraph.\n\n✻ Whirring… (3s)\n\n────\n❯ ", "The screen wraps long lines at word breaks.\n\nSecond paragraph."},
		{"list keeps its lines", "❯ hi\n\n⏺ 三件事：\n  - 第一\n  - 第二\n\n✻ Whirring… (3s)\n\n────\n❯ ", "三件事：\n- 第一\n- 第二"},
		{"screen bullets become Markdown", "⏺ Steps:\n  • first\n    • nested\n  • last\n\n────\n❯ ", "Steps:\n- first\n  - nested\n- last"},
		{"indented code", "⏺ Example:\n      x := 1\n  \ty := 2\n  Then use x and y.\n\n────\n❯ ", "Example:\n    x := 1\n\ty := 2\nThen use x and y."},
		{"tables", "⏺ Table:\n  ┌──┐\n  │ A│\n  ├──┤\n  └──┘\n  | B |\n  |---|\n  | 1 |\n  End.\n\n────\n❯ ", "Table:\n┌──┐\n│ A│\n├──┤\n└──┘\n| B |\n|---|\n| 1 |\nEnd."},
		{"fenced code", "⏺ Example:\n  ```text\n  left\n\n\n  • right\n    indented\n  ```\n  End.\n\n────\n❯ ", "Example:\n```text\nleft\n\n\n• right\n  indented\n```\nEnd."},
		// Mid-draw, with the status line missing, a custom status line
		// below the reply is not part of it.
		{"custom status line", "❯ hi\n\n⏺ 第一段。\n\n  喵呜~\n\n ☀ 27.6k │ 5h 17%\n                    ○ low · /effort\n────\n❯ \n────", "第一段。\n\n喵呜~"},
		{"text after a tool", "❯ hi\n\n⏺ Bash(ls)\n  ⎿  a.txt\n\n⏺ 目录里只有\n  a.txt。\n\n✻ Whirring… (3s)\n\n────\n❯ ", "目录里只有a.txt。"},
	} {
		if got, _, _ := screenPartial(strings.Split(c.screen, "\n"), "", false); got != c.want {
			t.Errorf("%s: partial = %q, want %q", c.name, got, c.want)
		}
	}
}

func TestPartialStaysUntilNextPrompt(t *testing.T) {
	t.Parallel()
	s := session{target: discovery.Target{Running: true, Status: "busy"}}
	for _, c := range []struct{ name, screen, wantPartial, wantActivity string }{
		{"reply", "❯ First\n\n⏺ Start of the answer\n\n✻ Working…\n────\n❯ ", "Start of the answer", "Working…"},
		{"tool", "❯ First\n\n⏺ Start of the answer\n\n⏺ Read(notes)\n  ⎿  done\n────\n❯ ", "Start of the answer", "Working…"},
		{"no visible block", "────\n❯ ", "Start of the answer", "Working…"},
		{"updated reply", "❯ First\n\n⏺ More of the answer\n────\n❯ ", "More of the answer", "Working…"},
		{"new user input", "❯ First\n\n⏺ More of the answer\n\n❯ Next\n────\n❯ ", "", "Working…"},
	} {
		s.updateScreenReply(strings.Split(c.screen, "\n"))
		status, _ := s.compute()
		if status.Partial != c.wantPartial || status.Activity != c.wantActivity {
			t.Errorf("%s: partial = %q, activity = %q; want %q, %q", c.name, status.Partial, status.Activity, c.wantPartial, c.wantActivity)
		}
	}
}

func TestScreenReplyOverlap(t *testing.T) {
	const overlap = "abcdefghijklmnopqrstuvwxyz012345"
	for _, c := range []struct{ name, previous, screen, want string }{
		{"reflow and formatting", "Intro. " + overlap + " wrapped", "  abcdefghijklmnop\n  qrstuvwxyz012345 wrapped text.\n\n  Next:\n  • first\n  │ B │", "Intro. " + overlap + " wrapped text.\n\nNext:\n- first\n│ B │"},
		{"repeated anchor", "Intro. " + overlap + " " + overlap, "  " + overlap + " " + overlap + " end", "Intro. " + overlap + " " + overlap + " end"},
		{"inside code", "Example:\n```text\n" + overlap + "\n\n", "  " + overlap + "\n\n\n  • literal\n  ```", "Example:\n```text\n" + overlap + "\n\n\n• literal\n```"},
		{"anchor is too short", "Intro. Short end.", "  Short end. More text.", "Intro. Short end."},
		{"anchor outside window", overlap + strings.Repeat("x", replyOverlapWindow), "  " + overlap + strings.Repeat("x", replyOverlapWindow) + "more", overlap + strings.Repeat("x", replyOverlapWindow)},
		{"not the same suffix", "Intro. " + overlap + " tail", "  " + overlap + " different text", "Intro. " + overlap + " tail"},
		{"cannot start with a continuation", "", "  " + overlap + " more", ""},
		{"new user input", "Intro. " + overlap, "  " + overlap + " more\n❯ Next", ""},
	} {
		t.Run(c.name, func(t *testing.T) {
			s := session{target: discovery.Target{Running: true, Status: "busy"}, partial: c.previous, partialFenced: c.name == "inside code"}
			s.updateScreenReply(strings.Split(c.screen+"\n────\n❯ ", "\n"))
			if s.partial != c.want {
				t.Errorf("partial = %q, want %q", s.partial, c.want)
			}
		})
	}
}

func TestRepeatedScreenReplyDoesNotGrow(t *testing.T) {
	const anchor = "abcdefghijklmnopqrstuvwxyz"
	previous := "Start. " + anchor + " " + anchor
	lines := []string{"  " + anchor, "  " + anchor, "────", "❯"}
	s := session{target: discovery.Target{Running: true, Status: "busy"}, partial: previous}
	for i := range 5 {
		s.updateScreenReply(lines)
		if s.partial != previous {
			t.Fatalf("poll %d: length %d, want %d", i, len(s.partial), len(previous))
		}
	}
	s.updateScreenReply([]string{lines[0], lines[1], "✻ Working…", lines[2], lines[3]})
	if s.partial != previous {
		t.Fatalf("status-only change grew the preview: %q", s.partial)
	}
}

func TestScreenReplyMatchesFullScreenWindow(t *testing.T) {
	const anchor = "abcdefghijklmnopqrstuvwxyz 0123456789"
	text := anchor + strings.Repeat("a", 3500)
	lines := []string{"  " + text + " more", "────", "❯"}
	got, _, _ := screenPartial(lines, "开头"+text, false)
	if got != "开头"+text+" more" {
		t.Fatalf("preview length %d, want %d", len(got), len("开头"+text+" more"))
	}
}

func TestScreenReplyWaitsForNewUser(t *testing.T) {
	for _, partial := range []string{"", "Old reply"} {
		s := session{target: discovery.Target{Running: true, Status: "busy"}, partial: partial}
		s.applyReplyItems([]transcript.Item{{ChatItem: protocol.ChatItem{Kind: protocol.ItemUser, Text: "New question"}}})
		s.updateScreenReply([]string{"❯ Old question", "⏺ Old reply grew", "────", "❯"})
		if s.partial != "" {
			t.Fatalf("old reply accepted with initial preview %q: %q", partial, s.partial)
		}
		s.updateScreenReply([]string{"❯ Old question", "⏺ Old reply grew", "❯ New", "  question", "⏺ New reply", "────", "❯"})
		if s.partial != "New reply" {
			t.Fatalf("new reply not accepted with initial preview %q: %q", partial, s.partial)
		}
	}
}

func TestScreenReplyWaitsForRepeatedUser(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.jsonl")
	const user = `{"type":"user","message":{"role":"user","content":"Same question"}}` + "\n"
	if err := os.WriteFile(path, []byte(user), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := transcript.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	s := session{target: discovery.Target{Running: true, Status: "busy"}, follower: f}
	s.updateScreenReply([]string{"❯ Same question", "⏺ Old reply", "────", "❯"})
	appendLine(t, path, user)
	items, err := f.Poll()
	if err != nil {
		t.Fatal(err)
	}
	s.applyReplyItems(items)
	s.updateScreenReply([]string{"❯ Same question", "⏺ Old reply grows", "────", "❯"})
	if s.partial != "" {
		t.Fatalf("old reply returned: %q", s.partial)
	}
	s.updateScreenReply([]string{"❯ Same question", "⏺ Old reply grows", "❯ Same question", "⏺ New reply", "────", "❯"})
	if s.partial != "New reply" {
		t.Fatalf("new reply not accepted: %q", s.partial)
	}
}

func TestLandedScreenReplyDoesNotReturn(t *testing.T) {
	const first = "Original answer with enough letters"
	s := session{target: discovery.Target{Running: true, Status: "busy"}}
	s.updateScreenReply([]string{"⏺ " + first, "────", "❯"})
	s.applyReplyItems([]transcript.Item{{ChatItem: protocol.ChatItem{Kind: protocol.ItemAssistant, Text: "**" + first + "** finished"}}})
	if s.partial != "" {
		t.Fatalf("recorded reply retained: %q", s.partial)
	}
	for i := range 22 {
		s.applyReplyItems([]transcript.Item{{ChatItem: protocol.ChatItem{Kind: protocol.ItemTool}}})
		s.updateScreenReply([]string{"⏺ Read(notes)", "  ⎿ done", "────", "❯"})
		if status, _ := s.compute(); status.Partial != "" {
			t.Fatalf("tool %d revived old reply: %q", i, status.Partial)
		}
	}
	s.updateScreenReply([]string{"⏺ Different answer", "────", "❯"})
	s.updateScreenReply([]string{"⏺ " + first + " grows", "────", "❯"})
	if s.partial != "Different answer" {
		t.Fatalf("old block replaced the later answer: %q", s.partial)
	}
	s.applyReplyItems([]transcript.Item{{ChatItem: protocol.ChatItem{Kind: protocol.ItemAssistant, Text: "Different answer, now recorded"}}})
	s.updateScreenReply([]string{"⏺ " + first + " appears again", "────", "❯"})
	if s.partial != "" {
		t.Fatalf("old block returned after the later answer landed: %q", s.partial)
	}
}

func TestReplyFingerprintStopsAtPrefix(t *testing.T) {
	const prefix = "一二三四五六七八九十12345678901234"
	if got := replyFingerprint("** " + prefix + "!!!" + strings.Repeat("more", 4000)); got != prefix {
		t.Errorf("fingerprint = %q, want %q", got, prefix)
	}
}
