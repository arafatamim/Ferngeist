package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/discovery"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/gateway"
	helperlogging "github.com/tamimarafat/ferngeist/desktop-helper/internal/logging"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/pairing"
	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestStatusIncludesProtocolVersion(t *testing.T) {
	server := newTestServer()
	server.startedAt = time.Date(2026, 3, 25, 9, 59, 30, 0, time.UTC)
	server.now = func() time.Time { return time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC) }

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()

	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}

	if response.ProtocolVersion != protocolVersion {
		t.Fatalf("ProtocolVersion = %q, want %q", response.ProtocolVersion, protocolVersion)
	}
	if response.Version != "dev" {
		t.Fatalf("Version = %q, want %q", response.Version, "dev")
	}
	if response.UptimeSeconds != 30 {
		t.Fatalf("UptimeSeconds = %d, want 30", response.UptimeSeconds)
	}
	if response.Build.Version != "dev" {
		t.Fatalf("Build.Version = %q, want %q", response.Build.Version, "dev")
	}
	if response.Discovery.ServiceType != "_ferngeist-helper._tcp" {
		t.Fatalf("Discovery.ServiceType = %q, want %q", response.Discovery.ServiceType, "_ferngeist-helper._tcp")
	}
	if response.RuntimeCounts.Total != 0 {
		t.Fatalf("RuntimeCounts.Total = %d, want 0", response.RuntimeCounts.Total)
	}
	if response.Registry.State != "disabled" {
		t.Fatalf("Registry.State = %q, want %q", response.Registry.State, "disabled")
	}
	if response.Remote.Configured {
		t.Fatal("Remote.Configured should be false by default")
	}
	if response.Remote.Mode != "local_only" {
		t.Fatalf("Remote.Mode = %q, want %q", response.Remote.Mode, "local_only")
	}
	if response.Remote.Scope != "local" {
		t.Fatalf("Remote.Scope = %q, want %q", response.Remote.Scope, "local")
	}
	if !response.Remote.Healthy {
		t.Fatal("Remote.Healthy should be true by default")
	}
}

func TestStatusIncludesRegistryHealth(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		fakeRegistryStatusProvider{
			status: acpregistry.Status{
				URL:           "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json",
				State:         "ready",
				Version:       "1.0.0",
				AgentCount:    25,
				LastFetchedAt: time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC),
			},
		},
	)

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.Registry.State != "ready" {
		t.Fatalf("Registry.State = %q, want %q", response.Registry.State, "ready")
	}
	if response.Registry.AgentCount != 25 {
		t.Fatalf("Registry.AgentCount = %d, want %d", response.Registry.AgentCount, 25)
	}
}

func TestStatusIncludesRemoteConfigurationState(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", PublicBaseURL: "https://helper.example.com"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if !response.Remote.Configured {
		t.Fatal("Remote.Configured should be true when PublicBaseURL is set")
	}
	if response.Remote.Mode != "manual_reverse_proxy" {
		t.Fatalf("Remote.Mode = %q, want %q", response.Remote.Mode, "manual_reverse_proxy")
	}
	if response.Remote.Scope != "public" {
		t.Fatalf("Remote.Scope = %q, want %q", response.Remote.Scope, "public")
	}
	if !response.Remote.Healthy {
		t.Fatal("Remote.Healthy should be true for valid PublicBaseURL")
	}
	if response.Remote.PublicURL != "" {
		t.Fatalf("Remote.PublicURL should not be exposed on public status, got %q", response.Remote.PublicURL)
	}
}

func TestStatusIncludesLANRemoteVisibility(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", EnableLAN: true},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.Remote.Configured {
		t.Fatal("Remote.Configured should be false for LAN-only visibility")
	}
	if response.Remote.Mode != "lan_direct" {
		t.Fatalf("Remote.Mode = %q, want %q", response.Remote.Mode, "lan_direct")
	}
	if response.Remote.Scope != "lan" {
		t.Fatalf("Remote.Scope = %q, want %q", response.Remote.Scope, "lan")
	}
	if !response.Remote.Healthy {
		t.Fatal("Remote.Healthy should be true for LAN mode")
	}
}

