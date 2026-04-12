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
	buildVersion = "0.3.0"
	buildCommit  = ""
	buildTime    = ""
)

func main() {
	command := &cli.Command{
		Name:  "ferngeist",
		Usage: "manage the Ferngeist desktop helper",
		Flags: []cli.Flag{
			&cli.BoolFlag{
				Name:    "version",
				Aliases: []string{"v"},
				Usage:   "print version information",
			},
		},
		Action: func(_ context.Context, cmd *cli.Command) error {
			if cmd.Bool("version") {
				printVersion()
				return nil
			}
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
						Flags: []cli.Flag{
							&cli.StringFlag{
								Name:  "host",
								Usage: "host interface for daemon listen address (optional, defaults to 127.0.0.1)",
							},
							&cli.IntFlag{
								Name:  "port",
								Usage: "port for daemon listen address (optional, defaults to 5788)",
								Value: 5788,
							},
							&cli.StringFlag{
								Name:  "public-url",
								Usage: "public base URL announced to clients (optional)",
							},
						},
						Action: func(_ context.Context, cmd *cli.Command) error {
							return runDaemonInstall(service.InstallOptions{
								Host:      cmd.String("host"),
								Port:      cmd.Int("port"),
								PublicURL: cmd.String("public-url"),
							})
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
		renderPairingQRCode(status.Payload)
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

func renderPairingQRCode(payload string) {
	qrterminal.GenerateWithConfig(payload, qrterminal.Config{
		Level:      qrterminal.L,
		Writer:     os.Stdout,
		HalfBlocks: true,
		QuietZone:  2,
	})
}

func printVersion() {
	writer := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintf(writer, "VERSION\t%s\n", valueOrFallback(buildVersion, "dev"))
	fmt.Fprintf(writer, "COMMIT\t%s\n", valueOrFallback(buildCommit, "unknown"))
	fmt.Fprintf(writer, "BUILT AT\t%s\n", valueOrFallback(buildTime, "unknown"))
	fmt.Fprintf(writer, "GO VERSION\t%s\n", goruntime.Version())
	_ = writer.Flush()
}
