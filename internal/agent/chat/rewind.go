package chat

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"sync"
	"time"

	"clauderc/internal/agent"
	"clauderc/internal/agent/attach"
	"clauderc/internal/agent/discovery"
	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// chat.rewind goes through Claude Code's own /rewind panel (see the attach
// package's rewind.go for its screens), checking the screen at each step.

// Rewind timings.
const (
	rewindPanelTimeout = 10 * time.Second
	// The panel takes no keys for about a second after it shows up, so it
	// is let sit that long before the first key.
	rewindSettle    = time.Second
	rewindSettleMax = 5 * time.Second
	// rewindMoveWait is how long a cursor move may take to show before the
	// arrow is pressed once more.
	rewindMoveWait     = 1500 * time.Millisecond
	rewindCloseTimeout = 20 * time.Second
	// rewindRestoredWait is how long Claude Code may take to put the
	// message back into its input box.
	rewindRestoredWait = 3 * time.Second
	rewindClearPresses = 40
)

// rewindLabels are the panel's labels of the ways back.
var rewindLabels = map[protocol.RewindRestore]string{
	protocol.RewindBoth:         "Restore code and conversation",
	protocol.RewindConversation: "Restore conversation",
	protocol.RewindCode:         "Restore code",
}

func rewindFailed(msg string) error { return &opError{protocol.CodeRewindFailed, msg} }

// rewinds maps a session, by rewindKey, to the user message the phone last
// took its conversation back to before. Claude Code writes nothing until the next
// message, so every follower opened for the session takes the same items
// away (see openFollower); once the transcript has moved on, the message
// is off its branch and that does nothing.
var rewinds sync.Map

// rewindKey names a session in rewinds by its Claude Code directory too.
func rewindKey(claudeDir, sessionID string) string { return claudeDir + "\x00" + sessionID }

func (h *Handler) rewind(ctx context.Context, req protocol.Envelope) protocol.Envelope {
	var body protocol.ChatRewindRequest
	target, err := h.decodeSession(ctx, req, &body)
	if err != nil {
		return failWith(req, err)
	}
	label, ok := rewindLabels[body.Restore]
	if body.UUID == "" || !ok {
		return agent.Fail(req, protocol.CodeInvalidParams, "缺少要回溯的消息或回溯方式")
	}
	if !target.Running {
		return failWith(req, errStopped)
	}
	f := openFollower(h.opts.Source.ClaudeDir, target.SessionID)
	if f == nil {
		return failWith(req, rewindFailed("读不到会话记录，没有回溯"))
	}
	ups, text, ok := rewindTarget(f, body.UUID)
	if !ok {
		return agent.Fail(req, protocol.CodeNotFound, "会话里找不到这条消息，它可能已经被回溯掉了")
	}
	if target.SessionState(f.Pending(target.Blocked()) != nil) != protocol.StateIdle {
		return failWith(req, rewindFailed("会话正在工作或等你确认，停下来以后再回溯"))
	}
	conversation := body.Restore != protocol.RewindCode
	err = h.withTerm(ctx, target, func(t *attach.Term) error { return runRewind(t, ups, text, label, conversation) })
	if err != nil {
		return failWith(req, err)
	}
	if conversation {
		h.rewound(target, body.UUID)
	}
	return agent.Reply(req, struct{}{})
}

// rewindTarget finds the user message uuid among those of the current
// branch, which the panel lists oldest first above "(current)", and
// returns how many rows above "(current)" it is and its text.
func rewindTarget(f *transcript.Follower, uuid string) (ups int, text string, ok bool) {
	users := branchMessages(f)
	for k, it := range users {
		if it.UUID == uuid {
			return len(users) - k, it.Text, true
		}
	}
	return 0, "", false
}

