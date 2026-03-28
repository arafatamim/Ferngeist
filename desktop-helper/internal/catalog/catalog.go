package catalog

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	goruntime "runtime"
	"sort"
	"strings"
	"unicode"

	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
)

var ErrAgentNotFound = errors.New("agent not found")

const mockAgentExecutablePlaceholder = "__MOCK_AGENT_EXECUTABLE__"

//go:embed manifests/*.json
var embeddedManifestFS embed.FS

// Agent is the helper's curated manifest for one supported ACP runtime. The
// catalog is intentionally opinionated: Ferngeist can only launch agents that
// appear here and pass validation.
type Agent struct {
	ID              string            `json:"id"`
	DisplayName     string            `json:"displayName"`
	Protocol        string            `json:"protocol"`
	PlatformSupport []string          `json:"platformSupport,omitempty"`
	Detection       DetectionConfig   `json:"detection"`
	Launch          LaunchConfig      `json:"launch"`
	HealthCheck     HealthCheckConfig `json:"healthCheck"`
	Capabilities    []string          `json:"capabilities,omitempty"`
	Security        SecurityConfig    `json:"security"`
	Registry        RegistryInfo      `json:"registry"`
	Detected        bool              `json:"detected"`
	ManifestValid   bool              `json:"manifestValid"`
	ValidationError string            `json:"validationError,omitempty"`
	Hint            string            `json:"hint,omitempty"`
}

type DetectionConfig struct {
	Mode    string `json:"mode"`
	Command string `json:"command,omitempty"`
	Path    string `json:"path,omitempty"`
}

type LaunchConfig struct {
	Mode      string          `json:"mode"`
	Command   string          `json:"command"`
	Args      []string        `json:"args"`
	Transport string          `json:"transport,omitempty"`
	Readiness ReadinessConfig `json:"readiness"`
	Restart   RestartConfig   `json:"restart"`
}

type ReadinessConfig struct {
	Mode           string `json:"mode"`
	TimeoutSeconds int    `json:"timeoutSeconds,omitempty"`
}

type RestartConfig struct {
	Mode           string `json:"mode"`
	MaxRetries     int    `json:"maxRetries,omitempty"`
	BackoffSeconds int    `json:"backoffSeconds,omitempty"`
}

type HealthCheckConfig struct {
	Mode           string `json:"mode"`
	TimeoutSeconds int    `json:"timeoutSeconds,omitempty"`
}

type SecurityConfig struct {
	CuratedLaunch          bool     `json:"curatedLaunch"`
	AllowsRemoteStart      bool     `json:"allowsRemoteStart"`
	UserConfigurableFields []string `json:"userConfigurableFields,omitempty"`
}

type RegistryInfo struct {
	Required                bool     `json:"required"`
	ValidationStatus        string   `json:"validationStatus"`
	Name                    string   `json:"name,omitempty"`
	Version                 string   `json:"version,omitempty"`
	Repository              string   `json:"repository,omitempty"`
	Website                 string   `json:"website,omitempty"`
	DistributionKinds       []string `json:"distributionKinds,omitempty"`
	CurrentBinaryPath       string   `json:"currentBinaryPath,omitempty"`
	CurrentBinaryArchiveURL string   `json:"currentBinaryArchiveUrl,omitempty"`
	CurrentBinaryCommand    string   `json:"currentBinaryCommand,omitempty"`
	CurrentBinaryArgs       []string `json:"currentBinaryArgs,omitempty"`
	NpxPackage              string   `json:"npxPackage,omitempty"`
}

type RegistrySource interface {
	Snapshot(ctx context.Context) (acpregistry.Snapshot, error)
}

type Service struct {
	agents   []Agent
	adapters []Agent
	baseDir  string
	registry RegistrySource
}

// New returns the default helper catalog rooted at the current working
// directory.
func New() *Service {
	return NewWithBaseDirAndRegistry(".", nil)
}

func NewWithBaseDir(baseDir string) *Service {
	return NewWithBaseDirAndRegistry(baseDir, nil)
}

