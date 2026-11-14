package relay

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
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
	if status, e := s.redeemPairCode(clientIP(r), req.Code, now); e != nil {
		writeJSON(w, status, e)
		return
	}

	token := make([]byte, 32)
	rand.Read(token)
	dev := store.Device{ID: "d-" + randomHex(4), Name: strings.TrimSpace(req.DeviceName), CreatedAt: wireTime(now)}
	tok := base64.RawURLEncoding.EncodeToString(token)
	if err := s.opts.Store.AddDevice(dev, tok); err != nil {
		s.log.Error("add device", "err", err)
		writeError(w, http.StatusInternalServerError, protocol.CodeInternal, "保存凭证失败")
		return
	}
	s.log.Info("device paired", "device", dev.ID)
	writeJSON(w, http.StatusOK, protocol.PairResponse{DeviceID: dev.ID, Token: tok})
}

// redeemPairCode checks the rate limit, looks up code and records a failure
// in one critical section, so concurrent attempts from one source cannot
// get past the limit. On success the code is used up and the error is nil.
func (s *Server) redeemPairCode(source, code string, now time.Time) (int, *protocol.Error) {
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	s.forgetPairFailures(source, now)
	if len(s.pairFailures[source]) >= s.opts.PairFailureLimit {
		return http.StatusTooManyRequests, &protocol.Error{Code: protocol.CodeRateLimited, Message: "尝试次数过多，请稍后再试"}
	}
	expires, found := s.pairCodes[code]
	if found && !now.After(expires) {
		delete(s.pairCodes, code)
		return http.StatusOK, nil
	}
	s.pairFailures[source] = append(s.pairFailures[source], now)
	if !found {
		return http.StatusNotFound, &protocol.Error{Code: protocol.CodePairCodeInvalid, Message: "配对码无效或已使用"}
	}
	return http.StatusGone, &protocol.Error{Code: protocol.CodePairCodeExpired, Message: "配对码已过期"}
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
// from a loopback peer, i.e. the local reverse proxy, and then its last
// entry is the address the proxy saw.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			if last := strings.TrimSpace(parts[len(parts)-1]); last != "" {
				return last
			}
		}
	}
	return host
}
