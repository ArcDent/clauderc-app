// Package pairing backs the agent's pair, devices and revoke commands
// (design D4): it calls the relay's admin API with the enroll key and
// draws the pairing QR code in the terminal.
package pairing

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"rsc.io/qr"

	"clauderc/internal/protocol"
)

// Client calls the relay's admin API.
type Client struct {
	// RelayURL is the relay address from agent.toml, https:// or wss://.
	RelayURL  string
	EnrollKey string
	// HTTPClient defaults to http.DefaultClient.
	HTTPClient *http.Client
}

// PairCode asks the relay for a one-time pairing code.
func (c Client) PairCode(ctx context.Context) (protocol.PairCodeResponse, error) {
	var res protocol.PairCodeResponse
	err := c.do(ctx, http.MethodPost, protocol.PathAdminPairCodes, http.StatusOK, &res)
	return res, err
}

// Devices lists the paired phones.
func (c Client) Devices(ctx context.Context) ([]protocol.Device, error) {
	var res protocol.DevicesListResponse
	err := c.do(ctx, http.MethodGet, protocol.PathAdminDevices, http.StatusOK, &res)
	return res.Devices, err
}

// Revoke deletes a phone's credential; the relay closes its connections.
func (c Client) Revoke(ctx context.Context, id string) error {
	return c.do(ctx, http.MethodDelete, protocol.PathAdminDevices+"/"+url.PathEscape(id), http.StatusNoContent, nil)
}

// do sends one admin request and decodes the answer into out, or turns
// the relay's error body into an error.
func (c Client) do(ctx context.Context, method, path string, want int, out any) error {
	base, err := httpBase(c.RelayURL)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.EnrollKey)
	hc := c.HTTPClient
	if hc == nil {
		hc = http.DefaultClient
	}
	res, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode != want {
		var e protocol.Error
		if json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&e) == nil && e.Message != "" {
			return fmt.Errorf("中转服务器拒绝：%s（%s）", e.Message, res.Status)
		}
		return fmt.Errorf("中转服务器返回 %s", res.Status)
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(res.Body).Decode(out)
}

// httpBase turns the relay address into the https:// base of its HTTP
// API; plain http:// and ws:// are refused.
func httpBase(relay string) (string, error) {
	u, err := url.Parse(relay)
	if err != nil {
		return "", fmt.Errorf("中转服务器地址 %q 无法解析：%w", relay, err)
	}
	switch u.Scheme {
	case "wss":
		u.Scheme = "https"
	case "https":
	default:
		return "", fmt.Errorf("中转服务器地址 %q 只能用 https:// 或 wss://", relay)
	}
	u.RawQuery, u.Fragment = "", ""
	return strings.TrimSuffix(u.String(), "/"), nil
}

// QR colors: the 256-color palette's pure black on its pure white. Themes
// remap the 16 basic colors and swap foreground and background between
// light and dark, but leave the color cube alone, so the code looks the
// same in every terminal.
const (
	qrColors = "\x1b[38;5;16;48;5;231m"
	qrReset  = "\x1b[0m"
)

// WriteQR draws text as a QR code with half-height block characters, two
// modules per character row, inside the quiet zone the standard asks for.
// Dark modules are black blocks on a white background whatever the
// terminal theme.
func WriteQR(w io.Writer, text string) error {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return err
	}
	const quiet = 2
	black := func(x, y int) bool {
		x, y = x-quiet, y-quiet
		return x >= 0 && y >= 0 && x < code.Size && y < code.Size && code.Black(x, y)
	}
	n := code.Size + 2*quiet
	var b strings.Builder
	for y := 0; y < n; y += 2 {
		b.WriteString(qrColors)
		for x := 0; x < n; x++ {
			top, bottom := black(x, y), y+1 < n && black(x, y+1)
			switch {
			case top && bottom:
				b.WriteString("█")
			case top:
				b.WriteString("▀")
			case bottom:
				b.WriteString("▄")
			default:
				b.WriteString(" ")
			}
		}
		// Reset before the newline so the white does not bleed into the
		// rest of the line.
		b.WriteString(qrReset + "\n")
	}
	_, err = io.WriteString(w, b.String())
	return err
}

// PrintPair shows a fresh pairing code: the QR code, its deadline in
// local time, and the URI as text for phones that cannot scan.
func PrintPair(w io.Writer, code protocol.PairCodeResponse) error {
	if err := WriteQR(w, code.URI); err != nil {
		return err
	}
	_, err := fmt.Fprintf(w, "\n用 clauderc APP 扫描上面的二维码，%s 前有效，只能用一次。\n%s\n",
		code.ExpiresAt.Local().Format("15:04:05"), code.URI)
	return err
}

// PrintDevices lists paired phones, one per line: id, pairing time, name.
func PrintDevices(w io.Writer, devices []protocol.Device) error {
	if len(devices) == 0 {
		_, err := fmt.Fprintln(w, "还没有已配对的设备。")
		return err
	}
	for _, d := range devices {
		if _, err := fmt.Fprintf(w, "%s  %s  %s\n", d.ID, d.CreatedAt.Local().Format("2006-01-02 15:04"), d.Name); err != nil {
			return err
		}
	}
	return nil
}
