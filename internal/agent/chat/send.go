package chat

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// UploadMaxAge is how long uploaded pictures are kept (design D7).
const UploadMaxAge = 24 * time.Hour

// imageExt maps the picture types chat.send accepts to file extensions.
var imageExt = map[string]string{"image/png": ".png", "image/jpeg": ".jpg"}

// uploadName matches the names saveImages gives uploads: 16 random bytes
// in hex plus an extension of imageExt. Only such files are cleaned up.
var uploadName = regexp.MustCompile(`^[0-9a-f]{32}\.(png|jpg)$`)

func (h *Handler) send(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatSendRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	// Cleaned first, so the checks see what will be typed.
	body.Text = attach.CleanText(body.Text)
	if msg := checkMessage(body); msg != "" {
		return agent.Fail(req, protocol.CodeInvalidParams, msg)
	}
	target, err := h.lookup(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	if instruction, ok := h.forkHere(target, body); ok {
		if err := h.opts.Forker.Fork(ctx, target.ID, instruction); err != nil {
			return agent.FailErr(req, err)
		}
		return agent.Reply(req, protocol.ChatSendResponse{})
	}
	if !target.Running {
		if h.opts.Waker == nil {
			return failWith(req, errStopped)
		}
		if err := h.opts.Waker.Wake(ctx, target.SessionID, h.hold); err != nil {
			// A Waker failure with a protocol code, such as a working
			// directory that is gone, tells the phone why.
			var pe *protocol.Error
			if errors.As(err, &pe) {
				return agent.Fail(req, pe.Code, "唤醒会话失败："+pe.Message)
			}
			return agent.Fail(req, protocol.CodeInternal, "唤醒会话失败，请稍后重试")
		}
	}
	paths, err := h.saveImages(body.Images)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, "保存图片失败")
	}
	conn, err := h.pool.Acquire(ctx, target.ID)
	if err != nil {
		return agent.Fail(req, protocol.CodeInternal, "接入会话失败，请稍后重试")
	}
	defer h.pool.Release(target.ID)

	if transcript.IsPanelCommand(body.Text) && len(paths) == 0 {
		return h.sendPanel(ctx, req, conn, strings.TrimSpace(body.Text))
	}

	w := &watch{claudeDir: h.opts.Source.ClaudeDir, sessionID: target.SessionID}
	w.f = openFollower(w.claudeDir, w.sessionID)
	err = conn.Do(ctx, func(t *attach.Term) error {
		if err := checkInput(t); err != nil {
			return err
		}
		if err := t.Send(attach.SendKeys(body.Text, paths, h.opts.ImageWait)); err != nil {
			return &opError{protocol.CodeInternal, "向会话输入消息失败，请重试"}
		}
		return nil
	})
	if err != nil {
		return failWith(req, err)
	}
	ok, queued, unknown := w.wait(ctx, body.Text, h.opts.DeliveryTimeout)
	if unknown {
		return agent.Fail(req, protocol.CodeInvalidParams, fmt.Sprintf("会话不认识 %s 这个命令", commandName(body.Text)))
	}
	if !ok && strings.HasPrefix(strings.TrimSpace(body.Text), "/") {
		ok = tookCommand(ctx, conn, body.Text)
	}
	if !ok {
		return agent.Fail(req, protocol.CodeNotDelivered, fmt.Sprintf("消息发出后 %g 秒内没有出现在会话里，可能没有送达", h.opts.DeliveryTimeout.Seconds()))
	}
	return agent.Reply(req, protocol.ChatSendResponse{Queued: queued})
}

// forkHere reports whether the agent forks the session itself for a
// /fork in body, and with what instruction: Claude Code refuses /fork in
// a session launched with --setting-sources (2.1.329), as the account
// login is.
func (h *Handler) forkHere(target discovery.Target, body protocol.ChatSendRequest) (string, bool) {
	if h.opts.Forker == nil || len(body.Images) > 0 || commandName(body.Text) != "/fork" {
		return "", false
	}
	t, ok := h.opts.Source.SessionTemplate(target.ID)
	if !ok || !slices.Contains(t.Flags, "--setting-sources") {
		return "", false
	}
	return strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(body.Text), "/fork")), true
}

// panelTyped is how long a panel command may take to show in the input
// after it was pasted.
const panelTyped = 2 * time.Second

