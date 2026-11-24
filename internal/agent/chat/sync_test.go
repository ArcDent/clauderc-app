package chat_test

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"clauderc/internal/agent/chat"
	"clauderc/internal/protocol"
)

// When a second phone opens a session right as a pending prompt appears, its
// snapshot does not overwrite the poller's baseline, so the first phone still
// receives chat.pending with a higher rev.
func TestSecondPhoneOpenDoesNotSuppressPendingForFirst(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{
		opts: func(o *chat.Options) {
			o.PollInterval = 100 * time.Millisecond
			o.StateInterval = 10 * time.Second
		},
	})

	var res1 protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res1); err != nil {
		t.Fatal(err)
	}
	if res1.Pending != nil || res1.Rev < 1 || res1.SubID == "" {
		t.Fatalf("first open = pending %+v rev %d subId %q", res1.Pending, res1.Rev, res1.SubID)
	}

	// A pending tool call lands in the transcript and claude reports the session blocked.
	f.appendTranscript(t, `{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_p2","name":"Bash","input":{"command":"ls"}}]}}`+"\n")
	// Wait until pollTranscript picks up the new item for c1 (target is still idle/done).
	eItems := f.nextEvent(t, protocol.EventChatItems, 2*time.Second)
	var itemsEvt protocol.ChatItemsEvent
	if err := json.Unmarshal(eItems.Body, &itemsEvt); err != nil {
		t.Fatal(err)
	}
	if itemsEvt.Rev <= res1.Rev {
		t.Fatalf("chat.items rev = %d, want > %d", itemsEvt.Rev, res1.Rev)
	}

	// Now agents.json reflects blocked; c2 opens before pollState runs.
	f.setAgents(t, "waiting", "blocked", "7")
	var res2 protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res2); err != nil {
		t.Fatal(err)
	}
	if res2.Pending == nil || res2.Pending.ToolUseID != "toolu_p2" {
		t.Fatalf("second open pending = %+v, want toolu_p2", res2.Pending)
	}

	// c1 must still receive chat.pending on the next poll tick.
	deadline := time.After(2 * time.Second)
	for {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventChatPending || e.Client != "c1" {
				continue
			}
			var p protocol.ChatPendingEvent
			if err := json.Unmarshal(e.Body, &p); err != nil {
				t.Fatal(err)
			}
			if p.Pending == nil || p.Pending.ToolUseID != "toolu_p2" {
				t.Fatalf("c1 chat.pending = %+v, want toolu_p2", p.Pending)
			}
			if p.Rev <= res2.Rev {
				t.Fatalf("c1 chat.pending rev = %d, want > snapshot rev %d", p.Rev, res2.Rev)
			}
			return
		case <-deadline:
			t.Fatal("c1 never received chat.pending after c2 opened")
		}
	}
}

// A late chat.close or term.close carrying an older subId leaves the newer
// subscription from the same client intact.
func TestLateCloseWithOldSubIDKeepsNewSubscription(t *testing.T) {
	t.Parallel()
	s := running()
	s.screen = "❯ ready"
	f := newFixture(t, s)

	var open1, open2 protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open1); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open2); err != nil {
		t.Fatal(err)
	}
	if open1.SubID == "" || open2.SubID == "" || open1.SubID == open2.SubID {
		t.Fatalf("subIds = %q and %q, want distinct non-empty ids", open1.SubID, open2.SubID)
	}

	// Old page's close arrives after the new page already opened.
	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID, SubID: open1.SubID})

	// New subscription still receives pushes.
	f.appendTranscript(t, assistantLine("新页面还在收推送"))
	e := f.nextEvent(t, protocol.EventChatItems, 2*time.Second)
	var items protocol.ChatItemsEvent
	if err := json.Unmarshal(e.Body, &items); err != nil {
		t.Fatal(err)
	}
	if len(items.Items) == 0 || items.Items[0].Text != "新页面还在收推送" {
		t.Fatalf("got %+v, want 新页面还在收推送", items)
	}

	// Same protection for term.open / term.close.
	t1 := f.openTerm(t, "c1")
	t2 := f.openTerm(t, "c1")
	if t1.SubID == "" || t2.SubID == "" || t1.SubID == t2.SubID {
		t.Fatalf("term subIds = %q and %q", t1.SubID, t2.SubID)
	}
	f.ok(t, "c1", protocol.MethodTermClose, protocol.TermCloseRequest{SessionID: shortID, SubID: t1.SubID})
	f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "1"})

	// Closing with the current subId tears down the subscription.
	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID, SubID: open2.SubID})
	f.ok(t, "c1", protocol.MethodTermClose, protocol.TermCloseRequest{SessionID: shortID, SubID: t2.SubID})
	f.appendTranscript(t, assistantLine("关掉后不该再推"))
	if ev, ok := f.maybeEvent(protocol.EventChatItems, 600*time.Millisecond); ok {
		t.Fatalf("chat.items after closing current subId: %s", ev.Body)
	}
}

