package relay

import (
	"net/http"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

type agentConn struct {
	peer    *peer
	machine store.Machine
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
	p := s.accept(w, r)
	if p == nil {
		return
	}
	a := &agentConn{peer: p, machine: m}
	s.agentOnline(a)
	p.serve(func(data []byte) { s.fromAgent(a, data) })
	s.agentOffline(a)
}

// fromAgent routes a response or event back to the phone named by Client.
func (s *Server) fromAgent(a *agentConn, data []byte) {
	env, err := protocol.Decode(data)
	if err != nil || env.Kind == protocol.KindRequest {
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
	}
	if err := s.opts.Store.SaveMachine(a.machine); err != nil {
		s.log.Error("save machine", "machine", a.machine.ID, "err", err)
	}
	s.broadcastMachine(machineInfo(a.machine, true))
	s.log.Info("machine online", "machine", a.machine.ID)
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
	if err := s.opts.Store.SaveMachine(a.machine); err != nil {
		s.log.Error("save machine", "machine", a.machine.ID, "err", err)
	}
	s.broadcastMachine(machineInfo(a.machine, false))
	s.log.Info("machine offline", "machine", a.machine.ID)
}
