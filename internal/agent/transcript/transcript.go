// Package transcript turns a Claude Code session transcript
// (~/.claude/projects/<dir>/<sessionId>.jsonl) into chat items (design D6).
//
// The transcript is only ever read. Formats are those of Claude Code
// 2.1.323; record types this package does not know are ignored.
package transcript

import (
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"path"
	"regexp"
	"strings"

	"clauderc/internal/protocol"
)

// ItemCommand is the kind of a slash command item. It is internal to the
// agent: the protocol has no such kind yet.
const ItemCommand protocol.ItemKind = "command"

// Item is one chat item plus what the protocol cannot carry yet.
type Item struct {
	protocol.ChatItem
	// Queued marks a message sent while the session was busy that Claude
	// Code still holds in its queue.
	Queued bool
	// Command is set for ItemCommand items.
	Command *Command
}

// Command is a slash command run in the session, e.g. /model.
type Command struct {
	// Name includes the leading slash.
	Name   string
	Args   string
	Output string
}

// Transcript is the parsed state of one transcript file. Feed it whole
// lines in file order; seqs are stable as the file grows.
type Transcript struct {
	items []Item
	// tools maps a tool_use id to its item index.
	tools map[string]int
	// queued lists indexes of queued items, oldest first.
	queued []int
	// command is the index of the command waiting for its output, or -1.
	command int
	// lastTool is the index of the latest tool_use item, or -1.
	lastTool int
	info     Info
}

// Parse reads a whole transcript.
func Parse(r io.Reader) (*Transcript, error) {
	t := newTranscript()
	br := bufio.NewReader(r)
	for {
		line, err := br.ReadBytes('\n')
		if len(line) > 0 && (err == nil || err == io.EOF) {
			t.feed(line)
		}
		if err == io.EOF {
			return t, nil
		}
		if err != nil {
			return nil, err
		}
	}
}

func newTranscript() *Transcript {
	return &Transcript{tools: map[string]int{}, command: -1, lastTool: -1}
}

// Items returns all items, oldest first.
func (t *Transcript) Items() []Item {
	return append([]Item(nil), t.items...)
}

// record is the part of a transcript line this package reads.
type record struct {
	Type      string          `json:"type"`
	Subtype   string          `json:"subtype"`
	IsMeta    bool            `json:"isMeta"`
	Message   *message        `json:"message"`
	Content   json.RawMessage `json:"content"`
	Operation string          `json:"operation"`
	// PermissionMode is set on permission-mode records.
	PermissionMode string      `json:"permissionMode"`
	Attachment     *attachment `json:"attachment"`
}

type attachment struct {
	Type     string `json:"type"`
	Identity struct {
		ModelID string `json:"modelId"`
	} `json:"identity"`
	Content string   `json:"content"`
	Names   []string `json:"names"`
}

type message struct {
	Content json.RawMessage `json:"content"`
}

type block struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
	Content   json.RawMessage `json:"content"`
	IsError   bool            `json:"is_error"`
	Source    *struct {
		Type      string `json:"type"`
		MediaType string `json:"media_type"`
		Data      string `json:"data"`
	} `json:"source"`
}

// feed applies one line and returns the indexes of items it added or
// changed. Malformed lines change nothing.
func (t *Transcript) feed(line []byte) []int {
	line = bytes.TrimSpace(line)
	if len(line) == 0 {
		return nil
	}
	var r record
	if json.Unmarshal(line, &r) != nil {
		return nil
	}
	switch r.Type {
	case "permission-mode":
		if r.PermissionMode != "" {
			t.info.PermissionMode = protocol.PermissionMode(r.PermissionMode)
		}
		return nil
	case "attachment":
		if r.Attachment != nil {
			t.attachment(r.Attachment)
		}
		return nil
	case "user":
		if r.IsMeta || r.Message == nil {
			return nil
		}
		return t.user(r.Message.Content)
	case "assistant":
		if r.Message == nil {
			return nil
		}
		return t.assistant(r.Message.Content)
	case "system":
		if r.Subtype != "local_command" {
			return nil
		}
		var s string
		if json.Unmarshal(r.Content, &s) != nil {
			return nil
		}
		return t.commandText(s)
	case "queue-operation":
		var s string
		_ = json.Unmarshal(r.Content, &s)
		return t.queueOperation(r.Operation, userText(s))
	}
	return nil
}

// queueOperation follows Claude Code's input queue. A message enqueued
// while the session is busy shows as queued until its user record
// arrives; popAll, on interrupt, puts every queued message back into the
// input box and remove drops one, so those items are removed.
func (t *Transcript) queueOperation(op, text string) []int {
	switch op {
	case "enqueue":
		if text == "" {
			return nil
		}
		i := t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemUser, Text: text}, Queued: true})
		t.queued = append(t.queued, i)
		return []int{i}
	case "popAll":
		changed := t.queued
		for _, i := range changed {
			t.remove(i)
		}
		t.queued = nil
		return changed
	case "remove":
		for k, i := range t.queued {
			if t.items[i].Text == text {
				t.queued = append(t.queued[:k:k], t.queued[k+1:]...)
				t.remove(i)
				return []int{i}
			}
		}
	}
	return nil
}

// remove turns item i into a removed item, keeping its seq.
func (t *Transcript) remove(i int) {
	t.items[i] = Item{ChatItem: protocol.ChatItem{Seq: t.items[i].Seq, Kind: protocol.ItemRemoved}}
}

// isInterruptMarker reports whether text is the note Claude Code records
// when the user interrupts a turn, e.g. "[Request interrupted by user]"
// or "[Request interrupted by user for tool use]".
func isInterruptMarker(text string) bool {
	return strings.HasPrefix(text, "[Request interrupted by user") && strings.HasSuffix(text, "]")
}

