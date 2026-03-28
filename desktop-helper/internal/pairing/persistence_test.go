package pairing

import (
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestServiceLoadsPersistedCredentials(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("storage.Open() error = %v", err)
	}
	defer store.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)

	service := NewService(logger, store)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	credential, err := service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}

	reloaded := NewService(logger, store)
	reloaded.now = func() time.Time { return now }

	validated, err := reloaded.ValidateCredential(credential.Token)
	if err != nil {
		t.Fatalf("ValidateCredential() error = %v", err)
	}
	if validated.DeviceName != "Pixel 9" {
		t.Fatalf("DeviceName = %q, want %q", validated.DeviceName, "Pixel 9")
	}
}
