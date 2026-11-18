package chat_test

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"clauderc/internal/protocol"
)

// toolLines is a Bash tool_use and its tool_result with output out.
func toolLines(id, out string) string {
	use, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "tool_use", "id": id, "name": "Bash", "input": map[string]any{"command": "go test -v ./..."}}}}})
	res, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user",
		"content": []any{map[string]any{"type": "tool_result", "tool_use_id": id, "content": out}}}})
	return string(use) + "\n" + string(res) + "\n"
}

// bigOutput is up to n bytes of test output lines, ending on a whole
// character.
func bigOutput(n int) string {
	var sb strings.Builder
	for i := 0; sb.Len() < n; i++ {
		sb.WriteString("--- PASS: TestEnvelopeRoundTrip (0.00s) 第 ")
		sb.WriteString(strings.Repeat("x", i%7))
		sb.WriteString(" 行\n")
	}
	s := sb.String()[:n]
	for !utf8.ValidString(s) {
		s = s[:len(s)-1]
	}
	return s
}

func toolItem(t *testing.T, items []protocol.ChatItem, id string) *protocol.ToolCall {
	t.Helper()
	for _, it := range items {
		if it.Tool != nil && it.Tool.ToolUseID == id {
			return it.Tool
		}
	}
	t.Fatalf("no tool item %s in %d items", id, len(items))
	return nil
}

func TestLongToolOutputIsTruncatedAndFetchedWhole(t *testing.T) {
	f := newFixture(t, setup{})
	big, small := bigOutput(20<<10), bigOutput(protocol.MaxToolOutputBytes)
	f.appendTranscript(t, toolLines("toolu_big", big)+toolLines("toolu_small", small))

	var open protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open); err != nil {
		t.Fatal(err)
	}
	got := toolItem(t, open.Items, "toolu_big").Result
	if !got.Truncated || len(got.Content) > protocol.MaxToolOutputBytes || !strings.HasPrefix(big, got.Content) || len(got.Content) < protocol.MaxToolOutputBytes/2 {
		t.Fatalf("chat.open result: truncated %v, %d bytes, prefix of the output %v", got.Truncated, len(got.Content), strings.HasPrefix(big, got.Content))
	}
	if s := toolItem(t, open.Items, "toolu_small").Result; s.Truncated || s.Content != small {
		t.Errorf("8 KB result: truncated %v, %d bytes, want it whole and unmarked", s.Truncated, len(s.Content))
	}

	f.appendTranscript(t, toolLines("toolu_new", big))
	var ev protocol.ChatItemsEvent
	for ev.Items == nil || ev.Items[len(ev.Items)-1].Tool == nil || ev.Items[len(ev.Items)-1].Tool.Result == nil {
		if err := json.Unmarshal(f.nextEvent(t, protocol.EventChatItems, 3*time.Second).Body, &ev); err != nil {
			t.Fatal(err)
		}
	}
	if r := toolItem(t, ev.Items, "toolu_new").Result; !r.Truncated || len(r.Content) > protocol.MaxToolOutputBytes {
		t.Errorf("chat.items result: truncated %v, %d bytes", r.Truncated, len(r.Content))
	}

	var whole protocol.ChatToolOutputResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatToolOutput, protocol.ChatToolOutputRequest{SessionID: shortID, ToolUseID: "toolu_big"}), &whole); err != nil {
		t.Fatal(err)
	}
	if whole.Content != big || whole.Truncated {
		t.Errorf("chat.toolOutput: %d bytes, truncated %v, want all %d bytes", len(whole.Content), whole.Truncated, len(big))
	}

	// After the chat is closed the transcript file is read instead.
	f.ok(t, "c1", protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: shortID})
	var again protocol.ChatToolOutputResponse
	if err := json.Unmarshal(f.ok(t, "c2", protocol.MethodChatToolOutput, protocol.ChatToolOutputRequest{SessionID: shortID, ToolUseID: "toolu_new"}), &again); err != nil {
		t.Fatal(err)
	}
	if again.Content != big {
		t.Errorf("chat.toolOutput without an open chat: %d bytes, want %d", len(again.Content), len(big))
	}
}

func TestToolOutputRejects(t *testing.T) {
	f := newFixture(t, setup{})
	cases := []struct {
		body protocol.ChatToolOutputRequest
		code protocol.ErrorCode
	}{
		{protocol.ChatToolOutputRequest{SessionID: shortID, ToolUseID: "toolu_missing"}, protocol.CodeNotFound},
		{protocol.ChatToolOutputRequest{SessionID: shortID}, protocol.CodeInvalidParams},
		{protocol.ChatToolOutputRequest{ToolUseID: "toolu_1"}, protocol.CodeInvalidParams},
		{protocol.ChatToolOutputRequest{SessionID: "zzz99999", ToolUseID: "toolu_1"}, protocol.CodeSessionNotFound},
	}
	for _, c := range cases {
		res := f.call(t, "c1", protocol.MethodChatToolOutput, c.body)
		if res.Error == nil || res.Error.Code != c.code {
			t.Errorf("%+v: error %+v, want %s", c.body, res.Error, c.code)
		}
	}
}
