package chat_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/chat"
	"clauderc/internal/agent/fakeattach"
	"clauderc/internal/protocol"
)

// The transcript is a real session's, read up to just before it was
// rewound (internal/agent/transcript/testdata/rewind-both.jsonl), and the
// fake attach lists its messages as Claude Code's /rewind panel did.
const (
	rewindTwo     = "请用 Edit 工具把 note.txt 里的 v1 改成 v2，然后只回复 TWO"
	rewindTwoUUID = "6cbceb2c-343c-432b-b686-a0cbb8482651"
	openRewind    = "\x15\x1b[200~/rewind\x1b[201~\r"
	up, down, esc = "\x1b[A", "\x1b[B", "\x1b"
	clearLine     = "\x15\x7f"
)

var (
	rewindRows    = []string{"只回复 OK", "只回复 ONE", rewindTwo, "只回复 THREE"}
	rewindOptions = []string{"Restore code and conversation", "Restore conversation", "Restore code", "Summarize from here", "Summarize up to here", "Never mind"}
	// A message that changed no code is offered fewer ways back.
	rewindNoCode = []string{"Restore conversation", "Summarize from here", "Summarize up to here", "Never mind"}
)

// rewindFixture is a fixture on the sample session, before its rewind.
func rewindFixture(t *testing.T, s setup) *fixture {
	t.Helper()
	f := newFixture(t, s)
	before, _ := rewindSample(t)
	if err := os.WriteFile(f.transcript, []byte(before), 0o600); err != nil {
		t.Fatal(err)
	}
	return f
}

// rewindSample splits the sample session where it was rewound: before
// holds the records written until then, after the new branch's.
func rewindSample(t *testing.T) (before, after string) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "transcript", "testdata", "rewind-both.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.SplitAfter(string(raw), "\n")
	cut := slices.IndexFunc(lines, func(l string) bool { return strings.Contains(l, `"只回复 FOUR"`) })
	for cut > 0 && !strings.Contains(lines[cut-1], `"turn_duration"`) {
		cut--
	}
	return strings.Join(lines[:cut], ""), strings.Join(lines[cut:], "")
}

func TestRewind(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name    string
		rows    []string
		options []string
		restore protocol.RewindRestore
		keys    string
		errCode protocol.ErrorCode
		env     []string
	}{
		// Up twice from "(current)" to the second turn's message; taking the
		// conversation back puts it into the input box, which is cleared.
		{"both", rewindRows, rewindOptions, protocol.RewindBoth, openRewind + up + up + "\r" + "\r" + clearLine, "", nil},
		{"code only", rewindRows, rewindOptions, protocol.RewindCode, openRewind + up + up + "\r" + down + down + "\r", "", nil},
		// The panel lists another message than the transcript: Esc before
		// the cursor moves, never Enter.
		{"row does not match", []string{"只回复 OK", "只回复 ONE", "别的消息", "只回复 THREE"}, rewindOptions, protocol.RewindBoth, openRewind + esc, protocol.CodeRewindFailed, nil},
		// The panel lists one message more than the branch has.
		{"more rows than messages", append([]string{"更早的消息"}, rewindRows...), rewindOptions, protocol.RewindBoth, openRewind + esc, protocol.CodeRewindFailed, nil},
		// The panel does not offer the way back asked for: Esc back to the
		// list and Esc again to close it.
		{"way back not offered", rewindRows, rewindNoCode, protocol.RewindCode, openRewind + up + up + "\r" + esc + esc, protocol.CodeRewindFailed, nil},
		// The first arrow shows only after the wait: no second press, Esc.
		{"slow arrow", rewindRows, rewindOptions, protocol.RewindBoth, openRewind + up + esc, protocol.CodeRewindFailed, []string{fakeattach.RewindSlowEnv(2500 * time.Millisecond)}},
		// The ways back are for another message than the row chosen.
		{"quote does not match", rewindRows, rewindOptions, protocol.RewindBoth, openRewind + up + up + "\r" + esc + esc, protocol.CodeRewindFailed, []string{fakeattach.RewindQuoteEnv("只回复 ONE")}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := rewindFixture(t, setup{attachEnv: append([]string{fakeattach.RewindEnv(c.rows, c.options)}, c.env...)})
			res := f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: c.restore})
			if c.errCode == "" && res.Error != nil || c.errCode != "" && (res.Error == nil || res.Error.Code != c.errCode) {
				t.Errorf("error %+v, want %q", res.Error, c.errCode)
			}
			if got := f.keyLog(); got != c.keys {
				t.Errorf("keys %q, want %q", got, c.keys)
			}
		})
	}
}

