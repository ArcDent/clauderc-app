package chat

import "testing"

func TestInputLine(t *testing.T) {
	rule := "──────────────────────────────── e2e-probe ─"
	// As Claude Code 2.1.326 draws it: an earlier /mcp in the conversation,
	// then the input box with a no-break space after its ❯.
	screen := "❯ /mcp\n\n⏺ OK\n" + rule + "\n❯ \n────────────\n  ⏸ manual mode on"
	if text, ok := inputLine(screen); !ok || text != "" {
		t.Errorf("empty input = %q, %v", text, ok)
	}
	if inInput(screen, "/mcp") {
		t.Errorf("the earlier /mcp above counts as the input")
	}
	typed := "❯ /mcp\n" + rule + "\n❯ /mcp\n────────────\n  ❯ /mcp          管理 MCP 服务器"
	if !inInput(typed, "/mcp") {
		t.Errorf("typed /mcp is not seen in the input")
	}
	panel := "❯ /mcp\n   Manage MCP servers\n   ❯ ✔ chrome-devtools     26 tools\n   ↑/↓ to navigate · Enter to confirm · Esc to cancel"
	if _, ok := inputLine(panel); ok {
		t.Errorf("a panel has no input box")
	}
}
