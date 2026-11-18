//go:build e2e

package claude

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

// writePNG writes a w×h PNG of one color at path and returns its bytes.
func writePNG(t *testing.T, path string, w, h int, c color.Color) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	var b bytes.Buffer
	if err := png.Encode(&b, img); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, b.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

// toolUse waits for a tool_use of name whose input mentions want, written
// at or after entry since, and returns its id.
func (s *session) toolUse(name, want string, since int, timeout time.Duration) string {
	s.t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		es := s.entries()
		for _, e := range es[min(since, len(es)):] {
			for _, b := range blocks(e, "tool_use") {
				in, _ := json.Marshal(b["input"])
				if b["name"] == name && strings.Contains(string(in), want) {
					id, _ := b["id"].(string)
					return id
				}
			}
		}
		if time.Now().After(deadline) {
			// What the session did instead.
			for _, e := range es[min(since, len(es)):] {
				line, _ := json.Marshal(map[string]any{"type": e["type"], "text": text(e), "tool_use": blocks(e, "tool_use"), "tool_result": resultTexts(e)})
				s.t.Log(string(line))
			}
			s.t.Fatalf("等待超时：%s 调用 %s，会话状态 %s", name, want, s.state())
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func resultTexts(e map[string]any) []string {
	var out []string
	for _, b := range blocks(e, "tool_result") {
		t := resultText(b)
		if len(t) > 300 {
			t = t[:300]
		}
		out = append(out, t)
	}
	return out
}

// allowUntilDone allows the permission prompt for toolUseID, if one comes,
// until its result is in.
func (s *session) allowUntilDone(toolUseID string, timeout time.Duration) {
	s.t.Helper()
	allowed := false
	waitFor(s.t, timeout, "tool_result "+toolUseID, func() bool {
		for _, e := range s.entries() {
			for _, b := range blocks(e, "tool_result") {
				if b["tool_use_id"] == toolUseID {
					return true
				}
			}
		}
		if p := s.latestPending(); p != nil && p.ToolUseID == toolUseID && p.Kind == protocol.PendingPermission && !allowed {
			allowed = true
			s.x.call(s.t, protocol.MethodChatAnswer, protocol.ChatAnswerRequest{SessionID: s.id, ToolUseID: toolUseID, Decision: protocol.DecisionAllow})
		}
		return false
	})
}

// latestPending is the last pending prompt the agent pushed for the
// session, or nil.
func (s *session) latestPending() *protocol.Pending {
	s.x.mu.Lock()
	defer s.x.mu.Unlock()
	for i := len(s.x.events) - 1; i >= 0; i-- {
		var ev protocol.ChatPendingEvent
		if s.x.events[i].Method == protocol.EventChatPending && json.Unmarshal(s.x.events[i].Body, &ev) == nil && ev.SessionID == s.id {
			return ev.Pending
		}
	}
	return nil
}

// picture checks that the tool call toolUseID came with one picture that
// has a thumbnail, and that chat.image returns it byte for byte as want.
func (s *session) picture(toolUseID string, want []byte) {
	s.t.Helper()
	// The test reads the transcript itself; the agent's follower may see the
	// result a moment later, and the phone only ever sees it through that.
	var res protocol.ChatToolOutputResponse
	waitFor(s.t, 20*time.Second, "目标机读到 "+toolUseID, func() bool {
		env, err := s.x.call(s.t, protocol.MethodChatToolOutput, protocol.ChatToolOutputRequest{SessionID: s.id, ToolUseID: toolUseID})
		return err == nil && json.Unmarshal(env.Body, &res) == nil
	})
	if len(res.Images) != 1 || len(res.Images[0].Thumb) == 0 {
		s.t.Fatalf("%s 的图片 %+v，want 一张带缩略图的", toolUseID, res.Images)
	}
	if _, _, err := image.Decode(bytes.NewReader(res.Images[0].Thumb)); err != nil {
		s.t.Errorf("%s 的缩略图解不开：%v", toolUseID, err)
	}
	var got protocol.ChatImageResponse
	s.x.must(s.t, protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: s.id, ID: res.Images[0].ID}, &got)
	if !bytes.Equal(got.Data, want) {
		s.t.Errorf("chat.image 取回 %d 字节，和文件的 %d 字节不同", len(got.Data), len(want))
	}
}

// TestImages: a picture the session reads, and one it sends with
// clauderc show, reach the phone with thumbnails and come back whole
// through chat.image.
func TestImages(t *testing.T) {
	s := launch(t, "images")
	shown := filepath.Join(s.ws, ".shown")
	t.Setenv("CLAUDERC_SHOW_DIR", shown)
	bin := filepath.Join(s.ws, ".bin", "clauderc")
	if out, err := exec.Command("go", "build", "-o", bin, filepath.Join(s.x.root, "cmd", "agent")).CombinedOutput(); err != nil {
		t.Fatalf("go build: %v\n%s", err, out)
	}
	read := writePNG(t, filepath.Join(s.ws, "read.png"), 800, 600, color.RGBA{220, 30, 30, 255})
	sent := writePNG(t, filepath.Join(s.ws, "sent.png"), 640, 480, color.RGBA{30, 60, 220, 255})

	m := s.mark()
	s.send("请用 Read 工具读取 read.png，然后只回复图里的主要颜色")
	id := s.toolUse("Read", "read.png", m, 120*time.Second)
	s.allowUntilDone(id, 120*time.Second)
	s.picture(id, read)
	s.waitIdle(120 * time.Second)

	m = s.mark()
	s.send("请只用 Bash 工具运行这条命令：CLAUDERC_SHOW_DIR=" + shown + " " + bin + " show sent.png  然后只回复 OK")
	id = s.toolUse("Bash", "show sent.png", m, 120*time.Second)
	s.allowUntilDone(id, 120*time.Second)
	s.picture(id, sent)
	s.waitIdle(120 * time.Second)
}
