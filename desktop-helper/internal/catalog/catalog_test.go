package catalog

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	goruntime "runtime"
	"testing"

	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
)

func TestLoadEmbeddedAgents(t *testing.T) {
	agents, err := loadEmbeddedAgents()
	if err != nil {
		t.Fatalf("loadEmbeddedAgents() error = %v", err)
	}
	if len(agents) == 0 {
		t.Fatal("loadEmbeddedAgents() returned no agents")
	}

	found := false
	for _, agent := range agents {
		if agent.ID == "mock-acp" {
			found = true
			if agent.Launch.Command == "" {
				t.Fatal("mock-acp launch command should not be empty")
			}
		}
	}
	if !found {
		t.Fatal("embedded manifests should include mock-acp")
	}
}

func TestMockAgentDetectionDependsOnBinaryPresence(t *testing.T) {
	baseDir := t.TempDir()
	service := NewWithBaseDir(baseDir)

	agent, err := service.Get("mock-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.Detected {
		t.Fatal("mock-acp should not be detected before the binary exists")
	}

	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	mockBinaryPath := filepath.Join(binDir, mockAgentExecutableName())
	if err := os.WriteFile(mockBinaryPath, []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	service.Refresh()
	agent, err = service.Get("mock-acp")
	if err != nil {
		t.Fatalf("Get() second error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("mock-acp should be detected after the binary exists")
	}
	if !agent.ManifestValid {
		t.Fatalf("ManifestValid = false, validation error = %q", agent.ValidationError)
	}
	if agent.Protocol != "acp" {
		t.Fatalf("Protocol = %q, want %q", agent.Protocol, "acp")
	}
	if agent.Detection.Mode != "local_file" {
		t.Fatalf("Detection.Mode = %q, want %q", agent.Detection.Mode, "local_file")
	}
	if !agent.Security.CuratedLaunch {
		t.Fatal("CuratedLaunch should be true for built-in mock agent")
	}
	if agent.Registry.ValidationStatus != "not_required" {
		t.Fatalf("Registry.ValidationStatus = %q, want %q", agent.Registry.ValidationStatus, "not_required")
	}
}

func TestRefreshMarksInvalidManifestAsUndetected(t *testing.T) {
	service := &Service{
		baseDir: t.TempDir(),
		adapters: []Agent{
			{
				ID:              "broken",
				DisplayName:     "Broken Agent",
				Protocol:        "acp",
				PlatformSupport: []string{"windows", "darwin", "linux"},
				Detection: DetectionConfig{
					Mode:    "path_lookup",
					Command: "bad/command",
				},
				Launch: LaunchConfig{
					Mode:    "external",
					Command: "bad/command",
				},
				Security: SecurityConfig{
					CuratedLaunch:     true,
					AllowsRemoteStart: false,
				},
			},
		},
	}

	service.Refresh()

	agent, err := service.Get("broken")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.ManifestValid {
		t.Fatal("ManifestValid should be false for invalid manifest")
	}
	if agent.Detected {
		t.Fatal("Detected should be false for invalid manifest")
	}
	if agent.ValidationError == "" {
		t.Fatal("ValidationError should not be empty")
	}
}

func TestInvalidAdapterFallsBackToRegistryLaunch(t *testing.T) {
	originalPath := os.Getenv("PATH")
	pathDir := t.TempDir()
	npxName := "npx"
	if goruntime.GOOS == "windows" {
		npxName = "npx.exe"
	}
	if err := os.WriteFile(filepath.Join(pathDir, npxName), []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+originalPath)

	service := &Service{
		baseDir: t.TempDir(),
		adapters: []Agent{
			{
				ID:              "codex-acp",
				DisplayName:     "Broken Codex",
				Protocol:        "acp",
				PlatformSupport: []string{"windows", "darwin", "linux"},
				Detection: DetectionConfig{
					Mode:    "path_lookup",
					Command: "bad/command",
				},
				Launch: LaunchConfig{
					Mode:      "external",
					Command:   "bad/command",
					Transport: "stdio",
					Readiness: ReadinessConfig{Mode: "immediate"},
					Restart:   RestartConfig{Mode: "never"},
				},
				HealthCheck: HealthCheckConfig{Mode: "none"},
				Security: SecurityConfig{
					CuratedLaunch: true,
				},
			},
		},
		registry: fakeRegistrySource{
			snapshot: acpregistry.Snapshot{
				Version: "1.0.0",
				Agents: map[string]acpregistry.AgentEntry{
					"codex-acp": {
						ID:                "codex-acp",
						Name:              "Codex CLI",
						Version:           "0.10.0",
						DistributionKinds: []string{"npx"},
						NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					},
				},
			},
		},
	}

	service.Refresh()

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.ManifestValid {
		t.Fatal("ManifestValid should stay false for the broken local adapter")
	}
	if agent.ValidationError == "" {
		t.Fatal("ValidationError should be populated for the broken local adapter")
	}
	if !agent.Detected {
		t.Fatal("registry fallback should still be detected when npx is available")
	}
	if agent.Launch.Command != "npx" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "npx")
	}
	if agent.Detection.Command != "npx" {
		t.Fatalf("Detection.Command = %q, want %q", agent.Detection.Command, "npx")
	}
}

