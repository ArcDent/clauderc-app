package relay

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

// Options configures a Server. Zero durations fall back to the protocol
// defaults; tests shorten them.
type Options struct {
	EnrollKey string
	// PublicURL is the wss:// address put into pairing URIs.
	PublicURL string
	Store     *store.Store
	Logger    *slog.Logger
	// Now is the clock for timestamps; defaults to time.Now.
	Now func() time.Time

	HeartbeatInterval time.Duration
	HeartbeatTimeout  time.Duration

	// PairCodeTTL defaults to 5 minutes (design D4).
	PairCodeTTL time.Duration
	// PairFailureLimit failed pairings per source within PairFailureWindow
	// trigger rate limiting; defaults 10 per minute.
	PairFailureLimit  int
	PairFailureWindow time.Duration
}

// Server authenticates phones and agents and forwards envelopes between
// them. Message bodies are only held in memory and never logged.
type Server struct {
	opts Options
	log  *slog.Logger
	mux  *http.ServeMux

	// base is the parent of every connection; Close cancels it.
	base      context.Context
	cancelAll context.CancelFunc
	conns     sync.WaitGroup

	mu sync.Mutex
	// closed is set by Close; no connection handler starts after it.
	closed bool
	agents map[string]*agentConn
	apps   map[string]*appConn
	// statusMu orders machine online and offline transitions, so each
	// one's store write and broadcast finish before the next begins.
	statusMu sync.Mutex

	// Pairing codes live only in memory (design D4).
	pairMu       sync.Mutex
	pairCodes    map[string]time.Time // code -> expiry
	pairFailures map[string][]time.Time
	// pairSweptAt is when every source in pairFailures was last pruned.
	pairSweptAt time.Time
}

// NewServer builds a relay server; it serves HTTP via ServeHTTP.
func NewServer(o Options) *Server {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.HeartbeatInterval == 0 {
		o.HeartbeatInterval = protocol.HeartbeatInterval
	}
	if o.HeartbeatTimeout == 0 {
		o.HeartbeatTimeout = protocol.HeartbeatTimeout
	}
	if o.PairCodeTTL == 0 {
		o.PairCodeTTL = 5 * time.Minute
	}
	if o.PairFailureLimit == 0 {
		o.PairFailureLimit = 10
	}
	if o.PairFailureWindow == 0 {
		o.PairFailureWindow = time.Minute
	}
	if o.Logger == nil {
		o.Logger = slog.Default()
	}
	s := &Server{
		opts:   o,
		log:    o.Logger,
		mux:    http.NewServeMux(),
		agents: map[string]*agentConn{},
		apps:   map[string]*appConn{},

		pairCodes:    map[string]time.Time{},
		pairFailures: map[string][]time.Time{},
	}
	s.base, s.cancelAll = context.WithCancel(context.Background())
	s.mux.HandleFunc("GET "+protocol.PathHealth, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok\n"))
	})
	s.mux.HandleFunc("GET "+protocol.PathAgentWS, s.handleAgent)
	s.mux.HandleFunc("GET "+protocol.PathAppWS, s.handleApp)
	s.mux.HandleFunc("POST "+protocol.PathPair, s.handlePair)
	s.mux.HandleFunc("POST "+protocol.PathAdminPairCodes, s.requireEnrollKey(s.handleCreatePairCode))
	s.mux.HandleFunc("GET "+protocol.PathAdminDevices, s.requireEnrollKey(s.handleListDevices))
	s.mux.HandleFunc("DELETE "+protocol.PathAdminDevices+"/{id}", s.requireEnrollKey(s.handleRevokeDevice))
	return s
}

