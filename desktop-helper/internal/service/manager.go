package service

import (
	"errors"
)

var (
	ErrServiceUnsupportedOS     = errors.New("daemon service management is unsupported on this operating system")
	ErrServiceUnsupportedConfig = errors.New("daemon service management is unsupported in this environment")
	ErrServiceNotInstalled      = errors.New("daemon service is not installed")
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

type Manager interface {
	Install() error
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

func (m unsupportedManager) Install() error {
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
