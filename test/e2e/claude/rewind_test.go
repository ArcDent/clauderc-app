//go:build e2e

package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"clauderc/internal/agent/attach"
	"clauderc/internal/protocol"
)

// rewindModes are the ways back the rewind panel offers for a message
// that changed code, by the name of their sample and the panel's label.
var rewindModes = []struct{ name, label string }{
	{"conversation", "Restore conversation"},
	{"code", "Restore code"},
	{"both", "Restore code and conversation"},
}

// rewindTarget is the second turn's message, which edits note.txt.
const rewindTarget = "请用 Edit 工具把 note.txt 里的 v1 改成 v2，然后只回复 TWO"

// kept are the user messages of the three turns, which restoring only the
// code keeps.
const kept = "只回复 OK|只回复 ONE|" + rewindTarget + "|只回复 THREE"

// TestRewind rewinds a session with three turns, the second editing a
// file, to before the second turn, once per way back, through Claude
// Code's own /rewind panel in the attach, then sends one more message;
// the chat opened at launch shows the current branch all along. Its phone
// subtest rewinds with chat.rewind instead.
// With CLAUDERC_E2E_SAVE_SAMPLES=1 the transcripts and the panel's screens
// are written, scrubbed, to the transcript and attach testdata.
func TestRewind(t *testing.T) {
	for _, m := range rewindModes {
		t.Run(m.name, func(t *testing.T) { rewind(t, m.name, m.label) })
	}
	t.Run("phone", rewindFromPhone)
}

// turn sends msg and waits for the reply to it.
func (s *session) turn(msg string) string {
	s.t.Helper()
	m := s.mark()
	s.send(msg)
	return s.reply(s.waitUser(msg, m, 15*time.Second), 120*time.Second)
}

// threeTurns launches a session in a new workspace holding note.txt with
// v1 and has three turns, the second changing note.txt to v2; it returns
// the session and the path of note.txt.
func threeTurns(t *testing.T, name string) (*session, string) {
	t.Helper()
	ws := workspace(t, name)
	note := filepath.Join(ws, "note.txt")
	if err := os.WriteFile(note, []byte("v1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	s := launchAt(t, name, plain, ws, false)
	s.turn("只回复 ONE")
	m := s.mark()
	s.send(rewindTarget)
	p := s.pending(protocol.PendingPermission, 120*time.Second)
	s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: p.ToolUseID, Decision: protocol.DecisionAllow}, nil)
	s.reply(s.waitUser(rewindTarget, m, 15*time.Second), 120*time.Second)
	s.turn("只回复 THREE")
	if got, _ := os.ReadFile(note); string(got) != "v2\n" {
		t.Fatalf("第二轮后 note.txt = %q，want v2", got)
	}
	return s, note
}

// rewindFromPhone takes both the conversation and the code back to before
// the second turn with chat.rewind, as the phone's left swipe does.
func rewindFromPhone(t *testing.T) {
	s, note := threeTurns(t, "rewind_phone")
	var uuid string
	for _, it := range s.users() {
		if it.Text == rewindTarget {
			uuid = it.UUID
		}
	}
	if uuid == "" {
		t.Fatalf("聊天条目里第二轮的消息没有 uuid：%+v", s.users())
	}
	s.x.must(t, protocol.MethodChatRewind, protocol.ChatRewindRequest{SessionID: s.id, UUID: uuid, Restore: protocol.RewindBoth}, nil)
	if texts := s.userTexts(); texts != "只回复 OK|只回复 ONE" {
		t.Errorf("回溯后聊天里的用户消息 %q，want 只剩第一轮", texts)
	}
	if got, _ := os.ReadFile(note); string(got) != "v1\n" {
		t.Errorf("回溯后 note.txt = %q，want v1", got)
	}
	s.term(t, time.Minute, func(term *attach.Term) {
		if !term.WaitFor(emptyPrompt, 5*time.Second) {
			t.Errorf("回溯后 Claude Code 的输入框不是空的：\n%s", tail(term.Screen(), 8))
		}
	})
	if r := s.turn("只回复 FOUR"); !strings.Contains(r, "FOUR") {
		t.Errorf("回溯后再发消息，回复 %q 不含 FOUR", r)
	}
	if texts := s.userTexts(); texts != "只回复 OK|只回复 ONE|只回复 FOUR" {
		t.Errorf("再发一条后聊天里的用户消息 %q", texts)
	}
}

