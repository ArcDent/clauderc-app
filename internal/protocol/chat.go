package protocol

import (
	"encoding/json"
	"time"
)

// Image limits for chat.send (session-chat spec, design D2).
const (
	MaxImageBytes = 10 << 20
	// MaxImagesTotalBytes bounds a message's pictures together: base64 makes
	// them a third bigger, and with the text they must stay under
	// MaxMessageBytes, past which either side drops the connection.
	MaxImagesTotalBytes = 11 << 20
	// MaxImageEdge is the longest side, in pixels, after the phone resizes.
	MaxImageEdge = 2048
)

// ChatPageSize is how many items one chat.open page holds (design D6).
const ChatPageSize = 50

// MaxToolOutputBytes bounds a tool result's text, and separately its diff,
// in chat.open and chat.items; the rest is fetched with chat.toolOutput.
const MaxToolOutputBytes = 8 << 10

// ItemKind is the kind of a chat item (design D6).
type ItemKind string

const (
	ItemUser      ItemKind = "user"
	ItemAssistant ItemKind = "assistant"
	ItemTool      ItemKind = "tool"
	// ItemNotice is a line of grey text that is not a bubble, such as a
	// background task notification. Text is the line; a notice with a
	// Title, only sent by another session, shows the title above the full
	// Text instead.
	ItemNotice ItemKind = "notice"
	// ItemInterrupted marks where the user interrupted a turn, the
	// "Interrupted" line Claude Code draws; it has no text.
	ItemInterrupted ItemKind = "interrupted"
	// ItemRemoved replaces an item that is no longer shown, such as a
	// queued message Claude Code put back into its input box. The phone
	// drops the item with the same Seq.
	ItemRemoved ItemKind = "removed"
)

// Image is an attached picture; Data is base64 in JSON.
type Image struct {
	MediaType string `json:"mediaType"`
	Data      []byte `json:"data"`
}

// ChatItem is one bubble or tool row. Seq increases along the transcript and
// starts at 1; a later item with the same Seq replaces the earlier one, e.g.
// when a tool row gets its result.
type ChatItem struct {
	Seq  int64    `json:"seq"`
	Kind ItemKind `json:"kind"`
	// Text is set for user and assistant items, and for the body of a
	// notice.
	Text string `json:"text,omitempty"`
	// Title is set for a notice from another session, e.g.
	// "clauderc 发来"; the phone shows it as a grey line above Text.
	Title string `json:"title,omitempty"`
	// Images is set for user items with pictures.
	Images []Image `json:"images,omitempty"`
	// Tool is set for tool items.
	Tool *ToolCall `json:"tool,omitempty"`
	// Queued marks a user item still waiting in Claude Code's queue.
	Queued bool `json:"queued,omitempty"`
	// UUID is set for a user item once its record is in the transcript:
	// the record's uuid, which chat.rewind names.
	UUID string `json:"uuid,omitempty"`
}

// ToolCall is a tool_use paired with its tool_result.
type ToolCall struct {
	ToolUseID string `json:"toolUseId"`
	ToolName  string `json:"toolName"`
	// Summary is the one-line row text, e.g. "运行 ls".
	Summary string          `json:"summary"`
	Input   json.RawMessage `json:"input"`
	// Result is absent until the tool_result arrives.
	Result *ToolResult `json:"result,omitempty"`
	// Agent is set for an Agent call: the subagent it started.
	Agent *Subagent `json:"agent,omitempty"`
}

// Subagent is a subagent a session started with the Agent tool.
type Subagent struct {
	// ID names its transcript; chat.open with it as AgentID opens it.
	// Empty until Claude Code reports it.
	ID          string `json:"id,omitempty"`
	Type        string `json:"type"`
	Description string `json:"description"`
	// Running is false once it finished, failed or was stopped.
	Running bool `json:"running"`
	// StartedAt is when the Agent call was made.
	StartedAt time.Time `json:"startedAt"`
}

// ToolResult is the outcome of a tool call, flattened to text.
type ToolResult struct {
	Content string `json:"content"`
	IsError bool   `json:"isError"`
	// Patch is set for Edit, MultiEdit and Write: the changed hunks, a new
	// file being one hunk of added lines. The whole file is never sent.
	Patch []Hunk `json:"patch,omitempty"`
	// Lines is set for Read of a text file: how many lines it read.
	Lines int `json:"lines,omitempty"`
	// Truncated marks a result whose Content or Patch was cut to
	// MaxToolOutputBytes; chat.toolOutput returns all of it.
	Truncated bool `json:"truncated,omitempty"`
	// Images are the pictures in the result, such as a Read of a PNG.
	Images []ToolImage `json:"images,omitempty"`
}

