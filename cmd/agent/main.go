// Command agent runs on a target machine and exposes its Claude Code
// background sessions to the relay server.
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"syscall"
	"time"

	"clauderc/internal/agent/app"
	"clauderc/internal/agent/config"
	"clauderc/internal/agent/pairing"
	"clauderc/internal/agent/service"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "dev"

// prog is the name the program was invoked as: "clauderc" through the npm
// wrapper, the file name when a binary from dist/ is run directly.
var prog = filepath.Base(os.Args[0])

// usage is a format string; %[1]s is prog.
const usage = `用法：
  %[1]s run [--config <agent.toml>]       连接中转服务器，开始服务
  %[1]s start [--config <agent.toml>]     在后台运行，关掉终端也不停
  %[1]s stop [--config <agent.toml>]      结束后台运行
  %[1]s pair [--config <agent.toml>]      生成配对二维码
  %[1]s devices [--config <agent.toml>]   列出已配对的手机
  %[1]s revoke [--config <agent.toml>] <id>  吊销一台手机
  %[1]s service install [--config <agent.toml>] [--dry-run]  生成开机自启的服务文件
  %[1]s --version
默认配置文件：~/.config/clauderc/agent.toml
后台运行的进程号和日志在配置文件同目录的 agent.pid、agent.log
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, usage, prog)
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var err error
	switch cmd, args := os.Args[1], os.Args[2:]; cmd {
	case "--version", "-version":
		fmt.Println(prog, version)
		return
	case "run":
		err = runCmd(ctx, args, nil, os.Stderr)
	case "start":
		var exe string
		if exe, err = os.Executable(); err == nil {
			err = startCmd(args, exe, os.Stdout)
		}
	case "stop":
		err = stopCmd(args, os.Stdout)
	case "pair", "devices", "revoke":
		err = adminCmd(ctx, cmd, args, nil, os.Stdout)
	case "service":
		if len(args) == 0 || args[0] != "install" {
			err = fmt.Errorf("用法：%s service install [--config <agent.toml>] [--dry-run]", prog)
			break
		}
		err = serviceCmd(args[1:], os.Stdout)
	default:
		fmt.Fprintf(os.Stderr, usage, prog)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s %s: %v\n", prog, os.Args[1], err)
		os.Exit(1)
	}
}

// flags parses --config, plus --dry-run when dryRun is not nil, and
// returns the remaining arguments.
func flags(name string, args []string, dryRun *bool) (cfgPath string, rest []string, err error) {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	fs.StringVar(&cfgPath, "config", defaultConfigPath(), "path to agent.toml")
	if dryRun != nil {
		fs.BoolVar(dryRun, "dry-run", false, "print the service file instead of writing it")
	}
	if err := fs.Parse(args); err != nil {
		return "", nil, err
	}
	return cfgPath, fs.Args(), nil
}

func defaultConfigPath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "agent.toml"
	}
	return filepath.Join(home, ".config", "clauderc", "agent.toml")
}

// runCmd serves the relay until ctx ends. hc dials the relay; nil uses
// the default client.
func runCmd(ctx context.Context, args []string, hc *http.Client, logOut io.Writer) error {
	cfgPath, _, err := flags("run", args, nil)
	if err != nil {
		return err
	}
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}
	return app.Run(ctx, app.Options{
		Config:     cfg,
		Version:    version,
		HTTPClient: hc,
		Logger:     slog.New(slog.NewTextHandler(logOut, nil)),
	})
}

// adminCmd runs pair, devices or revoke against the relay's admin API.
func adminCmd(ctx context.Context, cmd string, args []string, hc *http.Client, out io.Writer) error {
	cfgPath, rest, err := flags(cmd, args, nil)
	if err != nil {
		return err
	}
	if cmd == "revoke" && len(rest) != 1 {
		return fmt.Errorf("用法：%s revoke [--config <agent.toml>] <id>", prog)
	}
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}
	c := pairing.Client{RelayURL: cfg.RelayURL, EnrollKey: cfg.EnrollKey, HTTPClient: hc}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	switch cmd {
	case "pair":
		code, err := c.PairCode(ctx)
		if err != nil {
			return err
		}
		return pairing.PrintPair(out, code)
	case "devices":
		devices, err := c.Devices(ctx)
		if err != nil {
			return err
		}
		return pairing.PrintDevices(out, devices)
	default:
		if err := c.Revoke(ctx, rest[0]); err != nil {
			return err
		}
		_, err := fmt.Fprintf(out, "已吊销 %s，它的连接已被断开。\n", rest[0])
		return err
	}
}

// serviceCmd writes the service file for this OS.
func serviceCmd(args []string, out io.Writer) error {
	var dryRun bool
	cfgPath, _, err := flags("service install", args, &dryRun)
	if err != nil {
		return err
	}
	cfgPath, err = filepath.Abs(cfgPath)
	if err != nil {
		return err
	}
	if _, err := config.Load(cfgPath); err != nil {
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if exe, err = filepath.EvalSymlinks(exe); err != nil {
		return err
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	return service.Install(out, runtime.GOOS, service.Spec{Exe: exe, Config: cfgPath, Home: home}, dryRun)
}
