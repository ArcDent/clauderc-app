package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// getsid reads the session id from /proc, since syscall has no Getsid on Linux.
func getsid(pid int) (int, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return 0, err
	}
	// The command name may contain spaces and parentheses, so split after the last ')'.
	// The fields that follow are: state ppid pgrp session ...
	s := string(data)
	fields := strings.Fields(s[strings.LastIndexByte(s, ')')+1:])
	if len(fields) < 4 {
		return 0, fmt.Errorf("unexpected /proc/%d/stat: %q", pid, s)
	}
	return strconv.Atoi(fields[3])
}
