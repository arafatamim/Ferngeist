package api

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
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

	"github.com/coder/websocket"
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

func TestStatusRejectsNonGetMethods(t *testing.T) {
	server := newTestServer()

	request := httptest.NewRequest(http.MethodPost, "/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusMethodNotAllowed)
	}

	var response errorResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.Error != "method not allowed" {
		t.Fatalf("error = %q, want %q", response.Error, "method not allowed")
	}
}

func TestStatusUsesConfiguredHelperName(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0", HelperName: "desk-alpha"},
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
	if response.Name != "desk-alpha" {
		t.Fatalf("Name = %q, want %q", response.Name, "desk-alpha")
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

func TestAdminPairingStartIncludesPayload(t *testing.T) {
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

	request := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	recorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response adminPairingResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.State != string(pairing.ChallengeStateActive) {
		t.Fatalf("State = %q, want %q", response.State, pairing.ChallengeStateActive)
	}
	if response.Host != "helper.example.com" {
		t.Fatalf("Host = %q, want %q", response.Host, "helper.example.com")
	}
	if response.Scheme != "https" {
		t.Fatalf("Scheme = %q, want %q", response.Scheme, "https")
	}
	if !strings.Contains(response.Payload, "ferngeist-helper://pair?") {
		t.Fatalf("Payload = %q, want ferngeist-helper URI", response.Payload)
	}
}

func TestAdminStatusIncludesPairingReachability(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := NewServer(
		config.Config{
			ListenAddr:      "127.0.0.1:0",
			AdminListenAddr: "127.0.0.1:5789",
			PublicBaseURL:   "https://helper.example.com",
		},
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

	request := httptest.NewRequest(http.MethodGet, "/admin/v1/status", nil)
	recorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusOK)
	}

	var response adminStatusResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	if response.AdminListenAddr != "127.0.0.1:5789" {
		t.Fatalf("AdminListenAddr = %q", response.AdminListenAddr)
	}
	if !response.PairingTarget.Reachable {
		t.Fatal("PairingTarget.Reachable should be true")
	}
	if response.PairingTarget.Scheme != "https" {
		t.Fatalf("PairingTarget.Scheme = %q", response.PairingTarget.Scheme)
	}
	if response.PairingTarget.Host != "helper.example.com" {
		t.Fatalf("PairingTarget.Host = %q", response.PairingTarget.Host)
	}
}

func TestAdminDevicesListAndRevoke(t *testing.T) {
	server := newTestServer()
	token := pairDevice(t, server)

	listRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/devices", nil)
	listRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(listRecorder, listRequest)

	if listRecorder.Code != http.StatusOK {
		t.Fatalf("list status code = %d, want %d", listRecorder.Code, http.StatusOK)
	}

	var listResponse adminDevicesResponse
	if err := json.Unmarshal(listRecorder.Body.Bytes(), &listResponse); err != nil {
		t.Fatalf("Unmarshal(list) error = %v", err)
	}
	if len(listResponse.Devices) != 1 {
		t.Fatalf("len(devices) = %d, want 1", len(listResponse.Devices))
	}

	revokeRequest := httptest.NewRequest(http.MethodDelete, "/admin/v1/devices/"+listResponse.Devices[0].DeviceID, nil)
	revokeRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(revokeRecorder, revokeRequest)

	if revokeRecorder.Code != http.StatusOK {
		t.Fatalf("revoke status code = %d, want %d", revokeRecorder.Code, http.StatusOK)
	}

	agentsRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	agentsRequest.Header.Set("Authorization", "Bearer "+token)
	agentsRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(agentsRecorder, agentsRequest)

	if agentsRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("agents status code after revoke = %d, want %d", agentsRecorder.Code, http.StatusUnauthorized)
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

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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
	if startResponse.ChallengeID == "" {
		t.Fatal("ChallengeID should not be empty")
	}

	challengeStatusRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/pairings/"+startResponse.ChallengeID, nil)
	challengeStatusRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(challengeStatusRecorder, challengeStatusRequest)
	if challengeStatusRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing status code = %d, want %d", challengeStatusRecorder.Code, http.StatusOK)
	}

	var challengeStatus adminPairingResponse
	if err := json.Unmarshal(challengeStatusRecorder.Body.Bytes(), &challengeStatus); err != nil {
		t.Fatalf("Unmarshal(admin pairing status) error = %v", err)
	}
	if challengeStatus.Code == "" {
		t.Fatal("Admin pairing status should include code")
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        challengeStatus.Code,
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

func TestPairingRoundTripWithCodeOnlyComplete(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

	challengeStatusRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/pairings/"+startResponse.ChallengeID, nil)
	challengeStatusRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(challengeStatusRecorder, challengeStatusRequest)
	if challengeStatusRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing status code = %d, want %d", challengeStatusRecorder.Code, http.StatusOK)
	}

	var challengeStatus adminPairingResponse
	if err := json.Unmarshal(challengeStatusRecorder.Body.Bytes(), &challengeStatus); err != nil {
		t.Fatalf("Unmarshal(admin pairing status) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		Code:       challengeStatus.Code,
		DeviceName: "Pixel 9",
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
}

func TestAuthRefreshRotatesCredentialToken(t *testing.T) {
	server := newConfiguredTestServer(config.Config{ListenAddr: "127.0.0.1:0", CredentialTTL: 24 * time.Hour})
	oldToken := pairDevice(t, server)

	refreshRequest := httptest.NewRequest(http.MethodPost, "/v1/auth/refresh", nil)
	refreshRequest.Header.Set("Authorization", "Bearer "+oldToken)
	refreshRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(refreshRecorder, refreshRequest)

	if refreshRecorder.Code != http.StatusOK {
		t.Fatalf("refresh status code = %d, want %d", refreshRecorder.Code, http.StatusOK)
	}

	var refreshed pairCompleteResponse
	if err := json.Unmarshal(refreshRecorder.Body.Bytes(), &refreshed); err != nil {
		t.Fatalf("Unmarshal(refresh) error = %v", err)
	}
	if refreshed.Token == "" {
		t.Fatal("refreshed token should not be empty")
	}
	if refreshed.Token == oldToken {
		t.Fatal("refresh should rotate the token")
	}

	oldRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	oldRequest.Header.Set("Authorization", "Bearer "+oldToken)
	oldRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(oldRecorder, oldRequest)
	if oldRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("old token status code = %d, want %d", oldRecorder.Code, http.StatusUnauthorized)
	}

	newRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	newRequest.Header.Set("Authorization", "Bearer "+refreshed.Token)
	newRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(newRecorder, newRequest)
	if newRecorder.Code != http.StatusOK {
		t.Fatalf("new token status code = %d, want %d", newRecorder.Code, http.StatusOK)
	}
}

func TestAuthRefreshRequiresValidCredential(t *testing.T) {
	server := newTestServer()

	request := httptest.NewRequest(http.MethodPost, "/v1/auth/refresh", nil)
	request.Header.Set("Authorization", "Bearer invalid-token")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
}

func TestPublicModeRequiresProofKeyForPairing(t *testing.T) {
	server := newConfiguredTestServer(config.Config{ListenAddr: "127.0.0.1:0", RequireProofOfPossession: true})

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

	challengeStatusRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/pairings/"+startResponse.ChallengeID, nil)
	challengeStatusRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(challengeStatusRecorder, challengeStatusRequest)
	var challengeStatus adminPairingResponse
	if err := json.Unmarshal(challengeStatusRecorder.Body.Bytes(), &challengeStatus); err != nil {
		t.Fatalf("Unmarshal(admin pairing status) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{ChallengeID: startResponse.ChallengeID, Code: challengeStatus.Code, DeviceName: "Pixel 9"})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	completeRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	completeRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(completeRecorder, completeRequest)
	if completeRecorder.Code != http.StatusBadRequest {
		t.Fatalf("complete status code = %d, want %d", completeRecorder.Code, http.StatusBadRequest)
	}
}

func TestLegacyBearerCredentialCanBeDisabled(t *testing.T) {
	server := newTestServer()
	server.cfg.AllowLegacyBearerCredentials = false
	token := pairDevice(t, server)

	request := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
}

func TestProofBoundCredentialRequiresSignedRequests(t *testing.T) {
	server := newTestServer()
	credential := pairDeviceWithProof(t, server)

	unsignedRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	unsignedRequest.Header.Set("Authorization", "Bearer "+credential.token)
	unsignedRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(unsignedRecorder, unsignedRequest)
	if unsignedRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("unsigned status code = %d, want %d", unsignedRecorder.Code, http.StatusUnauthorized)
	}

	signedRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	signProofRequest(t, signedRequest, credential, nil, time.Now().UTC(), "nonce-1")
	signedRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(signedRecorder, signedRequest)
	if signedRecorder.Code != http.StatusOK {
		t.Fatalf("signed status code = %d, want %d", signedRecorder.Code, http.StatusOK)
	}
}

func TestProofBoundCredentialRejectsReplay(t *testing.T) {
	server := newTestServer()
	credential := pairDeviceWithProof(t, server)
	proofTime := time.Now().UTC()

	firstRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	signProofRequest(t, firstRequest, credential, nil, proofTime, "nonce-replay")
	firstRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(firstRecorder, firstRequest)
	if firstRecorder.Code != http.StatusOK {
		t.Fatalf("first status code = %d, want %d", firstRecorder.Code, http.StatusOK)
	}

	secondRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	signProofRequest(t, secondRequest, credential, nil, proofTime, "nonce-replay")
	secondRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(secondRecorder, secondRequest)
	if secondRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("second status code = %d, want %d", secondRecorder.Code, http.StatusUnauthorized)
	}
}

func TestPairingLockoutTracksChallengeAcrossIPs(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

	for i := 0; i < pairingMaxAttempts; i++ {
		body, err := json.Marshal(pairCompleteRequest{ChallengeID: startResponse.ChallengeID, Code: "000000", DeviceName: "Pixel 9"})
		if err != nil {
			t.Fatalf("Marshal() error = %v", err)
		}
		completeRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
		completeRequest.RemoteAddr = fmt.Sprintf("203.0.113.%d:1234", i+1)
		completeRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(completeRecorder, completeRequest)
		if completeRecorder.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d status = %d, want %d", i+1, completeRecorder.Code, http.StatusUnauthorized)
		}
	}

	body, err := json.Marshal(pairCompleteRequest{ChallengeID: startResponse.ChallengeID, Code: "000000", DeviceName: "Pixel 9"})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	lockedRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	lockedRequest.RemoteAddr = "203.0.113.250:9999"
	lockedRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(lockedRecorder, lockedRequest)
	if lockedRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("locked status code = %d, want %d", lockedRecorder.Code, http.StatusTooManyRequests)
	}
}

