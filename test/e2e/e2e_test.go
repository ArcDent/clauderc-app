// Package e2e runs the relay, the agent and a simulated phone together,
// with the fake claude and the fake attach standing in for Claude Code.
package e2e

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/agent/app"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/config"
	"clauderc/internal/agent/fakeattach"
	"clauderc/internal/agent/fakeclaude"
	"clauderc/internal/agent/pairing"
	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

func TestMain(m *testing.M) {
	fakeattach.RunIfFake()
	os.Exit(m.Run())
}

const (
	enrollKey = "enroll-key-for-tests"
	machineID = "m-e2e"
	shortID   = "abc12345"
	sessionID = "11111111-2222-4333-8444-555555555555"
	toolUseID = "call_00_ET_GEktflzLfLDHuagfOR2S4305"
)

func writeFile(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
}

// claudeHome builds a Claude data directory with one background session
// blocked on a Bash permission request, and returns it with the session's
// working directory and transcript.
func claudeHome(t *testing.T) (dir, work, transcript string) {
	t.Helper()
	dir, work = t.TempDir(), t.TempDir()
	agents := fmt.Sprintf(`[{"pid": 7, "id": %q, "cwd": %q, "kind": "background", "startedAt": 1794529871000, "sessionId": %q, "name": "e2e", "status": "waiting", "state": "blocked"}]`, shortID, work, sessionID)
	writeFile(t, filepath.Join(dir, "agents.json"), []byte(agents))
	writeFile(t, filepath.Join(dir, "jobs", shortID, "state.json"), []byte(fmt.Sprintf(
		`{"respawnFlags": ["--model", "haiku"], "providerEnv": {}, "cwd": %q, "updatedAt": "2026-11-13T00:00:00.000Z"}`, work)))
	_, self, _, _ := runtime.Caller(0)
	sample, err := os.ReadFile(filepath.Join(filepath.Dir(self), "..", "..", "internal", "agent", "transcript", "testdata", "pending-bash.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	transcript = filepath.Join(dir, "projects", "-e2e", sessionID+".jsonl")
	writeFile(t, transcript, sample)
	return dir, work, transcript
}

// phone is a simulated APP connection.
type phone struct {
	t   *testing.T
	ws  *websocket.Conn
	seq int
	// events collects the events read while waiting for responses.
	events []protocol.Envelope
}

// call sends a request and returns its response, keeping events seen
// meanwhile. A failed request fails the test.
func (p *phone) call(method protocol.Method, body any) protocol.Envelope {
	p.t.Helper()
	env := p.try(method, body)
	if env.Error != nil {
		p.t.Fatalf("%s failed: %+v", method, env.Error)
	}
	return env
}

// try is call that returns a failed response too.
func (p *phone) try(method protocol.Method, body any) protocol.Envelope {
	p.t.Helper()
	p.seq++
	raw, _ := json.Marshal(body)
	req := protocol.Envelope{V: protocol.Version, Kind: protocol.KindRequest, ID: fmt.Sprintf("r-%d", p.seq), Method: method, Machine: machineID, Body: raw}
	data, err := protocol.Encode(req)
	if err != nil {
		p.t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := p.ws.Write(ctx, websocket.MessageText, data); err != nil {
		p.t.Fatal(err)
	}
	for {
		_, data, err := p.ws.Read(ctx)
		if err != nil {
			p.t.Fatalf("%s: %v", method, err)
		}
		env, err := protocol.Decode(data)
		if err != nil {
			p.t.Fatal(err)
		}
		if env.Kind == protocol.KindEvent {
			p.events = append(p.events, env)
			continue
		}
		if env.ID == req.ID {
			return env
		}
	}
}

func decode(t *testing.T, env protocol.Envelope, v any) {
	t.Helper()
	if err := json.Unmarshal(env.Body, v); err != nil {
		t.Fatalf("%s body %s: %v", env.Method, env.Body, err)
	}
}

func TestPhoneDrivesAgentThroughRelay(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	srv := relay.NewServer(relay.Options{EnrollKey: enrollKey, PublicURL: "wss://relay.example.com", Store: st})
	hs := httptest.NewTLSServer(srv)
	defer srv.Close()
	defer hs.Close()

	claudeDir, work, transcript := claudeHome(t)
	claudeLog := fakeclaude.Setup(t, claudeDir)
	keys := filepath.Join(t.TempDir(), "keys")
	cfg := config.Config{
		RelayURL:     hs.URL,
		Name:         "e2e 目标机",
		ClaudePath:   fakeclaude.Path(),
		ClaudeDir:    claudeDir,
		EnrollKey:    enrollKey,
		MachineID:    machineID,
		ProfilesFile: filepath.Join(t.TempDir(), "profiles.json"),
		LaunchDir:    filepath.Join(t.TempDir(), "launch"),
	}
	agentDone := make(chan error, 1)
	go func() {
		agentDone <- app.Run(ctx, app.Options{
			Config:     cfg,
			Version:    "e2e",
			HTTPClient: hs.Client(),
			Attach: attach.Options{
				Command: fakeattach.Command,
				Env:     append(append(fakeattach.Env(keys), fakeattach.TranscriptEnv(transcript)...), fakeattach.PermissionEnv()),
			},
		})
	}()
	defer func() {
		cancel()
		if err := <-agentDone; err != nil {
			t.Errorf("agent: %v", err)
		}
	}()

	// Pairing: the agent's pair command asks for a code, the phone trades it.
	admin := pairing.Client{RelayURL: hs.URL, EnrollKey: enrollKey, HTTPClient: hs.Client()}
	code, err := admin.PairCode(ctx)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(protocol.PairRequest{Code: code.Code, DeviceName: "e2e phone"})
	res, err := hs.Client().Post(hs.URL+protocol.PathPair, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	var paired protocol.PairResponse
	if err := json.NewDecoder(res.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	ws, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(hs.URL, "https")+protocol.PathAppWS, &websocket.DialOptions{
		HTTPClient: hs.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + paired.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	ws.SetReadLimit(protocol.MaxMessageBytes)
	p := &phone{t: t, ws: ws}

	// machines.list, once the agent is connected.
	deadline := time.Now().Add(10 * time.Second)
	for {
		var machines protocol.MachinesListResponse
		decode(t, p.call(protocol.MethodMachinesList, protocol.Empty{}), &machines)
		if len(machines.Machines) == 1 && machines.Machines[0].Online && machines.Machines[0].Name == "e2e 目标机" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("machine not online: %+v", machines)
		}
		time.Sleep(20 * time.Millisecond)
	}

	var list protocol.SessionList
	decode(t, p.call(protocol.MethodSessionsSubscribe, protocol.Empty{}), &list)
	if len(list.Sessions) != 1 || list.Sessions[0].ID != shortID || list.Sessions[0].State != protocol.StatePending {
		t.Fatalf("sessions = %+v, want %s pending", list.Sessions, shortID)
	}

	var opened protocol.ChatOpenResponse
	decode(t, p.call(protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &opened)
	if len(opened.Items) == 0 || opened.Pending == nil || opened.Pending.ToolUseID != toolUseID {
		t.Fatalf("chat.open = %d items, pending %+v", len(opened.Items), opened.Pending)
	}

	// The permission prompt on screen would take a message's Enter.
	if res := p.try(protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "端到端测试消息"}); res.Error == nil {
		t.Fatal("chat.send on the permission prompt succeeded")
	}
	if sent, err := os.ReadFile(keys); err != nil || bytes.Contains(sent, []byte("\r")) {
		t.Fatalf("chat.send on the permission prompt pressed Enter: %q (%v)", sent, err)
	}

	p.call(protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: shortID, ToolUseID: toolUseID, Decision: protocol.DecisionAllow})
	if sent, err := os.ReadFile(keys); err != nil || !bytes.HasSuffix(sent, []byte("\r")) {
		t.Errorf("allow did not press Enter: %q (%v)", sent, err)
	}

	// Back on the input box once allowed.
	p.call(protocol.MethodChatSend, protocol.ChatSendRequest{SessionID: shortID, Text: "端到端测试消息"})
	if data, err := os.ReadFile(transcript); err != nil || !strings.Contains(string(data), "端到端测试消息") {
		t.Fatalf("message not in the transcript (%v)", err)
	}

	var created protocol.SessionCreateResponse
	decode(t, p.call(protocol.MethodSessionCreate, protocol.SessionCreateRequest{Cwd: work, Prompt: "hi"}), &created)
	if created.SessionID != "f00dcafe" {
		t.Errorf("session.create = %+v", created)
	}

	p.call(protocol.MethodSessionStop, protocol.SessionStopRequest{SessionID: shortID})
	var launched, stopped bool
	for _, c := range fakeclaude.Calls(t, claudeLog) {
		launched = launched || strings.Join(c.Args, " ") == "--setting-sources project,local --settings "+filepath.Join(cfg.LaunchDir, "account-settings.json")+" --bg -- hi"
		stopped = stopped || strings.Join(c.Args, " ") == "stop "+shortID
	}
	if !launched || !stopped {
		t.Errorf("claude calls: launched %v, stopped %v", launched, stopped)
	}

	// Revoking the phone closes its connection.
	if err := admin.Revoke(ctx, paired.DeviceID); err != nil {
		t.Fatal(err)
	}
	readCtx, readCancel := context.WithTimeout(ctx, 2*time.Second)
	defer readCancel()
	for {
		if _, _, err := ws.Read(readCtx); err != nil {
			if readCtx.Err() != nil {
				t.Fatal("phone still connected 2 seconds after revoke")
			}
			break
		}
	}
}

func TestPhoneContinuesChatAfterAgentReconnects(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	srv := relay.NewServer(relay.Options{EnrollKey: enrollKey, PublicURL: "wss://relay.example.com", Store: st})
	hs := httptest.NewTLSServer(srv)
	defer srv.Close()
	defer hs.Close()

	claudeDir, _, transcript := claudeHome(t)
	fakeclaude.Setup(t, claudeDir)
	keys := filepath.Join(t.TempDir(), "keys")
	cfg := config.Config{
		RelayURL:     hs.URL,
		Name:         "e2e 重连目标机",
		ClaudePath:   fakeclaude.Path(),
		ClaudeDir:    claudeDir,
		EnrollKey:    enrollKey,
		MachineID:    machineID,
		ProfilesFile: filepath.Join(t.TempDir(), "profiles.json"),
		LaunchDir:    filepath.Join(t.TempDir(), "launch"),
	}
	startAgent := func() (context.CancelFunc, <-chan error) {
		actx, acancel := context.WithCancel(context.Background())
		done := make(chan error, 1)
		go func() {
			done <- app.Run(actx, app.Options{
				Config:     cfg,
				Version:    "e2e",
				HTTPClient: hs.Client(),
				Attach: attach.Options{
					Command: fakeattach.Command,
					Env:     append(fakeattach.Env(keys), fakeattach.TranscriptEnv(transcript)...),
				},
			})
		}()
		return acancel, done
	}

	cancel1, done1 := startAgent()

	pairCtx, pairCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer pairCancel()
	admin := pairing.Client{RelayURL: hs.URL, EnrollKey: enrollKey, HTTPClient: hs.Client()}
	code, err := admin.PairCode(pairCtx)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(protocol.PairRequest{Code: code.Code, DeviceName: "e2e phone"})
	res, err := hs.Client().Post(hs.URL+protocol.PathPair, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	var paired protocol.PairResponse
	if err := json.NewDecoder(res.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	ws, _, err := websocket.Dial(pairCtx, "wss"+strings.TrimPrefix(hs.URL, "https")+protocol.PathAppWS, &websocket.DialOptions{
		HTTPClient: hs.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + paired.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	ws.SetReadLimit(protocol.MaxMessageBytes)
	p := &phone{t: t, ws: ws}

	var firstConnID string
	deadline := time.Now().Add(10 * time.Second)
	for {
		var machines protocol.MachinesListResponse
		decode(t, p.call(protocol.MethodMachinesList, protocol.Empty{}), &machines)
		if len(machines.Machines) == 1 && machines.Machines[0].Online && machines.Machines[0].ConnID != "" {
			firstConnID = machines.Machines[0].ConnID
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("machine not online with connId: %+v", machines)
		}
		time.Sleep(20 * time.Millisecond)
	}

	var opened protocol.ChatOpenResponse
	decode(t, p.call(protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &opened)
	if len(opened.Items) == 0 {
		t.Fatal("chat.open returned no items")
	}

	// Disconnect the agent for 1 second while the phone stays connected.
	cancel1()
	if err := <-done1; err != nil {
		t.Fatalf("first agent: %v", err)
	}
	time.Sleep(time.Second)

	cancel2, done2 := startAgent()
	defer func() {
		cancel2()
		if err := <-done2; err != nil {
			t.Errorf("second agent: %v", err)
		}
	}()

	// Wait on the existing phone socket for machine.status online with a new connId.
	waitCtx, waitCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer waitCancel()
	var secondConnID string
	for secondConnID == "" {
		_, data, err := ws.Read(waitCtx)
		if err != nil {
			t.Fatalf("waiting for reconnect status: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil || env.Kind != protocol.KindEvent || env.Method != protocol.EventMachineStatus {
			continue
		}
		var m protocol.Machine
		if err := json.Unmarshal(env.Body, &m); err != nil {
			t.Fatal(err)
		}
		if m.ID == machineID && m.Online && m.ConnID != "" && m.ConnID != firstConnID {
			secondConnID = m.ConnID
		}
	}

	// Reopen chat on the new agent connection.
	p.call(protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})

	// Append a new assistant reply to the transcript and verify the phone receives chat.items.
	f, err := os.OpenFile(transcript, os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.WriteString("\n" + `{"parentUuid":"f6fdcd82-2b32-4d55-9494-7a83a098f6f5","isSidechain":false,"message":{"content":[{"text":"重连后的新条目","type":"text"}],"id":"msg_reconnect_1","model":"deepseek-v4.1-flash","role":"assistant","stop_reason":"end_turn","stop_sequence":null,"type":"message"},"type":"assistant","uuid":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-11-13T02:11:00.000Z","sessionId":"` + sessionID + `"}` + "\n")
	f.Close()
	if err != nil {
		t.Fatal(err)
	}

	itemCtx, itemCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer itemCancel()
	for {
		_, data, err := ws.Read(itemCtx)
		if err != nil {
			t.Fatalf("waiting for new chat.items after reconnect: %v", err)
		}
		env, err := protocol.Decode(data)
		if err != nil || env.Kind != protocol.KindEvent || env.Method != protocol.EventChatItems {
			continue
		}
		var evt protocol.ChatItemsEvent
		if err := json.Unmarshal(env.Body, &evt); err != nil {
			t.Fatal(err)
		}
		for _, item := range evt.Items {
			if item.Text == "重连后的新条目" {
				return
			}
		}
	}
}