// users are the user items of the session's chat as chat.open gives them.
func (s *session) users() []protocol.ChatItem {
	s.t.Helper()
	var res protocol.ChatOpenResponse
	s.x.must(s.t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, &res)
	var out []protocol.ChatItem
	for _, it := range res.Items {
		if it.Kind == protocol.ItemUser {
			out = append(out, it)
		}
	}
	return out
}

// userTexts joins the texts of users with "|".
func (s *session) userTexts() string {
	var texts []string
	for _, it := range s.users() {
		texts = append(texts, it.Text)
	}
	return strings.Join(texts, "|")
}

func rewind(t *testing.T, mode, label string) {
	s, note := threeTurns(t, "rewind_"+mode)
	before, _ := os.ReadFile(s.path)

	screens := map[string]string{}
	s.term(t, 2*time.Minute, func(term *attach.Term) {
		if err := term.Send([]attach.Step{{Keys: []byte("/rewind"), Wait: time.Second}, {Keys: []byte("\r"), Wait: 0}}); err != nil {
			t.Fatal(err)
		}
		if !term.WaitFor(func(sc string) bool { return strings.Contains(sc, "Enter to continue") }, 10*time.Second) {
			t.Fatalf("没有出现回溯面板：\n%s", term.Screen())
		}
		// The panel takes keys only once it has worked out each message's
		// code changes.
		term.WaitQuiet(time.Second, 5*time.Second)
		screens["list"] = term.Screen()
		rows := panelRows(term.Screen())
		want := []string{"只回复 OK", "只回复 ONE", rewindTarget, "只回复 THREE", "(current)"}
		if strings.Join(rows, "|") != strings.Join(want, "|") {
			t.Fatalf("面板条目 %q，want %q", rows, want)
		}
		if cur, _ := panelCursor(term.Screen()); cur != "(current)" {
			t.Fatalf("光标在 %q，want (current)", cur)
		}
		for range 2 {
			press(t, term, "\x1b[A", panelCursor)
		}
		screens["target"] = term.Screen()
		if cur, _ := panelCursor(term.Screen()); cur != rewindTarget {
			t.Fatalf("按两次上键后光标在 %q，want %q", cur, rewindTarget)
		}
		_ = term.Send([]attach.Step{{Keys: []byte("\r"), Wait: 0}})
		if !term.WaitFor(func(sc string) bool { return strings.Contains(sc, "Never mind") }, 10*time.Second) {
			t.Fatalf("没有出现恢复方式：\n%s", term.Screen())
		}
		term.WaitQuiet(time.Second, 5*time.Second)
		screens["options"] = term.Screen()
		opts := panelOptions(term.Screen())
		at := -1
		for i, o := range opts {
			if o == label {
				at = i
			}
		}
		if at < 0 {
			t.Fatalf("恢复方式 %q 里没有 %q", opts, label)
		}
		for range at {
			press(t, term, "\x1b[B", optionCursor)
		}
		if cur, _ := optionCursor(term.Screen()); cur != label {
			t.Fatalf("光标在 %q，want %q", cur, label)
		}
		_ = term.Send([]attach.Step{{Keys: []byte("\r"), Wait: 0}})
		if !term.WaitFor(func(sc string) bool {
			return !strings.Contains(sc, "Never mind") && !strings.Contains(sc, "Enter to continue")
		}, 20*time.Second) {
			t.Fatalf("回溯面板没有关上：\n%s", term.Screen())
		}
		term.WaitQuiet(time.Second, 5*time.Second)
		screens["after-"+mode] = term.Screen()
		t.Logf("回溯后的屏幕：\n%s", tail(term.Screen(), 12))
		// The chat opened at launch reads the session's screen and takes the
		// messages left behind away before anything is written; it needs the
		// message back in the input box to tell, so that is cleared after.
		if mode == "code" {
			time.Sleep(3 * time.Second)
			if texts := s.userTexts(); texts != kept {
				t.Errorf("只回代码后聊天里的用户消息 %q，want 一条不少", texts)
			}
		} else {
			var texts string
			ok := term.WaitFor(func(string) bool { texts = s.userTexts(); return texts == "只回复 OK|只回复 ONE" }, 10*time.Second)
			if !ok {
				t.Errorf("回溯后打开聊天，用户消息 %q，want 只剩第一轮", texts)
			}
		}
		_ = term.Send([]attach.Step{{Keys: []byte("\x15"), Wait: 500 * time.Millisecond}})
	})

	time.Sleep(2 * time.Second)
	rewound, _ := os.ReadFile(s.path)
	wantNote := map[string]string{"conversation": "v2\n", "code": "v1\n", "both": "v1\n"}[mode]
	if got, _ := os.ReadFile(note); string(got) != wantNote {
		t.Errorf("回溯后 note.txt = %q，want %q", got, wantNote)
	}
	s.turn("只回复 FOUR")
	want := "只回复 OK|只回复 ONE|只回复 FOUR"
	if mode == "code" {
		want = kept + "|只回复 FOUR"
	}
	if texts := s.userTexts(); texts != want {
		t.Errorf("再发一条后聊天里的用户消息 %q，want %q", texts, want)
	}
	after, _ := os.ReadFile(s.path)
	// The chat follows the transcript by appending; one rewritten in place
	// would need another way to follow it.
	if !bytes.HasPrefix(rewound, before) || !bytes.HasPrefix(after, rewound) {
		t.Errorf("记录不是只往后追加：回溯前 %d 字节，回溯后 %d，发新消息后 %d", len(before), len(rewound), len(after))
	}

	if os.Getenv("CLAUDERC_E2E_SAVE_SAMPLES") != "1" {
		return
	}
	home, _ := os.UserHomeDir()
	scrub := scrubber(home, s.ws, s.sessionID)
	// Rewinding writes nothing, so the transcript after the next message
	// holds the one before it whole.
	writeSample(t, filepath.Join(s.x.root, "internal", "agent", "transcript", "testdata", "rewind-"+mode+".jsonl"), scrubTranscript(after, scrub))
	for name, sc := range screens {
		if mode != "both" && name != "after-"+mode {
			continue // the list and options look the same each time
		}
		writeSample(t, filepath.Join(s.x.root, "internal", "agent", "attach", "testdata", "rewind-"+name+".txt"), []byte(scrub(sc)))
	}
}

