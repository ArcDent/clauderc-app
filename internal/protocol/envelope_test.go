package protocol_test

import (
	"encoding/json"
	"reflect"
	"testing"

	"clauderc/internal/protocol"
)

// Envelope.Method must be the named Method type, not a plain string.
var _ protocol.Method = protocol.Envelope{}.Method

// assertSameJSON fails unless a and b hold the same JSON value, ignoring
// whitespace and object key order.
func assertSameJSON(t *testing.T, got, want []byte) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("got is not JSON: %v\n%s", err, got)
	}
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatalf("want is not JSON: %v\n%s", err, want)
	}
	if !reflect.DeepEqual(g, w) {
		t.Fatalf("JSON differs\n got: %s\nwant: %s", got, want)
	}
}

func TestEnvelopeRoundTrip(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want protocol.Envelope
	}{
		{
			name: "request",
			raw:  `{"v":1,"kind":"req","id":"r1","method":"chat.send","machine":"m1","client":"c1","body":{"sessionId":"s1","text":"hi"}}`,
			want: protocol.Envelope{
				V: 1, Kind: protocol.KindRequest, ID: "r1", Method: protocol.MethodChatSend,
				Machine: "m1", Client: "c1",
				Body: json.RawMessage(`{"sessionId":"s1","text":"hi"}`),
			},
		},
		{
			name: "error response",
			raw:  `{"v":1,"kind":"res","id":"r1","machine":"m1","client":"c1","error":{"code":"machineOffline","message":"machine m1 is offline"}}`,
			want: protocol.Envelope{
				V: 1, Kind: protocol.KindResponse, ID: "r1", Machine: "m1", Client: "c1",
				Error: &protocol.Error{Code: protocol.CodeMachineOffline, Message: "machine m1 is offline"},
			},
		},
		{
			name: "event",
			raw:  `{"v":1,"kind":"evt","method":"client.gone","client":"c1","body":{}}`,
			want: protocol.Envelope{
				V: 1, Kind: protocol.KindEvent, Method: protocol.EventClientGone, Client: "c1",
				Body: json.RawMessage(`{}`),
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := protocol.Decode([]byte(tc.raw))
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("Decode mismatch\n got: %+v\nwant: %+v", got, tc.want)
			}
			out, err := protocol.Encode(got)
			if err != nil {
				t.Fatalf("Encode: %v", err)
			}
			assertSameJSON(t, out, []byte(tc.raw))
		})
	}
}

func TestDecodeRejectsInvalidEnvelope(t *testing.T) {
	cases := map[string]string{
		"version 2":       `{"v":2,"kind":"req","id":"r1","method":"chat.send"}`,
		"version missing": `{"kind":"req","id":"r1","method":"chat.send"}`,
		"unknown kind":    `{"v":1,"kind":"call","id":"r1","method":"chat.send"}`,
		"not json":        `{"v":1,`,
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := protocol.Decode([]byte(raw)); err == nil {
				t.Fatalf("Decode(%s) returned no error", raw)
			}
		})
	}
}

func TestEncodeRejectsWrongVersion(t *testing.T) {
	_, err := protocol.Encode(protocol.Envelope{V: 2, Kind: protocol.KindEvent, Method: protocol.EventClientGone})
	if err == nil {
		t.Fatal("Encode with v=2 returned no error")
	}
}
