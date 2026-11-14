package relay_test

import (
	"encoding/json"
	"net/http"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"

	"clauderc/internal/relay"
)

// Times leave the relay in UTC whole seconds like the protocol samples,
// whatever the server's zone and clock precision.
func TestOutputTimesAreUTCWholeSeconds(t *testing.T) {
	zone := time.FixedZone("UTC-7", -7*3600)
	clock := &fakeClock{now: time.Date(2026, 11, 12, 2, 30, 0, 906015000, zone)}
	r := startRelay(t, func(o *relay.Options) { o.Now = clock.Now })

	var pc map[string]any
	if status := r.do(t, "POST", "/api/admin/pair-codes", enrollKey, "", nil, &pc); status != 200 {
		t.Fatalf("pair-codes status %d", status)
	}
	if pc["expiresAt"] != "2026-11-12T09:35:00Z" {
		t.Fatalf("expiresAt = %v", pc["expiresAt"])
	}
	status, paired, _ := r.pair(t, pc["code"].(string), "203.0.113.5")
	if status != 200 {
		t.Fatalf("pair status %d", status)
	}
	var raw json.RawMessage
	r.do(t, "GET", "/api/admin/devices", enrollKey, "", nil, &raw)
	if !strings.Contains(string(raw), `"createdAt":"2026-11-12T09:30:00Z"`) {
		t.Fatalf("devices = %s", raw)
	}

	p := r.phone(t, paired.Token)
	c, st := r.dial(t, agentQuery, enrollKey)
	if st != http.StatusSwitchingProtocols {
		t.Fatalf("agent status %d", st)
	}
	newClient(t, c)
	const seen = `"lastSeenAt":"2026-11-12T09:30:00Z"`
	if env := p.next(); !strings.Contains(string(env.Body), seen) {
		t.Fatalf("machine.status body = %s", env.Body)
	}
	if res, _ := listMachines(t, p); !strings.Contains(string(res.Body), seen) {
		t.Fatalf("machines.list body = %s", res.Body)
	}

	state, err := os.ReadFile(r.path)
	if err != nil {
		t.Fatal(err)
	}
	times := regexp.MustCompile(`"(createdAt|lastSeenAt)": "([^"]*)"`).FindAllStringSubmatch(string(state), -1)
	if len(times) != 2 {
		t.Fatalf("state file times = %v", times)
	}
	for _, m := range times {
		if m[2] != "2026-11-12T09:30:00Z" {
			t.Errorf("state file %s = %s", m[1], m[2])
		}
	}
}
