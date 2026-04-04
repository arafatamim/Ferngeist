package pairing

import (
	"context"
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
	now := time.Now().UTC().Add(-1 * time.Hour)

	service := NewService(logger, store)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairingWithLocalApproval()
	if err != nil {
		t.Fatalf("StartPairingWithLocalApproval() error = %v", err)
	}

	credential, err := service.CompletePairingWithProofKey(challenge.ID, challenge.Code, "Pixel 9", "proof-key")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}
	pairings, err := store.ListPairings(context.Background())
	if err != nil {
		t.Fatalf("ListPairings() error = %v", err)
	}
	if len(pairings) != 1 {
		t.Fatalf("len(pairings) = %d, want 1", len(pairings))
	}
	if !isHashedCredentialToken(pairings[0].Token) {
		t.Fatalf("stored token = %q, want hashed token", pairings[0].Token)
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
	if !validated.HasScope(ScopeRead) || !validated.HasScope(ScopeControl) {
		t.Fatalf("validated scopes = %v, want baseline scopes", validated.Scopes)
	}
	if validated.ProofPublicKey != "proof-key" {
		t.Fatalf("ProofPublicKey = %q, want %q", validated.ProofPublicKey, "proof-key")
	}
}

func TestRevokedCredentialDoesNotReloadAfterRestart(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("storage.Open() error = %v", err)
	}
	defer store.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	now := time.Now().UTC().Add(-1 * time.Hour)

	service := NewService(logger, store)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairingWithLocalApproval()
	if err != nil {
		t.Fatalf("StartPairingWithLocalApproval() error = %v", err)
	}

	credential, err := service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}

	if _, err := service.RevokeDevice(credential.DeviceID); err != nil {
		t.Fatalf("RevokeDevice() error = %v", err)
	}

	reloaded := NewService(logger, store)
	reloaded.now = func() time.Time { return now }

	if _, err := reloaded.ValidateCredential(credential.Token); err != ErrCredentialInvalid {
		t.Fatalf("ValidateCredential() error = %v, want %v", err, ErrCredentialInvalid)
	}
}

func TestRefreshedCredentialReloadsAfterRestart(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("storage.Open() error = %v", err)
	}
	defer store.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	now := time.Now().UTC().Add(-1 * time.Hour)

	service := NewServiceWithOptions(logger, store, Options{CredentialTTL: 24 * time.Hour})
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairingWithLocalApproval()
	if err != nil {
		t.Fatalf("StartPairingWithLocalApproval() error = %v", err)
	}
	credential, err := service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}

	service.now = func() time.Time { return now.Add(2 * time.Hour) }
	refreshed, err := service.RefreshCredential(credential.Token)
	if err != nil {
		t.Fatalf("RefreshCredential() error = %v", err)
	}

	reloaded := NewServiceWithOptions(logger, store, Options{CredentialTTL: 24 * time.Hour})
	reloaded.now = func() time.Time { return now.Add(2 * time.Hour) }

	if _, err := reloaded.ValidateCredential(credential.Token); err != ErrCredentialInvalid {
		t.Fatalf("ValidateCredential(old token) error = %v, want %v", err, ErrCredentialInvalid)
	}
	validated, err := reloaded.ValidateCredential(refreshed.Token)
	if err != nil {
		t.Fatalf("ValidateCredential(refreshed token) error = %v", err)
	}
	if validated.DeviceID != refreshed.DeviceID {
		t.Fatalf("DeviceID = %q, want %q", validated.DeviceID, refreshed.DeviceID)
	}
}
