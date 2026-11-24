package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"clauderc/internal/agent/config"
)

// startGrace is how long start waits for the background run to fail early;
// stopWait is how long stop waits for it to exit after SIGTERM.
var (
	startGrace = time.Second
	stopWait   = 5 * time.Second
)

// daemonEnv marks the run that start launches. Only that run writes the
// pid file, after it has taken the config's lock, so of two starts racing
// each other the file always names the run that stays.
const daemonEnv = "CLAUDERC_DAEMON"

// beforeLaunch runs after start found the config unlocked, just before it
// launches the run. Tests use it to let a second start in between.
var beforeLaunch = func() {}

// daemonFiles returns the pid and log files of the background run, kept
// next to the config so each config has its own.
func daemonFiles(cfgPath string) (pidFile, logFile string) {
	dir := filepath.Dir(cfgPath)
	return filepath.Join(dir, "agent.pid"), filepath.Join(dir, "agent.log")
}

// absConfig parses --config and makes it absolute, so the background run
// and the pid check do not depend on the current directory.
func absConfig(name string, args []string) (string, error) {
	cfgPath, _, err := flags(name, args, nil)
	if err != nil {
		return "", err
	}
	return filepath.Abs(cfgPath)
}

// startCmd runs exe as `run --config <cfg>` in a new session, detached from
// the terminal, with its output appended to agent.log.
func startCmd(args []string, exe string, out io.Writer) error {
	cfgPath, err := absConfig("start", args)
	if err != nil {
		return err
	}
	if _, err := config.Load(cfgPath); err != nil {
		return err
	}
	pidFile, logFile := daemonFiles(cfgPath)
	if pid, ok := runningPID(pidFile, cfgPath); ok {
		return alreadyRunning(out, pid, logFile)
	}
	// A foreground run or the service may hold the config without a pid file.
	lock, err := lockConfig(cfgPath)
	var running *runningError
	if errors.As(err, &running) {
		_, err := fmt.Fprintf(out, "已在运行，进程号 %d\n", running.pid)
		return err
	}
	if err != nil {
		return err
	}
	lock.Close()
	beforeLaunch()
	log, err := os.OpenFile(logFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	defer log.Close()
	// Only this run's lines are shown if it fails.
	from, err := log.Seek(0, io.SeekEnd)
	if err != nil {
		return err
	}
	cmd := exec.Command(exe, "run", "--config", cfgPath)
	cmd.Args[0] = prog
	cmd.Stdout, cmd.Stderr = log, log
	cmd.Env = append(os.Environ(), daemonEnv+"=1")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if home, err := os.UserHomeDir(); err == nil {
		cmd.Dir = home
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	pid := cmd.Process.Pid
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	select {
	case werr := <-exited:
		// A start racing this one may have launched the run that got the lock.
		if other, ok := runningPID(pidFile, cfgPath); ok {
			return alreadyRunning(out, other, logFile)
		}
		// The run may have written the pid file before it died.
		removePIDFile(pidFile, pid)
		return fmt.Errorf("后台进程启动后就退出了（%v），日志最后几行：\n%s", werr, logTail(logFile, from, 20))
	case <-time.After(startGrace):
	}
	stop := prog + " stop"
	if def, _ := filepath.Abs(defaultConfigPath()); cfgPath != def {
		stop += " --config " + cfgPath
	}
	_, err = fmt.Fprintf(out, "已在后台运行，进程号 %d\n日志：%s\n结束：%s\n", pid, logFile, stop)
	return err
}

func alreadyRunning(out io.Writer, pid int, logFile string) error {
	_, err := fmt.Fprintf(out, "已在后台运行，进程号 %d\n日志：%s\n", pid, logFile)
	return err
}

// writeOwnPID writes this process's pid file when start launched it and
// returns the function that removes the file again. The caller holds the
// config's lock, so no other run writes the file meanwhile.
func writeOwnPID(cfgPath string) (func(), error) {
	if os.Getenv(daemonEnv) == "" {
		return func() {}, nil
	}
	// Processes this run starts must not take the mark as theirs.
	os.Unsetenv(daemonEnv)
	pidFile, _ := daemonFiles(cfgPath)
	pid := os.Getpid()
	if err := os.WriteFile(pidFile, []byte(strconv.Itoa(pid)+"\n"), 0o600); err != nil {
		return nil, err
	}
	return func() { removePIDFile(pidFile, pid) }, nil
}

// removePIDFile removes pidFile only while it names pid, so a file another
// run has written since is kept.
func removePIDFile(pidFile string, pid int) {
	data, err := os.ReadFile(pidFile)
	if err != nil {
		return
	}
	if got, _ := strconv.Atoi(strings.TrimSpace(string(data))); got == pid {
		_ = os.Remove(pidFile)
	}
}

// stopCmd sends SIGTERM to the background run and waits for it to exit.
func stopCmd(args []string, out io.Writer) error {
	cfgPath, err := absConfig("stop", args)
	if err != nil {
		return err
	}
	pidFile, _ := daemonFiles(cfgPath)
	pid, ok := runningPID(pidFile, cfgPath)
	if !ok {
		removePIDFile(pidFile, pid)
		_, err := fmt.Fprintln(out, "没有在后台运行")
		return err
	}
	if err := syscall.Kill(pid, syscall.SIGTERM); err != nil && !errors.Is(err, syscall.ESRCH) {
		return err
	}
	for deadline := time.Now().Add(stopWait); ; {
		if !isOurRun(pid, cfgPath) {
			break
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("进程 %d 在 %s 内没有退出，可以用 kill -9 %d 强制结束", pid, stopWait, pid)
		}
		time.Sleep(50 * time.Millisecond)
	}
	removePIDFile(pidFile, pid)
	_, err = fmt.Fprintf(out, "已结束后台进程 %d\n", pid)
	return err
}

// runningPID reads pidFile and reports whether that process is still the
// background run of cfgPath.
func runningPID(pidFile, cfgPath string) (int, bool) {
	data, err := os.ReadFile(pidFile)
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0, false
	}
	return pid, isOurRun(pid, cfgPath)
}

// isOurRun checks the process's command line, so a pid the system has
// since given to another program is never signalled.
func isOurRun(pid int, cfgPath string) bool {
	line, err := exec.Command("ps", "-o", "command=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return false
	}
	return bytes.Contains(line, []byte(" run --config "+cfgPath))
}

// logTail returns the last n lines written to logFile after offset from.
func logTail(logFile string, from int64, n int) string {
	data, err := os.ReadFile(logFile)
	if err != nil || int64(len(data)) < from {
		return ""
	}
	lines := strings.Split(strings.TrimRight(string(data[from:]), "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}
