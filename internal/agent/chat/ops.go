package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// Screen timings of the operations that read the screen.
const (
	pickerTimeout = 10 * time.Second
	confirmWait   = 1500 * time.Millisecond
	redrawQuiet   = 400 * time.Millisecond
	redrawMax     = 4 * time.Second
	// maxModePresses bounds Shift+Tab presses: the cycle has up to five modes.
	maxModePresses = 4
)

// opError is a failure with its protocol error code.
type opError struct {
	code protocol.ErrorCode
	msg  string
}

func (e *opError) Error() string { return e.msg }

// Errors shared by several methods. Their messages are shown on the phone
// as they are.
var (
	errBadRequest  = &opError{protocol.CodeInvalidParams, "请求内容格式不对"}
	errNoSessionID = &opError{protocol.CodeInvalidParams, "缺少会话 id"}
	errStopped     = &opError{protocol.CodeInvalidParams, "会话已停止，无法操作"}
)

// failWith maps an error to a response: opError keeps its code and
// message, an unknown session is sessionNotFound, anything else internal
// with a fixed message, as error texts from below are not for the phone.
func failWith(req protocol.Envelope, err error) protocol.Envelope {
	var op *opError
	switch {
	case errors.As(err, &op):
		return agent.Fail(req, op.code, op.msg)
	case errors.Is(err, errNotFound):
		return agent.Fail(req, protocol.CodeSessionNotFound, "找不到这个后台会话，可能已经被移除")
	case errors.Is(err, attach.ErrClosed):
		return agent.Fail(req, protocol.CodeInternal, "电脑上的会话接入断开了，请重试")
	case errors.Is(err, attach.ErrUnreliableScreen):
		return agent.Fail(req, protocol.CodeNotDelivered, "暂时无法确认电脑上的画面，操作没有完成")
	case errors.Is(err, attach.ErrScreenTooSmall):
		return agent.Fail(req, protocol.CodeNotDelivered, "电脑上的窗口太小，画面显示不全，请把窗口放大到至少 80 列、24 行再试")
	case errors.Is(err, context.DeadlineExceeded):
		return agent.Fail(req, protocol.CodeInternal, "操作超时，请稍后重试")
	case errors.Is(err, context.Canceled):
		return agent.Fail(req, protocol.CodeInternal, "操作已取消")
	default:
		return agent.Fail(req, protocol.CodeInternal, "目标机内部出错，请稍后重试")
	}
}

// decodeSession unmarshals a request body that names a session in
// sessionId and looks that session up.
func (h *Handler) decodeSession(ctx context.Context, req protocol.Envelope, v any) (discovery.Target, error) {
	if err := json.Unmarshal(req.Body, v); err != nil {
		return discovery.Target{}, errBadRequest
	}
	var s struct {
		SessionID string `json:"sessionId"`
	}
	_ = json.Unmarshal(req.Body, &s)
	if s.SessionID == "" {
		return discovery.Target{}, errNoSessionID
	}
	return h.lookup(ctx, s.SessionID)
}

// withTerm runs fn as one operation on the running session's attach. The
// panels it opens meanwhile are its own (see Handler.driven).
func (h *Handler) withTerm(ctx context.Context, target discovery.Target, fn func(t *attach.Term) error) error {
	if !target.Running {
		return errStopped
	}
	conn, err := h.pool.Acquire(ctx, target.ID)
	if err != nil {
		return err
	}
	defer h.pool.Release(target.ID)
	return conn.Do(ctx, func(t *attach.Term) error {
		h.mu.Lock()
		h.driving[target.ID]++
		h.mu.Unlock()
		defer func() {
			h.mu.Lock()
			if h.driving[target.ID]--; h.driving[target.ID] == 0 {
				delete(h.driving, target.ID)
			}
			h.mu.Unlock()
		}()
		return fn(t)
	})
}

// driven reports whether an operation of withTerm runs on session id. An
// open chat's screen reads then take neither the panel it opens, such as
// the /model picker, for one left open, nor its /rewind for one made by
// hand.
func (h *Handler) driven(id string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.driving[id] > 0
}

// currentPending reads the session's pending prompt from its transcript.
func (h *Handler) currentPending(target discovery.Target) *protocol.Pending {
	f := openFollower(h.opts.Source.ClaudeDir, target.SessionID)
	if f == nil {
		return nil
	}
	return f.Pending(target.Blocked())
}

var errPendingChanged = &opError{protocol.CodePendingChanged, "待确认的内容已变化，请看最新的请求再回答"}

