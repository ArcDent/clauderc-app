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

func parseLines(t *testing.T, lines ...string) *transcript.Transcript {
	t.Helper()
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	return tr
}

func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// notices returns "title|text" for every notice item.
func notices(tr *transcript.Transcript) []string {
	var out []string
	for _, it := range tr.Items() {
		if it.Kind == protocol.ItemNotice {
			out = append(out, it.Title+"|"+it.Text)
		}
	}
	return out
}

func userTexts(tr *transcript.Transcript) []string {
	var out []string
	for _, it := range tr.Items() {
		if it.Kind == protocol.ItemUser {
			out = append(out, it.Text)
		}
	}
	return out
}

// A background task notification is a grey line, never a user bubble,
// whether it arrives as a user record, or by being taken into a running
// turn as a queue remove and a queued_command attachment.
func TestParseTaskNotification(t *testing.T) {
	const tn = `<task-notification>
<task-id>b1</task-id>
<status>completed</status>
<summary>Background command "等 CI 跑完" completed (exit code 0)</summary>
</task-notification>`
	const want = `Background command "等 CI 跑完" completed (exit code 0)`
	content := jsonString(tn)
	formal := fmt.Sprintf(`{"type":"user","origin":{"kind":"task-notification"},"promptSource":"system","message":{"role":"user","content":%s}}`, content)
	enqueue := fmt.Sprintf(`{"type":"queue-operation","operation":"enqueue","content":%s}`, content)
	removeAbsorbed := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","reason":"absorbed_mid_turn","content":%s}`, content)
	removeOther := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","content":%s}`, content)
	attachment := fmt.Sprintf(`{"type":"attachment","attachment":{"type":"queued_command","prompt":%s,"commandMode":"task-notification","origin":{"kind":"task-notification"}}}`, content)

	cases := []struct {
		name  string
		lines []string
		want  int
	}{
		{"user record", []string{formal}, 1},
		{"enqueue only", []string{enqueue}, 0},
		{"enqueue absorbed", []string{enqueue, removeAbsorbed, attachment}, 1},
		{"attachment then remove", []string{attachment, removeAbsorbed}, 1},
		{"remove only", []string{removeAbsorbed}, 1},
		{"attachment only", []string{attachment}, 1},
		{"removed then user record", []string{enqueue, removeOther, formal}, 1},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			tr := parseLines(t, c.lines...)
			got := notices(tr)
			if len(got) != c.want {
				t.Fatalf("notices %q, want %d", got, c.want)
			}
			for _, n := range got {
				if n != "|"+want {
					t.Errorf("notice %q, want %q", n, want)
				}
			}
			if users := userTexts(tr); len(users) != 0 {
				t.Errorf("user items %q, want none", users)
			}
		})
	}

	t.Run("event first line", func(t *testing.T) {
		body := `<task-notification>
<task-id>b2</task-id>
<summary>Monitor event: "html"</summary>
<event>
html settled at 10:00
more detail
</event>
</task-notification>`
		line := fmt.Sprintf(`{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":%s}}`, jsonString(body))
		got := notices(parseLines(t, line))
		want := `Monitor event: "html"：html settled at 10:00`
		if len(got) != 1 || got[0] != "|"+want {
			t.Fatalf("notices %q, want %q", got, want)
		}
	})

	t.Run("no summary", func(t *testing.T) {
		line := `{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":"<task-notification>\n<fork-source>x</fork-source>\n</task-notification>"}}`
		got := notices(parseLines(t, line))
		if len(got) != 1 || got[0] != "|后台任务通知" {
			t.Fatalf("notices %q, want 后台任务通知", got)
		}
	})
}

