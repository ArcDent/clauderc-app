package protocol

import "encoding/json"

// Image limits for chat.send (session-chat spec, design D2).
const (
	MaxImagesPerMessage = 5
	MaxImageBytes       = 10 << 20
	// MaxImageEdge is the longest side, in pixels, after the phone resizes.
	MaxImageEdge = 2048
)

// ChatPageSize is how many items one chat.open page holds (design D6).
const ChatPageSize = 50

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
}

// ToolResult is the outcome of a tool call, flattened to text.
type ToolResult struct {
	Content string `json:"content"`
	IsError bool   `json:"isError"`
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
}

// PendingKind tells a permission request from a question (design D6).
type PendingKind string

const (
	PendingPermission PendingKind = "permission"
	PendingQuestion   PendingKind = "question"
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

// ChatCloseRequest closes a chat.
type ChatCloseRequest struct {
	SessionID string `json:"sessionId"`
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
}

// ChatCommandsResponse answers chat.commands.
type ChatCommandsResponse struct {
	Commands []Command `json:"commands"`
}

// ChatItemsEvent carries new or updated items of an open chat.
type ChatItemsEvent struct {
	SessionID string     `json:"sessionId"`
	Items     []ChatItem `json:"items"`
}

// ChatPendingEvent reports the pending prompt; Pending is null once cleared.
type ChatPendingEvent struct {
	SessionID string   `json:"sessionId"`
	Pending   *Pending `json:"pending"`
}

// ChatStateEvent reports a change of state, model or permission mode.
type ChatStateEvent struct {
	SessionID string `json:"sessionId"`
	ChatStatus
}