// Close drops every WebSocket connection and waits for their handlers.
// Connections arriving afterwards are refused.
func (s *Server) Close() {
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	s.cancelAll()
	s.conns.Wait()
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

// Machines lists every registered machine with its online state.
func (s *Server) Machines() []protocol.Machine {
	stored := s.opts.Store.Machines()
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]protocol.Machine, 0, len(stored))
	for _, m := range stored {
		online := false
		if a, ok := s.agents[m.ID]; ok {
			online = true
			m = a.machine
			m.LastSeenAt = a.peer.seen()
		}
		out = append(out, machineInfo(m, online))
	}
	return out
}

func machineInfo(m store.Machine, online bool) protocol.Machine {
	return protocol.Machine{
		ID: m.ID, Name: m.Name, Online: online, LastSeenAt: m.LastSeenAt,
		AgentVersion: m.AgentVersion, ClaudeVersion: m.ClaudeVersion,
	}
}

// broadcastMachine sends machine.status to every connected phone.
func (s *Server) broadcastMachine(m protocol.Machine) {
	body, err := json.Marshal(m)
	if err != nil {
		return
	}
	env := protocol.Envelope{V: protocol.Version, Kind: protocol.KindEvent, Method: protocol.EventMachineStatus, Machine: m.ID, Body: body}
	s.mu.Lock()
	apps := make([]*appConn, 0, len(s.apps))
	for _, a := range s.apps {
		apps = append(apps, a)
	}
	s.mu.Unlock()
	for _, a := range apps {
		a.peer.sendEnvelope(env)
	}
}

// bearer returns the token from "Authorization: Bearer <token>".
func bearer(r *http.Request) string {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return ""
	}
	return token
}

// isEnrollKey compares in constant time; hashing first hides the length.
func (s *Server) isEnrollKey(token string) bool {
	a := sha256.Sum256([]byte(token))
	b := sha256.Sum256([]byte(s.opts.EnrollKey))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}

func writeError(w http.ResponseWriter, status int, code protocol.ErrorCode, msg string) {
	writeJSON(w, status, protocol.Error{Code: code, Message: msg})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// closeTimeout bounds the closing handshake. A revoked phone must be gone
// within one second (task 5.7), so this stays well below that.
const closeTimeout = 500 * time.Millisecond

// track registers a connection handler unless the server is closing. A
// handler that gets true must call s.conns.Done when it returns.
func (s *Server) track() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return false
	}
	s.conns.Add(1)
	return true
}

// refuseClosing answers a connection that arrives while the server closes.
func refuseClosing(w http.ResponseWriter) {
	writeError(w, http.StatusServiceUnavailable, protocol.CodeInternal, "中转服务器正在关闭")
}

// wireTime is how every time leaves the relay: UTC whole seconds, matching
// the protocol samples.
func wireTime(t time.Time) time.Time { return t.UTC().Truncate(time.Second) }

// peer is one accepted WebSocket with a buffered writer and a heartbeat.
type peer struct {
	s    *Server
	conn *websocket.Conn
	// raw is the hijacked TCP connection, closed directly when the client
	// does not finish the closing handshake in time.
	raw    net.Conn
	ctx    context.Context
	cancel context.CancelFunc
	out    chan []byte
	// alive is the wall clock of the last frame or pong, for deadlines.
	alive atomic.Int64
	// seenAt is the same moment on the server clock, for LastSeenAt.
	seenAt atomic.Int64
	// loops are writeLoop and heartbeat.
	loops sync.WaitGroup

	closeOnce   sync.Once
	closeCode   websocket.StatusCode
	closeReason string
}

const sendQueue = 64

// hijackRecorder keeps the TCP connection websocket.Accept hijacks.
type hijackRecorder struct {
	http.ResponseWriter
	raw net.Conn
}

func (h *hijackRecorder) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	c, brw, err := http.NewResponseController(h.ResponseWriter).Hijack()
	h.raw = c
	return c, brw, err
}

