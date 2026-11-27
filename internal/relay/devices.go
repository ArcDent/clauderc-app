package relay

import (
	"net/http"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
)

func (s *Server) handleListDevices(w http.ResponseWriter, r *http.Request) {
	resp := protocol.DevicesListResponse{Devices: []protocol.Device{}}
	for _, d := range s.opts.Store.Devices() {
		resp.Devices = append(resp.Devices, protocol.Device{ID: d.ID, Name: d.Name, CreatedAt: d.CreatedAt})
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleRevokeDevice deletes the device's token and closes its connections.
func (s *Server) handleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	removed, err := s.opts.Store.RemoveDevice(id)
	if err != nil {
		s.log.Error("remove device", "device", id, "err", err)
		writeError(w, http.StatusInternalServerError, protocol.CodeInternal, "删除凭证失败")
		return
	}
	if !removed {
		writeError(w, http.StatusNotFound, protocol.CodeDeviceNotFound, "没有这台设备")
		return
	}
	s.mu.Lock()
	for _, a := range s.apps {
		if a.device.ID == id {
			a.peer.close(websocket.StatusPolicyViolation, "device revoked")
		}
	}
	s.mu.Unlock()
	s.log.Info("device revoked", "device", id)
	w.WriteHeader(http.StatusNoContent)
}
