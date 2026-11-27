package chat_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/fakeattach"
	"clauderc/internal/protocol"
)

// blockedOn makes the session blocked on a tool_use with the given input.
func (f *fixture) blockedOn(t *testing.T, id, name, input string) {
	t.Helper()
	f.appendTranscript(t, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"`+id+`","name":"`+name+`","input":`+input+`}]}}`+"\n")
	f.setAgents(t, "waiting", "blocked", "7")
}

const (
	multiSelectInput = `{"questions":[{"question":"你喜欢哪些水果？","header":"水果","multiSelect":true,"options":[{"label":"苹果","description":""},{"label":"香蕉","description":""},{"label":"樱桃","description":""},{"label":"葡萄","description":""}]}]}`
	twoQuestionInput = `{"questions":[{"question":"你喜欢哪种颜色？","header":"颜色","multiSelect":false,"options":[{"label":"红","description":""},{"label":"绿","description":""},{"label":"蓝","description":""}]},` +
		`{"question":"你喜欢哪种动物？","header":"动物","multiSelect":false,"options":[{"label":"猫","description":""},{"label":"狗","description":""}]}]}`
)

func TestAnswerRejectsChangedPending(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	// Nothing pending at all.
	res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if res.Error == nil || res.Error.Code != protocol.CodePendingChanged {
		t.Fatalf("idle session: error %+v", res.Error)
	}
	// A different prompt is pending now.
	f.blockedOn(t, "toolu_p2", "Bash", `{"command":"touch b.txt"}`)
	res = f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if res.Error == nil || res.Error.Code != protocol.CodePendingChanged {
		t.Fatalf("other prompt: error %+v", res.Error)
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent: %q", got)
	}
}

func TestAnswerPermission(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		decision protocol.Decision
		keys     string
	}{{protocol.DecisionAllow, "\r"}, {protocol.DecisionDeny, "\x1b"}} {
		f := newFixture(t, setup{screen: promptScreen})
		f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch marker.txt"}`)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: c.decision})
		if got := f.keyLog(); got != c.keys {
			t.Errorf("%s: keys %q, want %q", c.decision, got, c.keys)
		}
	}
}

func TestAnswerQuestions(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name, input string
		answers     []protocol.Answer
		keys        string
	}{
		// RESULTS.md multi_select: 2nd and 4th option.
		{"multi select", multiSelectInput, []protocol.Answer{{Selected: []int{1, 3}}}, "\x1b[B\r\x1b[B\x1b[B\r\x1b[C\r"},
		// RESULTS.md multi_question: 2nd option of each question.
		{"two questions", twoQuestionInput, []protocol.Answer{{Selected: []int{1}}, {Selected: []int{1}}}, "\x1b[B\r\x1b[B\r\r"},
	}
	for _, c := range cases {
		// One screen shows every step, so only the keys are looked at.
		f := newFixture(t, setup{screen: questionScreen("你喜欢哪些水果？", "你喜欢哪种颜色？", "你喜欢哪种动物？", "Review your answers")})
		f.blockedOn(t, "toolu_q1", "AskUserQuestion", c.input)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_q1", Answers: c.answers})
		if got := f.keyLog(); got != c.keys {
			t.Errorf("%s: keys %q, want %q", c.name, got, c.keys)
		}
	}

	f := newFixture(t, setup{})
	f.blockedOn(t, "toolu_q1", "AskUserQuestion", twoQuestionInput)
	for _, bad := range []protocol.ChatAnswerRequest{
		{SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Selected: []int{1}}}},
		{SessionID: shortID, ToolUseID: "toolu_q1", Decision: protocol.DecisionAllow},
	} {
		res := f.call(t, "c1", protocol.MethodChatAnswer, bad)
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
			t.Errorf("answer %+v: error %+v", bad, res.Error)
		}
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent for bad answers: %q", got)
	}
}

// Esc only goes out while the session works: in a dialog or a pending
// prompt it would close or deny something else.
func TestInterrupt(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{status: "busy", state: "working"})
	f.ok(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	if got := f.keyLog(); got != "\x1b" {
		t.Fatalf("working session: keys %q", got)
	}

	f = newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch marker.txt"}`)
	f.ok(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	if got := f.keyLog(); got != "" {
		t.Fatalf("idle and pending sessions: keys %q", got)
	}
}

// With the /model picker open on the computer, an Enter typed from the
// phone would land on the list and save the default model (RESULTS.md),
// so chat.send and chat.answer refuse without typing anything.
func TestEnterRefusedWhilePickerOpen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{replay: "picker"})
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "你好"})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || !strings.Contains(res.Error.Message, "选择界面") {
		t.Errorf("send: error %+v, want invalidParams about the picker", res.Error)
	}
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch marker.txt"}`)
	res = f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || !strings.Contains(res.Error.Message, "选择界面") {
		t.Errorf("answer: error %+v, want invalidParams about the picker", res.Error)
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent with the picker open: %q", got)
	}
}

// On the recorded input screen a message is typed as usual.
func TestEnterAllowedOnPromptScreen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{replay: "mode-default2"})
	// The replay does not write the transcript, so delivery is not seen.
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "你好"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Errorf("send: error %+v, want notDelivered after typing", res.Error)
	}
	if got, want := f.keyLog(), "\x15\x1b[200~你好\x1b[201~\r"; got != want {
		t.Fatalf("keys %q, want %q", got, want)
	}
}