// ThumbEdge is the longest side, in pixels, of a ToolImage's thumbnail.
const ThumbEdge = 360

// ToolImage is a picture in a tool result, or one Claude sent with
// `clauderc show`. The phone shows Thumb and fetches the picture itself
// with chat.image by ID.
type ToolImage struct {
	ID        string `json:"id"`
	MediaType string `json:"mediaType"`
	// Name is the file name a picture sent with `clauderc show` had.
	Name string `json:"name,omitempty"`
	// Width and Height are 0 for a format the agent cannot read, such as WebP.
	Width  int `json:"width,omitempty"`
	Height int `json:"height,omitempty"`
	// Size is the picture's length in bytes.
	Size int `json:"size"`
	// Thumb is a JPEG at most ThumbEdge pixels on its longest side, or the
	// picture itself when it is no bigger; empty when the agent cannot read
	// the format.
	Thumb []byte `json:"thumb,omitempty"`
	// Data is the picture itself, kept by the agent for chat.image.
	Data []byte `json:"-"`
}

// ChatImageRequest asks for a picture of a tool result by its ID.
type ChatImageRequest struct {
	SessionID string `json:"sessionId"`
	ID        string `json:"id"`
}

// ChatImageResponse is the picture as it was, at most MaxImageBytes.
type ChatImageResponse struct {
	MediaType string `json:"mediaType"`
	Data      []byte `json:"data"`
}

// ChatToolOutputRequest asks for the whole result of one tool call.
type ChatToolOutputRequest struct {
	SessionID string `json:"sessionId"`
	ToolUseID string `json:"toolUseId"`
}

// ChatToolOutputResponse is the whole result, never truncated.
type ChatToolOutputResponse struct {
	ToolResult
}

// Hunk is one block of a diff, as in Claude Code's structuredPatch. Each
// line starts with " " (unchanged), "-" (removed) or "+" (added); OldStart
// and NewStart are 1-based line numbers, 0 for a side with no lines.
type Hunk struct {
	OldStart int      `json:"oldStart"`
	OldLines int      `json:"oldLines"`
	NewStart int      `json:"newStart"`
	NewLines int      `json:"newLines"`
	Lines    []string `json:"lines"`
}

// PermissionMode is Claude Code's own permission-mode value, passed through.
// Only default, acceptEdits, plan and auto can be switched to (design D7).
type PermissionMode string

const (
	PermissionDefault     PermissionMode = "default"
	PermissionAcceptEdits PermissionMode = "acceptEdits"
	PermissionPlan        PermissionMode = "plan"
	PermissionAuto        PermissionMode = "auto"
	PermissionBypass      PermissionMode = "bypassPermissions"
)

// ChatStatus is the live state shown in the chat header.
type ChatStatus struct {
	State SessionState `json:"state"`
	// Model is the model id from the transcript.
	Model          string         `json:"model"`
	PermissionMode PermissionMode `json:"permissionMode"`
	// UnknownDialog is set while the session is blocked on something on
	// screen that is neither its input nor a dialog the agent knows, such
	// as a panel; the phone offers the terminal view.
	UnknownDialog bool `json:"unknownDialog,omitempty"`
	// Activity is Claude Code's status line while the session works, as
	// on screen without its turning glyph and key hints, e.g.
	// "Whirring… (20s · ↓ 2.4k tokens)". Only a chat with a hidden attach
	// has it; the phone falls back to its own timer.
	Activity string `json:"activity,omitempty"`
	// Partial is the reply Claude Code has drawn on screen so far and not
	// yet written to the transcript. It is screen text: markdown already
	// rendered, wrapped lines joined. The phone shows it until the reply
	// arrives as an item.
	Partial string `json:"partial,omitempty"`
	// Limit is set while the session waits out a plan usage limit.
	Limit *Limit `json:"limit,omitempty"`
}

