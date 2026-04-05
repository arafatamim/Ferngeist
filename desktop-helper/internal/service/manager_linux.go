//go:build linux

package service

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const linuxUnitTemplate = `[Unit]
Description=Ferngeist daemon
After=network-online.target

[Service]
Type=simple
WorkingDirectory=%s
EnvironmentFile=%s
ExecStart=%s daemon run
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
`

type linuxManager struct{}

func newOSManager() Manager {
	return &linuxManager{}
}

func (m *linuxManager) Install() error {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return err
	}

	paths, err := resolveLinuxPaths()
	if err != nil {
		return err
	}

	if err := os.MkdirAll(paths.rootDir, 0o755); err != nil {
		return fmt.Errorf("create service root directory: %w", err)
	}
	if err := os.MkdirAll(paths.binDir, 0o755); err != nil {
		return fmt.Errorf("create service bin directory: %w", err)
	}
	if err := os.MkdirAll(paths.configDir, 0o755); err != nil {
		return fmt.Errorf("create service config directory: %w", err)
	}
	if err := os.MkdirAll(paths.logDir, 0o755); err != nil {
		return fmt.Errorf("create service log directory: %w", err)
	}
	if err := os.MkdirAll(paths.managedBinDir, 0o755); err != nil {
		return fmt.Errorf("create managed bin directory: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(paths.dbPath), 0o755); err != nil {
		return fmt.Errorf("create state db directory: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(paths.unitPath), 0o755); err != nil {
		return fmt.Errorf("create systemd user directory: %w", err)
	}

	if err := copyCurrentBinary(paths.binaryPath); err != nil {
		return err
	}
	if err := writeLinuxEnvFile(paths); err != nil {
		return err
	}
	if err := writeLinuxUnitFile(paths); err != nil {
		return err
	}

	if err := m.systemctl("daemon-reload"); err != nil {
		return err
	}
	if err := m.systemctl("enable", "--now", linuxUnitName); err != nil {
		return err
	}

	return nil
}

func (m *linuxManager) Uninstall(purge bool) error {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return err
	}

	paths, err := resolveLinuxPaths()
	if err != nil {
		return err
	}

	if err := m.systemctl("disable", "--now", linuxUnitName); err != nil {
		if !isSystemctlUnitNotFound(err) {
			return err
		}
	}

	if err := os.Remove(paths.unitPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("remove systemd unit: %w", err)
	}

	if err := m.systemctl("daemon-reload"); err != nil {
		return err
	}

	if purge {
		if err := os.RemoveAll(paths.rootDir); err != nil {
			return fmt.Errorf("purge service data: %w", err)
		}
	}

	return nil
}

func (m *linuxManager) Start() error {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	return m.systemctl("start", linuxUnitName)
}

func (m *linuxManager) Stop() error {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	return m.systemctl("stop", linuxUnitName)
}

func (m *linuxManager) Restart() error {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	return m.systemctl("restart", linuxUnitName)
}

func (m *linuxManager) Status() (Status, error) {
	if err := m.ensureSystemctlAvailable(); err != nil {
		return Status{}, err
	}

	out, err := m.systemctlOutput("show", linuxUnitName, "--property=LoadState,ActiveState,SubState,UnitFileState", "--value")
	if err != nil {
		if isSystemctlUnitNotFound(err) {
			paths, pathErr := resolveLinuxPaths()
			if pathErr != nil {
				return Status{}, pathErr
			}
			return Status{Installed: false, UnitPath: paths.unitPath}, nil
		}
		return Status{}, err
	}

	paths, pathErr := resolveLinuxPaths()
	if pathErr != nil {
		return Status{}, pathErr
	}

	lines := strings.Split(strings.TrimSpace(out), "\n")
	status := Status{Installed: true, UnitPath: paths.unitPath}
	if len(lines) > 0 {
		status.LoadState = strings.TrimSpace(lines[0])
	}
	if len(lines) > 1 {
		status.ActiveState = strings.TrimSpace(lines[1])
	}
	if len(lines) > 2 {
		status.SubState = strings.TrimSpace(lines[2])
	}
	if len(lines) > 3 {
		status.UnitFileState = strings.TrimSpace(lines[3])
	}

	if strings.EqualFold(status.LoadState, "not-found") {
		status.Installed = false
	}

	return status, nil
}

