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
	"slices"
	"strings"
	"time"

	"clauderc/internal/protocol"
)

// ItemCommand is the kind of a slash command item. It is internal to the
// agent: the protocol has no such kind yet.
const ItemCommand protocol.ItemKind = "command"

// Item is one chat item plus what the protocol cannot carry yet. Its
// Queued marks a message sent while the session was busy that Claude Code
// still holds in its queue.
type Item struct {
	protocol.ChatItem
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
	// queuedCommands counts enqueue records without text, which is how
	// Claude Code queues a slash command.
	queuedCommands int
	// unknownCommands lists, oldest first, the slash commands Claude Code
	// turned down as unknown, such as a skill the session does not load.
	unknownCommands []string
	// unpaired lists the notices made from one side of a delivery that
	// may still see the same text from the other side, oldest first.
	unpaired []unpairedNotice
	// line counts the lines fed so far.
	line int
	// parents maps the uuid of each record seen to its parent's, leaf is
	// the latest record's, and prompts marks the user's messages among
	// them; see branch.
	parents map[string]string
	prompts map[string]bool
	leaf    string
	// src holds, for each item, the uuid of the record that added it, and
	// cur the uuid of the record being fed.
	src  []string
	cur  string
	info Info
	// agents follows the subagents of the Agent calls.
	agents agentLog
}

// QueuedCommands returns how many slash commands were queued so far.
func (t *Transcript) QueuedCommands() int {
	return t.queuedCommands
}

// UnknownCommands returns the names, with their slash, of the commands
// Claude Code turned down as unknown so far, oldest first.
func (t *Transcript) UnknownCommands() []string {
	return slices.Clone(t.unknownCommands)
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
	return &Transcript{tools: map[string]int{}, command: -1, lastTool: -1, parents: map[string]string{}, prompts: map[string]bool{}}
}

// Items returns all items, oldest first.
func (t *Transcript) Items() []Item {
	return append([]Item(nil), t.items...)
}

// record is the part of a transcript line this package reads.
type record struct {
	Type string `json:"type"`
	// UUID and ParentUUID place the record in the conversation tree; a
	// compact boundary has no parent but names the record it follows in
	// LogicalParentUUID. Sidechain records belong to a subagent.
	UUID              string          `json:"uuid"`
	ParentUUID        string          `json:"parentUuid"`
	LogicalParentUUID string          `json:"logicalParentUuid"`
	IsSidechain       bool            `json:"isSidechain"`
	Subtype           string          `json:"subtype"`
	IsMeta            bool            `json:"isMeta"`
	Message           *message        `json:"message"`
	Content           json.RawMessage `json:"content"`
	Operation         string          `json:"operation"`
	// Reason is why a queue-operation removed a message.
	Reason string `json:"reason"`
	// IsCompactSummary marks the record Claude Code writes in place of
	// the conversation after /compact.
	IsCompactSummary bool `json:"isCompactSummary"`
	// PermissionMode is set on permission-mode records.
	PermissionMode string      `json:"permissionMode"`
	Attachment     *attachment `json:"attachment"`
	// Origin tells who wrote a user record the user did not type, such as
	// a background task notification or another session.
	Origin origin `json:"origin"`
	// ToolUseResult is the structured result of the record's tool_result,
	// such as an Edit's hunks; see structured.
	ToolUseResult json.RawMessage `json:"toolUseResult"`
	// Timestamp is kept as text: a malformed one must not drop the record.
	Timestamp string `json:"timestamp"`
	// IsAPIErrorMessage and Error mark the synthetic assistant record of
	// a failed request; Error "rate_limit" may be a plan usage limit.
	IsAPIErrorMessage bool   `json:"isApiErrorMessage"`
	Error             string `json:"error"`
}

// origin is the origin of a user record or a queued command: a
// background task notification, or a message from another session (peer),
// which carries the sender's Name and the message Body.
type origin struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
	Body string `json:"body"`
	// SenderTaskID is set when a subagent sent the message; those reports
	// are not shown.
	SenderTaskID string `json:"senderTaskId"`
}

type attachment struct {
	Type     string `json:"type"`
	Identity struct {
		ModelID string `json:"modelId"`
	} `json:"identity"`
	Content string   `json:"content"`
	Names   []string `json:"names"`
	// Prompt is the queued command's text, a string; the notification
	// itself when CommandMode is task-notification.
	Prompt json.RawMessage `json:"prompt"`
	Origin origin          `json:"origin"`
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
	t.line++
	var r record
	if json.Unmarshal(line, &r) != nil {
		return nil
	}
	changed := t.branch(&r)
	t.cur = ""
	if !r.IsSidechain {
		t.cur = r.UUID
	}
	added := t.record(&r)
	t.stamp(added)
	changed = append(changed, added...)
	return append(changed, t.syncAgents(t.agents.feed(&r))...)
}