// Limit is the plan usage limit a session ran into: its transcript ends
// on Claude Code's "You've hit your 5-hour limit · resets 10:40pm
// (America/Denver)". It is set only when the reset time is known, until
// the limit is lifted or, with AutoResume, until that message is sent.
type Limit struct {
	// Window is "fiveHour" or "weekly", as in usage.get; absent when
	// Claude Code names another limit.
	Window string `json:"window,omitempty"`
	// Text is Claude Code's message.
	Text     string    `json:"text"`
	ResetsAt time.Time `json:"resetsAt"`
	// AutoResume is set while the agent is to send a message by itself
	// once the limit is lifted.
	AutoResume *AutoResume `json:"autoResume,omitempty"`
}

// AutoResume is a message the agent sends to a session once its limit is
// lifted, unless something else was sent in the meantime.
type AutoResume struct {
	Text string `json:"text"`
	// At is when it goes out: a minute after Limit.ResetsAt.
	At time.Time `json:"at"`
}

// ChatAutoResumeRequest turns the message sent once the session's limit
// is lifted on, with Text, or off.
type ChatAutoResumeRequest struct {
	SessionID string `json:"sessionId"`
	On        bool   `json:"on"`
	Text      string `json:"text,omitempty"`
}

// ChatAutoResumeResponse answers chat.autoResume with the limit as it is
// now; null when the session no longer waits on one.
type ChatAutoResumeResponse struct {
	Limit *Limit `json:"limit"`
}

// RewindRestore is what chat.rewind takes back, as the /rewind panel
// offers it.
type RewindRestore string

const (
	// RewindBoth is "Restore code and conversation".
	RewindBoth RewindRestore = "both"
	// RewindConversation is "Restore conversation": the code stays.
	RewindConversation RewindRestore = "conversation"
	// RewindCode is "Restore code": the conversation stays.
	RewindCode RewindRestore = "code"
)

// ChatRewindRequest rewinds the session to before the user item whose
// UUID it names. The agent goes through Claude Code's /rewind panel and
// presses Enter only when the line under the cursor matches the message
// and Restore is among the panel's choices. Taking the conversation back
// puts the message into the phone's input box; the response is empty.
type ChatRewindRequest struct {
	SessionID string        `json:"sessionId"`
	UUID      string        `json:"uuid"`
	Restore   RewindRestore `json:"restore"`
}

// PendingKind tells a permission request from a question (design D6).
type PendingKind string

const (
	PendingPermission PendingKind = "permission"
	PendingQuestion   PendingKind = "question"
	// PendingDialog is a Claude Code dialog that holds the session, such
	// as the approval of a new project MCP server. Questions holds one
	// single-choice question: the dialog's title and its options, as on
	// screen. It is answered like a question, with one selected option.
	PendingDialog PendingKind = "dialog"
)

// Pending is what the session is waiting for the user to decide.
type Pending struct {
	// ToolUseID must be echoed back in chat.answer.
	ToolUseID string      `json:"toolUseId"`
	Kind      PendingKind `json:"kind"`
	// ToolName and Input are set for permission requests.
	ToolName string          `json:"toolName,omitempty"`
	Input    json.RawMessage `json:"input,omitempty"`
	// Questions is set for questions.
	Questions []Question `json:"questions,omitempty"`
}

// Question is one AskUserQuestion entry.
type Question struct {
	Question    string   `json:"question"`
	Header      string   `json:"header"`
	MultiSelect bool     `json:"multiSelect"`
	Options     []Option `json:"options"`
}

// Option is one choice of a question.
type Option struct {
	Label       string `json:"label"`
	Description string `json:"description"`
}

// ChatOpenRequest opens a chat, or fetches an older page when Before is set.
type ChatOpenRequest struct {
	SessionID string `json:"sessionId"`
	// Before is the Seq of the oldest item already loaded; omit for the
	// latest page.
	Before int64 `json:"before,omitempty"`
	// AgentID opens the chat of the session's subagent with that
	// Subagent.ID instead, read only: its first item is the task the
	// session gave it, a notice titled 任务; its state is working while it
	// runs, idle once it ended; it has no pending prompt. Its events carry
	// the same AgentID.
	AgentID string `json:"agentId,omitempty"`
}

// ChatOpenResponse answers chat.open with up to ChatPageSize items, oldest
// first, plus the current status and pending prompt.
type ChatOpenResponse struct {
	Items   []ChatItem `json:"items"`
	HasMore bool       `json:"hasMore"`
	ChatStatus
	// Pending is null when nothing is pending.
	Pending *Pending `json:"pending"`
}