func TestProofBoundCredentialRefreshRequiresProofAndRotatesToken(t *testing.T) {
	server := newTestServer()
	credential := pairDeviceWithProof(t, server)

	refreshRequest := httptest.NewRequest(http.MethodPost, "/v1/auth/refresh", nil)
	signProofRequest(t, refreshRequest, credential, nil, time.Now().UTC(), "nonce-refresh")
	refreshRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(refreshRecorder, refreshRequest)
	if refreshRecorder.Code != http.StatusOK {
		t.Fatalf("refresh status code = %d, want %d", refreshRecorder.Code, http.StatusOK)
	}

	var refreshed pairCompleteResponse
	if err := json.Unmarshal(refreshRecorder.Body.Bytes(), &refreshed); err != nil {
		t.Fatalf("Unmarshal(refresh) error = %v", err)
	}
	if refreshed.Token == credential.token {
		t.Fatal("refresh should rotate the token")
	}

	oldRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	signProofRequest(t, oldRequest, credential, nil, time.Now().UTC(), "nonce-old")
	oldRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(oldRecorder, oldRequest)
	if oldRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("old token status code = %d, want %d", oldRecorder.Code, http.StatusUnauthorized)
	}

	credential.token = refreshed.Token
	newRequest := httptest.NewRequest(http.MethodGet, "/v1/agents", nil)
	signProofRequest(t, newRequest, credential, nil, time.Now().UTC(), "nonce-new")
	newRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(newRecorder, newRequest)
	if newRecorder.Code != http.StatusOK {
		t.Fatalf("new token status code = %d, want %d", newRecorder.Code, http.StatusOK)
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
		time.Sleep(150 * time.Millisecond)
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

func TestAgentsRejectsNonGetMethods(t *testing.T) {
	server := newTestServer()
	token := pairDevice(t, server)

	request := httptest.NewRequest(http.MethodPost, "/v1/agents", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusMethodNotAllowed)
	}
}