func NewWithBaseDirAndRegistry(baseDir string, registrySource RegistrySource) *Service {
	agents, err := loadEmbeddedAgents()
	if err != nil {
		panic(fmt.Errorf("load embedded catalog manifests: %w", err))
	}
	service := &Service{
		adapters: agents,
		baseDir:  baseDir,
		registry: registrySource,
	}
	service.Refresh()
	return service
}

func loadEmbeddedAgents() ([]Agent, error) {
	entries, err := fs.ReadDir(embeddedManifestFS, "manifests")
	if err != nil {
		return nil, err
	}

	sort.Slice(entries, func(i, j int) bool {
		return entries[i].Name() < entries[j].Name()
	})

	agents := make([]Agent, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}

		path := "manifests/" + entry.Name()
		data, err := embeddedManifestFS.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}

		var agent Agent
		if err := json.Unmarshal(data, &agent); err != nil {
			return nil, fmt.Errorf("decode %s: %w", path, err)
		}

		normalizeManifestPlaceholders(&agent)
		agents = append(agents, agent)
	}
	return agents, nil
}

func normalizeManifestPlaceholders(agent *Agent) {
	if agent == nil {
		return
	}
	mockName := mockAgentExecutableName()
	agent.Detection.Path = strings.ReplaceAll(agent.Detection.Path, mockAgentExecutablePlaceholder, mockName)
	agent.Launch.Command = strings.ReplaceAll(agent.Launch.Command, mockAgentExecutablePlaceholder, mockName)
}

func (s *Service) List() []Agent {
	s.Refresh()

	out := make([]Agent, len(s.agents))
	copy(out, s.agents)
	return out
}

func (s *Service) Get(id string) (Agent, error) {
	s.Refresh()

	for _, agent := range s.agents {
		if agent.ID == id {
			return agent, nil
		}
	}
	return Agent{}, ErrAgentNotFound
}

// Refresh revalidates built-in manifests, merges ACP registry metadata, and
// then reruns host detection. The helper does this eagerly because the catalog
// is small and the API should reflect current host state.
func (s *Service) Refresh() {
	registrySnapshot, registryErr, registryEnabled := s.loadRegistry()
	adapterByID := make(map[string]Agent, len(s.adapters))
	for _, adapter := range s.adapters {
		adapterByID[adapter.ID] = adapter
	}

	capacity := len(s.adapters)
	if len(registrySnapshot.Agents) > capacity {
		capacity = len(registrySnapshot.Agents)
	}
	visible := make([]Agent, 0, capacity)
	seen := make(map[string]struct{})

	if registryEnabled && registryErr == nil {
		ids := sortedRegistryIDs(registrySnapshot.Agents)
		for _, id := range ids {
			entry := registrySnapshot.Agents[id]
			agent := registryEntryToAgent(entry)
			if adapter, ok := adapterByID[id]; ok {
				agent = mergeAdapter(agent, adapter)
				agent, agent.Registry = applyRegistryInfo(agent, agent.Registry, registrySnapshot, nil, true, agent.ID)
				validationError := validateAgent(adapter)
				agent.ManifestValid = validationError == nil
				agent.ValidationError = validationErrorString(validationError)
				if validationError == nil {
					agent.Detected = s.detect(agent) || detectViaNpx(agent)
				} else {
					agent.Detected = detectRegistryEntry(entry)
				}
			} else {
				agent.Detected = detectRegistryEntry(entry)
				agent.ManifestValid = true
			}
			visible = append(visible, agent)
			seen[id] = struct{}{}
		}
	}

	for _, adapter := range s.adapters {
		if _, ok := seen[adapter.ID]; ok {
			continue
		}

		validationError := validateAgent(adapter)
		agent := adapter
		agent, agent.Registry = applyRegistryInfo(agent, agent.Registry, registrySnapshot, registryErr, registryEnabled, agent.ID)
		if validationError == nil && agent.Registry.Required && agent.Registry.ValidationStatus == "missing" {
			validationError = errors.New("agent is not present in ACP registry")
		}
		agent.ManifestValid = validationError == nil
		agent.ValidationError = validationErrorString(validationError)
		agent.Detected = validationError == nil && (s.detect(agent) || detectViaNpx(agent))
		visible = append(visible, agent)
	}

	sort.Slice(visible, func(i, j int) bool {
		return visible[i].ID < visible[j].ID
	})
	s.agents = visible
}

