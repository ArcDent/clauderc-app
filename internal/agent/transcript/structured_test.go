package transcript_test

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

// toolsByID returns the tool calls of testdata/tools.jsonl by tool_use id.
func toolsByID(t *testing.T) (map[string]*protocol.ToolCall, []byte) {
	t.Helper()
	tr := parseFile(t, "testdata/tools.jsonl")
	byID := map[string]*protocol.ToolCall{}
	var items []protocol.ChatItem
	for _, it := range tr.Items() {
		items = append(items, it.ChatItem)
		if it.Tool != nil {
			byID[it.Tool.ToolUseID] = it.Tool
		}
	}
	raw, err := json.Marshal(items)
	if err != nil {
		t.Fatal(err)
	}
	return byID, raw
}

func TestStructuredEdit(t *testing.T) {
	tools, _ := toolsByID(t)
	edit := tools["toolu_edit"]
	if edit == nil || edit.Result == nil {
		t.Fatalf("edit %+v, want a result", edit)
	}
	if edit.Summary != "编辑 envelope.go" {
		t.Errorf("summary %q, want 编辑 envelope.go", edit.Summary)
	}
	p := edit.Result.Patch
	if len(p) != 1 || p[0].OldStart != 8 || p[0].NewStart != 8 || p[0].OldLines != 3 || p[0].NewLines != 6 {
		t.Fatalf("patch %+v, want one hunk from line 8, 3 old lines and 6 new", p)
	}
	for _, l := range p[0].Lines {
		if l == "" || !strings.ContainsAny(l[:1], " -+") {
			t.Errorf("line %q does not start with a space, - or +", l)
		}
	}
	if p[0].Lines[1] != `-var ErrVersion = errors.New("protocol: unsupported version")` || p[0].Lines[2] != `+var ErrVersion = errors.New("protocol: version must be 1")` {
		t.Errorf("lines %q, want the removed and the added ErrVersion", p[0].Lines[1:3])
	}
}

func TestStructuredWrite(t *testing.T) {
	tools, _ := toolsByID(t)
	create := tools["toolu_create"]
	if create.Summary != "写入 errors.go" {
		t.Errorf("summary %q, want 写入 errors.go", create.Summary)
	}
	want := []protocol.Hunk{{NewStart: 1, NewLines: 3, Lines: []string{"+package protocol", "+", `+const CodeNotFound = "not_found"`}}}
	if !reflect.DeepEqual(create.Result.Patch, want) {
		t.Errorf("new file patch %+v, want every line added: %+v", create.Result.Patch, want)
	}
	update := tools["toolu_update"]
	if p := update.Result.Patch; len(p) != 1 || strings.Join(p[0].Lines, "|") != " # 笔记| |-第一版|+第二版" {
		t.Errorf("overwrite patch %+v, want the structuredPatch hunk", p)
	}
}

func TestStructuredRead(t *testing.T) {
	tools, _ := toolsByID(t)
	read := tools["toolu_read"]
	if read.Result.Lines != 3 || read.Result.Patch != nil {
		t.Errorf("read result %+v, want 3 lines and no patch", read.Result)
	}
	if read.Result.Content != "1\t# proj\n2\t\n3\thello" {
		t.Errorf("read content %q, want the tool_result text unchanged", read.Result.Content)
	}
}

// Results without a structured part look as before: a failed Edit, whose
// toolUseResult is a string, a Grep, and a record written before
// toolUseResult existed.
func TestStructuredAbsent(t *testing.T) {
	tools, _ := toolsByID(t)
	if g := tools["toolu_grep"]; g.Summary != "搜索 ErrVersion" || g.Result.Patch != nil || g.Result.Lines != 0 {
		t.Errorf("grep %+v / %+v, want summary 搜索 ErrVersion and text only", g, g.Result)
	}
	if f := tools["toolu_failed"]; !f.Result.IsError || f.Result.Patch != nil || !strings.Contains(f.Result.Content, "String to replace not found") {
		t.Errorf("failed edit %+v, want the error text only", f.Result)
	}
	if o := tools["toolu_old"]; o.Result.Content != "1\tmodule clauderc" || o.Result.Lines != 0 || o.Result.Patch != nil {
		t.Errorf("old read %+v, want text only", o.Result)
	}
}

func TestStructuredLeavesOutOriginalFile(t *testing.T) {
	_, raw := toolsByID(t)
	if strings.Contains(string(raw), "ORIGINAL-FILE-MUST-NOT-LEAVE") || strings.Contains(string(raw), "originalFile") {
		t.Fatalf("serialized items carry the original file: %s", raw)
	}
}

// A record with two tool_results has one toolUseResult, which is not
// given to either.
func TestStructuredSkipsSharedRecord(t *testing.T) {
	tr := parseLines(t,
		`{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/a"}},{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/b"}}]}}`,
		`{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"1\ta"},{"type":"tool_result","tool_use_id":"t2","content":"1\tb"}]},"toolUseResult":{"type":"text","file":{"numLines":1}}}`,
	)
	for _, it := range tr.Items() {
		if it.Tool != nil && it.Tool.Result != nil && it.Tool.Result.Lines != 0 {
			t.Errorf("%s got lines %d from a shared record", it.Tool.ToolUseID, it.Tool.Result.Lines)
		}
	}
}