// record applies one parsed line and returns the indexes of items it added
// or changed.
func (t *Transcript) record(r *record) []int {
	switch r.Type {
	case "permission-mode":
		if r.PermissionMode != "" {
			t.info.PermissionMode = protocol.PermissionMode(r.PermissionMode)
		}
		return nil
	case "attachment":
		if r.Attachment != nil {
			return t.attachment(r.Attachment)
		}
		return nil
	case "user":
		if !r.IsMeta {
			t.info.Limit = nil
		}
		return t.userRecord(r)
	case "assistant":
		t.info.Limit = nil
		if r.Message == nil {
			return nil
		}
		if r.IsAPIErrorMessage && r.Error == "rate_limit" {
			at, _ := time.Parse(time.RFC3339Nano, r.Timestamp)
			if l, ok := parseLimit(flatten(r.Message.Content), at); ok {
				t.info.Limit = &l
			}
		}
		return t.assistant(r.Message.Content)
	case "system":
		if r.Subtype == "informational" {
			if name, ok := strings.CutPrefix(textOf(r.Content), "Unknown command: "); ok {
				t.unknownCommands = append(t.unknownCommands, strings.TrimSpace(name))
			}
			return nil
		}
		if r.Subtype != "local_command" {
			return nil
		}
		return t.commandText(textOf(r.Content))
	case "queue-operation":
		return t.queueOperation(r.Operation, r.Reason, textOf(r.Content))
	}
	return nil
}

// queueOperation follows Claude Code's input queue. A message enqueued
// while the session is busy shows as queued until its user record
// arrives, or until a remove with reason absorbed_mid_turn says the
// running turn took it in; popAll, on interrupt, puts every queued
// message back into the input box and any other remove drops one, so
// those items are removed.
func (t *Transcript) queueOperation(op, reason, raw string) []int {
	text := userText(raw)
	switch op {
	case "enqueue":
		if raw == "" {
			// A slash command: the record leaves out its text.
			t.queuedCommands++
			return nil
		}
		if text == "" || isTaskNotification(text) {
			// Only wrappers, or a notification: no queued item. The
			// notification's notice comes from the remove or the
			// attachment, or from a later user record.
			return nil
		}
		if strings.HasPrefix(text, "<bash-") {
			// A "!" command typed while busy runs at once; its input and
			// output wait in the queue for the user record repeating them.
			return t.bashCommand(text, true)
		}
		i := t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemUser, Text: text, Queued: true}})
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
		if i, ok := t.unqueue(text); ok {
			if reason != "absorbed_mid_turn" {
				t.remove(i)
			}
			// Otherwise delivered within the turn as a queued_command
			// attachment; no user record follows.
			return []int{i}
		}
		if reason != "absorbed_mid_turn" {
			return nil
		}
		// Taken in by the running turn with no queued item, which is how
		// a notification or a message from another session arrives.
		if isTaskNotification(text) {
			return t.pairedNotice(noticeFromRemove, text, "", taskNotificationText(text))
		}
		if strings.HasPrefix(text, "<cross-session-message") {
			title, body := peerNotice(origin{}, text)
			return t.pairedNotice(noticeFromRemove, text, title, body)
		}
	}
	return nil
}

// unqueue takes the queued item with the given text out of the queue and
// returns its index.
func (t *Transcript) unqueue(text string) (int, bool) {
	for k, i := range t.queued {
		if t.items[i].Text == text {
			t.queued = append(t.queued[:k:k], t.queued[k+1:]...)
			t.items[i].Queued = false
			return i, true
		}
	}
	return -1, false
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
	t.src = append(t.src, t.cur)
	return len(t.items) - 1
}

// notice adds a grey line item; title is empty for a one-line notice.
func (t *Transcript) notice(title, text string) []int {
	return []int{t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemNotice, Text: text, Title: title}})}
}

