package main

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"testing"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestDiscoveryTXTRecordsReflectPairingRequirement(t *testing.T) {
	cfg := config.Config{HelperName: "workstation"}

	unpaired := discoveryTXTRecords(cfg, 0)
	if !containsTXT(unpaired, "helper_name=workstation") {
		t.Fatalf("unpaired TXT records = %v, missing helper name", unpaired)
	}
	if !containsTXT(unpaired, "protocol_version=v1alpha1") {
		t.Fatalf("unpaired TXT records = %v, missing protocol version", unpaired)
	}
	if !containsTXT(unpaired, "pairing_required=true") {
		t.Fatalf("unpaired TXT records = %v, missing pairing_required=true", unpaired)
	}

	paired := discoveryTXTRecords(cfg, 2)
	if !containsTXT(paired, "pairing_required=false") {
		t.Fatalf("paired TXT records = %v, missing pairing_required=false", paired)
	}
}

func containsTXT(records []string, want string) bool {
	for _, record := range records {
		if record == want {
			return true
		}
	}
	return false
}

func TestApplyPersistedSettingsLoadsStoredDefaults(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer store.Close()

	if err := store.SaveHelperSettings(context.Background(), storage.HelperSettingsRecord{
		RegistryURL:   "https://stored.example/registry.json",
		PublicBaseURL: "https://helper.example.com",
		EnableLAN:     true,
		HelperName:    "stored-helper",
	}); err != nil {
		t.Fatalf("SaveHelperSettings() error = %v", err)
	}

	cfg := applyPersistedSettings(
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		store,
		config.Config{
			RegistryURL:   "https://default.example/registry.json",
			PublicBaseURL: "",
			EnableLAN:     false,
			HelperName:    "default-helper",
		},
	)

	if cfg.RegistryURL != "https://stored.example/registry.json" {
		t.Fatalf("RegistryURL = %q", cfg.RegistryURL)
	}
	if cfg.PublicBaseURL != "https://helper.example.com" {
		t.Fatalf("PublicBaseURL = %q", cfg.PublicBaseURL)
	}
	if !cfg.EnableLAN {
		t.Fatal("EnableLAN should be true from stored settings")
	}
	if cfg.HelperName != "stored-helper" {
		t.Fatalf("HelperName = %q", cfg.HelperName)
	}
}
