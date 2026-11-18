package protocol

// Method names a request or event in Envelope.Method.
type Method string

// Methods answered by the relay itself (design D4).
const (
	MethodMachinesList Method = "machines.list"
	// EventMachineStatus is sent to every phone when a machine comes online,
	// goes offline or registers for the first time.
	EventMachineStatus Method = "machine.status"
	// EventClientGone is sent by the relay to a machine when a phone
	// connection closes; the envelope's Client names that connection.
	EventClientGone Method = "client.gone"
)

// Session list methods (design D3).
const (
	MethodSessionsSubscribe   Method = "sessions.subscribe"
	MethodSessionsUnsubscribe Method = "sessions.unsubscribe"
	EventSessionsChanged      Method = "sessions.changed"
)

// Chat methods (design D3).
const (
	MethodChatOpen              Method = "chat.open"
	MethodChatClose             Method = "chat.close"
	MethodChatSend              Method = "chat.send"
	MethodChatAnswer            Method = "chat.answer"
	MethodChatInterrupt         Method = "chat.interrupt"
	MethodChatModels            Method = "chat.models"
	MethodChatSetModel          Method = "chat.setModel"
	MethodChatSetPermissionMode Method = "chat.setPermissionMode"
	MethodChatCommands          Method = "chat.commands"
	MethodChatToolOutput        Method = "chat.toolOutput"
	MethodChatImage             Method = "chat.image"
	EventChatItems              Method = "chat.items"
	EventChatPending            Method = "chat.pending"
	EventChatState              Method = "chat.state"
)

// Session management methods (design D3).
const (
	MethodSessionCreate Method = "session.create"
	MethodSessionStop   Method = "session.stop"
	MethodSessionRemove Method = "session.remove"
	MethodDirsRecent    Method = "dirs.recent"
)

// Plan usage of the machine's Claude login.
const (
	MethodUsageGet Method = "usage.get"
)

// Git methods (design D3).
const (
	MethodGitStatus   Method = "git.status"
	MethodGitLog      Method = "git.log"
	MethodGitShow     Method = "git.show"
	MethodGitDiff     Method = "git.diff"
	MethodGitBranches Method = "git.branches"
	MethodGitStage    Method = "git.stage"
	MethodGitUnstage  Method = "git.unstage"
	MethodGitCommit   Method = "git.commit"
	MethodGitPull     Method = "git.pull"
	MethodGitPush     Method = "git.push"
	MethodGitSwitch   Method = "git.switch"
	MethodDirBusy     Method = "dir.busy"
)
