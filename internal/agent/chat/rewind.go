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
	// rewindMoveWait is how long a cursor move may take to show.
	rewindMoveWait     = 1500 * time.Millisecond
	rewindCloseTimeout = 20 * time.Second
	rewindClearPresses = 40
	// rewindLeavePresses bounds the Esc presses that close the panel.
	rewindLeavePresses = 6
)

// rewindTotal bounds a rewind up to its last Enter. rewindAfter more
// bound what follows, leaving the panel or clearing the input box, so the
// answer is in before the phone stops waiting at 120 s
// (CHAT_REWIND_TIMEOUT_MILLIS on its side).
var rewindTotal = 90 * time.Second

const rewindAfter = 20 * time.Second

// beforeRewindTerm runs between a rewind's checks and taking the
// terminal; tests set it.
var beforeRewindTerm = func() {}

// rewindLabels are the panel's labels of the ways back.
var rewindLabels = map[protocol.RewindRestore]string{
	protocol.RewindBoth:         "Restore code and conversation",
	protocol.RewindConversation: "Restore conversation",
	protocol.RewindCode:         "Restore code",
}

func rewindFailed(msg string) error { return &opError{protocol.CodeRewindFailed, msg} }

// rewinds maps a session, by rewindKey, to the rewindMark of the last
// rewind that took its conversation back. Claude Code writes nothing until
// the next message, so every follower opened for the session takes the
// same items away (see openFollower) until the transcript moves on: then
// the next record shows the new branch by itself, or the rewind did not
// happen, and the mark is dropped.
var rewinds sync.Map

// rewindMark is a rewind to before the user message uuid, made while last
// was the latest record of the transcript.
type rewindMark struct{ uuid, last string }

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
	msgs, k, ok := rewindTarget(f, body.UUID)
	if !ok {
		return agent.Fail(req, protocol.CodeNotFound, "会话里找不到这条消息，它可能已经被回溯掉了")
	}
	if target.SessionState(f.Pending(target.Blocked()) != nil) != protocol.StateIdle {
		return failWith(req, rewindFailed("会话正在工作或等你确认，停下来以后再回溯"))
	}
	conversation := body.Restore != protocol.RewindCode
	var res protocol.ChatRewindResponse
	var restored bool
	beforeRewindTerm()
	err = h.withTerm(ctx, target, func(t *attach.Term) error {
		// The session may have started working while the terminal was being
		// taken: it is checked again now that no one else can type.
		now, err := h.lookup(ctx, target.ID)
		if _, perr := f.Poll(); err != nil || perr != nil || now.SessionID != target.SessionID || now.SessionState(f.Pending(now.Blocked()) != nil) != protocol.StateIdle {
			return rewindFailed("会话正在工作或等你确认，停下来以后再回溯")
		}
		// Records written meanwhile move the message's row.
		if msgs, k, ok = rewindTarget(f, body.UUID); !ok {
			return &opError{protocol.CodeNotFound, "会话里找不到这条消息，它可能已经被回溯掉了"}
		}
		res.Notice, restored, err = runRewind(t, msgs, k, label, conversation)
		return err
	})
	if err != nil {
		return failWith(req, err)
	}
	if restored {
		h.rewound(target, rewindMark{body.UUID, f.Last()})
	}
	return agent.Reply(req, res)
}

// rewindTarget returns the texts of the user messages of the current
// branch, oldest first as the panel lists them above "(current)", and
// which of them is the message uuid.
func rewindTarget(f *transcript.Follower, uuid string) (msgs []string, k int, ok bool) {
	k = -1
	for i, it := range branchMessages(f) {
		msgs = append(msgs, it.Text)
		if it.UUID == uuid {
			k = i
		}
	}
	return msgs, k, k >= 0
}

