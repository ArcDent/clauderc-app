// Package agent defines the contract between the agent's relay connection
// layer and its protocol handlers: the Handler and optional ClientTracker
// interfaces a handler implements, the Pusher it sends events through, and
// helpers that build responses and events. The handlers themselves live in
// subpackages such as sessions.
package agent