func TestPairingRejectsWrongCode(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

func TestPairStartRequiresLocalApproval(t *testing.T) {
	server := newTestServer()

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)

	if startRecorder.Code != http.StatusForbidden {
		t.Fatalf("status code = %d, want %d", startRecorder.Code, http.StatusForbidden)
	}
}

func TestPairStartResponseDoesNotExposeCode(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("start status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var raw map[string]any
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &raw); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}
	if _, ok := raw["code"]; ok {
		t.Fatalf("public start response should not expose code: %v", raw)
	}
}

func TestPublicPairStatusReturnsStateWithoutCode(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

	statusRequest := httptest.NewRequest(http.MethodGet, "/v1/pair/status/"+startResponse.ChallengeID, nil)
	statusRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(statusRecorder, statusRequest)
	if statusRecorder.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", statusRecorder.Code, http.StatusOK)
	}

	var raw map[string]any
	if err := json.Unmarshal(statusRecorder.Body.Bytes(), &raw); err != nil {
		t.Fatalf("Unmarshal(status) error = %v", err)
	}
	if raw["challengeId"] != startResponse.ChallengeID {
		t.Fatalf("challengeId = %v, want %q", raw["challengeId"], startResponse.ChallengeID)
	}
	if raw["state"] != string(pairing.ChallengeStateActive) {
		t.Fatalf("state = %v, want %q", raw["state"], pairing.ChallengeStateActive)
	}
	if _, ok := raw["code"]; ok {
		t.Fatalf("public pair status should not expose code: %v", raw)
	}
}

