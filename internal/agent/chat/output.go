package chat

import (
	"context"
	"encoding/json"
	"strings"
	"unicode/utf8"

	"clauderc/internal/agent"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

var (
	errNoToolUseID  = &opError{protocol.CodeInvalidParams, "缺少工具调用 id"}
	errToolNotFound = &opError{protocol.CodeNotFound, "会话记录里找不到这次工具调用"}
)

// truncated returns res with its text and its diff each cut to
// protocol.MaxToolOutputBytes, marked Truncated when anything was cut; res
// itself, shared with the transcript, is not changed.
func truncated(res *protocol.ToolResult) *protocol.ToolResult {
	out := *res
	if len(out.Content) > protocol.MaxToolOutputBytes {
		out.Content = cutText(out.Content, protocol.MaxToolOutputBytes)
		out.Truncated = true
	}
	if patch, cut := cutPatch(out.Patch, protocol.MaxToolOutputBytes); cut {
		out.Patch = patch
		out.Truncated = true
	}
	return &out
}

// cutText keeps the start of s within max bytes: up to the last line break
// when there is one in the second half, never in the middle of a character.
func cutText(s string, max int) string {
	s = s[:max]
	// Drop the bytes of a character cut in half.
	for len(s) > 0 {
		if r, size := utf8.DecodeLastRuneInString(s); r != utf8.RuneError || size > 1 {
			break
		}
		s = s[:len(s)-1]
	}
	if i := strings.LastIndexByte(s, '\n'); i >= max/2 {
		return s[:i+1]
	}
	return s
}

// cutPatch keeps whole lines of patch, hunk by hunk, while they fit in max
// bytes, a line counting its newline; the first line is kept even when it
// is too long, cut itself. It reports whether anything was left out.
func cutPatch(patch []protocol.Hunk, max int) ([]protocol.Hunk, bool) {
	size := 0
	for _, h := range patch {
		for _, l := range h.Lines {
			size += len(l) + 1
		}
	}
	if size <= max {
		return patch, false
	}
	var out []protocol.Hunk
	left := max
	for _, h := range patch {
		kept := h
		kept.Lines = nil
		for _, l := range h.Lines {
			if len(l)+1 > left {
				if len(out) == 0 && len(kept.Lines) == 0 {
					kept.Lines = []string{cutText(l, max)}
				}
				if len(kept.Lines) > 0 {
					out = append(out, kept)
				}
				return out, true
			}
			kept.Lines = append(kept.Lines, l)
			left -= len(l) + 1
		}
		out = append(out, kept)
	}
	return out, true
}

// allItems is the whole transcript of a session: the open chat's, or,
// without one, read from the file. A session with no transcript has none.
func (h *Handler) allItems(ctx context.Context, sessionID string) ([]transcript.Item, error) {
	target, err := h.lookup(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	h.mu.Lock()
	var f *transcript.Follower
	if s := h.chats[target.ID]; s != nil {
		s.mu.Lock()
		if s.sessionID == target.SessionID {
			f = s.follower
		}
		s.mu.Unlock()
	}
	h.mu.Unlock()
	if f == nil {
		f = openFollower(h.opts.Source.ClaudeDir, target.SessionID)
	}
	if f == nil {
		return nil, nil
	}
	items, _ := f.Page(0, int(^uint(0)>>1))
	return items, nil
}

// toolOutput answers chat.toolOutput with the whole result of one tool
// call, read from the transcript of the open chat or, without one, from
// the transcript file.
func (h *Handler) toolOutput(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatToolOutputRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	if body.SessionID == "" {
		return failWith(req, errNoSessionID)
	}
	if body.ToolUseID == "" {
		return failWith(req, errNoToolUseID)
	}
	items, err := h.allItems(ctx, body.SessionID)
	if err != nil {
		return failWith(req, err)
	}
	for _, ci := range convert(items, false) {
		if ci.Tool != nil && ci.Tool.ToolUseID == body.ToolUseID && ci.Tool.Result != nil {
			return agent.Reply(req, protocol.ChatToolOutputResponse{ToolResult: *ci.Tool.Result})
		}
	}
	return failWith(req, errToolNotFound)
}
