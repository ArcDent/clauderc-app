package relay

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"time"

	"clauderc/internal/protocol"
	"clauderc/internal/relay/store"
)

// requireEnrollKey wraps an admin handler with enroll key authentication.
func (s *Server) requireEnrollKey(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.isEnrollKey(bearer(r)) {
			writeError(w, http.StatusUnauthorized, protocol.CodeUnauthenticated, "注册密钥缺失或错误")
			return
		}
		h(w, r)
	}
}

func (s *Server) handleCreatePairCode(w http.ResponseWriter, r *http.Request) {
	now := s.opts.Now()
	code := randomHex(16)
	expires := now.Add(s.opts.PairCodeTTL)
	s.pairMu.Lock()
	// Expired codes stay a while so a late scan reads "expired", not "invalid".
	for c, exp := range s.pairCodes {
		if now.Sub(exp) > s.opts.PairCodeTTL {
			delete(s.pairCodes, c)
		}
	}
	for c, at := range s.pairUsed {
		if now.Sub(at) > s.opts.PairCodeTTL {
			delete(s.pairUsed, c)
		}
	}
	s.pairCodes[code] = expires
	s.pairMu.Unlock()
	writeJSON(w, http.StatusOK, protocol.PairCodeResponse{
		Code:      code,
		ExpiresAt: wireTime(expires),
		URI:       "clauderc://pair?relay=" + s.opts.PublicURL + "&code=" + code,
	})
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	var req protocol.PairRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil ||
		req.Code == "" || strings.TrimSpace(req.DeviceName) == "" {
		writeError(w, http.StatusBadRequest, protocol.CodeInvalidParams, "请求格式错误")
		return
	}
	now := s.opts.Now()
	expires, status, e := s.redeemPairCode(s.clientIP(r), req.Code, now)
	if e != nil {
		writeJSON(w, status, e)
		return
	}

	token := make([]byte, 32)
	rand.Read(token)
	dev := store.Device{ID: "d-" + randomHex(4), Name: strings.TrimSpace(req.DeviceName), CreatedAt: wireTime(now)}
	tok := base64.RawURLEncoding.EncodeToString(token)
	if err := s.opts.Store.AddDevice(dev, tok); err != nil {
		s.restorePairCode(req.Code, expires)
		s.log.Error("add device", "err", err)
		writeError(w, http.StatusInternalServerError, protocol.CodeInternal, "保存凭证失败")
		return
	}
	s.log.Info("device paired", "device", dev.ID)
	writeJSON(w, http.StatusOK, protocol.PairResponse{DeviceID: dev.ID, Token: tok})
}

// redeemPairCode checks the rate limit, looks up code and records a failure
// in one critical section, so concurrent attempts from one source cannot
// get past the limit. On success the code is used up, its expiry is
// returned for restorePairCode and the error is nil.
func (s *Server) redeemPairCode(source, code string, now time.Time) (time.Time, int, *protocol.Error) {
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	s.forgetPairFailures(source, now)
	if len(s.pairFailures[source]) >= s.opts.PairFailureLimit {
		return time.Time{}, http.StatusTooManyRequests, &protocol.Error{Code: protocol.CodeRateLimited, Message: "尝试次数过多，请稍后再试"}
	}
	expires, found := s.pairCodes[code]
	if found && !now.After(expires) {
		delete(s.pairCodes, code)
		s.pairUsed[code] = now
		return expires, http.StatusOK, nil
	}
	s.pairFailures[source] = append(s.pairFailures[source], now)
	if at, used := s.pairUsed[code]; used && now.Sub(at) <= s.opts.PairCodeTTL {
		return time.Time{}, http.StatusConflict, &protocol.Error{Code: protocol.CodePairCodeUsed, Message: "这个二维码已经配对过，请在目标机上重新生成"}
	}
	if !found {
		return time.Time{}, http.StatusNotFound, &protocol.Error{Code: protocol.CodePairCodeInvalid, Message: "配对码无效或已使用"}
	}
	return time.Time{}, http.StatusGone, &protocol.Error{Code: protocol.CodePairCodeExpired, Message: "配对码已过期"}
}

// restorePairCode puts back a code redeemed by a pairing that then failed,
// so the phone can scan the same QR code again.
func (s *Server) restorePairCode(code string, expires time.Time) {
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	delete(s.pairUsed, code)
	s.pairCodes[code] = expires
}

// forgetPairFailures drops failures older than the window, for source on
// every call and for all sources once per window, so addresses that never
// come back do not pile up. The caller holds pairMu.
func (s *Server) forgetPairFailures(source string, now time.Time) {
	prune := func(src string) {
		recent := s.pairFailures[src][:0]
		for _, at := range s.pairFailures[src] {
			if now.Sub(at) < s.opts.PairFailureWindow {
				recent = append(recent, at)
			}
		}
		if len(recent) == 0 {
			delete(s.pairFailures, src)
		} else {
			s.pairFailures[src] = recent
		}
	}
	if now.Sub(s.pairSweptAt) < s.opts.PairFailureWindow {
		prune(source)
		return
	}
	for src := range s.pairFailures {
		prune(src)
	}
	s.pairSweptAt = now
}

// clientIP is the rate limiting source. X-Forwarded-For is trusted only
// from a loopback peer, i.e. the local reverse proxy, or from a peer in
// TrustedProxies. Its entries are then read from the right, skipping those
// in TrustedProxies: the first other one is the address the outermost
// trusted proxy saw, and entries a client wrote further left are ignored.
func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if peer, err := netip.ParseAddr(host); err != nil || !peer.IsLoopback() && !s.trustedProxy(peer) {
		return host
	}
	// Proxies may add their own header line instead of extending the first.
	parts := strings.Split(strings.Join(r.Header.Values("X-Forwarded-For"), ","), ",")
	for i := len(parts) - 1; i >= 0; i-- {
		entry := strings.TrimSpace(parts[i])
		if entry == "" {
			break
		}
		if ip, err := netip.ParseAddr(entry); err == nil && s.trustedProxy(ip) {
			continue
		}
		return entry
	}
	return host
}

func (s *Server) trustedProxy(ip netip.Addr) bool {
	ip = ip.Unmap()
	for _, p := range s.opts.TrustedProxies {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}
