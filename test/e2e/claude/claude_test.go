//go:build e2e

// Package claude runs the agent against the real Claude Code (task 10.6)
// through the protocol methods the phone uses, replacing the scenarios of
// scripts/e2e-claude/run.py. It starts real background sessions, so it is
// left out of go test ./... and run by hand after upgrading Claude Code:
//
//	CLAUDERC_E2E_CLAUDE=~/.local/bin/claude-launch go test -tags e2e -v -timeout 40m ./test/e2e/claude/...
//
// CLAUDERC_E2E_CLAUDE is the claude command to use, "claude" by default.
// Every scenario creates its own session through session.create, in a
// fresh directory under .local/e2e-ws/, launched with
// --permission-mode manual --effort low, and stops and removes it at the
// end. Sessions that existed before are never touched, and
// ~/.claude/settings.json must stay unmodified.
package claude

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/lifecycle"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// profileID is a launch profile that exists only in the test's template
// cache.
const profileID = "e2e.clauderc.invalid"

// safeProfileID is profileID plus --safe-mode, which leaves out every
// skill, plugin and custom command: its slash popup holds built-ins only.
const safeProfileID = "e2e-safe.clauderc.invalid"

const client = "c-e2e"

// longTask keeps a session busy for a while without tools.
const longTask = "请写一篇约 2000 字的中文文章，主题是%s，直接输出正文，不要使用任何工具"

type harness struct {
	t     *testing.T
	src   discovery.Source
	life  *lifecycle.Handler
	chats *chat.Handler
	root  string // repository root

	mu     sync.Mutex
	events []protocol.Envelope
	seq    int
}

var (
	setupOnce sync.Once
	shared    *harness
	baseline  map[string]bool
	settings  string
	before    time.Time
	created   []string
	createdMu sync.Mutex
)

func TestMain(m *testing.M) {
	code := m.Run()
	if shared != nil {
		shared.chats.Close()
		// The cache picked up the machine's own launch templates.
		_ = os.RemoveAll(filepath.Dir(shared.src.ProfilesFile))
		if left := shared.leftovers(); len(left) > 0 {
			fmt.Fprintln(os.Stderr, "未能清理的测试会话：", strings.Join(left, " "))
			code = 1
		}
		if info, err := os.Stat(settings); err == nil && !info.ModTime().Equal(before) {
			fmt.Fprintln(os.Stderr, "~/.claude/settings.json 被改动了")
			code = 1
		}
	}
	os.Exit(code)
}

// h returns the harness shared by all scenarios, built on first use.
func h(t *testing.T) *harness {
	t.Helper()
	setupOnce.Do(func() { shared = newHarness(t) })
	if shared == nil {
		t.Fatal("harness setup failed")
	}
	return shared
}

