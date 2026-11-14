package protocol

import "time"

// Relay HTTP paths. Every authenticated request carries
// "Authorization: Bearer <secret>": the enroll key for /ws/agent and
// /api/admin/*, the device token for /ws/app. A missing or wrong secret is
// answered with HTTP 401 and an Error body; WebSocket upgrades are refused.
//
// HTTP endpoints answer errors with an Error body:
//
//	400 invalidParams    malformed request body
//	401 unauthenticated  missing or wrong enroll key / device token
//	404 pairCodeInvalid  pairing code unknown or already used
//	410 pairCodeExpired  pairing code older than 5 minutes
//	404 deviceNotFound   revoking a device id that is not paired
//	429 rateLimited      more than 10 failed pairings from one source per minute
const (
	// PathHealth (GET, no auth) answers 200 while the relay is running.
	PathHealth  = "/healthz"
	PathAgentWS = "/ws/agent"
	PathAppWS   = "/ws/app"
	// PathPair (POST, no auth) exchanges a pairing code for a device token:
	// PairRequest in, PairResponse out.
	PathPair = "/api/pair"
	// PathAdminPairCodes (POST, enroll key, empty body) issues a one-time
	// pairing code: PairCodeResponse.
	PathAdminPairCodes = "/api/admin/pair-codes"
	// PathAdminDevices (enroll key): GET lists paired devices as
	// DevicesListResponse; DELETE PathAdminDevices+"/{id}" revokes one,
	// answers 204 with no body and closes that phone's connections.
	PathAdminDevices = "/api/admin/devices"
)

// Device is a paired phone as listed by the admin API.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
}

// DevicesListResponse answers GET /api/admin/devices.
type DevicesListResponse struct {
	Devices []Device `json:"devices"`
}

// PairCodeResponse is a fresh one-time pairing code.
type PairCodeResponse struct {
	// Code is 128 random bits as 32 lowercase hex characters.
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
	// URI is the QR code content:
	// clauderc://pair?relay=<relay wss address>&code=<code>.
	URI string `json:"uri"`
}

// PairRequest is sent by the phone after scanning the QR code.
type PairRequest struct {
	Code       string `json:"code"`
	DeviceName string `json:"deviceName"`
}

// PairResponse carries the long-lived device token; the relay keeps only
// its SHA-256 digest.
type PairResponse struct {
	DeviceID string `json:"deviceId"`
	Token    string `json:"token"`
}

// Query parameters an agent puts on the /ws/agent upgrade request, e.g.
// /ws/agent?id=m-7f3a&name=mac-mini&agentVersion=0.1.0&claudeVersion=2.1.323.
// Query parameters rather than headers so that non-ASCII names survive.
// id is required; the others may be empty.
const (
	AgentParamID            = "id"
	AgentParamName          = "name"
	AgentParamAgentVersion  = "agentVersion"
	AgentParamClaudeVersion = "claudeVersion"
)

// Empty is the body of requests and responses that carry no data, and of
// the client.gone event.
type Empty struct{}

// Machine is one registered target machine as known to the relay.
// It is also the body of the machine.status event.
type Machine struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
	// LastSeenAt is when the relay last heard from the machine.
	LastSeenAt time.Time `json:"lastSeenAt"`
	// AgentVersion and ClaudeVersion are what the agent reported on its
	// last connection; empty when unknown.
	AgentVersion  string `json:"agentVersion"`
	ClaudeVersion string `json:"claudeVersion"`
}

// MachinesListResponse answers machines.list.
type MachinesListResponse struct {
	Machines []Machine `json:"machines"`
}
