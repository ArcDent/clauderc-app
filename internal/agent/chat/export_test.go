package chat

import "time"

// SetRewindTotal sets the time limit of a rewind up to its last Enter for
// a test and returns what puts it back.
func SetRewindTotal(d time.Duration) (restore func()) {
	was := rewindTotal
	rewindTotal = d
	return func() { rewindTotal = was }
}

// SetBeforeRewindTerm runs fn between a rewind's checks and taking the
// terminal, and returns what puts it back.
func SetBeforeRewindTerm(fn func()) (restore func()) {
	was := beforeRewindTerm
	beforeRewindTerm = fn
	return func() { beforeRewindTerm = was }
}
