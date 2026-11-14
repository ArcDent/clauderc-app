package pairing

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"rsc.io/qr"

	"clauderc/internal/protocol"
	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

const enrollKey = "enroll-key-for-tests"

func startRelay(t *testing.T) *httptest.Server {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	srv := relay.NewServer(relay.Options{EnrollKey: enrollKey, PublicURL: "wss://relay.example.com", Store: st})
	hs := httptest.NewTLSServer(srv)
	t.Cleanup(func() {
		hs.Close()
		srv.Close()
	})
	return hs
}

// readQR turns WriteQR output back into modules, dark = true, dropping
// the quiet zone. Every line must carry the fixed black-on-white colors
// and reset them at its end.
func readQR(t *testing.T, out string, size int) [][]bool {
	t.Helper()
	var rows [][]bool
	for i, line := range strings.Split(strings.TrimSuffix(out, "\n"), "\n") {
		body, ok := strings.CutPrefix(line, "\x1b[38;5;16;48;5;231m")
		if ok {
			body, ok = strings.CutSuffix(body, "\x1b[0m")
		}
		if !ok {
			t.Fatalf("line %d is not wrapped in the black-on-white colors: %q", i, line)
		}
		top, bottom := []bool{}, []bool{}
		for _, r := range body {
			top = append(top, r == '█' || r == '▀')
			bottom = append(bottom, r == '█' || r == '▄')
		}
		rows = append(rows, top, bottom)
	}
	grid := make([][]bool, size)
	for y := range grid {
		grid[y] = rows[y+2][2 : size+2]
	}
	return grid
}

func TestWriteQR(t *testing.T) {
	uri := "clauderc://pair?relay=wss%3A%2F%2Frelay.example.com&code=0123456789abcdef0123456789abcdef"
	var b bytes.Buffer
	if err := WriteQR(&b, uri); err != nil {
		t.Fatal(err)
	}
	want, err := qr.Encode(uri, qr.M)
	if err != nil {
		t.Fatal(err)
	}
	got := readQR(t, b.String(), want.Size)
	for y := 0; y < want.Size; y++ {
		for x := 0; x < want.Size; x++ {
			if got[y][x] != want.Black(x, y) {
				t.Fatalf("module (%d,%d) differs from the QR code of the URI", x, y)
			}
		}
	}
}

func TestPairDevicesRevoke(t *testing.T) {
	hs := startRelay(t)
	ctx := context.Background()
	c := Client{RelayURL: "wss" + strings.TrimPrefix(hs.URL, "https"), EnrollKey: enrollKey, HTTPClient: hs.Client()}

	code, err := c.PairCode(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(code.URI, "clauderc://pair?relay=") || !strings.HasSuffix(code.URI, "&code="+code.Code) {
		t.Errorf("uri = %q", code.URI)
	}
	var out bytes.Buffer
	if err := PrintPair(&out, code); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), code.ExpiresAt.Local().Format("15:04:05")+" 前有效") || !strings.Contains(out.String(), code.URI) {
		t.Errorf("pair output lacks the deadline or the URI:\n%s", out.String())
	}

	// A phone pairs with the code and connects.
	body, _ := json.Marshal(protocol.PairRequest{Code: code.Code, DeviceName: "Pixel"})
	res, err := hs.Client().Post(hs.URL+protocol.PathPair, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	var pair protocol.PairResponse
	if err := json.NewDecoder(res.Body).Decode(&pair); err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	ws, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(hs.URL, "https")+protocol.PathAppWS, &websocket.DialOptions{
		HTTPClient: hs.Client(),
		HTTPHeader: http.Header{"Authorization": {"Bearer " + pair.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()

	devices, err := c.Devices(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].ID != pair.DeviceID || devices[0].Name != "Pixel" {
		t.Fatalf("devices = %+v", devices)
	}
	out.Reset()
	if err := PrintDevices(&out, devices); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), pair.DeviceID) || !strings.Contains(out.String(), "Pixel") {
		t.Errorf("devices output = %q", out.String())
	}

	if err := c.Revoke(ctx, pair.DeviceID); err != nil {
		t.Fatal(err)
	}
	readCtx, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	if _, _, err := ws.Read(readCtx); err == nil || readCtx.Err() != nil {
		t.Errorf("phone connection still open after revoke (read: %v)", err)
	}
	if err := c.Revoke(ctx, pair.DeviceID); err == nil {
		t.Error("revoking twice succeeded")
	}
	if _, err := (Client{RelayURL: c.RelayURL, EnrollKey: "wrong", HTTPClient: hs.Client()}).Devices(ctx); err == nil {
		t.Error("a wrong enroll key listed devices")
	}
	if _, err := (Client{RelayURL: "http://relay.example.com", EnrollKey: enrollKey}).PairCode(ctx); err == nil {
		t.Error("a plain http relay address was used")
	}
}