func TestPairCompleteLocksOutAfterRepeatedMismatches(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

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

	for i := 0; i < pairingMaxAttempts; i++ {
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
			t.Fatalf("attempt %d status = %d, want %d", i+1, completeRecorder.Code, http.StatusUnauthorized)
		}
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        "000000",
		DeviceName:  "Pixel 9",
	})
	if err != nil {
		t.Fatalf("Marshal() error = %v", err)
	}
	lockedRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(body))
	lockedRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(lockedRecorder, lockedRequest)
	if lockedRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("locked status code = %d, want %d", lockedRecorder.Code, http.StatusTooManyRequests)
	}
}

func TestPairStartRateLimitedPerIP(t *testing.T) {
	server := newTestServer()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

	for i := 0; i < pairingBurstPerIP; i++ {
		startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
		startRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(startRecorder, startRequest)
		if startRecorder.Code != http.StatusOK {
			t.Fatalf("attempt %d status code = %d, want %d", i+1, startRecorder.Code, http.StatusOK)
		}
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("rate-limited status code = %d, want %d", startRecorder.Code, http.StatusTooManyRequests)
	}
}

func TestPairCompleteRejectsOversizedBody(t *testing.T) {
	server := newTestServer()

	oversized := bytes.Repeat([]byte("a"), int(jsonBodyLimit)+1)
	request := httptest.NewRequest(http.MethodPost, "/v1/pair/complete", bytes.NewReader(oversized))
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusBadRequest)
	}
}

