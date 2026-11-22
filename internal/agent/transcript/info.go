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
	// Limit is set while the transcript ends on a plan usage limit: no
	// user message or reply came after it.
	Limit *Limit
}

// Info returns what the transcript says about the session so far.
func (t *Transcript) Info() Info {
	info := t.info
	info.Skills = append([]protocol.Command(nil), t.info.Skills...)
	if t.info.Limit != nil {
		l := *t.info.Limit
		info.Limit = &l
	}
	return info
}

// attachment applies one attachment record and returns the indexes of the
// items it added.
func (t *Transcript) attachment(a *attachment) []int {
	switch a.Type {
	case "model":
		if a.Identity.ModelID != "" {
			t.info.Model = a.Identity.ModelID
		}
	case "skill_listing":
		// Content holds "- <name>: <description>" lines; names may contain
		// colons, so they come from Names.
		lines := strings.Split(textOf(a.Content), "\n")
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
	case "queued_command":
		// Something taken in by a running turn; the queue remove next to
		// it often carries the same text. A message of the user's, origin
		// human, shows as its queued item already, and a subagent's
		// report is not shown.
		prompt := userText(textOf(a.Prompt))
		switch {
		case a.Origin.Kind == "task-notification":
			return t.pairedNotice(noticeFromAttachment, prompt, "", taskNotificationText(prompt))
		case a.Origin.Kind == "peer" && a.Origin.SenderTaskID == "":
			title, body := peerNotice(a.Origin, prompt)
			return t.pairedNotice(noticeFromAttachment, prompt, title, body)
		}
	}
	return nil
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
// completion: those of a background session's popup in Claude Code
// 2.1.326 that run without opening a panel, plus /fork and /plan, tried
// in 2.1.328 (test/e2e/claude TestSlashCommon). Maintained by hand (design
// Open Questions); test/e2e/claude TestSlashList compares them with the
// real popup. Descriptions are shown on the phone as is, so they are
// Chinese and follow the UI mockup.
var BuiltinCommands = []protocol.Command{
	{Name: "add-dir", Description: "添加工作目录"},
	{Name: "clear", Description: "清空对话"},
	{Name: "compact", Description: "压缩对话上下文"},
	{Name: "context", Description: "查看上下文用量"},
	{Name: "fork", Description: "把这段对话复制成一个新的后台会话，这里照常继续"},
	{Name: "goal", Description: "设定 Claude 停下前要核对的目标"},
	{Name: "model", Description: "切换模型"},
	{Name: "plan", Description: "打开计划模式或查看计划"},
	{Name: "recap", Description: "生成一句话的会话回顾"},
	{Name: "reload-plugins", Description: "让改过的插件在本会话生效"},
	{Name: "reload-skills", Description: "重新读取磁盘上新增或改过的技能"},
	{Name: "rename", Description: "重命名会话"},
	{Name: "subtask", Description: "带上完整上下文派出子代理，结果回到这里"},
}

// PanelCommands are the built-ins that open a panel which the phone drives
// in the terminal view, chosen by the user on 2026-11-17. They come after
// BuiltinCommands in completion.
var PanelCommands = []protocol.Command{
	{Name: "mcp", Description: "管理 MCP 服务器", Panel: true},
	{Name: "config", Description: "打开设置", Panel: true},
	{Name: "permissions", Description: "管理工具的允许和拒绝规则", Panel: true},
	{Name: "hooks", Description: "查看工具事件的钩子配置", Panel: true},
	{Name: "status", Description: "查看版本、账号、模型和 MCP 状态", Panel: true},
	{Name: "usage", Description: "查看花费、套餐用量和活动统计", Panel: true},
	{Name: "effort", Description: "设置模型的思考强度", Panel: true},
	{Name: "plugin", Description: "管理插件", Panel: true},
	{Name: "skills", Description: "列出可用的技能", Panel: true},
	{Name: "tasks", Description: "查看和管理后台在跑的任务", Panel: true},
	{Name: "rewind", Description: "把对话或代码退回到之前某一步", Panel: true},
	{Name: "diff", Description: "查看未提交的改动", Panel: true},
	{Name: "help", Description: "查看帮助和全部命令", Panel: true},
}

// IsPanelCommand reports whether text is one of PanelCommands with nothing
// after it.
func IsPanelCommand(text string) bool {
	name, ok := strings.CutPrefix(strings.TrimSpace(text), "/")
	if !ok {
		return false
	}
	for _, c := range PanelCommands {
		if c.Name == name {
			return true
		}
	}
	return false
}

// UnofferedCommands are the other built-ins of that popup, left out of
// completion: they open a panel or dialog the phone cannot drive, change
// only the terminal, or would detach or stop the hidden attach. Tried in
// a background session of 2.1.328: /btw answers in an overlay that never
// reaches the transcript, /fast opens a dialog, /branch moves the session
// to a new session id, and /stop stops it without a transcript record, so
// chat.send reports it undelivered. The last
// group are Claude Code's own skills that its skill listing leaves out; a
// background session's popup only shows them in --safe-mode.
var UnofferedCommands = []string{
	"advisor", "auto-mode-setup", "autocompact", "background", "branch", "btw", "bug", "cd", "color",
	"copy", "design-login", "exit", "export", "fast", "feedback", "focus",
	"ide", "import", "keybindings", "list-agents", "login", "logout",
	"memory", "mobile", "output-style", "powerup", "radio",
	"release-notes", "resume", "sandbox", "scroll-speed", "skill-doctor",
	"stickers", "stop", "terminal-setup", "theme", "tui", "workflows",
	"batch", "debug", "deep-research", "design", "design-sync", "doctor", "insights",
	"run-skill-generator", "statusline", "team-onboarding", "verify",
}

// Commands merges BuiltinCommands and PanelCommands with the given lists in
// order, built-ins first; a name is listed once, as its first occurrence.
func Commands(lists ...[]protocol.Command) []protocol.Command {
	out := []protocol.Command{}
	seen := map[string]bool{}
	for _, list := range append([][]protocol.Command{BuiltinCommands, PanelCommands}, lists...) {
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