// userRecord handles a "user" line: a notice for what Claude Code wrote on
// its own, a user message otherwise.
func (t *Transcript) userRecord(r *record) []int {
	if r.Origin.Kind == "peer" {
		if r.Origin.SenderTaskID != "" {
			// A subagent's report, which the phone does not show.
			return nil
		}
		// Delivered while the session was idle; a busy session delivers
		// it as a queue remove and a queued_command attachment instead.
		text := ""
		if r.Message != nil {
			text = textOf(r.Message.Content)
		}
		return t.notice(peerNotice(r.Origin, text))
	}
	if r.IsCompactSummary {
		return t.notice("", "上下文已压缩")
	}
	if r.IsMeta {
		if r.Message == nil {
			return nil
		}
		return t.metaNotice(r.Message.Content)
	}
	if r.Message == nil {
		return nil
	}
	switch r.Origin.Kind {
	case "task-notification":
		return t.notice("", taskNotificationText(textOf(r.Message.Content)))
	case "":
		// The desktop client delivers a message from another session as
		// a plain user record after a system reminder.
		if text := userText(textOf(r.Message.Content)); strings.HasPrefix(text, "<cross-session-message") {
			return t.notice(peerNotice(origin{}, text))
		}
	}
	return t.user(r.Message.Content, r.ToolUseResult)
}

// metaNotice turns the cross-session notices Claude Code writes as isMeta
// records into grey lines; other isMeta records, such as skill bodies and
// image notes, are skipped.
func (t *Transcript) metaNotice(content json.RawMessage) []int {
	s := textOf(content)
	switch {
	case strings.HasPrefix(s, "[Cross-session idle notice]"):
		return t.notice("", idleNoticeText(s))
	case strings.HasPrefix(s, "[Cross-session delivery notice]"):
		return t.notice("", deliveryNoticeText(s))
	}
	return nil
}

// user handles a user record's content; toolUseResult is the record's
// structured result, which belongs to its tool_result when there is only
// one.
func (t *Transcript) user(content, toolUseResult json.RawMessage) []int {
	var s string
	if json.Unmarshal(content, &s) == nil {
		text := userText(s)
		switch {
		case strings.HasPrefix(text, "<bash-"):
			// A "!" command; one typed while busy is queued already.
			if i, ok := t.unqueue(text); ok {
				return []int{i}
			}
			return t.bashCommand(text, false)
		// A local command starts with <command-name>, a skill or custom
		// command with <command-message>.
		case strings.HasPrefix(text, "<command-name>") || strings.HasPrefix(text, "<command-message>") || strings.HasPrefix(text, "<local-command-"):
			return t.commandText(s)
		case isInterruptMarker(text):
			return []int{t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemInterrupted}})}
		}
		return t.userMessage(text, nil)
	}
	var blocks []block
	if json.Unmarshal(content, &blocks) != nil {
		return nil
	}
	var changed []int
	var texts []string
	var images []protocol.Image
	results := 0
	for _, b := range blocks {
		if b.Type == "tool_result" {
			results++
		}
	}
	for _, b := range blocks {
		switch b.Type {
		case "text":
			text := stripWrappers(b.Text)
			if text == "" {
				continue
			}
			if isInterruptMarker(strings.TrimSpace(text)) {
				changed = append(changed, t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemInterrupted}}))
				continue
			}
			texts = append(texts, text)
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
			content := flatten(b.Content)
			tc.Result = &protocol.ToolResult{Content: content, IsError: b.IsError, Images: toolImages(&tc, b.Content, content)}
			if results == 1 {
				structured(tc.Result, toolUseResult)
			}
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
	if i, ok := t.unqueue(text); ok {
		t.items[i].Images = images
		return []int{i}
	}
	return []int{t.add(Item{ChatItem: protocol.ChatItem{Kind: protocol.ItemUser, Text: text, Images: images}})}
}

var (
	commandName   = regexp.MustCompile(`(?s)<command-name>(.*?)</command-name>`)
	commandArgs   = regexp.MustCompile(`(?s)<command-args>(.*?)</command-args>`)
	commandOutput = regexp.MustCompile(`(?s)<local-command-(?:stdout|stderr)>(.*?)</local-command-(?:stdout|stderr)>`)
	// terminalEscape matches the color and OSC sequences command output
	// carries for the terminal, e.g. "\x1b[2m".
	terminalEscape = regexp.MustCompile("\x1b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\x07\x1b]*(?:\x07|\x1b\\\\))")
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
		return []int{t.setOutput(terminalOutput(m[1]))}
	}
	return nil
}

// setOutput gives the command waiting for its output that output, empty
// or not, and stops waiting, so later output cannot land on it. It
// returns the command's index.
func (t *Transcript) setOutput(out string) int {
	i := t.command
	c := *t.items[i].Command
	c.Output = out
	t.items[i].Command = &c
	t.command = -1
	return i
}

