package agent

import (
	"context"
	"encoding/json"
	"errors"

	"clauderc/internal/protocol"
)

// Handler serves one group of protocol methods on the agent, e.g. the
// session list or chat. The connection layer routes each request to the
// handler that lists its method.
//
// Concurrency: Handle may be called from several goroutines at once, also
// for the same client, and concurrently with the ClientTracker methods.
// It should return quickly; long work belongs in the handler's own
// goroutines, reporting back through its Pusher.
type Handler interface {
	// Methods lists the request methods Handle answers. It is called once
	// at registration and must not change.
	Methods() []protocol.Method
	// Handle answers one request; the result is the response envelope,
	// built with Reply or Fail. ctx ends when the request is abandoned.
	Handle(ctx context.Context, req protocol.Envelope) protocol.Envelope
}

// ClientTracker is implemented by handlers that keep state per phone
// connection, such as subscriptions or hidden attaches. Handlers without
// such state need not implement it. Both methods must return quickly and
// are safe to call for clients the handler never saw.
type ClientTracker interface {
	// ClientGone drops everything held for one phone connection; called
	// on the relay's client.gone event.
	ClientGone(client string)
	// AllClientsGone drops everything held for every phone connection.
	// The connection layer calls it whenever its relay connection is lost
	// or re-established, because the relay only sends client.gone while
	// the agent is online and phones that left meanwhile go unreported.
	AllClientsGone()
}

// Pusher delivers an event envelope to the phone connection named by its
// Client field. Handlers get one at construction to send events.
//
// Push must not block: an implementation queues or drops the event. It
// may be called concurrently, and for a client that is already gone; the
// connection layer must tolerate both and simply discard such events.
type Pusher interface {
	Push(evt protocol.Envelope)
}

// PusherFunc adapts a function to Pusher.
type PusherFunc func(evt protocol.Envelope)

// Push calls f(evt).
func (f PusherFunc) Push(evt protocol.Envelope) { f(evt) }

// PushEvent builds the event method with body for client and pushes it in
// one step. It returns the encoding error, in which case nothing is pushed.
func PushEvent(p Pusher, client string, method protocol.Method, body any) error {
	evt, err := Event(client, method, body)
	if err != nil {
		return err
	}
	p.Push(evt)
	return nil
}

// Reply builds the successful response to req carrying body.
func Reply(req protocol.Envelope, body any) protocol.Envelope {
	raw, err := json.Marshal(body)
	if err != nil {
		return Fail(req, protocol.CodeInternal, InternalMessage)
	}
	res := response(req)
	res.Body = raw
	return res
}

// Fail builds the error response to req.
func Fail(req protocol.Envelope, code protocol.ErrorCode, message string) protocol.Envelope {
	res := response(req)
	res.Error = &protocol.Error{Code: code, Message: message}
	return res
}

// InternalMessage is what the phone sees for an unexpected failure; the
// error itself stays on the agent, as its text is neither for the phone
// nor in Chinese.
const InternalMessage = "目标机内部出错，请稍后重试"

// BadRequestMessage answers a request body that does not decode.
const BadRequestMessage = "请求内容格式不对"

// FailErr builds the error response to req for err: a *protocol.Error
// keeps its code and message, anything else is CodeInternal with
// InternalMessage.
func FailErr(req protocol.Envelope, err error) protocol.Envelope {
	var e *protocol.Error
	if errors.As(err, &e) {
		return Fail(req, e.Code, e.Message)
	}
	return Fail(req, protocol.CodeInternal, InternalMessage)
}

// FailUnknown answers a method the handler does not serve.
func FailUnknown(req protocol.Envelope) protocol.Envelope {
	return Fail(req, protocol.CodeUnknownMethod, "目标机不支持这个方法："+string(req.Method))
}

// Event builds an event for one phone connection; PushEvent also sends it.
func Event(client string, method protocol.Method, body any) (protocol.Envelope, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return protocol.Envelope{}, err
	}
	return protocol.Envelope{V: protocol.Version, Kind: protocol.KindEvent, Method: method, Client: client, Body: raw}, nil
}

func response(req protocol.Envelope) protocol.Envelope {
	return protocol.Envelope{
		V:       protocol.Version,
		Kind:    protocol.KindResponse,
		ID:      req.ID,
		Method:  req.Method,
		Machine: req.Machine,
		Client:  req.Client,
	}
}
