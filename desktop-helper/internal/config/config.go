package config

import (
	"os"
	"path/filepath"
	goruntime "runtime"
	"strconv"
	"strings"
	"time"
)

const (
	defaultListenAddr            = "127.0.0.1:5788"
	defaultAdminAddr             = "127.0.0.1:5789"
	defaultLogLevel              = "info"
	defaultLogDir                = "logs"
	defaultLogMaxSize            = int64(1024 * 1024)
	defaultLogBackups            = 3
	defaultPairingArmTTL         = 2 * time.Minute
	defaultPairingLockoutWindow  = 2 * time.Minute
	defaultPairingStartRefill    = 5 * time.Second
	defaultPairingCompleteRefill = 2 * time.Second
	defaultPairingMaxAttempts    = 5
	defaultPairingBurstPerIP     = 5
	defaultPairingBurstGlobal    = 30
	defaultCredentialTTL         = 7 * 24 * time.Hour
)

// Config is the daemon's effective runtime configuration after environment
// variables and persisted settings have been merged.
type Config struct {
	ListenAddr                   string
	AdminListenAddr              string
	LogLevel                     string
	LogDir                       string
	LogMaxSize                   int64
	LogMaxBackups                int
	RegistryURL                  string
	PublicBaseURL                string
	EnableLAN                    bool
	StateDBPath                  string
	HelperName                   string
	ManagedBinDir                string
	PairingArmTTL                time.Duration
	PairingMaxAttempts           int
	PairingLockoutWindow         time.Duration
	PairingStartRefill           time.Duration
	PairingCompleteRefill        time.Duration
	PairingBurstPerIP            int
	PairingBurstGlobal           int
	CredentialTTL                time.Duration
	AllowDiagnosticsExport       bool
	AllowRuntimeRestartEnv       bool
	RequireProofOfPossession     bool
	AllowLegacyBearerCredentials bool
}

type PersistedSettings struct {
	RegistryURL   string
	PublicBaseURL string
	EnableLAN     *bool
	HelperName    string
}

// Load reads environment-driven configuration only. Persisted settings are
// applied later so explicit env vars always remain the strongest override.
func Load() Config {
	helperName := envOrDefault("FERNGEIST_HELPER_NAME", hostnameOrDefault("ferngeist-helper"))
	cfg := Config{
		ListenAddr:             envOrDefault("FERNGEIST_HELPER_LISTEN_ADDR", defaultListenAddr),
		AdminListenAddr:        envOrDefault("FERNGEIST_HELPER_ADMIN_ADDR", defaultAdminAddr),
		LogLevel:               envOrDefault("FERNGEIST_HELPER_LOG_LEVEL", defaultLogLevel),
		LogDir:                 envOrDefault("FERNGEIST_HELPER_LOG_DIR", defaultLogDir),
		LogMaxSize:             envInt64OrDefault("FERNGEIST_HELPER_LOG_MAX_BYTES", defaultLogMaxSize),
		LogMaxBackups:          envIntOrDefault("FERNGEIST_HELPER_LOG_MAX_BACKUPS", defaultLogBackups),
		RegistryURL:            envOrDefault("FERNGEIST_HELPER_REGISTRY_URL", "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"),
		PublicBaseURL:          strings.TrimSpace(os.Getenv("FERNGEIST_HELPER_PUBLIC_BASE_URL")),
		EnableLAN:              os.Getenv("FERNGEIST_HELPER_ENABLE_LAN") == "1",
		StateDBPath:            envOrDefault("FERNGEIST_HELPER_STATE_DB", "ferngeist-helper.db"),
		HelperName:             helperName,
		ManagedBinDir:          envOrDefault("FERNGEIST_HELPER_MANAGED_BIN_DIR", defaultManagedBinDir()),
		PairingArmTTL:          envDurationSecondsOrDefault("FERNGEIST_HELPER_PAIRING_ARM_TTL_SECONDS", defaultPairingArmTTL),
		PairingMaxAttempts:     envIntOrDefault("FERNGEIST_HELPER_PAIRING_MAX_ATTEMPTS", defaultPairingMaxAttempts),
		PairingLockoutWindow:   envDurationSecondsOrDefault("FERNGEIST_HELPER_PAIRING_LOCKOUT_SECONDS", defaultPairingLockoutWindow),
		PairingStartRefill:     envDurationSecondsOrDefault("FERNGEIST_HELPER_PAIRING_START_REFILL_SECONDS", defaultPairingStartRefill),
		PairingCompleteRefill:  envDurationSecondsOrDefault("FERNGEIST_HELPER_PAIRING_COMPLETE_REFILL_SECONDS", defaultPairingCompleteRefill),
		PairingBurstPerIP:      envIntOrDefault("FERNGEIST_HELPER_PAIRING_BURST_PER_IP", defaultPairingBurstPerIP),
		PairingBurstGlobal:     envIntOrDefault("FERNGEIST_HELPER_PAIRING_BURST_GLOBAL", defaultPairingBurstGlobal),
		CredentialTTL:          envDurationSecondsOrDefault("FERNGEIST_HELPER_CREDENTIAL_TTL_SECONDS", defaultCredentialTTL),
		AllowDiagnosticsExport: envBool("FERNGEIST_HELPER_ALLOW_REMOTE_DIAGNOSTICS_EXPORT"),
		AllowRuntimeRestartEnv: envBool("FERNGEIST_HELPER_ALLOW_REMOTE_RUNTIME_RESTART_ENV"),
	}
	return cfg.applySecurityDefaults()
}