// term runs fn on an attach to the session.
func (s *session) term(t *testing.T, timeout time.Duration, fn func(*attach.Term)) {
	t.Helper()
	pool := attach.NewPool(attach.Options{Command: func(id string) []string { return []string{s.x.src.ClaudePath, "attach", id} }, Linger: time.Second})
	defer pool.Close()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	conn, err := pool.Acquire(ctx, s.id)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Release(s.id)
	if err := conn.Do(ctx, func(term *attach.Term) error { fn(term); return nil }); err != nil {
		t.Fatal(err)
	}
}

// press sends an arrow key and waits for the cursor cursor reads to move,
// sending it once more if the first was not taken.
func press(t *testing.T, term *attach.Term, key string, cursor func(string) (string, bool)) {
	t.Helper()
	was, _ := cursor(term.Screen())
	for range 2 {
		_ = term.Send([]attach.Step{{Keys: []byte(key), Wait: 0}})
		if term.WaitFor(func(sc string) bool { cur, _ := cursor(sc); return cur != was }, 2*time.Second) {
			term.WaitQuiet(200*time.Millisecond, 2*time.Second)
			return
		}
	}
	t.Fatalf("按键后光标没有离开 %q：\n%s", was, term.Screen())
}

// panelLines are the lines of the rewind panel, below its title.
func panelLines(screen string) []string {
	lines := strings.Split(screen, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) == "Rewind" {
			return lines[i+1:]
		}
	}
	return nil
}

var panelRow = regexp.MustCompile(`^  (?:❯ |  )(\S.*?)\s*$`)

