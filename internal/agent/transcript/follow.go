package transcript

import (
	"bytes"
	"io"
	"os"
	"sort"
	"sync"

	"clauderc/internal/protocol"
)

// Page returns up to n items with Seq below before, oldest first, and
// whether older items remain. before <= 0 asks for the latest page.
func (t *Transcript) Page(before int64, n int) ([]Item, bool) {
	end := len(t.items)
	if before > 0 {
		// Seqs are 1..len, so the items below before end at index before-1.
		end = min(int(before-1), end)
	}
	start := max(end-n, 0)
	return append([]Item{}, t.items[start:end]...), start > 0
}

// Follower keeps a Transcript in step with a growing transcript file. Its
// methods are safe for concurrent use.
type Follower struct {
	path string

	mu      sync.Mutex
	t       *Transcript
	off     int64  // file offset up to which bytes were consumed
	partial []byte // bytes after the last newline, not yet a whole line
}

// Open parses the complete lines of the file at path; a trailing partial
// line waits for its newline.
func Open(path string) (*Follower, error) {
	f := &Follower{path: path, t: newTranscript()}
	if _, err := f.Poll(); err != nil {
		return nil, err
	}
	return f, nil
}

// Poll reads what was appended since the last call and returns the items
// it added or changed, in seq order.
func (f *Follower) Poll() ([]Item, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fh, err := os.Open(f.path)
	if err != nil {
		return nil, err
	}
	defer fh.Close()
	if _, err := fh.Seek(f.off, io.SeekStart); err != nil {
		return nil, err
	}
	data, err := io.ReadAll(fh)
	if err != nil {
		return nil, err
	}
	f.off += int64(len(data))
	data = append(f.partial, data...)
	last := bytes.LastIndexByte(data, '\n')
	if last < 0 {
		f.partial = data
		return nil, nil
	}
	f.partial = append([]byte(nil), data[last+1:]...)
	changed := map[int]bool{}
	for _, line := range bytes.SplitAfter(data[:last+1], []byte("\n")) {
		for _, i := range f.t.feed(line) {
			changed[i] = true
		}
	}
	idx := make([]int, 0, len(changed))
	for i := range changed {
		idx = append(idx, i)
	}
	sort.Ints(idx)
	items := make([]Item, 0, len(idx))
	for _, i := range idx {
		items = append(items, f.t.items[i])
	}
	return items, nil
}

// Page is Transcript.Page on the current state.
func (f *Follower) Page(before int64, n int) ([]Item, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.t.Page(before, n)
}

// Pending is Transcript.Pending on the current state.
func (f *Follower) Pending(blocked bool) *protocol.Pending {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.t.Pending(blocked)
}

// QueuedCommands is Transcript.QueuedCommands on the current state.
func (f *Follower) QueuedCommands() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.t.QueuedCommands()
}

// Info is Transcript.Info on the current state.
func (f *Follower) Info() Info {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.t.Info()
}
