package agent_test

import (
	"encoding/json"
	"testing"

	"clauderc/internal/agent"
	"clauderc/internal/protocol"
)

func TestPushEvent(t *testing.T) {
	var got []protocol.Envelope
	p := agent.PusherFunc(func(e protocol.Envelope) { got = append(got, e) })

	if err := agent.PushEvent(p, "c1", protocol.EventSessionsChanged, map[string]int{"n": 1}); err != nil {
		t.Fatalf("PushEvent: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("pushed %d events, want 1", len(got))
	}
	e := got[0]
	if e.V != protocol.Version || e.Kind != protocol.KindEvent || e.Method != protocol.EventSessionsChanged || e.Client != "c1" || string(e.Body) != `{"n":1}` {
		t.Fatalf("event = %+v body %s", e, e.Body)
	}

	// A body that cannot be encoded is reported and nothing is pushed.
	if err := agent.PushEvent(p, "c1", protocol.EventSessionsChanged, func() {}); err == nil {
		t.Fatal("unencodable body accepted")
	}
	if len(got) != 1 {
		t.Fatalf("pushed %d events after a failed encode, want 1", len(got))
	}
	if _, err := protocol.Encode(e); err != nil {
		t.Fatalf("pushed event does not encode: %v", err)
	}
	var body map[string]int
	if err := json.Unmarshal(e.Body, &body); err != nil || body["n"] != 1 {
		t.Fatalf("body %s", e.Body)
	}
}
