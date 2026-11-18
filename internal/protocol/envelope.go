package protocol

import (
	"encoding/json"
	"fmt"
	"time"
)

// Version is the only envelope version this package accepts.
const Version = 1

// Transport limits shared by both WebSocket legs (design D2).
const (
	// HeartbeatInterval is how often each side sends a WebSocket ping.
	HeartbeatInterval = 20 * time.Second
	// HeartbeatTimeout is how long a side waits without any frame before
	// treating the peer as disconnected.
	HeartbeatTimeout = 45 * time.Second
	// MaxMessageBytes caps a single text message, images included.
	MaxMessageBytes = 16 << 20
)

// Kind tells requests, responses and events apart.
type Kind string

const (
	KindRequest  Kind = "req"
	KindResponse Kind = "res"
	KindEvent    Kind = "evt"
)

// Envelope wraps every message exchanged over /ws/app and /ws/agent.
//
// The phone sets Machine on requests; the relay fills Client before
// forwarding to that machine, and routes responses and events back by Client.
// A response carries either Body or Error.
type Envelope struct {
	V       int             `json:"v"`
	Kind    Kind            `json:"kind"`
	ID      string          `json:"id,omitempty"`
	Method  Method          `json:"method,omitempty"`
	Machine string          `json:"machine,omitempty"`
	Client  string          `json:"client,omitempty"`
	Body    json.RawMessage `json:"body,omitempty"`
	Error   *Error          `json:"error,omitempty"`
}

// Error is the failure part of a response.
type Error struct {
	Code ErrorCode `json:"code"`
	// Message is human readable; for git failures it is git's raw output.
	Message string `json:"message"`
}

// Error lets a handler return an *Error as a Go error; agent.FailErr
// turns it back into the response's error.
func (e *Error) Error() string { return string(e.Code) + ": " + e.Message }

// ErrorCode identifies why a request failed.
type ErrorCode string

const (
	// CodeUnauthenticated: the credential is missing, wrong or revoked.
	CodeUnauthenticated ErrorCode = "unauthenticated"
	// CodeMachineOffline: the target machine is not connected to the relay.
	CodeMachineOffline ErrorCode = "machineOffline"
	// CodeUnknownMethod: the receiver does not handle this method.
	CodeUnknownMethod ErrorCode = "unknownMethod"
	// CodeInvalidParams: the request body is malformed or not allowed,
	// e.g. creating a session in the "other" profile.
	CodeInvalidParams ErrorCode = "invalidParams"
	// CodeSessionNotFound: no background session with that id.
	CodeSessionNotFound ErrorCode = "sessionNotFound"
	// CodeNotFound: the session has no such item, e.g. chat.toolOutput
	// named a tool_use id that is not in its transcript.
	CodeNotFound ErrorCode = "notFound"
	// CodeSessionRunning: the operation needs a stopped session.
	CodeSessionRunning ErrorCode = "sessionRunning"
	// CodePendingChanged: chat.answer named a tool_use id that is no longer
	// the session's current pending prompt; no keys were sent.
	CodePendingChanged ErrorCode = "pendingChanged"
	// CodeNotDelivered: the message did not show up in the transcript in time.
	CodeNotDelivered ErrorCode = "notDelivered"
	// CodeDirNotFound: the given directory does not exist.
	CodeDirNotFound ErrorCode = "dirNotFound"
	// CodeNotGitRepo: the directory is not inside a git repository.
	CodeNotGitRepo ErrorCode = "notGitRepo"
	// CodeNeedsConfirmation: the user must confirm before retrying, e.g.
	// git.push without an upstream; retry with the confirming flag set.
	CodeNeedsConfirmation ErrorCode = "needsConfirmation"
	// CodeGitFailed: git exited with an error; Message holds its raw output.
	CodeGitFailed ErrorCode = "gitFailed"
	// CodeGitCredentialsMissing: git needed interactive credentials.
	CodeGitCredentialsMissing ErrorCode = "gitCredentialsMissing"
	// CodeTimeout: the operation did not finish in time.
	CodeTimeout ErrorCode = "timeout"
	// CodeInternal: an unexpected failure on the receiver.
	CodeInternal ErrorCode = "internal"

	// Relay HTTP errors; see relay.go for the HTTP status of each.

	// CodePairCodeExpired: the pairing code is past its 5 minutes (HTTP 410).
	CodePairCodeExpired ErrorCode = "pairCodeExpired"
	// CodePairCodeInvalid: no such pairing code, or it was used (HTTP 404).
	CodePairCodeInvalid ErrorCode = "pairCodeInvalid"
	// CodeRateLimited: too many failed pairing attempts from this source
	// within a minute (HTTP 429).
	CodeRateLimited ErrorCode = "rateLimited"
	// CodeDeviceNotFound: no paired device with that id (HTTP 404).
	CodeDeviceNotFound ErrorCode = "deviceNotFound"
	// CodeMachineInUse: an agent with the same machine id is connected and
	// still answers pings, so the new one is refused (HTTP 409).
	CodeMachineInUse ErrorCode = "machineInUse"
)

func (e Envelope) validate() error {
	if e.V != Version {
		return fmt.Errorf("protocol: unsupported version %d, want %d", e.V, Version)
	}
	switch e.Kind {
	case KindRequest, KindResponse, KindEvent:
		return nil
	default:
		return fmt.Errorf("protocol: unknown kind %q", e.Kind)
	}
}

// Encode serializes an envelope after checking its version and kind.
func Encode(e Envelope) ([]byte, error) {
	if err := e.validate(); err != nil {
		return nil, err
	}
	return json.Marshal(e)
}

// Decode parses an envelope and rejects any version other than Version.
func Decode(data []byte) (Envelope, error) {
	var e Envelope
	if err := json.Unmarshal(data, &e); err != nil {
		return Envelope{}, fmt.Errorf("protocol: decode envelope: %w", err)
	}
	if err := e.validate(); err != nil {
		return Envelope{}, err
	}
	return e, nil
}
