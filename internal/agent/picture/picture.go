// Package picture handles the pictures the phone is shown: those in tool
// results, with thumbnails made with the standard library only, and those
// Claude sends with `clauderc show`, kept in a directory of the agent's own.
package picture

import (
	"bytes"
	"image"
	"image/draw"
	_ "image/gif" // decoders for image.Decode
	"image/jpeg"
	_ "image/png"

	"clauderc/internal/protocol"
)

// maxThumbPixels bounds the pictures a thumbnail is made of: decoding a
// bigger one would take too much memory. Such a picture has no thumbnail.
const maxThumbPixels = 24_000_000

// New describes data, a picture of type mediaType, with its size and
// thumbnail. A picture no bigger than protocol.ThumbEdge is its own
// thumbnail; one the standard library cannot read, such as WebP, has none.
func New(id, mediaType string, data []byte) protocol.ToolImage {
	img := protocol.ToolImage{ID: id, MediaType: mediaType, Size: len(data), Data: data}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return img
	}
	img.Width, img.Height = cfg.Width, cfg.Height
	if cfg.Width <= protocol.ThumbEdge && cfg.Height <= protocol.ThumbEdge {
		img.Thumb = data
		return img
	}
	if cfg.Width*cfg.Height > maxThumbPixels {
		return img
	}
	if src, _, err := image.Decode(bytes.NewReader(data)); err == nil {
		img.Thumb = thumbnailAt(src, protocol.ThumbEdge)
	}
	return img
}

// UserThumb builds a wire Image for a picture inside a user message: Data holds
// a thumbnail at most protocol.UserImageThumbEdge pixels on its longest side
// (while Original keeps the raw bytes for chat.image).
func UserThumb(id, mediaType string, data []byte) protocol.Image {
	img := protocol.Image{
		ID:                id,
		MediaType:         mediaType,
		Size:              len(data),
		Original:          data,
		OriginalMediaType: mediaType,
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		// Undecodable formats (e.g. WebP) stay inline only when small.
		if len(data) <= 256<<10 {
			img.Data = data
		}
		return img
	}
	img.Width, img.Height = cfg.Width, cfg.Height
	if cfg.Width <= protocol.UserImageThumbEdge && cfg.Height <= protocol.UserImageThumbEdge {
		img.Data = data
		return img
	}
	if cfg.Width*cfg.Height > maxThumbPixels {
		return img
	}
	if src, _, err := image.Decode(bytes.NewReader(data)); err == nil {
		if thumb := thumbnailAt(src, protocol.UserImageThumbEdge); len(thumb) > 0 {
			img.Data = thumb
			img.MediaType = "image/jpeg"
			return img
		}
	}
	if len(data) <= 256<<10 {
		img.Data = data
	}
	return img
}

// thumbnailAt scales src down to edge pixels on its longest side, each pixel
// the average of the ones it covers, over white, as a JPEG.
func thumbnailAt(src image.Image, edge int) []byte {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	tw, th := edge, edge
	if w >= h {
		th = max(1, h*edge/w)
	} else {
		tw = max(1, w*edge/h)
	}
	// Flatten onto white first: JPEG has no alpha, and draw has fast paths
	// for the common formats.
	flat := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(flat, flat.Bounds(), image.White, image.Point{}, draw.Src)
	draw.Draw(flat, flat.Bounds(), src, b.Min, draw.Over)
	dst := image.NewRGBA(image.Rect(0, 0, tw, th))
	for y := 0; y < th; y++ {
		y0, y1 := y*h/th, max((y+1)*h/th, y*h/th+1)
		for x := 0; x < tw; x++ {
			x0, x1 := x*w/tw, max((x+1)*w/tw, x*w/tw+1)
			var r, g, bl, n int
			for sy := y0; sy < y1; sy++ {
				row := flat.Pix[sy*flat.Stride:]
				for sx := x0; sx < x1; sx++ {
					r += int(row[sx*4])
					g += int(row[sx*4+1])
					bl += int(row[sx*4+2])
					n++
				}
			}
			i := dst.PixOffset(x, y)
			dst.Pix[i], dst.Pix[i+1], dst.Pix[i+2], dst.Pix[i+3] = uint8(r/n), uint8(g/n), uint8(bl/n), 255
		}
	}
	var buf bytes.Buffer
	if jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 82}) != nil {
		return nil
	}
	return buf.Bytes()
}
