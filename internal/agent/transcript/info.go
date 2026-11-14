package transcript

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"clauderc/internal/protocol"
)

// Info is session information read from the transcript (design D6).
type Info struct {
	// Model is the model id of the latest model attachment.
	Model string
	// PermissionMode is the latest permission-mode record. Claude Code only
	// writes a new one when the session stops, so while a hidden attach is
	// open its status line is the better source.
	PermissionMode protocol.PermissionMode
	// Skills are the skills listed to the session, in listing order.
	Skills []protocol.Command
}

// Info returns what the transcript says about the session so far.
func (t *Transcript) Info() Info {
	info := t.info
	info.Skills = append([]protocol.Command(nil), t.info.Skills...)
	return info
}

func (t *Transcript) attachment(a *attachment) {
	switch a.Type {
	case "model":
		if a.Identity.ModelID != "" {
			t.info.Model = a.Identity.ModelID
		}
	case "skill_listing":
		// Content holds "- <name>: <description>" lines; names may contain
		// colons, so they come from Names.
		lines := strings.Split(a.Content, "\n")
		for _, name := range a.Names {
			desc := ""
			for _, l := range lines {
				if rest, ok := strings.CutPrefix(l, "- "+name+": "); ok {
					desc = strings.TrimSpace(rest)
					break
				}
			}
			t.setSkill(protocol.Command{Name: name, Description: desc})
		}
	}
}

// setSkill adds a skill or updates the one with the same name in place.
func (t *Transcript) setSkill(c protocol.Command) {
	for i, s := range t.info.Skills {
		if s.Name == c.Name {
			t.info.Skills[i] = c
			return
		}
	}
	t.info.Skills = append(t.info.Skills, c)
}

// Pending reports what the session waits for the user to decide: the
// session is blocked and its latest tool_use has no tool_result yet
// (design D6). AskUserQuestion is a question, any other tool a permission
// request. It returns nil when nothing is pending.
func (t *Transcript) Pending(blocked bool) *protocol.Pending {
	if !blocked || t.lastTool < 0 {
		return nil
	}
	tc := t.items[t.lastTool].Tool
	if tc.Result != nil {
		return nil
	}
	if tc.ToolName == "AskUserQuestion" {
		var in struct {
			Questions []protocol.Question `json:"questions"`
		}
		if json.Unmarshal(tc.Input, &in) == nil && len(in.Questions) > 0 {
			return &protocol.Pending{ToolUseID: tc.ToolUseID, Kind: protocol.PendingQuestion, Questions: in.Questions}
		}
	}
	return &protocol.Pending{ToolUseID: tc.ToolUseID, Kind: protocol.PendingPermission, ToolName: tc.ToolName, Input: tc.Input}
}

// BuiltinCommands are Claude Code's own slash commands offered for
// completion. Maintained by hand (design Open Questions). Descriptions are
// shown on the phone as is, so they are Chinese and follow the UI mockup.
var BuiltinCommands = []protocol.Command{
	{Name: "add-dir", Description: "添加工作目录"},
	{Name: "agents", Description: "管理子代理配置"},
	{Name: "clear", Description: "清空对话"},
	{Name: "compact", Description: "压缩对话上下文"},
	{Name: "config", Description: "打开设置面板"},
	{Name: "context", Description: "查看上下文用量"},
	{Name: "cost", Description: "查看本次会话的花费和时长"},
	{Name: "doctor", Description: "检查 Claude Code 安装是否正常"},
	{Name: "export", Description: "导出当前对话"},
	{Name: "help", Description: "查看帮助和可用命令"},
	{Name: "hooks", Description: "管理工具事件的 hooks 配置"},
	{Name: "init", Description: "生成 CLAUDE.md"},
	{Name: "mcp", Description: "管理 MCP 服务器"},
	{Name: "memory", Description: "编辑记忆文件"},
	{Name: "model", Description: "切换模型"},
	{Name: "permissions", Description: "管理工具权限的允许和拒绝规则"},
	{Name: "review", Description: "审查 pull request"},
	{Name: "status", Description: "查看 Claude Code 状态"},
	{Name: "todos", Description: "列出当前待办事项"},
	{Name: "usage", Description: "查看套餐用量限额"},
}

// Commands merges BuiltinCommands with the given lists in order, built-ins
// first; a name is listed once, as its first occurrence.
func Commands(lists ...[]protocol.Command) []protocol.Command {
	out := []protocol.Command{}
	seen := map[string]bool{}
	for _, list := range append([][]protocol.Command{BuiltinCommands}, lists...) {
		for _, c := range list {
			if seen[c.Name] {
				continue
			}
			seen[c.Name] = true
			out = append(out, c)
		}
	}
	return out
}

// ErrNotFound means no transcript file exists for the session.
var ErrNotFound = errors.New("transcript: not found")

var sessionIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// HasPending reports whether the transcript of sessionID ends on a tool
// call still waiting for the user (design D6); a missing or unreadable
// transcript has none. Call it only for a blocked session.
func HasPending(claudeDir, sessionID string) bool {
	path, err := Find(claudeDir, sessionID)
	if err != nil {
		return false
	}
	t, err := ReadFile(path)
	return err == nil && t.Pending(true) != nil
}

// Find returns the transcript path <claudeDir>/projects/*/<sessionID>.jsonl.
// Anything that is not a plain session UUID is refused without touching
// the file system.
func Find(claudeDir, sessionID string) (string, error) {
	if !sessionIDPattern.MatchString(sessionID) {
		return "", ErrNotFound
	}
	matches, _ := filepath.Glob(filepath.Join(claudeDir, "projects", "*", sessionID+".jsonl"))
	var best string
	var bestMod int64
	for _, m := range matches {
		fi, err := os.Stat(m)
		if err != nil || !fi.Mode().IsRegular() {
			continue
		}
		if best == "" || fi.ModTime().UnixNano() > bestMod {
			best, bestMod = m, fi.ModTime().UnixNano()
		}
	}
	if best == "" {
		return "", ErrNotFound
	}
	return best, nil
}

// ReadFile parses the transcript at path.
func ReadFile(path string) (*Transcript, error) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer fh.Close()
	return Parse(fh)
}
