package chat

import (
	"context"
	"encoding/json"
	"strings"

	"clauderc/internal/agent"
	"clauderc/internal/agent/picture"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

var (
	errNoImageID     = &opError{protocol.CodeInvalidParams, "缺少图片编号"}
	errBadImageID    = &opError{protocol.CodeInvalidParams, "图片编号不对"}
	errImageNotFound = &opError{protocol.CodeNotFound, "会话记录里找不到这张图片"}
	errImageTooLarge = &opError{protocol.CodeInvalidParams, "图片太大，超过 10 MB，手机上看不了"}
)

// image answers chat.image with a picture as it was: one of a tool result,
// or one Claude sent with `clauderc show`, from the agent's own directory.
func (h *Handler) image(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatImageRequest
	if err := json.Unmarshal(req.Body, &body); err != nil {
		return failWith(req, errBadRequest)
	}
	if body.SessionID == "" {
		return failWith(req, errNoSessionID)
	}
	if body.ID == "" {
		return failWith(req, errNoImageID)
	}
	// A path or anything else that is no picture's ID goes no further.
	if !picture.ValidID(body.ID) {
		return failWith(req, errBadImageID)
	}
	if strings.HasPrefix(body.ID, picture.IDPrefix) {
		mediaType, data, err := picture.Open(picture.Dir(), body.ID)
		switch {
		case err != nil:
			return failWith(req, errImageNotFound)
		case len(data) > protocol.MaxImageBytes:
			return failWith(req, errImageTooLarge)
		}
		return agent.Reply(req, protocol.ChatImageResponse{MediaType: mediaType, Data: data})
	}
	var items []transcript.Item
	var err error
	if body.AgentID != "" {
		items, err = h.allAgentItems(ctx, body.SessionID, body.AgentID)
	} else {
		items, err = h.allItems(ctx, body.SessionID)
	}
	if err != nil {
		return failWith(req, err)
	}
	for _, it := range items {
		for _, img := range it.Images {
			if img.ID != body.ID {
				continue
			}
			data, mediaType := img.Original, img.OriginalMediaType
			if len(data) == 0 {
				data, mediaType = img.Data, img.MediaType
			}
			if len(data) > protocol.MaxImageBytes {
				return failWith(req, errImageTooLarge)
			}
			return agent.Reply(req, protocol.ChatImageResponse{MediaType: mediaType, Data: data})
		}
		if it.Tool == nil || it.Tool.Result == nil {
			continue
		}
		for _, img := range it.Tool.Result.Images {
			if img.ID != body.ID {
				continue
			}
			if len(img.Data) > protocol.MaxImageBytes {
				return failWith(req, errImageTooLarge)
			}
			return agent.Reply(req, protocol.ChatImageResponse{MediaType: img.MediaType, Data: img.Data})
		}
	}
	return failWith(req, errImageNotFound)
}
