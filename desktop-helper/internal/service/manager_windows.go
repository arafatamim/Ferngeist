//go:build windows

package service

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	windowsTaskName = "FerngeistDaemon"
)

type windowsManager struct{}

func newOSManager() Manager {
	return &windowsManager{}
}

func (m *windowsManager) Install(options InstallOptions) error {
	options = NormalizeInstallOptions(options)
	if err := ValidateInstallOptions(options); err != nil {
		return err
	}
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return err
	}

	paths, err := resolveWindowsPaths()
	if err != nil {
		return err
	}

	if err := os.MkdirAll(paths.serviceBinDir, 0o755); err != nil {
		return fmt.Errorf("create service bin directory: %w", err)
	}
	if err := os.MkdirAll(paths.serviceScriptsDir, 0o755); err != nil {
		return fmt.Errorf("create service scripts directory: %w", err)
	}
	if err := os.MkdirAll(paths.serviceConfigDir, 0o755); err != nil {
		return fmt.Errorf("create service config directory: %w", err)
	}
	if err := os.MkdirAll(paths.dataLogsDir, 0o755); err != nil {
		return fmt.Errorf("create daemon log directory: %w", err)
	}
	if err := os.MkdirAll(paths.dataManagedBinDir, 0o755); err != nil {
		return fmt.Errorf("create managed bin directory: %w", err)
	}

	if err := copyCurrentBinaryWindows(paths.binaryPath); err != nil {
		return err
	}
	if err := writeWindowsWrapperScript(paths, options); err != nil {
		return err
	}
	if err := writeWindowsOverridesTemplate(paths); err != nil {
		return err
	}

	action := fmt.Sprintf(
		"powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"%s\"",
		paths.wrapperScriptPath,
	)

	if err := m.schtasks("/Create", "/TN", windowsTaskName, "/SC", "ONLOGON", "/TR", action, "/RL", "LIMITED", "/F"); err != nil {
		return err
	}
	if err := m.schtasks("/Run", "/TN", windowsTaskName); err != nil {
		if !isTaskAlreadyRunning(err) {
			return err
		}
	}

	return nil
}

func (m *windowsManager) Uninstall(purge bool) error {
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return err
	}

	paths, err := resolveWindowsPaths()
	if err != nil {
		return err
	}

	if err := m.schtasks("/End", "/TN", windowsTaskName); err != nil {
		if !isTaskNotFound(err) && !isTaskNotRunning(err) {
			return err
		}
	}
	if err := m.schtasks("/Delete", "/TN", windowsTaskName, "/F"); err != nil {
		if !isTaskNotFound(err) {
			return err
		}
	}

	if err := os.RemoveAll(paths.serviceDir); err != nil {
		return fmt.Errorf("remove daemon service files: %w", err)
	}

	if purge {
		if err := os.RemoveAll(paths.rootDir); err != nil {
			return fmt.Errorf("purge daemon service data: %w", err)
		}
	}

	return nil
}

func (m *windowsManager) Start() error {
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	if err := m.schtasks("/Run", "/TN", windowsTaskName); err != nil {
		if !isTaskAlreadyRunning(err) {
			return err
		}
	}
	return nil
}

func (m *windowsManager) Stop() error {
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	if err := m.schtasks("/End", "/TN", windowsTaskName); err != nil {
		if !isTaskNotRunning(err) {
			return err
		}
	}
	return nil
}

func (m *windowsManager) Restart() error {
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return err
	}
	if err := m.ensureInstalled(); err != nil {
		return err
	}
	if err := m.schtasks("/End", "/TN", windowsTaskName); err != nil {
		if !isTaskNotRunning(err) {
			return err
		}
	}
	if err := m.schtasks("/Run", "/TN", windowsTaskName); err != nil {
		if !isTaskAlreadyRunning(err) {
			return err
		}
	}
	return nil
}