// terminalOutput is command output without its terminal escapes.
func terminalOutput(s string) string {
	return strings.TrimSpace(terminalEscape.ReplaceAllString(s, ""))
}

var (
	bashInput  = regexp.MustCompile(`(?s)<bash-input>(.*?)</bash-input>`)
	bashOutput = regexp.MustCompile(`(?s)<bash-(?:stdout|stderr)>(.*?)</bash-(?:stdout|stderr)>`)
)

// bashCommand handles a "!" command: <bash-input> holds the command line,
// <bash-stdout> and <bash-stderr> its output. They come in one record or
// in a later record; the output stays out of the chat until asked for.
// A queued command waits in the queue under the record's text, s.
func (t *Transcript) bashCommand(s string, queued bool) []int {
	var changed []int
	if m := bashInput.FindStringSubmatch(s); m != nil {
		c := &Command{Name: "!", Args: strings.TrimSpace(m[1])}
		// Text only matches the queued command with the user record that
		// repeats it; the phone gets the command, not this text.
		t.command = t.add(Item{ChatItem: protocol.ChatItem{Kind: ItemCommand, Text: s, Queued: queued}, Command: c})
		if queued {
			t.queued = append(t.queued, t.command)
		}
		changed = []int{t.command}
	}
	outputs := bashOutput.FindAllStringSubmatch(s, -1)
	if len(outputs) == 0 || t.command < 0 {
		return changed
	}
	var out []string
	for _, m := range outputs {
		if v := terminalOutput(m[1]); v != "" {
			out = append(out, v)
		}
	}
	if i := t.setOutput(strings.Join(out, "\n")); len(changed) == 0 {
		changed = []int{i}
	}
	return changed
}

var (
	taskSummary = regexp.MustCompile(`(?s)<summary>(.*?)</summary>`)
	taskEvent   = regexp.MustCompile(`(?s)<event>(.*?)</event>`)
	// crossSessionMessage matches the wrapper of a message from another
	// session; crossSessionName reads its sender, which the desktop
	// client writes as name instead of from-name.
	crossSessionMessage = regexp.MustCompile(`(?s)<cross-session-message\b[^>]*>(.*?)</cross-session-message>`)
	crossSessionName    = regexp.MustCompile(`<cross-session-message\b[^>]*?\b(?:from-)?name="([^"]*)"`)
	idleNoticeName      = regexp.MustCompile(`^\[Cross-session idle notice\] "([^"]*)"`)
	deliveryCount       = regexp.MustCompile(`^\[Cross-session delivery notice\] (\d+) of your messages`)
	// leadingWrapper matches a block the desktop client puts before what
	// the user typed: a system reminder, the artifact view context, which
	// has attributes, or a side sessions event, which may be all there is.
	leadingWrapper = regexp.MustCompile(`^\s*(?s:<system-reminder\b[^>]*>.*?</system-reminder>|<artifact-view-context\b[^>]*>.*?</artifact-view-context>|<side-sessions-event\b[^>]*>.*?</side-sessions-event>)`)
)

// textOf returns the string in raw, or "" for anything else.
func textOf(raw json.RawMessage) string {
	var s string
	_ = json.Unmarshal(raw, &s)
	return s
}

// stripWrappers removes the wrapper blocks at the start of s and keeps
// the text after them. Phone sessions are background sessions, but a
// transcript may still hold such records.
func stripWrappers(s string) string {
	for {
		loc := leadingWrapper.FindStringIndex(s)
		if loc == nil {
			return s
		}
		s = strings.TrimSpace(s[loc[1]:])
	}
}

// isTaskNotification reports whether s, trimmed, is a background task
// notification.
func isTaskNotification(s string) bool {
	return strings.HasPrefix(s, "<task-notification>") && strings.HasSuffix(s, "</task-notification>")
}

// forkSourceName finds the source's name in the notification Claude Code
// 2.1.327 puts where a /fork copy's own conversation starts: "... a
// session whose self-reported name is 'NAME'. The conversation up to ...".
var forkSourceName = regexp.MustCompile(`(?s)self-reported name is '(.*?)'\.\s+The conversation up to`)