const openPicker = "\x15\x1b[200~/model\x1b[201~\r"

func TestModels(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	var res protocol.ChatModelsResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	// The fake shows the picker recorded from Claude Code 2.1.323, where
	// two entries share a name.
	want := []protocol.ModelOption{
		{ID: "Default (recommended)", Label: "Default (recommended)", Description: "Use the default model (currently deepseek-v4.1-flash[1m]) · Set by ANTHROPIC_DEFAULT_MODEL"},
		{ID: "Opus 5.5", Label: "Opus 5.5", Description: "Custom Opus model (claude-opus-5-5[1M])"},
		{ID: "gpt-6-astra-cc-format[1M]", Label: "gpt-6-astra-cc-format[1M]", Description: "Custom Fable model"},
		{ID: "deepseek-v4.1-flash[1M]#4", Label: "deepseek-v4.1-flash[1M]", Description: "Custom Sonnet model (1M context)"},
		{ID: "glm-5.3-flash[1M]", Label: "glm-5.3-flash[1M]", Description: "Custom Haiku model"},
		{ID: "deepseek-v4.1-flash[1M]#6", Label: "deepseek-v4.1-flash[1M]", Description: "Custom model", Current: true},
	}
	if len(res.Models) != len(want) {
		t.Fatalf("models = %+v", res.Models)
	}
	for i := range want {
		if res.Models[i] != want[i] {
			t.Errorf("model %d = %+v, want %+v", i, res.Models[i], want[i])
		}
	}
	if got := f.keyLog(); got != openPicker+"\x1b" {
		t.Fatalf("keys %q, want the picker opened and left with Esc", got)
	}
}

func TestSetModelUsesSessionOnlyKey(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name    string
		env     []string
		model   string
		keys    string
		errCode protocol.ErrorCode
	}{
		// Cursor starts on entry 6; Opus 5.5 is entry 2.
		{"with confirmation", []string{fakeattach.ConfirmEnv()}, "Opus 5.5", openPicker + "\x1b[A\x1b[A\x1b[A\x1b[A" + "s" + "\r", ""},
		{"without confirmation", nil, "glm-5.3-flash[1M]", openPicker + "\x1b[A" + "s", ""},
		{"unknown model", nil, "sonnet", openPicker + "\x1b", protocol.CodeInvalidParams},
	}
	for _, c := range cases {
		f := newFixture(t, setup{attachEnv: c.env})
		res := f.call(t, "c1", protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID, Model: c.model})
		if c.errCode == "" && res.Error != nil || c.errCode != "" && (res.Error == nil || res.Error.Code != c.errCode) {
			t.Errorf("%s: error %+v, want %q", c.name, res.Error, c.errCode)
		}
		got := f.keyLog()
		if got != c.keys {
			t.Errorf("%s: keys %q, want %q", c.name, got, c.keys)
		}
		if strings.Contains(got, "/model ") || strings.Contains(got, "/model"+c.model) {
			t.Errorf("%s: /model sent with an argument: %q", c.name, got)
		}
		// Between opening the picker and s there is no Enter.
		if picked, _, ok := strings.Cut(strings.TrimPrefix(got, openPicker), "s"); ok && strings.Contains(picked, "\r") {
			t.Errorf("%s: Enter pressed on the list: %q", c.name, got)
		}
	}
}

func TestSetPermissionMode(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name    string
		env     []string
		mode    protocol.PermissionMode
		keys    string
		errCode protocol.ErrorCode
	}{
		{"already there", nil, protocol.PermissionDefault, "\x0c", ""},
		{"two steps", nil, protocol.PermissionPlan, "\x0c\x1b[Z\x0c\x1b[Z\x0c", ""},
		{"never reached", []string{fakeattach.ModesEnv("default")}, protocol.PermissionAuto, "\x0c" + strings.Repeat("\x1b[Z\x0c", 4), protocol.CodeInternal},
		{"bypass, screen never changes", []string{fakeattach.ModesEnv("default")}, protocol.PermissionBypass, "\x0c" + strings.Repeat("\x1b[Z\x0c", 4), protocol.CodeInternal},
		{"bypass not in the cycle", nil, protocol.PermissionBypass, "\x0c" + strings.Repeat("\x1b[Z\x0c", 4), protocol.CodeInvalidParams},
		{"dontAsk", nil, "dontAsk", "", protocol.CodeInvalidParams},
	}
	for _, c := range cases {
		f := newFixture(t, setup{attachEnv: c.env})
		res := f.call(t, "c1", protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: c.mode})
		if c.errCode == "" && res.Error != nil || c.errCode != "" && (res.Error == nil || res.Error.Code != c.errCode) {
			t.Errorf("%s: error %+v, want %q", c.name, res.Error, c.errCode)
		}
		if got := f.keyLog(); got != c.keys {
			t.Errorf("%s: keys %q, want %q", c.name, got, c.keys)
		}
	}
}

