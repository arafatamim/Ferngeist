package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	goruntime "runtime"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	qrterminal "github.com/mdp/qrterminal/v3"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/adminclient"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/api"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/daemon"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/service"
	"github.com/urfave/cli/v3"
)

var (
	buildVersion = "dev"
	buildCommit  = ""
	buildTime    = ""
)

func main() {
	command := &cli.Command{
		Name:  "ferngeist",
		Usage: "manage the Ferngeist desktop helper",
		Action: func(_ context.Context, cmd *cli.Command) error {
			cli.ShowSubcommandHelp(cmd)
			return nil
		},
		Commands: []*cli.Command{
			{
				Name:  "daemon",
				Usage: "run the helper daemon",
				Action: func(_ context.Context, cmd *cli.Command) error {
					cli.ShowSubcommandHelp(cmd)
					return nil
				},
				Commands: []*cli.Command{
					{
						Name:  "run",
						Usage: "run the helper daemon in the foreground",
						Flags: []cli.Flag{
							&cli.BoolFlag{
								Name:  "lan",
								Usage: "expose the helper on the local network",
							},
							&cli.StringFlag{
								Name:  "listen-addr",
								Usage: "override the helper public API listen address",
							},
							&cli.StringFlag{
								Name:  "public-base-url",
								Usage: "advertise a public helper URL for pairing",
							},
						},
						Action: func(_ context.Context, cmd *cli.Command) error {
							return runDaemon(cmd.Bool("lan"), cmd.String("listen-addr"), cmd.String("public-base-url"))
						},
					},
					{
						Name:  "install",
						Usage: "install and start the daemon as a user service",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDaemonInstall()
						},
					},
					{
						Name:  "uninstall",
						Usage: "uninstall the daemon user service",
						Flags: []cli.Flag{
							&cli.BoolFlag{
								Name:  "purge",
								Usage: "also remove daemon data, logs, and managed binaries",
							},
						},
						Action: func(_ context.Context, cmd *cli.Command) error {
							return runDaemonUninstall(cmd.Bool("purge"))
						},
					},
					{
						Name:  "start",
						Usage: "start the installed daemon user service",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDaemonStart()
						},
					},
					{
						Name:  "stop",
						Usage: "stop the installed daemon user service",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDaemonStop()
						},
					},
					{
						Name:  "restart",
						Usage: "restart the installed daemon user service",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDaemonRestart()
						},
					},
					{
						Name:  "status",
						Usage: "show daemon service state and API reachability",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDaemonStatus()
						},
					},
				},
			},
			{
				Name:  "pair",
				Usage: "start an interactive pairing flow",
				Action: func(_ context.Context, _ *cli.Command) error {
					return runPair()
				},
			},
			{
				Name:  "devices",
				Usage: "manage paired devices",
				Action: func(_ context.Context, cmd *cli.Command) error {
					cli.ShowSubcommandHelp(cmd)
					return nil
				},
				Commands: []*cli.Command{
					{
						Name:  "list",
						Usage: "list paired devices",
						Action: func(_ context.Context, _ *cli.Command) error {
							return runDevicesList()
						},
					},
					{
						Name:      "revoke",
						Usage:     "revoke one paired device",
						ArgsUsage: "<device-id>",
						Action: func(_ context.Context, cmd *cli.Command) error {
							if cmd.Args().Len() != 1 {
								return fmt.Errorf("usage: ferngeist devices revoke <device-id>")
							}
							return runDevicesRevoke(cmd.Args().First())
						},
					},
				},
			},
		},
	}

	if err := command.Run(context.Background(), os.Args); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runDaemon(enableLAN bool, listenAddr string, publicBaseURL string) error {
	applyDaemonRunOverrides(enableLAN, listenAddr, publicBaseURL)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	return daemon.Run(ctx, api.BuildInfo{
		Version:   buildVersion,
		Commit:    buildCommit,
		BuiltAt:   buildTime,
		GoVersion: goruntime.Version(),
	})
}

