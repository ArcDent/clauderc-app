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
	"strings"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
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
	if !target.Running {
		if h.opts.Waker == nil {
			return failWith(req, errStopped)
		}
		if err := h.opts.Waker.Wake(ctx, target.SessionID); err != nil {
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

	w := &watch{claudeDir: h.opts.Source.ClaudeDir, sessionID: target.SessionID}
	w.f = openFollower(w.claudeDir, w.sessionID)
	err = conn.Do(ctx, func(t *attach.Term) error {
		if err := checkNoPicker(t); err != nil {
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
	ok, queued := w.wait(ctx, body.Text, h.opts.DeliveryTimeout)
	if !ok {
		return agent.Fail(req, protocol.CodeNotDelivered, fmt.Sprintf("消息发出后 %g 秒内没有出现在会话里，可能没有送达", h.opts.DeliveryTimeout.Seconds()))
	}
	return agent.Reply(req, protocol.ChatSendResponse{Queued: queued})
}

// checkMessage validates a chat.send body and returns why it is refused.
func checkMessage(b protocol.ChatSendRequest) string {
	switch {
	case b.SessionID == "":
		return errNoSessionID.msg
	case strings.TrimSpace(b.Text) == "" && len(b.Images) == 0:
		return "消息是空的"
	case len(b.Images) > protocol.MaxImagesPerMessage:
		return fmt.Sprintf("一条消息最多附 %d 张图片", protocol.MaxImagesPerMessage)
	case isModelCommand(b.Text):
		return msgModelCommand
	}
	for _, im := range b.Images {
		if _, ok := imageExt[im.MediaType]; !ok {
			return "只支持 PNG 和 JPEG 图片"
		}
		if len(im.Data) == 0 || len(im.Data) > protocol.MaxImageBytes {
			return fmt.Sprintf("图片不能为空，也不能超过 %d MB", protocol.MaxImageBytes>>20)
		}
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

// wait reports whether text showed up within timeout, as a user item,
// queued or not, or for a slash command as a command item or as an
// enqueue record without text, which is how Claude Code queues one; and
// whether it waits in the queue.
func (w *watch) wait(ctx context.Context, text string, timeout time.Duration) (ok, queued bool) {
	deadline := time.Now().Add(timeout)
	command := strings.HasPrefix(strings.TrimSpace(text), "/")
	commands := 0
	if w.f != nil {
		commands = w.f.QueuedCommands()
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
				return true, it.Queued
			}
		}
		if command && w.f != nil && w.f.QueuedCommands() > commands {
			return true, true
		}
		if time.Now().After(deadline) {
			return false, false
		}
		select {
		case <-ctx.Done():
			return false, false
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
		name, _, _ := strings.Cut(text, " ")
		return strings.HasPrefix(text, "/") && it.Command != nil && it.Command.Name == name
	}
	return false
}