// With an attach the status line, not the transcript, gives the mode.
func TestPermissionModeFromScreen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	f.ok(t, "c1", protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: protocol.PermissionAcceptEdits})
	deadline := time.After(3 * time.Second)
	for {
		e := f.nextEvent(t, protocol.EventChatState, 3*time.Second)
		var ev protocol.ChatStateEvent
		if err := json.Unmarshal(e.Body, &ev); err != nil {
			t.Fatal(err)
		}
		if ev.PermissionMode == protocol.PermissionAcceptEdits {
			return
		}
		select {
		case <-deadline:
			t.Fatalf("no chat.state with acceptEdits; last %s", e.Body)
		default:
		}
	}
}

// A chat.state the poll worked out with the old mode does not reach the
// phone after the one chat.setPermissionMode pushes: the phone's last
// chat.state has the new mode.
func TestSwitchedModeIsThePhonesLastState(t *testing.T) {
	t.Parallel()
	var armed atomic.Bool
	held, switched := make(chan struct{}, 1), make(chan struct{})
	var once sync.Once
	f := newFixture(t, setup{opts: func(o *chat.Options) {
		push := o.Push
		o.Push = agent.PusherFunc(func(e protocol.Envelope) {
			var ev protocol.ChatStateEvent
			state := e.Method == protocol.EventChatState && json.Unmarshal(e.Body, &ev) == nil
			// The poll's push of the old mode waits until the switch's
			// went out, or long enough for the switch to get there.
			if state && ev.State == protocol.StateWorking && ev.PermissionMode == protocol.PermissionDefault && armed.CompareAndSwap(true, false) {
				held <- struct{}{}
				select {
				case <-switched:
				case <-time.After(3 * time.Second):
				}
			}
			push.Push(e)
			if state && ev.PermissionMode == protocol.PermissionAcceptEdits {
				once.Do(func() { close(switched) })
			}
		})
	}})
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	armed.Store(true)
	f.setAgents(t, "busy", "working", "7")
	select {
	case <-held:
	case <-time.After(5 * time.Second):
		t.Fatal("the poll pushed no working state")
	}
	f.ok(t, "c1", protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: protocol.PermissionAcceptEdits})
	time.Sleep(500 * time.Millisecond)
	var last protocol.ChatStateEvent
	for len(f.events) > 0 {
		if e := <-f.events; e.Method == protocol.EventChatState {
			if err := json.Unmarshal(e.Body, &last); err != nil {
				t.Fatal(err)
			}
		}
	}
	if last.PermissionMode != protocol.PermissionAcceptEdits {
		t.Fatalf("last chat.state = %+v, want acceptEdits", last.ChatStatus)
	}
}

func TestCommands(t *testing.T) {
	t.Parallel()
	// The session's folder holds a skill kept from the model, a symlinked
	// skill, a command in a subfolder and a skill the user cannot run; the
	// user's own folder (the fake ~/.claude) repeats a listed skill.
	cwd := t.TempDir()
	write := func(path, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(filepath.Join(cwd, ".claude", "skills", "batch-commit", "SKILL.md"), "---\nname: batch-commit\ndisable-model-invocation: true\ndescription: 分批提交\n---\n\n# batch-commit\n")
	elsewhere := filepath.Join(t.TempDir(), "todolist")
	write(filepath.Join(elsewhere, "SKILL.md"), "---\nname: todolist\ndescription: >\n  轻量工作流\n  写清单\n---\n")
	if err := os.Symlink(elsewhere, filepath.Join(cwd, ".claude", "skills", "todolist")); err != nil {
		t.Fatal(err)
	}
	write(filepath.Join(cwd, ".claude", "commands", "opsx", "apply.md"), "---\ndescription: \"实现变更\"\n---\nbody\n")
	write(filepath.Join(cwd, ".claude", "skills", "helper", "SKILL.md"), "---\nname: helper\nuser-invocable: false\ndescription: 只给模型用\n---\n")

	f := newFixture(t, setup{cwd: cwd})
	write(filepath.Join(f.sample, "skills", "matt-tdd", "SKILL.md"), "---\nname: matt-tdd\ndescription: 本地描述\n---\n")
	f.appendTranscript(t, `{"type":"attachment","attachment":{"type":"skill_listing","content":"- matt-tdd: Test-driven development.\n- init: Initialize a new CLAUDE.md file","names":["matt-tdd","init"]}}`+"\n")
	var res protocol.ChatCommandsResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatCommands, protocol.ChatCommandsRequest{SessionID: shortID}), &res); err != nil {
		t.Fatal(err)
	}
	count := map[string]int{}
	desc := map[string]string{}
	for _, c := range res.Commands {
		count[c.Name]++
		desc[c.Name] = c.Description
	}
	for _, name := range []string{"model", "compact", "init", "matt-tdd", "batch-commit", "todolist", "opsx:apply"} {
		if count[name] != 1 {
			t.Errorf("%q listed %d times", name, count[name])
		}
	}
	if count["helper"] != 0 {
		t.Errorf("a skill marked user-invocable: false is listed")
	}
	want := map[string]string{"matt-tdd": "Test-driven development.", "batch-commit": "分批提交", "todolist": "轻量工作流 写清单", "opsx:apply": "实现变更"}
	for name, d := range want {
		if desc[name] != d {
			t.Errorf("%s description %q, want %q", name, desc[name], d)
		}
	}
}

