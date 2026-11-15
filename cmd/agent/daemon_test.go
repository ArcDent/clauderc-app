package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
)

// buildAgent builds this command into a temp dir for start to launch.
func buildAgent(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "agent")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}
	return bin
}

func readPID(t *testing.T, pidFile string) int {
	t.Helper()
	data, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatalf("pid file: %v", err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil {
		t.Fatal(err)
	}
	return pid
}

func TestStartAndStop(t *testing.T) {
	exe := buildAgent(t)
	// Nothing listens there, so run keeps retrying instead of exiting.
	cfg := writeConfig(t, "wss://127.0.0.1:1")
	pidFile, logFile := daemonFiles(cfg)
	args := []string{"--config", cfg}

	var out bytes.Buffer
	if err := startCmd(args, exe, &out); err != nil {
		t.Fatal(err)
	}
	pid := readPID(t, pidFile)
	t.Cleanup(func() { _ = syscall.Kill(pid, syscall.SIGKILL) })
	if !strings.Contains(out.String(), "已在后台运行，进程号 "+strconv.Itoa(pid)) || !strings.Contains(out.String(), logFile) {
		t.Errorf("start output:\n%s", out.String())
	}
	if !isOurRun(pid, cfg) {
		t.Fatalf("process %d is not running run --config %s", pid, cfg)
	}
	if sid, err := getsid(pid); err != nil || sid != pid {
		t.Errorf("getsid(%d) = %d, %v; want its own session", pid, sid, err)
	}

	// A second start finds the running one and starts nothing.
	out.Reset()
	if err := startCmd(args, exe, &out); err != nil {
		t.Fatal(err)
	}
	if got := readPID(t, pidFile); got != pid || !strings.Contains(out.String(), "已在后台运行，进程号 "+strconv.Itoa(pid)) {
		t.Errorf("second start: pid %d, output:\n%s", got, out.String())
	}

	out.Reset()
	if err := stopCmd(args, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "已结束后台进程 "+strconv.Itoa(pid)) {
		t.Errorf("stop output:\n%s", out.String())
	}
	if isOurRun(pid, cfg) {
		t.Errorf("process %d still running after stop", pid)
	}
	if _, err := os.Stat(pidFile); !os.IsNotExist(err) {
		t.Errorf("pid file left behind: %v", err)
	}

	out.Reset()
	if err := stopCmd(args, &out); err != nil || !strings.Contains(out.String(), "没有在后台运行") {
		t.Errorf("stop when not running: %v %q", err, out.String())
	}
}

func TestStopClearsStalePID(t *testing.T) {
	cfg := writeConfig(t, "wss://127.0.0.1:1")
	pidFile, _ := daemonFiles(cfg)
	// Our own pid is alive but is not the background run, so it must not be signalled.
	if err := os.WriteFile(pidFile, []byte(strconv.Itoa(os.Getpid())), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if err := stopCmd([]string{"--config", cfg}, &out); err != nil || !strings.Contains(out.String(), "没有在后台运行") {
		t.Errorf("stop: %v %q", err, out.String())
	}
	if _, err := os.Stat(pidFile); !os.IsNotExist(err) {
		t.Errorf("stale pid file kept: %v", err)
	}
}

func TestStartRefusesBadConfig(t *testing.T) {
	cfg := filepath.Join(t.TempDir(), "agent.toml")
	err := startCmd([]string{"--config", cfg}, "/nonexistent", &bytes.Buffer{})
	if err == nil {
		t.Fatal("start with a missing config succeeded")
	}
	pidFile, _ := daemonFiles(cfg)
	if _, err := os.Stat(pidFile); !os.IsNotExist(err) {
		t.Errorf("pid file written: %v", err)
	}
}

func TestStartReportsEarlyExit(t *testing.T) {
	cfg := writeConfig(t, "wss://127.0.0.1:1")
	pidFile, logFile := daemonFiles(cfg)
	if err := os.WriteFile(logFile, []byte("an older run\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	exe := filepath.Join(t.TempDir(), "dies")
	if err := os.WriteFile(exe, []byte("#!/bin/sh\necho first\necho boom >&2\nexit 3\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	err := startCmd([]string{"--config", cfg}, exe, &bytes.Buffer{})
	if err == nil {
		t.Fatal("start of a dying process succeeded")
	}
	msg := err.Error()
	if !strings.Contains(msg, "first\nboom") || strings.Contains(msg, "an older run") {
		t.Errorf("error should show only this run's log:\n%s", msg)
	}
	if _, err := os.Stat(pidFile); !os.IsNotExist(err) {
		t.Errorf("pid file kept after the early exit: %v", err)
	}
}