func (m *windowsManager) Status() (Status, error) {
	if err := m.ensureTaskSchedulerAvailable(); err != nil {
		return Status{}, err
	}

	paths, err := resolveWindowsPaths()
	if err != nil {
		return Status{}, err
	}

	out, err := m.schtasksOutput("/Query", "/TN", windowsTaskName, "/FO", "LIST", "/V")
	if err != nil {
		if isTaskNotFound(err) {
			return Status{Installed: false, UnitPath: windowsTaskName}, nil
		}
		return Status{}, err
	}

	status := Status{
		Installed: true,
		UnitPath:  windowsTaskName,
		LoadState: "loaded",
	}

	for line := range strings.SplitSeq(out, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(strings.ToLower(trimmed), "status:") {
			value := strings.TrimSpace(strings.TrimPrefix(trimmed, "Status:"))
			if value != "" {
				status.ActiveState = strings.ToLower(value)
			}
		}
		if strings.HasPrefix(strings.ToLower(trimmed), "scheduled task state:") {
			value := strings.TrimSpace(strings.TrimPrefix(trimmed, "Scheduled Task State:"))
			if value != "" {
				status.SubState = strings.ToLower(value)
			}
		}
	}

	if status.ActiveState == "" {
		status.ActiveState = "unknown"
	}
	if status.SubState == "" {
		status.SubState = "unknown"
	}
	status.UnitFileState = paths.wrapperScriptPath

	return status, nil
}

func (m *windowsManager) ensureTaskSchedulerAvailable() error {
	if _, err := exec.LookPath("schtasks"); err != nil {
		return fmt.Errorf("%w: schtasks is not available", ErrServiceUnsupportedConfig)
	}
	if _, err := exec.LookPath("powershell"); err != nil {
		return fmt.Errorf("%w: powershell is not available", ErrServiceUnsupportedConfig)
	}
	return nil
}

func (m *windowsManager) ensureInstalled() error {
	status, err := m.Status()
	if err != nil {
		return err
	}
	if !status.Installed {
		return ErrServiceNotInstalled
	}
	return nil
}

func (m *windowsManager) schtasks(args ...string) error {
	_, err := m.schtasksOutput(args...)
	return err
}

func (m *windowsManager) schtasksOutput(args ...string) (string, error) {
	cmd := exec.Command("schtasks", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(out))
		if message == "" {
			message = err.Error()
		}
		if isTaskAccessDeniedMessage(message) {
			return "", fmt.Errorf("%w: task scheduler access was denied for the current user", ErrServicePermissionDenied)
		}
		return "", fmt.Errorf("schtasks %s failed: %s", strings.Join(args, " "), message)
	}
	return string(out), nil
}

type windowsPaths struct {
	rootDir            string
	serviceDir         string
	serviceBinDir      string
	serviceScriptsDir  string
	serviceConfigDir   string
	dataDir            string
	dataLogsDir        string
	dataManagedBinDir  string
	binaryPath         string
	wrapperScriptPath  string
	overrideScriptPath string
	daemonLogPath      string
	stateDBPath        string
}

func resolveWindowsPaths() (windowsPaths, error) {
	localAppData := strings.TrimSpace(os.Getenv("LocalAppData"))
	if localAppData == "" {
		home, err := os.UserHomeDir()
		if err != nil || strings.TrimSpace(home) == "" {
			return windowsPaths{}, fmt.Errorf("%w: LocalAppData and home directory are unavailable", ErrServiceUnsupportedConfig)
		}
		localAppData = filepath.Join(home, "AppData", "Local")
	}

	rootDir := filepath.Join(localAppData, "FerngeistDaemon")
	serviceDir := filepath.Join(rootDir, "service")
	dataDir := filepath.Join(rootDir, "data")

	return windowsPaths{
		rootDir:            rootDir,
		serviceDir:         serviceDir,
		serviceBinDir:      filepath.Join(serviceDir, "bin"),
		serviceScriptsDir:  filepath.Join(serviceDir, "scripts"),
		serviceConfigDir:   filepath.Join(serviceDir, "config"),
		dataDir:            dataDir,
		dataLogsDir:        filepath.Join(dataDir, "logs"),
		dataManagedBinDir:  filepath.Join(dataDir, "managed-bin"),
		binaryPath:         filepath.Join(serviceDir, "bin", "ferngeist.exe"),
		wrapperScriptPath:  filepath.Join(serviceDir, "scripts", "run-ferngeist-daemon.ps1"),
		overrideScriptPath: filepath.Join(serviceDir, "config", "daemon-overrides.ps1"),
		daemonLogPath:      filepath.Join(dataDir, "logs", "daemon.log"),
		stateDBPath:        filepath.Join(dataDir, "ferngeist-helper.db"),
	}, nil
}