// A session launched with --setting-sources without "user", as the
// account login was before it added the user's skills back through
// --add-dir, cannot run the user's own skills and commands, so they are
// not offered; the project's and the plugins' still are.
func TestCommandsFollowSettingSources(t *testing.T) {
	t.Parallel()
	write := func(path, body string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	linked := filepath.Join(t.TempDir(), "user-skills")
	for _, c := range []struct {
		name               string
		flags              []string
		personal, userCmds bool
	}{
		{"plain", []string{"--model", "opus"}, true, true},
		{"no user settings", []string{"--setting-sources", "project,local", "--settings", "/x.json"}, false, false},
		{"skills added back", []string{"--setting-sources", "project,local", "--settings", "/x.json", "--add-dir", linked}, true, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			cwd := t.TempDir()
			write(filepath.Join(cwd, ".claude", "skills", "batch-commit", "SKILL.md"), "---\nname: batch-commit\ndescription: 分批提交\n---\n")
			f := newFixture(t, setup{cwd: cwd})
			write(filepath.Join(f.sample, "skills", "personal", "SKILL.md"), "---\nname: personal\ndisable-model-invocation: true\ndescription: 个人技能\n---\n")
			write(filepath.Join(f.sample, "commands", "mine.md"), "---\ndescription: 个人命令\n---\nbody\n")
			demo := filepath.Join(f.sample, "plugins", "cache", "mk", "demo", "1.0.0")
			write(filepath.Join(demo, "commands", "run.md"), "---\ndescription: 运行演示\n---\nbody\n")
			write(filepath.Join(f.sample, "settings.json"), `{"enabledPlugins":{"demo@mk":true}}`)
			write(filepath.Join(f.sample, "plugins", "installed_plugins.json"), `{"version":2,"plugins":{"demo@mk":[{"scope":"user","installPath":"`+demo+`"}]}}`)
			if err := os.MkdirAll(filepath.Join(linked, ".claude"), 0o700); err != nil {
				t.Fatal(err)
			}
			_ = os.Remove(filepath.Join(linked, ".claude", "skills"))
			if err := os.Symlink(filepath.Join(f.sample, "skills"), filepath.Join(linked, ".claude", "skills")); err != nil {
				t.Fatal(err)
			}
			job, _ := json.Marshal(map[string]any{"respawnFlags": c.flags, "cwd": cwd})
			write(filepath.Join(f.sample, "jobs", shortID, "state.json"), string(job))

			var res protocol.ChatCommandsResponse
			if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatCommands, protocol.ChatCommandsRequest{SessionID: shortID}), &res); err != nil {
				t.Fatal(err)
			}
			got := map[string]bool{}
			for _, cmd := range res.Commands {
				got[cmd.Name] = true
			}
			want := map[string]bool{"batch-commit": true, "demo:run": true, "personal": c.personal, "mine": c.userCmds}
			for name, listed := range want {
				if got[name] != listed {
					t.Errorf("%s listed = %v, want %v", name, got[name], listed)
				}
			}
		})
	}
}

func TestMethodsListsAllChatMethods(t *testing.T) {
	t.Parallel()
	h := chat.New(chat.Options{})
	defer h.Close()
	want := map[protocol.Method]bool{
		protocol.MethodChatOpen: true, protocol.MethodChatClose: true, protocol.MethodChatSend: true,
		protocol.MethodChatAnswer: true, protocol.MethodChatInterrupt: true, protocol.MethodChatModels: true,
		protocol.MethodChatSetModel: true, protocol.MethodChatSetPermissionMode: true, protocol.MethodChatCommands: true,
		protocol.MethodChatToolOutput: true, protocol.MethodChatImage: true, protocol.MethodChatAutoResume: true, protocol.MethodChatRewind: true,
		protocol.MethodTermOpen: true, protocol.MethodTermKey: true, protocol.MethodTermClose: true,
	}
	got := h.Methods()
	if len(got) != len(want) {
		t.Fatalf("Methods = %v", got)
	}
	for _, m := range got {
		if !want[m] {
			t.Fatalf("Methods = %v", got)
		}
	}
}

// With the /model picker already open on the computer, the Enter after
// /model would land on the list and save the default model (RESULTS.md),
// so chat.models and chat.setModel refuse without typing anything.
func TestModelOpsRefusedWhilePickerOpen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{replay: "picker"})
	want := "电脑上开着模型选择界面，这时按回车会改掉默认模型；请先在电脑上关掉它再试"
	for _, c := range []struct {
		method protocol.Method
		body   any
	}{
		{protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID}},
		{protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID, Model: "Opus 5.5"}},
	} {
		res := f.call(t, "c1", c.method, c.body)
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || res.Error.Message != want {
			t.Errorf("%s: error %+v, want invalidParams %q", c.method, res.Error, want)
		}
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent with the picker open: %q", got)
	}
}

// When the picker does not show up after /model, Esc is pressed in case it
// opens late, so it is never left open with Enter saving the default.
func TestModelsLeavesWithEscWhenPickerNeverShows(t *testing.T) {
	t.Parallel()
	// The recorded prompt screen never reacts to keys.
	f := newFixture(t, setup{replay: "mode-default2"})
	res := f.call(t, "c1", protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID})
	if res.Error == nil || res.Error.Message != "电脑上的模型选择界面没有出现，已取消" {
		t.Errorf("error %+v", res.Error)
	}
	if got := f.keyLog(); got != openPicker+"\x1b" {
		t.Fatalf("keys %q, want the picker opened and the last key Esc", got)
	}
}

