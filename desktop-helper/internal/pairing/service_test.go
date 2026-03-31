package pairing

import (
	"io"
	"log/slog"
	"testing"
	"time"
)

func TestCompletePairingSuccess(t *testing.T) {
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service := NewService(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	credential, err := service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}

	if credential.DeviceName != "Pixel 9" {
		t.Fatalf("DeviceName = %q, want %q", credential.DeviceName, "Pixel 9")
	}
	if credential.DeviceID == "" {
		t.Fatal("DeviceID should not be empty")
	}
	if credential.Token == "" {
		t.Fatal("Token should not be empty")
	}
	if count := service.ActiveDeviceCount(); count != 1 {
		t.Fatalf("ActiveDeviceCount() = %d, want 1", count)
	}
}

func TestCompletePairingRejectsWrongCode(t *testing.T) {
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service := NewService(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	_, err = service.CompletePairing(challenge.ID, "000000", "Pixel 9")
	if err != ErrCodeMismatch {
		t.Fatalf("CompletePairing() error = %v, want %v", err, ErrCodeMismatch)
	}
}

func TestCompletePairingRejectsExpiredChallenge(t *testing.T) {
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service := NewService(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	service.now = func() time.Time { return now.Add(defaultChallengeTTL + time.Second) }

	_, err = service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != ErrChallengeExpired {
		t.Fatalf("CompletePairing() error = %v, want %v", err, ErrChallengeExpired)
	}
}

func TestCompletePairingAllowsCodeOnlyLookup(t *testing.T) {
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service := NewService(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	credential, err := service.CompletePairing("", challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}
	if credential.DeviceName != "Pixel 9" {
		t.Fatalf("DeviceName = %q, want %q", credential.DeviceName, "Pixel 9")
	}
}

func TestValidateCredentialSuccess(t *testing.T) {
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	service := NewService(slog.New(slog.NewTextHandler(io.Discard, nil)), nil)
	service.now = func() time.Time { return now }

	challenge, err := service.StartPairing()
	if err != nil {
		t.Fatalf("StartPairing() error = %v", err)
	}

	credential, err := service.CompletePairing(challenge.ID, challenge.Code, "Pixel 9")
	if err != nil {
		t.Fatalf("CompletePairing() error = %v", err)
	}

	validated, err := service.ValidateCredential(credential.Token)
	if err != nil {
		t.Fatalf("ValidateCredential() error = %v", err)
	}
	if validated.DeviceID != credential.DeviceID {
		t.Fatalf("DeviceID = %q, want %q", validated.DeviceID, credential.DeviceID)
	}
}