// A working session is refused before a single key goes out.
func TestRewindWhileWorking(t *testing.T) {
	t.Parallel()
	f := rewindFixture(t, setup{status: "busy", state: "working", attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions)}})
	res := f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	if res.Error == nil || res.Error.Code != protocol.CodeRewindFailed {
		t.Errorf("error %+v, want rewindFailed", res.Error)
	}
	if got := f.keyLog(); got != "" {
		t.Errorf("keys %q, want none", got)
	}
}

// Claude Code writes nothing when it rewinds, so the agent takes the
// messages away itself: the open chat gets them as removed items, and a
// later page of the session leaves them out too.
func TestRewindTakesTheMessagesAwayAtOnce(t *testing.T) {
	t.Parallel()
	f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions)}})
	var opened protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &opened); err != nil {
		t.Fatal(err)
	}
	var gone []int64
	for _, it := range opened.Items {
		if it.Kind == protocol.ItemUser && (it.Text == rewindTwo || it.Text == "只回复 THREE") || it.Kind == protocol.ItemTool ||
			it.Kind == protocol.ItemAssistant && (it.Text == "TWO" || it.Text == "THREE") {
			gone = append(gone, it.Seq)
		}
	}
	f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindConversation})
	var removed []int64
	for len(removed) < len(gone) {
		var ev protocol.ChatItemsEvent
		if err := json.Unmarshal(f.nextEvent(t, protocol.EventChatItems, 5*time.Second).Body, &ev); err != nil {
			t.Fatal(err)
		}
		for _, it := range ev.Items {
			if it.Kind == protocol.ItemRemoved {
				removed = append(removed, it.Seq)
			}
		}
	}
	if !slices.Equal(removed, gone) {
		t.Errorf("removed %v, want %v", removed, gone)
	}
	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID})
	var again protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, Before: 1 << 30}), &again); err != nil {
		t.Fatal(err)
	}
	for _, it := range again.Items {
		if it.Kind == protocol.ItemUser && (it.Text == rewindTwo || it.Text == "只回复 THREE") {
			t.Errorf("page after the rewind still has %q", it.Text)
		}
	}
}