// promptScreen is a permission prompt: no input box, the cursor hidden.
const promptScreen = "\x1b[?25l Bash command\n\n   rm -rf build\n\n Do you want to proceed?\n ❯ 1. Yes\n   2. No"

// On a permission prompt the keys of the model and mode operations would
// answer it: /model's Enter allows it, Shift+Tab allows all edits.
func TestModelAndModeOpsRefusedOnAPrompt(t *testing.T) {
	t.Parallel()
	s := running()
	s.screen = promptScreen
	f := newFixture(t, s)
	want := "会话停在面板或对话框上，先在终端里关掉再发"
	for _, c := range []struct {
		method protocol.Method
		body   any
	}{
		{protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID}},
		{protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID, Model: "Opus 5.5"}},
		{protocol.MethodChatSetPermissionMode, protocol.ChatSetPermissionModeRequest{SessionID: shortID, Mode: protocol.PermissionPlan}},
	} {
		res := f.call(t, "c1", c.method, c.body)
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams || res.Error.Message != want {
			t.Errorf("%s: error %+v, want invalidParams %q", c.method, res.Error, want)
		}
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys sent on the prompt: %q", got)
	}
}

// A prompt that comes up while /model is typed gets neither its Enter nor
// the Esc that would deny it.
func TestModelsStopsWhenAPromptComesUp(t *testing.T) {
	t.Parallel()
	prompt := `\033[2J\033[H\033[?25l Bash command\r\n\r\n Do you want to proceed?\r\n ❯ 1. Yes\r\n   2. No`
	s := running()
	s.opts = shellAttach(`printf '────────\r\n❯\302\240'; n=0; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "~" ]; then n=$((n+1)); [ $n -eq 2 ] && printf '` + prompt + `'; fi; done`)
	f := newFixture(t, s)
	res := f.call(t, "c1", protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID})
	if res.Error == nil || res.Error.Message != "会话弹出了待确认，这次操作没有做完，请先处理它" {
		t.Errorf("error %+v", res.Error)
	}
	if got := f.keyLog(); got != strings.TrimSuffix(openPicker, "\r") {
		t.Fatalf("keys %q, want /model typed and nothing after it", got)
	}
}

// A request given up while the picker is open still closes it with Esc:
// left open, an Enter on the computer would save the default model.
func TestModelsClosesThePickerWhenGivenUp(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		// The log as it is now: keyLog waits for it to settle.
		for b, _ := os.ReadFile(f.keys); !strings.Contains(string(b), openPicker); b, _ = os.ReadFile(f.keys) {
			time.Sleep(10 * time.Millisecond)
		}
		time.Sleep(150 * time.Millisecond)
		cancel()
	}()
	f.h.Handle(ctx, req("r-models", "c1", protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID}))
	deadline := time.Now().Add(3 * time.Second)
	for f.keyLog() != openPicker+"\x1b" && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if got := f.keyLog(); got != openPicker+"\x1b" {
		t.Fatalf("keys %q, want the picker opened and closed with Esc", got)
	}
}

// questionScreen is a question prompt answered in more than one step, the
// cursor hidden: its tab bar, then lines.
func questionScreen(lines ...string) string {
	return "\x1b[?25l────────\n ← ☐ 颜色  ☐ 动物  ✔ Submit →\n\n " + strings.Join(lines, "\n ") + "\n\n Enter to select · Esc to cancel"
}

// inputScreen is the input box, the cursor showing.
const inputScreen = "\x1b[?25h────────\n❯ \n────────"

// stepAttach draws screens[0], and at each Enter the next screen while
// there is one; received bytes go to the key log.
func stepAttach(t *testing.T, screens ...string) func(*chat.Options) {
	t.Helper()
	return slowStepAttach(t, 0, screens...)
}