func TestPairStartUsesConfiguredBurstLimit(t *testing.T) {
	server := newConfiguredTestServer(config.Config{ListenAddr: "127.0.0.1:0", PairingBurstPerIP: 1, PairingBurstGlobal: 1})

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status code = %d, want %d", armRecorder.Code, http.StatusOK)
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("first status code = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	secondRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	secondRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(secondRecorder, secondRequest)
	if secondRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("second status code = %d, want %d", secondRecorder.Code, http.StatusTooManyRequests)
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
	t.Cleanup(func() {
		_ = logSvc.Close()
	})

	logger := slog.New(slog.NewJSONHandler(io.MultiWriter(io.Discard, logSvc), nil))
	logger.Info("diagnostic export ready", slog.String("component", "test"))

	cfg := config.Config{ListenAddr: "127.0.0.1:0", LogDir: filepath.Join(baseDir, "logs"), StateDBPath: filepath.Join(baseDir, "state.db"), HelperName: "test-helper", AllowDiagnosticsExport: true}
	server := NewServer(
		cfg,
		BuildInfo{Version: "1.2.3", Commit: "abc123", BuiltAt: "2026-03-25T10:00:00Z", GoVersion: "go1.test"},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtime.NewSupervisorWithBaseDir(logger, baseDir, nil),
		pairing.NewServiceWithOptions(logger, nil, pairing.Options{AllowDiagnosticsExport: cfg.AllowDiagnosticsExport}),
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

func TestDiagnosticsExportRequiresElevatedScopeByDefault(t *testing.T) {
	server := newTestServer()
	token := pairDevice(t, server)

	request := httptest.NewRequest(http.MethodGet, "/v1/diagnostics/export", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, request)

	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusForbidden)
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
	if strings.Contains(connectResponse.WebSocketURL, connectResponse.BearerToken) {
		t.Fatalf("WebSocketURL = %q should not contain bearer token", connectResponse.WebSocketURL)
	}

	socketServer := httptest.NewServer(server.Handler())
	defer socketServer.Close()

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + connectResponse.WebSocketPath
	conn := dialTestWebSocket(t, wsURL, connectResponse.BearerToken)
	defer conn.CloseNow()

	var ready map[string]any
	msgType, data := readTestWebSocketMessage(t, conn)
	if msgType != websocket.MessageText {
		t.Fatalf("message type = %v, want %v", msgType, websocket.MessageText)
	}
	if err := json.Unmarshal(data, &ready); err != nil {
		t.Fatalf("Unmarshal(ready) error = %v", err)
	}
	if ready["type"] != "mock.ready" {
		t.Fatalf("ready type = %v, want %q", ready["type"], "mock.ready")
	}

	payload := []byte(`{"jsonrpc":"2.0","id":"1","method":"initialize"}`)
	if err := writeTestWebSocketMessage(conn, websocket.MessageText, payload); err != nil {
		t.Fatalf("Write() error = %v", err)
	}

	messageType, echoed := readTestWebSocketMessage(t, conn)
	if messageType != websocket.MessageText {
		t.Fatalf("message type = %v, want %v", messageType, websocket.MessageText)
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

	reconnect, _, err := dialTestWebSocketConn(wsURL, connectResponse.BearerToken)
	if err == nil {
		reconnect.CloseNow()
		t.Fatal("expected websocket dial to fail after runtime token revocation")
	}
}

func TestRuntimeRestartEndpointReturnsFreshConnectDescriptor(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	server := newConfiguredTestServerWithBaseDir(baseDir, config.Config{ListenAddr: "127.0.0.1:0", AllowRuntimeRestartEnv: true})
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

	var startResponse runtimeStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	restartBody, err := json.Marshal(runtimeRestartRequest{
		Env: map[string]string{"FERNGEIST_TEST_ENV": "restart-token"},
	})
	if err != nil {
		t.Fatalf("Marshal(restart) error = %v", err)
	}
	restartRequest := httptest.NewRequest(http.MethodPost, "/v1/runtimes/"+startResponse.Runtime.ID+"/restart", bytes.NewReader(restartBody))
	restartRequest.Header.Set("Authorization", "Bearer "+token)
	restartRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(restartRecorder, restartRequest)
	if restartRecorder.Code != http.StatusOK {
		t.Fatalf("restart status code = %d, want %d", restartRecorder.Code, http.StatusOK)
	}

	var restartResponse runtimeConnectResponse
	if err := json.Unmarshal(restartRecorder.Body.Bytes(), &restartResponse); err != nil {
		t.Fatalf("Unmarshal(restart) error = %v", err)
	}
	if restartResponse.RuntimeID == startResponse.Runtime.ID {
		t.Fatal("restart should hand back a new runtime id")
	}

	socketServer := httptest.NewServer(server.Handler())
	defer socketServer.Close()

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + restartResponse.WebSocketPath
	conn := dialTestWebSocket(t, wsURL, restartResponse.BearerToken)
	defer conn.CloseNow()

	var ready map[string]string
	msgType, data := readTestWebSocketMessage(t, conn)
	if msgType != websocket.MessageText {
		t.Fatalf("message type = %v, want %v", msgType, websocket.MessageText)
	}
	if err := json.Unmarshal(data, &ready); err != nil {
		t.Fatalf("Unmarshal(ready) error = %v", err)
	}
	if ready["env"] != "restart-token" {
		t.Fatalf("ready env = %q, want %q", ready["env"], "restart-token")
	}
}

func TestRuntimeRestartWithEnvRequiresElevatedScopeByDefault(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	runtimeSvc := runtime.NewSupervisorWithBaseDir(logger, baseDir, nil)
	server := NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtimeSvc,
		pairing.NewService(logger, nil),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)
	var token string
	t.Cleanup(func() {
		stopRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/stop", nil)
		stopRequest.Header.Set("Authorization", "Bearer "+token)
		stopRecorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(stopRecorder, stopRequest)
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = runtimeSvc.Shutdown(ctx)
		time.Sleep(150 * time.Millisecond)
	})
	token = pairDevice(t, server)

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

	restartBody, err := json.Marshal(runtimeRestartRequest{Env: map[string]string{"FERNGEIST_TEST_ENV": "forbidden"}})
	if err != nil {
		t.Fatalf("Marshal(restart) error = %v", err)
	}
	restartRequest := httptest.NewRequest(http.MethodPost, "/v1/runtimes/"+startResponse.Runtime.ID+"/restart", bytes.NewReader(restartBody))
	restartRequest.Header.Set("Authorization", "Bearer "+token)
	restartRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(restartRecorder, restartRequest)
	if restartRecorder.Code != http.StatusForbidden {
		t.Fatalf("restart status code = %d, want %d", restartRecorder.Code, http.StatusForbidden)
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

	server := newTestServerWithBaseDirAndRegistry(baseDir, fakeCatalogRegistrySource{
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
					},
				},
			},
		},
	})
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

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + connectResponse.WebSocketPath
	conn := dialTestWebSocket(t, wsURL, connectResponse.BearerToken)
	defer conn.CloseNow()

	var ready map[string]any
	msgType, data := readTestWebSocketMessage(t, conn)
	if msgType != websocket.MessageText {
		t.Fatalf("message type = %v, want %v", msgType, websocket.MessageText)
	}
	if err := json.Unmarshal(data, &ready); err != nil {
		t.Fatalf("Unmarshal(ready) error = %v", err)
	}
	if ready["type"] != "mock.ready" {
		t.Fatalf("ready type = %v, want %q", ready["type"], "mock.ready")
	}

	payload := []byte(`{"jsonrpc":"2.0","id":"1","method":"initialize"}`)
	if err := writeTestWebSocketMessage(conn, websocket.MessageText, payload); err != nil {
		t.Fatalf("Write() error = %v", err)
	}

	messageType, echoed := readTestWebSocketMessage(t, conn)
	if messageType != websocket.MessageText {
		t.Fatalf("message type = %v, want %v", messageType, websocket.MessageText)
	}
	if string(echoed) != string(payload) {
		t.Fatalf("echoed payload = %q, want %q", string(echoed), string(payload))
	}

	conn.CloseNow()
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
		case entry.Stream == "acp.stdout" && strings.Contains(entry.Message, "mock stdio ACP agent connected"):
			foundStdoutReady = true
		case entry.Stream == "acp.stdout" && entry.Message == string(payload):
			foundStdoutEcho = true
		case entry.Stream == "acp.stdin" && entry.Message == string(payload):
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

