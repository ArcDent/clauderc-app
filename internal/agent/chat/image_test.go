package chat_test

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

// imageLines is a Read whose result holds one picture of type mediaType.
func imageLines(id, mediaType string, data []byte) string {
	use, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "tool_use", "id": id, "name": "Read", "input": map[string]any{"file_path": "/p/a"}}}}})
	res, _ := json.Marshal(map[string]any{"type": "user", "message": map[string]any{"role": "user",
		"content": []any{map[string]any{"type": "tool_result", "tool_use_id": id,
			"content": []any{map[string]any{"type": "image", "source": map[string]any{"type": "base64", "media_type": mediaType, "data": base64.StdEncoding.EncodeToString(data)}}}}}}})
	return string(use) + "\n" + string(res) + "\n"
}

func shotPNG(t *testing.T) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, 900, 500))
	for i := range img.Pix {
		img.Pix[i] = uint8(i)
	}
	img.Set(0, 0, color.White)
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestChatImageReturnsTheOriginal(t *testing.T) {
	f := newFixture(t, setup{})
	shot := shotPNG(t)
	webp := append([]byte("RIFF\x24\x00\x00\x00WEBPVP8 "), make([]byte, 64)...)
	f.appendTranscript(t, imageLines("toolu_png", "image/png", shot)+imageLines("toolu_webp", "image/webp", webp))

	var open protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open); err != nil {
		t.Fatal(err)
	}
	png := toolItem(t, open.Items, "toolu_png").Result.Images
	wp := toolItem(t, open.Items, "toolu_webp").Result.Images
	if len(png) != 1 || len(png[0].Thumb) == 0 || png[0].ID != "toolu_png.0" || png[0].Width != 900 {
		t.Fatalf("png in chat.open: %+v", png)
	}
	if len(wp) != 1 || wp[0].Thumb != nil || wp[0].Size != len(webp) {
		t.Fatalf("webp in chat.open: %+v, want no thumbnail", wp)
	}

	for _, c := range []struct {
		id        string
		mediaType string
		data      []byte
	}{{"toolu_png.0", "image/png", shot}, {"toolu_webp.0", "image/webp", webp}} {
		var got protocol.ChatImageResponse
		if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: c.id}), &got); err != nil {
			t.Fatal(err)
		}
		if got.MediaType != c.mediaType || !bytes.Equal(got.Data, c.data) {
			t.Errorf("chat.image %s: %s, %d bytes, want %s and the same %d bytes", c.id, got.MediaType, len(got.Data), c.mediaType, len(c.data))
		}
	}
}

func TestChatImageRejects(t *testing.T) {
	f := newFixture(t, setup{})
	big := make([]byte, protocol.MaxImageBytes+1)
	f.appendTranscript(t, imageLines("toolu_big", "image/png", big))
	cases := []struct {
		body protocol.ChatImageRequest
		code protocol.ErrorCode
		text string
	}{
		{protocol.ChatImageRequest{SessionID: shortID, ID: "toolu_missing.0"}, protocol.CodeNotFound, "找不到"},
		{protocol.ChatImageRequest{SessionID: shortID, ID: "toolu_big.0"}, protocol.CodeInvalidParams, "图片太大"},
		{protocol.ChatImageRequest{SessionID: shortID}, protocol.CodeInvalidParams, ""},
		{protocol.ChatImageRequest{ID: "toolu_big.0"}, protocol.CodeInvalidParams, ""},
		{protocol.ChatImageRequest{SessionID: "zzz99999", ID: "toolu_big.0"}, protocol.CodeSessionNotFound, ""},
	}
	for _, c := range cases {
		res := f.call(t, "c1", protocol.MethodChatImage, c.body)
		if res.Error == nil || res.Error.Code != c.code || !strings.Contains(res.Error.Message, c.text) {
			t.Errorf("%+v: error %+v, want %s with %q", c.body, res.Error, c.code, c.text)
		}
	}
}