func newHarness(t *testing.T) *harness {
	claude := os.Getenv("CLAUDERC_E2E_CLAUDE")
	if claude == "" {
		claude = "claude"
	}
	home, err := os.UserHomeDir()
	if err != nil {
		t.Fatal(err)
	}
	_, self, _, _ := runtime.Caller(0)
	cache, err := os.MkdirTemp("", "clauderc-e2e-")
	if err != nil {
		t.Fatal(err)
	}
	profiles := filepath.Join(cache, "profiles.json")
	tmpl := map[string]any{"profiles": map[string]any{
		profileID: map[string]any{
			"profile":     protocol.Profile{ID: profileID, Kind: protocol.ProfileEndpoint},
			"flags":       []string{"--permission-mode", "manual", "--effort", "low"},
			"providerEnv": map[string]string{},
			"updatedAt":   "2000-01-01T00:00:00Z",
		},
		safeProfileID: map[string]any{
			"profile":     protocol.Profile{ID: safeProfileID, Kind: protocol.ProfileEndpoint},
			"flags":       []string{"--permission-mode", "manual", "--effort", "low", "--safe-mode"},
			"providerEnv": map[string]string{},
			"updatedAt":   "2000-01-01T00:00:00Z",
		},
	}}
	data, _ := json.Marshal(tmpl)
	if err := os.WriteFile(profiles, data, 0o600); err != nil {
		t.Fatal(err)
	}
	x := &harness{
		t:    t,
		src:  discovery.Source{ClaudePath: claude, ClaudeDir: filepath.Join(home, ".claude"), ProfilesFile: profiles},
		root: filepath.Join(filepath.Dir(self), "..", "..", ".."),
	}
	settings = filepath.Join(home, ".claude", "settings.json")
	if info, err := os.Stat(settings); err == nil {
		before = info.ModTime()
	}
	targets, err := x.src.Targets(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	baseline = map[string]bool{}
	for _, tg := range targets {
		baseline[tg.ID] = true
	}
	x.life = lifecycle.New(lifecycle.Options{Source: x.src})
	x.chats = chat.New(chat.Options{
		Source: x.src,
		Push:   agent.PusherFunc(x.push),
		Waker:  x.life,
		Attach: attach.Options{Linger: 2 * time.Second},
	})
	return x
}

func (x *harness) push(e protocol.Envelope) {
	x.mu.Lock()
	defer x.mu.Unlock()
	x.events = append(x.events, e)
}

// call sends one request to the handler serving its method.
func (x *harness) call(t *testing.T, method protocol.Method, body any) (protocol.Envelope, *protocol.Error) {
	t.Helper()
	raw, _ := json.Marshal(body)
	x.mu.Lock()
	x.seq++
	id := fmt.Sprintf("r-%d", x.seq)
	x.mu.Unlock()
	req := protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: id, Method: method, Client: client, Body: raw}
	var res protocol.Envelope
	if strings.HasPrefix(string(method), "session.") {
		res = x.life.Handle(context.Background(), req)
	} else {
		res = x.chats.Handle(context.Background(), req)
	}
	return res, res.Error
}

// must is call failing the test on an error response.
func (x *harness) must(t *testing.T, method protocol.Method, body, out any) {
	t.Helper()
	res, e := x.call(t, method, body)
	if e != nil {
		t.Fatalf("%s: %s %s", method, e.Code, e.Message)
	}
	if out != nil {
		if err := json.Unmarshal(res.Body, out); err != nil {
			t.Fatalf("%s: %v", method, err)
		}
	}
}

func (x *harness) target(id string) (discovery.Target, bool) {
	targets, err := x.src.Targets(context.Background())
	if err != nil {
		return discovery.Target{}, false
	}
	for _, tg := range targets {
		if tg.ID == id {
			return tg, true
		}
	}
	return discovery.Target{}, false
}

// leftovers are the sessions this test created, by id, or that run in
// one of its workspaces, such as a copy a wrapper's extra flag started.
func (x *harness) leftovers() []string {
	createdMu.Lock()
	mine := map[string]bool{}
	for _, id := range created {
		mine[id] = true
	}
	createdMu.Unlock()
	targets, err := x.src.Targets(context.Background())
	if err != nil {
		return []string{"（读不到会话列表：" + err.Error() + "）"}
	}
	ws := filepath.Join(x.root, ".local", "e2e-ws") + string(filepath.Separator)
	var left []string
	for _, tg := range targets {
		if mine[tg.ID] || !baseline[tg.ID] && strings.HasPrefix(tg.Cwd, ws) {
			left = append(left, tg.ID)
		}
	}
	return left
}

// session is one scenario's background session.
type session struct {
	x                 *harness
	t                 *testing.T
	id, sessionID, ws string
	path              string // transcript
}

func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("等待超时：%s", what)
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// launch creates a session in a new workspace, waits for the reply to its
// first message and opens its chat; cleanup stops and removes it.
func launch(t *testing.T, name string) *session {
	t.Helper()
	return launchIn(t, name, profileID)
}