// runRewind opens the panel, checks that its list shows msgs, moves up to
// msgs[k] one row at a time, presses Enter only with the cursor on that
// row showing its text, picks label for that message and presses Enter
// again. Any step that does not match, or a rewind still short of that
// last Enter after rewindTotal, leaves the panel with Esc.
//
// Taking the conversation back puts the message into Claude Code's input
// box, which is then cleared; restored says it came back, which is what
// shows the conversation went back. A rewind past that last Enter has
// happened: what then goes wrong only makes notice.
func runRewind(t *attach.Term, msgs []string, k int, label string, conversation bool) (notice string, restored bool, err error) {
	ups, text := len(msgs)-k, msgs[k]
	deadline := time.Now().Add(rewindTotal)
	replyBy := deadline.Add(rewindAfter)
	until := func(d time.Duration) time.Duration { return max(min(d, time.Until(replyBy)), 0) }
	late := func(err error) error {
		if !time.Now().Before(deadline) {
			return rewindFailed(fmt.Sprintf("回溯 %d 秒还没做完，已按 Esc 退出", int(rewindTotal.Seconds())))
		}
		return err
	}
	if err := checkInput(t); err != nil {
		return "", false, err
	}
	if !inputEmpty(t.Screen()) {
		return "", false, rewindFailed("电脑上的输入框里有还没发出去的字，清空以后再回溯")
	}
	// leave presses Esc until the panel is gone, one press at a time: the
	// list a press goes back to takes no keys for about a second.
	leave := func(err error) (string, bool, error) {
		for range rewindLeavePresses {
			screen := t.Screen()
			if !attach.RewindShown(screen) {
				return "", false, err
			}
			if time.Now().After(replyBy) || t.Send(attach.CancelKeys()) != nil {
				break
			}
			if t.WaitFor(func(s string) bool { return s != screen }, until(rewindMoveWait)) {
				t.WaitQuiet(rewindSettle, until(2*time.Second))
			}
		}
		if attach.RewindShown(t.Screen()) {
			return "", false, rewindFailed("电脑上的回溯面板没能用 Esc 关上，请在终端视图里关掉它")
		}
		return "", false, err
	}
	if err := t.Send(attach.OpenRewindKeys()); err != nil {
		return leave(err)
	}
	if !t.WaitFor(func(s string) bool { _, ok := attach.ParseRewindList(s); return ok }, rewindPanelTimeout) {
		if attach.RewindShown(t.Screen()) {
			return leave(rewindFailed("认不出电脑上的回溯面板，已按 Esc 退出"))
		}
		return leave(rewindFailed("电脑上的回溯面板没有出现，已取消"))
	}
	t.WaitQuiet(rewindSettle, untilDeadline(deadline, rewindSettleMax))
	if at, _, ok := listAt(t.Screen()); !ok || at != 0 {
		return leave(rewindFailed("回溯面板的光标没有停在最下面，已按 Esc 退出"))
	}
	if !listShows(t.Screen(), msgs) {
		return leave(rewindFailed("回溯面板列出的消息和会话记录对不上，已按 Esc 退出"))
	}
	for n := 1; n <= ups; n++ {
		if err := move(t, attach.UpKeys(), func(s string) (int, bool) { at, _, ok := listAt(s); return at, ok }, n, deadline); err != nil {
			return leave(late(err))
		}
	}
	if at, cur, ok := listAt(t.Screen()); !ok || at != ups || !attach.RewindRowMatches(cur, text) || !listShows(t.Screen(), msgs) {
		return leave(rewindFailed(fmt.Sprintf("回溯面板里光标那一行是「%s」，不是要回溯的那条消息，已按 Esc 退出", cur)))
	}
	if !time.Now().Before(deadline) {
		return leave(late(nil))
	}
	if err := t.Send(attach.EnterKeys()); err != nil {
		return leave(err)
	}
	if !t.WaitFor(func(s string) bool { _, ok := attach.ParseRewindOptions(s); return ok }, min(rewindPanelTimeout, time.Until(deadline))) {
		return leave(late(rewindFailed("回溯面板没有列出回去的方式，已按 Esc 退出")))
	}
	t.WaitQuiet(rewindSettle, untilDeadline(deadline, rewindSettleMax))
	if q := attach.RewindQuote(t.Screen()); !attach.RewindRowMatches(q, text) {
		return leave(rewindFailed(fmt.Sprintf("回溯面板要回到的是「%s」之前，不是要回溯的那条消息，已按 Esc 退出", q)))
	}
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
		return leave(rewindFailed(fmt.Sprintf("回溯面板上没有「%s」这一项，这条消息之后没改过文件，已按 Esc 退出", label)))
	}
	if from == 0 {
		return leave(rewindFailed("认不出回溯面板选项上的光标，已按 Esc 退出"))
	}
	keys, step := attach.DownKeys(), 1
	if to < from {
		keys, step = attach.UpKeys(), -1
	}
	for n := from + step; n != to+step; n += step {
		if err := move(t, keys, optionAt, n, deadline); err != nil {
			return leave(late(err))
		}
	}
	if cur := optionCursor(t.Screen()); cur != label {
		return leave(rewindFailed(fmt.Sprintf("回溯面板的光标停在「%s」，不是「%s」，已按 Esc 退出", cur, label)))
	}
	if !time.Now().Before(deadline) {
		return leave(late(nil))
	}
	if err := t.Send(attach.EnterKeys()); err != nil {
		return leave(err)
	}
	// Past this Enter the rewind has happened, Esc or not.
	if !t.WaitFor(func(s string) bool { return !attach.RewindShown(s) }, until(rewindCloseTimeout)) {
		return "", false, rewindFailed("按下回车后电脑上的回溯面板没有关上，请在终端视图里看一下")
	}
	if !conversation {
		return "", false, nil
	}
	// Restoring files first, Claude Code may take a while to fill the input.
	if !t.WaitFor(inputFilled, until(rewindRestoredGrace)) {
		return "已经回溯，但 Claude Code 没把这条消息放回电脑上的输入框，聊天里的消息等下一条消息后再更新", false, nil
	}
	// Each press clears a line; one that changes nothing will not clear it.
	for range rewindClearPresses {
		screen := t.Screen()
		if inputEmpty(screen) {
			return "", true, nil
		}
		if time.Now().After(replyBy) || t.Send(attach.ClearLineKeys()) != nil || !t.WaitFor(func(s string) bool { return s != screen }, until(rewindMoveWait)) {
			break
		}
		t.WaitQuiet(redrawQuiet, until(redrawMax))
	}
	if inputEmpty(t.Screen()) {
		return "", true, nil
	}
	return "已经回溯，但没能清空电脑上的输入框，原消息还留在那里", true, nil
}

