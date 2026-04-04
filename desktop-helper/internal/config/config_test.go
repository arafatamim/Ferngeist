package config

import (
	"testing"
	"time"
)

func TestApplyPersistedSettingsUsesStoredValuesWhenEnvMissing(t *testing.T) {
	cfg := Config{
		RegistryURL: "https://default.example/registry.json",
		HelperName:  "default-helper",
	}
	enableLAN := true

	updated := cfg.ApplyPersistedSettings(PersistedSettings{
		RegistryURL:   "https://stored.example/registry.json",
		PublicBaseURL: "https://helper.example.com",
		EnableLAN:     &enableLAN,
		HelperName:    "stored-helper",
	})

	if updated.RegistryURL != "https://stored.example/registry.json" {
		t.Fatalf("RegistryURL = %q", updated.RegistryURL)
	}
	if updated.PublicBaseURL != "https://helper.example.com" {
		t.Fatalf("PublicBaseURL = %q", updated.PublicBaseURL)
	}
	if !updated.EnableLAN {
		t.Fatal("EnableLAN should be true from persisted settings")
	}
	if updated.HelperName != "stored-helper" {
		t.Fatalf("HelperName = %q", updated.HelperName)
	}
}

func TestApplyPersistedSettingsKeepsExplicitEnvOverrides(t *testing.T) {
	t.Setenv("FERNGEIST_HELPER_REGISTRY_URL", "https://env.example/registry.json")
	t.Setenv("FERNGEIST_HELPER_PUBLIC_BASE_URL", "https://env.example.com")
	t.Setenv("FERNGEIST_HELPER_ENABLE_LAN", "1")
	t.Setenv("FERNGEIST_HELPER_NAME", "env-helper")

	cfg := Config{
		RegistryURL:   "https://env.example/registry.json",
		PublicBaseURL: "https://env.example.com",
		EnableLAN:     true,
		HelperName:    "env-helper",
	}
	enableLAN := false

	updated := cfg.ApplyPersistedSettings(PersistedSettings{
		RegistryURL:   "https://stored.example/registry.json",
		PublicBaseURL: "https://stored.example.com",
		EnableLAN:     &enableLAN,
		HelperName:    "stored-helper",
	})

	if updated.RegistryURL != cfg.RegistryURL {
		t.Fatalf("RegistryURL = %q, want env value %q", updated.RegistryURL, cfg.RegistryURL)
	}
	if updated.PublicBaseURL != cfg.PublicBaseURL {
		t.Fatalf("PublicBaseURL = %q, want env value %q", updated.PublicBaseURL, cfg.PublicBaseURL)
	}
	if updated.EnableLAN != cfg.EnableLAN {
		t.Fatalf("EnableLAN = %t, want env value %t", updated.EnableLAN, cfg.EnableLAN)
	}
	if updated.HelperName != cfg.HelperName {
		t.Fatalf("HelperName = %q, want env value %q", updated.HelperName, cfg.HelperName)
	}
}

func TestLoadIncludesPairingSecurityDefaults(t *testing.T) {
	cfg := Load()

	if cfg.PairingArmTTL <= 0 {
		t.Fatal("PairingArmTTL should be positive")
	}
	if cfg.PairingMaxAttempts <= 0 {
		t.Fatal("PairingMaxAttempts should be positive")
	}
	if cfg.PairingLockoutWindow <= 0 {
		t.Fatal("PairingLockoutWindow should be positive")
	}
	if cfg.PairingStartRefill <= 0 {
		t.Fatal("PairingStartRefill should be positive")
	}
	if cfg.PairingCompleteRefill <= 0 {
		t.Fatal("PairingCompleteRefill should be positive")
	}
	if cfg.PairingBurstPerIP <= 0 {
		t.Fatal("PairingBurstPerIP should be positive")
	}
	if cfg.PairingBurstGlobal <= 0 {
		t.Fatal("PairingBurstGlobal should be positive")
	}
	if cfg.CredentialTTL <= 0 {
		t.Fatal("CredentialTTL should be positive")
	}
	if cfg.RequireProofOfPossession {
		t.Fatal("RequireProofOfPossession should default to false without public mode")
	}
	if !cfg.AllowLegacyBearerCredentials {
		t.Fatal("AllowLegacyBearerCredentials should default to true without public mode")
	}
}