// ClientGone or a close arriving while chat.open, subagent chat.open or
// term.open is in flight prevents registering the subscription, stops polling
// and releases the attach.
func TestClientGoneOrCloseDuringOpensLeavesNoSubscriptions(t *testing.T) {
	t.Parallel()
	waitDelayCall := func(t *testing.T, f *fixture, before int) {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		for len(fakeclaudeCalls(t, f)) <= before {
			if time.Now().After(deadline) {
				t.Fatal("claude was not called")
			}
			time.Sleep(20 * time.Millisecond)
		}
		time.Sleep(50 * time.Millisecond)
	}

	t.Run("chat.open + ClientGone", func(t *testing.T) {
		f := newFixture(t, setup{})
		writeDelay(t, f.sample, "3")
		before := len(fakeclaudeCalls(t, f))
		resCh := make(chan protocol.Envelope, 1)
		go func() {
			resCh <- f.h.Handle(context.Background(), req("r-1", "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}))
		}()
		waitDelayCall(t, f, before)
		f.h.ClientGone("c1")
		select {
		case res := <-resCh:
			if res.Error == nil {
				t.Fatalf("chat.open succeeded after ClientGone: %s", res.Body)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("chat.open did not cancel promptly on ClientGone")
		}
		_ = os.Remove(filepath.Join(f.sample, "agents.delay"))
		f.appendTranscript(t, assistantLine("不应推送"))
		if ev, ok := f.maybeEvent(protocol.EventChatItems, 600*time.Millisecond); ok {
			t.Fatalf("received chat.items after canceled open: %s", ev.Body)
		}
	})

	t.Run("chat.open + chat.close", func(t *testing.T) {
		f := newFixture(t, setup{})
		writeDelay(t, f.sample, "3")
		before := len(fakeclaudeCalls(t, f))
		resCh := make(chan protocol.Envelope, 1)
		go func() {
			resCh <- f.h.Handle(context.Background(), req("r-2", "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}))
		}()
		waitDelayCall(t, f, before)
		f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID})
		select {
		case res := <-resCh:
			if res.Error == nil {
				t.Fatalf("chat.open succeeded after chat.close: %s", res.Body)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("chat.open did not cancel promptly on chat.close")
		}
		_ = os.Remove(filepath.Join(f.sample, "agents.delay"))
		f.appendTranscript(t, assistantLine("不应推送2"))
		if ev, ok := f.maybeEvent(protocol.EventChatItems, 600*time.Millisecond); ok {
			t.Fatalf("received chat.items after close-during-open: %s", ev.Body)
		}
	})

	t.Run("subagent chat.open + ClientGone", func(t *testing.T) {
		f := newFixture(t, setup{status: "busy", state: "working"})
		dir := f.addSubagents(t)
		writeDelay(t, f.sample, "3")
		before := len(fakeclaudeCalls(t, f))
		resCh := make(chan protocol.Envelope, 1)
		go func() {
			resCh <- f.h.Handle(context.Background(), req("r-3", "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: goAgent}))
		}()
		waitDelayCall(t, f, before)
		f.h.ClientGone("c1")
		select {
		case res := <-resCh:
			if res.Error == nil {
				t.Fatalf("subagent chat.open succeeded after ClientGone: %s", res.Body)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("subagent chat.open did not cancel promptly on ClientGone")
		}
		_ = os.Remove(filepath.Join(f.sample, "agents.delay"))
		line := `{"type":"assistant","isSidechain":true,"agentId":"` + goAgent + `","message":{"role":"assistant","content":[{"type":"text","text":"不该推送的子代理消息"}]}}` + "\n"
		appendFile(t, filepath.Join(dir, "agent-"+goAgent+".jsonl"), line)
		if ev, ok := f.maybeEvent(protocol.EventChatItems, 600*time.Millisecond); ok {
			t.Fatalf("received subagent chat.items after canceled open: %s", ev.Body)
		}
	})

	t.Run("term.open + ClientGone releases attach", func(t *testing.T) {
		pidFile := filepath.Join(t.TempDir(), "attach.pid")
		s := running()
		// Keep emitting so Acquire waits until ReadyTimeout unless canceled.
		s.opts = func(o *chat.Options) {
			o.Attach.Command = func(string) []string {
				return []string{"/bin/sh", "-c", `echo $$ > "$1"; stty raw -echo; while true; do printf 'tick\r\n'; sleep 0.05; done`, "sh", pidFile}
			}
			o.Attach.Quiet = 300 * time.Millisecond
			o.Attach.ReadyTimeout = 3 * time.Second
			o.Attach.Linger = 100 * time.Millisecond
		}
		f := newFixture(t, s)
		resCh := make(chan protocol.Envelope, 1)
		go func() {
			resCh <- f.h.Handle(context.Background(), req("r-4", "c1", protocol.MethodTermOpen, protocol.TermOpenRequest{SessionID: shortID}))
		}()
		pid := waitPID(t, pidFile, 3*time.Second)
		f.h.ClientGone("c1")
		select {
		case res := <-resCh:
			if res.Error == nil {
				t.Fatalf("term.open succeeded after ClientGone: %s", res.Body)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("term.open did not cancel promptly on ClientGone")
		}
		// Key must be rejected because the client was never registered.
		if res := f.call(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "1"}); res.Error == nil {
			t.Fatal("term.key accepted after canceled term.open")
		}
		// Attach must be released and exit after Linger + ReadyTimeout (or promptly once pool releases).
		deadline := time.Now().Add(5 * time.Second)
		for {
			if err := syscall.Kill(pid, 0); err != nil {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("attach process %d still alive after canceled term.open", pid)
			}
			time.Sleep(50 * time.Millisecond)
		}
		if got := f.screens(t, 400*time.Millisecond); len(got) != 0 {
			t.Fatalf("got %d term.screen events after canceled term.open", len(got))
		}
	})
}

// When /clear changes target.SessionID, the open chat switches to the new
// transcript and pushes one reset page, then follows new entries from it.
func TestClearSwitchesTranscriptAndPushesReset(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{
		opts: func(o *chat.Options) {
			o.PollInterval = 100 * time.Millisecond
			o.StateInterval = 150 * time.Millisecond
		},
	})
	var opened protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &opened); err != nil {
		t.Fatal(err)
	}
	if len(opened.Items) != 50 {
		t.Fatalf("initial items = %d, want 50", len(opened.Items))
	}

	const newSID = "9f0c2a5e-0000-4000-8000-0000000000ff"
	newPath := filepath.Join(filepath.Dir(f.transcript), newSID+".jsonl")
	if err := os.WriteFile(newPath, []byte(userLine("清空后的第一句")+assistantLine("清空后的回复")), 0o600); err != nil {
		t.Fatal(err)
	}
	f.writeAgents(t, "agents.json", f.agent(shortID, newSID, "idle", "done", "7"))

	var resetEvt protocol.ChatItemsEvent
	deadline := time.After(3 * time.Second)
	for !resetEvt.Reset {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventChatItems {
				continue
			}
			var got protocol.ChatItemsEvent
			if err := json.Unmarshal(e.Body, &got); err != nil {
				t.Fatal(err)
			}
			if got.Reset {
				resetEvt = got
			}
		case <-deadline:
			t.Fatal("timed out waiting for reset chat.items after /clear")
		}
	}
	if resetEvt.Rev <= opened.Rev {
		t.Fatalf("reset rev = %d, want > %d", resetEvt.Rev, opened.Rev)
	}
	if len(resetEvt.Items) != 2 || resetEvt.Items[0].Text != "清空后的第一句" || resetEvt.Items[1].Text != "清空后的回复" {
		t.Fatalf("reset items = %+v", resetEvt.Items)
	}

	// Further lines in the new transcript stream normally.
	appendFile(t, newPath, assistantLine("继续聊"))
	deadline = time.After(2 * time.Second)
	for {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventChatItems {
				continue
			}
			var got protocol.ChatItemsEvent
			if err := json.Unmarshal(e.Body, &got); err != nil {
				t.Fatal(err)
			}
			if !got.Reset && len(got.Items) == 1 && got.Items[0].Text == "继续聊" && got.Rev > resetEvt.Rev {
				return
			}
		case <-deadline:
			t.Fatal("timed out waiting for follow-up item on new transcript")
		}
	}
}

// When the hidden attach process exits on its own, the next screen poll
// switches to a fresh attach and terminal keys work again.
func TestDeadAttachReconnectsOnNextPollAndKeysWork(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	pidFile := filepath.Join(dir, "pid")
	countFile := filepath.Join(dir, "count")
	script := `n=$(cat "$1" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$1"; echo $$ > "$2"; stty raw -echo; printf 'attach-%d\r\n────────\r\n❯\302\240\r\n────────\r\n' "$n"; if [ "$n" -eq 2 ]; then printf '  ⏸ plan mode on\r\n'; fi; exec cat >> "$FAKE_ATTACH_LOG"`
	s := running()
	s.opts = func(o *chat.Options) {
		o.Attach.Command = func(string) []string {
			return []string{"/bin/sh", "-c", script, "sh", countFile, pidFile}
		}
		o.Attach.Quiet = 100 * time.Millisecond
		o.Attach.ReadyTimeout = time.Second
		o.PollInterval = 100 * time.Millisecond
		o.StateInterval = 300 * time.Millisecond
	}
	f := newFixture(t, s)

	// Open both chat and terminal so both hold the first attach.
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	first := f.openTerm(t, "c1")
	if first.Lines[0] != "attach-1" {
		t.Fatalf("first screen line 0 = %q, want attach-1", first.Lines[0])
	}
	pid1 := waitPID(t, pidFile, 2*time.Second)

	// Kill the fake attach process unexpectedly.
	if err := syscall.Kill(pid1, syscall.SIGKILL); err != nil {
		t.Fatal(err)
	}

	// Next terminal screen poll must acquire a new attach and push "attach-2".
	deadline := time.After(4 * time.Second)
	sawSecondScreen := false
	for !sawSecondScreen {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventTermScreen {
				continue
			}
			var sc protocol.TermScreen
			if err := json.Unmarshal(e.Body, &sc); err != nil {
				t.Fatal(err)
			}
			if len(sc.Lines) > 0 && sc.Lines[0] == "attach-2" && sc.Rev > first.Rev {
				sawSecondScreen = true
			}
		case <-deadline:
			t.Fatal("timed out waiting for term.screen from reconnected attach")
		}
	}

	// Keys pressed in the terminal view reach the new attach.
	f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: "7"})
	if log := f.keyLog(); !strings.Contains(log, "7") {
		t.Fatalf("key log = %q, want '7' delivered to the reconnected attach", log)
	}

	// Chat's screen poll also switches to the new attach and picks up its status line ("plan").
	deadline = time.After(4 * time.Second)
	for {
		select {
		case e := <-f.events:
			if e.Method != protocol.EventChatState {
				continue
			}
			var st protocol.ChatStateEvent
			if err := json.Unmarshal(e.Body, &st); err != nil {
				t.Fatal(err)
			}
			if st.PermissionMode == protocol.PermissionPlan {
				return
			}
		case <-deadline:
			// Also accept if chat.open snapshot already picked it up via ensureAttach.
			var snap protocol.ChatOpenResponse
			if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &snap); err != nil {
				t.Fatal(err)
			}
			if snap.PermissionMode == protocol.PermissionPlan {
				return
			}
			t.Fatalf("chat never picked up plan mode from reconnected attach (mode=%q)", snap.PermissionMode)
		}
	}
}