func (m *linuxManager) ensureSystemctlAvailable() error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return fmt.Errorf("%w: systemctl is not available", ErrServiceUnsupportedConfig)
	}

	cmd := exec.Command("systemctl", "--user", "show-environment")
	if out, err := cmd.CombinedOutput(); err != nil {
		message := strings.TrimSpace(string(out))
		if message == "" {
			message = err.Error()
		}
		return fmt.Errorf("%w: %s", ErrServiceUnsupportedConfig, message)
	}

	return nil
}

func (m *linuxManager) ensureInstalled() error {
	status, err := m.Status()
	if err != nil {
		return err
	}
	if !status.Installed {
		return ErrServiceNotInstalled
	}
	return nil
}

func (m *linuxManager) systemctl(args ...string) error {
	_, err := m.systemctlOutput(args...)
	return err
}

func (m *linuxManager) systemctlOutput(args ...string) (string, error) {
	cmd := exec.Command("systemctl", append([]string{"--user"}, args...)...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(out))
		if message == "" {
			message = err.Error()
		}
		return "", fmt.Errorf("systemctl --user %s failed: %s", strings.Join(args, " "), message)
	}
	return string(out), nil
}

type linuxPaths struct {
	rootDir       string
	binDir        string
	configDir     string
	logDir        string
	managedBinDir string
	dbPath        string
	binaryPath    string
	envPath       string
	unitPath      string
}

func resolveLinuxPaths() (linuxPaths, error) {
	home, err := os.UserHomeDir()
	if err != nil || strings.TrimSpace(home) == "" {
		return linuxPaths{}, fmt.Errorf("resolve user home directory: %w", err)
	}

	rootDir := filepath.Join(home, ".local", "share", "ferngeist-daemon")
	unitPath := filepath.Join(home, ".config", "systemd", "user", linuxUnitName)

	return linuxPaths{
		rootDir:       rootDir,
		binDir:        filepath.Join(rootDir, "bin"),
		configDir:     filepath.Join(rootDir, "config"),
		logDir:        filepath.Join(rootDir, "logs"),
		managedBinDir: filepath.Join(rootDir, "managed-bin"),
		dbPath:        filepath.Join(rootDir, "ferngeist-helper.db"),
		binaryPath:    filepath.Join(rootDir, "bin", "ferngeist"),
		envPath:       filepath.Join(rootDir, "config", "daemon.env"),
		unitPath:      unitPath,
	}, nil
}

func copyCurrentBinary(targetPath string) error {
	currentBinaryPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("resolve current binary: %w", err)
	}

	contents, err := os.ReadFile(currentBinaryPath)
	if err != nil {
		return fmt.Errorf("read current binary: %w", err)
	}
	if err := os.WriteFile(targetPath, contents, 0o755); err != nil {
		return fmt.Errorf("write service binary: %w", err)
	}

	return nil
}

func writeLinuxEnvFile(paths linuxPaths) error {
	content := strings.Join([]string{
		"FERNGEIST_HELPER_STATE_DB=" + paths.dbPath,
		"FERNGEIST_HELPER_LOG_DIR=" + paths.logDir,
		"FERNGEIST_HELPER_MANAGED_BIN_DIR=" + paths.managedBinDir,
		"",
	}, "\n")

	if err := os.WriteFile(paths.envPath, []byte(content), 0o644); err != nil {
		return fmt.Errorf("write service environment file: %w", err)
	}

	return nil
}

func writeLinuxUnitFile(paths linuxPaths) error {
	unitBody := fmt.Sprintf(
		linuxUnitTemplate,
		escapeSystemdValue(paths.rootDir),
		escapeSystemdValue(paths.envPath),
		escapeSystemdValue(paths.binaryPath),
	)

	if err := os.WriteFile(paths.unitPath, []byte(unitBody), 0o644); err != nil {
		return fmt.Errorf("write systemd unit file: %w", err)
	}

	return nil
}

func escapeSystemdValue(value string) string {
	return strings.ReplaceAll(value, "%", "%%")
}

func isSystemctlUnitNotFound(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "unit "+linuxUnitName+" could not be found") ||
		strings.Contains(message, "not-found")
}