// launchIn is launch in the given test profile.
func launchIn(t *testing.T, name, profile string) *session {
	t.Helper()
	x := h(t)
	tag := make([]byte, 3)
	_, _ = rand.Read(tag)
	ws := filepath.Join(x.root, ".local", "e2e-ws", name+"-"+hex.EncodeToString(tag))
	if err := os.MkdirAll(ws, 0o755); err != nil {
		t.Fatal(err)
	}
	var res protocol.SessionCreateResponse
	x.must(t, protocol.MethodSessionCreate, protocol.SessionCreateRequest{
		ProfileID: profile, Cwd: ws, Name: "e2e-" + name + "-" + hex.EncodeToString(tag), Prompt: "只回复 OK",
	}, &res)
	if baseline[res.SessionID] {
		t.Fatalf("新建返回的 %s 是测试前已有的会话", res.SessionID)
	}
	createdMu.Lock()
	created = append(created, res.SessionID)
	createdMu.Unlock()
	s := &session{x: x, t: t, id: res.SessionID, ws: ws}
	t.Cleanup(s.cleanup)

	waitFor(t, 60*time.Second, "会话出现在列表里", func() bool {
		tg, ok := x.target(s.id)
		s.sessionID = tg.SessionID
		return ok && tg.SessionID != ""
	})
	waitFor(t, 60*time.Second, "会话记录文件", func() bool {
		p, err := transcript.Find(x.src.ClaudeDir, s.sessionID)
		s.path = p
		return err == nil
	})
	s.waitIdle(120 * time.Second)
	if first := s.firstUserText(); first != "只回复 OK" {
		t.Fatalf("第一句话到达时是 %q，want 只回复 OK", first)
	}
	x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, nil)
	return s
}

// cleanup stops and removes the session and any other session in its
// workspace, then deletes the workspace.
func (s *session) cleanup() {
	x, t := s.x, s.t
	x.call(t, protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: s.id})
	ids := []string{s.id}
	if targets, err := x.src.Targets(context.Background()); err == nil {
		for _, tg := range targets {
			if tg.Cwd == s.ws && tg.ID != s.id && !baseline[tg.ID] {
				t.Logf("工作目录里还有会话 %s，一并清理", tg.ID)
				ids = append(ids, tg.ID)
			}
		}
	}
	for _, id := range ids {
		if tg, ok := x.target(id); ok && tg.Running {
			x.call(t, protocol.MethodSessionStop, protocol.SessionStopRequest{SessionID: id})
		}
		deadline := time.Now().Add(30 * time.Second)
		for time.Now().Before(deadline) {
			tg, ok := x.target(id)
			if !ok {
				break
			}
			if !tg.Running {
				x.call(t, protocol.MethodSessionRemove, protocol.SessionRemoveRequest{SessionID: id})
			}
			time.Sleep(time.Second)
		}
	}
	_ = os.RemoveAll(s.ws)
}

// entries reads the transcript, skipping a line still being written.
func (s *session) entries() []map[string]any {
	data, err := os.ReadFile(s.path)
	if err != nil {
		return nil
	}
	var es []map[string]any
	for _, line := range bytes.Split(data, []byte("\n")) {
		var e map[string]any
		if json.Unmarshal(line, &e) == nil {
			es = append(es, e)
		}
	}
	return es
}

func (s *session) mark() int { return len(s.entries()) }

// state is the session's state as the phone sees it.
func (s *session) state() protocol.SessionState {
	tg, _ := s.x.target(s.id)
	return tg.SessionState(s.unanswered() != "")
}

// waitIdle waits for an assistant reply and a session that is neither
// working nor waiting on a tool call.
func (s *session) waitIdle(timeout time.Duration) {
	s.t.Helper()
	waitFor(s.t, timeout, "回合结束", func() bool {
		st := s.state()
		return (st == protocol.StateIdle || st == protocol.StatePending && s.unanswered() == "") && s.lastIs("assistant")
	})
}

func (s *session) lastIs(typ string) bool {
	es := s.entries()
	for i := len(es) - 1; i >= 0; i-- {
		switch es[i]["type"] {
		case "assistant", "user":
			return es[i]["type"] == typ && !isToolResult(es[i])
		}
	}
	return false
}

var pasted = regexp.MustCompile(`(?s)^<pasted_content[^>]*>\n(.*)\n</pasted_content[^>]*>$`)

// blocks returns the content blocks of type typ in an entry's message.
func blocks(e map[string]any, typ string) []map[string]any {
	msg, _ := e["message"].(map[string]any)
	list, _ := msg["content"].([]any)
	var out []map[string]any
	for _, b := range list {
		if m, ok := b.(map[string]any); ok && m["type"] == typ {
			out = append(out, m)
		}
	}
	return out
}

