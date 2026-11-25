package relay

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

type appConn struct {
	peer   *peer
	id     string // client id put into forwarded envelopes
	device store.Device
	// machines this phone has sent requests to; they get client.gone.
	machines map[string]bool
}

func (s *Server) handleApp(w http.ResponseWriter, r *http.Request) {
	dev, ok := s.opts.Store.VerifyToken(bearer(r))
	if !ok {
		writeError(w, http.StatusUnauthorized, protocol.CodeUnauthenticated, "凭证无效或已吊销")
		return
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
	app := &appConn{peer: p, id: "c-" + randomHex(8), device: dev, machines: map[string]bool{}}
	s.mu.Lock()
	s.apps[app.id] = app
	s.mu.Unlock()
	// A revocation between the check above and registering would miss
	// this connection, so check again now that it is visible.
	if _, ok := s.opts.Store.VerifyToken(bearer(r)); !ok {
		p.close(websocket.StatusPolicyViolation, "device revoked")
	}
	s.log.Info("phone connected", "device", dev.ID, "client", app.id)

	p.serve(func(data []byte) { s.fromApp(app, data) })

	s.mu.Lock()
	delete(s.apps, app.id)
	var notify []*agentConn
	for id := range app.machines {
		if a := s.agents[id]; a != nil {
			notify = append(notify, a)
		}
	}
	s.mu.Unlock()
	for _, a := range notify {
		a.dropClient(app.id)
		a.peer.sendEnvelope(protocol.Envelope{
			V: protocol.Version, Kind: protocol.KindEvent, Method: protocol.EventClientGone,
			Client: app.id, Body: []byte(`{}`),
		})
	}
	s.log.Info("phone disconnected", "device", dev.ID, "client", app.id)
}

// fromApp forwards a phone request to its machine, adding Client.
func (s *Server) fromApp(app *appConn, data []byte) {
	env, err := protocol.Decode(data)
	if err != nil || env.Kind != protocol.KindRequest {
		return
	}
	if env.Method == protocol.MethodMachinesList {
		// Under statusMu, like machine.status events: a machine going
		// online or offline meanwhile has its event queued after this
		// reply, never before a reply that still lists the old state.
		s.statusMu.Lock()
		defer s.statusMu.Unlock()
		body, _ := json.Marshal(protocol.MachinesListResponse{Machines: s.Machines()})
		if hook := s.listedHook.Load(); hook != nil {
			(*hook)()
		}
		app.peer.sendEnvelope(protocol.Envelope{
			V: protocol.Version, Kind: protocol.KindResponse, ID: env.ID, Method: env.Method, Body: body,
		})
		return
	}
	s.mu.Lock()
	a := s.agents[env.Machine]
	if a != nil {
		app.machines[env.Machine] = true
	}
	s.mu.Unlock()
	if a == nil || !a.trackRequest(app.id, env.ID, env.Method) {
		app.peer.sendEnvelope(protocol.Envelope{
			V: protocol.Version, Kind: protocol.KindResponse, ID: env.ID, Method: env.Method,
			Machine: env.Machine,
			Error:   &protocol.Error{Code: protocol.CodeMachineOffline, Message: "目标机离线"},
		})
		return
	}
	env.Client = app.id
	// A machine that cannot keep up keeps its connection, which every
	// phone shares; only this request fails.
	if !a.peer.trySend(env) {
		if a.untrackRequest(app.id, env.ID) {
			app.peer.sendEnvelope(protocol.Envelope{
				V: protocol.Version, Kind: protocol.KindResponse, ID: env.ID, Method: env.Method,
				Machine: env.Machine,
				Error:   &protocol.Error{Code: protocol.CodeMachineBusy, Message: "目标机暂时忙不过来，请稍后重试"},
			})
		}
	}
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}
