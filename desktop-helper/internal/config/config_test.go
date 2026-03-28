package config

import "testing"

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
