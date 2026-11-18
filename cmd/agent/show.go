package main

import (
	"fmt"
	"io"
	"path/filepath"
	"time"

	"clauderc/internal/agent/picture"
)

// showCmd keeps each picture in dir and prints the line that makes the
// phone show it under the command, after removing pictures older than
// picture.KeepFor. Claude runs it; see `clauderc skill install`.
func showCmd(args []string, dir string, now time.Time, out io.Writer) error {
	if len(args) == 0 {
		return fmt.Errorf("用法：%s show <图片路径>...", prog)
	}
	picture.Clean(dir, now)
	for _, path := range args {
		id, err := picture.Save(dir, path)
		if err != nil {
			return err
		}
		fmt.Fprintln(out, picture.Marker(id, filepath.Base(path)))
	}
	return nil
}