// slowStepAttach is stepAttach drawing each next screen only delay after
// its Enter, as a slow terminal does.
func slowStepAttach(t *testing.T, delay time.Duration, screens ...string) func(*chat.Options) {
	t.Helper()
	dir := t.TempDir()
	for i, s := range screens {
		if err := os.WriteFile(filepath.Join(dir, strconv.Itoa(i)), []byte("\x1b[2J\x1b[H"+strings.ReplaceAll(s, "\n", "\r\n")), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	draw := `cat "$d/$i"`
	if delay > 0 {
		draw = fmt.Sprintf(`sleep %.3f; %s`, delay.Seconds(), draw)
	}
	return shellAttach(`d='` + dir + `'; cr=$(printf '\r'); i=0; cat "$d/0"; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "$cr" ]; then i=$((i+1)); [ -f "$d/$i" ] && { ` + draw + `; }; fi; done`)
}

// A prompt that comes up while chat.interrupt waits for the terminal would
// take its Esc as a denial: the screen is looked at once the terminal is
// held.
func TestInterruptLooksAtTheScreenWithTheTerminal(t *testing.T) {
	t.Parallel()
	prompt := `\033[2J\033[H\033[?25l Bash command\r\n\r\n Do you want to proceed?\r\n ❯ 1. Yes\r\n   2. No`
	s := running()
	// The prompt comes up as the paste of a message ends, on its second ~.
	s.opts = shellAttach(`printf '────────\r\n❯\302\240'; n=0; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "~" ]; then n=$((n+1)); [ $n -eq 2 ] && printf '` + prompt + `'; fi; done`)
	f := newFixture(t, s)
	sent := make(chan protocol.Envelope, 1)
	go func() {
		sent <- f.h.Handle(context.Background(), req("r-send", "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "hi"}))
	}()
	// The message holds the terminal from its Ctrl+U on.
	for b, _ := os.ReadFile(f.keys); !strings.Contains(string(b), "\x15"); b, _ = os.ReadFile(f.keys) {
		time.Sleep(10 * time.Millisecond)
	}
	res := f.call(t, "c1", protocol.MethodChatInterrupt, protocol.ChatInterruptRequest{SessionID: shortID})
	if res.Error == nil || res.Error.Message != "会话弹出了待确认，这次操作没有做完，请先处理它" {
		t.Errorf("interrupt: error %+v, want the prompt reported", res.Error)
	}
	if res := <-sent; res.Error == nil || res.Error.Message != "会话弹出了待确认，消息没有发出" {
		t.Errorf("send: error %+v", res.Error)
	}
	if got := f.keyLog(); got != "\x15\x1b[200~hi\x1b[201~" {
		t.Fatalf("keys %q, want the message typed and no Esc", got)
	}
}

// Allowed on the computer, a tool runs while the transcript still has it
// pending; the prompt is gone from the screen, and a denial from the phone
// would interrupt the tool: no key goes out.
func TestAnswerAfterThePromptLeftTheScreen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{screen: inputScreen})
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"sleep 30"}`)
	res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionDeny})
	if res.Error == nil || res.Error.Code != protocol.CodePendingChanged {
		t.Errorf("error %+v, want pendingChanged", res.Error)
	}
	if got := f.keyLog(); got != "" {
		t.Fatalf("keys %q, want none", got)
	}
}

func TestAnswerUnreliableScreen(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{screen: "\x1b[?1049h\x1b[201;1H"})
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"echo hi"}`)
	res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered || f.keyLog() != "" {
		t.Fatalf("answer: error = %+v, keys = %q", res.Error, f.keyLog())
	}
}