func TestStatusClassifiesTailscaleRemoteVisibility(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", PublicBaseURL: "https://helper.tail123.ts.net"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.Remote.Mode != "tailscale" {
		t.Fatalf("Remote.Mode = %q, want %q", response.Remote.Mode, "tailscale")
	}
	if response.Remote.Scope != "private" {
		t.Fatalf("Remote.Scope = %q, want %q", response.Remote.Scope, "private")
	}
}

func TestStatusFlagsInvalidRemoteConfiguration(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", PublicBaseURL: "://bad-url"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)

	request := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	var response statusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.Remote.Healthy {
		t.Fatal("Remote.Healthy should be false for invalid PublicBaseURL")
	}
	if response.Remote.Warning == "" {
		t.Fatal("Remote.Warning should describe invalid remote configuration")
	}
}

func TestPairingRoundTrip(t *testing.T) {
	server := newTestServer()

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var startResponse pairStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        startResponse.Code,
		DeviceName:  "Pixel 9",
	})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}

	completeRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	completeRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(completeRecorder, completeRequest)

	if completeRecorder.Code != http.StatusOK {
		t.Fatalf("complete status code = %d, want %d", completeRecorder.Code, http.StatusOK)
	}

	var completeResponse pairCompleteResponse
	if err := json.Unmarshal(completeRecorder.Body.Bytes(), &completeResponse); err != nil {
		t.Fatalf("Unmarshal(complete) error = %v", err)
	}

	if completeResponse.DeviceName != "Pixel 9" {
		t.Fatalf("DeviceName = %q, want %q", completeResponse.DeviceName, "Pixel 9")
	}
	if completeResponse.Token == "" {
		t.Fatal("Token should not be empty")
	}
}

func TestProtectedEndpointRequiresCredential(t *testing.T) {
	server := newTestServer()

	request := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
}

func TestAgentsEndpointIncludesRuntimeState(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	server := newTestServerWithBaseDir(baseDir)
	token := pairDevice(t, server)

	request := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("initial agents status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var initial agentsResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &initial); err != nil {
		t.Fatalf("Unmarshal(initial agents) error = %v", err)
	}

	mockAgent := findAgentState(t, initial.Agents, "mock-acp")
	if mockAgent.Running {
		t.Fatal("mock-acp should not be running before start")
	}
	if mockAgent.RuntimeID != "" {
		t.Fatalf("RuntimeID = %q, want empty before start", mockAgent.RuntimeID)
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/start", nil)
	startRequest.Header.Set("Authorization", "Bearer "+token)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}
	t.Cleanup(func() {
		stopRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/stop", nil)
		stopRequest.Header.Set("Authorization", "Bearer "+token)
		stopRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(stopRecorder, stopRequest)
	})

	request = httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder = httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("running agents status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var running agentsResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &running); err != nil {
		t.Fatalf("Unmarshal(running agents) error = %v", err)
	}

	mockAgent = findAgentState(t, running.Agents, "mock-acp")
	if !mockAgent.Running {
		t.Fatal("mock-acp should be marked running after start")
	}
	if mockAgent.RuntimeID == "" {
		t.Fatal("RuntimeID should not be empty after start")
	}
	if mockAgent.RuntimeStatus != "running" {
		t.Fatalf("RuntimeStatus = %q, want %q", mockAgent.RuntimeStatus, "running")
	}
}

func TestPairingRejectsWrongCode(t *testing.T) {
	server := newTestServer()

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	var startResponse pairStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        "000000",
		DeviceName:  "Pixel 9",
	})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}

	completeRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	completeRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(completeRecorder, completeRequest)

	if completeRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("complete status code = %d, want %d", completeRecorder.Code, http.StatusUnauthorized)
	}
}