// errPoppedOp is a prompt or dialog that came up during an operation:
// nothing more is typed, not even the Esc that would deny it.
var errPoppedOp = &opError{protocol.CodeInvalidParams, "会话弹出了待确认，这次操作没有做完，请先处理它"}

// panelLeave bounds the Esc that closes a panel once the request is given up.
const panelLeave = 2 * time.Second

// closePanel presses Esc to close the panel an operation opened, even when
// the request was given up meanwhile.
func closePanel(t *attach.Term) error {
	lt, cancel := t.Detached(panelLeave)
	defer cancel()
	return lt.Send(attach.CancelKeys())
}

var errPickerOpen = &opError{protocol.CodeInvalidParams, "电脑上开着模型选择界面，这时按回车会改掉默认模型；请先在电脑上关掉它再试"}

// checkNoPicker refuses an operation that ends in Enter while the /model
// picker is open, as someone on the computer may have left it: Enter on
// the list saves the default model in ~/.claude/settings.json.
func checkNoPicker(t *attach.Term) error {
	if attach.PickerOpen(t.Screen()) {
		return errPickerOpen
	}
	return nil
}

func (h *Handler) answer(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatAnswerRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	p := h.currentPending(target)
	if p == nil && strings.HasPrefix(body.ToolUseID, dialogPrefix) && target.Blocked() {
		return h.answerDialog(ctx, req, target, body)
	}
	if p == nil || p.ToolUseID != body.ToolUseID {
		return failWith(req, errPendingChanged)
	}
	groups, err := answerSteps(p, body)
	if err != nil {
		return failWith(req, err)
	}
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		// The prompt may have been answered on the computer meanwhile.
		if p := h.currentPending(target); p == nil || p.ToolUseID != body.ToolUseID {
			return errPendingChanged
		}
		v := t.View()
		if v.Unreliable {
			return attach.ErrUnreliableScreen
		}
		if err := checkNoPicker(t); err != nil {
			return err
		}
		// The transcript keeps a permission prompt pending until the tool's
		// result is written, also while the tool runs once allowed on the
		// computer; only the screen tells the prompt is still up.
		screen := strings.Join(v.Lines, "\n")
		if strings.TrimSpace(screen) == "" {
			t.WaitFor(func(s string) bool { return strings.TrimSpace(s) != "" }, questionWait)
			if err := ctx.Err(); err != nil {
				return err
			}
			screen = t.Screen()
			if strings.TrimSpace(screen) == "" {
				return attach.ErrUnreliableScreen
			}
		}
		if !promptShown(screen) {
			return errPendingChanged
		}
		return typeAnswer(ctx, t, p.Questions, groups)
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
}

// promptShown reports whether the screen shows a prompt rather than Claude
// Code's input box. A fresh `claude attach` to a session already blocked on a
// prompt leaves the cursor visible (Cursor=true) even after Ctrl+L (Claude
// Code 2.1.331), so chat.answer checks the screen layout instead of
// awayFromInput's hidden-cursor requirement.
func promptShown(screen string) bool {
	return strings.TrimSpace(screen) != "" && !hasInputBox(screen)
}

// errAnswerCut is a question that went from the screen while it was being
// answered: the prompt was left with Esc.
var errAnswerCut = &opError{protocol.CodePendingChanged, "回答到一半，电脑上的题目变了，已按 Esc 退出这次提问"}

// questionWait bounds the wait for the next question of an answer to be
// drawn: a slow terminal still shows the one just answered for a while.
const questionWait = 3 * time.Second

// tabGoneWait is how long a missing tab bar must persist before WaitFor
// treats the prompt as closed rather than a mid-redraw clear frame.
const tabGoneWait = 400 * time.Millisecond

// typeAnswer types the groups of keys of an answer to questions in turn.
// Taking more than one group, each needs its question on screen, or the
// review page for the last: one closed elsewhere would leave the keys to
// the input box or to whatever came up. Failing that before the first,
// nothing is typed. Later, while the prompt stays up, the next question
// is waited for up to questionWait; a prompt still up without it is left
// with Esc, and anything else gets no key, not even Esc, nor does a
// request given up meanwhile.
func typeAnswer(ctx context.Context, t *attach.Term, questions []protocol.Question, groups [][]attach.Step) error {
	for i, g := range groups {
		if len(groups) > 1 && !questionShown(t.Screen(), questions, i) {
			if i == 0 {
				if screen := t.Screen(); hasInputBox(screen) || questionTabs(strings.Split(screen, "\n")) >= 0 {
					return errPendingChanged
				}
			}
			var goneSince time.Time
			t.WaitFor(func(s string) bool {
				if questionShown(s, questions, i) || hasInputBox(s) {
					return true
				}
				if questionTabs(strings.Split(s, "\n")) < 0 {
					if goneSince.IsZero() {
						goneSince = time.Now()
					}
					return time.Since(goneSince) >= tabGoneWait
				}
				goneSince = time.Time{}
				return false
			}, questionWait)
			if err := ctx.Err(); err != nil {
				return err
			}
			screen := t.Screen()
			if strings.TrimSpace(screen) == "" {
				return attach.ErrUnreliableScreen
			}
			if !questionShown(screen, questions, i) {
				if i > 0 && promptShown(screen) && questionTabs(strings.Split(screen, "\n")) >= 0 {
					if err := closePanel(t); err != nil {
						return err
					}
					return errAnswerCut
				}
				return errPendingChanged
			}
		}
		if err := t.Send(g); err != nil {
			return err
		}
	}
	return nil
}

