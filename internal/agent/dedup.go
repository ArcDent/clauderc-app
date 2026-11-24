package agent

import (
	"context"
	"sync"
	"time"
)

// DefaultDedupTTL is how long a completed clientReqId stays cached so retries
// return the same outcome without running the operation a second time.
const DefaultDedupTTL = 10 * time.Minute

type dedupEntry[T any] struct {
	done chan struct{}
	at   time.Time
	val  T
	err  error
}

// Dedup deduplicates write operations keyed by clientReqId within a TTL window.
// Concurrent requests with the same key share the in-flight execution; once it
// succeeds, later requests within ttl receive the cached result. Failed runs
// are not cached so subsequent retries can run again.
type Dedup[T any] struct {
	ttl time.Duration
	now func() time.Time

	mu      sync.Mutex
	entries map[string]*dedupEntry[T]
}

// NewDedup returns a Dedup cache using ttl (defaulting to DefaultDedupTTL) and
// clock now (defaulting to time.Now).
func NewDedup[T any](ttl time.Duration, now func() time.Time) *Dedup[T] {
	if ttl <= 0 {
		ttl = DefaultDedupTTL
	}
	if now == nil {
		now = time.Now
	}
	return &Dedup[T]{ttl: ttl, now: now, entries: map[string]*dedupEntry[T]{}}
}

// Do runs fn at most once for non-empty key within ttl. An empty key bypasses
// deduplication and runs fn directly.
func (d *Dedup[T]) Do(ctx context.Context, key string, fn func() (T, error)) (T, error) {
	if key == "" {
		return fn()
	}
	now := d.now()
	d.mu.Lock()
	for k, e := range d.entries {
		select {
		case <-e.done:
			if now.Sub(e.at) >= d.ttl {
				delete(d.entries, k)
			}
		default:
		}
	}
	if e, ok := d.entries[key]; ok {
		d.mu.Unlock()
		select {
		case <-e.done:
			return e.val, e.err
		case <-ctx.Done():
			var zero T
			return zero, ctx.Err()
		}
	}
	e := &dedupEntry[T]{done: make(chan struct{})}
	d.entries[key] = e
	d.mu.Unlock()

	val, err := fn()
	d.mu.Lock()
	e.val, e.err, e.at = val, err, d.now()
	if err != nil {
		// Remove failed entries so a later retry can run afresh; any
		// concurrent waiter holding e still gets e.err via e.done.
		if d.entries[key] == e {
			delete(d.entries, key)
		}
	}
	close(e.done)
	d.mu.Unlock()
	return val, err
}