// A message from another session is a notice titled "<name> 发来", from
// the idle user record or from the queue remove and attachment of a busy
// turn; both sides of the pair together make one notice.
func TestParsePeerMessage(t *testing.T) {
	const name = "clauderc远端升级"
	const body = "脚本已经跑完，结果在 dist/ 下。"
	tag := `<cross-session-message from="uds:/tmp/cc-socks/1.sock" from-name="` + name + `" from-mode="bypass">` + "\n" + body + "\n" + `</cross-session-message>`
	want := name + " 发来|" + body

	idle := fmt.Sprintf(`{"type":"user","isMeta":true,"origin":{"kind":"peer","from":"uds:/tmp/cc-socks/1.sock","name":%s,"body":%s},"message":{"role":"user","content":%s}}`,
		jsonString(name), jsonString(body), jsonString("Another Claude session sent a message:\n"+tag+"\n\nThis came from another Claude session — …"))
	remove := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","reason":"absorbed_mid_turn","content":%s}`, jsonString(tag))
	attachment := fmt.Sprintf(`{"type":"attachment","attachment":{"type":"queued_command","prompt":%s,"commandMode":"prompt","origin":{"kind":"peer","name":%s,"body":%s}}}`,
		jsonString(tag), jsonString(name), jsonString(body))

	cases := []struct {
		name  string
		lines []string
	}{
		{"idle record", []string{idle}},
		{"remove only", []string{remove}},
		{"attachment only", []string{attachment}},
		{"remove then attachment", []string{remove, attachment}},
		{"attachment then remove", []string{attachment, remove}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			tr := parseLines(t, c.lines...)
			got := notices(tr)
			if len(got) != 1 || got[0] != want {
				t.Fatalf("notices %q, want %q", got, want)
			}
			if users := userTexts(tr); len(users) != 0 {
				t.Errorf("user items %q, want none", users)
			}
		})
	}

	t.Run("repeated from one side", func(t *testing.T) {
		got := notices(parseLines(t, remove, remove))
		if len(got) != 2 {
			t.Fatalf("notices %q, want 2", got)
		}
	})

	t.Run("empty origin body", func(t *testing.T) {
		line := fmt.Sprintf(`{"type":"user","isMeta":true,"origin":{"kind":"peer","name":%s},"message":{"role":"user","content":%s}}`,
			jsonString(name), jsonString("Another Claude session sent a message:\n"+tag+"\n\nThis came from another Claude session — …"))
		got := notices(parseLines(t, line))
		if len(got) != 1 || got[0] != want {
			t.Fatalf("notices %q, want %q", got, want)
		}
	})

	t.Run("subagent report skipped", func(t *testing.T) {
		line := fmt.Sprintf(`{"type":"user","isMeta":true,"origin":{"kind":"peer","name":%s,"body":%s,"senderTaskId":"t1"},"message":{"role":"user","content":%s}}`,
			jsonString(name), jsonString(body), jsonString("An agent sent a message:\n"+tag))
		if got := notices(parseLines(t, line)); len(got) != 0 {
			t.Fatalf("notices %q, want none", got)
		}
	})

	t.Run("idle and delivery notices", func(t *testing.T) {
		idleLine := `{"type":"user","isMeta":true,"message":{"role":"user","content":"[Cross-session idle notice] \"clauderc远端升级\", which you asked to be notified about, is idle now — it finished a turn at 10:57. More."}}`
		unnamedIdle := `{"type":"user","isMeta":true,"message":{"role":"user","content":"[Cross-session idle notice] \"uds:/tmp/cc-socks/2.sock\", which you asked to be notified about, is idle now — it finished a turn at 11:02."}}`
		deliveryLine := `{"type":"user","isMeta":true,"message":{"role":"user","content":"[Cross-session delivery notice] 2 of your messages to another session were held by that session (recipients: uds:/tmp/cc-socks/1.sock)."}}`
		otherMeta := `{"type":"user","isMeta":true,"message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /x"}]}}`
		got := notices(parseLines(t, idleLine, unnamedIdle, deliveryLine, otherMeta))
		want := []string{"|「clauderc远端升级」空闲了", "|另一个会话空闲了", "|有 2 条消息被对方暂扣，没送到"}
		if strings.Join(got, "\n") != strings.Join(want, "\n") {
			t.Fatalf("notices %q, want %q", got, want)
		}
	})

	// The desktop client names the sender with a name attribute and
	// leaves origin without a name or body.
	t.Run("desktop wrapper", func(t *testing.T) {
		line := fmt.Sprintf(`{"type":"user","isMeta":true,"origin":{"kind":"peer","from":"local_x","hostInjected":true},"message":{"role":"user","content":%s}}`,
			jsonString(`<cross-session-message from="local_x" name="主页设计">`+"\n第二步\n</cross-session-message>"))
		got := notices(parseLines(t, line))
		if len(got) != 1 || got[0] != "主页设计 发来|第二步" {
			t.Fatalf("notices %q, want 主页设计 发来|第二步", got)
		}
		// Or as a plain user record without origin, after a reminder.
		line = fmt.Sprintf(`{"type":"user","message":{"role":"user","content":%s}}`,
			jsonString("<system-reminder>\nrule\n</system-reminder>\n"+`<cross-session-message from="local_x" name="主页设计">`+"\n第二步\n</cross-session-message>"))
		tr := parseLines(t, line)
		if got := notices(tr); len(got) != 1 || got[0] != "主页设计 发来|第二步" {
			t.Fatalf("notices %q, want 主页设计 发来|第二步", got)
		}
		if users := userTexts(tr); len(users) != 0 {
			t.Errorf("user items %q, want none", users)
		}
	})

	t.Run("no sender name", func(t *testing.T) {
		line := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","reason":"absorbed_mid_turn","content":%s}`,
			jsonString(`<cross-session-message from="uds:/tmp/cc-socks/3.sock">`+"\n"+body+"\n</cross-session-message>"))
		got := notices(parseLines(t, line))
		if len(got) != 1 || got[0] != "另一个会话发来|"+body {
			t.Fatalf("notices %q, want 另一个会话发来|%s", got, body)
		}
	})

	// Two deliveries of the same text, as a burst writes them: both
	// removes first, then both attachments.
	t.Run("same text twice", func(t *testing.T) {
		for _, lines := range [][]string{{remove, remove, attachment, attachment}, {remove, attachment, remove, attachment}} {
			if got := notices(parseLines(t, lines...)); len(got) != 2 {
				t.Fatalf("notices %q, want 2", got)
			}
		}
	})

	// A delivery seen only as an attachment does not swallow a later
	// delivery of the same text.
	t.Run("one side then a later delivery", func(t *testing.T) {
		lines := []string{attachment}
		for range 10 {
			lines = append(lines, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"在做"}]}}`)
		}
		lines = append(lines, remove, attachment)
		if got := notices(parseLines(t, lines...)); len(got) != 2 {
			t.Fatalf("notices %q, want 2", got)
		}
	})

	t.Run("trailing newline on both sides", func(t *testing.T) {
		r := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","reason":"absorbed_mid_turn","content":%s}`, jsonString(tag+"\n"))
		a := fmt.Sprintf(`{"type":"attachment","attachment":{"type":"queued_command","prompt":%s,"commandMode":"prompt","origin":{"kind":"peer","name":%s,"body":%s}}}`,
			jsonString(tag+"\n"), jsonString(name), jsonString(body))
		if got := notices(parseLines(t, r, a)); len(got) != 1 {
			t.Fatalf("notices %q, want 1", got)
		}
	})

	// The user's own message holding a wrapper, sent while busy, stays
	// the user's message: enqueue, remove absorbed_mid_turn, then the
	// queued_command attachment with origin human.
	t.Run("user message with a wrapper", func(t *testing.T) {
		for _, text := range []string{"帮我看下这段：" + tag, tag} {
			enq := fmt.Sprintf(`{"type":"queue-operation","operation":"enqueue","content":%s}`, jsonString(text))
			rem := fmt.Sprintf(`{"type":"queue-operation","operation":"remove","reason":"absorbed_mid_turn","content":%s}`, jsonString(text))
			att := fmt.Sprintf(`{"type":"attachment","attachment":{"type":"queued_command","prompt":%s,"commandMode":"prompt","origin":{"kind":"human"}}}`, jsonString(text))
			tr := parseLines(t, enq, rem, att)
			if got := notices(tr); len(got) != 0 {
				t.Fatalf("notices %q, want none", got)
			}
			items := tr.Items()
			if len(items) != 1 || items[0].Kind != protocol.ItemUser || items[0].Text != text || items[0].Queued {
				t.Fatalf("items %+v, want the delivered user message", items)
			}
		}
	})
}

