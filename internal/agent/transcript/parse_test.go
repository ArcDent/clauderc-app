package transcript_test

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// samplePNG is the 1x1 PNG put into the image blocks of the sanitized sample.
const samplePNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

func parseFile(t *testing.T, path string) *transcript.Transcript {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	tr, err := transcript.Parse(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	return tr
}

// want describes one expected item of testdata/session.jsonl.
type want struct {
	kind    protocol.ItemKind
	text    string // user/assistant text; for tools the summary
	tool    string // tool name
	result  string // tool result prefix, or command output
	isError bool
}

func TestParseSample(t *testing.T) {
	tr := parseFile(t, "testdata/session.jsonl")
	wants := []want{
		{kind: protocol.ItemUser, text: "只回复 OK"},
		{kind: protocol.ItemAssistant, text: "已加载全局规则\n\nOK\n\n喵呜~"},
		{kind: protocol.ItemUser, text: "第一行：苹果\n第二行：香蕉\n第三行：樱桃\n上面一共列了几种水果？只回答阿拉伯数字"},
		{kind: protocol.ItemAssistant, text: "3\n\n喵呜~"},
		{kind: protocol.ItemUser, text: "请只用 Bash 工具运行这条命令：touch marker_allow.txt  然后用一句话告诉我结果"},
		{kind: protocol.ItemTool, text: "运行 touch marker_allow.txt", tool: "Bash", result: "(Bash completed with no output)"},
		{kind: protocol.ItemAssistant, text: "已在当前目录创建空文件 marker_allow.txt，没有报错。\n\n喵呜~"},
		{kind: protocol.ItemUser, text: "请只用 Bash 工具运行这条命令：touch marker_deny.txt  然后用一句话告诉我结果"},
		{kind: protocol.ItemTool, text: "运行 touch marker_deny.txt", tool: "Bash", result: "The user doesn't want to proceed", isError: true},
		{kind: protocol.ItemInterrupted},
		{kind: protocol.ItemUser, text: "[Image #1] [Image #2] 不要使用任何工具，直接用一句话回答：这两张图依次是什么颜色？"},
		{kind: protocol.ItemAssistant, text: "第一张是红色，第二张是蓝色。\n\n喵呜~"},
		{kind: transcript.ItemCommand, text: "/model", result: "Set model to `Opus 5.5` for this session only"},
		{kind: transcript.ItemCommand, text: "/model", result: "Kept model as `deepseek-v4.1-flash[1m]`"},
		{kind: protocol.ItemUser, text: "请写一篇约 2000 字的中文文章，主题是城市里的树，直接输出正文，不要使用任何工具"},
		{kind: protocol.ItemUser, text: "第二条消息：只回复 SECOND"},
		{kind: protocol.ItemAssistant, text: "# 城市里的树\n\n"},
		{kind: protocol.ItemAssistant, text: "SECOND\n\n喵呜~"},
		{kind: protocol.ItemTool, text: "读取 README.md", tool: "Read", result: "1\t# proj\n2\thello"},
		{kind: protocol.ItemTool, text: "mcp__gsearch__search", tool: "mcp__gsearch__search", result: "no results"},
	}
	items := tr.Items()
	if len(items) != len(wants) {
		for i, it := range items {
			t.Logf("%d: %s %q", i, it.Kind, it.Text)
		}
		t.Fatalf("got %d items, want %d", len(items), len(wants))
	}
	for i, w := range wants {
		it := items[i]
		if it.Seq != int64(i+1) {
			t.Errorf("item %d: seq %d, want %d", i, it.Seq, i+1)
		}
		if it.Kind != w.kind {
			t.Errorf("item %d: kind %q, want %q", i, it.Kind, w.kind)
			continue
		}
		if it.Queued {
			t.Errorf("item %d: still queued", i)
		}
		switch w.kind {
		case protocol.ItemUser:
			if it.Text != w.text {
				t.Errorf("item %d: text %q, want %q", i, it.Text, w.text)
			}
		case protocol.ItemAssistant:
			if !strings.HasPrefix(it.Text, w.text) {
				t.Errorf("item %d: text %q, want prefix %q", i, it.Text, w.text)
			}
		case protocol.ItemTool:
			tc := it.Tool
			if tc == nil {
				t.Errorf("item %d: no tool", i)
				continue
			}
			if tc.Summary != w.text || tc.ToolName != w.tool {
				t.Errorf("item %d: tool %q summary %q, want %q %q", i, tc.ToolName, tc.Summary, w.tool, w.text)
			}
			if tc.Result == nil || !strings.HasPrefix(tc.Result.Content, w.result) || tc.Result.IsError != w.isError {
				t.Errorf("item %d: result %+v, want prefix %q isError %v", i, tc.Result, w.result, w.isError)
			}
		case protocol.ItemInterrupted:
			if it.Text != "" {
				t.Errorf("item %d: interrupted item has text %q", i, it.Text)
			}
		case transcript.ItemCommand:
			c := it.Command
			if c == nil || c.Name != w.text || c.Output != w.result {
				t.Errorf("item %d: command %+v, want %q output %q", i, c, w.text, w.result)
			}
		}
	}

	// The tool rows carry the raw input of their tool_use.
	var in struct{ Command string }
	if err := json.Unmarshal(items[5].Tool.Input, &in); err != nil || in.Command != "touch marker_allow.txt" {
		t.Errorf("Bash input = %s", items[5].Tool.Input)
	}
	if id := items[5].Tool.ToolUseID; id != "call_00_ET_GEktflzLfLDHuagfOR2S4305" {
		t.Errorf("Bash tool_use id = %q", id)
	}

	// Pictures of the image message.
	img := items[10].Images
	if len(img) != 2 {
		t.Fatalf("image message has %d images, want 2", len(img))
	}
	png, _ := base64.StdEncoding.DecodeString(samplePNG)
	for i, im := range img {
		if im.MediaType != "image/jpeg" || !bytes.Equal(im.Data, png) {
			t.Errorf("image %d = %s %d bytes", i, im.MediaType, len(im.Data))
		}
	}
	for i, it := range items {
		if strings.Contains(it.Text, "[Image: source:") || strings.Contains(it.Text, "local-command") || strings.Contains(it.Text, "pasted_content") {
			t.Errorf("item %d leaks a wrapper: %q", i, it.Text)
		}
	}
}

// Until the formal user entry arrives, an enqueued message is a queued user
// item; the formal entry then replaces it under the same seq.
func TestParseQueuedMessage(t *testing.T) {
	raw, err := os.ReadFile("testdata/session.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.SplitAfter(string(raw), "\n")
	cut := -1
	for i, l := range lines {
		if strings.Contains(l, `"operation": "enqueue"`) {
			cut = i + 1
		}
	}
	if cut < 0 {
		t.Fatal("sample has no enqueue record")
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines[:cut], "")))
	if err != nil {
		t.Fatal(err)
	}
	items := tr.Items()
	last := items[len(items)-1]
	if last.Kind != protocol.ItemUser || !last.Queued || last.Text != "第二条消息：只回复 SECOND" || last.Seq != 16 {
		t.Fatalf("last item before the formal entry = %+v, want queued user seq 16", last)
	}
}

