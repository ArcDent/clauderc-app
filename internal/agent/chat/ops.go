package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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
	// maxModePresses bounds Shift+Tab presses: the cycle has four modes.
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

// withTerm runs fn as one operation on the running session's attach.
func (h *Handler) withTerm(ctx context.Context, target discovery.Target, fn func(t *attach.Term) error) error {
	if !target.Running {
		return errStopped
	}
	conn, err := h.pool.Acquire(ctx, target.ID)
	if err != nil {
		return err
	}
	defer h.pool.Release(target.ID)
	return conn.Do(ctx, fn)
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
	steps, err := answerSteps(p, body)
	if err != nil {
		return failWith(req, err)
	}
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		// The prompt may have been answered on the computer meanwhile.
		if p := h.currentPending(target); p == nil || p.ToolUseID != body.ToolUseID {
			return errPendingChanged
		}
		if err := checkNoPicker(t); err != nil {
			return err
		}
		return t.Send(steps)
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
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

func answerSteps(p *protocol.Pending, body protocol.ChatAnswerRequest) ([]attach.Step, error) {
	badDecision := &opError{protocol.CodeInvalidParams, "权限请求只能选允许或拒绝"}
	if p.Kind == protocol.PendingPermission {
		switch {
		case len(body.Answers) > 0:
			return nil, badDecision
		case body.Decision == protocol.DecisionAllow:
			return attach.AllowKeys(), nil
		case body.Decision == protocol.DecisionDeny:
			return attach.DenyKeys(), nil
		}
		return nil, badDecision
	}
	if body.Decision != "" {
		return nil, &opError{protocol.CodeInvalidParams, "提问要选选项或填写回答，不能选允许或拒绝"}
	}
	steps, err := attach.AnswerKeys(p.Questions, body.Answers)
	if err != nil {
		for _, a := range answerErrors {
			if errors.Is(err, a.err) {
				return nil, &opError{protocol.CodeInvalidParams, a.msg}
			}
		}
		return nil, err
	}
	return steps, nil
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
		err = h.withTerm(ctx, target, func(t *attach.Term) error { return t.Send(attach.InterruptKeys()) })
		if err != nil {
			return failWith(req, err)
		}
	}
	return agent.Reply(req, struct{}{})
}

// openPicker runs /model and reads the picker. It types nothing while the
// picker is open already: the Enter after /model would land on the list
// and save the default model. Once /model went out, every failure leaves
// with Esc, so the picker is not left open, not even when it shows up late.
func openPicker(t *attach.Term) ([]attach.PickerItem, error) {
	if attach.PickerOpen(t.Screen()) {
		return nil, errPickerOpen
	}
	fail := func(err error) ([]attach.PickerItem, error) {
		_ = t.Send(attach.CancelKeys())
		return nil, err
	}
	if err := t.Send(attach.OpenModelPickerKeys()); err != nil {
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
		return t.Send(attach.CancelKeys())
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
			if cerr := t.Send(attach.CancelKeys()); cerr != nil {
				return cerr
			}
			if to == 0 {
				return &opError{protocol.CodeInvalidParams, "模型列表里没有这个模型，已取消"}
			}
			return &opError{protocol.CodeInternal, "找不到模型选择界面的光标位置，已取消"}
		}
		if err := t.Send(attach.PickModelKeys(from, to)); err != nil {
			return err
		}
		if t.WaitFor(attach.ConfirmOpen, confirmWait) {
			return t.Send(attach.ConfirmKeys())
		}
		return nil
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
}

// switchableModes are the modes of the Shift+Tab cycle (design D7).
var switchableModes = map[protocol.PermissionMode]bool{
	protocol.PermissionDefault:     true,
	protocol.PermissionAcceptEdits: true,
	protocol.PermissionPlan:        true,
	protocol.PermissionAuto:        true,
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
		return agent.Fail(req, protocol.CodeInvalidParams, "这个权限模式不能从手机切换")
	}
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		for presses := 0; ; presses++ {
			mode, ok, err := readMode(t)
			if err != nil {
				return err
			}
			if ok && mode == body.Mode {
				h.setScreenMode(target.ID, mode)
				return nil
			}
			if presses == maxModePresses {
				return &opError{protocol.CodeInternal, fmt.Sprintf("按了 %d 次切换键仍没到目标权限模式", maxModePresses)}
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
	local := transcript.LocalCommands(h.opts.Source.ClaudeDir, target.Cwd)
	return agent.Reply(req, protocol.ChatCommandsResponse{Commands: transcript.Commands(skills, local)})
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