// A rewind made in the terminal view or on the computer shows on the open
// chat's screen; the agent takes the messages away when the panel closes
// on a way back that took the conversation, and only then.
func TestRewindSeenOnTheScreen(t *testing.T) {
	t.Parallel()
	const wait = 1300 * time.Millisecond // the chat reads its screen once a second
	type step struct {
		keys []protocol.TermKey
	}
	k := func(keys ...protocol.TermKey) step { return step{keys} }
	open := k(protocol.TermKeyEsc, protocol.TermKeyEsc)
	toTwo := k(protocol.TermKeyUp, protocol.TermKeyUp)
	enter := k(protocol.TermKeyEnter)
	cases := []struct {
		name  string
		rows  []string
		steps []step
		takes bool
		env   []string
	}{
		{"both", rewindRows, []step{open, toTwo, enter, enter}, true, nil},
		{"conversation", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyDown), enter}, true, nil},
		// The cursor reaches the message and Enter goes within one screen read.
		{"quick", rewindRows, []step{open, k(protocol.TermKeyUp, protocol.TermKeyUp, protocol.TermKeyEnter), enter}, true, nil},
		// Restoring files first, Claude Code fills the input box a while after the panel closed.
		{"slow to fill the input", rewindRows, []step{open, toTwo, enter, enter}, true, []string{fakeattach.RewindDelayEnv(2500 * time.Millisecond)}},
		{"code only", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyDown, protocol.TermKeyDown), enter}, false, nil},
		{"never mind", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyDown, protocol.TermKeyDown, protocol.TermKeyDown, protocol.TermKeyDown, protocol.TermKeyDown), enter}, false, nil},
		{"esc", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyEsc), k(protocol.TermKeyEsc)}, false, nil},
		// The panel names another message at that row than the transcript.
		{"row does not match", []string{"只回复 OK", "只回复 ONE", "别的消息", "只回复 THREE"}, []step{open, toTwo, enter, enter}, false, nil},
		// Esc twice within one screen read leaves the panel from its second
		// screen unseen, its cursor on a way back that takes the
		// conversation; the user then starts typing the same message.
		{"typing after leaving", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyEsc, protocol.TermKeyEsc)}, false, []string{fakeattach.RewindTypedEnv("请", 300*time.Millisecond)}},
		// Only the whole message in the input box is taken for it put back.
		{"typing the whole message", rewindRows, []step{open, toTwo, enter, k(protocol.TermKeyEsc, protocol.TermKeyEsc)}, true, []string{fakeattach.RewindTypedEnv(rewindTwo, 300*time.Millisecond)}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := rewindFixture(t, setup{attachEnv: append([]string{fakeattach.RewindEnv(c.rows, rewindOptions)}, c.env...)})
			f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
			f.openTerm(t, "c1")
			for _, s := range c.steps {
				for _, key := range s.keys {
					f.ok(t, "c1", protocol.MethodTermKey, protocol.TermKeyRequest{SessionID: shortID, Key: key})
				}
				time.Sleep(wait)
			}
			removed := 0
			deadline := time.Now().Add(5 * time.Second)
			for time.Now().Before(deadline) {
				e, ok := f.maybeEvent(protocol.EventChatItems, time.Until(deadline))
				if !ok {
					break
				}
				var ev protocol.ChatItemsEvent
				_ = json.Unmarshal(e.Body, &ev)
				for _, it := range ev.Items {
					if it.Kind == protocol.ItemRemoved {
						removed++
					}
				}
				if removed == 6 {
					break
				}
			}
			if c.takes && removed != 6 || !c.takes && removed != 0 {
				t.Errorf("removed %d items, want %d", removed, map[bool]int{true: 6}[c.takes])
			}
		})
	}
}

// The mark a rewind leaves on the session holds while the transcript stays
// as it was: once a record comes, the branch it hangs from tells.
func TestRewindMarkLastsUntilTheTranscriptMovesOn(t *testing.T) {
	t.Parallel()
	before, after := rewindSample(t)
	var last string
	for _, l := range strings.Split(strings.TrimSpace(before), "\n") {
		var r struct {
			UUID        string `json:"uuid"`
			IsSidechain bool   `json:"isSidechain"`
		}
		if json.Unmarshal([]byte(l), &r) == nil && r.UUID != "" && !r.IsSidechain {
			last = r.UUID
		}
	}
	for _, c := range []struct {
		name, next string
		shown      bool
	}{
		// The rewind did not take: the next record goes on from the latest.
		{"same branch", `{"type":"user","uuid":"next-1","parentUuid":"` + last + `","message":{"content":"接着原来的分支"}}` + "\n", true},
		// The next message hangs from the point rewound to.
		{"new branch", after, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions)}})
			f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindConversation})
			texts := func(client string) []string {
				var res protocol.ChatOpenResponse
				if err := json.Unmarshal(f.ok(t, client, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &res); err != nil {
					t.Fatal(err)
				}
				f.ok(t, client, protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID})
				var out []string
				for _, it := range res.Items {
					if it.Kind == protocol.ItemUser {
						out = append(out, it.Text)
					}
				}
				return out
			}
			if got := texts("c1"); slices.Contains(got, rewindTwo) {
				t.Fatalf("right after the rewind %q", got)
			}
			f.appendTranscript(t, c.next)
			got := texts("c2")
			if slices.Contains(got, rewindTwo) != c.shown || slices.Contains(got, "只回复 THREE") != c.shown {
				t.Errorf("after the next record %q, rewound messages shown: want %v", got, c.shown)
			}
		})
	}
}

