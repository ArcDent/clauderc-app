//go:build e2e

package claude

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// TestQuestionAllPatterns drives the real Claude Code CLI against a local
// Anthropic SSE mock so every AskUserQuestion rendering pattern of Claude Code
// (single/multi-select, custom "其他", wrapped "│ " questions, wrapped 2-line
// tab bars, tall option descriptions, and fresh attach after linger expiry) is
// verified deterministically without hitting account rate limits.
func TestQuestionAllPatterns(t *testing.T) {
	q := func(header, question string, multi bool, opts ...[2]string) map[string]any {
		var list []map[string]string
		for _, o := range opts {
			list = append(list, map[string]string{"label": o[0], "description": o[1]})
		}
		return map[string]any{"header": header, "question": question, "multiSelect": multi, "options": list}
	}

	cases := map[string]any{
		"single_option": map[string]any{
			"questions": []any{q("颜色", "你喜欢哪种颜色？", false, [2]string{"红", "红色"}, [2]string{"绿", "绿色"}, [2]string{"蓝", "蓝色"})},
		},
		"single_custom_zh": map[string]any{
			"questions": []any{q("季节", "你喜欢哪种季节？", false, [2]string{"春", ""}, [2]string{"夏", ""}, [2]string{"秋", ""})},
		},
		"single_multi_custom": map[string]any{
			"questions": []any{q("蔬菜", "你喜欢哪些蔬菜？", true, [2]string{"白菜", ""}, [2]string{"萝卜", ""}, [2]string{"茄子", ""})},
		},
		"three_q_mixed": map[string]any{
			"questions": []any{
				q("配色", "主色调选哪种？", false, [2]string{"红", ""}, [2]string{"蓝", ""}),
				q("平台", "支持哪些平台？", true, [2]string{"iOS", ""}, [2]string{"Android", ""}, [2]string{"Web", ""}),
				q("节奏", "什么时候发布？", false, [2]string{"本周", ""}, [2]string{"下周", ""}),
			},
		},
		"long_single_line": map[string]any{
			"questions": []any{
				q("长题干", "这是一个完全不包含换行符但是故意写得非常非常长超过一百二十列终端宽度的中文问题用来测试自动折行的时候是不是也会加上竖线或者出现匹配失败的情况你觉得会怎样呢？", false, [2]string{"会", ""}, [2]string{"不会", ""}),
				q("确认", "第二题也很长同样超过一百二十列终端宽度用来确认切题后自动折行是不是也能在屏幕上被正确识别出来而不会误报题目已经变化了？", false, [2]string{"能", ""}, [2]string{"不能", ""}),
			},
		},
		"markdown_multiline": map[string]any{
			"questions": []any{
				q("策略", "针对 `internal/relay/store.go` 的 **SQLite** 迁移：\n第一行说明包含 `v1 -> v2` 升级细节，且整段问题文本超过一百二十列用来验证多行带竖线问题能否正常提交？", false, [2]string{"方案一", "原地迁移"}, [2]string{"方案二", "新建双写"}),
				q("回滚", "如果失败是否回滚 `db.sqlite`？\n（选「是」会保留备份）", false, [2]string{"是", ""}, [2]string{"否", ""}),
			},
		},
		"ultra_long_headers": map[string]any{
			"questions": []any{
				q("非常长的数据库迁移与历史兼容策略标题一", "第一题选什么？", false, [2]string{"A", ""}, [2]string{"B", ""}),
				q("非常长的手机端离线缓存与断线重连策略二", "第二题选什么？", false, [2]string{"C", ""}, [2]string{"D", ""}),
				q("非常长的权限确认等待超时与重绘检测三", "第三题选什么？", false, [2]string{"E", ""}, [2]string{"F", ""}),
				q("非常长的日志脱敏终端快照调试输出方案四", "第四题选什么？", false, [2]string{"G", ""}, [2]string{"H", ""}),
			},
		},
		"fresh_attach_single": map[string]any{
			"questions": []any{q("颜色", "新鲜接入单选：你喜欢哪种颜色？", false, [2]string{"红", ""}, [2]string{"绿", ""}, [2]string{"蓝", ""})},
		},
		"fresh_attach_two_q": map[string]any{
			"questions": []any{
				q("颜色", "新鲜接入多题一：颜色？", false, [2]string{"红", ""}, [2]string{"蓝", ""}),
				q("大小", "新鲜接入多题二：大小？", false, [2]string{"大", ""}, [2]string{"小", ""}),
			},
		},
	}

	var seq atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/count_tokens") {
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"input_tokens":10}`))
			return
		}
		var req struct {
			Model    string `json:"model"`
			Stream   bool   `json:"stream"`
			Messages []struct {
				Role    string `json:"role"`
				Content any    `json:"content"`
			} `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		model := req.Model
		if model == "" {
			model = "claude-sonnet-4-5"
		}
		msgID := fmt.Sprintf("msg_q_%d", seq.Add(1))
		var lastUser string
		hasToolResult := false
		if len(req.Messages) > 0 {
			last := req.Messages[len(req.Messages)-1]
			switch v := last.Content.(type) {
			case string:
				lastUser = v
			case []any:
				for _, blk := range v {
					if m, ok := blk.(map[string]any); ok {
						if m["type"] == "tool_result" {
							hasToolResult = true
						}
						if m["type"] == "text" {
							if s, ok := m["text"].(string); ok {
								lastUser += "\n" + s
							}
						}
					}
				}
			}
		}
		var toolInput any
		if !hasToolResult {
			for key, payload := range cases {
				if strings.Contains(lastUser, "QPAT:"+key) {
					toolInput = payload
					break
				}
			}
		}
		if !req.Stream {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id": msgID, "type": "message", "role": "assistant", "model": model,
				"content": []map[string]any{{"type": "text", "text": "OK"}}, "stop_reason": "end_turn",
				"usage": map[string]any{"input_tokens": 10, "output_tokens": 2},
			})
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		flusher, _ := w.(http.Flusher)
		sse := func(ev string, data any) {
			b, _ := json.Marshal(data)
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev, b)
			if flusher != nil {
				flusher.Flush()
			}
		}
		sse("message_start", map[string]any{"type": "message_start", "message": map[string]any{
			"id": msgID, "type": "message", "role": "assistant", "model": model,
			"content": []any{}, "stop_reason": nil, "stop_sequence": nil,
			"usage": map[string]any{"input_tokens": 10, "output_tokens": 1},
		}})
		if toolInput != nil {
			inB, _ := json.Marshal(toolInput)
			sse("content_block_start", map[string]any{"type": "content_block_start", "index": 0,
				"content_block": map[string]any{"type": "tool_use", "id": fmt.Sprintf("toolu_q_%d", seq.Add(1)), "name": "AskUserQuestion", "input": map[string]any{}}})
			sse("content_block_delta", map[string]any{"type": "content_block_delta", "index": 0,
				"delta": map[string]any{"type": "input_json_delta", "partial_json": string(inB)}})
			sse("content_block_stop", map[string]any{"type": "content_block_stop", "index": 0})
			sse("message_delta", map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "tool_use"}, "usage": map[string]any{"output_tokens": 10}})
		} else {
			sse("content_block_start", map[string]any{"type": "content_block_start", "index": 0, "content_block": map[string]any{"type": "text", "text": ""}})
			sse("content_block_delta", map[string]any{"type": "content_block_delta", "index": 0, "delta": map[string]any{"type": "text_delta", "text": "OK"}})
			sse("content_block_stop", map[string]any{"type": "content_block_stop", "index": 0})
			sse("message_delta", map[string]any{"type": "message_delta", "delta": map[string]any{"stop_reason": "end_turn"}, "usage": map[string]any{"output_tokens": 2}})
		}
		sse("message_stop", map[string]any{"type": "message_stop"})
	}))
	t.Cleanup(srv.Close)

	prevEndpoint := endpoint
	endpoint.url = srv.URL
	endpoint.key = "sk-ant-mock-qpat"
	t.Cleanup(func() { endpoint = prevEndpoint })

	s := launch(t, "qpat")

	run := func(name string, freshAttach bool, as []protocol.Answer, want map[int]string) {
		p := ask(t, s, "QPAT:"+name)
		if freshAttach {
			s.x.must(t, protocol.MethodChatClose, protocol.ChatCloseRequest{SessionID: s.id}, nil)
			time.Sleep(3 * time.Second)
			s.x.must(t, protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: s.id}, nil)
			time.Sleep(2 * time.Second)
		}
		s.x.must(t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{
			SessionID: s.id, ToolUseID: p.ToolUseID, Answers: as,
		}, nil)
		_, tur := s.result(p.ToolUseID)
		got := answers(tur)
		for qi, w := range want {
			if got[p.Questions[qi].Question] != w {
				t.Errorf("[%s] q%d got %q, want %q (all=%v)", name, qi, got[p.Questions[qi].Question], w, got)
			}
		}
		waitFor(t, 20*time.Second, name+" idle", func() bool { return s.state() == protocol.StateIdle })
	}

	run("single_option", false, []protocol.Answer{{Selected: []int{1}}}, map[int]string{0: "绿"})
	run("single_custom_zh", false, []protocol.Answer{{Custom: "冬天"}}, map[int]string{0: "冬天"})
	run("single_multi_custom", false, []protocol.Answer{{Selected: []int{0, 2}, Custom: "南瓜"}}, map[int]string{0: "白菜, 茄子, 南瓜"})
	run("three_q_mixed", false, []protocol.Answer{{Custom: "墨黑"}, {Selected: []int{1, 2}}, {Selected: []int{0}}},
		map[int]string{0: "墨黑", 1: "Android, Web", 2: "本周"})
	run("long_single_line", false, []protocol.Answer{{Selected: []int{0}}, {Selected: []int{1}}},
		map[int]string{0: "会", 1: "不能"})
	run("markdown_multiline", false, []protocol.Answer{{Selected: []int{0}}, {Selected: []int{1}}},
		map[int]string{0: "方案一", 1: "否"})
	run("ultra_long_headers", false, []protocol.Answer{{Selected: []int{0}}, {Selected: []int{1}}, {Selected: []int{0}}, {Selected: []int{1}}},
		map[int]string{0: "A", 1: "D", 2: "E", 3: "H"})
	run("fresh_attach_single", true, []protocol.Answer{{Selected: []int{2}}}, map[int]string{0: "蓝"})
	run("fresh_attach_two_q", true, []protocol.Answer{{Selected: []int{1}}, {Selected: []int{0}}},
		map[int]string{0: "蓝", 1: "大"})
}