// A "!" command written as <bash-input> and <bash-stdout> records becomes
// one command item with the output folded into it.
func TestParseBashCommand(t *testing.T) {
	lines := []string{
		`{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"<bash-input>git status</bash-input>"}}`,
		`{"type":"user","message":{"role":"user","content":"<bash-stdout>\u001b[2mOn branch main\u001b[22m</bash-stdout><bash-stderr></bash-stderr>"}}`,
	}
	tr := parseLines(t, lines...)
	items := tr.Items()
	if len(items) != 1 {
		t.Fatalf("got %d items, want 1", len(items))
	}
	it := items[0]
	if it.Kind != transcript.ItemCommand || it.Command == nil {
		t.Fatalf("item %+v, want command", it)
	}
	if it.Command.Name != "!" || it.Command.Args != "git status" || it.Command.Output != "On branch main" {
		t.Fatalf("command %+v, want ! git status with output", it.Command)
	}
	if users := userTexts(tr); len(users) != 0 {
		t.Errorf("user items %q, want none", users)
	}

	t.Run("one record", func(t *testing.T) {
		line := `{"type":"user","message":{"role":"user","content":"<bash-input>ls</bash-input><bash-stdout>a.txt</bash-stdout>"}}`
		tr := parseLines(t, line)
		items := tr.Items()
		if len(items) != 1 || items[0].Command == nil || items[0].Command.Args != "ls" || items[0].Command.Output != "a.txt" {
			t.Fatalf("items %+v, want one command ls with output", items)
		}
	})

	// Typed while busy, the command runs at once and waits in the queue
	// with its output; the user record after the dequeue repeats it.
	t.Run("queued", func(t *testing.T) {
		const content = `"<bash-input>pwd</bash-input><bash-stdout>/tmp</bash-stdout><bash-stderr></bash-stderr>"`
		enqueue := `{"type":"queue-operation","operation":"enqueue","content":` + content + `}`
		tr := parseLines(t, enqueue)
		items := tr.Items()
		if len(items) != 1 || items[0].Kind != transcript.ItemCommand || items[0].Command == nil || items[0].Command.Args != "pwd" || items[0].Command.Output != "/tmp" || !items[0].Queued {
			t.Fatalf("items %+v, want one queued command pwd with output", items)
		}
		tr = parseLines(t, enqueue,
			`{"type":"queue-operation","operation":"dequeue"}`,
			`{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":`+content+`}}`)
		items = tr.Items()
		if len(items) != 1 || items[0].Command == nil || items[0].Command.Args != "pwd" || items[0].Queued {
			t.Fatalf("items %+v, want the one command, no longer queued", items)
		}
	})

	// A command without output stops waiting for it, so a later stray
	// output does not land on it.
	t.Run("no output", func(t *testing.T) {
		tr := parseLines(t,
			`{"type":"user","message":{"role":"user","content":"<bash-input>true</bash-input>"}}`,
			`{"type":"user","message":{"role":"user","content":"<bash-stdout></bash-stdout><bash-stderr></bash-stderr>"}}`,
			`{"type":"user","message":{"role":"user","content":"<local-command-stdout>stale</local-command-stdout>"}}`)
		items := tr.Items()
		if len(items) != 1 || items[0].Command == nil || items[0].Command.Args != "true" || items[0].Command.Output != "" {
			t.Fatalf("items %+v, want ! true without output", items)
		}
	})
}