// runRewind opens the panel, moves up ups rows, presses Enter only on the
// row showing text, picks label and presses Enter again. Taking the
// conversation back puts the message into Claude Code's input box, which
// is then cleared. Any step that does not match leaves the panel with Esc.
func runRewind(t *attach.Term, ups int, text, label string, conversation bool) error {
	if err := checkInput(t); err != nil {
		return err
	}
	if !inputEmpty(t.Screen()) {
		return rewindFailed("电脑上的输入框里有还没发出去的字，清空以后再回溯")
	}
	leave := func(err error) error {
		for range 2 {
			screen := t.Screen()
			if !attach.RewindOpen(screen) {
				break
			}
			if t.Send(attach.CancelKeys()) != nil {
				break
			}
			t.WaitFor(func(s string) bool { return s != screen }, rewindMoveWait)
			t.WaitQuiet(redrawQuiet, redrawMax)
		}
		return err
	}
	if err := t.Send(attach.OpenRewindKeys()); err != nil {
		return leave(err)
	}
	if !t.WaitFor(func(s string) bool { _, ok := attach.ParseRewindList(s); return ok }, rewindPanelTimeout) {
		return leave(rewindFailed("电脑上的回溯面板没有出现，已取消"))
	}
	t.WaitQuiet(rewindSettle, rewindSettleMax)
	if cur := listCursor(t.Screen()); cur != attach.RewindCurrent {
		return leave(rewindFailed("回溯面板的光标没有停在最下面，已按 Esc 退出"))
	}
	for range ups {
		if err := move(t, attach.UpKeys(), listCursor); err != nil {
			return leave(err)
		}
	}
	if cur := listCursor(t.Screen()); !attach.RewindRowMatches(cur, text) {
		return leave(rewindFailed(fmt.Sprintf("回溯面板里光标那一行是「%s」，不是要回溯的那条消息，已按 Esc 退出", cur)))
	}
	if err := t.Send(attach.EnterKeys()); err != nil {
		return leave(err)
	}
	if !t.WaitFor(func(s string) bool { _, ok := attach.ParseRewindOptions(s); return ok }, rewindPanelTimeout) {
		return leave(rewindFailed("回溯面板没有列出回去的方式，已按 Esc 退出"))
	}
	t.WaitQuiet(rewindSettle, rewindSettleMax)
	opts, _ := attach.ParseRewindOptions(t.Screen())
	from, to := 0, 0
	for _, o := range opts {
		if o.Cursor {
			from = o.Number
		}
		if o.Label == label {
			to = o.Number
		}
	}
	if to == 0 {
		return leave(rewindFailed(fmt.Sprintf("回溯面板上没有「%s」这一项，这一轮可能没改过文件，已按 Esc 退出", label)))
	}
	keys := attach.DownKeys()
	if to < from {
		keys = attach.UpKeys()
	}
	for range max(to-from, from-to) {
		if err := move(t, keys, optionCursor); err != nil {
			return leave(err)
		}
	}
	if cur := optionCursor(t.Screen()); cur != label {
		return leave(rewindFailed(fmt.Sprintf("回溯面板的光标停在「%s」，不是「%s」，已按 Esc 退出", cur, label)))
	}
	if err := t.Send(attach.EnterKeys()); err != nil {
		return leave(err)
	}
	if !t.WaitFor(func(s string) bool { return !attach.RewindOpen(s) }, rewindCloseTimeout) {
		return leave(rewindFailed("按下回车后回溯面板没有关上，已按 Esc 退出"))
	}
	if conversation {
		t.WaitFor(func(s string) bool { return !inputEmpty(s) }, rewindRestoredWait)
		for range rewindClearPresses {
			if inputEmpty(t.Screen()) {
				return nil
			}
			if err := t.Send(attach.ClearLineKeys()); err != nil {
				return err
			}
			t.WaitQuiet(redrawQuiet, redrawMax)
		}
		if !inputEmpty(t.Screen()) {
			return rewindFailed("已经回溯，但没能清空电脑上的输入框")
		}
	}
	return nil
}

// move presses an arrow and waits for the cursor cursor reads to leave
// its row, pressing once more when the first press was not taken.
func move(t *attach.Term, keys []attach.Step, cursor func(string) string) error {
	was := cursor(t.Screen())
	for range 2 {
		if err := t.Send(keys); err != nil {
			return err
		}
		if t.WaitFor(func(s string) bool { return cursor(s) != was }, rewindMoveWait) {
			t.WaitQuiet(redrawQuiet, redrawMax)
			return nil
		}
	}
	return rewindFailed("回溯面板不理方向键，已按 Esc 退出")
}

func listCursor(screen string) string {
	rows, _ := attach.ParseRewindList(screen)
	for _, r := range rows {
		if r.Cursor {
			return r.Text
		}
	}
	return ""
}

func optionCursor(screen string) string {
	opts, _ := attach.ParseRewindOptions(screen)
	for _, o := range opts {
		if o.Cursor {
			return o.Label
		}
	}
	return ""
}