func (t *Transcript) add(it Item) int {
	it.Seq = int64(len(t.items) + 1)
	t.items = append(t.items, it)
	return len(t.items) - 1
}

func (t *Transcript) user(content json.RawMessage) []int {
	var s string
	if json.Unmarshal(content, &s) == nil {
		if strings.HasPrefix(s, "<command-name>") || strings.HasPrefix(s, "<local-command-") {
			return t.commandText(s)
		}
		if text := userText(s); isInterruptMarker(text) {
			return []int{t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemInterrupted}})}
		}
		return t.userMessage(userText(s), nil)
	}
	var blocks []block
	if json.Unmarshal(content, &blocks) != nil {
		return nil
	}
	var changed []int
	var texts []string
	var images []protocol.Image
	for _, b := range blocks {
		switch b.Type {
		case "text":
			if isInterruptMarker(strings.TrimSpace(b.Text)) {
				changed = append(changed, t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemInterrupted}}))
				continue
			}
			texts = append(texts, b.Text)
		case "image":
			if b.Source == nil || b.Source.Type != "base64" {
				continue
			}
			data, err := base64.StdEncoding.DecodeString(b.Source.Data)
			if err != nil {
				continue
			}
			images = append(images, protocol.Image{MediaType: b.Source.MediaType, Data: data})
		case "tool_result":
			i, ok := t.tools[b.ToolUseID]
			if !ok {
				continue
			}
			tc := *t.items[i].Tool
			tc.Result = &protocol.ToolResult{Content: flatten(b.Content), IsError: b.IsError}
			t.items[i].Tool = &tc
			changed = append(changed, i)
		}
	}
	if text := userText(strings.Join(texts, "\n")); text != "" || len(images) > 0 {
		changed = append(changed, t.userMessage(text, images)...)
	}
	return changed
}

// userMessage adds a user bubble, or turns the matching queued item into
// it, keeping that item's seq.
func (t *Transcript) userMessage(text string, images []protocol.Image) []int {
	if text == "" && len(images) == 0 {
		return nil
	}
	for k, i := range t.queued {
		if t.items[i].Text == text {
			t.queued = append(t.queued[:k:k], t.queued[k+1:]...)
			t.items[i].Queued = false
			t.items[i].Images = images
			return []int{i}
		}
	}
	return []int{t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemUser, Text: text, Images: images}})}
}

var (
	commandName   = regexp.MustCompile(`(?s)<command-name>(.*?)</command-name>`)
	commandArgs   = regexp.MustCompile(`(?s)<command-args>(.*?)</command-args>`)
	commandOutput = regexp.MustCompile(`(?s)<local-command-(?:stdout|stderr)>(.*?)</local-command-(?:stdout|stderr)>`)
)

// commandText handles the <command-name> and <local-command-stdout> parts
// of a slash command, written as user or system records. The caveat part
// is an isMeta record and never gets here.
func (t *Transcript) commandText(s string) []int {
	if m := commandName.FindStringSubmatch(s); m != nil {
		c := &Command{Name: strings.TrimSpace(m[1])}
		if a := commandArgs.FindStringSubmatch(s); a != nil {
			c.Args = strings.TrimSpace(a[1])
		}
		t.command = t.add(Item{ChatItem: protocol.ChatItem{Kind: ItemCommand}, Command: c})
		return []int{t.command}
	}
	if m := commandOutput.FindStringSubmatch(s); m != nil && t.command >= 0 {
		i := t.command
		c := *t.items[i].Command
		c.Output = strings.TrimSpace(m[1])
		t.items[i].Command = &c
		t.command = -1
		return []int{i}
	}
	return nil
}

func (t *Transcript) assistant(content json.RawMessage) []int {
	var blocks []block
	if json.Unmarshal(content, &blocks) != nil {
		return nil
	}
	var changed []int
	for _, b := range blocks {
		switch b.Type {
		case "text":
			if strings.TrimSpace(b.Text) == "" {
				continue
			}
			changed = append(changed, t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemAssistant, Text: b.Text}}))
		case "tool_use":
			input := b.Input
			if len(input) == 0 {
				input = json.RawMessage(`{}`)
			}
			i := t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemTool, Tool: &protocol.ToolCall{
				ToolUseID: b.ID,
				ToolName:  b.Name,
				Summary:   summary(b.Name, input),
				Input:     input,
			}}})
			t.tools[b.ID] = i
			t.lastTool = i
			changed = append(changed, i)
		}
	}
	return changed
}

// summary is the one-line text of a tool row (design D6).
func summary(name string, input json.RawMessage) string {
	var in struct {
		Command  string `json:"command"`
		FilePath string `json:"file_path"`
	}
	_ = json.Unmarshal(input, &in)
	switch {
	case name == "Bash" && in.Command != "":
		return "运行 " + firstLine(in.Command)
	case name == "Read" && in.FilePath != "":
		return "读取 " + path.Base(in.FilePath)
	}
	return name
}

func firstLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

var pasted = regexp.MustCompile(`(?s)<pasted_content id="([^"]*)">\n(.*?)\n</pasted_content id="([^"]*)">`)

// userText removes the <pasted_content> wrapper of multi-line messages.
func userText(s string) string {
	s = pasted.ReplaceAllString(s, "$2")
	return strings.TrimSpace(s)
}

// flatten turns tool_result content, a string or a list of blocks, into
// text; non-text blocks are left out.
func flatten(raw json.RawMessage) string {
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var blocks []block
	if json.Unmarshal(raw, &blocks) != nil {
		return ""
	}
	var texts []string
	for _, b := range blocks {
		if b.Type == "text" {
			texts = append(texts, b.Text)
		}
	}
	return strings.Join(texts, "\n")
}
