package discovery

import (
	"fmt"
	"log/slog"
	"sync"

	"github.com/grandcat/zeroconf"
)

const serviceType = "_ferngeist-helper._tcp"

// Snapshot is the helper's last known LAN advertisement state. It is surfaced
// directly in status and diagnostics so discovery problems are debuggable.
type Snapshot struct {
	Active      bool     `json:"active"`
	ServiceName string   `json:"serviceName"`
	ServiceType string   `json:"serviceType"`
	Port        int      `json:"port"`
	TXTRecords  []string `json:"txtRecords,omitempty"`
	LastError   string   `json:"lastError,omitempty"`
}

type Service struct {
	logger   *slog.Logger
	mu       sync.Mutex
	server   *zeroconf.Server
	snapshot Snapshot
}

func New(logger *slog.Logger) *Service {
	return &Service{
		logger: logger.With("component", "discovery"),
		snapshot: Snapshot{
			ServiceType: serviceType,
		},
	}
}

// Start publishes the helper via mDNS. If the helper is already advertising,
// this is treated as idempotent to simplify startup wiring.
func (s *Service) Start(name string, port int, txt []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.server != nil {
		s.snapshot.Active = true
		return nil
	}

	server, err := zeroconf.Register(name, serviceType, "local.", port, txt, nil)
	if err != nil {
		s.snapshot = Snapshot{
			Active:      false,
			ServiceName: name,
			ServiceType: serviceType,
			Port:        port,
			TXTRecords:  append([]string(nil), txt...),
			LastError:   err.Error(),
		}
		return fmt.Errorf("start discovery: %w", err)
	}

	s.server = server
	s.snapshot = Snapshot{
		Active:      true,
		ServiceName: name,
		ServiceType: serviceType,
		Port:        port,
		TXTRecords:  append([]string(nil), txt...),
	}
	s.logger.Info("mdns discovery started", "service_name", name, "port", port)
	return nil
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.server != nil {
		s.server.Shutdown()
		s.server = nil
	}
	s.snapshot.Active = false
}

func (s *Service) Snapshot() Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.snapshot
}
