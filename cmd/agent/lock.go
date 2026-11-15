package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// lockFile sits next to the config like agent.pid. The directory owns one
// machine id, and two agents with the same id would keep replacing each
// other at the relay, so each directory runs at most one agent.
func lockFile(cfgPath string) string {
	return filepath.Join(filepath.Dir(cfgPath), "agent.lock")
}

// runningError reports that another process holds the config's lock; pid
// is 0 when the lock file did not name it.
type runningError struct{ pid int }

func (e *runningError) Error() string {
	if e.pid > 0 {
		return fmt.Sprintf("这份配置已有 clauderc 在运行，进程号 %d，同一份配置只能运行一个", e.pid)
	}
	return "这份配置已有 clauderc 在运行，同一份配置只能运行一个"
}

// lockConfig takes the config directory's lock and writes this process's
// pid into the lock file. The system drops the lock when the returned file
// is closed or the process exits, even when it is killed.
func lockConfig(cfgPath string) (*os.File, error) {
	f, err := os.OpenFile(lockFile(cfgPath), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		defer f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			data, _ := io.ReadAll(f)
			pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
			return nil, &runningError{pid: pid}
		}
		return nil, err
	}
	err = f.Truncate(0)
	if err == nil {
		_, err = f.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
	}
	if err != nil {
		f.Close()
		return nil, err
	}
	return f, nil
}