func isToolResult(e map[string]any) bool { return len(blocks(e, "tool_result")) > 0 }

// text is an entry's message text, without a pasted_content wrapper.
func text(e map[string]any) string {
	msg, _ := e["message"].(map[string]any)
	var s string
	if str, ok := msg["content"].(string); ok {
		s = str
	} else {
		var parts []string
		for _, b := range blocks(e, "text") {
			t, _ := b["text"].(string)
			parts = append(parts, t)
		}
		s = strings.Join(parts, "\n")
	}
	// Claude Code puts blank lines around the wrapper.
	s = strings.TrimSpace(s)
	if m := pasted.FindStringSubmatch(s); m != nil {
		return m[1]
	}
	return s
}

func (s *session) firstUserText() string {
	for _, e := range s.entries() {
		if e["type"] == "user" && e["isMeta"] != true && !isToolResult(e) {
			return text(e)
		}
	}
	return ""
}

// waitUser returns the index of the user entry with exactly msg, at or
// after since.
func (s *session) waitUser(msg string, since int, timeout time.Duration) int {
	s.t.Helper()
	at := -1
	waitFor(s.t, timeout, "user 条目："+msg, func() bool {
		es := s.entries()
		for i := since; i < len(es); i++ {
			if es[i]["type"] == "user" && text(es[i]) == msg {
				at = i
				return true
			}
		}
		return false
	})
	return at
}

// reply waits for the turn after entry i to end and returns its text.
func (s *session) reply(i int, timeout time.Duration) string {
	s.t.Helper()
	s.waitIdle(timeout)
	var parts []string
	for _, e := range s.entries()[i:] {
		if e["type"] == "assistant" {
			parts = append(parts, text(e))
		}
	}
	return strings.Join(parts, "\n")
}

// unanswered is the id of the latest tool_use without a result, or "".
func (s *session) unanswered() string {
	es := s.entries()
	answered := map[string]bool{}
	for _, e := range es {
		for _, b := range blocks(e, "tool_result") {
			id, _ := b["tool_use_id"].(string)
			answered[id] = true
		}
	}
	for i := len(es) - 1; i >= 0; i-- {
		if us := blocks(es[i], "tool_use"); len(us) > 0 {
			id, _ := us[len(us)-1]["id"].(string)
			if !answered[id] {
				return id
			}
			return ""
		}
	}
	return ""
}

// pending waits for the agent to report a pending prompt of kind.
func (s *session) pending(kind protocol.PendingKind, timeout time.Duration) protocol.Pending {
	s.t.Helper()
	var p protocol.Pending
	waitFor(s.t, timeout, "待确认："+string(kind), func() bool {
		s.x.mu.Lock()
		defer s.x.mu.Unlock()
		for i := len(s.x.events) - 1; i >= 0; i-- {
			e := s.x.events[i]
			if e.Method != protocol.EventChatPending {
				continue
			}
			var ev protocol.ChatPendingEvent
			if json.Unmarshal(e.Body, &ev) != nil || ev.SessionID != s.id {
				continue
			}
			if ev.Pending == nil || ev.Pending.Kind != kind {
				return false
			}
			p = *ev.Pending
			return true
		}
		return false
	})
	return p
}

// result waits for the tool_result of toolUseID and returns it with the
// entry's toolUseResult.
func (s *session) result(toolUseID string) (block map[string]any, toolUseResult any) {
	s.t.Helper()
	waitFor(s.t, 60*time.Second, "tool_result "+toolUseID, func() bool {
		for _, e := range s.entries() {
			for _, b := range blocks(e, "tool_result") {
				if b["tool_use_id"] == toolUseID {
					block, toolUseResult = b, e["toolUseResult"]
					return true
				}
			}
		}
		return false
	})
	return block, toolUseResult
}

func resultText(b map[string]any) string {
	if s, ok := b["content"].(string); ok {
		return s
	}
	var parts []string
	if list, ok := b["content"].([]any); ok {
		for _, c := range list {
			if m, ok := c.(map[string]any); ok {
				t, _ := m["text"].(string)
				parts = append(parts, t)
			}
		}
	}
	return strings.Join(parts, "\n")
}

