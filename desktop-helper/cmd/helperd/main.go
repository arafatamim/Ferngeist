package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	goruntime "runtime"
	"syscall"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/acquire"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/api"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/discovery"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/gateway"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/logging"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/pairing"
	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
	helperruntime "github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

var (
	buildVersion = "dev"
	buildCommit  = ""
	buildTime    = ""
)

// main wires the helper's narrow runtime: config, persistence, registry-backed
// catalog, process supervision, discovery, and the single HTTP/API surface.
func main() {
	cfg := config.Load()
	logger, logSvc, err := logging.New(cfg.LogLevel, cfg.LogDir, cfg.LogMaxSize, cfg.LogMaxBackups)
	if err != nil {
		slog.Error("failed to initialize logger", slog.String("error", err.Error()))
		os.Exit(1)
	}
	defer logSvc.Close()
	store, err := storage.Open(cfg.StateDBPath)
	if err != nil {
		logger.Error("failed to open state database", slog.String("path", cfg.StateDBPath), slog.String("error", err.Error()))
		os.Exit(1)
	}
	defer store.Close()
	if err := store.DeleteAllRuntimeTokens(context.Background()); err != nil {
		logger.Error("failed to clear stale runtime tokens", slog.String("error", err.Error()))
		os.Exit(1)
	}
	cfg = applyPersistedSettings(logger, store, cfg)

	registryClient := acpregistry.New(cfg.RegistryURL, 6*time.Hour)
	catalogSvc := catalog.NewWithBaseDirAndRegistry(".", registryClient)
	installer := acquire.New(logger, cfg.ManagedBinDir, store)
	runtimeSvc := helperruntime.NewSupervisorWithBaseDirAndInstaller(logger, ".", store, installer)
	pairingSvc := pairing.NewService(logger, store)
	gatewaySvc := gateway.New(logger, store)
	discoverySvc := discovery.New(logger)

	if cfg.EnableLAN {
		if _, portText, err := net.SplitHostPort(cfg.ListenAddr); err == nil {
			if port, parseErr := net.LookupPort("tcp", portText); parseErr == nil {
				if err := discoverySvc.Start(cfg.HelperName, port, discoveryTXTRecords(cfg, pairingSvc.ActiveDeviceCount())); err != nil {
					logger.Warn("mdns discovery unavailable", slog.String("error", err.Error()))
				}
			}
		}
	}
	defer discoverySvc.Stop()

	server := api.NewServer(
		cfg,
		api.BuildInfo{
			Version:   buildVersion,
			Commit:    buildCommit,
			BuiltAt:   buildTime,
			GoVersion: goruntime.Version(),
		},
		logger,
		catalogSvc,
		runtimeSvc,
		pairingSvc,
		gatewaySvc,
		discoverySvc,
		logSvc,
		registryClient,
	)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	logger.Info("starting helper daemon",
		slog.String("listen_addr", cfg.ListenAddr),
		slog.Bool("lan_enabled", cfg.EnableLAN),
	)

	errCh := make(chan error, 1)
	go func() {
		errCh <- server.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		logger.Info("shutdown requested")
	case err = <-errCh:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server exited", slog.String("error", err.Error()))
			os.Exit(1)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", slog.String("error", err.Error()))
		os.Exit(1)
	}
	if err := runtimeSvc.Shutdown(shutdownCtx); err != nil {
		logger.Error("runtime shutdown failed", slog.String("error", err.Error()))
		os.Exit(1)
	}

	logger.Info("helper daemon stopped")
}

// discoveryTXTRecords keeps the mDNS payload intentionally small and stable so
// Android can make fast pairing decisions without another round-trip.
func discoveryTXTRecords(cfg config.Config, pairedDeviceCount int) []string {
	return []string{
		"helper_name=" + cfg.HelperName,
		"helper_version=dev",
		"protocol_version=v1alpha1",
		fmt.Sprintf("pairing_required=%t", pairedDeviceCount == 0),
	}
}

// applyPersistedSettings treats SQLite as the source of user defaults while
// still letting process-level environment variables win for local debugging and
// packaged deployments.
func applyPersistedSettings(logger *slog.Logger, store *storage.SQLiteStore, cfg config.Config) config.Config {
	record, err := store.GetHelperSettings(context.Background())
	if err == nil {
		enableLAN := record.EnableLAN
		return cfg.ApplyPersistedSettings(config.PersistedSettings{
			RegistryURL:   record.RegistryURL,
			PublicBaseURL: record.PublicBaseURL,
			EnableLAN:     &enableLAN,
			HelperName:    record.HelperName,
		})
	}
	if !errors.Is(err, storage.ErrNotFound) {
		logger.Warn("failed to load helper settings", slog.String("error", err.Error()))
	}

	if err := store.SaveHelperSettings(context.Background(), storage.HelperSettingsRecord{
		RegistryURL:   cfg.RegistryURL,
		PublicBaseURL: cfg.PublicBaseURL,
		EnableLAN:     cfg.EnableLAN,
		HelperName:    cfg.HelperName,
	}); err != nil {
		logger.Warn("failed to seed helper settings", slog.String("error", err.Error()))
	}
	return cfg
}