// reviewTitle heads the page that submits the answers (RESULTS.md).
const reviewTitle = "Review your answers"

// questionTabMaxWrap is the most continuation lines a wrapped question tab bar
// can span below the "←" line when 3-4 long headers exceed the screen width.
const questionTabMaxWrap = 3

// questionTabs is the index of the lowest line of the tab bar of a question
// prompt answered in more than one step, "← ☐ 颜色 ☐ 动物 ✔ Submit →"
// (RESULTS.md), or -1. When long headers wrap the tab bar across lines so
// Submit lands below "←", the index of the Submit line is returned.
func questionTabs(lines []string) int {
	for i := len(lines) - 1; i >= 0; i-- {
		l := strings.TrimSpace(lines[i])
		if !strings.HasPrefix(l, "←") {
			continue
		}
		if strings.Contains(l, "Submit") {
			return i
		}
		if !strings.ContainsAny(l, "☐☒✔→") {
			continue
		}
		end := min(i+1+questionTabMaxWrap, len(lines))
		for j := i + 1; j < end; j++ {
			cont := strings.TrimSpace(lines[j])
			if cont == "" || strings.HasPrefix(cont, "─") || strings.HasPrefix(cont, "❯") {
				break
			}
			if strings.Contains(cont, "Submit") {
				return j
			}
		}
	}
	return -1
}

// stripQuestionLines joins lines while removing the leading "│" rail Claude
// Code prepends to every line of a wrapped or multi-line question (2.1.331).
func stripQuestionLines(lines []string) string {
	var b strings.Builder
	for _, l := range lines {
		t := strings.TrimSpace(l)
		t = strings.TrimPrefix(t, "│")
		b.WriteString(t)
	}
	return b.String()
}

// questionShown reports whether the screen shows, under the prompt's tab
// bar, question i of questions, or past the last one the review page.
// Spaces and leading "│" rails on wrapped lines are left out.
func questionShown(screen string, questions []protocol.Question, i int) bool {
	lines := strings.Split(screen, "\n")
	tabs := questionTabs(lines)
	if tabs < 0 {
		return false
	}
	want := reviewTitle
	if i < len(questions) {
		want = questions[i].Question
	}
	return strings.Contains(squash(stripQuestionLines(lines[tabs+1:])), squash(stripQuestionLines(strings.Split(want, "\n"))))
}

// answerErrors words the reasons attach.AnswerKeys refuses answers.
var answerErrors = []struct {
	err error
	msg string
}{
	{attach.ErrAnswerCount, "回答的题数和提问的题数不一致"},
	{attach.ErrOptionRange, "选的选项不存在"},
	{attach.ErrNoAnswer, "有题目还没回答"},
	{attach.ErrSingleAnswer, "单选题只能选一个答案"},
}

// answerSteps returns the keys of an answer in groups (see
// attach.AnswerKeys); a permission prompt takes one.
func answerSteps(p *protocol.Pending, body protocol.ChatAnswerRequest) ([][]attach.Step, error) {
	badDecision := &opError{protocol.CodeInvalidParams, "权限请求只能选允许或拒绝"}
	if p.Kind == protocol.PendingPermission {
		switch {
		case len(body.Answers) > 0:
			return nil, badDecision
		case body.Decision == protocol.DecisionAllow:
			return [][]attach.Step{attach.AllowKeys()}, nil
		case body.Decision == protocol.DecisionDeny:
			return [][]attach.Step{attach.DenyKeys()}, nil
		}
		return nil, badDecision
	}
	if body.Decision != "" {
		return nil, &opError{protocol.CodeInvalidParams, "提问要选选项或填写回答，不能选允许或拒绝"}
	}
	groups, err := attach.AnswerKeys(p.Questions, body.Answers)
	if err != nil {
		for _, a := range answerErrors {
			if errors.Is(err, a.err) {
				return nil, &opError{protocol.CodeInvalidParams, a.msg}
			}
		}
		return nil, err
	}
	return groups, nil
}

