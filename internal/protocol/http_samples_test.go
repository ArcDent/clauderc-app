package protocol_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"testing"

	"clauderc/internal/protocol"
)

// HTTP samples live in testdata/http/ and hold one request or response
// body, not an envelope:
//
//	<endpoint>.req[.<variant>].json
//	<endpoint>.res.<HTTP status>[.<variant>].json
//
// Endpoints: pair = POST /api/pair, admin.pair-codes = POST
// /api/admin/pair-codes, admin.devices.list = GET /api/admin/devices,
// admin.devices.revoke = DELETE /api/admin/devices/{id} (204 on success),
// ws.app = the refused /ws/app upgrade, ws.agent = the refused /ws/agent
// upgrade.
// Every response with status >= 400 is an Error body.
var httpSampleName = regexp.MustCompile(`^(.+)\.(req|res\.([0-9]{3}))(\.[a-z0-9-]+)?\.json$`)

// httpBodies maps <endpoint>.req or <endpoint>.res.<2xx status> to the body type.
var httpBodies = map[string]func() any{
	"pair.req":                   func() any { return &protocol.PairRequest{} },
	"pair.res.200":               func() any { return &protocol.PairResponse{} },
	"admin.pair-codes.res.200":   func() any { return &protocol.PairCodeResponse{} },
	"admin.devices.list.res.200": func() any { return &protocol.DevicesListResponse{} },
}

// httpErrorStatus is the agreed HTTP status for each error code.
var httpErrorStatus = map[protocol.ErrorCode]int{
	protocol.CodeInvalidParams:   400,
	protocol.CodeUnauthenticated: 401,
	protocol.CodePairCodeInvalid: 404,
	protocol.CodePairCodeUsed:    409,
	protocol.CodePairCodeExpired: 410,
	protocol.CodeRateLimited:     429,
	protocol.CodeDeviceNotFound:  404,
	protocol.CodeMachineInUse:    409,
}

// Error samples the phone relies on.
var requiredHTTPSamples = []string{"pair.res.404", "pair.res.409", "pair.res.410", "pair.res.429", "ws.app.res.401"}

func TestHTTPSamplesRoundTrip(t *testing.T) {
	files, err := filepath.Glob(filepath.Join("testdata", "http", "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	covered := map[string]bool{}
	for _, file := range files {
		name := filepath.Base(file)
		t.Run(name, func(t *testing.T) {
			m := httpSampleName.FindStringSubmatch(name)
			if m == nil {
				t.Fatalf("file name does not match <endpoint>.req[.variant].json or <endpoint>.res.<status>[.variant].json")
			}
			key := m[1] + "." + m[2]
			covered[key] = true
			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			status, _ := strconv.Atoi(m[3])
			var body any
			if status >= 400 {
				body = &protocol.Error{}
			} else {
				newBody, ok := httpBodies[key]
				if !ok {
					t.Fatalf("no body type for %s", key)
				}
				body = newBody()
			}
			if err := decodeStrict(raw, body); err != nil {
				t.Fatalf("decode %T: %v", body, err)
			}
			if e, ok := body.(*protocol.Error); ok {
				want, known := httpErrorStatus[e.Code]
				if !known {
					t.Fatalf("error code %q has no agreed HTTP status", e.Code)
				}
				if want != status {
					t.Fatalf("error code %q goes with HTTP %d, file says %d", e.Code, want, status)
				}
			}
			out, err := json.Marshal(body)
			if err != nil {
				t.Fatal(err)
			}
			assertSameJSON(t, out, raw)
		})
	}
	for key := range httpBodies {
		if !covered[key] {
			t.Errorf("no sample in testdata/http for %s", key)
		}
	}
	for _, key := range requiredHTTPSamples {
		if !covered[key] {
			t.Errorf("no sample in testdata/http for %s", key)
		}
	}
}