// A computer window smaller than 80×24 may cut a panel short: the answer is
// refused with the reason, and no key goes out.
func TestAnswerScreenTooSmall(t *testing.T) {
	t.Parallel()
	rule := strings.Repeat("─", 100)
	f := newFixture(t, setup{screen: "\x1b[?1049h\x1b[1;1H" + rule + "\x1b[3;1HDo you want to proceed?\x1b[5;1H❯ 1. Yes\x1b[20;1H"})
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"echo hi"}`)
	res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered || !strings.Contains(res.Error.Message, "窗口太小") || f.keyLog() != "" {
		t.Fatalf("answer: error = %+v, keys = %q", res.Error, f.keyLog())
	}
}

// Answers to more than one question go question by question, each looked
// for on the screen first: one closed elsewhere would leave the rest of the
// keys to the input box or to whatever came up.
func TestAnswerLooksForEachQuestion(t *testing.T) {
	t.Parallel()
	q1, q2, review := answerScreens()
	const changed = "待确认的内容已变化，请看最新的请求再回答"
	cases := []struct {
		name    string
		screens []string
		keys    string
		err     string
	}{
		{"each question shows", []string{q1, q2, review}, "\x1b[B\r\x1b[B\r\r", ""},
		// The prompt stays on the first question: Esc leaves it.
		{"second question missing", []string{q1}, "\x1b[B\r\x1b", "回答到一半，电脑上的题目变了，已按 Esc 退出这次提问"},
		// Answered on the computer meanwhile: the input box gets no key.
		{"prompt closed", []string{q1, inputScreen}, "\x1b[B\r", changed},
		// A permission prompt that came up gets no key, not even Esc.
		{"another prompt", []string{q1, promptScreen}, "\x1b[B\r", changed},
		// Moved on to the second question on the computer: nothing typed.
		{"first question gone", []string{q2}, "", changed},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := setup{opts: stepAttach(t, c.screens...)}
			f := newFixture(t, s)
			f.blockedOn(t, "toolu_q1", "AskUserQuestion", twoQuestionInput)
			res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_q1",
				Answers: []protocol.Answer{{Selected: []int{1}}, {Selected: []int{1}}}})
			if c.err == "" && res.Error != nil || c.err != "" && (res.Error == nil || res.Error.Message != c.err) {
				t.Errorf("error %+v, want %q", res.Error, c.err)
			}
			if got := f.keyLog(); got != c.keys {
				t.Errorf("keys %q, want %q", got, c.keys)
			}
		})
	}
}

// answerScreens are the two questions of twoQuestionInput and the review
// page after them.
func answerScreens() (q1, q2, review string) {
	return questionScreen("你喜欢哪种颜色？", "❯ 1. 红", "  2. 绿", "  3. 蓝"),
		questionScreen("你喜欢哪种动物？", "❯ 1. 猫", "  2. 狗"),
		questionScreen("Review your answers", "● 你喜欢哪种颜色？ → 绿", "● 你喜欢哪种动物？ → 狗", "❯ 1. Submit answers", "  2. Cancel")
}

// A slow terminal draws the next question a while after the keys of the
// one before: it is waited for while the prompt stays up, and only a
// prompt that keeps showing the question answered is left with Esc, some
// 3 seconds on. A prompt that closes meanwhile ends the wait at once.
func TestAnswerWaitsForTheNextQuestion(t *testing.T) {
	t.Parallel()
	q1, q2, review := answerScreens()
	cases := []struct {
		name     string
		delay    time.Duration
		screens  []string
		keys     string
		err      string
		min, max time.Duration
	}{
		{"drawn late", 1500 * time.Millisecond, []string{q1, q2, review},
			"\x1b[B\r\x1b[B\r\r", "", 3 * time.Second, 0},
		{"never drawn", 0, []string{q1},
			"\x1b[B\r\x1b", "回答到一半，电脑上的题目变了，已按 Esc 退出这次提问", 3 * time.Second, 0},
		{"unreliable redraw", 0, []string{q1, "\x1b[?1049h\x1b[201;1H"},
			"\x1b[B\r", "暂时无法确认电脑上的画面，操作没有完成", 3 * time.Second, 0},
		// The keys of the first question take a second: waiting out the
		// 3 seconds would take 4.
		{"prompt closed", 0, []string{q1, inputScreen},
			"\x1b[B\r", "待确认的内容已变化，请看最新的请求再回答", 0, 2500 * time.Millisecond},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			f := newFixture(t, setup{opts: slowStepAttach(t, c.delay, c.screens...)})
			f.blockedOn(t, "toolu_q1", "AskUserQuestion", twoQuestionInput)
			start := time.Now()
			res := f.call(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_q1",
				Answers: []protocol.Answer{{Selected: []int{1}}, {Selected: []int{1}}}})
			took := time.Since(start)
			if c.err == "" && res.Error != nil || c.err != "" && (res.Error == nil || res.Error.Message != c.err) {
				t.Errorf("error %+v, want %q", res.Error, c.err)
			}
			if c.name == "unreliable redraw" && (res.Error == nil || res.Error.Code != protocol.CodeNotDelivered) {
				t.Errorf("error %+v, want notDelivered", res.Error)
			}
			if got := f.keyLog(); got != c.keys {
				t.Errorf("keys %q, want %q", got, c.keys)
			}
			if took < c.min || c.max > 0 && took > c.max {
				t.Errorf("took %v, want between %v and %v", took, c.min, c.max)
			}
		})
	}
}

// Claude Code 2.1.331 realities verified against the live CLI:
//   - a fresh attach to an already-blocked prompt leaves the cursor visible
//     (Cursor=true, no \x1b[?25l),
//   - multi-line or wrapped questions get a leading "│ " on every line,
//   - 3-4 long headers wrap the tab bar so Submit sits on the next line, and
//   - a brief clear-screen redraw between questions must not abort WaitFor.
func TestAnswerClaude21331Screens(t *testing.T) {
	t.Parallel()
	t.Run("fresh attach visible cursor on single question", func(t *testing.T) {
		t.Parallel()
		// No \x1b[?25l: cursor stays visible as on a fresh `claude attach`.
		screen := "────────\n ☐ 颜色\n\n 你喜欢哪种颜色？\n\n ❯ 1. 红\n   2. 绿\n   3. 蓝\n   4. Type something.\n────────\n   5. Chat about this\n Enter to select · ↑/↓ to navigate · Esc to cancel"
		f := newFixture(t, setup{screen: screen})
		f.blockedOn(t, "toolu_q1", "AskUserQuestion", `{"questions":[{"question":"你喜欢哪种颜色？","header":"颜色","multiSelect":false,"options":[{"label":"红"},{"label":"绿"},{"label":"蓝"}]}]}`)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_q1", Answers: []protocol.Answer{{Selected: []int{1}}}})
		if got := f.keyLog(); got != "\x1b[B\r" {
			t.Errorf("keys %q, want %q", got, "\x1b[B\r")
		}
	})

	t.Run("fresh attach visible cursor on permission prompt", func(t *testing.T) {
		t.Parallel()
		screen := " Bash command\n\n   echo hi\n\n Do you want to proceed?\n ❯ 1. Yes\n   2. No"
		f := newFixture(t, setup{screen: screen})
		f.blockedOn(t, "toolu_p1", "Bash", `{"command":"echo hi"}`)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
		if got := f.keyLog(); got != "\r" {
			t.Errorf("keys %q, want %q", got, "\r")
		}
	})

	t.Run("wrapped multiline question with rail and wrapped tab bar", func(t *testing.T) {
		t.Parallel()
		q1Text := "针对 `store.go` 的 **SQLite** 迁移：\n第一行说明包含 `v1 -> v2` 细节，且超过终端宽度自动折行？"
		q2Text := "失败时是否回滚备份？"
		input := fmt.Sprintf(`{"questions":[{"question":%q,"header":"非常长的数据库迁移与历史兼容策略标题一","multiSelect":false,"options":[{"label":"方案一"},{"label":"方案二"}]},{"question":%q,"header":"非常长的失败回滚与数据保护策略标题二","multiSelect":false,"options":[{"label":"是"},{"label":"否"}]}]}`, q1Text, q2Text)
		q1 := "────────\n←  ☐ 非常长的数据库迁移与历史 ☒ 非常长的失败回滚与数据保护 ✔       →\n  兼容…                      策略…                      Submit\n\n│ 针对 `store.go` 的 **SQLite** 迁移：\n│ 第一行说明包含 `v1 -> v2`\n│ 细节，且超过终端宽度自动折行？\n\n❯ 1. 方案一\n  2. 方案二"
		q2 := "────────\n←  ☒ 非常长的数据库迁移与历史 ☐ 非常长的失败回滚与数据保护 ✔       →\n  兼容…                      策略…                      Submit\n\n│ 失败时是否\n│ 回滚备份？\n\n❯ 1. 是\n  2. 否"
		review := "────────\n←  ☒ 非常长的数据库迁移与历史 ☒ 非常长的失败回滚与数据保护 ✔       →\n  兼容…                      策略…                      Submit\n\nReview your answers\n❯ 1. Submit answers\n  2. Cancel"
		frame := func(page string) string {
			return "\x1b[?1049h\x1b[?2026h\x1b[31;1H" + strings.Replace(page, "────────", strings.Repeat("─", 127), 1) + "\x1b[43;1H\x1b[?2026l"
		}
		f := newFixture(t, setup{opts: stepAttach(t, frame(q1), frame(q2), frame(review))})
		f.blockedOn(t, "toolu_q1", "AskUserQuestion", input)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{
			SessionID: shortID, ToolUseID: "toolu_q1",
			Answers: []protocol.Answer{{Selected: []int{0}}, {Selected: []int{1}}},
		})
		if got := f.keyLog(); got != "\r\x1b[B\r\r" {
			t.Errorf("keys %q, want %q", got, "\r\x1b[B\r\r")
		}
	})

	t.Run("mid-redraw blank frame between questions", func(t *testing.T) {
		t.Parallel()
		q1, q2, review := answerScreens()
		dir := t.TempDir()
		for i, s := range []string{q1, q2, review} {
			if err := os.WriteFile(filepath.Join(dir, strconv.Itoa(i)), []byte("\x1b[2J\x1b[H"+strings.ReplaceAll(s, "\n", "\r\n")), 0o600); err != nil {
				t.Fatal(err)
			}
		}
		// Clears the screen on Enter, pauses 150ms (shorter than tabGoneWait), then draws the next page.
		opts := shellAttach(`d='` + dir + `'; cr=$(printf '\r'); i=0; cat "$d/0"; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "$cr" ]; then i=$((i+1)); if [ -f "$d/$i" ]; then printf '\033[2J\033[H'; sleep 0.15; cat "$d/$i"; fi; fi; done`)
		f := newFixture(t, setup{opts: opts})
		f.blockedOn(t, "toolu_q1", "AskUserQuestion", twoQuestionInput)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{
			SessionID: shortID, ToolUseID: "toolu_q1",
			Answers: []protocol.Answer{{Selected: []int{1}}, {Selected: []int{1}}},
		})
		if got := f.keyLog(); got != "\x1b[B\r\x1b[B\r\r" {
			t.Errorf("keys %q, want %q", got, "\x1b[B\r\x1b[B\r\r")
		}
	})
}

// The /model picker chat.setModel opens is its own, not a panel left open:
// an open chat does not tell the phone the session stopped on a panel,
// however long the picker shows.
func TestSetModelPickerIsNoPanelLeftOpen(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	picker := "\x1b[2J\x1b[H\x1b[?25l   Select model\r\n\r\n"
	for i := 1; i <= 10; i++ {
		mark := " "
		if i == 10 {
			mark = "❯"
		}
		picker += fmt.Sprintf("   %s %d. Model %d  Custom model\r\n", mark, i, i)
	}
	picker += "\r\n   Enter to set as default · s to use this session only · Esc to cancel\r\n"
	for name, text := range map[string]string{"picker": picker, "input": "\x1b[2J\x1b[H\x1b[?25h────────\r\n❯ \r\n────────"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(text), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	s := running()
	// Enter opens the picker, s closes it.
	s.opts = shellAttach(`d='` + dir + `'; cr=$(printf '\r'); p=0; cat "$d/input"; while :; do c=$(dd bs=1 count=1 2>/dev/null); [ -z "$c" ] && exit; printf '%s' "$c" >> "$FAKE_ATTACH_LOG"; if [ "$c" = "$cr" ] && [ $p -eq 0 ]; then p=1; cat "$d/picker"; elif [ "$c" = s ] && [ $p -eq 1 ]; then p=0; cat "$d/input"; fi; done`)
	f := newFixture(t, s)
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	start := time.Now()
	// Nine rows up from the cursor: the picker shows about 5 seconds.
	f.ok(t, "c1", protocol.MethodChatSetModel, protocol.ChatSetModelRequest{SessionID: shortID, Model: "Model 1"})
	if took := time.Since(start); took < 5*time.Second {
		t.Fatalf("setModel took %v, want the picker open about 5 s", took)
	}
	if got := f.keyLog(); !strings.Contains(got, strings.Repeat("\x1b[A", 9)+"s") {
		t.Fatalf("keys %q, want Model 1 picked", got)
	}
	for {
		e, ok := f.maybeEvent(protocol.EventChatState, time.Second)
		if !ok {
			break
		}
		var ev protocol.ChatStateEvent
		if err := json.Unmarshal(e.Body, &ev); err != nil {
			t.Fatal(err)
		}
		if ev.UnknownDialog {
			t.Fatalf("chat.state told of a panel left open: %s", e.Body)
		}
	}
}