// Messages that read the same are told apart by their place: the third of
// seven, the first reading the same, is where Enter goes. In a list longer
// than the screen, the third of sixteen is more than a screen up.
func TestRewindByPlace(t *testing.T) {
	t.Parallel()
	var long []string
	for i := 1; i <= 16; i++ {
		long = append(long, fmt.Sprintf("第 %d 条", i))
	}
	for name, texts := range map[string][]string{
		"same text": {"继续", "改一下", "继续", "好的", "好的", "再试一次", "再试一次"},
		"scrolled":  long,
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(texts, rewindOptions)}})
			if err := os.WriteFile(f.transcript, []byte(turns(texts)), 0o600); err != nil {
				t.Fatal(err)
			}
			f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: "u2", Restore: protocol.RewindBoth})
			if got, want := f.keyLog(), openRewind+strings.Repeat(up, len(texts)-2)+"\r"+"\r"+clearLine; got != want {
				t.Errorf("keys %q, want %q", got, want)
			}
		})
	}
}

// turns is a transcript of one turn per text, the user message of the
// i-th turn having uuid u<i>.
func turns(texts []string) string {
	var lines []string
	parent := "null"
	for i, text := range texts {
		lines = append(lines,
			fmt.Sprintf(`{"type":"user","uuid":"u%d","parentUuid":%s,"message":{"content":%q}}`, i, parent, text),
			fmt.Sprintf(`{"type":"assistant","uuid":"a%d","parentUuid":"u%d","message":{"content":[{"type":"text","text":"好"}]}}`, i, i))
		parent = fmt.Sprintf(`"a%d"`, i)
	}
	return strings.Join(lines, "\n") + "\n"
}

// A rewind still short of its last Enter when its time is up leaves the
// panel with Esc.
func TestRewindTimeLimit(t *testing.T) {
	defer chat.SetRewindTotal(2 * time.Second)()
	f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions), fakeattach.RewindSlowEnv(time.Minute)}})
	res := f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	if res.Error == nil || res.Error.Code != protocol.CodeRewindFailed || !strings.Contains(res.Error.Message, "2 秒还没做完") {
		t.Errorf("error %+v", res.Error)
	}
	if got := f.keyLog(); got != openRewind+up+esc {
		t.Errorf("keys %q", got)
	}
}

// After the last Enter the rewind has happened: the agent waits for the
// message to come back into the input box and clears it; what goes wrong
// then is told, and the rewind still answered as done.
func TestRewindAfterTheLastEnter(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name   string
		env    []string
		notice string
		// removed is whether the open chat loses the messages at once.
		removed bool
		keys    string
	}{
		// Restoring files first, Claude Code fills the input box 4 s later.
		{"slow to fill", []string{fakeattach.RewindDelayEnv(4 * time.Second)}, "", true, openRewind + up + up + "\r" + "\r" + clearLine},
		{"cannot clear", []string{fakeattach.RewindStickyEnv()}, "没能清空电脑上的输入框", true, openRewind + up + up + "\r" + "\r" + clearLine},
		// Nothing in the input box tells the conversation went back.
		{"never filled", []string{fakeattach.RewindDelayEnv(time.Minute)}, "没把这条消息放回电脑上的输入框", false, openRewind + up + up + "\r" + "\r"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := rewindFixture(t, setup{attachEnv: append([]string{fakeattach.RewindEnv(rewindRows, rewindOptions)}, c.env...)})
			f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
			var res protocol.ChatRewindResponse
			if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth}), &res); err != nil {
				t.Fatal(err)
			}
			if c.notice == "" && res.Notice != "" || !strings.Contains(res.Notice, c.notice) {
				t.Errorf("notice %q, want %q", res.Notice, c.notice)
			}
			// The open chat redraws the screen with Ctrl+L now and then.
			if got := strings.ReplaceAll(f.keyLog(), "\f", ""); got != c.keys {
				t.Errorf("keys %q, want %q", got, c.keys)
			}
			removed := 0
			for {
				e, ok := f.maybeEvent(protocol.EventChatItems, 2*time.Second)
				if !ok {
					break
				}
				var ev protocol.ChatItemsEvent
				_ = json.Unmarshal(e.Body, &ev)
				for _, it := range ev.Items {
					if it.Kind == protocol.ItemRemoved {
						removed++
					}
				}
			}
			if c.removed && removed != 6 || !c.removed && removed != 0 {
				t.Errorf("removed %d items", removed)
			}
		})
	}
}