func TestWebSocketDisconnectStopsRuntimeAndAllowsReconnect(t *testing.T) {
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

	wsURL := "ws" + strings.TrimPrefix(socketServer.URL, "http") + connectResponse.WebSocketPath
	conn := dialTestWebSocket(t, wsURL, connectResponse.BearerToken)
	_, _ = readTestWebSocketMessage(t, conn)
	conn.CloseNow()

	time.Sleep(150 * time.Millisecond)

	restartStartRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/start", nil)
	restartStartRequest.Header.Set("Authorization", "Bearer "+token)
	restartStartRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(restartStartRecorder, restartStartRequest)
	if restartStartRecorder.Code != http.StatusOK {
		t.Fatalf("restart start status code = %d, want %d", restartStartRecorder.Code, http.StatusOK)
	}

	var restartStartResponse runtimeStartResponse
	if err := json.Unmarshal(restartStartRecorder.Body.Bytes(), &restartStartResponse); err != nil {
		t.Fatalf("Unmarshal(restart start) error = %v", err)
	}
	if restartStartResponse.Runtime.ID == startResponse.Runtime.ID {
		t.Fatal("expected a fresh runtime id after websocket disconnect cleanup")
	}

	reconnectRequest := httptest.NewRequest(http.MethodPost, "/v1/runtimes/"+restartStartResponse.Runtime.ID+"/connect", nil)
	reconnectRequest.Header.Set("Authorization", "Bearer "+token)
	reconnectRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(reconnectRecorder, reconnectRequest)
	if reconnectRecorder.Code != http.StatusOK {
		t.Fatalf("reconnect status code = %d, want %d", reconnectRecorder.Code, http.StatusOK)
	}

	stopRequest := httptest.NewRequest(http.MethodPost, "/v1/agents/mock-acp/stop", nil)
	stopRequest.Header.Set("Authorization", "Bearer "+token)
	stopRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(stopRecorder, stopRequest)
	if stopRecorder.Code != http.StatusOK {
		t.Fatalf("stop status code = %d, want %d", stopRecorder.Code, http.StatusOK)
	}
}