// inputEmpty reports whether Claude Code's input box shows and holds
// nothing: its prompt line is bare and the box's lower rule follows it.
func inputEmpty(screen string) bool {
	lines := strings.Split(screen, "\n")
	for i := len(lines) - 2; i > 0; i-- {
		if !strings.HasPrefix(strings.TrimSpace(lines[i-1]), "─") {
			continue
		}
		rest, ok := strings.CutPrefix(lines[i], "❯")
		if !ok {
			continue
		}
		return strings.TrimSpace(strings.ReplaceAll(rest, " ", " ")) == "" && strings.HasPrefix(strings.TrimSpace(lines[i+1]), "─")
	}
	return false
}

// rewound notes that the session's conversation went back to before the
// message uuid, and takes those items out of its open chat at once.
func (h *Handler) rewound(target discovery.Target, uuid string) {
	rewinds.Store(rewindKey(h.opts.Source.ClaudeDir, target.SessionID), uuid)
	h.mu.Lock()
	s := h.chats[target.ID]
	h.mu.Unlock()
	if s == nil {
		return
	}
	s.mu.Lock()
	f := s.follower
	s.mu.Unlock()
	if f == nil {
		return
	}
	if items := f.Rewind(uuid); len(items) > 0 {
		s.pushAll(protocol.EventChatItems, protocol.ChatItemsEvent{SessionID: s.id, Items: toProtocol(items)})
	}
}

// rewindWatch follows Claude Code's /rewind panel on an open chat's
// screen, so that a rewind made on the computer or in the terminal view
// takes its messages away before the next record shows the new branch.
type rewindWatch struct {
	open bool
	// ups is how many rows above "(current)" the list's cursor last was;
	// quote and label are the message and the way back under the cursor
	// last seen on the second screen.
	ups          int
	quote, label string
	// closed is when the panel was first seen gone: Claude Code may take
	// a moment, restoring files, before the message is back in the input.
	closed time.Time
}

// rewindRestoredGrace is how long after the panel closes the message may
// take to show in the input box.
const rewindRestoredGrace = 5 * time.Second

// watchRewind reads one screen of an open chat. Once the panel closes
// after a way back that takes the conversation, and Claude Code's input
// box holds the quoted message, as it does only then (Esc and Never mind
// leave it empty), the conversation goes back to before the message of the
// branch the quote shows. The screen is read once a second, so the list's
// cursor may have moved unseen: the quote finds the message, and the row
// last seen only picks among messages that read the same. Anything that
// does not add up changes nothing.
func (s *session) watchRewind(screen string) {
	if rows, ok := attach.ParseRewindList(screen); ok {
		s.rw.open, s.rw.label, s.rw.closed = true, "", time.Time{}
		for i, r := range rows {
			if r.Cursor {
				s.rw.ups = len(rows) - 1 - i
			}
		}
		return
	}
	if _, ok := attach.ParseRewindOptions(screen); ok {
		s.rw.open, s.rw.quote, s.rw.label, s.rw.closed = true, attach.RewindQuote(screen), optionCursor(screen), time.Time{}
		return
	}
	if !s.rw.open {
		return
	}
	w := s.rw
	if w.label != rewindLabels[protocol.RewindBoth] && w.label != rewindLabels[protocol.RewindConversation] || w.quote == "" {
		s.rw = rewindWatch{}
		return
	}
	text, ok := inputLine(screen)
	if !ok || !attach.RewindRowMatches(w.quote, text) && !attach.RewindRowMatches(text, w.quote) {
		if w.closed.IsZero() {
			s.rw.closed = time.Now()
		} else if time.Since(w.closed) > rewindRestoredGrace {
			s.rw = rewindWatch{}
		}
		return
	}
	s.rw = rewindWatch{}
	s.mu.Lock()
	f, target := s.follower, s.target
	s.mu.Unlock()
	if f == nil {
		return
	}
	users := branchMessages(f)
	var match []int
	for k, it := range users {
		if attach.RewindRowMatches(w.quote, it.Text) {
			match = append(match, k)
		}
	}
	switch {
	case len(match) == 1:
		s.h.rewound(target, users[match[0]].UUID)
	case len(match) > 1 && slices.Contains(match, len(users)-w.ups):
		s.h.rewound(target, users[len(users)-w.ups].UUID)
	}
}

// branchMessages are the user's messages on the current branch, oldest
// first, as the /rewind panel lists them above "(current)".
func branchMessages(f *transcript.Follower) []transcript.Item {
	items, _ := f.Page(0, int(^uint(0)>>1))
	var users []transcript.Item
	for _, it := range items {
		if it.Kind == protocol.ItemUser && !it.Queued && it.UUID != "" {
			users = append(users, it)
		}
	}
	return users
}
