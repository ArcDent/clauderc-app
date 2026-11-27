package chat

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"strings"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/protocol"
)

// dialogPrefix starts the ToolUseID of a dialog pending prompt; dialogs
// have no tool_use of their own.
const dialogPrefix = "dialog-"

// dialogID names a dialog by its text, so the same dialog keeps its id
// and a different one gets a new id.
func dialogID(d attach.Dialog) string {
	sum := sha256.Sum256([]byte(d.Title + "\n" + strings.Join(d.Options, "\n")))
	return dialogPrefix + hex.EncodeToString(sum[:6])
}

// dialogPending is the pending prompt shown for a dialog.
func dialogPending(d attach.Dialog) *protocol.Pending {
	opts := make([]protocol.Option, len(d.Options))
	for i, o := range d.Options {
		opts[i] = protocol.Option{Label: o}
	}
	return &protocol.Pending{
		ToolUseID: dialogID(d),
		Kind:      protocol.PendingDialog,
		Questions: []protocol.Question{{Question: d.Title, Options: opts}},
	}
}

// screenDialog reads a dialog off the screen of a running session that is
// blocked while its transcript shows nothing pending.
func screenDialog(conn *attach.Conn, target discovery.Target) *attach.Dialog {
	if conn == nil || !target.Blocked() {
		return nil
	}
	d, ok := attach.ParseDialog(conn.Screen())
	if !ok {
		return nil
	}
	return &d
}

// HasDialog reports whether the running session id shows a dialog. It
// attaches when no chat holds the session open; the attach lingers, so a
// list refresh soon after reuses it.
func (h *Handler) HasDialog(ctx context.Context, id string) bool {
	conn, err := h.pool.Acquire(ctx, id)
	if err != nil {
		return false
	}
	defer h.pool.Release(id)
	_, ok := attach.ParseDialog(conn.Screen())
	return ok
}

var errDialogAnswer = &opError{protocol.CodeInvalidParams, "对话框要选一个选项"}

// answerDialog picks one option of the dialog the session shows; the
// dialog must still be the one the phone saw.
func (h *Handler) answerDialog(ctx context.Context, req protocol.Envelope, target discovery.Target, body protocol.ChatAnswerRequest) protocol.Envelope {
	if body.Decision != "" || len(body.Answers) != 1 || len(body.Answers[0].Selected) != 1 || body.Answers[0].Custom != "" {
		return failWith(req, errDialogAnswer)
	}
	pick := body.Answers[0].Selected[0]
	err := h.withTerm(ctx, target, func(t *attach.Term) error {
		v := t.View()
		if v.Unreliable {
			return attach.ErrUnreliableScreen
		}
		d, ok := attach.ParseDialog(strings.Join(v.Lines, "\n"))
		if !ok || dialogID(d) != body.ToolUseID {
			return errPendingChanged
		}
		if pick < 0 || pick >= len(d.Options) {
			return &opError{protocol.CodeInvalidParams, "选的选项不存在"}
		}
		return t.Send(attach.DialogKeys(d.Cursor, pick))
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, struct{}{})
}