func TestDiagnosticsSummaryIncludesPersistedFailures(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer store.Close()

	if err := store.SaveRuntimeFailure(context.Background(), storage.RuntimeFailureRecord{
		RuntimeID:  "run-failed",
		AgentID:    "mock-acp",
		AgentName:  "Mock ACP",
		LastError:  "process exited with status 1",
		CreatedAt:  time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC),
		FailedAt:   time.Date(2026, 3, 25, 10, 5, 0, 0, time.UTC),
		LogPreview: `[{"timestamp":"2026-03-25T10:04:59Z","stream":"stderr","message":"boom"}]`,
	}); err != nil {
		t.Fatalf("SaveRuntimeFailure() error = %v", err)
	}

	server := newTestServerWithStore(t.TempDir(), store)
	token := pairDevice(t, server)

	request := httptest.NewRequest(http.MethodGet, "/v1/diagnostics/summary", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response diagnosticsSummaryResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(response.Runtime.RecentFailures) != 1 {
		t.Fatalf("len(RecentFailures) = %d, want 1", len(response.Runtime.RecentFailures))
	}
	if response.Runtime.RecentFailures[0].LastError != "process exited with status 1" {
		t.Fatalf("LastError = %q", response.Runtime.RecentFailures[0].LastError)
	}
}

