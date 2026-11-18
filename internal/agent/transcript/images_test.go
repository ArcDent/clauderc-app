package transcript_test

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"testing"

	"clauderc/internal/protocol"
)

// pngOf is a w×h PNG, a gradient so it does not compress to nothing.
func pngOf(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x), uint8(y), 128, 255})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// readImageLines is a Read of a picture and its result, as Claude Code
// writes them: the picture is an image block in the tool_result.
func readImageLines(id, mediaType string, data []byte) []string {
	use, _ := json.Marshal(map[string]any{"type": "assistant", "message": map[string]any{"role": "assistant",
		"content": []any{map[string]any{"type": "tool_use", "id": id, "name": "Read", "input": map[string]any{"file_path": "/p/shot.png"}}}}})
	res, _ := json.Marshal(map[string]any{"type": "user",
		"message": map[string]any{"role": "user", "content": []any{map[string]any{"type": "tool_result", "tool_use_id": id,
			"content": []any{map[string]any{"type": "image", "source": map[string]any{"type": "base64", "media_type": mediaType, "data": base64.StdEncoding.EncodeToString(data)}}}}}},
		"toolUseResult": map[string]any{"type": "image", "file": map[string]any{"type": mediaType, "originalSize": len(data)}}})
	return []string{string(use), string(res)}
}

func imagesOf(t *testing.T, lines []string) []protocol.ToolImage {
	t.Helper()
	tr := parseLines(t, lines...)
	for _, it := range tr.Items() {
		if it.Tool != nil && it.Tool.Result != nil {
			return it.Tool.Result.Images
		}
	}
	t.Fatal("no tool result")
	return nil
}

func TestToolResultImageGetsAThumbnail(t *testing.T) {
	shot := pngOf(t, 720, 1560)
	imgs := imagesOf(t, readImageLines("toolu_img", "image/png", shot))
	if len(imgs) != 1 {
		t.Fatalf("%d images, want 1", len(imgs))
	}
	img := imgs[0]
	if img.ID != "toolu_img.0" || img.MediaType != "image/png" || img.Width != 720 || img.Height != 1560 || img.Size != len(shot) {
		t.Errorf("image %s %s %dx%d %d bytes, want toolu_img.0 image/png 720x1560 %d bytes", img.ID, img.MediaType, img.Width, img.Height, img.Size, len(shot))
	}
	if !bytes.Equal(img.Data, shot) {
		t.Error("the picture kept for chat.image is not the original")
	}
	thumb, _, err := image.Decode(bytes.NewReader(img.Thumb))
	if err != nil {
		t.Fatalf("thumbnail does not decode: %v", err)
	}
	if b := thumb.Bounds(); b.Dy() != protocol.ThumbEdge || b.Dx() > protocol.ThumbEdge || b.Dx() != 720*protocol.ThumbEdge/1560 {
		t.Errorf("thumbnail %dx%d, want %dx%d", b.Dx(), b.Dy(), 720*protocol.ThumbEdge/1560, protocol.ThumbEdge)
	}
	// The original never goes out with the item.
	raw, _ := json.Marshal(img)
	if bytes.Contains(raw, []byte(base64.StdEncoding.EncodeToString(shot[:64]))) {
		t.Error("the original is in the item's JSON")
	}
}

func TestSmallPictureIsItsOwnThumbnail(t *testing.T) {
	icon := pngOf(t, 200, 120)
	img := imagesOf(t, readImageLines("toolu_icon", "image/png", icon))[0]
	if !bytes.Equal(img.Thumb, icon) {
		t.Errorf("thumbnail of a 200x120 picture is %d bytes, want the picture itself (%d bytes)", len(img.Thumb), len(icon))
	}
}

func TestUnreadablePictureHasNoThumbnail(t *testing.T) {
	webp := append([]byte("RIFF\x24\x00\x00\x00WEBPVP8 "), make([]byte, 64)...)
	img := imagesOf(t, readImageLines("toolu_webp", "image/webp", webp))[0]
	if img.Thumb != nil || img.Width != 0 || !bytes.Equal(img.Data, webp) || img.MediaType != "image/webp" {
		t.Errorf("webp: thumb %d bytes, %dx%d, data kept %v, want no thumbnail and the data kept", len(img.Thumb), img.Width, img.Height, bytes.Equal(img.Data, webp))
	}
}
