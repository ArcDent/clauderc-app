package transcript_test

import (
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