func TestDiagnosticsExportIncludesHelperLogsAndRuntimeLogs(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	logSvc, err := helperlogging.NewService(filepath.Join(baseDir, "logs"), "helper.log", 1024*1024, 2)
	if err != nil {
		t.Fatalf("NewService() error = %v", err)
	}
	defer logSvc.Close()

	logger := slog.New(slog.NewJSONHandler(io.MultiWriter(io.Discard, logSvc), nil))
	logger.Info("diagnostic export ready", slog.String("component", "test"))

	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", LogDir: filepath.Join(baseDir, "logs"), StateDBPath: filepath.Join(baseDir, "state.db"), HelperName: "test-helper"},
		BuildInfo{Version: "1.2.3", Commit: "abc123", BuiltAt: "2026-03-25T10:00:00Z", GoVersion: "go1.test"},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtime.NewSupervisorWithBaseDir(logger, baseDir, nil),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		logSvc,
		nil,
	)
	token := pairDevice(t, server)

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/start", nil)
	startRequest.Header.Set("Authorization", "Bearer "+token)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}
	t.Cleanup(func() {
		stopRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/stop", nil)
		stopRequest.Header.Set("Authorization", "Bearer "+token)
		stopRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(stopRecorder, stopRequest)
	})

	request := httptest.NewRequest(http.MethodGet, "/v1/diagnostics/export", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response diagnosticsExportResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if len(response.HelperLogs) == 0 {
		t.Fatal("HelperLogs should not be empty")
	}
	if !strings.Contains(strings.Join(response.HelperLogs, "\n"), "diagnostic export ready") {
		t.Fatalf("HelperLogs do not contain diagnostic line: %v", response.HelperLogs)
	}
	if len(response.Runtimes) != 1 {
		t.Fatalf("len(Runtimes) = %d, want 1", len(response.Runtimes))
	}
	if response.Helper.Build.Version != "1.2.3" {
		t.Fatalf("Helper.Build.Version = %q, want %q", response.Helper.Build.Version, "1.2.3")
	}
	if response.Helper.Remote.PublicURL != "" {
		t.Fatalf("Helper.Remote.PublicURL = %q, want empty by default", response.Helper.Remote.PublicURL)
	}
	if response.Helper.Remote.Mode != "local_only" {
		t.Fatalf("Helper.Remote.Mode = %q, want %q", response.Helper.Remote.Mode, "local_only")
	}
	if response.Helper.Remote.Scope != "local" {
		t.Fatalf("Helper.Remote.Scope = %q, want %q", response.Helper.Remote.Scope, "local")
	}
	foundRuntimeLogs := false
	for range 30 {
		request = httptest.NewRequest(http.MethodGet, "/v1/diagnostics/export", nil)
		request.Header.Set("Authorization", "Bearer "+token)
		recorder = httptest.NewRecorder()
		server.Handler().ServeHTTP(recorder, request)
		if recorder.Code != http.StatusOK {
			t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
		}
		if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
			t.Fatalf("Unmarshal() error = %v", err)
		}
		if len(response.RuntimeLogs[response.Runtimes[0].ID]) > 0 {
			foundRuntimeLogs = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !foundRuntimeLogs {
		t.Fatal("runtime logs should not be empty")
	}
}

func TestRuntimeLifecycleEndpoints(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	server := newTestServerWithBaseDir(baseDir)
	token := pairDevice(t, server)

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/start", nil)
	startRequest.Header.Set("Authorization", "Bearer "+token)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var startResponse runtimeStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}
	if startResponse.Runtime.ID == "" {
		t.Fatal("Runtime ID should not be empty")
	}

	listRequest := httptest.NewRequest(http.MethodGet, "/v1/runtimes", nil)
	listRequest.Header.Set("Authorization", "Bearer "+token)
	listRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(listRecorder, listRequest)

	if listRecorder.Code != http.StatusOK {
		t.Fatalf("list status code = %d, want %d", listRecorder.Code, http.StatusOK)
	}

	var listResponse runtimesResponse
	if err := json.Unmarshal(listRecorder.Body.Bytes(), &listResponse); err != nil {
		t.Fatalf("Unmarshal(list) error = %v", err)
	}
	if len(listResponse.Runtimes) != 1 {
		t.Fatalf("len(runtimes) = %d, want 1", len(listResponse.Runtimes))
	}

	diagnosticsRequest := httptest.NewRequest(http.MethodGet, "/v1/diagnostics/summary", nil)
	diagnosticsRequest.Header.Set("Authorization", "Bearer "+token)
	diagnosticsRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(diagnosticsRecorder, diagnosticsRequest)
	if diagnosticsRecorder.Code != http.StatusOK {
		t.Fatalf("diagnostics status code = %d, want %d", diagnosticsRecorder.Code, http.StatusOK)
	}
	var diagnostics diagnosticsSummaryResponse
	if err := json.Unmarshal(diagnosticsRecorder.Body.Bytes(), &diagnostics); err != nil {
		t.Fatalf("Unmarshal(diagnostics) error = %v", err)
	}
	if diagnostics.Runtime.Running != 1 {
		t.Fatalf("Runtime.Running = %d, want 1", diagnostics.Runtime.Running)
	}

	var logsResponse runtimeLogsResponse
	foundStartupLog := false
	for range 30 {
		logsRequest := httptest.NewRequest(http.MethodGet, "/v1/runtimes/"+startResponse.Runtime.ID+"/logs", nil)
		logsRequest.Header.Set("Authorization", "Bearer "+token)
		logsRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(logsRecorder, logsRequest)
		if logsRecorder.Code != http.StatusOK {
			t.Fatalf("logs status code = %d, want %d", logsRecorder.Code, http.StatusOK)
		}
		if err := json.Unmarshal(logsRecorder.Body.Bytes(), &logsResponse); err != nil {
			t.Fatalf("Unmarshal(logs) error = %v", err)
		}
		for _, entry := range logsResponse.Logs {
			if strings.Contains(entry.Message, "mock stdio agent started") {
				foundStartupLog = true
				break
			}
		}
		if foundStartupLog {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !foundStartupLog {
		t.Fatal("expected startup log entry to be captured")
	}

	connectRequest := httptest.NewRequest(http.MethodPost, "/v1/runtimes/"+startResponse.Runtime.ID+"/connect", nil)
	connectRequest.Header.Set("Authorization", "Bearer "+token)
	connectRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(connectRecorder, connectRequest)

	if connectRecorder.Code != http.StatusOK {
		t.Fatalf("connect status code = %d, want %d", connectRecorder.Code, http.StatusOK)
	}

	var connectResponse runtimeConnectResponse
	if err := json.Unmarshal(connectRecorder.Body.Bytes(), &connectResponse); err != nil {
		t.Fatalf("Unmarshal(connect) error = %v", err)
	}
	if connectResponse.WebSocketPath == "" {
		t.Fatal("WebSocketPath should not be empty")
	}
	if connectResponse.BearerToken == "" {
		t.Fatal("BearerToken should not be empty")
	}
	if connectResponse.Scheme != "ws" {
		t.Fatalf("Scheme = %q, want %q", connectResponse.Scheme, "ws")
	}
	if !strings.Contains(connectResponse.WebSocketURL, connectResponse.WebSocketPath) {
		t.Fatalf("WebSocketURL = %q does not contain path %q", connectResponse.WebSocketURL, connectResponse.WebSocketPath)
	}
	if !strings.Contains(connectResponse.WebSocketURL, connectResponse.BearerToken) {
		t.Fatalf("WebSocketURL = %q does not contain bearer token", connectResponse.WebSocketURL)
	}

	socketServer := httptest.NewServer(server.Handler())
	defer socketServer.Close()

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + connectResponse.WebSocketPath + "?access_token=" + connectResponse.BearerToken
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	defer conn.Close()

	var ready map[string]any
	if err := conn.ReadJSON(&ready); err != nil {
		t.Fatalf("ReadJSON(ready) error = %v", err)
	}
	if ready["type"] != "mock.ready" {
		t.Fatalf("ready type = %v, want %q", ready["type"], "mock.ready")
	}

	payload := []byte(`{"jsonrpc":"2.0","id":"1","method":"initialize"}`)
	if err := conn.WriteMessage(websocket.TextMessage, payload); err != nil {
		t.Fatalf("WriteMessage() error = %v", err)
	}

	messageType, echoed, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("ReadMessage() error = %v", err)
	}
	if messageType != websocket.TextMessage {
		t.Fatalf("message type = %d, want %d", messageType, websocket.TextMessage)
	}
	if string(echoed) != string(payload) {
		t.Fatalf("echoed payload = %q, want %q", string(echoed), string(payload))
	}

	stopRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/stop", nil)
	stopRequest.Header.Set("Authorization", "Bearer "+token)
	stopRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(stopRecorder, stopRequest)

	if stopRecorder.Code != http.StatusOK {
		t.Fatalf("stop status code = %d, want %d", stopRecorder.Code, http.StatusOK)
	}

	listRequest = httptest.NewRequest(http.MethodGet, "/v1/runtimes", nil)
	listRequest.Header.Set("Authorization", "Bearer "+token)
	listRecorder = httptest.NewRecorder()
	server.Handler().ServeHTTP(listRecorder, listRequest)
	if listRecorder.Code != http.StatusOK {
		t.Fatalf("post-stop list status code = %d, want %d", listRecorder.Code, http.StatusOK)
	}
	if err := json.Unmarshal(listRecorder.Body.Bytes(), &listResponse); err != nil {
		t.Fatalf("Unmarshal(post-stop list) error = %v", err)
	}
	if len(listResponse.Runtimes) != 1 {
		t.Fatalf("len(post-stop runtimes) = %d, want 1", len(listResponse.Runtimes))
	}
	if listResponse.Runtimes[0].Status != runtime.StatusStopped {
		t.Fatalf("post-stop runtime status = %q, want %q", listResponse.Runtimes[0].Status, runtime.StatusStopped)
	}

	diagnosticsRequest = httptest.NewRequest(http.MethodGet, "/v1/diagnostics/summary", nil)
	diagnosticsRequest.Header.Set("Authorization", "Bearer "+token)
	diagnosticsRecorder = httptest.NewRecorder()
	server.Handler().ServeHTTP(diagnosticsRecorder, diagnosticsRequest)
	if diagnosticsRecorder.Code != http.StatusOK {
		t.Fatalf("post-stop diagnostics status code = %d, want %d", diagnosticsRecorder.Code, http.StatusOK)
	}
	if err := json.Unmarshal(diagnosticsRecorder.Body.Bytes(), &diagnostics); err != nil {
		t.Fatalf("Unmarshal(post-stop diagnostics) error = %v", err)
	}
	if diagnostics.Runtime.Stopped != 1 {
		t.Fatalf("Runtime.Stopped = %d, want 1", diagnostics.Runtime.Stopped)
	}

	reconnect, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		_ = reconnect.Close()
		t.Fatal("expected websocket dial to fail after runtime token revocation")
	}
}

