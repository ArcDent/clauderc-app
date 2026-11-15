//go:build !linux

package main

import "syscall"

func getsid(pid int) (int, error) {
	return syscall.Getsid(pid)
}