// After /compact the transcript starts with the summary Claude Code writes
// in place of the conversation; it shows as one grey line.
func TestParseCompactSummary(t *testing.T) {
	line := `{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"This session is being continued from a previous conversation that ran out of context. The summary is long."}}`
	tr := parseLines(t, line)
	got := notices(tr)
	if len(got) != 1 || got[0] != "|上下文已压缩" {
		t.Fatalf("notices %q, want 上下文已压缩", got)
	}
	if users := userTexts(tr); len(users) != 0 {
		t.Errorf("user items %q, want none", users)
	}
}

// <system-reminder> and <artifact-view-context> blocks are desktop-client
// wrappers put before the text: the text after them stays, the wrappers
// do not.
func TestParseSystemReminder(t *testing.T) {
	lines := []string{
		`{"type":"user","isMeta":true,"message":{"role":"user","content":"<system-reminder>rules</system-reminder>"}}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<system-reminder>\nrule\n</system-reminder>"},{"type":"text","text":"正文"}]}}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<system-reminder>only</system-reminder>"}]}}`,
		`{"type":"user","message":{"role":"user","content":"<artifact-view-context>ctx</artifact-view-context>"}}`,
		`{"type":"user","message":{"role":"user","content":"正文里提到 <system-reminder> 也算正文"}}`,
		`{"type":"queue-operation","operation":"enqueue","content":"<system-reminder>\nrules\n</system-reminder>"}`,
		// The task a forked session starts with.
		`{"type":"user","message":{"role":"user","content":"<system-reminder>\nrule\n</system-reminder>\nTask (from the session that started this one): 做这件事"}}`,
		`{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"<artifact-view-context artifact=\"5ac9\">\n{\"context\":{}}\n</artifact-view-context>\n\nD吧"}}`,
		`{"type":"user","message":{"role":"user","content":"<system-reminder>\nrule\n</system-reminder>\n<side-sessions-event>\n{\"event\":\"done\"}\n</side-sessions-event>"}}`,
	}
	tr := parseLines(t, lines...)
	got := userTexts(tr)
	want := []string{"正文", "正文里提到 <system-reminder> 也算正文", "Task (from the session that started this one): 做这件事", "D吧"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("user items %q, want %q", got, want)
	}
}