func answers(toolUseResult any) map[string]any {
	m, _ := toolUseResult.(map[string]any)
	a, _ := m["answers"].(map[string]any)
	return a
}

func (s *session) send(msg string) {
	s.t.Helper()
	s.x.must(s.t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: msg}, nil)
}

func TestSend(t *testing.T) {
	s := launch(t, "send")
	m := s.mark()
	msg := "1+1 等于几？只回答阿拉伯数字"
	s.send(msg)
	if r := s.reply(s.waitUser(msg, m, 15*time.Second), 120*time.Second); !strings.Contains(r, "2") {
		t.Errorf("回复 %q 不含 2", r)
	}
}

func TestMultiline(t *testing.T) {
	s := launch(t, "multiline")
	m := s.mark()
	msg := "第一行：苹果\n第二行：香蕉\n第三行：樱桃\n上面一共列了几种水果？只回答阿拉伯数字"
	s.send(msg)
	u := s.waitUser(msg, m, 15*time.Second)
	users := 0
	for _, e := range s.entries()[m:] {
		if e["type"] == "user" && e["isMeta"] != true && !isToolResult(e) {
			users++
		}
	}
	if r := s.reply(u, 120*time.Second); users != 1 || !strings.Contains(r, "3") {
		t.Errorf("新增 user 条目 %d 条，回复 %q；want 1 条且含 3", users, r)
	}
}

func permission(t *testing.T, name string, d protocol.Decision) (map[string]any, any, bool) {
	s := launch(t, name)
	marker := "marker_" + name + ".txt"
	s.send("请只用 Bash 工具运行这条命令：touch " + marker + "  然后用一句话告诉我结果")
	p := s.pending(protocol.PendingPermission, 120*time.Second)
	if p.ToolName != "Bash" || !strings.Contains(string(p.Input), marker) {
		t.Fatalf("待确认 = %s %s", p.ToolName, p.Input)
	}
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID, Decision: d}, nil)
	block, tur := s.result(p.ToolUseID)
	// After a denial the turn ends on "[Request interrupted …]" without a
	// reply, so only the state is waited for.
	waitFor(t, 120*time.Second, "回合结束", func() bool { return s.state() == protocol.StateIdle })
	_, err := os.Stat(filepath.Join(s.ws, marker))
	return block, tur, err == nil
}

func TestAllow(t *testing.T) {
	block, _, exists := permission(t, "allow", protocol.DecisionAllow)
	if block["is_error"] == true || !exists {
		t.Errorf("is_error=%v，标记文件存在=%v；want 成功且文件存在", block["is_error"], exists)
	}
}

func TestDeny(t *testing.T) {
	block, tur, exists := permission(t, "deny", protocol.DecisionDeny)
	if block["is_error"] != true || !strings.Contains(resultText(block), "The user doesn't want to proceed with this tool use") || exists {
		t.Errorf("is_error=%v，结果=%q，toolUseResult=%v，标记文件存在=%v", block["is_error"], resultText(block), tur, exists)
	}
}

// ask sends prompt and returns the question the session asks.
func ask(t *testing.T, s *session, prompt string) protocol.Pending {
	t.Helper()
	s.send(prompt)
	return s.pending(protocol.PendingQuestion, 120*time.Second)
}

func index(t *testing.T, q protocol.Question, label string) int {
	t.Helper()
	for i, o := range q.Options {
		if o.Label == label {
			return i
		}
	}
	t.Fatalf("问题 %q 没有选项 %q", q.Question, label)
	return -1
}

func TestChoice(t *testing.T) {
	s := launch(t, "choice")
	p := ask(t, s, "请调用 AskUserQuestion 工具问我一个单选题：你喜欢哪种颜色？选项依次只有：红、绿、蓝。拿到答案后只复述我的选择")
	q := p.Questions[0]
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID,
		Answers: []protocol.Answer{{Selected: []int{index(t, q, "绿")}}}}, nil)
	_, tur := s.result(p.ToolUseID)
	if got := answers(tur); len(got) != 1 || got[q.Question] != "绿" {
		t.Errorf("answers = %v", got)
	}
}