func (s *Service) detect(agent Agent) bool {
	if !isPlatformSupported(agent.PlatformSupport, goruntime.GOOS) {
		return false
	}

	switch agent.Detection.Mode {
	case "local_file":
		path := agent.Detection.Path
		if !filepath.IsAbs(path) {
			path = filepath.Join(s.baseDir, path)
		}
		info, err := os.Stat(path)
		return err == nil && !info.IsDir()
	case "path_lookup":
		_, err := exec.LookPath(agent.Detection.Command)
		return err == nil
	default:
		return false
	}
}

func detectRegistryEntry(entry acpregistry.AgentEntry) bool {
	if entry.CurrentBinary == nil || strings.TrimSpace(entry.CurrentBinary.CommandName) == "" {
		return detectNpxPackage(entry.NpxPackage)
	}
	_, err := exec.LookPath(entry.CurrentBinary.CommandName)
	if err == nil {
		return true
	}
	return detectNpxPackage(entry.NpxPackage)
}

func detectViaNpx(agent Agent) bool {
	if agent.Launch.Mode != "external" || normalizeExecutableName(agent.Launch.Command) != "npx" {
		return false
	}
	return detectNpxPackage(agent.Registry.NpxPackage)
}

func detectNpxPackage(pkg string) bool {
	if strings.TrimSpace(pkg) == "" {
		return false
	}
	_, err := exec.LookPath("npx")
	return err == nil
}

// validateAgent enforces the "curated launch only" boundary. This is where the
// helper rejects manifests that would drift into arbitrary command execution or
// unsupported transport combinations.
func validateAgent(agent Agent) error {
	if strings.TrimSpace(agent.ID) == "" {
		return errors.New("agent id is required")
	}
	if strings.TrimSpace(agent.DisplayName) == "" {
		return errors.New("display name is required")
	}
	if strings.TrimSpace(agent.Protocol) == "" {
		return errors.New("protocol is required")
	}
	if agent.Protocol != "acp" {
		return fmt.Errorf("unsupported protocol %q", agent.Protocol)
	}
	if len(agent.PlatformSupport) == 0 {
		return errors.New("platform support is required")
	}

	for _, capability := range agent.Capabilities {
		if strings.TrimSpace(capability) == "" {
			return errors.New("capabilities cannot contain empty values")
		}
	}

	if !agent.Security.CuratedLaunch {
		return errors.New("launch definitions must be curated")
	}
	if agent.Launch.Mode != "process" && !agent.Security.AllowsRemoteStart {
		// Non-bundled adapters stay visible but are not launchable until a trusted local adapter exists.
	}

	if err := validateDetection(agent.Detection); err != nil {
		return err
	}
	if err := validateLaunch(agent.Launch); err != nil {
		return err
	}
	if err := validateHealthCheck(agent.HealthCheck, agent.Launch.Transport); err != nil {
		return err
	}
	if err := validateDetectionLaunchPair(agent.Detection, agent.Launch); err != nil {
		return err
	}

	return nil
}

func validateDetection(detection DetectionConfig) error {
	switch detection.Mode {
	case "local_file":
		if strings.TrimSpace(detection.Path) == "" {
			return errors.New("local_file detection requires path")
		}
	case "path_lookup":
		if err := validateExecutableName(detection.Command); err != nil {
			return fmt.Errorf("path_lookup detection requires safe command: %w", err)
		}
	default:
		return fmt.Errorf("unsupported detection mode %q", detection.Mode)
	}
	return nil
}

