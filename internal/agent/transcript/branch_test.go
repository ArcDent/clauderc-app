package transcript_test

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"clauderc/internal/agent/transcript"
	"clauderc/internal/protocol"
)

// The rewind samples are real sessions of three turns, the second editing
// note.txt, rewound to before the second turn through Claude Code's /rewind
// panel and then sent one more message (test/e2e/claude TestRewind).
const rewindTarget = "请用 Edit 工具把 note.txt 里的 v1 改成 v2，然后只回复 TWO"

// shown lists the items still shown, as "kind text" with a tool's name.
func shown(items []transcript.Item) []string {
	var out []string
	for _, it := range items {
		switch it.Kind {
		case protocol.ItemRemoved:
		case protocol.ItemTool:
			out = append(out, "tool "+it.Tool.ToolName)
		default:
			out = append(out, string(it.Kind)+" "+it.Text)
		}
	}
	return out
}

func TestRewoundTurnsAreNotShown(t *testing.T) {
	before := []string{"user 只回复 OK", "assistant OK", "user 只回复 ONE"}
	after := []string{"user 只回复 FOUR", "assistant FOUR"}
	for _, c := range []struct {
		file string
		want []string
	}{
		// Going back in the conversation leaves the second and third turns behind.
		{"rewind-conversation.jsonl", nil},
		{"rewind-both.jsonl", nil},
		// Restoring only the code keeps the conversation whole.
		{"rewind-code.jsonl", []string{"user " + rewindTarget, "tool Read", "tool Edit", "assistant TWO", "user 只回复 THREE", "assistant THREE"}},
	} {
		got := shown(parseFile(t, filepath.Join("testdata", c.file)).Items())
		// The reply to ONE is whatever the model said; only its place matters.
		at := slices.Index(got, "user 只回复 ONE")
		if at < 0 || at+1 >= len(got) || !strings.HasPrefix(got[at+1], "assistant ") {
			t.Fatalf("%s: %q", c.file, got)
		}
		got = slices.Delete(got, at+1, at+2)
		want := slices.Concat(before, c.want, after)
		if strings.Join(got, "\n") != strings.Join(want, "\n") {
			t.Errorf("%s:\n got %q\nwant %q", c.file, got, want)
		}
	}
}

// Following a session as it is rewound: each item left behind comes again
// as a removed item under its seq, the new branch's items come after every
// seq handed out before, and paging back finds none of those left behind.
func TestFollowingARewind(t *testing.T) {
	for _, file := range []string{"rewind-conversation.jsonl", "rewind-both.jsonl"} {
		raw, err := os.ReadFile(filepath.Join("testdata", file))
		if err != nil {
			t.Fatal(err)
		}
		lines := strings.SplitAfter(string(raw), "\n")
		// The session was rewound after the third turn ended.
		cut := slices.IndexFunc(lines, func(l string) bool { return strings.Contains(l, `"只回复 FOUR"`) })
		for cut > 0 && !strings.Contains(lines[cut-1], `"turn_duration"`) {
			cut--
		}
		path := filepath.Join(t.TempDir(), "s.jsonl")
		if err := os.WriteFile(path, []byte(strings.Join(lines[:cut], "")), 0o644); err != nil {
			t.Fatal(err)
		}
		f, err := transcript.Open(path)
		if err != nil {
			t.Fatal(err)
		}
		old, _ := f.Page(0, 1000)
		var gone []int64
		var last int64
		for _, it := range old {
			last = max(last, it.Seq)
			if it.Kind == protocol.ItemUser && (it.Text == rewindTarget || it.Text == "只回复 THREE") ||
				it.Kind == protocol.ItemAssistant && (it.Text == "TWO" || it.Text == "THREE") || it.Kind == protocol.ItemTool {
				gone = append(gone, it.Seq)
			}
		}
		if len(gone) != 6 {
			t.Fatalf("%s: before the rewind %q", file, shown(old))
		}

		fh, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := fh.WriteString(strings.Join(lines[cut:], "")); err != nil {
			t.Fatal(err)
		}
		fh.Close()
		polled, err := f.Poll()
		if err != nil {
			t.Fatal(err)
		}
		var removed []int64
		var added []transcript.Item
		for _, it := range polled {
			if it.Kind == protocol.ItemRemoved {
				removed = append(removed, it.Seq)
			} else {
				added = append(added, it)
			}
		}
		if !slices.Equal(removed, gone) {
			t.Errorf("%s: removed seqs %v, want %v", file, removed, gone)
		}
		if got := shown(added); strings.Join(got, "|") != "user 只回复 FOUR|assistant FOUR" {
			t.Errorf("%s: added %q", file, got)
		}
		for _, it := range added {
			if it.Seq <= last {
				t.Errorf("%s: new item %d %s not after seq %d", file, it.Seq, it.Kind, last)
			}
		}

		// Paging back a few items at a time from the newest.
		var paged []transcript.Item
		for before := int64(0); ; {
			page, more := f.Page(before, 3)
			paged = append(page, paged...)
			if !more {
				break
			}
			before = page[0].Seq
		}
		for _, s := range shown(paged) {
			if strings.Contains(s, "TWO") || strings.Contains(s, "THREE") || strings.HasPrefix(s, "tool ") {
				t.Errorf("%s: paging back finds %q", file, s)
			}
		}
	}
}