func TestRegistryAgentIsSurfacedAndEnriched(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"codex-acp": {
					ID:                "codex-acp",
					Name:              "Codex CLI",
					Version:           "0.10.0",
					Repository:        "https://github.com/zed-industries/codex-acp",
					DistributionKinds: []string{"binary", "npx"},
					NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					NpxArgs:           []string{"--acp"},
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "codex-acp",
					},
				},
			},
		},
	})

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.ManifestValid {
		t.Fatalf("ManifestValid = false, validation error = %q", agent.ValidationError)
	}
	if agent.Registry.ValidationStatus != "matched" {
		t.Fatalf("Registry.ValidationStatus = %q, want %q", agent.Registry.ValidationStatus, "matched")
	}
	if agent.Registry.Name != "Codex CLI" {
		t.Fatalf("Registry.Name = %q, want %q", agent.Registry.Name, "Codex CLI")
	}
	if len(agent.Registry.DistributionKinds) != 2 {
		t.Fatalf("len(DistributionKinds) = %d, want 2", len(agent.Registry.DistributionKinds))
	}
	if agent.Registry.CurrentBinaryCommand != "codex-acp" {
		t.Fatalf("CurrentBinaryCommand = %q, want %q", agent.Registry.CurrentBinaryCommand, "codex-acp")
	}
	if agent.Registry.NpxPackage != "@zed-industries/codex-acp@0.10.0" {
		t.Fatalf("NpxPackage = %q, want %q", agent.Registry.NpxPackage, "@zed-industries/codex-acp@0.10.0")
	}
	if len(agent.Registry.NpxArgs) != 1 || agent.Registry.NpxArgs[0] != "--acp" {
		t.Fatalf("NpxArgs = %v, want [--acp]", agent.Registry.NpxArgs)
	}
	if len(agent.Registry.UvxArgs) != 0 {
		t.Fatalf("UvxArgs = %v, want []", agent.Registry.UvxArgs)
	}
}

func TestRegistryOnlyAgentMissingFromRegistryIsNotFound(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents:  map[string]acpregistry.AgentEntry{},
		},
	})

	_, err := service.Get("codex-acp")
	if !errors.Is(err, ErrAgentNotFound) {
		t.Fatalf("Get() error = %v, want %v", err, ErrAgentNotFound)
	}
}

func TestRegistryOnlyInstalledAgentIsSurfacedAndLaunchable(t *testing.T) {
	baseDir := t.TempDir()
	pathDir := filepath.Join(baseDir, "path")
	if err := os.MkdirAll(pathDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	commandName := "novel-acp"
	if goruntime.GOOS == "windows" {
		commandName += ".exe"
	}
	commandPath := filepath.Join(pathDir, commandName)
	if err := os.WriteFile(commandPath, []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	originalPath := os.Getenv("PATH")
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+originalPath)

	service := NewWithBaseDirAndRegistry(baseDir, fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"novel-acp": {
					ID:                "novel-acp",
					Name:              "Novel ACP",
					Version:           "0.1.0",
					DistributionKinds: []string{"binary"},
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "novel-acp",
					},
				},
			},
		},
	})

	agent, err := service.Get("novel-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("registry-only installed agent should be detected")
	}
	if !agent.ManifestValid {
		t.Fatal("registry-only installed agent should still be surfaced as valid inventory")
	}
	if !agent.Security.AllowsRemoteStart {
		t.Fatal("registry-only installed agent should be launchable with the generic registry policy")
	}
	if agent.Launch.Mode != "external" {
		t.Fatalf("Launch.Mode = %q, want %q", agent.Launch.Mode, "external")
	}
	if agent.Launch.Command != "novel-acp" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "novel-acp")
	}
	if agent.Registry.ValidationStatus != "matched" {
		t.Fatalf("Registry.ValidationStatus = %q, want %q", agent.Registry.ValidationStatus, "matched")
	}
}

func TestRegistryOnlyAgentWithoutSupportedDistributionIsVisibleButNotLaunchable(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"odd-acp": {
					ID:                "odd-acp",
					Name:              "Odd ACP",
					Version:           "0.1.0",
					DistributionKinds: []string{"docker"},
				},
			},
		},
	})

	agent, err := service.Get("odd-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.Detected {
		t.Fatal("odd-acp should not be detected without a supported distribution")
	}
	if agent.Security.AllowsRemoteStart {
		t.Fatal("odd-acp should not be launchable without a supported distribution")
	}
}