// ApplyPersistedSettings fills only fields that were not set explicitly in the
// process environment.
func (c Config) ApplyPersistedSettings(settings PersistedSettings) Config {
	if !hasEnv("FERNGEIST_HELPER_REGISTRY_URL") && strings.TrimSpace(settings.RegistryURL) != "" {
		c.RegistryURL = strings.TrimSpace(settings.RegistryURL)
	}
	if !hasEnv("FERNGEIST_HELPER_PUBLIC_BASE_URL") && strings.TrimSpace(settings.PublicBaseURL) != "" {
		c.PublicBaseURL = strings.TrimSpace(settings.PublicBaseURL)
	}
	if !hasEnv("FERNGEIST_HELPER_ENABLE_LAN") && settings.EnableLAN != nil {
		c.EnableLAN = *settings.EnableLAN
	}
	if !hasEnv("FERNGEIST_HELPER_NAME") && strings.TrimSpace(settings.HelperName) != "" {
		c.HelperName = strings.TrimSpace(settings.HelperName)
	}
	return c.applySecurityDefaults()
}

func (c Config) applySecurityDefaults() Config {
	publicMode := strings.TrimSpace(c.PublicBaseURL) != ""
	if !hasEnv("FERNGEIST_HELPER_REQUIRE_PROOF_OF_POSSESSION") {
		c.RequireProofOfPossession = publicMode
	} else {
		c.RequireProofOfPossession = envBool("FERNGEIST_HELPER_REQUIRE_PROOF_OF_POSSESSION")
	}
	if !hasEnv("FERNGEIST_HELPER_ALLOW_LEGACY_BEARER_CREDENTIALS") {
		c.AllowLegacyBearerCredentials = !publicMode
	} else {
		c.AllowLegacyBearerCredentials = envBool("FERNGEIST_HELPER_ALLOW_LEGACY_BEARER_CREDENTIALS")
	}
	return c
}

func envOrDefault(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

func hasEnv(key string) bool {
	_, ok := os.LookupEnv(key)
	return ok
}

func hostnameOrDefault(fallback string) string {
	name, err := os.Hostname()
	if err != nil || name == "" {
		return fallback
	}
	return name
}

func envInt64OrDefault(key string, fallback int64) int64 {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}

	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func envIntOrDefault(key string, fallback int) int {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}

	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func envDurationSecondsOrDefault(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return time.Duration(parsed) * time.Second
}

func envBool(key string) bool {
	value := strings.TrimSpace(strings.ToLower(os.Getenv(key)))
	return value == "1" || value == "true" || value == "yes"
}

func defaultManagedBinDir() string {
	home, err := os.UserHomeDir()
	if err != nil || strings.TrimSpace(home) == "" {
		return "managed-bin"
	}

	switch goruntime.GOOS {
	case "windows":
		if localAppData := strings.TrimSpace(os.Getenv("LocalAppData")); localAppData != "" {
			return filepath.Join(localAppData, "FerngeistHelper", "bin")
		}
		return filepath.Join(home, "AppData", "Local", "FerngeistHelper", "bin")
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Ferngeist Helper", "bin")
	default:
		return filepath.Join(home, ".local", "share", "ferngeist-helper", "bin")
	}
}
