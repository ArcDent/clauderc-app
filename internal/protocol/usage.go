package protocol

import "time"

// UsageWindow is one plan limit of the machine's Claude login.
type UsageWindow struct {
	// Utilization is the share of the limit used, 0–100.
	Utilization float64 `json:"utilization"`
	// ResetsAt is when the window starts over; absent when unknown.
	ResetsAt *time.Time `json:"resetsAt,omitempty"`
}

// UsageStale says why the numbers in a UsageResponse are not fresh.
type UsageStale string

const (
	// UsageExpired: the Claude login on the machine has expired; Claude Code
	// renews it the next time it talks to Anthropic.
	UsageExpired UsageStale = "expired"
	// UsageFailed: reading the usage failed for another reason, such as the
	// network or Anthropic refusing the request.
	UsageFailed UsageStale = "failed"
)

// UsageResponse answers usage.get: the 5-hour and weekly limits of the
// Claude subscription the machine is logged in with.
type UsageResponse struct {
	// Available is false when the machine has no Claude subscription login
	// to read; the other fields are empty then.
	Available bool         `json:"available"`
	FiveHour  *UsageWindow `json:"fiveHour,omitempty"`
	SevenDay  *UsageWindow `json:"sevenDay,omitempty"`
	// FetchedAt is when the windows were read; absent when never read.
	FetchedAt *time.Time `json:"fetchedAt,omitempty"`
	// Stale is set when the latest read failed; the windows, if any, are
	// the last ones read successfully.
	Stale UsageStale `json:"stale,omitempty"`
}