func fakeclaudeCalls(t *testing.T, f *fixture) []string {
	t.Helper()
	entries, err := os.ReadDir(f.claudeLog)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, e := range entries {
		if _, err := os.Stat(filepath.Join(f.claudeLog, e.Name(), "done")); err == nil {
			out = append(out, e.Name())
		}
	}
	return out
}

func writeDelay(t *testing.T, sample, seconds string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(sample, "agents.delay"), []byte(seconds+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
}

func waitPID(t *testing.T, path string, timeout time.Duration) int {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		b, err := os.ReadFile(path)
		if err == nil {
			if pid, err := strconv.Atoi(strings.TrimSpace(string(b))); err == nil && pid > 0 {
				return pid
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("no pid written to %s within %v", path, timeout)
	return 0
}

// Sending the same clientReqId twice within 10 minutes types the message once
// and returns the same response; once the clock moves past 10 minutes, the same
// id executes as a fresh send.
func TestChatSendDedupAndExpiry(t *testing.T) {
	t.Parallel()
	var nowMu sync.Mutex
	now := time.Date(2026, 11, 23, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time {
		nowMu.Lock()
		defer nowMu.Unlock()
		return now
	}
	f := newFixture(t, setup{
		opts: func(o *chat.Options) {
			o.Now = clock
		},
	})

	send := func(id string) protocol.ChatSendResponse {
		t.Helper()
		var res protocol.ChatSendResponse
		raw := f.ok(t, "c1", protocol.MethodChatSend, protocol.ChatSendRequest{
			SessionID:   shortID,
			Text:        "去重验证消息",
			ClientReqID: id,
		})
		if err := json.Unmarshal(raw, &res); err != nil {
			t.Fatal(err)
		}
		return res
	}

	res1 := send("req-dedup-1")
	res2 := send("req-dedup-1")
	if res1 != res2 {
		t.Fatalf("duplicate send returned %+v, want %+v", res2, res1)
	}
	if n := strings.Count(f.keyLog(), "去重验证消息"); n != 1 {
		t.Fatalf("typed %d times for duplicate clientReqId, want 1 (log=%q)", n, f.keyLog())
	}

	// Advance fake clock past the 10-minute dedup window: same id runs anew.
	nowMu.Lock()
	now = now.Add(10*time.Minute + time.Second)
	nowMu.Unlock()

	_ = send("req-dedup-1")
	if n := strings.Count(f.keyLog(), "去重验证消息"); n != 2 {
		t.Fatalf("typed %d times after 10m expiry, want 2 (log=%q)", n, f.keyLog())
	}
}