// taskNotificationText is the grey line of a background task notification:
// its summary, with the first line of the event after it when there is one.
// A fork's start marker says which session the history above came from.
func taskNotificationText(s string) string {
	if strings.Contains(s, "<fork-source>") {
		if m := forkSourceName.FindStringSubmatch(s); m != nil && strings.TrimSpace(m[1]) != "" {
			return "以上来自「" + strings.TrimSpace(m[1]) + "」"
		}
		return "以上来自另一个会话"
	}
	summary := ""
	if m := taskSummary.FindStringSubmatch(s); m != nil {
		summary = strings.TrimSpace(m[1])
	}
	event := ""
	if m := taskEvent.FindStringSubmatch(s); m != nil {
		event = strings.TrimSpace(firstLine(m[1]))
	}
	switch {
	case summary != "" && event != "":
		return summary + "：" + event
	case summary != "":
		return summary
	case event != "":
		return event
	}
	return "后台任务通知"
}

// peerNotice is the title and body of a message from another session:
// the sender and body o names, or those of the cross-session-message
// wrapper in text where o leaves them out, as the desktop client and
// some idle deliveries do.
func peerNotice(o origin, text string) (title, body string) {
	name, body := o.Name, o.Body
	if m := crossSessionMessage.FindStringSubmatch(text); m != nil {
		if body == "" {
			body = strings.TrimSpace(m[1])
		}
		if n := crossSessionName.FindStringSubmatch(text); name == "" && n != nil {
			name = n[1]
		}
	}
	if name == "" {
		return "另一个会话发来", body
	}
	return name + " 发来", body
}

// idleNoticeText is 「名字」空闲了 for the usual idle notice; any other
// variant shows its first line. A session without a name is quoted by
// its socket address, which tells the user nothing.
func idleNoticeText(s string) string {
	if m := idleNoticeName.FindStringSubmatch(s); m != nil && strings.Contains(s, "is idle now") {
		if strings.HasPrefix(m[1], "uds:") {
			return "另一个会话空闲了"
		}
		return "「" + m[1] + "」空闲了"
	}
	return strings.TrimSpace(firstLine(s))
}

// deliveryNoticeText is the note that the other session held messages
// back, with their count when the notice names it.
func deliveryNoticeText(s string) string {
	if m := deliveryCount.FindStringSubmatch(s); m != nil {
		return "有 " + m[1] + " 条消息被对方暂扣，没送到"
	}
	return "有消息被对方暂扣，没送到"
}

// noticeSide is the record one delivery of a notification arrives in.
type noticeSide int

const (
	noticeFromRemove noticeSide = iota
	noticeFromAttachment
)

// noticePairWindow is how many lines apart the queue remove and the
// queued_command attachment of one delivery may be. They are usually
// next to each other; a burst of deliveries writes all the removes, then
// all the attachments, in the same order.
const noticePairWindow = 8

// unpairedNotice is a notice made from one side of a delivery.
type unpairedNotice struct {
	side noticeSide
	text string
	line int
}

// pairedNotice adds a notice for text, the userText of the record,
// unless it is the other side of a recent delivery that already made
// one: the queue remove and the queued_command attachment of a delivery
// usually both appear, but either may be missing. Repeats of the same
// text pair up oldest first.
func (t *Transcript) pairedNotice(side noticeSide, text, title, body string) []int {
	kept := t.unpaired[:0]
	paired := false
	for _, u := range t.unpaired {
		switch {
		case t.line-u.line > noticePairWindow:
			// The other side never came.
		case !paired && u.side != side && u.text == text:
			paired = true
		default:
			kept = append(kept, u)
		}
	}
	t.unpaired = kept
	if paired {
		return nil
	}
	t.unpaired = append(t.unpaired, unpairedNotice{side: side, text: text, line: t.line})
	return t.notice(title, body)
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
		Pattern  string `json:"pattern"`
	}
	_ = json.Unmarshal(input, &in)
	switch {
	case name == "Bash" && in.Command != "":
		return "运行 " + firstLine(in.Command)
	case name == "Read" && in.FilePath != "":
		return "读取 " + path.Base(in.FilePath)
	case (name == "Edit" || name == "MultiEdit") && in.FilePath != "":
		return "编辑 " + path.Base(in.FilePath)
	case name == "Write" && in.FilePath != "":
		return "写入 " + path.Base(in.FilePath)
	case (name == "Grep" || name == "Glob") && in.Pattern != "":
		return "搜索 " + firstLine(in.Pattern)
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

// userText removes the <pasted_content> wrapper of multi-line messages
// and the desktop client's wrappers in front of the text.
func userText(s string) string {
	s = pasted.ReplaceAllString(s, "$2")
	return strings.TrimSpace(stripWrappers(s))
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
