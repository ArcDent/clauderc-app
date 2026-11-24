package chat_test

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"hash/crc32"
	"image"
	"image/color"
	_ "image/jpeg"
	"image/png"
	"path/filepath"
	"strings"
	"testing"
	"time"

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
	t.Parallel()
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
	t.Parallel()
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

// A subagent's own Read is fetched from its transcript when the request
// names it, whether its chat is open or not; without the AgentID only the
// session's own transcript is looked at, as before.
func TestChatImageOfASubagent(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	dir := f.addSubagents(t)
	shot := shotPNG(t)
	lines := strings.ReplaceAll(imageLines("toolu_sub", "image/png", shot), `{"message":`, `{"agentId":"`+goAgent+`","isSidechain":true,"message":`)
	appendFile(t, filepath.Join(dir, "agent-"+goAgent+".jsonl"), lines)

	fetch := func(agentID string) protocol.Envelope {
		return f.call(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: "toolu_sub.0", AgentID: agentID})
	}
	check := func(when string) {
		t.Helper()
		res := fetch(goAgent)
		var got protocol.ChatImageResponse
		if res.Error != nil || json.Unmarshal(res.Body, &got) != nil || got.MediaType != "image/png" || !bytes.Equal(got.Data, shot) {
			t.Fatalf("%s: error %+v, type %q, %d bytes; want the original %d bytes", when, res.Error, got.MediaType, len(got.Data), len(shot))
		}
	}
	check("closed")
	if res := fetch(""); res.Error == nil || res.Error.Code != protocol.CodeNotFound {
		t.Errorf("without agentId: error %+v, want not found in the session's own transcript", res.Error)
	}
	f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID, AgentID: goAgent})
	check("open")
	for _, id := range []string{"../" + goAgent, "a/b"} {
		if res := fetch(id); res.Error == nil || res.Error.Code != protocol.CodeInvalidParams {
			t.Errorf("agentId %q: error %+v, want invalid params", id, res.Error)
		}
	}
	if res := fetch("abf8fcd189003562d"); res.Error == nil || res.Error.Code != protocol.CodeNotFound {
		t.Errorf("a subagent with no transcript: error %+v, want not found", res.Error)
	}
}

// bigPNG builds a valid 1200x900 PNG whose raw byte size is at least 4 MB by
// appending a valid ancillary tEXt chunk before IEND.
func bigPNG(t *testing.T, tag byte) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, 1200, 900))
	c := color.NRGBA{R: 40 + tag*50, G: 120, B: 200 - tag*30, A: 255}
	for y := 0; y < 900; y++ {
		for x := 0; x < 1200; x++ {
			img.SetNRGBA(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	raw := buf.Bytes()
	const iendLen = 12
	body := raw[:len(raw)-iendLen]
	iend := raw[len(raw)-iendLen:]
	payload := append([]byte("pad\x00"), bytes.Repeat([]byte{'a' + tag}, 4<<20)...)
	var out bytes.Buffer
	out.Grow(len(raw) + len(payload) + 12)
	out.Write(body)
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(payload)))
	out.Write(lenBuf[:])
	chunkType := []byte("tEXt")
	out.Write(chunkType)
	out.Write(payload)
	h := crc32.NewIEEE()
	h.Write(chunkType)
	h.Write(payload)
	var crcBuf [4]byte
	binary.BigEndian.PutUint32(crcBuf[:], h.Sum32())
	out.Write(crcBuf[:])
	out.Write(iend)
	return out.Bytes()
}

func userImagesLine(uuid, text string, imgs [][]byte) string {
	blocks := make([]any, 0, len(imgs)+1)
	for _, b := range imgs {
		blocks = append(blocks, map[string]any{
			"type": "image",
			"source": map[string]any{
				"type":       "base64",
				"media_type": "image/png",
				"data":       base64.StdEncoding.EncodeToString(b),
			},
		})
	}
	blocks = append(blocks, map[string]any{"type": "text", "text": text})
	raw, _ := json.Marshal(map[string]any{
		"type":    "user",
		"uuid":    uuid,
		"message": map[string]any{"role": "user", "content": blocks},
	})
	return string(raw) + "\n"
}

// A user message with three 4 MB PNGs carries only <=480px thumbnails and IDs
// in both chat.open and live chat.items; chat.image returns the exact 4 MB
// original bytes for each ID.
func TestUserMessageImagesSendThumbnailsAndFetchOriginal(t *testing.T) {
	t.Parallel()
	f := newFixture(t, setup{})
	originals := [][]byte{bigPNG(t, 0), bigPNG(t, 1), bigPNG(t, 2)}
	f.appendTranscript(t, userImagesLine("u-open-imgs", "三张大图", originals))

	checkImages := func(where string, imgs []protocol.Image) {
		t.Helper()
		if len(imgs) != 3 {
			t.Fatalf("%s: got %d images, want 3", where, len(imgs))
		}
		for i, img := range imgs {
			if img.ID == "" {
				t.Fatalf("%s[%d]: missing image ID", where, i)
			}
			if img.Width != 1200 || img.Height != 900 || img.Size != len(originals[i]) {
				t.Fatalf("%s[%d]: meta = %dx%d %dB, want 1200x900 %dB", where, i, img.Width, img.Height, img.Size, len(originals[i]))
			}
			cfg, _, err := image.DecodeConfig(bytes.NewReader(img.Data))
			if err != nil {
				t.Fatalf("%s[%d]: decode thumb: %v", where, i, err)
			}
			if cfg.Width > protocol.UserImageThumbEdge || cfg.Height > protocol.UserImageThumbEdge {
				t.Fatalf("%s[%d]: thumb size %dx%d exceeds %d", where, i, cfg.Width, cfg.Height, protocol.UserImageThumbEdge)
			}
			if len(img.Data) >= 200<<10 {
				t.Fatalf("%s[%d]: thumb byte size %d too large", where, i, len(img.Data))
			}
			var got protocol.ChatImageResponse
			if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatImage, protocol.ChatImageRequest{SessionID: shortID, ID: img.ID}), &got); err != nil {
				t.Fatal(err)
			}
			if got.MediaType != "image/png" || !bytes.Equal(got.Data, originals[i]) {
				t.Fatalf("%s[%d]: chat.image returned %s %dB, want image/png %dB matching original", where, i, got.MediaType, len(got.Data), len(originals[i]))
			}
		}
	}

	var open protocol.ChatOpenResponse
	if err := json.Unmarshal(f.ok(t, "c1", protocol.MethodChatOpen, protocol.ChatOpenRequest{SessionID: shortID}), &open); err != nil {
		t.Fatal(err)
	}
	checkImages("chat.open", open.Items[len(open.Items)-1].Images)

	// Append another 3-image user message while open and check the live chat.items push.
	f.appendTranscript(t, userImagesLine("u-live-imgs", "又三张大图", originals))
	ev := f.nextEvent(t, protocol.EventChatItems, 15*time.Second)
	var pushed protocol.ChatItemsEvent
	if err := json.Unmarshal(ev.Body, &pushed); err != nil {
		t.Fatal(err)
	}
	if len(pushed.Items) == 0 {
		t.Fatalf("empty chat.items push")
	}
	checkImages("chat.items", pushed.Items[len(pushed.Items)-1].Images)
}