func TestMultiSelect(t *testing.T) {
	s := launch(t, "multi_select")
	p := ask(t, s, "请调用 AskUserQuestion 工具问我一个多选题（multiSelect 设为 true）：你喜欢哪些水果？选项依次只有：苹果、香蕉、樱桃、葡萄。拿到答案后只复述我的选择")
	q := p.Questions[0]
	if !q.MultiSelect {
		t.Fatalf("问题不是多选：%+v", q)
	}
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID,
		Answers: []protocol.Answer{{Selected: []int{index(t, q, "香蕉"), index(t, q, "葡萄")}}}}, nil)
	_, tur := s.result(p.ToolUseID)
	if got := answers(tur); got[q.Question] != "香蕉, 葡萄" {
		t.Errorf("answers = %v", got)
	}

	// The phone's 「其他」 is ticked along with options: both go out in one answer.
	p = ask(t, s, "请再调用一次 AskUserQuestion 工具问我一个多选题（multiSelect 设为 true）：你喜欢哪些蔬菜？选项依次只有：白菜、萝卜、茄子。拿到答案后只复述我的选择")
	q = p.Questions[0]
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID,
		Answers: []protocol.Answer{{Selected: []int{index(t, q, "白菜"), index(t, q, "茄子")}, Custom: "南瓜"}}}, nil)
	_, tur = s.result(p.ToolUseID)
	got, _ := answers(tur)[q.Question].(string)
	for _, want := range []string{"白菜", "茄子", "南瓜"} {
		if !strings.Contains(got, want) {
			t.Errorf("answer %q lacks %q", got, want)
		}
	}
	if strings.Contains(got, "萝卜") {
		t.Errorf("answer %q has an option not picked", got)
	}
}

func TestMultiQuestion(t *testing.T) {
	s := launch(t, "multi_question")
	p := ask(t, s, "请调用一次 AskUserQuestion 工具，questions 数组里放两个单选题：第一题「你喜欢哪种颜色？」选项依次只有 红、绿、蓝；第二题「你喜欢哪种动物？」选项依次只有 猫、狗。拿到答案后只复述我的选择")
	if len(p.Questions) != 2 {
		t.Fatalf("题数 = %d", len(p.Questions))
	}
	want := []string{"绿", "狗"}
	var as []protocol.Answer
	for i, q := range p.Questions {
		as = append(as, protocol.Answer{Selected: []int{index(t, q, want[i])}})
	}
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID, Answers: as}, nil)
	_, tur := s.result(p.ToolUseID)
	got := answers(tur)
	for i, q := range p.Questions {
		if got[q.Question] != want[i] {
			t.Errorf("answers = %v", got)
		}
	}
}

func TestBusySend(t *testing.T) {
	s := launch(t, "busy_send")
	m := s.mark()
	first := fmt.Sprintf(longTask, "城市里的树")
	s.send(first)
	u1 := s.waitUser(first, m, 15*time.Second)
	waitFor(t, 30*time.Second, "工作中", func() bool { return s.state() == protocol.StateWorking })
	time.Sleep(2 * time.Second)
	second := "第二条消息：只回复 SECOND"
	s.send(second) // chat.send answers once the enqueue record shows up
	u2 := s.waitUser(second, u1+1, 240*time.Second)
	r := s.reply(u2, 120*time.Second)
	essay, interrupted := false, false
	for _, e := range s.entries()[u1:u2] {
		essay = essay || e["type"] == "assistant" && len([]rune(text(e))) > 300
		interrupted = interrupted || e["type"] == "user" && strings.Contains(text(e), "[Request interrupted")
	}
	if !essay || interrupted || !strings.Contains(r, "SECOND") {
		t.Errorf("长文写完=%v，被打断=%v，第二条回复=%q", essay, interrupted, r)
	}
}

func jpegOf(c color.Color) []byte {
	img := image.NewRGBA(image.Rect(0, 0, 64, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, c)
		}
	}
	var b bytes.Buffer
	_ = jpeg.Encode(&b, img, nil)
	return b.Bytes()
}

var imageSource = regexp.MustCompile(`^\[Image: source: (.+)\]$`)