func TestExternalStdioRuntimeLifecycleEndpoints(t *testing.T) {
	baseDir := t.TempDir()
	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}
	buildMockStdioAgent(t, filepath.Join(binDir, namedBinary("codex-acp")))
	t.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	server := newTestServerWithBaseDir(baseDir)
	token := pairDevice(t, server)

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/codex-acp/start", nil)
	startRequest.Header.Set("Authorization", "Bearer "+token)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var startResponse runtimeStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}
	if startResponse.Runtime.Transport != "stdio" {
		t.Fatalf("Transport = %q, want %q", startResponse.Runtime.Transport, "stdio")
	}

	connectRequest := httptest.NewRequest(http.MethodPost, "/v1/runtimes/"+startResponse.Runtime.ID+"/connect", nil)
	connectRequest.Header.Set("Authorization", "Bearer "+token)
	connectRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(connectRecorder, connectRequest)
	if connectRecorder.Code != http.StatusOK {
		t.Fatalf("connect status code = %d, want %d", connectRecorder.Code, http.StatusOK)
	}

	var connectResponse runtimeConnectResponse
	if err := json.Unmarshal(connectRecorder.Body.Bytes(), &connectResponse); err != nil {
		t.Fatalf("Unmarshal(connect) error = %v", err)
	}

	socketServer := httptest.NewServer(server.Handler())
	defer socketServer.Close()

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + connectResponse.WebSocketPath + "?access_token=" + connectResponse.BearerToken
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	defer conn.Close()

	var ready map[string]any
	if err := conn.ReadJSON(&ready); err != nil {
		t.Fatalf("ReadJSON(ready) error = %v", err)
	}
	if ready["type"] != "mock.ready" {
		t.Fatalf("ready type = %v, want %q", ready["type"], "mock.ready")
	}

	payload := []byte(`{"jsonrpc":"2.0","id":"1","method":"initialize"}`)
	if err := conn.WriteMessage(websocket.TextMessage, payload); err != nil {
		t.Fatalf("WriteMessage() error = %v", err)
	}

	messageType, echoed, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("ReadMessage() error = %v", err)
	}
	if messageType != websocket.TextMessage {
		t.Fatalf("message type = %d, want %d", messageType, websocket.TextMessage)
	}
	if string(echoed) != string(payload) {
		t.Fatalf("echoed payload = %q, want %q", string(echoed), string(payload))
	}

	_ = conn.Close()
	time.Sleep(150 * time.Millisecond)

	logsRequest := httptest.NewRequest(http.MethodGet, "/v1/runtimes/"+startResponse.Runtime.ID+"/logs", nil)
	logsRequest.Header.Set("Authorization", "Bearer "+token)
	logsRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(logsRecorder, logsRequest)
	if logsRecorder.Code != http.StatusOK {
		t.Fatalf("logs status code = %d, want %d", logsRecorder.Code, http.StatusOK)
	}

	var logsResponse runtimeLogsResponse
	if err := json.Unmarshal(logsRecorder.Body.Bytes(), &logsResponse); err != nil {
		t.Fatalf("Unmarshal(logs) error = %v", err)
	}
	foundStdoutReady := false
	foundStdoutEcho := false
	foundStdinPayload := false
	for _, entry := range logsResponse.Logs {
		switch {
		case entry.Stream == "stdout" && strings.Contains(entry.Message, "mock stdio ACP agent connected"):
			foundStdoutReady = true
		case entry.Stream == "stdout" && entry.Message == string(payload):
			foundStdoutEcho = true
		case entry.Stream == "stdin" && entry.Message == string(payload):
			foundStdinPayload = true
		}
	}
	if !foundStdoutReady {
		t.Fatal("expected stdout ready message to be retained in runtime logs")
	}
	if !foundStdoutEcho {
		t.Fatal("expected stdout ACP response to be retained in runtime logs")
	}
	if !foundStdinPayload {
		t.Fatal("expected stdin ACP request to be retained in runtime logs")
	}

	listRequest := httptest.NewRequest(http.MethodGet, "/v1/runtimes", nil)
	listRequest.Header.Set("Authorization", "Bearer "+token)
	listRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(listRecorder, listRequest)
	if listRecorder.Code != http.StatusOK {
		t.Fatalf("list status code = %d, want %d", listRecorder.Code, http.StatusOK)
	}

	var listResponse runtimesResponse
	if err := json.Unmarshal(listRecorder.Body.Bytes(), &listResponse); err != nil {
		t.Fatalf("Unmarshal(list) error = %v", err)
	}
	if len(listResponse.Runtimes) != 1 {
		t.Fatalf("len(runtimes) = %d, want 1 after stdio session cleanup", len(listResponse.Runtimes))
	}
	if listResponse.Runtimes[0].Status != runtime.StatusStopped {
		t.Fatalf("runtime status = %q, want %q after stdio session cleanup", listResponse.Runtimes[0].Status, runtime.StatusStopped)
	}
}

