package transcript

import (
	"encoding/base64"
	"encoding/json"
	"strconv"

	"clauderc/internal/agent/picture"
	"clauderc/internal/protocol"
)

// toolImages returns the pictures of a tool result: the image blocks of
// its content, numbered <toolUseID>.<n>, and for a command the pictures
// Claude sent with `clauderc show`, named by marker lines in its output.
func toolImages(tc *protocol.ToolCall, raw json.RawMessage, output string) []protocol.ToolImage {
	var out []protocol.ToolImage
	var blocks []block
	if json.Unmarshal(raw, &blocks) == nil {
		for _, b := range blocks {
			if b.Type != "image" || b.Source == nil || b.Source.Type != "base64" {
				continue
			}
			data, err := base64.StdEncoding.DecodeString(b.Source.Data)
			if err != nil {
				continue
			}
			out = append(out, picture.New(tc.ToolUseID+"."+strconv.Itoa(len(out)), b.Source.MediaType, data))
		}
	}
	if tc.ToolName == "Bash" {
		out = append(out, picture.Shown(picture.Dir(), output)...)
	}
	return out
}