// An interrupt right after a message is the "Interrupted" line, not a
// second user bubble, whether the marker is a plain string or a text block.
func TestParseInterrupt(t *testing.T) {
	src := `{"type":"user","message":{"role":"user","content":"继续用这个 开做吧"}}
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}
{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}
{"type":"user","message":{"role":"user","content":"那边已经改完了"}}
`
	tr, err := transcript.Parse(strings.NewReader(src))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, it := range tr.Items() {
		got = append(got, string(it.Kind)+":"+it.Text)
	}
	want := []string{"user:继续用这个 开做吧", "interrupted:", "interrupted:", "user:那边已经改完了"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("items %q, want %q", got, want)
	}
}

// Messages queued while the session is busy go back to the input box on
// interrupt (popAll) or are dropped one by one (remove); their items turn
// into removed items under the same seq, and a later enqueue starts afresh.
func TestParseQueueRemoved(t *testing.T) {
	lines := []string{
		`{"type":"user","message":{"role":"user","content":"写一篇长文"}}`,
		`{"type":"queue-operation","operation":"enqueue","content":"第二条"}`,
		`{"type":"queue-operation","operation":"enqueue","content":"第三条"}`,
		`{"type":"queue-operation","operation":"popAll"}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}`,
		`{"type":"queue-operation","operation":"enqueue","content":"第四条"}`,
		`{"type":"queue-operation","operation":"enqueue","content":"第五条"}`,
		`{"type":"queue-operation","operation":"remove","content":"第四条"}`,
		`{"type":"user","message":{"role":"user","content":"第五条"}}`,
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, it := range tr.Items() {
		s := fmt.Sprintf("%d %s %s", it.Seq, it.Kind, it.Text)
		if it.Queued {
			s += " queued"
		}
		got = append(got, s)
	}
	want := []string{"1 user 写一篇长文", "2 removed ", "3 removed ", "4 interrupted ", "5 removed ", "6 user 第五条"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("items\n%q\nwant\n%q", got, want)
	}
}

// Messages queued while a tool runs can be taken in by the running turn:
// Claude Code writes a remove with reason absorbed_mid_turn and hands them
// over as queued_command attachments, with no user record. Their items
// stay under the same seq and are no longer queued.
func TestParseQueueAbsorbed(t *testing.T) {
	lines := []string{
		`{"type":"user","message":{"role":"user","content":"1"}}`,
		`{"type":"queue-operation","operation":"enqueue","content":"111"}`,
		`{"type":"queue-operation","operation":"enqueue","content":"1"}`,
		`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`,
		`{"type":"queue-operation","operation":"remove","content":"111","reason":"absorbed_mid_turn"}`,
		`{"type":"queue-operation","operation":"remove","content":"1","reason":"absorbed_mid_turn"}`,
		`{"type":"attachment","attachment":{"type":"queued_command","prompt":"111","commandMode":"prompt"}}`,
		`{"type":"attachment","attachment":{"type":"queued_command","prompt":"1","commandMode":"prompt"}}`,
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, it := range tr.Items() {
		if it.Kind == protocol.ItemTool {
			continue
		}
		s := fmt.Sprintf("%d %s %s", it.Seq, it.Kind, it.Text)
		if it.Queued {
			s += " queued"
		}
		got = append(got, s)
	}
	want := []string{"1 user 1", "2 user 111", "3 user 1"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("items\n%q\nwant\n%q", got, want)
	}
}

// A skill run as a slash command is written with <command-message> first
// and its body as an isMeta record; a local command with <command-name>
// first and its output, colored with terminal escapes, in a later record.
// Both become command items, and the output loses the escapes.
func TestParseSkillAndLocalCommand(t *testing.T) {
	lines := []string{
		`{"type":"user","message":{"role":"user","content":"<command-message>todolist-workflow</command-message>\n<command-name>/todolist-workflow</command-name>\n<command-args>先用这个</command-args>"}}`,
		`{"type":"user","isMeta":true,"message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /x\n\n# todolist-workflow"}]}}`,
		`{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n            <command-message>compact</command-message>\n            <command-args></command-args>"}}`,
		`{"type":"user","message":{"role":"user","content":"<local-command-stdout>\u001b[2mCompacted (ctrl+o to see full summary)\u001b[22m</local-command-stdout>"}}`,
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, it := range tr.Items() {
		s := fmt.Sprintf("%d %s %s", it.Seq, it.Kind, it.Text)
		if c := it.Command; c != nil {
			s += fmt.Sprintf(" name=%s args=%s out=%q", c.Name, c.Args, c.Output)
		}
		got = append(got, s)
	}
	want := []string{
		`1 command  name=/todolist-workflow args=先用这个 out=""`,
		`2 command  name=/compact args= out="Compacted (ctrl+o to see full summary)"`,
	}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("items\n%q\nwant\n%q", got, want)
	}
}
