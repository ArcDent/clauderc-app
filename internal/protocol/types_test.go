package protocol_test

import (
	"bytes"
	"encoding/json"
	"testing"

	"clauderc/internal/protocol"
)

// bodyCase ties one method and message kind to its Go body type and a JSON
// literal written from the field names agreed for the wire contract.
type bodyCase struct {
	// method is held as any so the test can tell a protocol.Method constant
	// from an untyped string one.
	method any
	name   string // literal method name from design D3/D4
	kind   protocol.Kind
	body   func() any
	raw    string
}

const ts = `"2026-11-12T09:30:00+08:00"`

var bodyCases = []bodyCase{
	// Relay (design D4).
	{protocol.MethodMachinesList, "machines.list", protocol.KindRequest, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodMachinesList, "machines.list", protocol.KindResponse, func() any { return &protocol.MachinesListResponse{} },
		`{"machines":[{"id":"m1","name":"mac-mini","online":true,"lastSeenAt":` + ts + `,"agentVersion":"0.1.0","claudeVersion":"2.1.323"}]}`},
	{protocol.EventMachineStatus, "machine.status", protocol.KindEvent, func() any { return &protocol.Machine{} },
		`{"id":"m1","name":"mac-mini","online":false,"lastSeenAt":` + ts + `,"agentVersion":"0.1.0","claudeVersion":"2.1.323"}`},
	{protocol.EventClientGone, "client.gone", protocol.KindEvent, func() any { return &protocol.Empty{} }, `{}`},

	// Session list.
	{protocol.MethodSessionsSubscribe, "sessions.subscribe", protocol.KindRequest, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodSessionsSubscribe, "sessions.subscribe", protocol.KindResponse, func() any { return &protocol.SessionList{} },
		`{"sessions":[{"id":"a1","name":"fix","cwd":"/p/x","state":"pending","profileId":"api.anthropic.com","lastActiveAt":` + ts + `}],` +
			`"profiles":[{"id":"default","kind":"default"},{"id":"api.anthropic.com","kind":"endpoint"},{"id":"other","kind":"other"}]}`},
	{protocol.MethodSessionsUnsubscribe, "sessions.unsubscribe", protocol.KindRequest, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodSessionsUnsubscribe, "sessions.unsubscribe", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.EventSessionsChanged, "sessions.changed", protocol.KindEvent, func() any { return &protocol.SessionList{} },
		`{"sessions":[],"profiles":[{"id":"default","kind":"default"}]}`},

	// Chat.
	{protocol.MethodChatOpen, "chat.open", protocol.KindRequest, func() any { return &protocol.ChatOpenRequest{} },
		`{"sessionId":"a1","before":51}`},
	{protocol.MethodChatOpen, "chat.open", protocol.KindResponse, func() any { return &protocol.ChatOpenResponse{} },
		`{"items":[{"seq":51,"kind":"user","text":"hi"},{"seq":52,"kind":"assistant","text":"hello"},` +
			`{"seq":53,"kind":"tool","tool":{"toolUseId":"toolu_1","toolName":"Bash","summary":"运行 ls","input":{"command":"ls"},"result":{"content":"a.txt","isError":false}}}],` +
			`"hasMore":true,"state":"idle","model":"claude-opus-4-1","permissionMode":"default","pending":null}`},
	{protocol.MethodChatClose, "chat.close", protocol.KindRequest, func() any { return &protocol.ChatCloseRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodChatClose, "chat.close", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodChatSend, "chat.send", protocol.KindRequest, func() any { return &protocol.ChatSendRequest{} },
		`{"sessionId":"a1","text":"a\nb","images":[{"mediaType":"image/jpeg","data":"/9j/4A=="}]}`},
	{protocol.MethodChatSend, "chat.send", protocol.KindResponse, func() any { return &protocol.ChatSendResponse{} }, `{}`},
	{protocol.MethodChatSend, "chat.send", protocol.KindResponse, func() any { return &protocol.ChatSendResponse{} }, `{"queued":true}`},
	{protocol.MethodChatAnswer, "chat.answer", protocol.KindRequest, func() any { return &protocol.ChatAnswerRequest{} },
		`{"sessionId":"a1","toolUseId":"toolu_1","decision":"allow"}`},
	{protocol.MethodChatAnswer, "chat.answer", protocol.KindRequest, func() any { return &protocol.ChatAnswerRequest{} },
		`{"sessionId":"a1","toolUseId":"toolu_2","answers":[{"selected":[1]},{"custom":"都不喜欢"}]}`},
	{protocol.MethodChatAnswer, "chat.answer", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodChatInterrupt, "chat.interrupt", protocol.KindRequest, func() any { return &protocol.ChatInterruptRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodChatInterrupt, "chat.interrupt", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodChatSetModel, "chat.setModel", protocol.KindRequest, func() any { return &protocol.ChatSetModelRequest{} },
		`{"sessionId":"a1","model":"sonnet"}`},
	{protocol.MethodChatSetModel, "chat.setModel", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodChatSetPermissionMode, "chat.setPermissionMode", protocol.KindRequest, func() any { return &protocol.ChatSetPermissionModeRequest{} },
		`{"sessionId":"a1","mode":"acceptEdits"}`},
	{protocol.MethodChatSetPermissionMode, "chat.setPermissionMode", protocol.KindRequest, func() any { return &protocol.ChatSetPermissionModeRequest{} },
		`{"sessionId":"a1","mode":"auto"}`},
	{protocol.MethodChatSetPermissionMode, "chat.setPermissionMode", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodChatModels, "chat.models", protocol.KindRequest, func() any { return &protocol.ChatModelsRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodChatModels, "chat.models", protocol.KindResponse, func() any { return &protocol.ChatModelsResponse{} },
		`{"models":[{"id":"Opus 5.5","label":"Opus 5.5","description":"Custom Opus model (claude-opus-5-5[1M])","current":false},` +
			`{"id":"glm-5.3-flash[1M]","label":"glm-5.3-flash[1M]","description":"","current":true}]}`},
	{protocol.MethodChatCommands, "chat.commands", protocol.KindRequest, func() any { return &protocol.ChatCommandsRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodChatCommands, "chat.commands", protocol.KindResponse, func() any { return &protocol.ChatCommandsResponse{} },
		`{"commands":[{"name":"model","description":"Set the AI model"}]}`},
	{protocol.MethodChatToolOutput, "chat.toolOutput", protocol.KindRequest, func() any { return &protocol.ChatToolOutputRequest{} },
		`{"sessionId":"a1","toolUseId":"toolu_1"}`},
	{protocol.MethodChatToolOutput, "chat.toolOutput", protocol.KindResponse, func() any { return &protocol.ChatToolOutputResponse{} },
		`{"content":"1\tline","isError":false,"patch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}],"lines":1}`},
	{protocol.MethodChatImage, "chat.image", protocol.KindRequest, func() any { return &protocol.ChatImageRequest{} },
		`{"sessionId":"a1","id":"toolu_1.0"}`},
	{protocol.MethodChatImage, "chat.image", protocol.KindResponse, func() any { return &protocol.ChatImageResponse{} },
		`{"mediaType":"image/png","data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="}`},
	{protocol.EventChatItems, "chat.items", protocol.KindEvent, func() any { return &protocol.ChatItemsEvent{} },
		`{"sessionId":"a1","items":[{"seq":54,"kind":"tool","tool":{"toolUseId":"toolu_2","toolName":"Bash","summary":"运行 touch marker.txt","input":{"command":"touch marker.txt"}}}]}`},
	{protocol.EventChatItems, "chat.items", protocol.KindEvent, func() any { return &protocol.ChatItemsEvent{} },
		`{"sessionId":"a1","items":[{"seq":55,"kind":"user","text":"排队的消息","queued":true}]}`},
	{protocol.EventChatPending, "chat.pending", protocol.KindEvent, func() any { return &protocol.ChatPendingEvent{} },
		`{"sessionId":"a1","pending":{"toolUseId":"toolu_3","kind":"question","questions":[{"question":"你喜欢哪种颜色","header":"颜色","multiSelect":false,"options":[{"label":"红","description":""},{"label":"绿","description":""}]}]}}`},
	{protocol.EventChatPending, "chat.pending", protocol.KindEvent, func() any { return &protocol.ChatPendingEvent{} },
		`{"sessionId":"a1","pending":{"toolUseId":"toolu_2","kind":"permission","toolName":"Bash","input":{"command":"touch marker.txt"}}}`},
	{protocol.EventChatState, "chat.state", protocol.KindEvent, func() any { return &protocol.ChatStateEvent{} },
		`{"sessionId":"a1","state":"working","model":"claude-sonnet-4-5","permissionMode":"acceptEdits"}`},

	// Terminal view.
	{protocol.MethodTermOpen, "term.open", protocol.KindRequest, func() any { return &protocol.TermOpenRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodTermOpen, "term.open", protocol.KindResponse, func() any { return &protocol.TermScreen{} },
		`{"sessionId":"a1","lines":["❯ abc",""],"cursorRow":0,"cursorCol":5,"cursor":true}`},
	{protocol.MethodTermKey, "term.key", protocol.KindRequest, func() any { return &protocol.TermKeyRequest{} }, `{"sessionId":"a1","key":"up"}`},
	{protocol.MethodTermKey, "term.key", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodTermClose, "term.close", protocol.KindRequest, func() any { return &protocol.TermCloseRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodTermClose, "term.close", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.EventTermScreen, "term.screen", protocol.KindEvent, func() any { return &protocol.TermScreen{} },
		`{"sessionId":"a1","lines":["   ❯ ✔ chrome-devtools     26 tools"],"cursorRow":0,"cursorCol":3,"cursor":false}`},

	// Session management.
	{protocol.MethodSessionCreate, "session.create", protocol.KindRequest, func() any { return &protocol.SessionCreateRequest{} },
		`{"profileId":"anyrouter.top","cwd":"/p/x","name":"check","prompt":"检查测试是否通过"}`},
	{protocol.MethodSessionCreate, "session.create", protocol.KindResponse, func() any { return &protocol.SessionCreateResponse{} }, `{"sessionId":"b2"}`},
	{protocol.MethodSessionStop, "session.stop", protocol.KindRequest, func() any { return &protocol.SessionStopRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodSessionStop, "session.stop", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodSessionRemove, "session.remove", protocol.KindRequest, func() any { return &protocol.SessionRemoveRequest{} }, `{"sessionId":"a1"}`},
	{protocol.MethodSessionRemove, "session.remove", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodDirsRecent, "dirs.recent", protocol.KindRequest, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodDirsRecent, "dirs.recent", protocol.KindResponse, func() any { return &protocol.DirsRecentResponse{} }, `{"dirs":["/p/x","/p/y"]}`},

	// Plan usage.
	{protocol.MethodUsageGet, "usage.get", protocol.KindRequest, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodUsageGet, "usage.get", protocol.KindResponse, func() any { return &protocol.UsageResponse{} },
		`{"available":true,"fiveHour":{"utilization":12.5,"resetsAt":"2026-11-14T22:00:00Z"},"sevenDay":{"utilization":30},"fetchedAt":"2026-11-14T18:00:00Z","stale":"expired"}`},

	// Git.
	{protocol.MethodGitStatus, "git.status", protocol.KindRequest, func() any { return &protocol.GitStatusRequest{} }, `{"dir":"/p/x"}`},
	{protocol.MethodGitStatus, "git.status", protocol.KindResponse, func() any { return &protocol.GitStatusResponse{} },
		`{"branch":"main","upstream":"origin/main","ahead":1,"behind":2,"staged":[{"path":"a.go","change":"modified"}],` +
			`"unstaged":[{"path":"b.go","oldPath":"c.go","change":"renamed"},{"path":"n.txt","change":"untracked"}]}`},
	{protocol.MethodGitLog, "git.log", protocol.KindRequest, func() any { return &protocol.GitLogRequest{} }, `{"dir":"/p/x","page":1}`},
	{protocol.MethodGitLog, "git.log", protocol.KindResponse, func() any { return &protocol.GitLogResponse{} },
		`{"commits":[{"hash":"c3","parents":["c2","c1"],"author":"me","time":` + ts + `,"subject":"merge feat","refs":[{"name":"main","kind":"branch"},{"name":"v1","kind":"tag"}],"lane":0,"edges":[{"from":0,"to":0},{"from":0,"to":1}]}],"hasMore":true}`},
	{protocol.MethodGitShow, "git.show", protocol.KindRequest, func() any { return &protocol.GitShowRequest{} }, `{"dir":"/p/x","hash":"c3"}`},
	{protocol.MethodGitShow, "git.show", protocol.KindResponse, func() any { return &protocol.GitShowResponse{} },
		`{"hash":"c3","parents":["c2"],"author":"me","time":` + ts + `,"message":"fix\n\nbody","files":[{"path":"a.go","added":3,"deleted":1,"binary":false},{"path":"logo.png","added":0,"deleted":0,"binary":true}]}`},
	{protocol.MethodGitDiff, "git.diff", protocol.KindRequest, func() any { return &protocol.GitDiffRequest{} },
		`{"dir":"/p/x","path":"a.go","hash":"c3","staged":false}`},
	{protocol.MethodGitDiff, "git.diff", protocol.KindResponse, func() any { return &protocol.GitDiffResponse{} },
		`{"path":"a.go","binary":false,"patch":"@@ -1 +1 @@\n-a\n+b\n","collapsed":false}`},
	{protocol.MethodGitBranches, "git.branches", protocol.KindRequest, func() any { return &protocol.GitBranchesRequest{} }, `{"dir":"/p/x"}`},
	{protocol.MethodGitBranches, "git.branches", protocol.KindResponse, func() any { return &protocol.GitBranchesResponse{} },
		`{"current":"main","local":["main"],"remote":[{"remote":"origin","name":"feat-x"}]}`},
	{protocol.MethodGitStage, "git.stage", protocol.KindRequest, func() any { return &protocol.GitStageRequest{} }, `{"dir":"/p/x","path":"a.go"}`},
	{protocol.MethodGitStage, "git.stage", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodGitUnstage, "git.unstage", protocol.KindRequest, func() any { return &protocol.GitUnstageRequest{} }, `{"dir":"/p/x","path":"a.go"}`},
	{protocol.MethodGitUnstage, "git.unstage", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodGitCommit, "git.commit", protocol.KindRequest, func() any { return &protocol.GitCommitRequest{} }, `{"dir":"/p/x","message":"修复重连"}`},
	{protocol.MethodGitCommit, "git.commit", protocol.KindResponse, func() any { return &protocol.GitCommitResponse{} }, `{"hash":"c4"}`},
	{protocol.MethodGitPull, "git.pull", protocol.KindRequest, func() any { return &protocol.GitPullRequest{} }, `{"dir":"/p/x"}`},
	{protocol.MethodGitPull, "git.pull", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodGitPush, "git.push", protocol.KindRequest, func() any { return &protocol.GitPushRequest{} }, `{"dir":"/p/x","setUpstream":true}`},
	{protocol.MethodGitPush, "git.push", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodGitSwitch, "git.switch", protocol.KindRequest, func() any { return &protocol.GitSwitchRequest{} }, `{"dir":"/p/x","branch":"feat-x"}`},
	{protocol.MethodGitSwitch, "git.switch", protocol.KindResponse, func() any { return &protocol.Empty{} }, `{}`},
	{protocol.MethodDirBusy, "dir.busy", protocol.KindRequest, func() any { return &protocol.DirBusyRequest{} }, `{"dir":"/p/x"}`},
	{protocol.MethodDirBusy, "dir.busy", protocol.KindResponse, func() any { return &protocol.DirBusyResponse{} }, `{"busy":true}`},
}

// decodeStrict decodes raw into v and fails on any field v does not define.
func decodeStrict(raw []byte, v any) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

func TestBodyTypesMatchContract(t *testing.T) {
	for _, tc := range bodyCases {
		t.Run(tc.name+"/"+string(tc.kind), func(t *testing.T) {
			if tc.method != any(protocol.Method(tc.name)) {
				t.Fatalf("method constant = %#v, want protocol.Method(%q)", tc.method, tc.name)
			}
			v := tc.body()
			if err := decodeStrict([]byte(tc.raw), v); err != nil {
				t.Fatalf("decode %T: %v", v, err)
			}
			out, err := json.Marshal(v)
			if err != nil {
				t.Fatalf("encode %T: %v", v, err)
			}
			assertSameJSON(t, out, []byte(tc.raw))
		})
	}
}

func TestEveryMethodHasBodyTypes(t *testing.T) {
	// Request/response methods from design D3 and D4.
	calls := []string{
		"machines.list",
		"sessions.subscribe", "sessions.unsubscribe",
		"chat.open", "chat.close", "chat.send", "chat.answer", "chat.interrupt",
		"chat.models", "chat.setModel", "chat.setPermissionMode", "chat.commands", "chat.toolOutput", "chat.image",
		"session.create", "session.stop", "session.remove", "dirs.recent", "usage.get",
		"git.status", "git.log", "git.show", "git.diff", "git.branches", "git.stage",
		"git.unstage", "git.commit", "git.pull", "git.push", "git.switch", "dir.busy",
		"term.open", "term.key", "term.close",
	}
	events := []string{
		"machine.status", "client.gone",
		"sessions.changed", "chat.items", "chat.pending", "chat.state", "term.screen",
	}
	have := map[string]bool{}
	for _, tc := range bodyCases {
		have[tc.name+"/"+string(tc.kind)] = true
	}
	for _, m := range calls {
		for _, k := range []protocol.Kind{protocol.KindRequest, protocol.KindResponse} {
			if !have[m+"/"+string(k)] {
				t.Errorf("no %s body type for %s", k, m)
			}
		}
	}
	for _, m := range events {
		if !have[m+"/"+string(protocol.KindEvent)] {
			t.Errorf("no event body type for %s", m)
		}
	}
}

func TestEnumValues(t *testing.T) {
	cases := []struct{ got, want string }{
		{string(protocol.StateWorking), "working"},
		{string(protocol.StateIdle), "idle"},
		{string(protocol.StatePending), "pending"},
		{string(protocol.StateStopped), "stopped"},
		{string(protocol.ProfileDefault), "default"},
		{string(protocol.ProfileEndpoint), "endpoint"},
		{string(protocol.ProfileOther), "other"},
		{string(protocol.ItemUser), "user"},
		{string(protocol.ItemAssistant), "assistant"},
		{string(protocol.ItemTool), "tool"},
		{string(protocol.PendingPermission), "permission"},
		{string(protocol.PendingQuestion), "question"},
		{string(protocol.DecisionAllow), "allow"},
		{string(protocol.DecisionDeny), "deny"},
		{string(protocol.PermissionDefault), "default"},
		{string(protocol.PermissionAcceptEdits), "acceptEdits"},
		{string(protocol.PermissionPlan), "plan"},
		{string(protocol.PermissionAuto), "auto"},
		{string(protocol.PermissionBypass), "bypassPermissions"},
		{string(protocol.ChangeAdded), "added"},
		{string(protocol.ChangeModified), "modified"},
		{string(protocol.ChangeDeleted), "deleted"},
		{string(protocol.ChangeRenamed), "renamed"},
		{string(protocol.ChangeUntracked), "untracked"},
		{string(protocol.RefBranch), "branch"},
		{string(protocol.RefRemoteBranch), "remoteBranch"},
		{string(protocol.RefTag), "tag"},
	}
	for code, want := range errorCodes {
		cases = append(cases, struct{ got, want string }{string(code), want})
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("enum value %q, want %q", c.got, c.want)
		}
	}
}

// errorCodes lists every defined error code with its wire value. Keys are
// map keys, so two constants sharing a value fail to compile.
var errorCodes = map[protocol.ErrorCode]string{
	protocol.CodeUnauthenticated:       "unauthenticated",
	protocol.CodeMachineOffline:        "machineOffline",
	protocol.CodeUnknownMethod:         "unknownMethod",
	protocol.CodeInvalidParams:         "invalidParams",
	protocol.CodeSessionNotFound:       "sessionNotFound",
	protocol.CodeNotFound:              "notFound",
	protocol.CodeSessionRunning:        "sessionRunning",
	protocol.CodePendingChanged:        "pendingChanged",
	protocol.CodeNotDelivered:          "notDelivered",
	protocol.CodeDirNotFound:           "dirNotFound",
	protocol.CodeNotTrusted:            "notTrusted",
	protocol.CodeNotGitRepo:            "notGitRepo",
	protocol.CodeNeedsConfirmation:     "needsConfirmation",
	protocol.CodeGitFailed:             "gitFailed",
	protocol.CodeGitCredentialsMissing: "gitCredentialsMissing",
	protocol.CodeTimeout:               "timeout",
	protocol.CodeInternal:              "internal",
	protocol.CodePairCodeExpired:       "pairCodeExpired",
	protocol.CodePairCodeInvalid:       "pairCodeInvalid",
	protocol.CodeRateLimited:           "rateLimited",
	protocol.CodeDeviceNotFound:        "deviceNotFound",
	protocol.CodeMachineInUse:          "machineInUse",
}

// Field names replaced when the wire names were unified must be rejected.
func TestOldFieldNamesRejected(t *testing.T) {
	cases := []struct {
		name string
		body any
		raw  string
	}{
		{"ToolCall.id", &protocol.ToolCall{}, `{"id":"toolu_1"}`},
		{"ToolCall.name", &protocol.ToolCall{}, `{"name":"Bash"}`},
		{"Pending.tool", &protocol.Pending{}, `{"tool":"Bash"}`},
		{"Profile.host", &protocol.Profile{}, `{"host":"api.anthropic.com"}`},
		{"GitDiffRequest.commit", &protocol.GitDiffRequest{}, `{"commit":"c3"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := decodeStrict([]byte(tc.raw), tc.body); err == nil {
				t.Fatalf("%s decoded into %T", tc.raw, tc.body)
			}
		})
	}
}
