package picture

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"clauderc/internal/protocol"
)

// KeepFor is how long a picture sent with `clauderc show` is kept.
const KeepFor = 7 * 24 * time.Hour

// IDPrefix starts the ToolImage ID of a picture sent with `clauderc show`.
const IDPrefix = "show-"

// extensions maps the formats `clauderc show` takes to the file names it
// keeps them under.
var extensions = map[string]string{"image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif", "image/webp": ".webp"}

// Dir is where `clauderc show` keeps pictures: clauderc/shown in the
// user's cache directory, or $CLAUDERC_SHOW_DIR when set.
func Dir() string {
	if d := os.Getenv("CLAUDERC_SHOW_DIR"); d != "" {
		return d
	}
	base, err := os.UserCacheDir()
	if err != nil {
		base = os.TempDir()
	}
	return filepath.Join(base, "clauderc", "shown")
}

var (
	// shownID is the part of a picture's ID after IDPrefix, and its file
	// name before the extension.
	shownID = regexp.MustCompile(`^[0-9a-f]{16}$`)
	// marker is the line `clauderc show` prints for each picture.
	marker = regexp.MustCompile(`(?m)^\[clauderc show ([0-9a-f]{16})\] (.+?)\r?$`)
)

// Save copies the picture at path into dir, readable by the user only,
// and returns its ID without IDPrefix. It refuses what is not a PNG, JPEG,
// GIF or WebP, or is bigger than protocol.MaxImageBytes.
func Save(dir, path string) (string, error) {
	st, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", fmt.Errorf("找不到文件：%s", path)
		}
		return "", fmt.Errorf("读不了 %s：%v", path, err)
	}
	if st.IsDir() {
		return "", fmt.Errorf("%s 是目录，不是图片", path)
	}
	if st.Size() > protocol.MaxImageBytes {
		return "", fmt.Errorf("%s 太大，图片不能超过 10 MB", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("读不了 %s：%v", path, err)
	}
	ext, ok := extensions[http.DetectContentType(data)]
	if !ok {
		return "", fmt.Errorf("%s 不是图片，只能发 PNG、JPEG、GIF 和 WebP", path)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	id := hex.EncodeToString(b[:])
	if err := os.WriteFile(filepath.Join(dir, id+ext), data, 0o600); err != nil {
		return "", err
	}
	return id, nil
}

// Marker is the line that stands for a picture in the command's output,
// [name] being the file name it was sent under.
func Marker(id, name string) string {
	return "[clauderc show " + id + "] " + name
}

// Clean removes the pictures in dir older than KeepFor.
func Clean(dir string, now time.Time) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || !info.Mode().IsRegular() || now.Sub(info.ModTime()) <= KeepFor {
			continue
		}
		os.Remove(filepath.Join(dir, e.Name()))
	}
}

// ValidID reports whether id can name a picture at all: one sent with
// `clauderc show`, or one of a tool result, <tool_use id>.<n>. Anything
// else, such as a path, is refused before any file is looked at.
func ValidID(id string) bool {
	if rest, ok := strings.CutPrefix(id, IDPrefix); ok {
		return shownID.MatchString(rest)
	}
	return toolImageID.MatchString(id)
}

var toolImageID = regexp.MustCompile(`^[A-Za-z0-9_-]+\.[0-9]+$`)

// Open reads the picture with ID id, IDPrefix included, from dir.
func Open(dir, id string) (mediaType string, data []byte, err error) {
	rest, ok := strings.CutPrefix(id, IDPrefix)
	if !ok || !shownID.MatchString(rest) {
		return "", nil, os.ErrNotExist
	}
	for t, ext := range extensions {
		if data, err = os.ReadFile(filepath.Join(dir, rest+ext)); err == nil {
			return t, data, nil
		}
	}
	return "", nil, os.ErrNotExist
}

// Shown returns the pictures whose markers are in output, the output of a
// command; one no longer in dir has no thumbnail and a size of 0.
func Shown(dir, output string) []protocol.ToolImage {
	var out []protocol.ToolImage
	for _, m := range marker.FindAllStringSubmatch(output, -1) {
		id := IDPrefix + m[1]
		img := protocol.ToolImage{ID: id, Name: m[2]}
		if t, data, err := Open(dir, id); err == nil {
			img = New(id, t, data)
			img.Name = m[2]
		}
		out = append(out, img)
	}
	return out
}
