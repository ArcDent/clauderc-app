package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// skillMark is in every SKILL.md this program writes, so that it updates
// its own and leaves anyone else's alone.
const skillMark = "<!-- 由 clauderc skill install 写入，再运行一次会更新 -->"

// skillText tells Claude when to send the user a picture.
const skillText = `---
name: clauderc-show
description: 用户在手机或电脑上通过 clauderc 看这个会话时，要给用户看图片（截图、图表、生成的图）就运行 clauderc show <图片路径>，图会显示在设备的聊天里。
---

` + skillMark + `

# 给设备上的用户发图

用户可能在手机或电脑上用 clauderc 看这个会话。设备上点不开回复里写的本地路径，所以想让用户看一张图时：

1. 先把图存成文件，PNG、JPEG、GIF 或 WebP，不超过 10 MB。
2. 运行 ` + "`clauderc show <图片路径>`" + `，可以一次给多个路径。
3. 命令每张图输出一行 ` + "`[clauderc show <编号>] <文件名>`" + `，设备就在这条命令的位置显示这张图。

适合发图的时候：用户要看界面截图、图表、生成或修改过的图片，或者文字说不清、看一眼就明白的结果。

命令报错时照错误信息处理，比如文件不是图片或者太大。不需要再把路径或编号写进回复。
`

// skillCmd writes the clauderc-show skill into home's ~/.claude/skills and
// prints where. It leaves an up-to-date copy as it is and refuses to
// overwrite a skill of the same name it did not write.
func skillCmd(home string, out io.Writer) error {
	path := filepath.Join(home, ".claude", "skills", "clauderc-show", "SKILL.md")
	old, err := os.ReadFile(path)
	switch {
	case err == nil && string(old) == skillText:
		fmt.Fprintf(out, "已是最新：%s\n", path)
		return nil
	case err == nil && !bytes.Contains(old, []byte(skillMark)):
		return fmt.Errorf("%s 已有同名技能，不是 clauderc 写的，没有覆盖；要换成 clauderc 的，先删掉它再运行", path)
	case err != nil && !errors.Is(err, os.ErrNotExist):
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(skillText), 0o644); err != nil {
		return err
	}
	if old != nil {
		fmt.Fprintf(out, "已更新：%s\n", path)
	} else {
		fmt.Fprintf(out, "已装到：%s\n", path)
	}
	return nil
}
