package gateway

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

var (
	ErrRuntimeTokenMissing = errors.New("runtime token missing")
	ErrRuntimeTokenInvalid = errors.New("runtime token invalid")
	ErrRuntimeTokenExpired = errors.New("runtime token expired")
)

type registration struct {
	RuntimeID string
	Token     string
	ExpiresAt time.Time
}

type Service struct {
	logger        *slog.Logger
	mu            sync.Mutex
	now           func() time.Time
	registrations map[string]registration
	store         *storage.SQLiteStore
}

// New returns the runtime-token gatekeeper used by the ACP WebSocket endpoint.
// These tokens are narrower than helper credentials: they authorize one ACP
// attach to one runtime for a short window.
func New(logger *slog.Logger, store *storage.SQLiteStore) *Service {
	return &Service{
		logger:        logger.With("component", "gateway"),
		now:           time.Now,
		registrations: make(map[string]registration),
		store:         store,
	}
}

func (s *Service) Register(descriptor runtime.ConnectDescriptor) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.registrations[descriptor.RuntimeID] = registration{
		RuntimeID: descriptor.RuntimeID,
		Token:     descriptor.BearerToken,
		ExpiresAt: descriptor.TokenExpiresAt,
	}
	if s.store != nil {
		if err := s.store.SaveRuntimeToken(context.Background(), storage.RuntimeTokenRecord{
			RuntimeID: descriptor.RuntimeID,
			Token:     descriptor.BearerToken,
			ExpiresAt: descriptor.TokenExpiresAt,
		}); err != nil {
			s.logger.Error("persist runtime token failed", "runtime_id", descriptor.RuntimeID, "error", err)
		}
	}
}

// Validate prefers persisted tokens so helper restarts do not invalidate a
// connect descriptor immediately. The in-memory map is only a fast path.
func (s *Service) Validate(runtimeID, token string) error {
	if token == "" {
		return ErrRuntimeTokenMissing
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	if s.store != nil {
		if err := s.store.DeleteExpiredRuntimeTokens(context.Background(), now); err != nil {
			s.logger.Error("delete expired runtime tokens failed", "error", err)
		}
		record, err := s.store.GetRuntimeToken(context.Background(), runtimeID)
		if err == nil {
			if now.After(record.ExpiresAt) {
				_ = s.store.DeleteRuntimeToken(context.Background(), runtimeID)
				delete(s.registrations, runtimeID)
				return ErrRuntimeTokenExpired
			}
			if record.Token != token {
				return ErrRuntimeTokenInvalid
			}
			return nil
		}
		if !errors.Is(err, storage.ErrNotFound) {
			s.logger.Error("load runtime token failed", "runtime_id", runtimeID, "error", err)
		}
	}

	registration, ok := s.registrations[runtimeID]
	if !ok {
		for id, pending := range s.registrations {
			if now.After(pending.ExpiresAt) {
				delete(s.registrations, id)
			}
		}
		return ErrRuntimeTokenInvalid
	}
	if now.After(registration.ExpiresAt) {
		delete(s.registrations, runtimeID)
		return ErrRuntimeTokenExpired
	}
	if registration.Token != token {
		return ErrRuntimeTokenInvalid
	}

	return nil
}

func (s *Service) Revoke(runtimeID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	delete(s.registrations, runtimeID)
	if s.store != nil {
		if err := s.store.DeleteRuntimeToken(context.Background(), runtimeID); err != nil {
			s.logger.Error("delete runtime token failed", "runtime_id", runtimeID, "error", err)
		}
	}
}

func (s *Service) RevokeIfMatches(runtimeID, token string) bool {
	if token == "" {
		return false
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.store != nil {
		record, err := s.store.GetRuntimeToken(context.Background(), runtimeID)
		switch {
		case err == nil:
			if record.Token != token {
				return false
			}
			delete(s.registrations, runtimeID)
			if err := s.store.DeleteRuntimeToken(context.Background(), runtimeID); err != nil {
				s.logger.Error("delete runtime token failed", "runtime_id", runtimeID, "error", err)
			}
			return true
		case !errors.Is(err, storage.ErrNotFound):
			s.logger.Error("load runtime token failed", "runtime_id", runtimeID, "error", err)
		}
	}

	registration, ok := s.registrations[runtimeID]
	if !ok || registration.Token != token {
		return false
	}
	delete(s.registrations, runtimeID)
	return true
}

func (s *Service) ClearAll() {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.registrations = make(map[string]registration)
	if s.store != nil {
		if err := s.store.DeleteAllRuntimeTokens(context.Background()); err != nil {
			s.logger.Error("delete all runtime tokens failed", "error", err)
		}
	}
}
