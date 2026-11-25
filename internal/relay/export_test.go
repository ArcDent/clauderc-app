package relay

// SetListedHook makes fn run between the machines.list snapshot and the
// queuing of its reply.
func (s *Server) SetListedHook(fn func()) { s.listedHook.Store(&fn) }
