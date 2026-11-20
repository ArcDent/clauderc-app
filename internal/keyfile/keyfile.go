// Package keyfile reads and writes secret files shared by the agent and the
// relay, such as the enroll key file (design D12).
package keyfile

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Write writes data to path with mode 600 through a temp file in the same
// directory, creating the directory with mode 700, so readers never see
// half a file.
func Write(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+"-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

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
