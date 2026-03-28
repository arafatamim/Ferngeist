package gateway

import (
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestValidateRegisteredRuntimeToken(t *testing.T) {
	service := New(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return now }

	service.Register(runtime.ConnectDescriptor{
		RuntimeID:      "runtime-1",
		BearerToken:    "token-1",
		TokenExpiresAt: now.Add(time.Minute),
	})

	if err := service.Validate("runtime-1", "token-1"); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRuntimeTokenFromStoreAndRevoke(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("storage.Open() error = %v", err)
	}
	defer store.Close()

	service := New(slog.New(slog.NewTextHandler(io.Discard, nil)), store)
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return now }

	service.Register(runtime.ConnectDescriptor{
		RuntimeID:      "runtime-1",
		BearerToken:    "token-1",
		TokenExpiresAt: now.Add(time.Minute),
	})

	if err := service.Validate("runtime-1", "token-1"); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}

	service.Revoke("runtime-1")

	if err := service.Validate("runtime-1", "token-1"); err != ErrRuntimeTokenInvalid {
		t.Fatalf("Validate() after revoke error = %v, want %v", err, ErrRuntimeTokenInvalid)
	}
}