// panelRows are the messages the panel lists, without the line under
// each that tells its code changes, then "(current)".
func panelRows(screen string) []string {
	var rows []string
	lines := panelLines(screen)
	for i := 0; i < len(lines); i++ {
		m := panelRow.FindStringSubmatch(lines[i])
		if m == nil || strings.HasPrefix(m[1], "Enter to continue") || strings.HasPrefix(m[1], "Restore the code") {
			continue
		}
		rows = append(rows, m[1])
		if m[1] != "(current)" {
			i++ // the code changes line
		}
	}
	return rows
}

func panelCursor(screen string) (string, bool) {
	for _, l := range panelLines(screen) {
		if rest, ok := strings.CutPrefix(l, "  ❯ "); ok {
			return strings.TrimSpace(rest), true
		}
	}
	return "", false
}

var optionRow = regexp.MustCompile(`^  (?:❯ |  )\d+\. (.+?)\s*$`)

func panelOptions(screen string) []string {
	var opts []string
	for _, l := range panelLines(screen) {
		if m := optionRow.FindStringSubmatch(l); m != nil {
			opts = append(opts, m[1])
		}
	}
	return opts
}

func optionCursor(screen string) (string, bool) {
	for _, l := range panelLines(screen) {
		if m := optionRow.FindStringSubmatch(l); m != nil && strings.HasPrefix(l, "  ❯ ") {
			return m[1], true
		}
	}
	return "", false
}

func tail(screen string, n int) string {
	lines := strings.Split(strings.TrimRight(screen, "\n "), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// scrubber replaces the workspace, the home directory, the session id and
// the global rules' replies with stand-ins.
func scrubber(home, ws, sessionID string) func(string) string {
	r := strings.NewReplacer(
		ws, "/Users/demo/rewind",
		"~"+strings.TrimPrefix(ws, home), "~/rewind",
		home, "/Users/demo",
		" · Claude Pro\n", "\n",
		" · Claude Max\n", "\n",
		"\n\n  喵呜~", "",
		sessionID, "11111111-2222-4333-8444-555555555555",
		"\n\n已加载全局规则", "",
		"\n\n喵呜~", "",
		"已加载全局规则\n\n", "",
		`\n\n已加载全局规则`, "",
		`\n\n喵呜~`, "",
		`已加载全局规则\n\n`, "",
	)
	return r.Replace
}

// noisy are the records left out of a sample: requests as sent, with the
// system prompt and the user's rules in them, and bookkeeping.
var noisy = map[string]bool{"api-request": true, "api-request-blob": true, "api-request-shape": true, "data-flow-records": true, "tools-used": true, "session-owner": true, "atis-latch": true}

// keptAttachments keep what is in them; any other attachment keeps its type.
var keptAttachments = map[string]bool{"model": true, "queued_command": true}

// attachmentKeys are what an attachment record keeps: the rendered text
// beside the attachment repeats the user's rules and account.
var attachmentKeys = map[string]bool{"type": true, "uuid": true, "parentUuid": true, "isSidechain": true, "timestamp": true, "sessionId": true, "attachment": true}

func scrubTranscript(raw []byte, scrub func(string) string) []byte {
	var out bytes.Buffer
	for _, line := range bytes.Split(raw, []byte("\n")) {
		var e map[string]any
		if json.Unmarshal(line, &e) != nil {
			continue
		}
		typ, _ := e["type"].(string)
		if noisy[typ] {
			continue
		}
		if a, ok := e["attachment"].(map[string]any); ok {
			if at, _ := a["type"].(string); !keptAttachments[at] {
				e["attachment"] = map[string]any{"type": at}
			}
			for k := range e {
				if !attachmentKeys[k] {
					delete(e, k)
				}
			}
		}
		// The stop hooks' command lines are this machine's own setup.
		if _, ok := e["hookInfos"]; ok {
			e["hookInfos"] = []any{}
		}
		if msg, ok := e["message"].(map[string]any); ok {
			if list, ok := msg["content"].([]any); ok {
				for _, b := range list {
					if bm, ok := b.(map[string]any); ok && bm["type"] == "thinking" {
						bm["signature"] = ""
					}
				}
			}
		}
		var enc bytes.Buffer
		w := json.NewEncoder(&enc)
		w.SetEscapeHTML(false)
		_ = w.Encode(e)
		out.WriteString(scrub(enc.String()))
	}
	return out.Bytes()
}

func writeSample(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("写入样例 %s", path)
}
