package chat_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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
	for _, c := range []struct {
		decision protocol.Decision
		keys     string
	}{{protocol.DecisionAllow, "\r"}, {protocol.DecisionDeny, "\x1b"}} {
		f := newFixture(t, setup{})
		f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch marker.txt"}`)
		f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: c.decision})
		if got := f.keyLog(); got != c.keys {
			t.Errorf("%s: keys %q, want %q", c.decision, got, c.keys)
		}
	}
}

func TestAnswerQuestions(t *testing.T) {
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
		f := newFixture(t, setup{})
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

// On the recorded prompt screen the same operations type as usual.
func TestEnterAllowedOnPromptScreen(t *testing.T) {
	f := newFixture(t, setup{replay: "mode-default"})
	// The replay does not write the transcript, so delivery is not seen.
	res := f.call(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "你好"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotDelivered {
		t.Errorf("send: error %+v, want notDelivered after typing", res.Error)
	}
	f.blockedOn(t, "toolu_p1", "Bash", `{"command":"touch marker.txt"}`)
	f.ok(t, "c1", protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: "toolu_p1", Decision: protocol.DecisionAllow})
	if got, want := f.keyLog(), "\x15\x1b[200~你好\x1b[201~\r"+"\r"; got != want {
		t.Fatalf("keys %q, want %q", got, want)
	}
}

const openPicker = "\x15\x1b[200~/model\x1b[201~\r"

func TestModels(t *testing.T) {
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
		{"bypass", nil, protocol.PermissionBypass, "", protocol.CodeInvalidParams},
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

func TestCommands(t *testing.T) {
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

func TestMethodsListsAllChatMethods(t *testing.T) {
	h := chat.New(chat.Options{})
	defer h.Close()
	want := map[protocol.Method]bool{
		protocol.MethodChatOpen: true, protocol.MethodChatClose: true, protocol.MethodChatSend: true,
		protocol.MethodChatAnswer: true, protocol.MethodChatInterrupt: true, protocol.MethodChatModels: true,
		protocol.MethodChatSetModel: true, protocol.MethodChatSetPermissionMode: true, protocol.MethodChatCommands: true,
		protocol.MethodChatToolOutput: true, protocol.MethodChatImage: true, protocol.MethodChatAutoResume: true,
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
	// The recorded prompt screen never reacts to keys.
	f := newFixture(t, setup{replay: "mode-default"})
	res := f.call(t, "c1", protocol.MethodChatModels, protocol.ChatModelsRequest{SessionID: shortID})
	if res.Error == nil || res.Error.Message != "电脑上的模型选择界面没有出现，已取消" {
		t.Errorf("error %+v", res.Error)
	}
	if got := f.keyLog(); got != openPicker+"\x1b" {
		t.Fatalf("keys %q, want the picker opened and the last key Esc", got)
	}
}