// A panel that cannot be read is left with Esc until it is gone; with no
// panel, no Esc goes out.
func TestRewindLeavesAPanelItCannotRead(t *testing.T) {
	t.Parallel()
	f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions), fakeattach.RewindBrokenEnv()}})
	res := f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	if res.Error == nil || res.Error.Code != protocol.CodeRewindFailed || !strings.Contains(res.Error.Message, "已按 Esc 退出") {
		t.Errorf("error %+v", res.Error)
	}
	if got := f.keyLog(); got != openRewind+esc {
		t.Errorf("keys %q, want one Esc", got)
	}

	f = rewindFixture(t, setup{})
	res = f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	if res.Error == nil || res.Error.Code != protocol.CodeRewindFailed {
		t.Errorf("without a panel: error %+v", res.Error)
	}
	if got := f.keyLog(); got != openRewind {
		t.Errorf("without a panel: keys %q, want no Esc", got)
	}
}

// A session that starts working while the terminal is being taken gets no
// key: the state is checked again with the terminal held.
func TestRewindChecksTheSessionAgainWithTheTerminal(t *testing.T) {
	f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions)}})
	defer chat.SetBeforeRewindTerm(func() { f.setAgents(t, "busy", "working", "7") })()
	res := f.call(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	if res.Error == nil || res.Error.Code != protocol.CodeRewindFailed {
		t.Errorf("error %+v, want rewindFailed", res.Error)
	}
	if got := f.keyLog(); strings.Contains(got, "/rewind") {
		t.Errorf("keys %q, want no /rewind", got)
	}
}

// A turn written while the terminal is being taken moves the message a row
// up: its place is worked out again with the terminal held.
func TestRewindPlacesTheMessageAgainWithTheTerminal(t *testing.T) {
	before, _ := rewindSample(t)
	var last string
	for _, l := range strings.Split(strings.TrimSpace(before), "\n") {
		var r struct {
			UUID        string `json:"uuid"`
			IsSidechain bool   `json:"isSidechain"`
		}
		if json.Unmarshal([]byte(l), &r) == nil && r.UUID != "" && !r.IsSidechain {
			last = r.UUID
		}
	}
	rows := append(append([]string{}, rewindRows...), "又一条")
	f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(rows, rewindOptions)}})
	defer chat.SetBeforeRewindTerm(func() {
		f.appendTranscript(t, `{"type":"user","uuid":"late-1","parentUuid":"`+last+`","message":{"content":"又一条"}}`+"\n"+
			`{"type":"assistant","uuid":"late-2","parentUuid":"late-1","message":{"content":[{"type":"text","text":"好"}]}}`+"\n")
	})()
	f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindCode})
	if got := f.keyLog(); got != openRewind+up+up+up+"\r"+down+down+"\r" {
		t.Errorf("keys %q", got)
	}
}

// The /rewind panel of chat.rewind is not followed as a rewind made by
// hand: the agent takes the messages away itself once it is done, not the
// open chat's screen reads while it runs. The input box keeps the message,
// so chat.rewind holds the terminal a while after the panel closed.
func TestRewindIsNotFollowedOnTheScreen(t *testing.T) {
	t.Parallel()
	s := setup{attachEnv: []string{fakeattach.RewindEnv(rewindRows, rewindOptions), fakeattach.RewindStickyEnv()}}
	var mu sync.Mutex
	var removedAt []time.Time
	s.opts = func(o *chat.Options) {
		push := o.Push
		o.Push = agent.PusherFunc(func(e protocol.Envelope) {
			var ev protocol.ChatItemsEvent
			if e.Method == protocol.EventChatItems && json.Unmarshal(e.Body, &ev) == nil && slices.ContainsFunc(ev.Items, func(it protocol.ChatItem) bool { return it.Kind == protocol.ItemRemoved }) {
				mu.Lock()
				removedAt = append(removedAt, time.Now())
				mu.Unlock()
			}
			push.Push(e)
		})
	}
	f := rewindFixture(t, s)
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID})
	f.ok(t, "c1", protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: shortID, UUID: rewindTwoUUID, Restore: protocol.RewindBoth})
	done := time.Now()
	time.Sleep(1500 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if len(removedAt) != 1 {
		t.Fatalf("%d chat.items took messages away, want one", len(removedAt))
	}
	if d := done.Sub(removedAt[0]); d > 400*time.Millisecond {
		t.Errorf("messages taken away %v before chat.rewind was done", d)
	}
}