func TestRegistrySynthesizesBinaryArgsForTrustedExternalAdapter(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"opencode": {
					ID:                "opencode",
					Name:              "OpenCode",
					Version:           "1.3.2",
					DistributionKinds: []string{"binary"},
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "opencode",
						Args:        []string{"acp"},
					},
				},
			},
		},
	})

	agent, err := service.Get("opencode")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.ManifestValid {
		t.Fatalf("ManifestValid = false, validation error = %q", agent.ValidationError)
	}
	if len(agent.Launch.Args) != 1 || agent.Launch.Args[0] != "acp" {
		t.Fatalf("Launch.Args = %v, want [acp]", agent.Launch.Args)
	}
	if len(agent.Registry.CurrentBinaryArgs) != 1 || agent.Registry.CurrentBinaryArgs[0] != "acp" {
		t.Fatalf("CurrentBinaryArgs = %v, want [acp]", agent.Registry.CurrentBinaryArgs)
	}
}

func TestRegistrySynthesizesNpxLaunchForTrustedExternalAdapter(t *testing.T) {
	originalPath := os.Getenv("PATH")
	pathDir := t.TempDir()
	npxName := "npx"
	if goruntime.GOOS == "windows" {
		npxName = "npx.exe"
	}
	if err := os.WriteFile(filepath.Join(pathDir, npxName), []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+originalPath)

	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"codex-acp": {
					ID:                "codex-acp",
					Name:              "Codex CLI",
					Version:           "0.10.0",
					DistributionKinds: []string{"npx"},
					NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					NpxArgs:           []string{"--acp"},
				},
			},
		},
	})

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("codex-acp should be detected when npx is available and registry exposes an npx package")
	}
	if agent.Launch.Command != "npx" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "npx")
	}
	if len(agent.Launch.Args) != 3 || agent.Launch.Args[0] != "-y" || agent.Launch.Args[1] != "@zed-industries/codex-acp@0.10.0" || agent.Launch.Args[2] != "--acp" {
		t.Fatalf("Launch.Args = %v, want [-y @zed-industries/codex-acp@0.10.0 --acp]", agent.Launch.Args)
	}
}

func TestRegistryPrefersNpxOverBinaryWhenBothExist(t *testing.T) {
	originalPath := os.Getenv("PATH")
	pathDir := t.TempDir()
	npxName := "npx"
	if goruntime.GOOS == "windows" {
		npxName = "npx.exe"
	}
	if err := os.WriteFile(filepath.Join(pathDir, npxName), []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+originalPath)

	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"codex-acp": {
					ID:                "codex-acp",
					Name:              "Codex CLI",
					Version:           "0.10.0",
					DistributionKinds: []string{"binary", "npx"},
					NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "codex-acp",
						Command:     "./codex-acp",
						ArchiveURL:  "https://example.com/codex-acp.tar.gz",
					},
				},
			},
		},
	})

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("codex-acp should be detected when npx is available")
	}
	if agent.Launch.Command != "npx" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "npx")
	}
}

func TestRegistryFallsBackToBinaryAfterNpxAndUvx(t *testing.T) {
	baseDir := t.TempDir()
	pathDir := filepath.Join(baseDir, "path")
	if err := os.MkdirAll(pathDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	commandName := "codex-acp"
	if goruntime.GOOS == "windows" {
		commandName += ".exe"
	}
	if err := os.WriteFile(filepath.Join(pathDir, commandName), []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	service := NewWithBaseDirAndRegistry(baseDir, fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"codex-acp": {
					ID:                "codex-acp",
					Name:              "Codex CLI",
					Version:           "0.10.0",
					DistributionKinds: []string{"binary", "npx", "uvx"},
					NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					UvxPackage:        "codex-acp",
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "codex-acp",
						Command:     "./codex-acp",
						ArchiveURL:  "https://example.com/codex-acp.tar.gz",
					},
				},
			},
		},
	})

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("codex-acp should be detected when its binary exists in PATH")
	}
	if agent.Launch.Command != "codex-acp" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "codex-acp")
	}
}

func TestRegistryLeavesBinaryDownloadAsLastResort(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"codex-acp": {
					ID:                "codex-acp",
					Name:              "Codex CLI",
					Version:           "0.10.0",
					DistributionKinds: []string{"binary", "npx", "uvx"},
					NpxPackage:        "@zed-industries/codex-acp@0.10.0",
					UvxPackage:        "codex-acp",
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "codex-acp",
						Command:     "./codex-acp",
						ArchiveURL:  "https://example.com/codex-acp.tar.gz",
					},
				},
			},
		},
	})

	agent, err := service.Get("codex-acp")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.Detected {
		t.Fatal("codex-acp should stay undetected until a launcher or PATH binary exists")
	}
	if agent.Launch.Command != "codex-acp" {
		t.Fatalf("Launch.Command = %q, want %q", agent.Launch.Command, "codex-acp")
	}
	if agent.Registry.CurrentBinaryArchiveURL == "" {
		t.Fatal("CurrentBinaryArchiveURL should stay available for last-resort download")
	}
}