// accept upgrades the request and starts the peer's writer and heartbeat;
// it returns nil if the upgrade fails. The caller must run serve.
func (s *Server) accept(w http.ResponseWriter, r *http.Request) *peer {
	hw := &hijackRecorder{ResponseWriter: w}
	c, err := websocket.Accept(hw, r, nil)
	if err != nil {
		return nil
	}
	c.SetReadLimit(protocol.MaxMessageBytes)
	ctx, cancel := context.WithCancel(s.base)
	p := &peer{s: s, conn: c, raw: hw.raw, ctx: ctx, cancel: cancel, out: make(chan []byte, sendQueue)}
	p.touch()
	p.loops.Add(2)
	go p.writeLoop()
	go p.heartbeat()
	return p
}

func (p *peer) touch() {
	p.alive.Store(time.Now().UnixNano())
	p.seenAt.Store(p.s.opts.Now().UnixNano())
}

func (p *peer) seen() time.Time { return wireTime(time.Unix(0, p.seenAt.Load())) }

// close ends the connection with code and reason; only the first call
// counts. It cancels the peer and returns at once. The handler goroutine
// in serve, which Server.Close waits for, then sends the close frame.
func (p *peer) close(code websocket.StatusCode, reason string) {
	p.closeOnce.Do(func() {
		p.closeCode, p.closeReason = code, reason
		p.cancel()
	})
}

// serve passes each text message to handle until the peer is closed or
// the connection drops. It then sends the close frame and drops the TCP
// connection if the client has not answered within closeTimeout.
func (p *peer) serve(handle func([]byte)) {
	read := make(chan struct{})
	go func() {
		defer close(read)
		p.readLoop(handle)
	}()
	<-p.ctx.Done()
	// Without an earlier close call the cancel came from Server.Close.
	p.close(websocket.StatusGoingAway, "relay shutting down")
	cut := time.AfterFunc(closeTimeout, func() { p.raw.Close() })
	p.conn.Close(p.closeCode, p.closeReason)
	cut.Stop()
	<-read
	p.loops.Wait()
}

// send queues a message; a peer that cannot keep up is disconnected.
func (p *peer) send(msg []byte) {
	select {
	case p.out <- msg:
	default:
		p.close(websocket.StatusTryAgainLater, "send queue full")
	}
}

// writeLoop and heartbeat bound each operation with their own timeout, not
// the peer context: cancelling a context mid-operation makes the websocket
// library drop the connection before serve can send the close frame.
func (p *peer) writeLoop() {
	defer p.loops.Done()
	for {
		select {
		case <-p.ctx.Done():
			return
		case msg := <-p.out:
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			err := p.conn.Write(ctx, websocket.MessageText, msg)
			cancel()
			if err != nil {
				p.close(websocket.StatusInternalError, "write failed")
				return
			}
		}
	}
}

// heartbeat pings every interval and drops the peer once nothing has been
// heard for the timeout (design D2).
func (p *peer) heartbeat() {
	defer p.loops.Done()
	t := time.NewTicker(p.s.opts.HeartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-p.ctx.Done():
			return
		case <-t.C:
		}
		deadline := time.Unix(0, p.alive.Load()).Add(p.s.opts.HeartbeatTimeout)
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		err := p.conn.Ping(ctx)
		cancel()
		if err != nil {
			p.close(websocket.StatusGoingAway, "heartbeat timeout")
			return
		}
		p.touch()
	}
}

// sendEnvelope encodes env and queues it.
func (p *peer) sendEnvelope(env protocol.Envelope) {
	raw, err := protocol.Encode(env)
	if err != nil {
		p.s.log.Error("encode envelope", "method", env.Method, "err", err)
		return
	}
	p.send(raw)
}

// readLoop calls handle for each text message until the connection ends.
// Its reads are not bound to the peer context, for the same reason as in
// writeLoop; serve closing the connection ends them.
func (p *peer) readLoop(handle func([]byte)) {
	defer p.close(websocket.StatusNormalClosure, "")
	for {
		typ, data, err := p.conn.Read(context.Background())
		if err != nil {
			return
		}
		p.touch()
		if typ == websocket.MessageText {
			handle(data)
		}
	}
}