// sendPanel sends one of transcript.PanelCommands. Claude Code records it
// only once its panel closes, so the screen tells: the command must show in
// the input box before Enter, and be gone from it after: its panel open in
// place of the box, or the box empty when the command was queued.
func (h *Handler) sendPanel(ctx context.Context, req protocol.Envelope, conn *attach.Conn, cmd string) protocol.Envelope {
	steps := attach.SendKeys(cmd, nil, 0)
	err := conn.Do(ctx, func(t *attach.Term) error {
		if err := checkInput(t); err != nil {
			return err
		}
		if err := t.Send(steps[:len(steps)-1]); err != nil {
			return &opError{protocol.CodeInternal, "向会话输入消息失败，请重试"}
		}
		if !t.WaitFor(func(sc string) bool { return inInput(sc, cmd) }, panelTyped) {
			return &opError{protocol.CodeNotDelivered, "命令没有出现在会话的输入框里，没有发出"}
		}
		if err := t.Send(steps[len(steps)-1:]); err != nil {
			return &opError{protocol.CodeInternal, "向会话输入消息失败，请重试"}
		}
		if !t.WaitFor(func(sc string) bool { return !inInput(sc, cmd) }, h.opts.DeliveryTimeout) {
			return &opError{protocol.CodeNotDelivered, fmt.Sprintf("命令按下回车后 %g 秒内还留在输入框里，可能没有送达", h.opts.DeliveryTimeout.Seconds())}
		}
		return nil
	})
	if err != nil {
		return failWith(req, err)
	}
	return agent.Reply(req, protocol.ChatSendResponse{})
}

// tookCommand reports whether the screen shows that Claude Code took the
// slash command cmd though its record has not shown up, as /compact is
// written only once the compaction is done: by the rule of sendPanel, cmd
// is gone from the input box, and the status line of a working session
// is up.
func tookCommand(ctx context.Context, conn *attach.Conn, cmd string) bool {
	took := false
	_ = conn.Do(ctx, func(t *attach.Term) error {
		screen := t.Screen()
		i, _ := statusLine(strings.Split(screen, "\n"))
		took = i >= 0 && !inInput(screen, strings.TrimSpace(cmd))
		return nil
	})
	return took
}

// inInput reports whether Claude Code's input box holds exactly cmd.
func inInput(screen, cmd string) bool {
	text, ok := inputLine(screen)
	return ok && text == cmd
}

// inputLine returns what Claude Code's input box holds: the line starting
// with its prompt right under a rule. Earlier messages in the conversation
// above also start with ❯, but not under a rule. The prompt is ❯, or ! for
// a shell command, and is followed by a no-break space.
func inputLine(screen string) (string, bool) {
	box, ok := inputBox(screen)
	if !ok {
		return "", false
	}
	return box[0], true
}

// inputText returns what Claude Code's input box holds over all its
// lines, which a long or multi-line message wraps onto.
func inputText(screen string) (string, bool) {
	box, ok := inputBox(screen)
	return strings.Join(box, "\n"), ok
}

// inputBox returns the lines of Claude Code's input box: the prompt line
// under a rule and the lines under it down to the box's lower rule.
func inputBox(screen string) ([]string, bool) {
	lines := strings.Split(screen, "\n")
	for i := len(lines) - 1; i > 0; i-- {
		if !strings.HasPrefix(strings.TrimSpace(lines[i-1]), "─") {
			continue
		}
		for _, prompt := range []string{"❯", "!"} {
			rest, ok := strings.CutPrefix(lines[i], prompt)
			if !ok {
				continue
			}
			box := []string{strings.TrimSpace(strings.ReplaceAll(rest, "\u00a0", " "))}
			for _, l := range lines[i+1:] {
				if strings.HasPrefix(strings.TrimSpace(l), "─") {
					break
				}
				box = append(box, strings.TrimSpace(l))
			}
			return box, true
		}
	}
	return nil, false
}

var errOffInput = &opError{protocol.CodeInvalidParams, "会话停在面板或对话框上，先在终端里关掉再发"}

// checkInput refuses to type into a session that is off its input (see
// awayFromInput): a panel or dialog there would take the keys, and its
// Enter would act on whatever it has selected. The /model picker keeps its
// own message.
func checkInput(t *attach.Term) error {
	if err := checkNoPicker(t); err != nil {
		return err
	}
	if awayFromInput(t.View()) {
		return errOffInput
	}
	return nil
}

// checkMessage validates a chat.send body and returns why it is refused.
func checkMessage(b protocol.ChatSendRequest) string {
	switch {
	case b.SessionID == "":
		return errNoSessionID.msg
	case strings.TrimSpace(b.Text) == "" && len(b.Images) == 0:
		return "消息是空的"
	case isModelCommand(b.Text):
		return msgModelCommand
	}
	total := 0
	for _, im := range b.Images {
		if _, ok := imageExt[im.MediaType]; !ok {
			return "只支持 PNG 和 JPEG 图片"
		}
		if len(im.Data) == 0 || len(im.Data) > protocol.MaxImageBytes {
			return fmt.Sprintf("图片不能为空，也不能超过 %d MB", protocol.MaxImageBytes>>20)
		}
		total += len(im.Data)
	}
	if total > protocol.MaxImagesTotalBytes {
		return fmt.Sprintf("图片加起来不能超过 %d MB", protocol.MaxImagesTotalBytes>>20)
	}
	return ""
}