func TestImage(t *testing.T) {
	s := launch(t, "image")
	m := s.mark()
	s.x.must(t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: "不要使用任何工具，直接用一句话回答：这两张图依次是什么颜色？",
		Images: []protocol.Image{{MediaType: "image/jpeg", Data: jpegOf(color.RGBA{255, 0, 0, 255})}, {MediaType: "image/jpeg", Data: jpegOf(color.RGBA{0, 0, 255, 255})}}}, nil)
	var types, sources []string
	waitFor(t, 20*time.Second, "带图的 user 条目和 isMeta 来源条目", func() bool {
		types, sources = nil, nil
		for _, e := range s.entries()[m:] {
			if e["type"] != "user" {
				continue
			}
			for _, b := range blocks(e, "image") {
				src, _ := b["source"].(map[string]any)
				mt, _ := src["media_type"].(string)
				types = append(types, mt)
			}
			if e["isMeta"] == true {
				for _, b := range blocks(e, "text") {
					t, _ := b["text"].(string)
					if mm := imageSource.FindStringSubmatch(t); mm != nil {
						sources = append(sources, mm[1])
					}
				}
			}
		}
		return len(types) >= 2 && len(sources) >= 2
	})
	if !slices.Equal(types, []string{"image/jpeg", "image/jpeg"}) || len(sources) != 2 {
		t.Errorf("image 块 %v，来源 %v", types, sources)
	}
	// Let the turn end; a tool the model wants for reading pixels is refused.
	deadline := time.Now().Add(120 * time.Second)
	for time.Now().Before(deadline) {
		if id := s.unanswered(); id != "" && s.state() == protocol.StatePending {
			s.x.call(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: id, Decision: protocol.DecisionDeny})
		} else if s.state() == protocol.StateIdle && s.lastIs("assistant") {
			break
		}
		time.Sleep(time.Second)
	}
}

func TestPermissionMode(t *testing.T) {
	s := launch(t, "mode_cycle")
	if _, e := s.x.call(t, protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: s.id, Mode: protocol.PermissionBypass}); e == nil || e.Code != protocol.CodeInvalidParams {
		t.Errorf("切到 bypassPermissions 返回 %+v，want invalidParams", e)
	}
	s.x.must(t, protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: s.id, Mode: protocol.PermissionAcceptEdits}, nil)
	m := s.mark()
	s.send("请用 Write 工具在当前目录创建 edit_ok.txt，内容为 hello，然后只回复 DONE")
	blocked := false
	waitFor(t, 120*time.Second, "写文件的回合结束", func() bool {
		st := s.state()
		blocked = blocked || st == protocol.StatePending && s.unanswered() != ""
		return blocked || st == protocol.StateIdle && s.lastIs("assistant") && s.mark() > m
	})
	_, err := os.Stat(filepath.Join(s.ws, "edit_ok.txt"))
	s.x.must(t, protocol.MethodSessionStop, protocol.SessionStopRequest{SessionID: s.id}, nil)
	waitFor(t, 30*time.Second, "会话停止", func() bool { return s.state() == protocol.StateStopped })
	var modes []string
	for _, e := range s.entries() {
		if e["type"] == "permission-mode" {
			mode, _ := e["permissionMode"].(string)
			modes = append(modes, mode)
		}
	}
	if blocked || err != nil || len(modes) == 0 || modes[len(modes)-1] != "acceptEdits" {
		t.Errorf("出现待确认=%v，文件写入=%v，记录里的权限模式=%v", blocked, err == nil, modes)
	}
}

func TestInterrupt(t *testing.T) {
	s := launch(t, "interrupt")
	m := s.mark()
	task := fmt.Sprintf(longTask, "山")
	s.send(task)
	u := s.waitUser(task, m, 15*time.Second)
	waitFor(t, 30*time.Second, "工作中", func() bool { return s.state() == protocol.StateWorking })
	time.Sleep(9 * time.Second)
	start := time.Now()
	s.x.must(t, protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: s.id}, nil)
	waitFor(t, 15*time.Second, "中断后空闲", func() bool { return s.state() == protocol.StateIdle })
	took := time.Since(start)
	// claude agents may report idle before the marker reaches the file.
	marked := false
	waitFor(t, 15*time.Second, "中断标记", func() bool {
		for _, e := range s.entries()[u:] {
			marked = marked || e["type"] == "user" && text(e) == "[Request interrupted by user]"
		}
		return marked
	})
	m2 := s.mark()
	s.send("只回复 AFTER")
	r := s.reply(s.waitUser("只回复 AFTER", m2, 15*time.Second), 120*time.Second)
	if !marked || took > 10*time.Second || !strings.Contains(r, "AFTER") {
		t.Errorf("中断标记=%v，用时 %v，后续回复 %q", marked, took, r)
	}
}