func applyDaemonRunOverrides(enableLAN bool, listenAddr string, publicBaseURL string) {
	if enableLAN {
		_ = os.Setenv("FERNGEIST_HELPER_ENABLE_LAN", "1")
		if strings.TrimSpace(listenAddr) == "" {
			if _, hasListenAddr := os.LookupEnv("FERNGEIST_HELPER_LISTEN_ADDR"); !hasListenAddr {
				listenAddr = "0.0.0.0:5788"
			}
		}
	}
	if strings.TrimSpace(listenAddr) != "" {
		_ = os.Setenv("FERNGEIST_HELPER_LISTEN_ADDR", strings.TrimSpace(listenAddr))
	}
	if strings.TrimSpace(publicBaseURL) != "" {
		_ = os.Setenv("FERNGEIST_HELPER_PUBLIC_BASE_URL", strings.TrimSpace(publicBaseURL))
	}
}

func runPair() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client := adminclient.New(config.Load())
	status, err := client.StartPairing(ctx)
	if err != nil {
		return fmt.Errorf("start pairing: %w", err)
	}

	fmt.Println("Ferngeist pairing started")
	fmt.Printf("Code: %s\n", status.Code)
	if !status.ExpiresAt.IsZero() {
		fmt.Printf("Expires at: %s\n", status.ExpiresAt.Local().Format(time.RFC3339))
	}
	if strings.TrimSpace(status.Payload) != "" {
		fmt.Println()
		qrterminal.Generate(status.Payload, qrterminal.L, os.Stdout)
		fmt.Println("Payload:")
		fmt.Println(status.Payload)
	}
	if status.Host != "" {
		fmt.Printf("Target: %s://%s\n", status.Scheme, status.Host)
	}
	fmt.Println()
	fmt.Println("Waiting for Ferngeist Android to pair. Press Ctrl-C to cancel.")

	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			cancelCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_, _ = client.CancelPairing(cancelCtx, status.ChallengeID)
			return errors.New("pairing cancelled")
		case <-ticker.C:
			current, err := client.GetPairing(ctx, status.ChallengeID)
			if err != nil {
				return fmt.Errorf("read pairing status: %w", err)
			}
			switch current.State {
			case "active":
				continue
			case "completed":
				fmt.Printf("Paired device: %s (%s)\n", current.CompletedDevice, current.CompletedDeviceID)
				return nil
			case "cancelled":
				return errors.New("pairing cancelled")
			case "expired":
				return errors.New("pairing expired")
			default:
				return fmt.Errorf("unexpected pairing state: %s", current.State)
			}
		}
	}
}

func runDaemonInstall() error {
	manager := service.NewManager()
	if err := manager.Install(); err != nil {
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
		fmt.Fprintf(writer, "UNIT FILE\t%s\n", serviceStatus.UnitFileState)
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

func runDevicesList() error {
	client := adminclient.New(config.Load())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	devices, err := client.ListDevices(ctx)
	if err != nil {
		return fmt.Errorf("list devices: %w", err)
	}
	if len(devices) == 0 {
		fmt.Println("No paired devices.")
		return nil
	}

	writer := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(writer, "DEVICE ID\tDEVICE NAME\tEXPIRES AT")
	for _, device := range devices {
		fmt.Fprintf(writer, "%s\t%s\t%s\n", device.DeviceID, device.DeviceName, device.ExpiresAt.Local().Format(time.RFC3339))
	}
	return writer.Flush()
}

func runDevicesRevoke(deviceID string) error {
	client := adminclient.New(config.Load())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	device, err := client.RevokeDevice(ctx, deviceID)
	if err != nil {
		return fmt.Errorf("revoke device: %w", err)
	}
	fmt.Printf("Revoked device: %s (%s)\n", device.DeviceName, device.DeviceID)
	return nil
}

func valueOrFallback(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return value
}

func formatUptime(seconds int64) string {
	if seconds <= 0 {
		return "0s"
	}
	return (time.Duration(seconds) * time.Second).String()
}