// msgModelCommand is shown on the phone when a typed message is /model.
const msgModelCommand = "不能直接发送 /model，它会改掉电脑上所有新会话的默认模型；请用切换模型入口"

// isModelCommand reports whether text runs /model. With an argument it
// saves the model as the default in ~/.claude/settings.json; bare, it
// opens the picker, where the next Enter does the same (RESULTS.md).
// Surrounding whitespace is ignored and case too, to stay on the safe
// side of how Claude Code matches command names.
func isModelCommand(text string) bool {
	fields := strings.Fields(text)
	return len(fields) > 0 && strings.EqualFold(fields[0], "/model")
}

func (h *Handler) uploadDir() (string, error) {
	if h.opts.UploadDir != "" {
		return h.opts.UploadDir, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".cache", "clauderc", "uploads"), nil
}

// saveImages removes stale uploads, then writes each picture to a new
// file readable only by the agent's user and returns the paths.
func (h *Handler) saveImages(images []protocol.Image) ([]string, error) {
	dir, err := h.uploadDir()
	if err != nil {
		return nil, err
	}
	removeOld(dir, time.Now().Add(-UploadMaxAge))
	if len(images) == 0 {
		return nil, nil
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	var paths []string
	for _, im := range images {
		var rnd [16]byte
		if _, err := rand.Read(rnd[:]); err != nil {
			return nil, err
		}
		p := filepath.Join(dir, hex.EncodeToString(rnd[:])+imageExt[im.MediaType])
		f, err := os.OpenFile(p, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err != nil {
			return nil, err
		}
		_, werr := f.Write(im.Data)
		if cerr := f.Close(); werr == nil {
			werr = cerr
		}
		if werr != nil {
			return nil, werr
		}
		paths = append(paths, p)
	}
	return paths, nil
}

// removeOld deletes uploads in dir last modified before cutoff; files it
// did not write are left alone.
func removeOld(dir string, cutoff time.Time) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if !uploadName.MatchString(e.Name()) {
			continue
		}
		info, err := e.Info()
		if err != nil || !info.Mode().IsRegular() || !info.ModTime().Before(cutoff) {
			continue
		}
		_ = os.Remove(filepath.Join(dir, e.Name()))
	}
}

// watch looks for a sent message in what the transcript gains after it
// was created.
type watch struct {
	claudeDir, sessionID string
	f                    *transcript.Follower
}

// commandName is the first word of a slash command, slash included.
func commandName(text string) string {
	fields := strings.Fields(text)
	if len(fields) == 0 {
		return ""
	}
	return fields[0]
}

// wait reports whether text showed up within timeout, as a user item,
// queued or not, or for a slash command as a command item or as an
// enqueue record without text, which is how Claude Code queues one; and
// whether it waits in the queue. unknown is true, at once, when Claude
// Code turns the slash command down as unknown.
func (w *watch) wait(ctx context.Context, text string, timeout time.Duration) (ok, queued, unknown bool) {
	deadline := time.Now().Add(timeout)
	command := strings.HasPrefix(strings.TrimSpace(text), "/")
	commands, unknowns := 0, 0
	if w.f != nil {
		commands = w.f.QueuedCommands()
		unknowns = len(w.f.UnknownCommands())
	}
	for {
		var items []transcript.Item
		if w.f == nil {
			// The transcript did not exist yet: everything in it is new.
			if w.f = openFollower(w.claudeDir, w.sessionID); w.f != nil {
				items, _ = w.f.Page(0, int(^uint(0)>>1))
			}
		} else {
			items, _ = w.f.Poll()
		}
		for _, it := range items {
			if delivered(it, text) {
				return true, it.Queued, false
			}
		}
		if command && w.f != nil {
			if w.f.QueuedCommands() > commands {
				return true, true, false
			}
			if names := w.f.UnknownCommands(); len(names) > unknowns && slices.Contains(names[unknowns:], commandName(text)) {
				return false, false, true
			}
		}
		if time.Now().After(deadline) {
			return false, false, false
		}
		select {
		case <-ctx.Done():
			return false, false, false
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func delivered(it transcript.Item, text string) bool {
	text = strings.TrimSpace(text)
	switch it.Kind {
	case protocol.ItemUser:
		return strings.HasSuffix(strings.TrimSpace(it.Text), text)
	case transcript.ItemCommand:
		if it.Command == nil {
			return false
		}
		if line, ok := strings.CutPrefix(text, "!"); ok {
			return it.Command.Name == "!" && it.Command.Args == strings.TrimSpace(line)
		}
		name, _, _ := strings.Cut(text, " ")
		return strings.HasPrefix(text, "/") && it.Command.Name == name
	}
	return false
}
