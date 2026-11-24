// Package conn keeps the agent connected to the relay (design D2). It
// dials /ws/agent with the enroll key, hands each request to the handler
// that lists its method, sends responses and pushed events back in order,
// pings every HeartbeatInterval, drops a connection the relay has sent
// nothing on for HeartbeatTimeout, and after a drop reconnects with pauses
// that double up to MaxBackoff.
package conn

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
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

// outQueue is how many outgoing messages wait for the writer; an event
// beyond it drops the connection (see Push), a response waits.
const outQueue = 256

// errQueueFull ends a connection whose queue an event found full.
var errQueueFull = errors.New("send queue full")

// errSilent ends a connection the relay sent nothing on, not even a ping,
// for HeartbeatTimeout.
var errSilent = errors.New("nothing from the relay within the heartbeat timeout")

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
	// A connection that gets no frame from the relay, its pings included,
	// for HeartbeatTimeout is dropped and dialed again.
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
	// cancel ends the live connection; nil while offline.
	cancel context.CancelCauseFunc
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
// connection, or dropped while offline. One that finds the queue full is
// not dropped alone, as the events after it would still go out and the
// phone could miss a chat.pending for good: the connection is dropped
// instead, and so is every event until the next connection is up. The relay
// then tells the phones the machine is back, and they open their pages
// again and get whole snapshots.
func (c *Conn) Push(evt protocol.Envelope) {
	data, err := protocol.Encode(evt)
	if err != nil {
		c.opts.Logger.Error("encode event", "method", evt.Method, "err", err)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.out == nil {
		return
	}
	select {
	case c.out <- data:
	default:
		c.opts.Logger.Warn("send queue full, reconnecting", "method", evt.Method)
		c.cancel(errQueueFull)
		c.out, c.cancel = nil, nil
	}
}

// ErrMachineInUse ends Run: the relay refused the connection because an
// agent with the same machine id is connected and alive there.
var ErrMachineInUse = errors.New("这个目标机身份已在中转服务器上在线；如果配置目录是从别的电脑或别的目录拷来的，删掉其中的 machine-id 文件后再运行")

// Run stays connected until ctx ends and then returns ctx's error, or
// returns ErrMachineInUse without retrying.
func (c *Conn) Run(ctx context.Context) error {
	backoff := c.opts.MinBackoff
	for {
		connected, err := c.session(ctx)
		c.allClientsGone()
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, ErrMachineInUse) {
			return err
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

// machineInUse reports whether resp is the relay refusing a second agent
// with our machine id.
func machineInUse(resp *http.Response) bool {
	if resp == nil || resp.StatusCode != http.StatusConflict {
		return false
	}
	var e protocol.Error
	return json.NewDecoder(resp.Body).Decode(&e) == nil && e.Code == protocol.CodeMachineInUse
}

// wsWriteChunk is the max payload per WebSocket fragment when streaming large
// messages so control frames (Ping/Pong) interleave freely between chunks.
const wsWriteChunk = 64 << 10

// writeTimeout scales with message length so large transfers do not trip a
// short fixed deadline on slow links.
func (c *Conn) writeTimeout(n int) time.Duration {
	d := c.opts.HeartbeatTimeout + time.Duration(n/wsWriteChunk)*time.Second
	return min(d, 5*time.Minute)
}

func writeMessage(ctx context.Context, ws *websocket.Conn, data []byte) error {
	if len(data) <= wsWriteChunk {
		return ws.Write(ctx, websocket.MessageText, data)
	}
	w, err := ws.Writer(ctx, websocket.MessageText)
	if err != nil {
		return err
	}
	for off := 0; off < len(data); off += wsWriteChunk {
		if _, err := w.Write(data[off:min(off+wsWriteChunk, len(data))]); err != nil {
			return err
		}
	}
	return w.Close()
}

type activityConn struct {
	net.Conn
	onRead func()
}

func (a *activityConn) Read(b []byte) (int, error) {
	n, err := a.Conn.Read(b)
	if n > 0 && a.onRead != nil {
		a.onRead()
	}
	return n, err
}

// clientWithReadHook returns a copy of base whose TCP connections call onRead
// on every received chunk, so receiving a large message refreshes liveness.
func clientWithReadHook(base *http.Client, onRead func()) *http.Client {
	var tr *http.Transport
	if base != nil && base.Transport != nil {
		t, ok := base.Transport.(*http.Transport)
		if !ok {
			return base
		}
		tr = t.Clone()
	} else {
		t, ok := http.DefaultTransport.(*http.Transport)
		if !ok {
			return base
		}
		tr = t.Clone()
	}
	dial := tr.DialContext
	if dial == nil {
		dial = (&net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}).DialContext
	}
	tr.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		nc, err := dial(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		return &activityConn{Conn: nc, onRead: onRead}, nil
	}
	if tr.DialTLSContext != nil {
		dialTLS := tr.DialTLSContext
		tr.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			nc, err := dialTLS(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			return &activityConn{Conn: nc, onRead: onRead}, nil
		}
	}
	out := &http.Client{Transport: tr}
	if base != nil {
		out.CheckRedirect = base.CheckRedirect
		out.Jar = base.Jar
		out.Timeout = base.Timeout
	}
	return out
}

// session dials once and serves until the connection drops; connected
// tells whether the dial succeeded.
func (c *Conn) session(ctx context.Context) (connected bool, err error) {
	ctx, cancel := context.WithCancelCause(ctx)
	defer cancel(nil)
	// silence bounds the dial and then drops the connection once nothing
	// has come from the relay for HeartbeatTimeout (design D2). Every frame
	// and TCP chunk read starts it over: messages, large frame fragments,
	// and the pings and pongs the websocket library handles internally.
	var lastHeard atomic.Int64
	var stopped atomic.Bool
	lastHeard.Store(time.Now().UnixNano())
	silence := time.AfterFunc(c.opts.HeartbeatTimeout, func() { cancel(errSilent) })
	defer func() {
		stopped.Store(true)
		silence.Stop()
	}()
	heard := func() {
		lastHeard.Store(time.Now().UnixNano())
		if !stopped.Load() {
			silence.Reset(c.opts.HeartbeatTimeout)
		}
	}
	ws, resp, err := websocket.Dial(ctx, c.wsURL, &websocket.DialOptions{
		HTTPClient:     clientWithReadHook(c.opts.HTTPClient, heard),
		HTTPHeader:     http.Header{"Authorization": {"Bearer " + c.opts.EnrollKey}},
		OnPingReceived: func(context.Context, []byte) bool { heard(); return true },
		OnPongReceived: func(context.Context, []byte) { heard() },
	})
	if err != nil {
		if machineInUse(resp) {
			return false, ErrMachineInUse
		}
		return false, err
	}
	heard()
	defer ws.CloseNow()
	ws.SetReadLimit(protocol.MaxMessageBytes)
	// Phones known to handlers from an earlier connection are gone.
	c.allClientsGone()
	c.opts.Logger.Info("relay connected")

	out := make(chan []byte, outQueue)
	c.mu.Lock()
	c.out, c.cancel = out, cancel
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.out, c.cancel = nil, nil
		c.mu.Unlock()
	}()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); c.write(ctx, cancel, ws, out) }()
	go func() { defer wg.Done(); c.heartbeat(ctx, cancel, ws, &lastHeard) }()
	for {
		_, data, err := ws.Read(ctx)
		if err != nil {
			cancel(err)
			break
		}
		heard()
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
			// Once Push has dropped an event, a response queued after it
			// must not go out either.
			if ctx.Err() != nil {
				return
			}
			wctx, wcancel := context.WithTimeout(ctx, c.writeTimeout(len(data)))
			err := writeMessage(wctx, ws, data)
			wcancel()
			if err != nil {
				cancel(fmt.Errorf("write: %w", err))
				return
			}
		}
	}
}

// heartbeat pings every interval and drops the connection when a pong
// does not come back within the timeout, unless bytes are actively arriving.
func (c *Conn) heartbeat(ctx context.Context, cancel context.CancelCauseFunc, ws *websocket.Conn, lastHeard *atomic.Int64) {
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
			if errors.Is(err, context.DeadlineExceeded) && time.Since(time.Unix(0, lastHeard.Load())) < c.opts.HeartbeatTimeout {
				continue
			}
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
