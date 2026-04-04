package storage

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestSQLiteStorePersistsPairingsAndRuntimes(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer store.Close()

	ctx := context.Background()
	expiresAt := time.Date(2026, 4, 25, 10, 0, 0, 0, time.UTC)
	if err := store.SavePairing(ctx, PairingRecord{
		DeviceID:       "dev-1",
		DeviceName:     "Pixel 9",
		Token:          "token-1",
		ExpiresAt:      expiresAt,
		Scopes:         []string{"helper.read", "helper.control"},
		ProofPublicKey: "proof-key-1",
	}); err != nil {
		t.Fatalf("SavePairing() error = %v", err)
	}

	if err := store.SaveRuntime(ctx, RuntimeRecord{
		RuntimeID: "run-1",
		AgentID:   "mock-acp",
		AgentName: "Mock ACP",
		Status:    "running",
		Command:   "mock",
		PID:       1234,
		CreatedAt: time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC),
	}); err != nil {
		t.Fatalf("SaveRuntime() error = %v", err)
	}

	pairings, err := store.ListPairings(ctx)
	if err != nil {
		t.Fatalf("ListPairings() error = %v", err)
	}
	if len(pairings) != 1 {
		t.Fatalf("len(pairings) = %d, want 1", len(pairings))
	}
	if pairings[0].Token != "token-1" {
		t.Fatalf("pairing token = %q, want %q", pairings[0].Token, "token-1")
	}
	if len(pairings[0].Scopes) != 2 {
		t.Fatalf("len(pairing scopes) = %d, want 2", len(pairings[0].Scopes))
	}
	if pairings[0].ProofPublicKey != "proof-key-1" {
		t.Fatalf("pairing proof key = %q, want %q", pairings[0].ProofPublicKey, "proof-key-1")
	}

	if err := store.DeletePairing(ctx, "dev-1"); err != nil {
		t.Fatalf("DeletePairing() error = %v", err)
	}
	if err := store.DeletePairing(ctx, "dev-1"); err != ErrNotFound {
		t.Fatalf("DeletePairing() second error = %v, want %v", err, ErrNotFound)
	}

	pairings, err = store.ListPairings(ctx)
	if err != nil {
		t.Fatalf("ListPairings() after delete error = %v", err)
	}
	if len(pairings) != 0 {
		t.Fatalf("len(pairings) after delete = %d, want 0", len(pairings))
	}

	if err := store.SavePairing(ctx, PairingRecord{
		DeviceID:       "dev-1",
		DeviceName:     "Pixel 9",
		Token:          "token-1",
		ExpiresAt:      expiresAt,
		Scopes:         []string{"helper.read"},
		ProofPublicKey: "proof-key-2",
	}); err != nil {
		t.Fatalf("SavePairing() after delete error = %v", err)
	}

	if err := store.UpdateRuntimeStatus(ctx, "run-1", "stopped", "", 0); err != nil {
		t.Fatalf("UpdateRuntimeStatus() error = %v", err)
	}

	runtimes, err := store.ListRuntimes(ctx)
	if err != nil {
		t.Fatalf("ListRuntimes() error = %v", err)
	}
	if len(runtimes) != 1 {
		t.Fatalf("len(runtimes) = %d, want 1", len(runtimes))
	}
	if runtimes[0].Status != "stopped" {
		t.Fatalf("runtime status = %q, want %q", runtimes[0].Status, "stopped")
	}

	tokenExpiry := time.Date(2026, 3, 25, 10, 5, 0, 0, time.UTC)
	if err := store.SaveRuntimeToken(ctx, RuntimeTokenRecord{
		RuntimeID: "run-1",
		Token:     "token-1",
		ExpiresAt: tokenExpiry,
	}); err != nil {
		t.Fatalf("SaveRuntimeToken() error = %v", err)
	}

	tokenRecord, err := store.GetRuntimeToken(ctx, "run-1")
	if err != nil {
		t.Fatalf("GetRuntimeToken() error = %v", err)
	}
	if tokenRecord.Token != "token-1" {
		t.Fatalf("runtime token = %q, want %q", tokenRecord.Token, "token-1")
	}

	if err := store.DeleteRuntimeToken(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteRuntimeToken() error = %v", err)
	}
	if _, err := store.GetRuntimeToken(ctx, "run-1"); err != ErrNotFound {
		t.Fatalf("GetRuntimeToken() after delete error = %v, want %v", err, ErrNotFound)
	}

	failedAt := time.Date(2026, 3, 25, 10, 6, 0, 0, time.UTC)
	if err := store.SaveRuntimeFailure(ctx, RuntimeFailureRecord{
		RuntimeID:  "run-1",
		AgentID:    "mock-acp",
		AgentName:  "Mock ACP",
		LastError:  "process exited with status 1",
		CreatedAt:  time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC),
		FailedAt:   failedAt,
		LogPreview: `[{"timestamp":"2026-03-25T10:05:59Z","stream":"stderr","message":"boom"}]`,
	}); err != nil {
		t.Fatalf("SaveRuntimeFailure() error = %v", err)
	}

	failures, err := store.ListRecentRuntimeFailures(ctx, 5)
	if err != nil {
		t.Fatalf("ListRecentRuntimeFailures() error = %v", err)
	}
	if len(failures) != 1 {
		t.Fatalf("len(failures) = %d, want 1", len(failures))
	}
	if failures[0].FailedAt != failedAt {
		t.Fatalf("failures[0].FailedAt = %v, want %v", failures[0].FailedAt, failedAt)
	}
	if failures[0].LastError != "process exited with status 1" {
		t.Fatalf("failures[0].LastError = %q", failures[0].LastError)
	}

	if err := store.SaveHelperSettings(ctx, HelperSettingsRecord{
		RegistryURL:   "https://stored.example/registry.json",
		PublicBaseURL: "https://helper.example.com",
		EnableLAN:     true,
		HelperName:    "workstation",
	}); err != nil {
		t.Fatalf("SaveHelperSettings() error = %v", err)
	}

	settings, err := store.GetHelperSettings(ctx)
	if err != nil {
		t.Fatalf("GetHelperSettings() error = %v", err)
	}
	if settings.RegistryURL != "https://stored.example/registry.json" {
		t.Fatalf("settings.RegistryURL = %q", settings.RegistryURL)
	}
	if settings.PublicBaseURL != "https://helper.example.com" {
		t.Fatalf("settings.PublicBaseURL = %q", settings.PublicBaseURL)
	}
	if !settings.EnableLAN {
		t.Fatal("settings.EnableLAN should be true")
	}
	if settings.HelperName != "workstation" {
		t.Fatalf("settings.HelperName = %q", settings.HelperName)
	}

	if err := store.SaveAcquiredBinary(ctx, AcquiredBinaryRecord{
		AgentID:     "codex-acp",
		Version:     "0.10.0",
		Path:        "C:/Users/test/AppData/Local/FerngeistHelper/bin/codex-acp.exe",
		ArchiveURL:  "https://example.com/codex.zip",
		InstalledAt: time.Date(2026, 3, 25, 10, 7, 0, 0, time.UTC),
	}); err != nil {
		t.Fatalf("SaveAcquiredBinary() error = %v", err)
	}

	acquired, err := store.GetAcquiredBinary(ctx, "codex-acp")
	if err != nil {
		t.Fatalf("GetAcquiredBinary() error = %v", err)
	}
	if acquired.Path != "C:/Users/test/AppData/Local/FerngeistHelper/bin/codex-acp.exe" {
		t.Fatalf("acquired.Path = %q", acquired.Path)
	}
}
