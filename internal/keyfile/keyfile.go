// Package keyfile reads secret key files shared by the agent and the relay,
// such as the enroll key file (design D12).
package keyfile

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

// Read returns the trimmed content of the key file at path. The file mode
// must be 600 or stricter: no execute bit and no group or other bits, so
// 600, 400 and 200 pass while 700, 500 and 644 fail. An empty file is an
// error, since an empty key would match an empty credential.
func Read(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("key file: %w", err)
	}
	if mode := info.Mode().Perm(); mode&^0o600 != 0 {
		return "", fmt.Errorf("key file %s has mode %#o, must be 600 or stricter", path, mode)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("key file: %w", err)
	}
	key := strings.TrimSpace(string(raw))
	if key == "" {
		return "", errors.New("key file " + path + " is empty")
	}
	return key, nil
}
