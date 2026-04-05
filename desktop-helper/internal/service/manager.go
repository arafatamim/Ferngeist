package service

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
)

var (
	ErrServiceUnsupportedOS     = errors.New("daemon service management is unsupported on this operating system")
	ErrServiceUnsupportedConfig = errors.New("daemon service management is unsupported in this environment")
	ErrServicePermissionDenied  = errors.New("insufficient permissions to manage daemon service")
	ErrInvalidInstallOptions    = errors.New("invalid daemon install options")
	ErrServiceNotInstalled      = errors.New("daemon service is not installed")
)

const (
	defaultInstallHost = "127.0.0.1"
	defaultInstallPort = 5788
)

const linuxUnitName = "ferngeist-daemon.service"

type Status struct {
	Installed     bool
	LoadState     string
	ActiveState   string
	SubState      string
	UnitFileState string
	UnitPath      string
}

type InstallOptions struct {
	Host      string
	Port      int
	PublicURL string
}

type Manager interface {
	Install(options InstallOptions) error
	Uninstall(purge bool) error
	Start() error
	Stop() error
	Restart() error
	Status() (Status, error)
}

func NewManager() Manager {
	return newOSManager()
}

type unsupportedManager struct {
	err error
}

func (m unsupportedManager) Install(_ InstallOptions) error {
	return m.err
}

func (m unsupportedManager) Uninstall(_ bool) error {
	return m.err
}

func (m unsupportedManager) Start() error {
	return m.err
}

func (m unsupportedManager) Stop() error {
	return m.err
}

func (m unsupportedManager) Restart() error {
	return m.err
}

func (m unsupportedManager) Status() (Status, error) {
	return Status{}, m.err
}

func ValidateInstallOptions(options InstallOptions) error {
	normalized := NormalizeInstallOptions(options)
	host := strings.TrimSpace(normalized.Host)
	if host == "" {
		return fmt.Errorf("%w: host is required", ErrInvalidInstallOptions)
	}
	if normalized.Port < 1 || normalized.Port > 65535 {
		return fmt.Errorf("%w: port must be between 1 and 65535", ErrInvalidInstallOptions)
	}
	return nil
}

func ListenAddr(options InstallOptions) string {
	normalized := NormalizeInstallOptions(options)
	return net.JoinHostPort(strings.TrimSpace(normalized.Host), strconv.Itoa(normalized.Port))
}

func NormalizeInstallOptions(options InstallOptions) InstallOptions {
	host := strings.TrimSpace(options.Host)
	if host == "" {
		host = defaultInstallHost
	}
	port := options.Port
	if port == 0 {
		port = defaultInstallPort
	}

	return InstallOptions{
		Host:      host,
		Port:      port,
		PublicURL: strings.TrimSpace(options.PublicURL),
	}
}

func isLoopbackHost(host string) bool {
	trimmed := strings.TrimSpace(strings.Trim(host, "[]"))
	if strings.EqualFold(trimmed, "localhost") {
		return true
	}
	ip := net.ParseIP(trimmed)
	return ip != nil && ip.IsLoopback()
}
