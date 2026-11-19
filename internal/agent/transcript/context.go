package transcript

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"strings"

	"clauderc/internal/protocol"
)

// Context windows as Claude Code 2.1.325 counts them: a model id ending in
// [1m] gets the long window, as do the models that have it natively;
// everything else, other vendors' models included, the standard one.
const (
	standardWindow = 200_000
	longWindow     = 1_000_000
)

// nativeLong lists the model id prefixes whose standard window is already
// the long one, from Claude Code's model table.
var nativeLong = []string{
	"claude-opus-4-7", "claude-opus-4-8", "claude-opus-5",
	"claude-sonnet-5", "claude-haiku-5", "claude-fable-5", "claude-mythos-5",
}

// contextTails are how much of the file end is searched for the last
// reply, the larger one only when the smaller holds none.
var contextTails = []int64{256 << 10, 4 << 20}

// WindowOf returns the context window of a model id.
func WindowOf(model string) int {
	m := strings.ToLower(strings.TrimSpace(model))
	if strings.HasSuffix(m, "[1m]") {
		return longWindow
	}
	// Ids through a router may carry a vendor prefix and dots: anthropic/claude-fable-5.2.
	m = strings.ReplaceAll(m[strings.LastIndex(m, "/")+1:], ".", "-")
	for _, p := range nativeLong {
		if m == p || strings.HasPrefix(m, p+"-") {
			return longWindow
		}
	}
	return standardWindow
}

// usageRecord is the part of an assistant line ContextOf reads.
type usageRecord struct {
	Type           string `json:"type"`
	IsSidechain    bool   `json:"isSidechain"`
	RequestedModel string `json:"requestedModel"`
	Message        *struct {
		Model string `json:"model"`
		Usage *struct {
			InputTokens              int `json:"input_tokens"`
			CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
			CacheReadInputTokens     int `json:"cache_read_input_tokens"`
		} `json:"usage"`
	} `json:"message"`
}

// ContextOf returns how full the context window was after the last reply
// of the main chain in the transcript at path: the prompt of that reply,
// input plus cache reads and writes, as Claude Code's status line counts
// it. Nil when no reply is found near the end.
func ContextOf(path string) (*protocol.SessionContext, error) {
	tail, err := TailOf(path)
	return tail.Context, err
}

// Tail is what the session list reads from the end of a transcript.
type Tail struct {
	// Context is as ContextOf returns it.
	Context *protocol.SessionContext
	// Branch is the git branch of the last main-chain entry that records
	// one. Empty outside a git repository, where Claude Code records
	// "HEAD", and on a detached head.
	Branch string
}

// TailOf reads ContextOf's context and the branch in one pass over the
// end of the transcript at path.
func TailOf(path string) (Tail, error) {
	var t Tail
	fh, err := os.Open(path)
	if err != nil {
		return t, err
	}
	defer fh.Close()
	fi, err := fh.Stat()
	if err != nil {
		return t, err
	}
	branchSeen := false
	for _, tail := range contextTails {
		from := max(fi.Size()-tail, 0)
		buf := make([]byte, fi.Size()-from)
		if _, err := fh.ReadAt(buf, from); err != nil && err != io.EOF {
			return Tail{}, err
		}
		if from > 0 {
			// The first line is cut off.
			if i := bytes.IndexByte(buf, '\n'); i >= 0 {
				buf = buf[i+1:]
			} else {
				buf = nil
			}
		}
		if t.Context == nil {
			t.Context = lastContext(buf)
		}
		if !branchSeen {
			t.Branch, branchSeen = lastBranch(buf)
		}
		if t.Context != nil && branchSeen || from == 0 {
			return t, nil
		}
	}
	return t, nil
}

// lastBranch scans whole lines from the end for the last main-chain entry
// with a gitBranch; ok is false when none has one.
func lastBranch(buf []byte) (branch string, ok bool) {
	for len(buf) > 0 {
		i := bytes.LastIndexByte(bytes.TrimRight(buf, "\n"), '\n')
		line := buf[i+1:]
		buf = buf[:max(i, 0)]
		if !bytes.Contains(line, []byte(`"gitBranch"`)) {
			continue
		}
		var r struct {
			GitBranch   *string `json:"gitBranch"`
			IsSidechain bool    `json:"isSidechain"`
		}
		if json.Unmarshal(line, &r) != nil || r.GitBranch == nil || r.IsSidechain {
			continue
		}
		if *r.GitBranch == "HEAD" {
			return "", true
		}
		return *r.GitBranch, true
	}
	return "", false
}

// lastContext scans whole lines from the end for the last main-chain reply
// with usage. Synthetic replies, written by Claude Code itself, carry none.
func lastContext(buf []byte) *protocol.SessionContext {
	for len(buf) > 0 {
		i := bytes.LastIndexByte(bytes.TrimRight(buf, "\n"), '\n')
		line := buf[i+1:]
		buf = buf[:max(i, 0)]
		if !bytes.Contains(line, []byte(`"assistant"`)) {
			continue
		}
		var r usageRecord
		if json.Unmarshal(line, &r) != nil || r.Type != "assistant" || r.IsSidechain || r.Message == nil || r.Message.Usage == nil {
			continue
		}
		u := r.Message.Usage
		used := u.InputTokens + u.CacheCreationInputTokens + u.CacheReadInputTokens
		if used == 0 || r.Message.Model == "<synthetic>" {
			continue
		}
		model := r.RequestedModel
		if model == "" {
			model = r.Message.Model
		}
		return &protocol.SessionContext{UsedTokens: used, WindowTokens: WindowOf(model)}
	}
	return nil
}
