// Package conn keeps the agent connected to the relay (design D2). It
// dials /ws/agent with the enroll key, hands each request to the handler
// that lists its method, sends responses and pushed events back in order,
// pings every HeartbeatInterval, and after a drop reconnects with pauses
// that double up to MaxBackoff.
package conn

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/agent"
	"clauderc/internal/protocol"
)

// Default reconnect pauses (task 10.1: at most 60 seconds).
const (
	DefaultMinBackoff = time.Second
	DefaultMaxBackoff = 60 * time.Second
)

// outQueue is how many outgoing messages wait for the writer; events
// beyond it are dropped, responses wait.
const outQueue = 256

// Options configures a Conn.
type Options struct {
	// RelayURL is the relay's address: https:// or wss://, optionally
	// with a path prefix.
	RelayURL  string
	EnrollKey string
	// MachineID, Name, AgentVersion and ClaudeVersion identify this
	// machine to the relay.
	MachineID     string
	Name          string
	AgentVersion  string
	ClaudeVersion string

	// MinBackoff and MaxBackoff default to DefaultMinBackoff and
	// DefaultMaxBackoff.
	MinBackoff, MaxBackoff time.Duration
	// HeartbeatInterval and HeartbeatTimeout default to the protocol's.
	HeartbeatInterval, HeartbeatTimeout time.Duration
	// HTTPClient dials the relay; nil uses the default client.
	HTTPClient *http.Client
	Logger     *slog.Logger
}

// Conn is the agent's relay connection. Register the handlers, then Run.
type Conn struct {
	opts   Options
	wsURL  string
	routes map[protocol.Method]agent.Handler
	// trackers are the registered handlers that keep per-client state.
	trackers []agent.ClientTracker

	mu  sync.Mutex
	out chan []byte // queue of the live connection; nil while offline
}

// New checks the options and returns a Conn without connecting.
func New(opts Options) (*Conn, error) {
	u, err := agentURL(opts.RelayURL, opts)
	if err != nil {
		return nil, err
	}
	if opts.MinBackoff <= 0 {
		opts.MinBackoff = DefaultMinBackoff
	}
	if opts.MaxBackoff <= 0 {
		opts.MaxBackoff = DefaultMaxBackoff
	}
	if opts.HeartbeatInterval <= 0 {
		opts.HeartbeatInterval = protocol.HeartbeatInterval
	}
	if opts.HeartbeatTimeout <= 0 {
		opts.HeartbeatTimeout = protocol.HeartbeatTimeout
	}
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	return &Conn{opts: opts, wsURL: u, routes: map[protocol.Method]agent.Handler{}}, nil
}

// agentURL turns the relay address into the /ws/agent URL with this
// machine's query parameters; plain http:// and ws:// are refused.
func agentURL(relay string, opts Options) (string, error) {
	u, err := url.Parse(relay)
	if err != nil {
		return "", fmt.Errorf("中转服务器地址 %q 无法解析：%w", relay, err)
	}
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	case "wss":
	default:
		return "", fmt.Errorf("中转服务器地址 %q 只能用 https:// 或 wss://", relay)
	}
	if opts.MachineID == "" {
		return "", errors.New("缺少目标机 id")
	}
	u.Path = strings.TrimSuffix(u.Path, "/") + protocol.PathAgentWS
	q := url.Values{}
	q.Set(protocol.AgentParamID, opts.MachineID)
	q.Set(protocol.AgentParamName, opts.Name)
	q.Set(protocol.AgentParamAgentVersion, opts.AgentVersion)
	q.Set(protocol.AgentParamClaudeVersion, opts.ClaudeVersion)
	u.RawQuery = q.Encode()
	return u.String(), nil
}

// Register routes the methods h lists to h. It must be called before Run;
// a method listed twice is an error.
func (c *Conn) Register(handlers ...agent.Handler) error {
	for _, h := range handlers {
		for _, m := range h.Methods() {
			if _, dup := c.routes[m]; dup {
				return fmt.Errorf("方法 %s 重复注册", m)
			}
			c.routes[m] = h
		}
		if t, ok := h.(agent.ClientTracker); ok {
			c.trackers = append(c.trackers, t)
		}
	}
	return nil
}

// Push implements agent.Pusher. The event is queued on the live
// connection, or dropped while offline or when the queue is full.
func (c *Conn) Push(evt protocol.Envelope) {
	data, err := protocol.Encode(evt)
	if err != nil {
		c.opts.Logger.Error("encode event", "method", evt.Method, "err", err)
		return
	}
	c.mu.Lock()
	out := c.out
	c.mu.Unlock()
	if out == nil {
		return
	}
	select {
	case out <- data:
	default:
		c.opts.Logger.Warn("event dropped, queue full", "method", evt.Method)
	}
}