// move presses an arrow once and waits, until deadline at most, for the
// cursor to stand where at reads want. A press drawn late, or not taken,
// fails rather than being pressed again, which could take the cursor a
// row too far.
func move(t *attach.Term, keys []attach.Step, at func(string) (int, bool), want int, deadline time.Time) error {
	if !time.Now().Before(deadline) {
		return rewindFailed("回溯超时，已按 Esc 退出")
	}
	if err := t.Send(keys); err != nil {
		return err
	}
	there := func(s string) bool { n, ok := at(s); return ok && n == want }
	if !t.WaitFor(there, untilDeadline(deadline, rewindMoveWait)) {
		return rewindFailed("回溯面板的光标没有跟着方向键移到下一行，已按 Esc 退出")
	}
	t.WaitQuiet(redrawQuiet, untilDeadline(deadline, redrawMax))
	if !there(t.Screen()) {
		return rewindFailed("回溯面板的光标移过了头，已按 Esc 退出")
	}
	return nil
}

// untilDeadline is d, or what is left before deadline when that is less.
func untilDeadline(deadline time.Time, d time.Duration) time.Duration {
	return max(min(d, time.Until(deadline)), 0)
}

// inputFilled reports whether Claude Code's input box shows and holds
// something; a screen without the box, as while it redraws, does not.
func inputFilled(screen string) bool {
	text, ok := inputText(screen)
	return ok && strings.TrimSpace(text) != ""
}

// listAt returns how many rows above "(current)" the list's cursor is and
// the text of its row; ok is false when the list or its cursor does not
// show.
func listAt(screen string) (ups int, text string, ok bool) {
	l, ok := attach.ParseRewindList(screen)
	for i, r := range l.Rows {
		if r.Cursor {
			return l.Ups(i), r.Text, true
		}
	}
	return 0, "", false
}

// listShows reports whether the list shows msgs, the user messages of the
// branch oldest first, row for row: it lists as many messages, and each
// row on screen is the message at its place.
func listShows(screen string, msgs []string) bool {
	l, ok := attach.ParseRewindList(screen)
	if !ok || l.Above+len(l.Rows)+l.Below != len(msgs)+1 {
		return false
	}
	for i, r := range l.Rows {
		if ups := l.Ups(i); ups > 0 && !attach.RewindRowMatches(r.Text, msgs[len(msgs)-ups]) || ups == 0 && r.Text != attach.RewindCurrent {
			return false
		}
	}
	return true
}

// optionAt returns the number of the way back under the cursor.
func optionAt(screen string) (int, bool) {
	opts, _ := attach.ParseRewindOptions(screen)
	for _, o := range opts {
		if o.Cursor {
			return o.Number, true
		}
	}
	return 0, false
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

// rewound notes that the session's conversation went back as m says, and
// takes those items out of its open chat at once.
func (h *Handler) rewound(target discovery.Target, m rewindMark) {
	rewinds.Store(rewindKey(h.opts.Source.ClaudeDir, target.SessionID), m)
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
	if items := f.Rewind(m.uuid); len(items) > 0 {
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

// restoredQuote reports whether the input box holds the message the
// panel quoted: the whole quote, without the "…" a long one ends in,
// starts the input. Spaces and line breaks are left out, the input box
// wrapping where the panel does not, so a few letters typed after the
// panel closed are never taken for the message put back.
func restoredQuote(quote, input string) bool {
	squash := func(s string) string { return strings.Join(strings.Fields(s), "") }
	q := squash(strings.TrimSuffix(strings.TrimSpace(quote), "…"))
	return q != "" && strings.HasPrefix(squash(input), q)
}

// watchRewind reads one screen of an open chat. Once the panel closes
// after a way back that takes the conversation, and Claude Code's input
// box holds the quoted message, as it does only then (Esc and Never mind
// leave it empty), the conversation goes back to before the message of the
// branch the quote shows. The screen is read once a second, so the list's
// cursor may have moved unseen: the quote finds the message, and the row
// last seen only picks among messages that read the same. Anything that
// does not add up changes nothing.
func (s *session) watchRewind(screen string) {
	if l, ok := attach.ParseRewindList(screen); ok {
		s.rw.open, s.rw.label, s.rw.closed = true, "", time.Time{}
		for i, r := range l.Rows {
			if r.Cursor {
				s.rw.ups = l.Ups(i)
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
	text, ok := inputText(screen)
	if !ok || !restoredQuote(w.quote, text) {
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
		s.h.rewound(target, rewindMark{users[match[0]].UUID, f.Last()})
	case len(match) > 1 && slices.Contains(match, len(users)-w.ups):
		s.h.rewound(target, rewindMark{users[len(users)-w.ups].UUID, f.Last()})
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
