package config

import (
	"os"
	"path/filepath"
	goruntime "runtime"
	"strconv"
	"strings"
)

const (
	defaultListenAddr = "127.0.0.1:5788"
	defaultLogLevel   = "info"
	defaultLogDir     = "logs"
	defaultLogMaxSize = int64(1024 * 1024)
	defaultLogBackups = 3
)

// Config is the daemon's effective runtime configuration after environment
// variables and persisted settings have been merged.
type Config struct {
	ListenAddr    string
	LogLevel      string
	LogDir        string
	LogMaxSize    int64
	LogMaxBackups int
	RegistryURL   string
	PublicBaseURL string
	EnableLAN     bool
	StateDBPath   string
	HelperName    string
	ManagedBinDir string
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
	return Config{
		ListenAddr:    envOrDefault("FERNGEIST_HELPER_LISTEN_ADDR", defaultListenAddr),
		LogLevel:      envOrDefault("FERNGEIST_HELPER_LOG_LEVEL", defaultLogLevel),
		LogDir:        envOrDefault("FERNGEIST_HELPER_LOG_DIR", defaultLogDir),
		LogMaxSize:    envInt64OrDefault("FERNGEIST_HELPER_LOG_MAX_BYTES", defaultLogMaxSize),
		LogMaxBackups: envIntOrDefault("FERNGEIST_HELPER_LOG_MAX_BACKUPS", defaultLogBackups),
		RegistryURL:   envOrDefault("FERNGEIST_HELPER_REGISTRY_URL", "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"),
		PublicBaseURL: strings.TrimSpace(os.Getenv("FERNGEIST_HELPER_PUBLIC_BASE_URL")),
		EnableLAN:     os.Getenv("FERNGEIST_HELPER_ENABLE_LAN") == "1",
		StateDBPath:   envOrDefault("FERNGEIST_HELPER_STATE_DB", "ferngeist-helper.db"),
		HelperName:    helperName,
		ManagedBinDir: envOrDefault("FERNGEIST_HELPER_MANAGED_BIN_DIR", defaultManagedBinDir()),
	}
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