// ChatCloseRequest closes a chat, a subagent's when AgentID is set.
type ChatCloseRequest struct {
	SessionID string `json:"sessionId"`
	AgentID   string `json:"agentId,omitempty"`
}

// ChatSendRequest sends one user message; a stopped session is woken first.
type ChatSendRequest struct {
	SessionID string  `json:"sessionId"`
	Text      string  `json:"text"`
	Images    []Image `json:"images,omitempty"`
}

// ChatSendResponse answers chat.send once the message shows up in the
// transcript.
type ChatSendResponse struct {
	// Queued is set when the message waits in Claude Code's queue until
	// the current turn ends.
	Queued bool `json:"queued,omitempty"`
}

// Decision answers a permission request.
type Decision string

const (
	DecisionAllow Decision = "allow"
	DecisionDeny  Decision = "deny"
)

// Answer answers one question: chosen option indexes, or a custom text.
type Answer struct {
	// Selected holds zero-based indexes into Question.Options.
	Selected []int  `json:"selected,omitempty"`
	Custom   string `json:"custom,omitempty"`
}

// ChatAnswerRequest answers the pending prompt identified by ToolUseID.
// Decision is set for permission requests; Answers, one per question in
// order, for questions.
type ChatAnswerRequest struct {
	SessionID string   `json:"sessionId"`
	ToolUseID string   `json:"toolUseId"`
	Decision  Decision `json:"decision,omitempty"`
	Answers   []Answer `json:"answers,omitempty"`
}

// ChatInterruptRequest interrupts the current turn.
type ChatInterruptRequest struct {
	SessionID string `json:"sessionId"`
}

// ChatModelsRequest asks for the models of the session's /model picker.
type ChatModelsRequest struct {
	SessionID string `json:"sessionId"`
}

// ModelOption is one entry of the /model picker.
type ModelOption struct {
	// ID is opaque; pass it back unchanged as ChatSetModelRequest.Model.
	ID    string `json:"id"`
	Label string `json:"label"`
	// Description may be empty.
	Description string `json:"description"`
	// Current marks the session's model; exactly one entry has it.
	Current bool `json:"current"`
}

// ChatModelsResponse answers chat.models in picker order.
type ChatModelsResponse struct {
	Models []ModelOption `json:"models"`
}

// ChatSetModelRequest switches the model for this session only; Model is a
// ModelOption.ID from chat.models.
type ChatSetModelRequest struct {
	SessionID string `json:"sessionId"`
	Model     string `json:"model"`
}

// ChatSetPermissionModeRequest switches the permission mode.
type ChatSetPermissionModeRequest struct {
	SessionID string         `json:"sessionId"`
	Mode      PermissionMode `json:"mode"`
}

// ChatCommandsRequest asks for the slash commands of a session.
type ChatCommandsRequest struct {
	SessionID string `json:"sessionId"`
}

// Command is a built-in slash command or a skill.
type Command struct {
	// Name has no leading slash.
	Name        string `json:"name"`
	Description string `json:"description"`
	// Panel marks a built-in that opens a panel: the phone sends it as is
	// and opens the terminal view. chat.send confirms it once it left the
	// input, as Claude Code records it only when the panel closes.
	Panel bool `json:"panel,omitempty"`
}

// ChatCommandsResponse answers chat.commands.
type ChatCommandsResponse struct {
	Commands []Command `json:"commands"`
}

// ChatItemsEvent carries new or updated items of an open chat; AgentID is
// set for a subagent's chat.
type ChatItemsEvent struct {
	SessionID string     `json:"sessionId"`
	AgentID   string     `json:"agentId,omitempty"`
	Items     []ChatItem `json:"items"`
}

// ChatPendingEvent reports the pending prompt; Pending is null once cleared.
type ChatPendingEvent struct {
	SessionID string   `json:"sessionId"`
	Pending   *Pending `json:"pending"`
}

// ChatStateEvent reports a change of state, model or permission mode;
// AgentID is set for a subagent's chat.
type ChatStateEvent struct {
	SessionID string `json:"sessionId"`
	AgentID   string `json:"agentId,omitempty"`
	ChatStatus
}
