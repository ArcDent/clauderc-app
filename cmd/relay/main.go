// Command relay forwards messages between phones and target machines.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"clauderc/internal/relay"
	"clauderc/internal/relay/store"
)

// version is overridden at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "serve" {
		if err := serve(os.Args[2:]); err != nil {
			fmt.Fprintln(os.Stderr, "relay serve:", err)
			os.Exit(1)
		}
		return
	}
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "usage: relay serve --config <relay.toml>\n       relay --version\n")
	}
	flag.Parse()
	if *showVersion {
		fmt.Println("relay", version)
		return
	}
	flag.Usage()
	os.Exit(2)
}

func serve(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	cfgPath := fs.String("config", "", "path to relay.toml")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *cfgPath == "" {
		return errors.New("--config is required")
	}
	cfg, err := relay.LoadConfig(*cfgPath)
	if err != nil {
		return err
	}
	st, err := store.Open(cfg.StateFile)
	if err != nil {
		return err
	}
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	srv := relay.NewServer(relay.Options{
		EnrollKey: cfg.EnrollKey,
		PublicURL: cfg.PublicURL,
		Store:     st,
		Logger:    log,
	})
	ln, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return err
	}
	hs := &http.Server{Handler: srv, ReadHeaderTimeout: 10 * time.Second}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	errc := make(chan error, 1)
	go func() { errc <- hs.Serve(ln) }()
	log.Info("relay listening", "addr", ln.Addr().String(), "version", version)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err = hs.Shutdown(shutdownCtx)
	srv.Close()
	return err
}