func modelIDs(es []map[string]any) []string {
	var ids []string
	for _, e := range es {
		a, _ := e["attachment"].(map[string]any)
		if e["type"] == "attachment" && a["type"] == "model" {
			id, _ := a["identity"].(map[string]any)
			s, _ := id["modelId"].(string)
			ids = append(ids, s)
		}
	}
	return ids
}

func TestModel(t *testing.T) {
	s := launch(t, "model")
	old := modelIDs(s.entries())
	var models protocol.ChatModelsResponse
	s.x.must(t, protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: s.id}, &models)
	var pick *protocol.ModelOption
	current := 0
	for i := range models.Models {
		mo := &models.Models[i]
		if mo.Current {
			current++
			continue
		}
		if pick == nil || strings.Contains(mo.Label, "Opus") && !strings.Contains(pick.Label, "Opus") {
			pick = mo
		}
	}
	if current != 1 || pick == nil || len(old) == 0 {
		t.Fatalf("模型列表 %+v，记录里的模型 %v", models.Models, old)
	}
	m := s.mark()
	s.x.must(t, protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: s.id, Model: pick.ID}, nil)
	waitFor(t, 15*time.Second, "「for this session only」输出", func() bool {
		for _, e := range s.entries()[m:] {
			if e["type"] == "user" && strings.Contains(text(e), "for this session only") {
				return true
			}
		}
		return false
	})
	m2 := s.mark()
	s.send("只回复 OK2")
	u := s.waitUser("只回复 OK2", m2, 15*time.Second)
	var now []string
	waitFor(t, 30*time.Second, "下一条消息之后的模型", func() bool {
		now = modelIDs(s.entries()[u:])
		return len(now) > 0
	})
	if now[len(now)-1] == old[len(old)-1] {
		t.Errorf("模型仍是 %s，选的是 %+v", now[len(now)-1], pick)
	}
}

// A stopped session is woken by chat.send through `claude attach` and
// keeps its ids, also when the claude command is a wrapper that adds a
// flag such as a default --effort: with `claude --bg --resume` that made
// Claude Code start a copy.
func TestWake(t *testing.T) {
	s := launch(t, "wake")
	s.x.must(t, protocol.MethodSessionStop, protocol.SessionStopRequest{SessionID: s.id}, nil)
	waitFor(t, 30*time.Second, "会话停止", func() bool { return s.state() == protocol.StateStopped })
	m := s.mark()
	if _, e := s.x.call(t, protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: s.id, Text: "只回复 WOKE"}); e != nil {
		t.Fatalf("chat.send: %s %s", e.Code, e.Message)
	}
	if st := s.state(); st == protocol.StateStopped {
		t.Fatalf("chat.send 之后会话仍是 %s", st)
	}
	// Only the wake is judged: whether the model answers depends on the
	// route the claude command gives the resumed session.
	r := s.reply(s.waitUser("只回复 WOKE", m, 15*time.Second), 120*time.Second)
	t.Logf("唤醒后的回复：%q", r)
	// The attach that woke it has ended by now.
	if st := s.state(); st == protocol.StateStopped {
		t.Errorf("回复之后会话又停了")
	}
	targets, _ := s.x.src.Targets(context.Background())
	copies := 0
	for _, tg := range targets {
		if tg.SessionID == s.sessionID {
			copies++
		} else if tg.Cwd == s.ws {
			t.Errorf("工作目录里多出了会话 %s", tg.ID)
		}
	}
	if copies != 1 {
		t.Errorf("同一 sessionId 的会话 %d 个，want 1", copies)
	}
}
