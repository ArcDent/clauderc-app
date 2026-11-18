package transcript

import (
	"encoding/json"
	"strings"

	"clauderc/internal/protocol"
)

// useResult is the part of a record's toolUseResult the phone draws on its
// own. Edit and Write also carry originalFile, the whole file before the
// change; it is left out here so that it is never decoded or sent.
type useResult struct {
	// Type is "create" or "update" for Write, "text" or "image" for Read.
	Type string `json:"type"`
	// Content is the new file of a Write.
	Content         string          `json:"content"`
	StructuredPatch []protocol.Hunk `json:"structuredPatch"`
	File            *struct {
		NumLines int `json:"numLines"`
	} `json:"file"`
}

// structured fills in res from raw, the record's toolUseResult: the hunks
// of an Edit, MultiEdit or Write, a new file being one hunk of added lines,
// and how many lines a Read of a text file read. Results that are a plain
// string, such as a failed Edit, and tools it does not know leave res as is.
func structured(res *protocol.ToolResult, raw json.RawMessage) {
	if len(raw) == 0 || raw[0] != '{' {
		return
	}
	var u useResult
	if json.Unmarshal(raw, &u) != nil {
		return
	}
	switch {
	case len(u.StructuredPatch) > 0:
		res.Patch = u.StructuredPatch
	case u.Type == "create" && u.Content != "":
		res.Patch = []protocol.Hunk{created(u.Content)}
	case u.Type == "text" && u.File != nil:
		res.Lines = u.File.NumLines
	}
}

// created is the hunk of a new file: all of its lines added.
func created(content string) protocol.Hunk {
	lines := strings.Split(strings.TrimSuffix(content, "\n"), "\n")
	for i, l := range lines {
		lines[i] = "+" + l
	}
	return protocol.Hunk{NewStart: 1, NewLines: len(lines), Lines: lines}
}