func dialTestWebSocket(t *testing.T, wsURL string, bearerToken ...string) *websocket.Conn {
	t.Helper()

	conn, _, err := dialTestWebSocketConn(wsURL, bearerToken...)
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	return conn
}

func dialTestWebSocketConn(wsURL string, bearerToken ...string) (*websocket.Conn, *http.Response, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	options := &websocket.DialOptions{}
	if len(bearerToken) > 0 && strings.TrimSpace(bearerToken[0]) != "" {
		options.HTTPHeader = http.Header{"Authorization": []string{"Bearer " + bearerToken[0]}}
	}
	return websocket.Dial(ctx, wsURL, options)
}

func readTestWebSocketMessage(t *testing.T, conn *websocket.Conn) (websocket.MessageType, []byte) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	messageType, payload, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("Read() error = %v", err)
	}
	return messageType, payload
}

func writeTestWebSocketMessage(conn *websocket.Conn, messageType websocket.MessageType, payload []byte) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return conn.Write(ctx, messageType, payload)
}

func newTestServer() *Server {
	return newConfiguredTestServer(config.Config{ListenAddr: "127.0.0.1:0"})
}

func newConfiguredTestServer(cfg config.Config) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = "127.0.0.1:0"
	}
	return NewServer(
		cfg,
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir("."),
		runtime.NewSupervisor(logger),
		pairing.NewServiceWithOptions(logger, nil, pairing.Options{
			ArmTTL:                 cfg.PairingArmTTL,
			CredentialTTL:          cfg.CredentialTTL,
			AllowDiagnosticsExport: cfg.AllowDiagnosticsExport,
			AllowRuntimeRestartEnv: cfg.AllowRuntimeRestartEnv,
		}),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)
}

func newTestServerWithBaseDir(baseDir string) *Server {
	return newConfiguredTestServerWithBaseDir(baseDir, config.Config{ListenAddr: "127.0.0.1:0"})
}

func newConfiguredTestServerWithBaseDir(baseDir string, cfg config.Config) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if cfg.ListenAddr == "" {
		cfg.ListenAddr = "127.0.0.1:0"
	}
	return NewServer(
		cfg,
		BuildInfo{},
		logger,
		catalog.NewWithBaseDir(baseDir),
		runtime.NewSupervisorWithBaseDir(logger, baseDir, nil),
		pairing.NewServiceWithOptions(logger, nil, pairing.Options{
			ArmTTL:                 cfg.PairingArmTTL,
			CredentialTTL:          cfg.CredentialTTL,
			AllowDiagnosticsExport: cfg.AllowDiagnosticsExport,
			AllowRuntimeRestartEnv: cfg.AllowRuntimeRestartEnv,
		}),
		gateway.New(logger, nil),
		discovery.New(logger),
		nil,
		nil,
	)
}

