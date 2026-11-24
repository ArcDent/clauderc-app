package relay

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

type inflightKey struct {
	client string
	id     string
}

type inflightReq struct {
	method protocol.Method
}

type agentConn struct {
	peer    *peer
	machine store.Machine
	connID  string

	reqMu    sync.Mutex
	inflight map[inflightKey]inflightReq
	dropped  bool
}

func (a *agentConn) trackRequest(client, id string, method protocol.Method) bool {
	if id == "" {
		return true
	}
	a.reqMu.Lock()
	defer a.reqMu.Unlock()
	if a.dropped {
		return false
	}
	a.inflight[inflightKey{client: client, id: id}] = inflightReq{method: method}
	return true
}

// untrackRequest removes a request that could not be queued. It returns true
// when the entry was still present so the caller should reply machineBusy;
// false means failInflight already answered it with machineOffline.
func (a *agentConn) untrackRequest(client, id string) bool {
	if id == "" {
		return true
	}
	a.reqMu.Lock()
	defer a.reqMu.Unlock()
	if a.dropped {
		return false
	}
	key := inflightKey{client: client, id: id}
	if _, ok := a.inflight[key]; !ok {
		return false
	}
	delete(a.inflight, key)
	return true
}

func (a *agentConn) dropClient(client string) {
	a.reqMu.Lock()
	defer a.reqMu.Unlock()
	for k := range a.inflight {
		if k.client == client {
			delete(a.inflight, k)
		}
	}
}

func (s *Server) handleAgent(w http.ResponseWriter, r *http.Request) {
	if !s.isEnrollKey(bearer(r)) {
		writeError(w, http.StatusUnauthorized, protocol.CodeUnauthenticated, "注册密钥缺失或错误")
		return
	}
	q := r.URL.Query()
	m := store.Machine{
		ID:            q.Get(protocol.AgentParamID),
		Name:          q.Get(protocol.AgentParamName),
		AgentVersion:  q.Get(protocol.AgentParamAgentVersion),
		ClaudeVersion: q.Get(protocol.AgentParamClaudeVersion),
	}
	if m.ID == "" {
		writeError(w, http.StatusBadRequest, protocol.CodeInvalidParams, "缺少目标机 id")
		return
	}
	if m.Name == "" {
		m.Name = m.ID
	}
	if !s.track() {
		refuseClosing(w)
		return
	}
	defer s.conns.Done()
	a := s.admitAgent(w, r, m)
	if a == nil {
		return
	}
	a.peer.serve(func(data []byte) { s.fromAgent(a, data) })
	s.agentOffline(a)
}

// admitAgent upgrades the connection and registers it as machine m,
// unless an agent with the same id is connected and answers a ping; it
// returns nil when it answered the request instead. Admissions of one id
// take turns, so two connections arriving together cannot both find no
// agent, or the same dead one, and the later replace the earlier while
// it is alive.
func (s *Server) admitAgent(w http.ResponseWriter, r *http.Request, m store.Machine) *agentConn {
	mu, _ := s.admitting.LoadOrStore(m.ID, new(sync.Mutex))
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()
	if s.agentAnswers(m.ID) {
		s.log.Warn("duplicate agent refused", "machine", m.ID)
		writeError(w, http.StatusConflict, protocol.CodeMachineInUse, "这台目标机已有连接在线")
		return nil
	}
	p := s.accept(w, r)
	if p == nil {
		return nil
	}
	a := &agentConn{
		peer:     p,
		machine:  m,
		connID:   "g-" + randomHex(8),
		inflight: map[inflightKey]inflightReq{},
	}
	s.agentOnline(a)
	return a
}

// probeTimeout bounds the ping that decides whether the connected agent
// with the same id is still alive.
const probeTimeout = 5 * time.Second