// interrupt sends Esc only while the session works. Otherwise Esc would
// deny a pending prompt or close a dialog, so nothing is sent.
func (h *Handler) interrupt(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatInterruptRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	if target.SessionState(h.currentPending(target) != nil) == protocol.StateWorking {
		err = h.withTerm(ctx, target, func(t *attach.Term) error {
			// A prompt may have come up while the terminal was being taken.
			if awayFromInput(t.View()) {
				return errPoppedOp
			}
			return t.Send(attach.InterruptKeys())
		})
		if err != nil {
			return failWith(req, err)
		}
	}
	return agent.Reply(req, struct{}{})
}

// openPicker runs /model and reads the picker. It types nothing off the
// input or while the picker is open already: the Enter after /model would
// land on the list and save the default model, or on a prompt and allow it.
// Once /model went out, every failure leaves with Esc, so the picker is not
// left open, not even when it shows up late or the request was given up.
func openPicker(t *attach.Term) ([]attach.PickerItem, error) {
	if err := checkInput(t); err != nil {
		return nil, err
	}
	fail := func(err error) ([]attach.PickerItem, error) {
		if cerr := closePanel(t); errors.Is(cerr, attach.ErrUnreliableScreen) {
			return nil, cerr
		}
		return nil, err
	}
	keys := attach.OpenModelPickerKeys()
	if err := t.Send(keys[:len(keys)-1]); err != nil {
		return nil, err
	}
	// No Esc for a prompt that came up meanwhile: it would deny it.
	if awayFromInput(t.View()) {
		return nil, errPoppedOp
	}
	if err := t.Send(keys[len(keys)-1:]); err != nil {
		return fail(err)
	}
	found := t.WaitFor(func(s string) bool {
		_, ok := attach.ParsePicker(s)
		return ok && attach.PickerOpen(s)
	}, pickerTimeout)
	if !found {
		return fail(&opError{protocol.CodeInternal, "电脑上的模型选择界面没有出现，已取消"})
	}
	t.WaitQuiet(redrawQuiet, redrawMax)
	items, ok := attach.ParsePicker(t.Screen())
	if !ok {
		return fail(&opError{protocol.CodeInternal, "读不出模型选择界面的内容，已取消"})
	}
	return items, nil
}

// modelIDs names each picker entry by its label; entries sharing a label
// get "#<number>" appended so the id stays unique.
func modelIDs(items []attach.PickerItem) []string {
	count := map[string]int{}
	for _, it := range items {
		count[it.Label]++
	}
	ids := make([]string, len(items))
	for i, it := range items {
		ids[i] = it.Label
		if count[it.Label] > 1 {
			ids[i] += "#" + strconv.Itoa(it.Number)
		}
	}
	return ids
}

func (h *Handler) models(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatModelsRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	var models []protocol.ModelOption
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		items, err := openPicker(t)
		if err != nil {
			return err
		}
		ids := modelIDs(items)
		models = make([]protocol.ModelOption, len(items))
		for i, it := range items {
			models[i] = protocol.ModelOption{ID: ids[i], Label: it.Label, Description: it.Description, Current: it.Current}
		}
		return closePanel(t)
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, protocol.ChatModelsResponse{Models: models})
}

// setModel picks the model in the /model picker with s, "this session
// only". Neither /model <name> nor Enter on the list is used: both save
// the model as the default in ~/.claude/settings.json.
func (h *Handler) setModel(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatSetModelRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	if body.Model == "" {
		return agent.Fail(req, protocol.CodeInvalidParams, "缺少要切换的模型")
	}
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		items, err := openPicker(t)
		if err != nil {
			return err
		}
		var from, to int
		for i, id := range modelIDs(items) {
			if id == body.Model {
				to = items[i].Number
			}
			if items[i].Cursor {
				from = items[i].Number
			}
		}
		if to == 0 || from == 0 {
			if cerr := closePanel(t); cerr != nil {
				return cerr
			}
			if to == 0 {
				return &opError{protocol.CodeInvalidParams, "模型列表里没有这个模型，已取消"}
			}
			return &opError{protocol.CodeInternal, "找不到模型选择界面的光标位置，已取消"}
		}
		if err := t.Send(attach.PickModelKeys(from, to)); err != nil {
			_ = closePanel(t)
			return err
		}
		if t.WaitFor(attach.ConfirmOpen, confirmWait) {
			return t.Send(attach.ConfirmKeys())
		}
		if t.View().Unreliable {
			return attach.ErrUnreliableScreen
		}
		return nil
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
}