func newTestServerWithBaseDirAndRegistry(baseDir string, registrySource catalog.RegistrySource) *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(
		config.Config{ListenAddr: "127.0.0.1:0"},
		BuildInfo{},
		logger,
		catalog.NewWithBaseDirAndRegistry(baseDir, registrySource),
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

type fakeCatalogRegistrySource struct {
	snapshot acpregistry.Snapshot
	err      error
}

func (f fakeCatalogRegistrySource) Snapshot(context.Context) (acpregistry.Snapshot, error) {
	return f.snapshot, f.err
}

func (f fakeRegistryStatusProvider) Status() acpregistry.Status {
	return f.status
}

func pairDevice(t *testing.T, server *Server) string {
	t.Helper()

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status = %d, want %d", armRecorder.Code, http.StatusOK)
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("pair start status = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var startResponse pairStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	challengeStatusRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/pairings/"+startResponse.ChallengeID, nil)
	challengeStatusRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(challengeStatusRecorder, challengeStatusRequest)
	if challengeStatusRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing status = %d, want %d", challengeStatusRecorder.Code, http.StatusOK)
	}

	var challengeStatus adminPairingResponse
	if err := json.Unmarshal(challengeStatusRecorder.Body.Bytes(), &challengeStatus); err != nil {
		t.Fatalf("Unmarshal(admin pairing status) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID: startResponse.ChallengeID,
		Code:        challengeStatus.Code,
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

type proofTestCredential struct {
	token      string
	privateKey *ecdsa.PrivateKey
}

func pairDeviceWithProof(t *testing.T, server *Server) proofTestCredential {
	t.Helper()

	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey() error = %v", err)
	}
	publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		t.Fatalf("MarshalPKIXPublicKey() error = %v", err)
	}

	armRequest := httptest.NewRequest(http.MethodPost, "/admin/v1/pairings/start", nil)
	armRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(armRecorder, armRequest)
	if armRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing start status = %d, want %d", armRecorder.Code, http.StatusOK)
	}

	startRequest := httptest.NewRequest(http.MethodPost, "/v1/pair/start", nil)
	startRecorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(startRecorder, startRequest)
	if startRecorder.Code != http.StatusOK {
		t.Fatalf("pair start status = %d, want %d", startRecorder.Code, http.StatusOK)
	}

	var startResponse pairStartResponse
	if err := json.Unmarshal(startRecorder.Body.Bytes(), &startResponse); err != nil {
		t.Fatalf("Unmarshal(start) error = %v", err)
	}

	challengeStatusRequest := httptest.NewRequest(http.MethodGet, "/admin/v1/pairings/"+startResponse.ChallengeID, nil)
	challengeStatusRecorder := httptest.NewRecorder()
	server.AdminHandler().ServeHTTP(challengeStatusRecorder, challengeStatusRequest)
	if challengeStatusRecorder.Code != http.StatusOK {
		t.Fatalf("admin pairing status = %d, want %d", challengeStatusRecorder.Code, http.StatusOK)
	}

	var challengeStatus adminPairingResponse
	if err := json.Unmarshal(challengeStatusRecorder.Body.Bytes(), &challengeStatus); err != nil {
		t.Fatalf("Unmarshal(admin pairing status) error = %v", err)
	}

	body, err := json.Marshal(pairCompleteRequest{
		ChallengeID:    startResponse.ChallengeID,
		Code:           challengeStatus.Code,
		DeviceName:     "Pixel 9",
		ProofPublicKey: base64.RawURLEncoding.EncodeToString(publicKeyBytes),
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
	return proofTestCredential{token: completeResponse.Token, privateKey: privateKey}
}

func signProofRequest(t *testing.T, request *http.Request, credential proofTestCredential, body []byte, proofTime time.Time, nonce string) {
	t.Helper()
	request.Header.Set("Authorization", "Bearer "+credential.token)
	timestamp := fmt.Sprintf("%d", proofTime.UTC().Unix())
	bodyHash := sha256.Sum256(body)
	message := buildProofMessage(request.Method, requestPathWithRawQuery(request), credential.token, timestamp, nonce, base64.RawURLEncoding.EncodeToString(bodyHash[:]))
	digest := sha256.Sum256([]byte(message))
	signature, err := ecdsa.SignASN1(rand.Reader, credential.privateKey, digest[:])
	if err != nil {
		t.Fatalf("SignASN1() error = %v", err)
	}
	request.Header.Set(proofHeaderTimestamp, timestamp)
	request.Header.Set(proofHeaderNonce, nonce)
	request.Header.Set(proofHeaderSignature, base64.RawURLEncoding.EncodeToString(signature))
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