func copyCurrentBinaryWindows(targetPath string) error {
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

func writeWindowsWrapperScript(paths windowsPaths, options InstallOptions) error {
	options = NormalizeInstallOptions(options)
	listenAddr := ListenAddr(options)
	enableLAN := "0"
	if !isLoopbackHost(options.Host) {
		enableLAN = "1"
	}
	publicURLLine := ""
	if options.PublicURL != "" {
		publicURLLine = "$env:FERNGEIST_HELPER_PUBLIC_BASE_URL = '" + escapePowerShellSingleQuoted(options.PublicURL) + "'"
	}

	content := fmt.Sprintf(
		`$ErrorActionPreference = "Stop"

$binaryPath = '%s'
$overrideScriptPath = '%s'
$daemonLogPath = '%s'
$stateDBPath = '%s'
$logDir = '%s'
$managedBinDir = '%s'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $managedBinDir | Out-Null

$env:FERNGEIST_HELPER_STATE_DB = $stateDBPath
$env:FERNGEIST_HELPER_LOG_DIR = $logDir
$env:FERNGEIST_HELPER_MANAGED_BIN_DIR = $managedBinDir
$env:FERNGEIST_HELPER_LISTEN_ADDR = '%s'
$env:FERNGEIST_HELPER_ENABLE_LAN = '%s'
%s

if (Test-Path $overrideScriptPath) {
    . $overrideScriptPath
}

& $binaryPath daemon run *>> $daemonLogPath
`,
		escapePowerShellSingleQuoted(paths.binaryPath),
		escapePowerShellSingleQuoted(paths.overrideScriptPath),
		escapePowerShellSingleQuoted(paths.daemonLogPath),
		escapePowerShellSingleQuoted(paths.stateDBPath),
		escapePowerShellSingleQuoted(paths.dataLogsDir),
		escapePowerShellSingleQuoted(paths.dataManagedBinDir),
		escapePowerShellSingleQuoted(listenAddr),
		escapePowerShellSingleQuoted(enableLAN),
		publicURLLine,
	)

	if err := os.WriteFile(paths.wrapperScriptPath, []byte(content), 0o644); err != nil {
		return fmt.Errorf("write daemon wrapper script: %w", err)
	}
	return nil
}

func writeWindowsOverridesTemplate(paths windowsPaths) error {
	_, err := os.Stat(paths.overrideScriptPath)
	if err == nil {
		return nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("read daemon override script: %w", err)
	}

	content := []byte(`# Optional daemon runtime overrides.
# Uncomment and edit as needed.
# $env:FERNGEIST_HELPER_ENABLE_LAN = "1"
# $env:FERNGEIST_HELPER_LISTEN_ADDR = "0.0.0.0:5788"
# $env:FERNGEIST_HELPER_PUBLIC_BASE_URL = "https://example.com"
`)

	if err := os.WriteFile(paths.overrideScriptPath, content, 0o644); err != nil {
		return fmt.Errorf("write daemon override script: %w", err)
	}
	return nil
}

func escapePowerShellSingleQuoted(value string) string {
	return strings.ReplaceAll(value, "'", "''")
}

func isTaskNotFound(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "cannot find the file specified") ||
		strings.Contains(message, "cannot find the task") ||
		strings.Contains(message, "task does not exist")
}

func isTaskNotRunning(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "not currently running")
}

func isTaskAlreadyRunning(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "already running")
}

func isTaskAccessDeniedMessage(message string) bool {
	lower := strings.ToLower(strings.TrimSpace(message))
	return strings.Contains(lower, "access is denied")
}