// Records hung from an earlier record without leaving a user message
// behind, such as a hook's progress beside a tool call, and the boundary
// /compact writes without a parent, keep every item.
func TestForksThatLeaveNoMessageBehindChangeNothing(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"a","parentUuid":null,"message":{"content":"第一条"}}`,
		`{"type":"assistant","uuid":"b","parentUuid":"a","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}`,
		`{"type":"progress","uuid":"p","parentUuid":"b"}`,
		`{"type":"user","uuid":"c","parentUuid":"b","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`,
		`{"type":"assistant","uuid":"d","parentUuid":"c","message":{"content":[{"type":"text","text":"列好了"}]}}`,
		`{"type":"system","subtype":"compact_boundary","uuid":"e","parentUuid":null,"logicalParentUuid":"d","content":"Conversation compacted"}`,
		`{"type":"user","uuid":"f","parentUuid":"e","message":{"content":"第二条"}}`,
		`{"type":"assistant","uuid":"g","parentUuid":"zzz","message":{"content":[{"type":"text","text":"父条目没见过"}]}}`,
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(shown(tr.Items()), "|"); got != "user 第一条|tool Bash|assistant 列好了|user 第二条|assistant 父条目没见过" {
		t.Errorf("items %q", got)
	}
}

// A user item carries its record's uuid, which chat.rewind names; a queued
// message has none until its user record arrives.
func TestUserItemsCarryTheirUUID(t *testing.T) {
	for _, it := range parseFile(t, filepath.Join("testdata", "rewind-both.jsonl")).Items() {
		if it.Kind == protocol.ItemUser && it.Text == "只回复 FOUR" && !strings.HasPrefix(it.UUID, "b7aafbf0-") {
			t.Errorf("FOUR uuid %q", it.UUID)
		}
		if it.Kind != protocol.ItemUser && it.UUID != "" {
			t.Errorf("%s item has uuid %q", it.Kind, it.UUID)
		}
	}
	queued := `{"type":"queue-operation","operation":"enqueue","content":"排队的消息"}`
	delivered := `{"type":"user","uuid":"u-2","parentUuid":"u-1","message":{"content":"排队的消息"}}`
	first := `{"type":"user","uuid":"u-1","parentUuid":null,"message":{"content":"第一条"}}`
	tr, err := transcript.Parse(strings.NewReader(first + "\n" + queued + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if items := tr.Items(); len(items) != 2 || items[0].UUID != "u-1" || !items[1].Queued || items[1].UUID != "" {
		t.Fatalf("before delivery %+v", items)
	}
	tr, _ = transcript.Parse(strings.NewReader(first + "\n" + queued + "\n" + delivered + "\n"))
	if items := tr.Items(); len(items) != 2 || items[1].Queued || items[1].UUID != "u-2" {
		t.Errorf("after delivery %+v", items)
	}
}

// Records whose content is not text, such as a task reminder (an array),
// a file attachment or an api_error (objects), keep their place in the
// tree, so a rewind across them takes back everything after the point.
func TestRecordsOfAnyShapeKeepTheirPlace(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"a","parentUuid":null,"message":{"content":"第一条"}}`,
		`{"type":"assistant","uuid":"b","parentUuid":"a","message":{"content":[{"type":"text","text":"回复一"}]}}`,
		`{"type":"user","uuid":"c","parentUuid":"b","message":{"content":"第二条"}}`,
		`{"type":"attachment","uuid":"r","parentUuid":"c","attachment":{"type":"task_reminder","content":[{"id":"1","subject":"甲","status":"pending"}],"itemCount":1}}`,
		`{"type":"assistant","uuid":"d","parentUuid":"r","message":{"content":[{"type":"text","text":"回复二"}]}}`,
		`{"type":"attachment","uuid":"f","parentUuid":"d","attachment":{"type":"file","filename":"a.txt","content":{"type":"text","file":{"filePath":"a.txt","content":"x"}}}}`,
		`{"type":"user","uuid":"g","parentUuid":"f","message":{"content":"第三条"}}`,
		`{"type":"system","subtype":"api_error","uuid":"e","parentUuid":"g","error":{"message":"Connection error."},"retryAttempt":1}`,
		`{"type":"assistant","uuid":"h","parentUuid":"e","message":{"content":[{"type":"text","text":"回复三"}]}}`,
		`{"type":"attachment","uuid":"s","parentUuid":"h","attachment":{"type":"skill_listing","content":"- matt-tdd: Test-driven development.","names":["matt-tdd"]}}`,
	}
	parse := func(lines ...string) *transcript.Transcript {
		tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
		if err != nil {
			t.Fatal(err)
		}
		return tr
	}
	tr := parse(lines...)
	if got := strings.Join(shown(tr.Items()), "|"); got != "user 第一条|assistant 回复一|user 第二条|assistant 回复二|user 第三条|assistant 回复三" {
		t.Fatalf("items %q", got)
	}
	if s := tr.Info().Skills; len(s) != 1 || s[0].Name != "matt-tdd" || s[0].Description != "Test-driven development." {
		t.Errorf("skills %+v", s)
	}

	// Rewound in Claude Code to before the second message, then sent one more.
	next := `{"type":"user","uuid":"n","parentUuid":"b","message":{"content":"新的第二条"}}`
	if got := strings.Join(shown(parse(append(lines, next)...).Items()), "|"); got != "user 第一条|assistant 回复一|user 新的第二条" {
		t.Errorf("after a rewind %q", got)
	}

	// Rewound from the phone, before anything is written.
	if removed := tr.Rewind("c"); len(removed) != 4 {
		t.Errorf("rewind removed %v", removed)
	}
	if got := strings.Join(shown(tr.Items()), "|"); got != "user 第一条|assistant 回复一" {
		t.Errorf("after chat.rewind %q", got)
	}
}

// Paging leaves out the items a rewind took away: the first page holds the
// latest items still shown, and paging back from it reaches the first item
// with no page empty or seen twice.
func TestPagesLeaveRewoundItemsOut(t *testing.T) {
	var lines []string
	parent := "null"
	for i := 1; i <= 80; i++ {
		lines = append(lines,
			fmt.Sprintf(`{"type":"user","uuid":"u%d","parentUuid":%s,"message":{"content":"第 %d 条"}}`, i, parent, i),
			fmt.Sprintf(`{"type":"assistant","uuid":"a%d","parentUuid":"u%d","message":{"content":[{"type":"text","text":"回复 %d"}]}}`, i, i, i))
		parent = fmt.Sprintf(`"a%d"`, i)
	}
	// Back to before the 51st message: 30 turns, 60 items, go.
	next := `{"type":"user","uuid":"n1","parentUuid":"a50","message":{"content":"新的第 51 条"}}`
	rewound := func(t *testing.T) *transcript.Transcript {
		tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
		if err != nil {
			t.Fatal(err)
		}
		if removed := tr.Rewind("u51"); len(removed) != 60 {
			t.Fatalf("rewind removed %d items", len(removed))
		}
		return tr
	}
	// pages pages back from the newest as the phone does, from the oldest
	// item of the page before.
	pages := func(tr interface {
		Page(int64, int) ([]transcript.Item, bool)
	}) [][]transcript.Item {
		var out [][]transcript.Item
		for before := int64(0); len(out) < 10; {
			page, more := tr.Page(before, 50)
			out = append(out, page)
			if !more || len(page) == 0 {
				break
			}
			before = page[0].Seq
		}
		return out
	}

	got := pages(rewound(t))
	if len(got) != 2 || len(got[0]) != 50 || len(got[1]) != 50 {
		t.Fatalf("pages of %v items", lens(got))
	}
	if first, last := got[0][0].Text, got[0][49].Text; first != "第 26 条" || last != "回复 50" {
		t.Errorf("first page runs from %q to %q", first, last)
	}

	tr, err := transcript.Parse(strings.NewReader(strings.Join(append(lines, next), "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	got = pages(tr)
	seen := map[int64]bool{}
	var all []transcript.Item
	for _, page := range got {
		if len(page) == 0 {
			t.Errorf("an empty page among %v", lens(got))
		}
		for _, it := range page {
			if it.Kind == protocol.ItemRemoved || seen[it.Seq] {
				t.Errorf("page item %d %s", it.Seq, it.Kind)
			}
			seen[it.Seq] = true
		}
		all = append(page, all...)
	}
	if len(all) != 101 || all[0].Text != "第 1 条" || all[100].Text != "新的第 51 条" {
		t.Errorf("paged %d items, from %q to %q", len(all), all[0].Text, all[len(all)-1].Text)
	}
}

func lens(pages [][]transcript.Item) []int {
	var n []int
	for _, p := range pages {
		n = append(n, len(p))
	}
	return n
}

// Rewound to before the first message, whose record has no parent, the
// next message has none either: the old first turn goes too.
func TestRewoundToBeforeTheFirstMessage(t *testing.T) {
	lines := []string{
		`{"type":"user","uuid":"a","parentUuid":null,"message":{"content":"第一条"}}`,
		`{"type":"assistant","uuid":"b","parentUuid":"a","message":{"content":[{"type":"text","text":"回复一"}]}}`,
		`{"type":"user","uuid":"c","parentUuid":"b","message":{"content":"第二条"}}`,
		`{"type":"assistant","uuid":"d","parentUuid":"c","message":{"content":[{"type":"text","text":"回复二"}]}}`,
		`{"type":"user","uuid":"n","parentUuid":null,"message":{"content":"新的第一条"}}`,
	}
	tr, err := transcript.Parse(strings.NewReader(strings.Join(lines, "\n") + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(shown(tr.Items()), "|"); got != "user 新的第一条" {
		t.Errorf("items %q", got)
	}
	// Rewound from the phone first, as chat.rewind does before anything is written.
	tr, _ = transcript.Parse(strings.NewReader(strings.Join(lines[:4], "\n") + "\n"))
	if removed := tr.Rewind("a"); len(removed) != 4 {
		t.Errorf("rewind removed %v", removed)
	}
}
