package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"text/tabwriter"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/adminclient"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/service"
)

func runDaemonInstall(options service.InstallOptions) error {
	manager := service.NewManager()
	if err := manager.Install(options); err != nil {
		if errors.Is(err, service.ErrServicePermissionDenied) {
			return fmt.Errorf("install daemon service: %w\nHint: rerun with elevated privileges, for example: sudo ferngeist daemon install", err)
		}
		if errors.Is(err, service.ErrInvalidInstallOptions) {
			return fmt.Errorf("install daemon service: %w\nHint: use --host, --port, and optional --public-url", err)
		}
		return fmt.Errorf("install daemon service: %w", err)
	}
	fmt.Println("Daemon service installed and started.")
	return nil
}

func runDaemonUninstall(purge bool) error {
	manager := service.NewManager()
	if err := manager.Uninstall(purge); err != nil {
		return fmt.Errorf("uninstall daemon service: %w", err)
	}
	if purge {
		fmt.Println("Daemon service uninstalled and data purged.")
	} else {
		fmt.Println("Daemon service uninstalled.")
	}
	return nil
}

func runDaemonStart() error {
	manager := service.NewManager()
	if err := manager.Start(); err != nil {
		return fmt.Errorf("start daemon service: %w", err)
	}
	fmt.Println("Daemon service started.")
	return nil
}

func runDaemonStop() error {
	manager := service.NewManager()
	if err := manager.Stop(); err != nil {
		return fmt.Errorf("stop daemon service: %w", err)
	}
	fmt.Println("Daemon service stopped.")
	return nil
}

func runDaemonRestart() error {
	manager := service.NewManager()
	if err := manager.Restart(); err != nil {
		return fmt.Errorf("restart daemon service: %w", err)
	}
	fmt.Println("Daemon service restarted.")
	return nil
}

func runDaemonStatus() error {
	manager := service.NewManager()
	serviceStatus, err := manager.Status()
	if err != nil {
		return fmt.Errorf("read daemon service status: %w", err)
	}

	writer := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(writer, "SERVICE")
	fmt.Fprintf(writer, "INSTALLED\t%t\n", serviceStatus.Installed)
	if serviceStatus.UnitPath != "" {
		fmt.Fprintf(writer, "UNIT PATH\t%s\n", serviceStatus.UnitPath)
	}
	if serviceStatus.LoadState != "" {
		fmt.Fprintf(writer, "LOAD STATE\t%s\n", serviceStatus.LoadState)
	}
	if serviceStatus.ActiveState != "" {
		fmt.Fprintf(writer, "ACTIVE STATE\t%s\n", serviceStatus.ActiveState)
	}
	if serviceStatus.SubState != "" {
		fmt.Fprintf(writer, "SUB STATE\t%s\n", serviceStatus.SubState)
	}
	if serviceStatus.UnitFileState != "" {
		fmt.Fprintf(writer, "DETAIL\t%s\n", serviceStatus.UnitFileState)
	}

	daemonStatus, err := fetchDaemonStatus()
	if err != nil {
		fmt.Fprintf(writer, "DAEMON API\tunreachable (%s)\n", err)
		return writer.Flush()
	}

	fmt.Fprintln(writer, "")
	fmt.Fprintln(writer, "DAEMON")
	fmt.Fprintf(writer, "NAME\t%s\n", daemonStatus.Name)
	fmt.Fprintf(writer, "VERSION\t%s\n", daemonStatus.Version)
	fmt.Fprintf(writer, "LISTEN ADDR\t%s\n", daemonStatus.ListenAddr)
	fmt.Fprintf(writer, "ADMIN ADDR\t%s\n", daemonStatus.AdminListenAddr)
	fmt.Fprintf(writer, "LAN ENABLED\t%t\n", daemonStatus.LANEnabled)
	fmt.Fprintf(writer, "REMOTE MODE\t%s\n", valueOrFallback(daemonStatus.Remote.Mode, "unknown"))
	fmt.Fprintf(writer, "REMOTE SCOPE\t%s\n", valueOrFallback(daemonStatus.Remote.Scope, "unknown"))
	fmt.Fprintf(writer, "PAIRED DEVICES\t%d\n", daemonStatus.PairedDeviceCount)
	fmt.Fprintf(writer, "UPTIME\t%s\n", formatUptime(daemonStatus.UptimeSeconds))
	if daemonStatus.Remote.PublicURL != "" {
		fmt.Fprintf(writer, "PUBLIC URL\t%s\n", daemonStatus.Remote.PublicURL)
	}
	if daemonStatus.Remote.Warning != "" {
		fmt.Fprintf(writer, "REMOTE WARNING\t%s\n", daemonStatus.Remote.Warning)
	}
	if daemonStatus.PairingTarget.Reachable {
		fmt.Fprintf(writer, "PAIRING TARGET\t%s://%s\n", daemonStatus.PairingTarget.Scheme, daemonStatus.PairingTarget.Host)
	} else {
		fmt.Fprintf(writer, "PAIRING TARGET\tunavailable\n")
		fmt.Fprintf(writer, "PAIRING ERROR\t%s\n", valueOrFallback(daemonStatus.PairingTarget.Error, "unknown"))
	}
	if daemonStatus.ActivePairing != nil {
		fmt.Fprintf(writer, "ACTIVE PAIRING\t%s\n", daemonStatus.ActivePairing.State)
		fmt.Fprintf(writer, "PAIRING CODE\t%s\n", daemonStatus.ActivePairing.Code)
		if !daemonStatus.ActivePairing.ExpiresAt.IsZero() {
			fmt.Fprintf(writer, "PAIRING EXPIRES\t%s\n", daemonStatus.ActivePairing.ExpiresAt.Local().Format(time.RFC3339))
		}
	}

	return writer.Flush()
}

func fetchDaemonStatus() (adminclient.DaemonStatus, error) {
	client := adminclient.New(config.Load())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	status, err := client.Status(ctx)
	if err != nil {
		return adminclient.DaemonStatus{}, fmt.Errorf("read daemon status: %w", err)
	}

	return status, nil
}
