package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	goruntime "runtime"
	"syscall"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/api"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/daemon"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

var (
	buildVersion = "dev"
	buildCommit  = ""
	buildTime    = ""
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	err := daemon.Run(ctx, api.BuildInfo{
		Version:   buildVersion,
		Commit:    buildCommit,
		BuiltAt:   buildTime,
		GoVersion: goruntime.Version(),
	})
	if err == nil {
		return
	}
	slog.Error("helper daemon failed", slog.String("error", err.Error()))
	os.Exit(1)
}

func discoveryTXTRecords(cfg config.Config, pairedDeviceCount int) []string {
	return daemon.DiscoveryTXTRecords(cfg, pairedDeviceCount)
}

func applyPersistedSettings(logger *slog.Logger, store *storage.SQLiteStore, cfg config.Config) config.Config {
	return daemon.ApplyPersistedSettings(logger, store, cfg)
}
