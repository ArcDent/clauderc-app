package main

import (
	"bytes"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"clauderc/internal/protocol"
)

var markerLine = regexp.MustCompile(`^\[clauderc show [0-9a-f]{16}\] (.+)\n$`)

// pictures writes one small picture of each format clauderc show takes.
func pictures(t *testing.T) map[string]string {
	t.Helper()
	dir := t.TempDir()
	img := image.NewPaletted(image.Rect(0, 0, 40, 30), color.Palette{color.White, color.Black})
	var p, j, g bytes.Buffer
	if png.Encode(&p, img) != nil || jpeg.Encode(&j, img, nil) != nil || gif.Encode(&g, img, nil) != nil {
		t.Fatal("encode")
	}
	files := map[string][]byte{
		"shot.png": p.Bytes(), "photo.jpg": j.Bytes(), "anim.gif": g.Bytes(),
		"hero.webp": append([]byte("RIFF\x24\x00\x00\x00WEBPVP8 "), make([]byte, 32)...),
	}
	out := map[string]string{}
	for name, data := range files {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, data, 0o644); err != nil {
			t.Fatal(err)
		}
		out[name] = path
	}
	return out
}

func entries(t *testing.T, dir string) []os.DirEntry {
	t.Helper()
	es, err := os.ReadDir(dir)
	if err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	return es
}

func TestShowKeepsEachFormatForTheUserOnly(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "shown")
	for name, path := range pictures(t) {
		before := len(entries(t, dir))
		var out bytes.Buffer
		if err := showCmd([]string{path}, dir, time.Now(), &out); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		m := markerLine.FindStringSubmatch(out.String())
		if m == nil || m[1] != name {
			t.Errorf("%s: output %q, want a marker line naming it", name, out.String())
		}
		es := entries(t, dir)
		if len(es) != before+1 {
			t.Fatalf("%s: %d files in the directory, want %d", name, len(es), before+1)
		}
		for _, e := range es {
			if info, _ := e.Info(); info.Mode().Perm() != 0o600 {
				t.Errorf("%s: %s has mode %v, want 0600", name, e.Name(), info.Mode().Perm())
			}
		}
	}
	if info, err := os.Stat(dir); err != nil || info.Mode().Perm() != 0o700 {
		t.Errorf("directory mode %v, want 0700", info.Mode().Perm())
	}
}

func TestShowRefusesWhatIsNoPicture(t *testing.T) {
	src := t.TempDir()
	text := filepath.Join(src, "notes.txt")
	big := filepath.Join(src, "huge.png")
	os.WriteFile(text, []byte("只是文字\n"), 0o644)
	os.WriteFile(big, append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, protocol.MaxImageBytes)...), 0o644)
	dir := filepath.Join(t.TempDir(), "shown")
	for _, c := range []struct{ path, why string }{
		{text, "不是图片"},
		{big, "太大"},
		{filepath.Join(src, "missing.png"), "找不到文件"},
	} {
		var out bytes.Buffer
		err := showCmd([]string{c.path}, dir, time.Now(), &out)
		if err == nil || !strings.Contains(err.Error(), c.why) {
			t.Errorf("%s: error %v, want one saying %s", filepath.Base(c.path), err, c.why)
		}
		if out.Len() != 0 || len(entries(t, dir)) != 0 {
			t.Errorf("%s: printed %q and kept %d files, want nothing", filepath.Base(c.path), out.String(), len(entries(t, dir)))
		}
	}
}

func TestShowRemovesPicturesOlderThanAWeek(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	old, recent := filepath.Join(dir, "0123456789abcdef.png"), filepath.Join(dir, "fedcba9876543210.png")
	for _, p := range []string{old, recent} {
		os.WriteFile(p, []byte("x"), 0o600)
	}
	os.Chtimes(old, now.Add(-8*24*time.Hour), now.Add(-8*24*time.Hour))
	os.Chtimes(recent, now.Add(-6*24*time.Hour), now.Add(-6*24*time.Hour))
	if err := showCmd([]string{pictures(t)["shot.png"]}, dir, now, &bytes.Buffer{}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(old); !os.IsNotExist(err) {
		t.Error("the picture from 8 days ago is still there")
	}
	if _, err := os.Stat(recent); err != nil {
		t.Error("the picture from 6 days ago was removed")
	}
}

func TestShowExitCodes(t *testing.T) {
	bin := filepath.Join(t.TempDir(), "clauderc")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("go build: %v\n%s", err, out)
	}
	dir := filepath.Join(t.TempDir(), "shown")
	run := func(path string) (string, string, error) {
		cmd := exec.Command(bin, "show", path)
		cmd.Env = append(os.Environ(), "CLAUDERC_SHOW_DIR="+dir)
		var out, errOut bytes.Buffer
		cmd.Stdout, cmd.Stderr = &out, &errOut
		err := cmd.Run()
		return out.String(), errOut.String(), err
	}
	out, _, err := run(pictures(t)["shot.png"])
	if err != nil || !markerLine.MatchString(out) {
		t.Errorf("show shot.png: %v, output %q, want exit 0 and a marker line", err, out)
	}
	text := filepath.Join(t.TempDir(), "notes.txt")
	os.WriteFile(text, []byte("hello"), 0o644)
	_, errOut, err := run(text)
	if code := exitCode(err); code == 0 || !strings.Contains(errOut, "不是图片") {
		t.Errorf("show notes.txt: exit %d, stderr %q, want non-zero and the reason", code, errOut)
	}
}

func exitCode(err error) int {
	if e, ok := err.(*exec.ExitError); ok {
		return e.ExitCode()
	}
	if err != nil {
		return -1
	}
	return 0
}