func newTestServer() *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)
}

func newTestServerWithBaseDir(baseDir string) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtime.NewSupervisorWithBaseDir(logger, baseDir, nil),
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)
}

func newTestServerWithStore(baseDir string, store *storage.SQLiteStore) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtime.NewSupervisorWithBaseDir(logger, baseDir, store),
		pairing.NewService(logger, store),
		gateway.New(logger, store),
		discovery.New(logger),
		nil,
		nil,
	)
}

type fakeRegistryStatusProvider struct {
	status acpregistry.Status
}

func (f fakeRegistryStatusProvider) Status() acpregistry.Status {
	return f.status
}

func pairDevice(t *testing.T, server *Server) string {
	t.Helper()

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	var startResponse pairStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        startResponse.Code,
		DeviceName:  "Pixel 9",
	})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}

	completeRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	completeRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(completeRecorder, completeRequest)

	if completeRecorder.Code != http.StatusOK {
		t.Fatalf("pair complete status = %d, want %d", completeRecorder.Code, http.StatusOK)
	}

	var completeResponse pairCompleteResponse
	if err := json.Unmarshal(completeRecorder.Body.Bytes(), &completeResponse); err != nil {
		t.Fatalf("Unmarshal(complete) error = %v", err)
	}
	return completeResponse.Token
}

func buildMockAgent(t *testing.T, baseDir string) {
	t.Helper()

	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	outputPath := filepath.Join(binDir, mockAgentBinaryName())
	command := exec.Command("go", "build", "-o", outputPath, "./cmd/mock-stdio-agent")
	command.Dir = filepath.Join("..", "..")
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		t.Fatalf("go build mock agent error = %v", err)
	}
}

func buildMockStdioAgent(t *testing.T, outputPath string) {
	t.Helper()

	command := exec.Command("go", "build", "-o", outputPath, "./cmd/mock-stdio-agent")
	command.Dir = filepath.Join("..", "..")
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		t.Fatalf("go build mock stdio agent error = %v", err)
	}
}

func mockAgentBinaryName() string {
	if os.PathSeparator == '\\' {
		return "mock-stdio-agent.exe"
	}
	return "mock-stdio-agent"
}

func namedBinary(name string) string {
	if os.PathSeparator == '\\' {
		return name + ".exe"
	}
	return name
}

func findAgentState(t *testing.T, agents []agentRuntimeState, id string) agentRuntimeState {
	t.Helper()

	for _, agent := range agents {
		if agent.ID == id {
			return agent
		}
	}
	t.Fatalf("agent %q not found", id)
	return agentRuntimeState{}
}
