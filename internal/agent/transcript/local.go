package transcript

import (
	"bufio"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"clauderc/internal/protocol"
)

// LocalCommands lists the skills and custom commands on disk that a
// session in cwd can run as slash commands: <cwd>/.claude first, then the
// user's own under claudeDir (normally ~/.claude). The transcript's skill
// listing misses those Claude Code keeps from the model, i.e. skills with
// disable-model-invocation, so the phone needs both. Skills marked
// user-invocable: false are left out; symlinked skill folders are
// followed.
func LocalCommands(claudeDir, cwd string) []protocol.Command {
	var bases []string
	if cwd != "" {
		bases = append(bases, filepath.Join(cwd, ".claude"))
	}
	if claudeDir != "" {
		bases = append(bases, claudeDir)
	}
	var out []protocol.Command
	for _, base := range bases {
		out = append(out, skillsIn(filepath.Join(base, "skills"))...)
		out = append(out, commandsIn(filepath.Join(base, "commands"), "", 0)...)
	}
	return out
}

// skillsIn reads <dir>/<name>/SKILL.md for every skill folder in dir.
func skillsIn(dir string) []protocol.Command {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []protocol.Command
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		fm, _, ok := readFrontMatter(filepath.Join(dir, e.Name(), "SKILL.md"))
		if !ok || fm["user-invocable"] == "false" {
			continue
		}
		name := fm["name"]
		if name == "" {
			name = e.Name()
		}
		out = append(out, protocol.Command{Name: name, Description: fm["description"]})
	}
	return out
}

// maxCommandDepth bounds the walk through command folders, which also
// keeps a symlink loop from running forever.
const maxCommandDepth = 3

// commandsIn lists <dir>/**/*.md; a file in a subfolder is named with the
// folders joined by colons, e.g. opsx/apply.md is opsx:apply.
func commandsIn(dir, prefix string, depth int) []protocol.Command {
	entries, err := os.ReadDir(dir)
	if err != nil || depth > maxCommandDepth {
		return nil
	}
	var out []protocol.Command
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		fi, err := os.Stat(path)
		if err != nil {
			continue
		}
		if fi.IsDir() {
			out = append(out, commandsIn(path, prefix+e.Name()+":", depth+1)...)
			continue
		}
		name, ok := strings.CutSuffix(e.Name(), ".md")
		if !ok || !fi.Mode().IsRegular() {
			continue
		}
		fm, body, ok := readFrontMatter(path)
		if !ok || fm["user-invocable"] == "false" {
			continue
		}
		desc := fm["description"]
		if desc == "" {
			desc = body
		}
		out = append(out, protocol.Command{Name: prefix + name, Description: desc})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// frontMatterLimit is how much of a skill or command file is read.
const frontMatterLimit = 16 << 10

// readFrontMatter returns the top-level scalar keys of the file's YAML
// front matter and the first non-empty line after it. Folded and literal
// blocks (> and |) are joined into one line. ok is false when the file
// cannot be read.
func readFrontMatter(path string) (fm map[string]string, firstLine string, ok bool) {
	fh, err := os.Open(path)
	if err != nil {
		return nil, "", false
	}
	defer fh.Close()
	fi, err := fh.Stat()
	if err != nil || !fi.Mode().IsRegular() {
		return nil, "", false
	}
	sc := bufio.NewScanner(io.LimitReader(fh, frontMatterLimit))
	fm = map[string]string{}
	in, started := false, false
	var block string // key of the folded or literal block being read
	for sc.Scan() {
		line := strings.TrimRight(sc.Text(), " \t\r")
		if !started {
			started = true
			if line == "---" {
				in = true
				continue
			}
		}
		if in {
			if line == "---" {
				in = false
				continue
			}
			if block != "" && (strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t") || line == "") {
				if t := strings.TrimSpace(line); t != "" {
					fm[block] = strings.TrimSpace(fm[block] + " " + t)
				}
				continue
			}
			block = ""
			key, val, found := strings.Cut(line, ":")
			if !found || key == "" || strings.HasPrefix(key, " ") || strings.HasPrefix(key, "#") {
				continue
			}
			val = strings.TrimSpace(val)
			if val == ">" || val == "|" || val == ">-" || val == "|-" {
				block = key
				continue
			}
			fm[key] = unquote(val)
			continue
		}
		if t := strings.TrimSpace(line); t != "" {
			return fm, strings.TrimLeft(t, "# "), true
		}
	}
	return fm, "", true
}

func unquote(s string) string {
	if len(s) >= 2 && (s[0] == '"' && s[len(s)-1] == '"' || s[0] == '\'' && s[len(s)-1] == '\'') {
		return s[1 : len(s)-1]
	}
	return s
}
