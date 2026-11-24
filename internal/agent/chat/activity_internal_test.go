package chat

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func screenSample(t *testing.T, name string) []string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return strings.Split(strings.TrimSuffix(string(b), "\n"), "\n")
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
	got := screenPartial(screenSample(t, "working-partial.txt"))
	want := "终端模拟器像一台很窄的显示器：它从伪终端收到一串字节，再把这些字节翻译成屏幕上排列整齐的字符。程序写出的不只是文字，还" +
		"有换行、回车、退格，以及以 ESC 开头的控制指令。模拟器先按 UTF-8" +
		"把字节拼成字符；遇到普通文字，就放进当前光标所在的格子；遇到控制指令，就移动光标、清行、换颜色，或者切到另一块缓冲区。"
	if got != want {
		t.Errorf("partial =\n%q\nwant\n%q", got, want)
	}

	full := screenPartial(screenSample(t, "working-tokens.txt"))
	if !strings.HasPrefix(full, want) || !strings.HasSuffix(full, "变成像素。\n\n喵呜~") {
		t.Errorf("whole reply on screen =\n%q", full)
	}
	// Before any reply the last block above the status line is the prompt.
	if got := screenPartial(screenSample(t, "working-thinking.txt")); got != "" {
		t.Errorf("thinking: partial = %q, want none", got)
	}

	for _, c := range []struct {
		name, screen, want string
	}{
		{"tool call", "❯ 看看\n\n⏺ Bash(ls)\n  ⎿  a.txt\n\n✻ Whirring… (3s)\n\n────\n❯ ", ""},
		{"folded reads", "❯ 看看\n\n⏺ Read 2 files (ctrl+o to expand)\n\n✻ Whirring… (3s)\n\n────\n❯ ", ""},
		{"words joined with a space", "❯ hi\n\n⏺ The screen wraps long\n  lines at word breaks.\n\n  Second paragraph.\n\n✻ Whirring… (3s)\n\n────\n❯ ", "The screen wraps long lines at word breaks.\n\nSecond paragraph."},
		{"list keeps its lines", "❯ hi\n\n⏺ 三件事：\n  - 第一\n  - 第二\n\n✻ Whirring… (3s)\n\n────\n❯ ", "三件事：\n- 第一\n- 第二"},
		// Mid-draw, with the status line missing, a custom status line
		// below the reply is not part of it.
		{"custom status line", "❯ hi\n\n⏺ 第一段。\n\n  喵呜~\n\n ☀ 27.6k │ 5h 17%\n                    ○ low · /effort\n────\n❯ \n────", "第一段。\n\n喵呜~"},
		{"text after a tool", "❯ hi\n\n⏺ Bash(ls)\n  ⎿  a.txt\n\n⏺ 目录里只有\n  a.txt。\n\n✻ Whirring… (3s)\n\n────\n❯ ", "目录里只有a.txt。"},
	} {
		if got := screenPartial(strings.Split(c.screen, "\n")); got != c.want {
			t.Errorf("%s: partial = %q, want %q", c.name, got, c.want)
		}
	}
}
