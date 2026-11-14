package protocol_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clauderc/internal/protocol"
)

// Sample files are named <method>.<kind>[.<variant>].json and hold one full
// envelope. Responses carry "method" so the body type can be chosen.
func TestSamplesRoundTrip(t *testing.T) {
	types := map[string]func() any{}
	for _, tc := range bodyCases {
		types[tc.name+"."+string(tc.kind)] = tc.body
	}

	files, err := filepath.Glob(filepath.Join("testdata", "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	covered := map[string]bool{}
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			raw, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			env, err := protocol.Decode(raw)
			if err != nil {
				t.Fatalf("Decode: %v", err)
			}
			if env.Error != nil {
				if _, ok := errorCodes[env.Error.Code]; !ok {
					t.Fatalf("error code %q is not a defined constant", env.Error.Code)
				}
			}
			key := string(env.Method) + "." + string(env.Kind)
			if !strings.HasPrefix(filepath.Base(file), key+".") {
				t.Fatalf("file name does not start with %q", key+".")
			}
			newBody, ok := types[key]
			if !ok {
				t.Fatalf("no body type for %s", key)
			}
			covered[key] = true
			if env.Error == nil {
				body := newBody()
				if err := decodeStrict(env.Body, body); err != nil {
					t.Fatalf("decode body %T: %v", body, err)
				}
				if env.Body, err = json.Marshal(body); err != nil {
					t.Fatalf("encode body: %v", err)
				}
			}
			out, err := protocol.Encode(env)
			if err != nil {
				t.Fatalf("Encode: %v", err)
			}
			assertSameJSON(t, out, raw)
		})
	}
	for key := range types {
		if !covered[key] {
			t.Errorf("no sample in testdata for %s", key)
		}
	}
}
