package chat_test

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"clauderc/internal/agent/picture"
	"clauderc/internal/protocol"
)

// showLines is a Bash call of clauderc show whose output is out.
func showLines(id, out string) string {
	use, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "tool_use", "id": id, "name": "Bash", "input": map[string]any{"command": "clauderc show /p/shot.png"}}}}})
	res, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user",
		"content": []any{map[string]any{"type": "tool_result", "tool_use_id": id, "content": out}}}})
	return string(use) + "\n" + string(res) + "\n"
}

func TestShownPictureComesWithItsCommand(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("CLAUDERC_SHOW_DIR", dir)
	src := filepath.Join(t.TempDir(), "shot.png")
	shot := shotPNG(t)
	os.WriteFile(src, shot, 0o644)
	id, err := picture.Save(dir, src)
	if err != nil {
		t.Fatal(err)
	}
	f := newFixture(t, setup{})
	f.appendTranscript(t, showLines("toolu_show", picture.Marker(id, "shot.png")+"\n"))

	var open protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open); err != nil {
		t.Fatal(err)
	}
	imgs := toolItem(t, open.Items, "toolu_show").Result.Images
	if len(imgs) != 1 || imgs[0].ID != picture.IDPrefix+id || imgs[0].Name != "shot.png" || len(imgs[0].Thumb) == 0 || imgs[0].MediaType != "image/png" {
		t.Fatalf("images of the clauderc show item: %+v", imgs)
	}
	var got protocol.ChatImageResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: imgs[0].ID}), &got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got.Data, shot) {
		t.Errorf("chat.image gave %d bytes, want the %d of shot.png", len(got.Data), len(shot))
	}
}

func TestChatImageRefusesPathsBeforeLookingAtFiles(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "shown")
	os.MkdirAll(dir, 0o700)
	// Files a path could reach if it were followed.
	os.WriteFile(filepath.Join(root, "x"), []byte("secret"), 0o600)
	os.WriteFile(filepath.Join(dir, "x.png"), []byte("secret"), 0o600)
	t.Setenv("CLAUDERC_SHOW_DIR", dir)
	f := newFixture(t, setup{})
	for _, id := range []string{"../x", "/etc/passwd", filepath.Join(root, "x"), "show-../x", "show-x", "show-0123456789abcdef/../../x", "x.png", "show-0123456789ABCDEF"} {
		res := f.call(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: id})
		if res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
			t.Errorf("%q: error %+v, want invalidParams", id, res.Error)
		}
	}
	res := f.call(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: "show-0123456789abcdef"})
	if res.Error == nil || res.Error.Code != protocol.CodeNotFound {
		t.Errorf("a well-formed id with no file: error %+v, want notFound", res.Error)
	}
}
