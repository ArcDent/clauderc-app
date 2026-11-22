package transcript

import (
	"encoding/json"
	"slices"

	"clauderc/internal/protocol"
)

// Records with a uuid form a tree through their parentUuid. Claude Code
// only appends to a transcript: rewinding the conversation writes nothing,
// and the next record hangs from the point rewound to, so the records
// after that point turn into a branch that is no longer shown. The chat
// shows the branch of the latest record.

// branch notes r in the tree and, when r leaves the branch of the latest
// record, removes the items of the records left behind and returns their
// indexes. A user message with no parent leaves the whole branch behind.
// A record hung from a parent never seen, or one that leaves no user
// message behind, such as a hook's progress beside a tool call, changes
// nothing.
func (t *Transcript) branch(r *record) []int {
	if r.UUID == "" || r.IsSidechain {
		return nil
	}
	parent := r.ParentUUID
	if parent == "" {
		// After /compact the boundary starts afresh but tells what it follows.
		parent = r.LogicalParentUUID
	}
	leaf := t.leaf
	t.parents[r.UUID] = parent
	t.leaf, t.last = r.UUID, r.UUID
	if isPrompt(r) {
		t.prompts[r.UUID] = true
	}
	if leaf == "" || parent == leaf {
		return nil
	}
	if parent == "" {
		// A message of the user's with no parent after other records starts
		// the conversation afresh: a rewind to before the first message.
		if !isPrompt(r) {
			return nil
		}
	} else if _, seen := t.parents[parent]; !seen {
		return nil
	}
	kept := map[string]bool{}
	for u := parent; u != "" && !kept[u]; u = t.parents[u] {
		kept[u] = true
	}
	left := map[string]bool{}
	prompt := false
	for u := leaf; u != "" && !kept[u] && !left[u]; u = t.parents[u] {
		left[u] = true
		prompt = prompt || t.prompts[u]
	}
	if !prompt {
		return nil
	}
	var changed []int
	for i, src := range t.src {
		if left[src] && t.items[i].Kind != protocol.ItemRemoved {
			t.remove(i)
			changed = append(changed, i)
		}
	}
	t.forget(changed)
	return changed
}

// stamp gives the user items the record being fed added or delivered
// the record's uuid, which chat.rewind names; a queued message gets it
// when its user record arrives.
func (t *Transcript) stamp(changed []int) {
	if t.cur == "" {
		return
	}
	for _, i := range changed {
		if it := &t.items[i]; it.Kind == protocol.ItemUser && it.UUID == "" && !it.Queued {
			it.UUID = t.cur
			t.src[i] = t.cur
		}
	}
}

// forget drops what points at the removed items: their tool calls, the
// command waiting for output, the queue, and the latest tool call, which
// becomes the latest one still shown.
func (t *Transcript) forget(removed []int) {
	if len(removed) == 0 {
		return
	}
	gone := map[int]bool{}
	for _, i := range removed {
		gone[i] = true
	}
	for id, i := range t.tools {
		if gone[i] {
			delete(t.tools, id)
		}
	}
	if gone[t.command] {
		t.command = -1
	}
	t.queued = slices.DeleteFunc(t.queued, func(i int) bool { return gone[i] })
	if gone[t.lastTool] {
		t.lastTool = -1
		for i := len(t.items) - 1; i >= 0; i-- {
			if t.items[i].Kind == protocol.ItemTool && t.items[i].Tool != nil {
				t.lastTool = i
				break
			}
		}
	}
}

// isPrompt reports whether r is a message the user sent: a user record
// that is not Claude Code's own note, a notification or a tool result.
// Claude Code 2.1.330 marks a typed message with origin kind "human".
func isPrompt(r *record) bool {
	if r.Type != "user" || r.IsMeta || r.IsCompactSummary || r.Origin.Kind != "" && r.Origin.Kind != "human" || r.Message == nil {
		return false
	}
	var s string
	if json.Unmarshal(r.Message.Content, &s) == nil {
		return true
	}
	var bs []block
	if json.Unmarshal(r.Message.Content, &bs) != nil || len(bs) == 0 {
		return false
	}
	for _, b := range bs {
		if b.Type == "tool_result" {
			return false
		}
	}
	return true
}

// Rewind takes the branch back to before the user message uuid, as
// Claude Code's /rewind does before it writes anything: the items of that
// message's record and of every later record on the branch are removed,
// and the next record is expected to hang from the message's parent. A
// message that is not on the branch changes nothing.
func (t *Transcript) Rewind(uuid string) []int {
	parent, seen := t.parents[uuid]
	if !seen {
		return nil
	}
	left := map[string]bool{}
	for u := t.leaf; u != "" && !left[u]; u = t.parents[u] {
		left[u] = true
		if u != uuid {
			continue
		}
		var changed []int
		for i, src := range t.src {
			if left[src] && t.items[i].Kind != protocol.ItemRemoved {
				t.remove(i)
				changed = append(changed, i)
			}
		}
		t.forget(changed)
		t.leaf = parent
		return changed
	}
	return nil
}