// Run stays connected until ctx ends and then returns ctx's error.
func (c *Conn) Run(ctx context.Context) error {
	backoff := c.opts.MinBackoff
	for {
		connected, err := c.session(ctx)
		c.allClientsGone()
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if connected {
			backoff = c.opts.MinBackoff
		}
		c.opts.Logger.Warn("relay connection lost", "err", err, "retry in", backoff)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		if !connected {
			backoff = min(backoff*2, c.opts.MaxBackoff)
		}
	}
}

func (c *Conn) allClientsGone() {
	for _, t := range c.trackers {
		t.AllClientsGone()
	}
}

// session dials once and serves until the connection drops; connected
// tells whether the dial succeeded.
func (c *Conn) session(ctx context.Context) (connected bool, err error) {
	dialCtx, cancelDial := context.WithTimeout(ctx, c.opts.HeartbeatTimeout)
	ws, _, err := websocket.Dial(dialCtx, c.wsURL, &websocket.DialOptions{
		HTTPClient: c.opts.HTTPClient,
		HTTPHeader: http.Header{"Authorization": {"Bearer " + c.opts.EnrollKey}},
	})
	cancelDial()
	if err != nil {
		return false, err
	}
	defer ws.CloseNow()
	ws.SetReadLimit(protocol.MaxMessageBytes)
	// Phones known to handlers from an earlier connection are gone.
	c.allClientsGone()
	c.opts.Logger.Info("relay connected")

	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	out := make(chan []byte, outQueue)
	c.mu.Lock()
	c.out = out
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.out = nil
		c.mu.Unlock()
	}()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); c.write(ctx, cancel, ws, out) }()
	go func() { defer wg.Done(); c.heartbeat(ctx, cancel, ws) }()
	for {
		_, data, err := ws.Read(ctx)
		if err != nil {
			cancel(err)
			break
		}
		c.dispatch(ctx, &wg, out, data)
	}
	wg.Wait()
	return true, context.Cause(ctx)
}

// write sends queued messages until ctx ends or a write fails.
func (c *Conn) write(ctx context.Context, cancel context.CancelCauseFunc, ws *websocket.Conn, out <-chan []byte) {
	for {
		select {
		case <-ctx.Done():
			return
		case data := <-out:
			wctx, wcancel := context.WithTimeout(ctx, c.opts.HeartbeatTimeout)
			err := ws.Write(wctx, websocket.MessageText, data)
			wcancel()
			if err != nil {
				cancel(fmt.Errorf("write: %w", err))
				return
			}
		}
	}
}

// heartbeat pings every interval and drops the connection when a pong
// does not come back within the timeout.
func (c *Conn) heartbeat(ctx context.Context, cancel context.CancelCauseFunc, ws *websocket.Conn) {
	t := time.NewTicker(c.opts.HeartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		pctx, pcancel := context.WithTimeout(ctx, c.opts.HeartbeatTimeout)
		err := ws.Ping(pctx)
		pcancel()
		if err != nil {
			cancel(fmt.Errorf("heartbeat: %w", err))
			return
		}
	}
}

// dispatch handles one incoming message: requests run on their own
// goroutine and answer through out; client.gone reaches every tracker.
func (c *Conn) dispatch(ctx context.Context, wg *sync.WaitGroup, out chan<- []byte, data []byte) {
	env, err := protocol.Decode(data)
	if err != nil {
		c.opts.Logger.Warn("bad message from relay", "err", err)
		return
	}
	switch env.Kind {
	case protocol.KindRequest:
		h := c.routes[env.Method]
		if h == nil {
			c.reply(ctx, out, agent.FailUnknown(env))
			return
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.reply(ctx, out, h.Handle(ctx, env))
		}()
	case protocol.KindEvent:
		if env.Method == protocol.EventClientGone {
			for _, t := range c.trackers {
				t.ClientGone(env.Client)
			}
		}
	}
}

// reply queues a response, waiting for room unless the connection ends.
func (c *Conn) reply(ctx context.Context, out chan<- []byte, res protocol.Envelope) {
	data, err := protocol.Encode(res)
	if err != nil {
		c.opts.Logger.Error("encode response", "method", res.Method, "err", err)
		return
	}
	select {
	case out <- data:
	case <-ctx.Done():
	}
}