// validateLaunch keeps launch templates intentionally narrow. The helper should
// not accept shell fragments or transport-specific fields that do not match the
// declared launch mode.
func validateLaunch(launch LaunchConfig) error {
	switch launch.Mode {
	case "process":
		if strings.TrimSpace(launch.Command) == "" {
			return errors.New("process launch requires command")
		}
		if launch.Transport != "stdio" {
			return fmt.Errorf("unsupported process transport %q", launch.Transport)
		}
		if launch.Readiness.Mode == "" {
			return errors.New("process launch requires readiness mode")
		}
	case "external":
		if err := validateExecutableName(launch.Command); err != nil {
			return fmt.Errorf("external launch requires safe command: %w", err)
		}
		if launch.Transport != "stdio" {
			return fmt.Errorf("unsupported external transport %q", launch.Transport)
		}
		if launch.Readiness.Mode == "" {
			return errors.New("external launch requires readiness mode")
		}
	default:
		return fmt.Errorf("unsupported launch mode %q", launch.Mode)
	}

	for _, arg := range launch.Args {
		if strings.TrimSpace(arg) == "" {
			return errors.New("launch args cannot contain empty values")
		}
		if containsControlChars(arg) {
			return errors.New("launch args cannot contain control characters")
		}
	}

	if containsControlChars(launch.Command) {
		return errors.New("launch command cannot contain control characters")
	}
	if err := validateReadiness(launch.Readiness, launch.Transport); err != nil {
		return err
	}
	if err := validateRestart(launch.Restart, launch.Transport); err != nil {
		return err
	}
	return nil
}

func validateDetectionLaunchPair(detection DetectionConfig, launch LaunchConfig) error {
	switch {
	case detection.Mode == "local_file" && launch.Mode != "process":
		return errors.New("local_file detection must pair with process launch")
	case detection.Mode == "path_lookup" && launch.Mode != "external":
		return errors.New("path_lookup detection must pair with external launch")
	}

	if detection.Mode == "local_file" && filepath.Clean(detection.Path) != filepath.Clean(launch.Command) {
		return errors.New("local_file detection path must match process launch command")
	}
	if detection.Mode == "path_lookup" && detection.Command != launch.Command {
		return errors.New("path_lookup detection command must match external launch command")
	}
	return nil
}