// switchableModes are the modes of the Shift+Tab cycle (design D7).
// Bypass is in the cycle only when enabled at session launch. Otherwise,
// cycling to bypass returns to the starting mode and reports an error.
var switchableModes = map[protocol.PermissionMode]bool{
	protocol.PermissionDefault:     true,
	protocol.PermissionAcceptEdits: true,
	protocol.PermissionPlan:        true,
	protocol.PermissionAuto:        true,
	protocol.PermissionBypass:      true,
}

// readMode redraws the screen and reads the mode from the status line.
func readMode(t *attach.Term) (protocol.PermissionMode, bool, error) {
	if err := t.Send(attach.RedrawKeys()); err != nil {
		return "", false, err
	}
	t.WaitQuiet(redrawQuiet, redrawMax)
	mode, ok := attach.ParseMode(t.Screen())
	return mode, ok, nil
}

func (h *Handler) setPermissionMode(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatSetPermissionModeRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	if !switchableModes[body.Mode] {
		return agent.Fail(req, protocol.CodeInvalidParams, "这个权限模式不能从设备切换")
	}
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		if err := checkInput(t); err != nil {
			return err
		}
		// start is the first mode read; moved is set once another mode shows,
		// so a press the screen has not caught up with is not a full cycle.
		var start protocol.PermissionMode
		moved := false
		for presses := 0; ; presses++ {
			mode, ok, err := readMode(t)
			if err != nil {
				return err
			}
			if ok && mode == body.Mode {
				h.setScreenMode(target.ID, mode)
				return nil
			}
			if ok && start != "" && mode != start {
				moved = true
			}
			cycled := ok && moved && mode == start
			if cycled && body.Mode == protocol.PermissionBypass {
				return &opError{protocol.CodeInvalidParams, "这个会话启动时没开 bypass，切不过去"}
			}
			if cycled || presses == maxModePresses {
				return &opError{protocol.CodeInternal, fmt.Sprintf("按了 %d 次切换键仍没到目标权限模式", presses)}
			}
			if ok && start == "" {
				start = mode
			}
			// Shift+Tab on a permission prompt for an edit allows all
			// edits for the session.
			if awayFromInput(t.View()) {
				return errPoppedOp
			}
			if err := t.Send(attach.CycleModeKeys()); err != nil {
				return err
			}
		}
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
}

func (h *Handler) commands(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatCommandsRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	var skills []protocol.Command
	if f := openFollower(h.opts.Source.ClaudeDir, target.SessionID); f != nil {
		skills = f.Info().Skills
	}
	var flags []string
	if t, ok := h.opts.Source.SessionTemplate(target.ID); ok {
		flags = t.Flags
	}
	userSkills, userCommands := userSources(flags, h.opts.Source.ClaudeDir)
	local := transcript.LocalCommands(h.opts.Source.ClaudeDir, target.Cwd, userSkills, userCommands)
	return agent.Reply(req, protocol.ChatCommandsResponse{Commands: transcript.Commands(skills, local)})
}

// userSources tells whether a session launched with flags loads the
// skills and the commands under claudeDir. --setting-sources without
// "user" leaves both out, as for the account login, which adds the skills
// back with an --add-dir whose .claude/skills leads to them.
func userSources(flags []string, claudeDir string) (skills, commands bool) {
	i := slices.Index(flags, "--setting-sources")
	if i < 0 || i+1 >= len(flags) || slices.Contains(strings.Split(flags[i+1], ","), "user") {
		return true, true
	}
	want, err := filepath.EvalSymlinks(filepath.Join(claudeDir, "skills"))
	if err != nil {
		return false, false
	}
	for j, f := range flags {
		if f != "--add-dir" || j+1 >= len(flags) {
			continue
		}
		if got, err := filepath.EvalSymlinks(filepath.Join(flags[j+1], ".claude", "skills")); err == nil && got == want {
			return true, false
		}
	}
	return false, false
}

// setScreenMode records the mode read from the status line of session id
// for its open chat, which then reports it.
func (h *Handler) setScreenMode(id string, mode protocol.PermissionMode) {
	h.mu.Lock()
	s := h.chats[id]
	h.mu.Unlock()
	if s == nil {
		return
	}
	s.mu.Lock()
	s.screenMode = mode
	s.mu.Unlock()
	s.pushChanges()
}