func TestLoadAppliesPairingSecurityEnvOverrides(t *testing.T) {
	t.Setenv("FERNGEIST_HELPER_PAIRING_ARM_TTL_SECONDS", "45")
	t.Setenv("FERNGEIST_HELPER_PAIRING_MAX_ATTEMPTS", "9")
	t.Setenv("FERNGEIST_HELPER_PAIRING_LOCKOUT_SECONDS", "75")
	t.Setenv("FERNGEIST_HELPER_PAIRING_START_REFILL_SECONDS", "7")
	t.Setenv("FERNGEIST_HELPER_PAIRING_COMPLETE_REFILL_SECONDS", "3")
	t.Setenv("FERNGEIST_HELPER_PAIRING_BURST_PER_IP", "11")
	t.Setenv("FERNGEIST_HELPER_PAIRING_BURST_GLOBAL", "42")
	t.Setenv("FERNGEIST_HELPER_CREDENTIAL_TTL_SECONDS", "86400")
	t.Setenv("FERNGEIST_HELPER_ALLOW_REMOTE_DIAGNOSTICS_EXPORT", "1")
	t.Setenv("FERNGEIST_HELPER_ALLOW_REMOTE_RUNTIME_RESTART_ENV", "true")
	t.Setenv("FERNGEIST_HELPER_REQUIRE_PROOF_OF_POSSESSION", "true")
	t.Setenv("FERNGEIST_HELPER_ALLOW_LEGACY_BEARER_CREDENTIALS", "false")

	cfg := Load()

	if cfg.PairingArmTTL != 45*time.Second {
		t.Fatalf("PairingArmTTL = %s", cfg.PairingArmTTL)
	}
	if cfg.PairingMaxAttempts != 9 {
		t.Fatalf("PairingMaxAttempts = %d", cfg.PairingMaxAttempts)
	}
	if cfg.PairingLockoutWindow != 75*time.Second {
		t.Fatalf("PairingLockoutWindow = %s", cfg.PairingLockoutWindow)
	}
	if cfg.PairingStartRefill != 7*time.Second {
		t.Fatalf("PairingStartRefill = %s", cfg.PairingStartRefill)
	}
	if cfg.PairingCompleteRefill != 3*time.Second {
		t.Fatalf("PairingCompleteRefill = %s", cfg.PairingCompleteRefill)
	}
	if cfg.PairingBurstPerIP != 11 {
		t.Fatalf("PairingBurstPerIP = %d", cfg.PairingBurstPerIP)
	}
	if cfg.PairingBurstGlobal != 42 {
		t.Fatalf("PairingBurstGlobal = %d", cfg.PairingBurstGlobal)
	}
	if cfg.CredentialTTL != 24*time.Hour {
		t.Fatalf("CredentialTTL = %s", cfg.CredentialTTL)
	}
	if !cfg.AllowDiagnosticsExport {
		t.Fatal("AllowDiagnosticsExport should be true")
	}
	if !cfg.AllowRuntimeRestartEnv {
		t.Fatal("AllowRuntimeRestartEnv should be true")
	}
	if !cfg.RequireProofOfPossession {
		t.Fatal("RequireProofOfPossession should be true")
	}
	if cfg.AllowLegacyBearerCredentials {
		t.Fatal("AllowLegacyBearerCredentials should be false")
	}
}

func TestApplyPersistedSettingsDefaultsPublicModeToProofAndNoLegacy(t *testing.T) {
	cfg := Config{}
	updated := cfg.ApplyPersistedSettings(PersistedSettings{PublicBaseURL: "https://helper.example.com"})
	if !updated.RequireProofOfPossession {
		t.Fatal("RequireProofOfPossession should default to true in public mode")
	}
	if updated.AllowLegacyBearerCredentials {
		t.Fatal("AllowLegacyBearerCredentials should default to false in public mode")
	}
}