func validateExecutableName(command string) error {
	command = strings.TrimSpace(command)
	if command == "" {
		return errors.New("command is required")
	}
	if strings.Contains(command, "/") || strings.Contains(command, `\`) {
		return errors.New("command must not contain path separators")
	}
	if containsControlChars(command) || strings.ContainsAny(command, " \t") {
		return errors.New("command must be a single executable name")
	}
	return nil
}

func validateReadiness(readiness ReadinessConfig, transport string) error {
	switch transport {
	case "stdio":
		if readiness.Mode != "immediate" {
			return fmt.Errorf("stdio transport requires readiness mode %q", "immediate")
		}
	default:
		return fmt.Errorf("unsupported readiness transport %q", transport)
	}
	if readiness.TimeoutSeconds < 0 {
		return errors.New("readiness timeout must not be negative")
	}
	return nil
}

func validateRestart(restart RestartConfig, transport string) error {
	mode := restart.Mode
	if mode == "" {
		mode = "never"
	}

	switch mode {
	case "never":
	case "on_failure":
		if transport != "stdio" {
			return errors.New("automatic restart is only supported for stdio runtimes")
		}
	default:
		return fmt.Errorf("unsupported restart mode %q", restart.Mode)
	}

	if restart.MaxRetries < 0 {
		return errors.New("restart maxRetries must not be negative")
	}
	if restart.BackoffSeconds < 0 {
		return errors.New("restart backoffSeconds must not be negative")
	}
	return nil
}

func validateHealthCheck(healthCheck HealthCheckConfig, transport string) error {
	mode := healthCheck.Mode
	if mode == "" {
		return errors.New("healthCheck mode is required")
	}

	switch transport {
	case "stdio":
		if mode != "none" {
			return fmt.Errorf("stdio transport requires healthCheck mode %q", "none")
		}
	default:
		return fmt.Errorf("unsupported healthCheck transport %q", transport)
	}

	if healthCheck.TimeoutSeconds < 0 {
		return errors.New("healthCheck timeout must not be negative")
	}
	return nil
}

func containsControlChars(value string) bool {
	for _, r := range value {
		if unicode.IsControl(r) {
			return true
		}
	}
	return false
}

func isPlatformSupported(supported []string, target string) bool {
	for _, platform := range supported {
		if strings.EqualFold(strings.TrimSpace(platform), target) {
			return true
		}
	}
	return false
}

func validationErrorString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func mergeAdapter(base Agent, adapter Agent) Agent {
	base.DisplayName = adapter.DisplayName
	base.Protocol = adapter.Protocol
	base.PlatformSupport = append([]string(nil), adapter.PlatformSupport...)
	base.Detection = adapter.Detection
	base.Launch = adapter.Launch
	base.HealthCheck = adapter.HealthCheck
	base.Capabilities = append([]string(nil), adapter.Capabilities...)
	base.Security = adapter.Security
	base.Hint = adapter.Hint
	if adapter.Registry.Required || adapter.Registry.ValidationStatus != "" {
		base.Registry.Required = adapter.Registry.Required
		if adapter.Registry.ValidationStatus != "" {
			base.Registry.ValidationStatus = adapter.Registry.ValidationStatus
		}
	}
	return base
}

func (s *Service) loadRegistry() (acpregistry.Snapshot, error, bool) {
	if s.registry == nil {
		return acpregistry.Snapshot{}, nil, false
	}
	snapshot, err := s.registry.Snapshot(context.Background())
	return snapshot, err, true
}

func sortedRegistryIDs(entries map[string]acpregistry.AgentEntry) []string {
	ids := make([]string, 0, len(entries))
	for id := range entries {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func registryEntryToAgent(entry acpregistry.AgentEntry) Agent {
	displayName := strings.TrimSpace(entry.Name)
	if displayName == "" {
		displayName = entry.ID
	}

	agent := Agent{
		ID:              entry.ID,
		DisplayName:     displayName,
		Protocol:        "acp",
		PlatformSupport: []string{"windows", "darwin", "linux"},
		HealthCheck: HealthCheckConfig{
			Mode: "none",
		},
		Capabilities: []string{"acp"},
		Security: SecurityConfig{
			CuratedLaunch:          true,
			AllowsRemoteStart:      false,
			UserConfigurableFields: []string{},
		},
		Registry: RegistryInfo{
			Required:                false,
			ValidationStatus:        "matched",
			Name:                    entry.Name,
			Version:                 entry.Version,
			Repository:              entry.Repository,
			Website:                 entry.Website,
			DistributionKinds:       append([]string(nil), entry.DistributionKinds...),
			CurrentBinaryCommand:    "",
			CurrentBinaryPath:       "",
			CurrentBinaryArchiveURL: "",
			CurrentBinaryArgs:       nil,
		},
		Hint: "Detected from the ACP registry.",
	}

	if entry.CurrentBinary != nil {
		agent.Detection = DetectionConfig{
			Mode:    "path_lookup",
			Command: entry.CurrentBinary.CommandName,
		}
		agent.Launch = LaunchConfig{
			Mode:      "external",
			Command:   entry.CurrentBinary.CommandName,
			Args:      append([]string(nil), entry.CurrentBinary.Args...),
			Transport: "stdio",
			Readiness: ReadinessConfig{
				Mode: "immediate",
			},
			Restart: RestartConfig{
				Mode: "never",
			},
		}
		agent.Security.AllowsRemoteStart = true
		agent.Registry.CurrentBinaryCommand = entry.CurrentBinary.CommandName
		agent.Registry.CurrentBinaryPath = entry.CurrentBinary.Command
		agent.Registry.CurrentBinaryArchiveURL = entry.CurrentBinary.ArchiveURL
		agent.Registry.CurrentBinaryArgs = append([]string(nil), entry.CurrentBinary.Args...)
	}
	agent.Registry.NpxPackage = entry.NpxPackage
	if agent.Launch.Mode == "" && strings.TrimSpace(entry.NpxPackage) != "" {
		agent.Detection = DetectionConfig{
			Mode:    "path_lookup",
			Command: "npx",
		}
		agent.Launch = LaunchConfig{
			Mode:      "external",
			Command:   "npx",
			Args:      []string{"-y", entry.NpxPackage},
			Transport: "stdio",
			Readiness: ReadinessConfig{
				Mode: "immediate",
			},
			Restart: RestartConfig{
				Mode: "never",
			},
		}
		agent.Security.AllowsRemoteStart = true
	}
	if agent.Security.AllowsRemoteStart {
		agent.Hint = "Detected from the ACP registry and launchable with the helper's generic ACP runtime policy."
	} else {
		agent.Hint = "Detected from the ACP registry, but no supported launch distribution is available yet."
	}
	return agent
}

// applyRegistryInfo enriches a local manifest without letting the registry
// become the launch authority. The helper still relies on curated local adapter
// definitions and only copies compatible metadata from the official registry.
func applyRegistryInfo(agent Agent, base RegistryInfo, snapshot acpregistry.Snapshot, registryErr error, registryEnabled bool, agentID string) (Agent, RegistryInfo) {
	if !base.Required {
		base.ValidationStatus = "not_required"
		return agent, base
	}
	if !registryEnabled {
		base.ValidationStatus = "unavailable"
		return agent, base
	}
	if registryErr != nil {
		base.ValidationStatus = "unavailable"
		return agent, base
	}
	entry, ok := snapshot.Agents[agentID]
	if !ok {
		base.ValidationStatus = "missing"
		return agent, base
	}

	base.ValidationStatus = "matched"
	base.Name = entry.Name
	base.Version = entry.Version
	base.Repository = entry.Repository
	base.Website = entry.Website
	base.DistributionKinds = entry.DistributionKinds
	base.NpxPackage = entry.NpxPackage
	if entry.CurrentBinary != nil {
		base.CurrentBinaryPath = entry.CurrentBinary.Command
		base.CurrentBinaryArchiveURL = entry.CurrentBinary.ArchiveURL
		base.CurrentBinaryCommand = entry.CurrentBinary.CommandName
		base.CurrentBinaryArgs = append([]string(nil), entry.CurrentBinary.Args...)
	}
	agent = synthesizeRegistryLaunch(agent, entry)
	return agent, base
}

// synthesizeRegistryLaunch only applies official binary args when the registry
// command matches the curated local executable name. That keeps remote metadata
// from silently changing which binary the helper trusts.
func synthesizeRegistryLaunch(agent Agent, entry acpregistry.AgentEntry) Agent {
	if agent.Launch.Mode != "external" {
		return agent
	}
	if entry.CurrentBinary != nil && sameExecutableName(agent.Launch.Command, entry.CurrentBinary.CommandName) {
		agent.Launch.Args = append([]string(nil), entry.CurrentBinary.Args...)
		return agent
	}
	if strings.TrimSpace(entry.NpxPackage) != "" {
		agent.Launch.Command = "npx"
		agent.Launch.Args = []string{"-y", entry.NpxPackage}
	}
	return agent
}

func sameExecutableName(left, right string) bool {
	return normalizeExecutableName(left) == normalizeExecutableName(right) && normalizeExecutableName(left) != ""
}

func normalizeExecutableName(command string) string {
	command = strings.TrimSpace(command)
	command = strings.TrimSuffix(command, ".exe")
	command = strings.TrimSuffix(command, ".EXE")
	return command
}

func mockAgentExecutableName() string {
	if goruntime.GOOS == "windows" {
		return "mock-stdio-agent.exe"
	}
	return "mock-stdio-agent"
}