// agentAnswers reports whether an agent with this id is connected and
// answers a ping. One that does not was left behind by a network change
// or a killed process and may be replaced by the new connection. The ping
// is bounded by the server, not by the new request: that caller giving up
// must not make a live agent look dead.
func (s *Server) agentAnswers(id string) bool {
	s.mu.Lock()
	old := s.agents[id]
	s.mu.Unlock()
	if old == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(s.base, min(probeTimeout, s.opts.HeartbeatTimeout))
	defer cancel()
	return old.peer.conn.Ping(ctx) == nil
}

// fromAgent routes a response or event back to the phone named by Client.
func (s *Server) fromAgent(a *agentConn, data []byte) {
	env, err := protocol.Decode(data)
	if err != nil || env.Kind == protocol.KindRequest {
		return
	}
	a.reqMu.Lock()
	dropped := a.dropped
	if !dropped && env.Kind == protocol.KindResponse && env.ID != "" {
		delete(a.inflight, inflightKey{client: env.Client, id: env.ID})
	}
	a.reqMu.Unlock()
	if dropped {
		return
	}
	s.mu.Lock()
	app := s.apps[env.Client]
	s.mu.Unlock()
	if app == nil {
		return
	}
	env.Machine = a.machine.ID
	app.peer.sendEnvelope(env)
}

// failInflight answers every request still waiting on a with machineOffline.
func (s *Server) failInflight(a *agentConn) {
	a.reqMu.Lock()
	if a.dropped {
		a.reqMu.Unlock()
		return
	}
	a.dropped = true
	pending := a.inflight
	a.inflight = nil
	a.reqMu.Unlock()
	if len(pending) == 0 {
		return
	}
	s.mu.Lock()
	targets := make(map[string]*appConn, len(pending))
	for k := range pending {
		if app := s.apps[k.client]; app != nil {
			targets[k.client] = app
		}
	}
	s.mu.Unlock()
	for k, req := range pending {
		app := targets[k.client]
		if app == nil {
			continue
		}
		app.peer.sendEnvelope(protocol.Envelope{
			V: protocol.Version, Kind: protocol.KindResponse, ID: k.id, Method: req.method,
			Machine: a.machine.ID,
			Error:   &protocol.Error{Code: protocol.CodeMachineOffline, Message: "目标机离线"},
		})
	}
}

func (s *Server) agentOnline(a *agentConn) {
	s.statusMu.Lock()
	defer s.statusMu.Unlock()
	a.machine.LastSeenAt = a.peer.seen()
	s.mu.Lock()
	old := s.agents[a.machine.ID]
	s.agents[a.machine.ID] = a
	s.mu.Unlock()
	if old != nil {
		old.peer.close(websocket.StatusPolicyViolation, "replaced by a newer connection")
		s.failInflight(old)
	}
	if err := s.opts.Store.SaveMachine(a.machine); err != nil {
		s.log.Error("save machine", "machine", a.machine.ID, "err", err)
	}
	s.broadcastMachine(machineInfo(a.machine, true, a.connID))
	s.log.Info("machine online", "machine", a.machine.ID, "connId", a.connID)
}

// agentOffline runs under statusMu like agentOnline, so a stale offline
// can never be written or broadcast after a reconnect's online.
func (s *Server) agentOffline(a *agentConn) {
	s.statusMu.Lock()
	defer s.statusMu.Unlock()
	s.mu.Lock()
	if s.agents[a.machine.ID] != a {
		// Replaced by a newer connection of the same machine.
		s.mu.Unlock()
		return
	}
	delete(s.agents, a.machine.ID)
	a.machine.LastSeenAt = a.peer.seen()
	s.mu.Unlock()
	s.failInflight(a)
	if err := s.opts.Store.SaveMachine(a.machine); err != nil {
		s.log.Error("save machine", "machine", a.machine.ID, "err", err)
	}
	s.broadcastMachine(machineInfo(a.machine, false, ""))
	s.log.Info("machine offline", "machine", a.machine.ID)
}