func TestRegistrySynthesizesUvxLaunchForRegistryAgent(t *testing.T) {
	originalPath := os.Getenv("PATH")
	pathDir := t.TempDir()
	uvxName := "uvx"
	if goruntime.GOOS == "windows" {
		uvxName = "uvx.exe"
	}
	if err := os.WriteFile(filepath.Join(pathDir, uvxName), []byte("placeholder"), 0o755); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	t.Setenv("PATH", pathDir+string(os.PathListSeparator)+originalPath)

	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"fast-agent": {
					ID:                "fast-agent",
					Name:              "fast-agent",
					Version:           "0.6.9",
					DistributionKinds: []string{"uvx"},
					UvxPackage:        "fast-agent-acp==0.6.9",
					UvxArgs:           []string{"-x"},
				},
			},
		},
	})

	agent, err := service.Get("fast-agent")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if !agent.Detected {
		t.Fatal("fast-agent should be detected when uvx is available")
	}
	if agent.Launch.Command != "uvx" {
		t.Fatalf("Launch.Command = %q, want uvx", agent.Launch.Command)
	}
	if len(agent.Launch.Args) != 2 || agent.Launch.Args[0] != "fast-agent-acp==0.6.9" || agent.Launch.Args[1] != "-x" {
		t.Fatalf("Launch.Args = %v", agent.Launch.Args)
	}
	if agent.Registry.UvxPackage != "fast-agent-acp==0.6.9" {
		t.Fatalf("UvxPackage = %q, want %q", agent.Registry.UvxPackage, "fast-agent-acp==0.6.9")
	}
	if len(agent.Registry.UvxArgs) != 1 || agent.Registry.UvxArgs[0] != "-x" {
		t.Fatalf("UvxArgs = %v, want [-x]", agent.Registry.UvxArgs)
	}
	if !agent.Security.AllowsRemoteStart {
		t.Fatal("fast-agent should be launchable when uvx is supported")
	}
}

func TestRegistryBinaryAgentIsLaunchableWithoutPathWhenDownloadable(t *testing.T) {
	service := NewWithBaseDirAndRegistry(t.TempDir(), fakeRegistrySource{
		snapshot: acpregistry.Snapshot{
			Version: "1.0.0",
			Agents: map[string]acpregistry.AgentEntry{
				"corust-agent": {
					ID:                "corust-agent",
					Name:              "Corust Agent",
					Version:           "0.4.0",
					DistributionKinds: []string{"binary"},
					CurrentBinary: &acpregistry.BinaryTarget{
						CommandName: "corust-agent-acp",
						Command:     "./corust-agent-acp",
						ArchiveURL:  "https://example.com/corust-agent.tar.gz",
					},
				},
			},
		},
	})

	agent, err := service.Get("corust-agent")
	if err != nil {
		t.Fatalf("Get() error = %v", err)
	}
	if agent.Detected {
		t.Fatal("corust-agent should not be marked detected before install or PATH discovery")
	}
	if !agent.ManifestValid {
		t.Fatalf("ManifestValid = false, validation error = %q", agent.ValidationError)
	}
	if !agent.Security.AllowsRemoteStart {
		t.Fatal("corust-agent should still be launchable because the registry exposes a downloadable binary")
	}
	if agent.Registry.CurrentBinaryArchiveURL == "" {
		t.Fatal("CurrentBinaryArchiveURL should be preserved for auto-acquire")
	}
}

func TestValidateRestartAllowsAutoRestartForStdio(t *testing.T) {
	err := validateRestart(RestartConfig{Mode: "on_failure", MaxRetries: 1}, "stdio")
	if err != nil {
		t.Fatalf("validateRestart() error = %v", err)
	}
}

func TestValidateHealthCheckRejectsWebSocketProbeForStdio(t *testing.T) {
	err := validateHealthCheck(HealthCheckConfig{Mode: "websocket_connect", TimeoutSeconds: 1}, "stdio")
	if err == nil {
		t.Fatal("validateHealthCheck() should reject websocket_connect for stdio")
	}
}

type fakeRegistrySource struct {
	snapshot acpregistry.Snapshot
	err      error
}

func (f fakeRegistrySource) Snapshot(context.Context) (acpregistry.Snapshot, error) {
	return f.snapshot, f.err
}
