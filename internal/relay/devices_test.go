package relay_test

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

func TestDevicesNeedEnrollKey(t *testing.T) {
	r := startRelay(t, nil)
	if status := r.do(t, "GET", "/api/admin/devices", "", "", nil, nil); status != 401 {
		t.Fatalf("list without key: %d, want 401", status)
	}
	r.pairDevice(t, "d-1", "tok-1")
	if status := r.do(t, "DELETE", "/api/admin/devices/d-1", "tok-1", "", nil, nil); status != 401 {
		t.Fatalf("revoke with device token: %d, want 401", status)
	}
	if len(r.store.Devices()) != 1 {
		t.Fatal("device removed without enroll key")
	}
}

func TestDevicesList(t *testing.T) {
	r := startRelay(t, nil)
	var raw json.RawMessage
	if status := r.do(t, "GET", "/api/admin/devices", enrollKey, "", nil, &raw); status != 200 {
		t.Fatalf("status %d", status)
	}
	assertJSON(t, raw, `{"devices":[]}`)

	created := time.Date(2026, 11, 12, 9, 31, 20, 0, time.UTC)
	if err := r.store.AddDevice(store.Device{ID: "d-1", Name: "Pixel 9", CreatedAt: created}, "tok-1"); err != nil {
		t.Fatal(err)
	}
	if status := r.do(t, "GET", "/api/admin/devices", enrollKey, "", nil, &raw); status != 200 {
		t.Fatalf("status %d", status)
	}
	assertJSON(t, raw, `{"devices":[{"id":"d-1","name":"Pixel 9","createdAt":"2026-11-12T09:31:20Z"}]}`)
}

func TestRevokeClosesConnectionsAndRejectsToken(t *testing.T) {
	r := startRelay(t, nil)
	r.pairDevice(t, "d-1", "tok-1")
	r.pairDevice(t, "d-2", "tok-2")
	p1a := r.phone(t, "tok-1")
	p1b := r.phone(t, "tok-1")
	p2 := r.phone(t, "tok-2")

	if status := r.do(t, "DELETE", "/api/admin/devices/d-1", enrollKey, "", nil, nil); status != 204 {
		t.Fatalf("revoke status %d, want 204", status)
	}
	p1a.waitClosed(time.Second)
	p1b.waitClosed(time.Second)
	if _, status := r.dial(t, "/ws/app", "tok-1"); status != http.StatusUnauthorized {
		t.Fatalf("old token: status %d, want 401", status)
	}

	// The other phone is unaffected.
	listMachines(t, p2)
	devs := r.store.Devices()
	if len(devs) != 1 || devs[0].ID != "d-2" {
		t.Fatalf("devices = %+v", devs)
	}
}

func TestRevokeUnknownDevice(t *testing.T) {
	r := startRelay(t, nil)
	var e protocol.Error
	if status := r.do(t, "DELETE", "/api/admin/devices/d-404", enrollKey, "", nil, &e); status != 404 || e.Code != "deviceNotFound" {
		t.Fatalf("= %d %+v, want 404 deviceNotFound", status, e)
	}
}

// rawClient finishes the WebSocket handshake by hand and afterwards never
// answers pings or close frames, like a phone that has frozen.
type rawClient struct {
	net.Conn
	r *bufio.Reader
}

func (r *testRelay) rawPhone(t *testing.T, token string) *rawClient {
	t.Helper()
	addr := strings.TrimPrefix(r.http.URL, "http://")
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	fmt.Fprintf(c, "GET /ws/app HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer %s\r\n\r\n", addr, token)
	rc := &rawClient{Conn: c, r: bufio.NewReader(c)}
	resp, err := http.ReadResponse(rc.r, nil)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("handshake status %d", resp.StatusCode)
	}
	return rc
}

// writeText sends one masked text frame shorter than 126 bytes.
func (c *rawClient) writeText(t *testing.T, payload []byte) {
	t.Helper()
	mask := [4]byte{1, 2, 3, 4}
	frame := append([]byte{0x81, 0x80 | byte(len(payload))}, mask[:]...)
	for i, b := range payload {
		frame = append(frame, b^mask[i%4])
	}
	if _, err := c.Write(frame); err != nil {
		t.Fatal(err)
	}
}

// readFrame returns the opcode and payload of the next frame.
func (c *rawClient) readFrame(t *testing.T) (byte, []byte) {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var h [2]byte
	if _, err := io.ReadFull(c.r, h[:]); err != nil {
		t.Fatal(err)
	}
	n := int(h[1] & 0x7f)
	if n == 126 {
		var ext [2]byte
		if _, err := io.ReadFull(c.r, ext[:]); err != nil {
			t.Fatal(err)
		}
		n = int(ext[0])<<8 | int(ext[1])
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(c.r, payload); err != nil {
		t.Fatal(err)
	}
	return h[0] & 0x0f, payload
}

// A phone that never answers the close frame must still lose its TCP
// connection within a second of being revoked.
func TestRevokeClosesUnresponsivePhoneWithinOneSecond(t *testing.T) {
	r := startRelay(t, func(o *relay.Options) {
		o.HeartbeatInterval = 10 * time.Second
		o.HeartbeatTimeout = 30 * time.Second
	})
	r.pairDevice(t, "d-1", "tok-1")
	c := r.rawPhone(t, "tok-1")
	// A machines.list round trip proves the relay has registered the phone.
	c.writeText(t, []byte(`{"v":1,"kind":"req","id":"r-1","method":"machines.list","body":{}}`))
	if op, _ := c.readFrame(t); op != 0x1 {
		t.Fatalf("opcode %#x, want text response", op)
	}

	start := time.Now()
	if status := r.do(t, "DELETE", "/api/admin/devices/d-1", enrollKey, "", nil, nil); status != 204 {
		t.Fatalf("revoke status %d", status)
	}
	op, payload := c.readFrame(t)
	if op != 0x8 || len(payload) < 2 || int(payload[0])<<8|int(payload[1]) != int(websocket.StatusPolicyViolation) {
		t.Fatalf("frame op %#x payload %q, want close frame with policy violation", op, payload)
	}
	c.SetReadDeadline(start.Add(time.Second))
	if _, err := io.Copy(io.Discard, c.r); err != nil {
		t.Fatalf("TCP connection still open 1s after revoke: %v", err)
	}
}
