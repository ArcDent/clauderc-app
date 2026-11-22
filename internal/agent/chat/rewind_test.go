package chat_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

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
	raw, err := os.ReadFile(filepath.Join("..", "transcript", "testdata", "rewind-both.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.SplitAfter(string(raw), "\n")
	cut := slices.IndexFunc(lines, func(l string) bool { return strings.Contains(l, `"只回复 FOUR"`) })
	for cut > 0 && !strings.Contains(lines[cut-1], `"turn_duration"`) {
		cut--
	}
	if err := os.WriteFile(f.transcript, []byte(strings.Join(lines[:cut], "")), 0o600); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestRewind(t *testing.T) {
	cases := []struct {
		name    string
		rows    []string
		options []string
		restore protocol.RewindRestore
		keys    string
		errCode protocol.ErrorCode
	}{
		// Up twice from "(current)" to the second turn's message; taking the
		// conversation back puts it into the input box, which is cleared.
		{"both", rewindRows, rewindOptions, protocol.RewindBoth, openRewind + up + up + "\r" + "\r" + clearLine, ""},
		{"code only", rewindRows, rewindOptions, protocol.RewindCode, openRewind + up + up + "\r" + down + down + "\r", ""},
		// The row under the cursor is not the message: Esc, never Enter.
		{"row does not match", []string{"只回复 OK", "只回复 ONE", "别的消息", "只回复 THREE"}, rewindOptions, protocol.RewindBoth, openRewind + up + up + esc, protocol.CodeRewindFailed},
		// The panel does not offer the way back asked for: Esc back to the
		// list and Esc again to close it.
		{"way back not offered", rewindRows, rewindNoCode, protocol.RewindCode, openRewind + up + up + "\r" + esc + esc, protocol.CodeRewindFailed},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := rewindFixture(t, setup{attachEnv: []string{fakeattach.RewindEnv(c.rows, c.options)}})
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
