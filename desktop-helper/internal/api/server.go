// Package api provides the HTTP control plane and ACP bridge for the Ferngeist
// desktop helper daemon. It exposes two servers:
//
//   - A public API for paired devices (pairing, agent control, ACP WebSocket)
//   - An admin API bound to localhost for local management and diagnostics
//
// The API stays intentionally small: a handful of control endpoints and a
// single ACP WebSocket path that bridges client connections to stdio-based
// ACP agent processes.
package api

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/discovery"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/gateway"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/logging"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/pairing"
	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
)

// Server is the main HTTP server that wires together all helper subsystems into
// a unified control plane. It manages two HTTP servers:
//   - httpServer: the public-facing API for paired mobile/desktop clients
//   - adminServer: a localhost-bound admin interface for local management
//
// The server handles pairing orchestration, agent lifecycle management, ACP
// protocol bridging via WebSocket, and diagnostic export. All requests are
// logged with structured metadata for troubleshooting.
type Server struct {
	httpServer  *http.Server // public API server
	adminServer *http.Server // localhost admin server
	logger      *slog.Logger
	cfg         config.Config
	build       BuildInfo        // version/commit info baked at compile time
	startedAt   time.Time        // used for uptime calculation
	now         func() time.Time // injectable clock for testability
	catalog     *catalog.Service
	runtime     *runtime.Supervisor
	pairing     *pairing.Service
	gateway     *gateway.Service
	discovery   *discovery.Service
	logs        *logging.Service
	registry    registryStatusProvider
	rateLimiter *pairingRateLimiter    // protects pairing endpoints from abuse
	attempts    *pairingAttemptTracker // tracks failed pairing attempts for lockout
	proofNonces *proofNonceTracker     // prevents replay of credential proofs
}

// protocolVersion identifies the current helper-to-client protocol version.
// Clients use this to detect compatibility mismatches.
const protocolVersion = "v1alpha1"

// Operational limits and security defaults.
const (
	acpWebSocketReadLimit = 1024 * 1024               // max ACP message size (1MB)
	acpWebSocketIOTimeout = 30 * time.Second          // read/write deadline per WebSocket frame
	jsonBodyLimit         = int64(16 * 1024)          // max JSON request body size
	pairingMaxAttempts    = 5                         // failures before temporary lockout
	pairingLockoutWindow  = 2 * time.Minute           // cooldown period after max attempts
	pairingStartRefill    = 5 * time.Second           // token bucket refill interval for /pair/start
	pairingCompleteRefill = 2 * time.Second           // token bucket refill interval for /pair/complete
	pairingBurstPerIP     = 5                         // burst allowance per source IP
	pairingBurstGlobal    = 30                        // global burst allowance across all IPs
	proofSkewWindow       = 5 * time.Minute           // allowed clock drift for proof timestamps
	proofReplayWindow     = 10 * time.Minute          // nonce validity window to prevent replay
	proofDomain           = "FERNGEIST-HTTP-PROOF-V1" // domain separator for proof signatures
)

// HTTP header names used for proof-of-possession credential verification.
const (
	proofHeaderTimestamp = "X-Ferngeist-Proof-Timestamp" // Unix timestamp of the proof
	proofHeaderNonce     = "X-Ferngeist-Proof-Nonce"     // random nonce to prevent replay
	proofHeaderSignature = "X-Ferngeist-Proof-Signature" // ECDSA signature over the proof message
)

// BuildInfo is injected from the build so status and diagnostics can describe
// the exact helper binary that produced a failure report.
type BuildInfo struct {
	Version   string `json:"version"`
	Commit    string `json:"commit,omitempty"`
	BuiltAt   string `json:"builtAt,omitempty"`
	GoVersion string `json:"goVersion,omitempty"`
}

// errorResponse is the standard JSON error envelope returned on API errors.
type errorResponse struct {
	Error string `json:"error"`
}

// statusResponse is returned by the public /v1/status endpoint with a summary
// of helper health, configuration, and runtime state.
type statusResponse struct {
	Name              string             `json:"name"`
	Version           string             `json:"version"`
	Build             BuildInfo          `json:"build"`
	ProtocolVersion   string             `json:"protocolVersion"`
	StartedAt         time.Time          `json:"startedAt"`
	UptimeSeconds     int64              `json:"uptimeSeconds"`
	ListenAddr        string             `json:"listenAddr"`
	LANEnabled        bool               `json:"lanEnabled"`
	PairedDeviceCount int                `json:"pairedDeviceCount"`
	Discovery         discovery.Snapshot `json:"discovery"`
	Remote            remoteStatus       `json:"remote"`
	Registry          acpregistry.Status `json:"registry"`
	RuntimeCounts     runtimeCounts      `json:"runtimeCounts"`
}

// remoteStatus describes the helper's remote access configuration as detected
// from the PublicBaseURL setting (tailscale, cloudflare tunnel, manual proxy, etc).
type remoteStatus struct {
	Configured bool   `json:"configured"`
	Mode       string `json:"mode,omitempty"`  // e.g. "tailscale", "cloudflare_tunnel", "lan_direct"
	Scope      string `json:"scope,omitempty"` // "public", "private", or "local"
	Healthy    bool   `json:"healthy"`
	Warning    string `json:"warning,omitempty"`
	PublicURL  string `json:"publicUrl,omitempty"`
}

// agentsResponse wraps the list of agents with their live runtime state.
type agentsResponse struct {
	Agents []agentRuntimeState `json:"agents"`
}

// agentRuntimeState merges static catalog metadata with live runtime status
// so clients can determine which agents are currently running.
type agentRuntimeState struct {
	catalog.Agent
	Running       bool   `json:"running"`
	RuntimeID     string `json:"runtimeId,omitempty"`
	RuntimeStatus string `json:"runtimeStatus,omitempty"`
}

// pairStartResponse is returned when a new pairing challenge is created.
type pairStartResponse struct {
	ChallengeID string    `json:"challengeId"`
	ExpiresAt   time.Time `json:"expiresAt"`
}

// pairCompleteRequest submits a pairing code and optional proof public key
// to complete the device pairing handshake.
type pairCompleteRequest struct {
	ChallengeID    string `json:"challengeId"`
	Code           string `json:"code"`
	DeviceName     string `json:"deviceName"`
	ProofPublicKey string `json:"proofPublicKey,omitempty"` // ECDSA public key for proof-of-possession
}

// pairCompleteResponse contains the credential issued after successful pairing.
type pairCompleteResponse struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	Token      string    `json:"token"`
	ExpiresAt  time.Time `json:"expiresAt"`
	Scopes     []string  `json:"scopes,omitempty"`
}

// pairStatusResponse exposes the state of a pairing challenge to public clients.
type pairStatusResponse struct {
	ChallengeID     string    `json:"challengeId"`
	ExpiresAt       time.Time `json:"expiresAt"`
	State           string    `json:"state"`
	CompletedDevice string    `json:"completedDevice,omitempty"`
	CompletedID     string    `json:"completedDeviceId,omitempty"`
	CompletedExpiry time.Time `json:"completedDeviceExpiresAt,omitempty"`
}

// adminPairingResponse is the admin-facing representation of a pairing challenge,
// including the deep-link payload for mobile clients.
type adminPairingResponse struct {
	ChallengeID     string    `json:"challengeId"`
	Code            string    `json:"code"`
	ExpiresAt       time.Time `json:"expiresAt"`
	State           string    `json:"state"`
	Scheme          string    `json:"scheme,omitempty"`
	Host            string    `json:"host,omitempty"`
	Payload         string    `json:"payload,omitempty"` // ferngeist-helper://pair?... deep link
	CompletedDevice string    `json:"completedDevice,omitempty"`
	CompletedID     string    `json:"completedDeviceId,omitempty"`
	CompletedExpiry time.Time `json:"completedDeviceExpiresAt,omitempty"`
}

// adminDevicesResponse wraps the list of paired devices for the admin endpoint.
type adminDevicesResponse struct {
	Devices []adminDeviceResponse `json:"devices"`
}

// adminStatusResponse is the comprehensive status returned by the admin endpoint,
// including pairing target info and active challenge state.
type adminStatusResponse struct {
	Name              string                 `json:"name"`
	Version           string                 `json:"version"`
	Build             BuildInfo              `json:"build"`
	ProtocolVersion   string                 `json:"protocolVersion"`
	StartedAt         time.Time              `json:"startedAt"`
	UptimeSeconds     int64                  `json:"uptimeSeconds"`
	ListenAddr        string                 `json:"listenAddr"`
	AdminListenAddr   string                 `json:"adminListenAddr"`
	LANEnabled        bool                   `json:"lanEnabled"`
	PairedDeviceCount int                    `json:"pairedDeviceCount"`
	Discovery         discovery.Snapshot     `json:"discovery"`
	Remote            remoteStatus           `json:"remote"`
	Registry          acpregistry.Status     `json:"registry"`
	RuntimeCounts     runtimeCounts          `json:"runtimeCounts"`
	PairingTarget     adminPairingTargetInfo `json:"pairingTarget"`
	ActivePairing     *adminPairingResponse  `json:"activePairing,omitempty"`
}

// adminPairingTargetInfo describes how a mobile client can reach this helper
// for pairing (scheme + host), or why pairing is unavailable.
type adminPairingTargetInfo struct {
	Reachable bool   `json:"reachable"`
	Scheme    string `json:"scheme,omitempty"`
	Host      string `json:"host,omitempty"`
	Error     string `json:"error,omitempty"`
}

// adminDeviceResponse represents a single paired device in admin listings.
type adminDeviceResponse struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	ExpiresAt  time.Time `json:"expiresAt"`
	Scopes     []string  `json:"scopes,omitempty"`
}

// pairingTarget holds the reachability information for mobile device pairing.
type pairingTarget struct {
	Scheme string
	Host   string
}

// runtimesResponse wraps the list of managed ACP runtimes.
type runtimesResponse struct {
	Runtimes []runtime.Runtime `json:"runtimes"`
}

// runtimeStartResponse is returned when an agent runtime is successfully started.
type runtimeStartResponse struct {
	Runtime runtime.Runtime `json:"runtime"`
}

// runtimeStopResponse is returned when an agent runtime is successfully stopped.
type runtimeStopResponse struct {
	Runtime runtime.Runtime `json:"runtime"`
}

// runtimeConnectResponse provides the WebSocket connection details and short-lived
// bearer token for establishing an ACP session with a running runtime.
type runtimeConnectResponse struct {
	RuntimeID      string    `json:"runtimeId"`
	Protocol       string    `json:"protocol"`
	Scheme         string    `json:"scheme"`
	Host           string    `json:"host"`
	WebSocketURL   string    `json:"websocketUrl"`
	WebSocketPath  string    `json:"websocketPath"`
	BearerToken    string    `json:"bearerToken"`
	TokenExpiresAt time.Time `json:"tokenExpiresAt"`
}

// runtimeRestartRequest optionally carries environment variable overrides
// for the restarted runtime.
type runtimeRestartRequest struct {
	Env map[string]string `json:"env"`
}

// runtimeLogsResponse contains the buffered log entries for a specific runtime.
type runtimeLogsResponse struct {
	RuntimeID string             `json:"runtimeId"`
	Logs      []runtime.LogEntry `json:"logs"`
}

// runtimeCounts aggregates the current state of all managed runtimes.
type runtimeCounts struct {
	Starting    int `json:"starting"`
	Total       int `json:"total"`
	Running     int `json:"running"`
	Stopping    int `json:"stopping"`
	Stopped     int `json:"stopped"`
	Failed      int `json:"failed"`
	CircuitOpen int `json:"circuitOpen"` // circuit breaker tripped, no new starts attempted
}

// diagnosticsSummaryResponse provides a compact health overview for monitoring.
type diagnosticsSummaryResponse struct {
	Runtime runtime.Summary `json:"runtime"`
}

// diagnosticsExportResponse is the full diagnostic bundle exported for bug reports.
// It includes helper metadata, runtime state, and bounded logs from all sources.
type diagnosticsExportResponse struct {
	GeneratedAt time.Time                     `json:"generatedAt"`
	Helper      diagnosticsHelperSnapshot     `json:"helper"`
	Runtime     runtime.Summary               `json:"runtime"`
	Runtimes    []runtime.Runtime             `json:"runtimes"`
	RuntimeLogs map[string][]runtime.LogEntry `json:"runtimeLogs"`
	HelperLogs  []string                      `json:"helperLogs"`
}

// diagnosticsHelperSnapshot captures the helper daemon's own configuration and
// state at the moment of diagnostic export.
type diagnosticsHelperSnapshot struct {
	Name            string             `json:"name"`
	Version         string             `json:"version"`
	Build           BuildInfo          `json:"build"`
	ProtocolVersion string             `json:"protocolVersion"`
	StartedAt       time.Time          `json:"startedAt"`
	UptimeSeconds   int64              `json:"uptimeSeconds"`
	ListenAddr      string             `json:"listenAddr"`
	LANEnabled      bool               `json:"lanEnabled"`
	HelperName      string             `json:"helperName"`
	LogDir          string             `json:"logDir"`
	StateDBPath     string             `json:"stateDbPath"`
	Discovery       discovery.Snapshot `json:"discovery"`
	Remote          remoteStatus       `json:"remote"`
	Registry        acpregistry.Status `json:"registry"`
}

type registryStatusProvider interface {
	Status() acpregistry.Status
}

// NewServer wires the helper's control plane and ACP bridge into one HTTP
// server. The API stays intentionally small: a handful of control endpoints and
// a single ACP WebSocket path.
func NewServer(
	cfg config.Config,
	build BuildInfo,
	logger *slog.Logger,
	catalogSvc *catalog.Service,
	runtimeSvc *runtime.Supervisor,
	pairingSvc *pairing.Service,
	gatewaySvc *gateway.Service,
	discoverySvc *discovery.Service,
	logSvc *logging.Service,
	registrySvc registryStatusProvider,
) *Server {
	cfg = normalizeSecurityConfig(cfg)
	server := &Server{
		logger:      logger.With("component", "api"),
		cfg:         cfg,
		build:       normalizeBuildInfo(build),
		startedAt:   time.Now().UTC(),
		now:         func() time.Time { return time.Now().UTC() },
		catalog:     catalogSvc,
		runtime:     runtimeSvc,
		pairing:     pairingSvc,
		gateway:     gatewaySvc,
		discovery:   discoverySvc,
		logs:        logSvc,
		registry:    registrySvc,
		rateLimiter: newPairingRateLimiter(cfg),
		attempts:    newPairingAttemptTracker(cfg),
		proofNonces: newProofNonceTracker(),
	}

	mux := http.NewServeMux()
	adminMux := http.NewServeMux()
	mux.HandleFunc("/healthz", server.handleHealth)
	mux.HandleFunc("/v1/status", server.handleStatus)
	mux.HandleFunc("/v1/agents", server.handleAgents)
	mux.HandleFunc("GET /v1/diagnostics/summary", server.handleDiagnosticsSummary)
	mux.HandleFunc("GET /v1/diagnostics/export", server.handleDiagnosticsExport)
	mux.HandleFunc("POST /v1/auth/refresh", server.handleAuthRefresh)
	mux.HandleFunc("/v1/pair/start", server.handlePairStart)
	mux.HandleFunc("/v1/pair/complete", server.handlePairComplete)
	mux.HandleFunc("GET /v1/pair/status/{challengeId}", server.handlePairStatus)
	mux.HandleFunc("/v1/runtimes", server.handleRuntimes)
	mux.HandleFunc("GET /v1/runtimes/{runtimeId}/logs", server.handleRuntimeLogs)
	mux.HandleFunc("POST /v1/agents/{agentId}/start", server.handleAgentStart)
	mux.HandleFunc("POST /v1/agents/{agentId}/stop", server.handleAgentStop)
	mux.HandleFunc("POST /v1/runtimes/{runtimeId}/connect", server.handleRuntimeConnect)
	mux.HandleFunc("POST /v1/runtimes/{runtimeId}/restart", server.handleRuntimeRestart)
	mux.HandleFunc("GET /v1/acp/{runtimeId}", server.handleACPWebSocket)
	adminMux.HandleFunc("GET /admin/v1/status", server.handleAdminStatus)
	adminMux.HandleFunc("POST /admin/v1/pairings/start", server.handleAdminPairingStart)
	adminMux.HandleFunc("GET /admin/v1/pairings/{challengeId}", server.handleAdminPairingStatus)
	adminMux.HandleFunc("DELETE /admin/v1/pairings/{challengeId}", server.handleAdminPairingCancel)
	adminMux.HandleFunc("GET /admin/v1/devices", server.handleAdminDevices)
	adminMux.HandleFunc("DELETE /admin/v1/devices/{deviceId}", server.handleAdminDeviceRevoke)

	adminAddr := strings.TrimSpace(cfg.AdminListenAddr)
	if adminAddr == "" {
		adminAddr = "127.0.0.1:0"
	}

	server.httpServer = &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           server.withRequestLogging(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	server.adminServer = &http.Server{
		Addr:              adminAddr,
		Handler:           server.withRequestLogging(adminMux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      20 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	return server
}

func normalizeSecurityConfig(cfg config.Config) config.Config {
	publicMode := strings.TrimSpace(cfg.PublicBaseURL) != ""
	if publicMode {
		if !cfg.RequireProofOfPossession {
			cfg.RequireProofOfPossession = true
		}
		if !cfg.AllowLegacyBearerCredentials {
			cfg.AllowLegacyBearerCredentials = false
		}
		return cfg
	}
	if !cfg.RequireProofOfPossession && !cfg.AllowLegacyBearerCredentials {
		cfg.AllowLegacyBearerCredentials = true
	}
	return cfg
}

func (s *Server) ListenAndServe() error {
	errCh := make(chan error, 2)
	go func() {
		s.logger.Info("api server listening", slog.String("addr", s.httpServer.Addr))
		errCh <- s.httpServer.ListenAndServe()
	}()
	go func() {
		s.logger.Info("admin api listening", slog.String("addr", s.adminServer.Addr))
		errCh <- s.adminServer.ListenAndServe()
	}()

	var firstErr error
	for i := 0; i < 2; i++ {
		err := <-errCh
		if err == nil || errors.Is(err, http.ErrServerClosed) {
			continue
		}
		if firstErr == nil {
			firstErr = err
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			_ = s.Shutdown(shutdownCtx)
			cancel()
		}
	}
	return firstErr
}

func (s *Server) Shutdown(ctx context.Context) error {
	return errors.Join(s.httpServer.Shutdown(ctx), s.adminServer.Shutdown(ctx))
}

func (s *Server) Handler() http.Handler {
	return s.httpServer.Handler
}

func (s *Server) AdminHandler() http.Handler {
	return s.adminServer.Handler
}

// withRequestLogging records one structured log entry per HTTP request so the
// helper can diagnose pairing, launch, and ACP handoff traffic from stdout or
// the rolling helper log file.
func (s *Server) withRequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startedAt := time.Now()
		wrapped := &loggingResponseWriter{ResponseWriter: w, statusCode: http.StatusOK}
		next.ServeHTTP(wrapped, r)
		s.logger.Info(
			"http request",
			slog.String("method", r.Method),
			slog.String("path", requestPath(r)),
			slog.Int("status", wrapped.statusCode),
			slog.Int("bytes", wrapped.bytesWritten),
			slog.Duration("duration", time.Since(startedAt)),
			slog.String("remote_addr", r.RemoteAddr),
		)
	})
}

type loggingResponseWriter struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int
}

func (w *loggingResponseWriter) WriteHeader(statusCode int) {
	w.statusCode = statusCode
	w.ResponseWriter.WriteHeader(statusCode)
}

func (w *loggingResponseWriter) Write(p []byte) (int, error) {
	written, err := w.ResponseWriter.Write(p)
	w.bytesWritten += written
	return written, err
}

func (w *loggingResponseWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func (w *loggingResponseWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hijacker, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("response writer does not support hijacking")
	}
	return hijacker.Hijack()
}

func (w *loggingResponseWriter) Push(target string, opts *http.PushOptions) error {
	pusher, ok := w.ResponseWriter.(http.Pusher)
	if !ok {
		return http.ErrNotSupported
	}
	return pusher.Push(target, opts)
}

func requestPath(r *http.Request) string {
	if r == nil || r.URL == nil {
		return ""
	}
	if r.URL.RawQuery == "" {
		return r.URL.Path
	}
	return r.URL.Path + "?" + sanitizeRawQuery(r.URL.RawQuery)
}

func sanitizeRawQuery(rawQuery string) string {
	values, err := url.ParseQuery(rawQuery)
	if err != nil {
		return rawQuery
	}
	for key := range values {
		if isSensitiveQueryKey(key) {
			values.Set(key, "redacted")
		}
	}
	return values.Encode()
}

func isSensitiveQueryKey(key string) bool {
	switch strings.ToLower(strings.TrimSpace(key)) {
	case "access_token", "token", "authorization":
		return true
	default:
		return false
	}
}

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, s.statusSnapshot(false))
}

// handleAgents merges static catalog data with live runtime state so clients do
// not need to reconstruct helper policy from multiple endpoints.
func (s *Server) handleAgents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeRead); !ok {
		return
	}

	runtimes := s.runtime.List()
	runtimeByAgent := make(map[string]runtime.Runtime, len(runtimes))
	for _, runtimeInfo := range runtimes {
		runtimeByAgent[runtimeInfo.AgentID] = runtimeInfo
	}

	agents := s.catalog.List()
	response := make([]agentRuntimeState, 0, len(agents))
	for _, agent := range agents {
		state := agentRuntimeState{Agent: agent}
		if runtimeInfo, ok := runtimeByAgent[agent.ID]; ok {
			state.Running = runtimeInfo.Status == "running"
			state.RuntimeID = runtimeInfo.ID
			state.RuntimeStatus = runtimeInfo.Status
		}
		response = append(response, state)
	}

	writeJSON(w, http.StatusOK, agentsResponse{Agents: response})
}

func (s *Server) handleDiagnosticsSummary(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeRead); !ok {
		return
	}

	writeJSON(w, http.StatusOK, diagnosticsSummaryResponse{
		Runtime: s.runtime.Summary(),
	})
}

// handleDiagnosticsExport produces a compact bug-report bundle. It includes
// helper metadata, active runtime state, and bounded logs, but intentionally
// does not try to become a transcript export format.
func (s *Server) handleDiagnosticsExport(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeDiagnosticsExport); !ok {
		return
	}

	runtimes := s.runtime.List()
	runtimeLogs := make(map[string][]runtime.LogEntry, len(runtimes))
	for _, runtimeInfo := range runtimes {
		logs, err := s.runtime.Logs(runtimeInfo.ID)
		if err != nil {
			continue
		}
		runtimeLogs[runtimeInfo.ID] = logs
	}

	helperLogs, err := s.tailHelperLogs(200)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to read helper logs")
		return
	}

	writeJSON(w, http.StatusOK, diagnosticsExportResponse{
		GeneratedAt: s.now(),
		Helper: diagnosticsHelperSnapshot{
			Name:            s.helperDisplayName(),
			Version:         s.build.Version,
			Build:           s.build,
			ProtocolVersion: protocolVersion,
			StartedAt:       s.startedAt,
			UptimeSeconds:   uptimeSeconds(s.startedAt, s.now()),
			ListenAddr:      s.cfg.ListenAddr,
			LANEnabled:      s.cfg.EnableLAN,
			HelperName:      s.cfg.HelperName,
			LogDir:          s.cfg.LogDir,
			StateDBPath:     s.cfg.StateDBPath,
			Discovery:       s.discovery.Snapshot(),
			Remote:          s.remoteStatus(true),
			Registry:        s.registryStatus(),
		},
		Runtime:     s.runtime.Summary(),
		Runtimes:    runtimes,
		RuntimeLogs: runtimeLogs,
		HelperLogs:  helperLogs,
	})
}

func (s *Server) handleAuthRefresh(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperCredential(w, r); !ok {
		return
	}
	refreshed, err := s.pairing.RefreshCredential(bearerToken(r))
	if err != nil {
		switch {
		case errors.Is(err, pairing.ErrCredentialMissing), errors.Is(err, pairing.ErrCredentialInvalid), errors.Is(err, pairing.ErrCredentialExpired):
			writeError(w, http.StatusUnauthorized, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "failed to refresh helper credential")
		}
		return
	}
	writeJSON(w, http.StatusOK, pairCompleteResponse{
		DeviceID:   refreshed.DeviceID,
		DeviceName: refreshed.DeviceName,
		Token:      refreshed.Token,
		ExpiresAt:  refreshed.ExpiresAt,
		Scopes:     refreshed.Scopes,
	})
}

func (s *Server) handleRuntimes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeRead); !ok {
		return
	}

	writeJSON(w, http.StatusOK, runtimesResponse{Runtimes: s.runtime.List()})
}

func (s *Server) handleRuntimeLogs(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeRead); !ok {
		return
	}

	runtimeID := r.PathValue("runtimeId")
	logs, err := s.runtime.Logs(runtimeID)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, runtimeLogsResponse{
		RuntimeID: runtimeID,
		Logs:      logs,
	})
}

func (s *Server) handlePairStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.allowPairingRequest(r, true) {
		writeError(w, http.StatusTooManyRequests, "pairing temporarily rate-limited")
		return
	}

	challenge, err := s.pairing.StartPairing()
	if err != nil {
		if errors.Is(err, pairing.ErrPairingNotArmed) {
			writeError(w, http.StatusForbidden, err.Error())
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to start pairing")
		return
	}

	writeJSON(w, http.StatusOK, pairStartResponse{
		ChallengeID: challenge.ID,
		ExpiresAt:   challenge.ExpiresAt,
	})
}

// handlePairComplete validates the pairing code and optional proof-of-possession
// to issue a credential. It enforces rate limiting, attempt-based lockout
// (per-IP and per-challenge), and proof public key validation before calling
// into the pairing service. On success, the attempt counters are reset.
func (s *Server) handlePairComplete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !s.allowPairingRequest(r, false) {
		writeError(w, http.StatusTooManyRequests, "pairing temporarily rate-limited")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, jsonBodyLimit)

	var request pairCompleteRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	challengeKey := pairingAttemptKey(request.ChallengeID)
	if source := requestSourceIP(r); s.attempts.isLocked(source, challengeKey, s.now()) {
		writeError(w, http.StatusTooManyRequests, "pairing temporarily locked")
		return
	}
	if s.cfg.RequireProofOfPossession && strings.TrimSpace(request.ProofPublicKey) == "" {
		writeError(w, http.StatusBadRequest, "proof public key required")
		return
	}
	if proofKey := strings.TrimSpace(request.ProofPublicKey); proofKey != "" {
		if _, err := parseProofPublicKey(proofKey); err != nil {
			writeError(w, http.StatusBadRequest, "invalid proof public key")
			return
		}
	}

	credential, err := s.pairing.CompletePairingWithProofKey(request.ChallengeID, request.Code, request.DeviceName, strings.TrimSpace(request.ProofPublicKey))
	if err != nil {
		switch {
		case errors.Is(err, pairing.ErrInvalidDeviceName):
			writeError(w, http.StatusBadRequest, err.Error())
		case errors.Is(err, pairing.ErrCodeMismatch):
			s.attempts.recordFailure(requestSourceIP(r), challengeKey, s.now())
			writeError(w, http.StatusUnauthorized, "pairing failed")
		case errors.Is(err, pairing.ErrChallengeNotFound), errors.Is(err, pairing.ErrChallengeExpired):
			writeError(w, http.StatusUnauthorized, "pairing failed")
		default:
			writeError(w, http.StatusInternalServerError, "failed to complete pairing")
		}
		return
	}
	s.attempts.reset(requestSourceIP(r), challengeKey)

	writeJSON(w, http.StatusOK, pairCompleteResponse{
		DeviceID:   credential.DeviceID,
		DeviceName: credential.DeviceName,
		Token:      credential.Token,
		ExpiresAt:  credential.ExpiresAt,
		Scopes:     credential.Scopes,
	})
}

func (s *Server) handlePairStatus(w http.ResponseWriter, r *http.Request) {
	status, err := s.pairing.GetChallengeStatus(r.PathValue("challengeId"))
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, publicPairStatusResponse(status))
}

func (s *Server) handleAdminPairingStart(w http.ResponseWriter, r *http.Request) {
	challenge, err := s.pairing.StartPairingWithLocalApproval()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to start pairing")
		return
	}

	target, _ := s.pairingTarget()

	writeJSON(w, http.StatusOK, s.adminPairingResponse(pairing.ChallengeStatus{
		ID:        challenge.ID,
		Code:      challenge.Code,
		ExpiresAt: challenge.ExpiresAt,
		State:     pairing.ChallengeStateActive,
	}, target))
}

func (s *Server) handleAdminStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	status := s.statusSnapshot(true)
	response := adminStatusResponse{
		Name:              status.Name,
		Version:           status.Version,
		Build:             status.Build,
		ProtocolVersion:   status.ProtocolVersion,
		StartedAt:         status.StartedAt,
		UptimeSeconds:     status.UptimeSeconds,
		ListenAddr:        status.ListenAddr,
		AdminListenAddr:   s.cfg.AdminListenAddr,
		LANEnabled:        status.LANEnabled,
		PairedDeviceCount: status.PairedDeviceCount,
		Discovery:         status.Discovery,
		Remote:            status.Remote,
		Registry:          status.Registry,
		RuntimeCounts:     status.RuntimeCounts,
	}
	if target, err := s.pairingTarget(); err != nil {
		response.PairingTarget = adminPairingTargetInfo{Reachable: false, Error: err.Error()}
	} else {
		response.PairingTarget = adminPairingTargetInfo{Reachable: true, Scheme: target.Scheme, Host: target.Host}
	}
	if challenge, ok := s.pairing.ActiveChallenge(); ok {
		activePairing := s.adminPairingResponse(challenge, pairingTarget{})
		response.ActivePairing = &activePairing
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *Server) handleAdminPairingStatus(w http.ResponseWriter, r *http.Request) {
	status, err := s.pairing.GetChallengeStatus(r.PathValue("challengeId"))
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	target, _ := s.pairingTarget()
	writeJSON(w, http.StatusOK, s.adminPairingResponse(status, target))
}

func (s *Server) handleAdminPairingCancel(w http.ResponseWriter, r *http.Request) {
	status, err := s.pairing.CancelChallenge(r.PathValue("challengeId"))
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	target, _ := s.pairingTarget()
	writeJSON(w, http.StatusOK, s.adminPairingResponse(status, target))
}

func (s *Server) handleAdminDevices(w http.ResponseWriter, _ *http.Request) {
	devices := s.pairing.ListDevices()
	response := make([]adminDeviceResponse, 0, len(devices))
	for _, device := range devices {
		response = append(response, adminDeviceResponse{
			DeviceID:   device.DeviceID,
			DeviceName: device.DeviceName,
			ExpiresAt:  device.ExpiresAt,
			Scopes:     append([]string(nil), device.Scopes...),
		})
	}
	writeJSON(w, http.StatusOK, adminDevicesResponse{Devices: response})
}

func (s *Server) handleAdminDeviceRevoke(w http.ResponseWriter, r *http.Request) {
	device, err := s.pairing.RevokeDevice(r.PathValue("deviceId"))
	if err != nil {
		if errors.Is(err, pairing.ErrDeviceNotFound) {
			writeError(w, http.StatusNotFound, err.Error())
			return
		}
		writeError(w, http.StatusInternalServerError, "failed to revoke paired device")
		return
	}
	writeJSON(w, http.StatusOK, adminDeviceResponse{
		DeviceID:   device.DeviceID,
		DeviceName: device.DeviceName,
		ExpiresAt:  device.ExpiresAt,
		Scopes:     append([]string(nil), device.Scopes...),
	})
}

func (s *Server) handleAgentStart(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeControl); !ok {
		return
	}

	agentID := r.PathValue("agentId")
	agent, err := s.catalog.Get(agentID)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	runtimeInfo, err := s.runtime.Start(agent)
	if err != nil {
		switch {
		case errors.Is(err, runtime.ErrAgentNotDetected), errors.Is(err, runtime.ErrUnsupportedLaunch), errors.Is(err, runtime.ErrRemoteStartNotAllowed):
			writeError(w, http.StatusConflict, err.Error())
		case errors.Is(err, runtime.ErrExecutableNotFound):
			writeError(w, http.StatusFailedDependency, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "failed to start runtime")
		}
		return
	}
	writeJSON(w, http.StatusOK, runtimeStartResponse{Runtime: runtimeInfo})
}

func (s *Server) handleAgentStop(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeControl); !ok {
		return
	}

	agentID := r.PathValue("agentId")
	runtimeInfo, err := s.runtime.StopByAgentID(agentID)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	s.gateway.Revoke(runtimeInfo.ID)
	writeJSON(w, http.StatusOK, runtimeStopResponse{Runtime: runtimeInfo})
}

// handleRuntimeConnect converts a running runtime into a short-lived ACP
// connection descriptor. The gateway token is registered here so the WebSocket
// endpoint can stay stateless apart from token validation.
func (s *Server) handleRuntimeConnect(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperScope(w, r, pairing.ScopeControl); !ok {
		return
	}

	runtimeID := r.PathValue("runtimeId")
	descriptor, err := s.runtime.Connect(runtimeID)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	s.gateway.Register(descriptor)
	writeJSON(w, http.StatusOK, connectResponseFromDescriptor(r, descriptor))
}

// handleRuntimeRestart performs an atomic restart of a runtime: it stops the
// existing runtime (revoking its gateway token), starts a fresh instance with
// optional environment overrides, and issues a new connection descriptor.
// The env scope check ensures only credentials with runtimeRestartEnv scope
// can pass custom environment variables.
func (s *Server) handleRuntimeRestart(w http.ResponseWriter, r *http.Request) {
	credential, ok := s.requireHelperScope(w, r, pairing.ScopeControl)
	if !ok {
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, jsonBodyLimit)
	runtimeID := r.PathValue("runtimeId")
	var request runtimeRestartRequest
	if r.Body != nil {
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil && !errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
	}
	if len(request.Env) > 0 {
		if err := credential.RequireScope(pairing.ScopeRuntimeRestartEnv); err != nil {
			writeError(w, http.StatusForbidden, err.Error())
			return
		}
	}

	restarted, err := s.runtime.Restart(runtimeID, request.Env)
	if err != nil {
		s.gateway.Revoke(runtimeID)
		s.runtime.AppendLog(runtimeID, "helper", "runtime restart failed: "+err.Error())
		switch {
		case errors.Is(err, runtime.ErrRuntimeNotFound):
			writeError(w, http.StatusNotFound, err.Error())
		case errors.Is(err, runtime.ErrRuntimeNotRunning):
			writeError(w, http.StatusConflict, err.Error())
		case errors.Is(err, runtime.ErrExecutableNotFound):
			writeError(w, http.StatusFailedDependency, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "failed to restart runtime")
		}
		return
	}

	s.gateway.Revoke(runtimeID)
	descriptor, err := s.runtime.Connect(restarted.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "restarted runtime is not connectable")
		return
	}
	s.gateway.Register(descriptor)
	writeJSON(w, http.StatusOK, connectResponseFromDescriptor(r, descriptor))
}

// handleACPWebSocket bridges a client WebSocket connection to a running runtime's
// stdio ACP process. The flow is:
//  1. Validate the gateway token (from query param or Authorization header)
//  2. Attach to the runtime's stdio pipes (stdin/stdout)
//  3. Launch two goroutines: WebSocket->stdio and stdio->WebSocket
//  4. Each direction is mirrored into the runtime log buffer for diagnostics
//  5. When either direction closes, both connections are torn down
//  6. If the token matches the gateway registration, the runtime is stopped
//
// This design keeps the WebSocket endpoint stateless apart from token validation,
// while the stdio attachment handles the actual ACP protocol framing.
func (s *Server) handleACPWebSocket(w http.ResponseWriter, r *http.Request) {
	runtimeID := r.PathValue("runtimeId")
	token := r.URL.Query().Get("access_token")
	if token == "" {
		token = bearerToken(r)
	}

	if err := s.gateway.Validate(runtimeID, token); err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
		return
	}

	clientConn, stdin, stdout, release, err := s.attachStdioRuntime(w, r, runtimeID)
	if err != nil {
		return
	}
	defer release()

	proxyDone := make(chan error, 2)
	go proxyWebSocketToStdio(clientConn, stdin, runtimeID, s.runtime.AppendLog, proxyDone)
	go proxyStdioToWebSocket(stdout, clientConn, runtimeID, s.runtime.AppendLog, proxyDone)
	<-proxyDone
	clientConn.CloseNow()
	<-proxyDone

	if s.gateway.RevokeIfMatches(runtimeID, token) {
		_, _ = s.runtime.StopByRuntimeID(runtimeID)
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}

// requireHelperCredential is the common auth gate for the control API. Pairing
// bootstrap and public status stay outside this path by design.
func (s *Server) requireHelperCredential(w http.ResponseWriter, r *http.Request) (pairing.Credential, bool) {
	if r == nil {
		writeError(w, http.StatusUnauthorized, pairing.ErrCredentialMissing.Error())
		return pairing.Credential{}, false
	}

	rawToken := bearerToken(r)
	credential, err := s.pairing.ValidateCredential(rawToken)
	if err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
		return pairing.Credential{}, false
	}
	if strings.TrimSpace(credential.ProofPublicKey) == "" && !s.cfg.AllowLegacyBearerCredentials {
		writeError(w, http.StatusUnauthorized, "legacy bearer credentials are disabled")
		return pairing.Credential{}, false
	}
	if err := s.verifyCredentialProof(r, rawToken, credential); err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
		return pairing.Credential{}, false
	}
	return credential, true
}

func (s *Server) requireHelperScope(w http.ResponseWriter, r *http.Request, scope string) (pairing.Credential, bool) {
	credential, ok := s.requireHelperCredential(w, r)
	if !ok {
		return pairing.Credential{}, false
	}
	if err := credential.RequireScope(scope); err != nil {
		writeError(w, http.StatusForbidden, err.Error())
		return pairing.Credential{}, false
	}
	return credential, true
}

func bearerToken(r *http.Request) string {
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if auth == "" {
		return ""
	}

	prefix := "Bearer "
	if !strings.HasPrefix(auth, prefix) {
		return ""
	}
	return strings.TrimSpace(strings.TrimPrefix(auth, prefix))
}

// websocketScheme determines the WebSocket scheme (ws/wss) to advertise in
// connection responses. It respects X-Forwarded-Proto for reverse proxy setups,
// falling back to the presence of TLS on the direct connection.
func websocketScheme(r *http.Request) string {
	if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")); forwarded != "" {
		switch strings.ToLower(forwarded) {
		case "https", "wss":
			return "wss"
		case "http", "ws":
			return "ws"
		}
	}
	if r.TLS != nil {
		return "wss"
	}
	return "ws"
}

// websocketHost returns the host that clients should use to reach this helper.
// It respects X-Forwarded-Host for reverse proxy configurations.
func websocketHost(r *http.Request) string {
	if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); forwarded != "" {
		return forwarded
	}
	return r.Host
}

// websocketHostWithPath returns the direct websocket endpoint without embedding
// auth material into the URL. Clients should use the returned bearer token via
// Authorization headers when opening the ACP socket.
func websocketHostWithPath(r *http.Request, path string) string {
	return fmt.Sprintf("%s%s", websocketHost(r), path)
}

func absoluteWebSocketURL(r *http.Request, path string) string {
	return fmt.Sprintf("%s://%s", websocketScheme(r), websocketHostWithPath(r, path))
}

func connectResponseFromDescriptor(r *http.Request, descriptor runtime.ConnectDescriptor) runtimeConnectResponse {
	return runtimeConnectResponse{
		RuntimeID:      descriptor.RuntimeID,
		Protocol:       descriptor.Protocol,
		Scheme:         websocketScheme(r),
		Host:           websocketHostWithPath(r, descriptor.WebSocketPath),
		WebSocketURL:   absoluteWebSocketURL(r, descriptor.WebSocketPath),
		WebSocketPath:  descriptor.WebSocketPath,
		BearerToken:    descriptor.BearerToken,
		TokenExpiresAt: descriptor.TokenExpiresAt,
	}
}

func (s *Server) statusSnapshot(includePublicURL bool) statusResponse {
	summary := s.runtime.Summary()
	now := s.now()
	return statusResponse{
		Name:              s.helperDisplayName(),
		Version:           s.build.Version,
		Build:             s.build,
		ProtocolVersion:   protocolVersion,
		StartedAt:         s.startedAt,
		UptimeSeconds:     uptimeSeconds(s.startedAt, now),
		ListenAddr:        s.cfg.ListenAddr,
		LANEnabled:        s.cfg.EnableLAN,
		PairedDeviceCount: s.pairing.ActiveDeviceCount(),
		Discovery:         s.discovery.Snapshot(),
		Remote:            s.remoteStatus(includePublicURL),
		Registry:          s.registryStatus(),
		RuntimeCounts: runtimeCounts{
			Starting:    summary.Starting,
			Total:       summary.Total,
			Running:     summary.Running,
			Stopping:    summary.Stopping,
			Stopped:     summary.Stopped,
			Failed:      summary.Failed,
			CircuitOpen: summary.CircuitOpen,
		},
	}
}

func (s *Server) adminPairingResponse(status pairing.ChallengeStatus, target pairingTarget) adminPairingResponse {
	response := adminPairingResponse{
		ChallengeID: status.ID,
		Code:        status.Code,
		ExpiresAt:   status.ExpiresAt,
		State:       string(status.State),
	}
	if target.Scheme != "" && target.Host != "" {
		response.Scheme = target.Scheme
		response.Host = target.Host
		response.Payload = buildPairingPayload(target, status.ID, status.Code)
	}
	if status.CompletedDevice != nil {
		response.CompletedID = status.CompletedDevice.DeviceID
		response.CompletedDevice = status.CompletedDevice.DeviceName
		response.CompletedExpiry = status.CompletedDevice.ExpiresAt
	}
	return response
}

func buildPairingPayload(target pairingTarget, challengeID, code string) string {
	values := url.Values{}
	values.Set("scheme", target.Scheme)
	values.Set("host", target.Host)
	values.Set("challengeId", challengeID)
	values.Set("code", code)
	return "ferngeist-helper://pair?" + values.Encode()
}

// pairingTarget determines how a mobile client can reach this helper for pairing.
// The resolution order is:
//  1. PublicBaseURL if configured (for remote/reverse proxy setups)
//  2. Listen address host if it's routable (non-loopback, non-unspecified)
//  3. Error if LAN is disabled (user must enable LAN or set public URL)
//  4. Error if listen address is loopback (user must use --lan flag)
//  5. First available LAN interface IPv4/IPv6 address
func (s *Server) pairingTarget() (pairingTarget, error) {
	publicBaseURL := strings.TrimSpace(s.cfg.PublicBaseURL)
	if publicBaseURL != "" {
		parsed, err := url.Parse(publicBaseURL)
		if err != nil || parsed.Scheme == "" || parsed.Host == "" {
			return pairingTarget{}, fmt.Errorf("configured public base URL is invalid")
		}
		return pairingTarget{Scheme: parsed.Scheme, Host: parsed.Host}, nil
	}

	listenHost, port, err := net.SplitHostPort(s.cfg.ListenAddr)
	if err != nil || strings.TrimSpace(port) == "" {
		return pairingTarget{}, fmt.Errorf("helper listen address is invalid")
	}
	host := strings.Trim(strings.TrimSpace(listenHost), "[]")
	if isRoutableHost(host) {
		return pairingTarget{Scheme: "http", Host: net.JoinHostPort(host, port)}, nil
	}
	if !s.cfg.EnableLAN {
		return pairingTarget{}, fmt.Errorf("helper is running in local-only mode; pairing requires a phone-reachable address. Set FERNGEIST_HELPER_PUBLIC_BASE_URL or run `ferngeist daemon run --lan`")
	}
	if host != "" && (strings.EqualFold(host, "localhost") || isLoopbackHost(host)) {
		return pairingTarget{}, fmt.Errorf("helper LAN pairing requires a non-loopback listen address; run `ferngeist daemon run --lan` or set FERNGEIST_HELPER_LISTEN_ADDR=0.0.0.0:5788")
	}

	lanHost, err := firstLANHost()
	if err != nil {
		return pairingTarget{}, err
	}
	return pairingTarget{Scheme: "http", Host: net.JoinHostPort(lanHost, port)}, nil
}

// isRoutableHost returns true if the host is reachable from other devices on
// the network. Loopback addresses, localhost, and unspecified (0.0.0.0) are
// considered non-routable.
func isRoutableHost(host string) bool {
	host = strings.Trim(strings.TrimSpace(host), "[]")
	if host == "" {
		return false
	}
	if strings.EqualFold(host, "localhost") {
		return false
	}
	if ip := net.ParseIP(host); ip != nil {
		return !ip.IsLoopback() && !ip.IsUnspecified()
	}
	return true
}

// isLoopbackHost returns true if the host resolves to a loopback IP address.
func isLoopbackHost(host string) bool {
	host = strings.Trim(strings.TrimSpace(host), "[]")
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// firstLANHost scans local network interfaces to find the first non-loopback,
// non-unspecified IPv4 address. If no IPv4 address is found, it falls back to
// the first global unicast IPv6 address. This is used for LAN pairing when the
// listen address is loopback.
func firstLANHost() (string, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", fmt.Errorf("failed to inspect local network interfaces")
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch value := addr.(type) {
			case *net.IPNet:
				ip = value.IP
			case *net.IPAddr:
				ip = value.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
				continue
			}
			if v4 := ip.To4(); v4 != nil {
				return v4.String(), nil
			}
			if ip.IsGlobalUnicast() {
				return ip.String(), nil
			}
		}
	}
	return "", fmt.Errorf("no LAN address is available for pairing")
}

func (s *Server) helperDisplayName() string {
	if name := strings.TrimSpace(s.cfg.HelperName); name != "" {
		return name
	}
	return "ferngeist-helper"
}

// remoteStatus builds a description of how this helper is reachable from outside.
// It classifies the connection mode (tailscale, cloudflare tunnel, LAN, local)
// and detects the network scope (public vs private) for security warnings.
func (s *Server) remoteStatus(includePublicURL bool) remoteStatus {
	publicBaseURL := strings.TrimSpace(s.cfg.PublicBaseURL)
	status := remoteStatus{
		Configured: publicBaseURL != "",
		Healthy:    true,
	}

	switch {
	case publicBaseURL != "":
		status.Mode, status.Scope, status.Warning, status.Healthy = classifyRemoteURL(publicBaseURL)
		if warning := s.remoteSecurityWarning(status.Scope); warning != "" {
			status.Warning = strings.TrimSpace(strings.Join([]string{status.Warning, warning}, "; "))
		}
		if includePublicURL {
			status.PublicURL = publicBaseURL
		}
	case s.cfg.EnableLAN:
		status.Mode = "lan_direct"
		status.Scope = "lan"
	default:
		status.Mode = "local_only"
		status.Scope = "local"
	}

	return status
}

// remoteSecurityWarning returns security warnings for the current remote mode.
// Public mode should require proof-of-possession and should not allow legacy
// bearer credentials.
func (s *Server) remoteSecurityWarning(scope string) string {
	if strings.TrimSpace(scope) != "public" {
		return ""
	}
	warnings := make([]string, 0, 2)
	if !s.cfg.RequireProofOfPossession {
		warnings = append(warnings, "public mode should require proof-of-possession pairing")
	}
	if s.cfg.AllowLegacyBearerCredentials {
		warnings = append(warnings, "legacy bearer credentials are still allowed")
	}
	return strings.Join(warnings, "; ")
}

// classifyRemoteURL inspects a public base URL to determine the remote access
// mode and network scope. It recognizes Tailscale (.ts.net), Cloudflare tunnels
// (.trycloudflare.com), and private hostnames (.local, .internal, .lan).
// It also flags non-HTTPS URLs as unhealthy.
func classifyRemoteURL(raw string) (mode, scope, warning string, healthy bool) {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return "configured", "unknown", "public base URL is invalid", false
	}

	host := strings.ToLower(parsed.Hostname())
	mode = "reverse_proxy"
	scope = "public"
	healthy = true

	switch {
	case strings.HasSuffix(host, ".ts.net") || strings.Contains(host, "tailscale"):
		mode = "tailscale"
		scope = "private"
	case strings.HasSuffix(host, ".trycloudflare.com") || strings.Contains(host, "cloudflare"):
		mode = "cloudflare_tunnel"
		scope = "public"
	case isPrivateHostname(host):
		mode = "manual_reverse_proxy"
		scope = "private"
	default:
		mode = "manual_reverse_proxy"
		scope = "public"
	}

	if parsed.Scheme != "https" {
		warning = "remote access should use HTTPS"
	}

	return mode, scope, warning, healthy
}

// isPrivateHostname returns true for hostnames that are clearly internal
// (localhost, .local, .internal, .lan suffixes).
func isPrivateHostname(host string) bool {
	if host == "localhost" || host == "127.0.0.1" || host == "::1" {
		return true
	}
	if strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".internal") || strings.HasSuffix(host, ".lan") {
		return true
	}
	return false
}

// websocketReadContext returns a context with the configured ACP WebSocket
// read timeout. Each incoming message read is bounded by this deadline.
func websocketReadContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), acpWebSocketIOTimeout)
}

// websocketWriteContext returns a context with the configured ACP WebSocket
// write timeout. Each outgoing message write is bounded by this deadline.
func websocketWriteContext() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), acpWebSocketIOTimeout)
}

// proxyWebSocketToStdio adapts ACP-over-WebSocket client messages into the
// newline-delimited stdio framing used by CLI ACP servers. It also mirrors the
// raw client payload into the runtime log buffer as `acp.stdin` traffic.
// The loop exits on normal WebSocket closure or any read/write error.
func proxyWebSocketToStdio(src *websocket.Conn, stdin io.WriteCloser, runtimeID string, appendLog func(string, string, string), done chan<- error) {
	defer stdin.Close()
	for {
		ctx, cancel := websocketReadContext()
		messageType, payload, err := src.Read(ctx)
		cancel()
		if err != nil {
			if closeStatus := websocket.CloseStatus(err); closeStatus == websocket.StatusNormalClosure || closeStatus == websocket.StatusGoingAway {
				done <- io.EOF
				return
			}
			done <- err
			return
		}
		if messageType != websocket.MessageText && messageType != websocket.MessageBinary {
			continue
		}
		if appendLog != nil {
			appendLog(runtimeID, "acp.stdin", string(payload))
		}
		if _, err := stdin.Write(append(payload, '\n')); err != nil {
			done <- err
			return
		}
	}
}

// proxyStdioToWebSocket performs the reverse adaptation by streaming each stdio
// line as one WebSocket text frame. Each line is also mirrored into the runtime
// log buffer as `acp.stdout` traffic before being forwarded to the client.
// The scanner buffer is capped at 1MB to match the WebSocket read limit.
func proxyStdioToWebSocket(src io.Reader, dst *websocket.Conn, runtimeID string, appendLog func(string, string, string), done chan<- error) {
	scanner := bufio.NewScanner(src)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, 1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		if appendLog != nil {
			appendLog(runtimeID, "acp.stdout", line)
		}
		ctx, cancel := websocketWriteContext()
		err := dst.Write(ctx, websocket.MessageText, []byte(line))
		cancel()
		if err != nil {
			if closeStatus := websocket.CloseStatus(err); closeStatus == websocket.StatusNormalClosure || closeStatus == websocket.StatusGoingAway {
				done <- io.EOF
				return
			}
			done <- err
			return
		}
	}
	if err := scanner.Err(); err != nil {
		done <- err
		return
	}
	done <- io.EOF
}

func (s *Server) attachStdioRuntime(w http.ResponseWriter, r *http.Request, runtimeID string) (*websocket.Conn, io.WriteCloser, io.ReadCloser, func(), error) {
	stdin, stdout, release, err := s.runtime.AttachStdio(runtimeID)
	if err != nil {
		writeError(w, http.StatusConflict, err.Error())
		return nil, nil, nil, nil, err
	}

	clientConn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		release()
		s.logger.Error("websocket upgrade failed", slog.String("error", err.Error()))
		return nil, nil, nil, nil, err
	}
	clientConn.SetReadLimit(acpWebSocketReadLimit)
	return clientConn, stdin, stdout, release, nil
}

func (s *Server) tailHelperLogs(limit int) ([]string, error) {
	if s.logs == nil {
		return nil, nil
	}
	return s.logs.TailLines(limit)
}

func (s *Server) registryStatus() acpregistry.Status {
	if s.registry == nil {
		return acpregistry.Status{State: "disabled"}
	}
	return s.registry.Status()
}

func normalizeBuildInfo(build BuildInfo) BuildInfo {
	if strings.TrimSpace(build.Version) == "" {
		build.Version = "dev"
	}
	return build
}

func publicPairStatusResponse(status pairing.ChallengeStatus) pairStatusResponse {
	response := pairStatusResponse{
		ChallengeID: status.ID,
		ExpiresAt:   status.ExpiresAt,
		State:       string(status.State),
	}
	if status.CompletedDevice != nil {
		response.CompletedDevice = status.CompletedDevice.DeviceName
		response.CompletedID = status.CompletedDevice.DeviceID
		response.CompletedExpiry = status.CompletedDevice.ExpiresAt
	}
	return response
}

func uptimeSeconds(startedAt, now time.Time) int64 {
	if startedAt.IsZero() || now.Before(startedAt) {
		return 0
	}
	return int64(now.Sub(startedAt).Seconds())
}

// tokenBucket implements a simple token bucket rate limiter with continuous
// refill. Tokens are refilled based on elapsed time since the last access,
// up to a maximum capacity.
type tokenBucket struct {
	tokens float64
	last   time.Time
}

// pairingRateLimiter protects the pairing endpoints from brute-force attacks
// by maintaining separate token buckets for:
//   - Per-IP start requests (pairStart)
//   - Per-IP complete requests (pairComplete)
//   - Global start requests (across all IPs)
//   - Global complete requests (across all IPs)
//
// This allows burst tolerance for legitimate use while throttling sustained abuse.
type pairingRateLimiter struct {
	mu             sync.Mutex
	ipStartBuckets map[string]tokenBucket // per-IP buckets for /pair/start
	ipDoneBuckets  map[string]tokenBucket // per-IP buckets for /pair/complete
	globalStart    tokenBucket            // global bucket for /pair/start
	globalDone     tokenBucket            // global bucket for /pair/complete
	burstPerIP     int                    // max tokens per IP bucket
	globalBurst    int                    // max tokens for global bucket
	startRefill    time.Duration          // refill interval for start buckets
	completeRefill time.Duration          // refill interval for complete buckets
}

// newPairingRateLimiter creates a rate limiter with configuration-aware defaults.
// Zero or negative config values fall back to compiled-in constants.
func newPairingRateLimiter(cfg config.Config) *pairingRateLimiter {
	now := time.Now().UTC()
	burstPerIP := cfg.PairingBurstPerIP
	if burstPerIP <= 0 {
		burstPerIP = pairingBurstPerIP
	}
	globalBurst := cfg.PairingBurstGlobal
	if globalBurst <= 0 {
		globalBurst = pairingBurstGlobal
	}
	startRefill := cfg.PairingStartRefill
	if startRefill <= 0 {
		startRefill = pairingStartRefill
	}
	completeRefill := cfg.PairingCompleteRefill
	if completeRefill <= 0 {
		completeRefill = pairingCompleteRefill
	}
	return &pairingRateLimiter{
		ipStartBuckets: make(map[string]tokenBucket),
		ipDoneBuckets:  make(map[string]tokenBucket),
		globalStart:    tokenBucket{tokens: float64(globalBurst), last: now},
		globalDone:     tokenBucket{tokens: float64(globalBurst), last: now},
		burstPerIP:     burstPerIP,
		globalBurst:    globalBurst,
		startRefill:    startRefill,
		completeRefill: completeRefill,
	}
}

// allow checks whether a pairing request should be permitted. It consumes one
// token from both the per-IP bucket and the global bucket for the relevant
// phase (start vs complete). Both must have tokens available.
func (l *pairingRateLimiter) allow(ip string, isStart bool, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if ip == "" {
		ip = "unknown"
	}
	now = now.UTC()
	if isStart {
		bucket := l.ipStartBuckets[ip]
		if !takeToken(&bucket, now, l.burstPerIP, l.startRefill) {
			l.ipStartBuckets[ip] = bucket
			return false
		}
		l.ipStartBuckets[ip] = bucket
		if !takeToken(&l.globalStart, now, l.globalBurst, l.startRefill) {
			return false
		}
		return true
	}
	bucket := l.ipDoneBuckets[ip]
	if !takeToken(&bucket, now, l.burstPerIP, l.completeRefill) {
		l.ipDoneBuckets[ip] = bucket
		return false
	}
	l.ipDoneBuckets[ip] = bucket
	if !takeToken(&l.globalDone, now, l.globalBurst, l.completeRefill) {
		return false
	}
	return true
}

// takeToken attempts to consume one token from the bucket. If the bucket is
// empty, it refills based on elapsed time before checking again. Returns false
// if no token is available even after refill.
func takeToken(bucket *tokenBucket, now time.Time, capacity int, refillEvery time.Duration) bool {
	if bucket.last.IsZero() {
		bucket.last = now
		bucket.tokens = float64(capacity)
	}
	if refillEvery > 0 {
		elapsed := now.Sub(bucket.last)
		if elapsed > 0 {
			bucket.tokens += elapsed.Seconds() / refillEvery.Seconds()
			if bucket.tokens > float64(capacity) {
				bucket.tokens = float64(capacity)
			}
			bucket.last = now
		}
	}
	if bucket.tokens < 1 {
		return false
	}
	bucket.tokens -= 1
	return true
}

// pairingAttemptTracker tracks failed pairing attempts per source IP and per
// challenge. After maxAttempts failures, the tracker enters a lockout state
// for lockoutWindow duration, during which further attempts are rejected.
type pairingAttemptTracker struct {
	mu                sync.Mutex
	ipAttempts        map[string]attemptState
	challengeAttempts map[string]attemptState
	maxAttempts       int
	lockoutWindow     time.Duration
}

// attemptState holds the failure count and lockout deadline for a single key.
type attemptState struct {
	failures    int
	lockedUntil time.Time
}

// newPairingAttemptTracker creates an attempt tracker with configuration-aware
// defaults. Zero or negative config values fall back to compiled-in constants.
func newPairingAttemptTracker(cfg config.Config) *pairingAttemptTracker {
	maxAttempts := cfg.PairingMaxAttempts
	if maxAttempts <= 0 {
		maxAttempts = pairingMaxAttempts
	}
	lockoutWindow := cfg.PairingLockoutWindow
	if lockoutWindow <= 0 {
		lockoutWindow = pairingLockoutWindow
	}
	return &pairingAttemptTracker{
		ipAttempts:        make(map[string]attemptState),
		challengeAttempts: make(map[string]attemptState),
		maxAttempts:       maxAttempts,
		lockoutWindow:     lockoutWindow,
	}
}

// recordFailure increments the failure count for both the source IP and the
// challenge key. If failures reach maxAttempts, the lockout deadline is set.
func (t *pairingAttemptTracker) recordFailure(ip, challengeKey string, now time.Time) {
	if ip == "" {
		ip = "unknown"
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.ipAttempts[ip] = nextAttemptState(t.ipAttempts[ip], now, t.maxAttempts, t.lockoutWindow)
	if challengeKey != "" {
		t.challengeAttempts[challengeKey] = nextAttemptState(t.challengeAttempts[challengeKey], now, t.maxAttempts, t.lockoutWindow)
	}
}

// isLocked returns true if either the source IP or the challenge key is
// currently in a lockout period. Expired lockouts are automatically cleaned up.
func (t *pairingAttemptTracker) isLocked(ip, challengeKey string, now time.Time) bool {
	if ip == "" {
		ip = "unknown"
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if attemptLocked(t.ipAttempts, ip, now) {
		return true
	}
	if challengeKey != "" && attemptLocked(t.challengeAttempts, challengeKey, now) {
		return true
	}
	return false
}

// reset clears the failure state for a source IP and challenge key after
// successful pairing.
func (t *pairingAttemptTracker) reset(ip, challengeKey string) {
	if ip == "" {
		ip = "unknown"
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.ipAttempts, ip)
	if challengeKey != "" {
		delete(t.challengeAttempts, challengeKey)
	}
}

// nextAttemptState advances the failure counter and sets a lockout deadline
// if the max attempts threshold is reached.
func nextAttemptState(state attemptState, now time.Time, maxAttempts int, lockoutWindow time.Duration) attemptState {
	state.failures++
	if state.failures >= maxAttempts {
		state.lockedUntil = now.UTC().Add(lockoutWindow)
		state.failures = 0 // reset counter so next batch starts fresh after lockout expires
	}
	return state
}

// attemptLocked checks whether a specific key is currently locked. It also
// performs lazy cleanup of expired lockouts to prevent unbounded map growth.
func attemptLocked(attempts map[string]attemptState, key string, now time.Time) bool {
	state, ok := attempts[key]
	if !ok {
		return false
	}
	if state.lockedUntil.IsZero() {
		return false
	}
	if now.UTC().After(state.lockedUntil) {
		delete(attempts, key)
		return false
	}
	return true
}

// pairingAttemptKey normalizes a challenge ID into a tracking key. Empty
// challenge IDs are treated as "active" to track attempts against the current
// challenge collectively.
func pairingAttemptKey(challengeID string) string {
	challengeID = strings.TrimSpace(challengeID)
	if challengeID == "" {
		return "active"
	}
	return challengeID
}

// requestSourceIP extracts the source IP address from an HTTP request for
// rate limiting and attempt tracking purposes.
func requestSourceIP(r *http.Request) string {
	if r == nil {
		return ""
	}
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err == nil {
		return host
	}
	return strings.TrimSpace(r.RemoteAddr)
}

// allowPairingRequest is the public entry point for rate limiting checks.
func (s *Server) allowPairingRequest(r *http.Request, isStart bool) bool {
	if s == nil || s.rateLimiter == nil {
		return true
	}
	return s.rateLimiter.allow(requestSourceIP(r), isStart, s.now())
}

// verifyCredentialProof validates a proof-of-possession signature attached to
// an authenticated request. The proof mechanism prevents credential theft by
// requiring the client to prove it holds the private key corresponding to the
// public key registered during pairing.
//
// The verification process:
//  1. Extract timestamp, nonce, and signature from HTTP headers
//  2. Validate timestamp is within the allowed skew window (prevents old requests)
//  3. Check nonce against the replay tracker (prevents duplicate requests)
//  4. Compute SHA-256 hash of the request body
//  5. Build a canonical proof message from method, path, token, timestamp, nonce, body hash
//  6. Verify the ECDSA signature against the credential's registered public key
//
// Returns nil if the credential has no proof public key registered (legacy mode)
// or if all verification steps pass.
func (s *Server) verifyCredentialProof(r *http.Request, rawToken string, credential pairing.Credential) error {
	if strings.TrimSpace(credential.ProofPublicKey) == "" {
		return nil
	}
	if r == nil {
		return errors.New("helper credential proof required")
	}
	timestampText := strings.TrimSpace(r.Header.Get(proofHeaderTimestamp))
	nonce := strings.TrimSpace(r.Header.Get(proofHeaderNonce))
	signatureText := strings.TrimSpace(r.Header.Get(proofHeaderSignature))
	if timestampText == "" || nonce == "" || signatureText == "" {
		return errors.New("helper credential proof required")
	}
	timestampUnix, err := strconv.ParseInt(timestampText, 10, 64)
	if err != nil {
		return errors.New("helper credential proof invalid")
	}
	now := s.now().UTC()
	timestamp := time.Unix(timestampUnix, 0).UTC()
	if timestamp.Before(now.Add(-proofSkewWindow)) || timestamp.After(now.Add(proofSkewWindow)) {
		return errors.New("helper credential proof expired")
	}
	if s.proofNonces != nil && !s.proofNonces.use(credential.DeviceID+":"+nonce, now) {
		return errors.New("helper credential proof replayed")
	}
	bodyHash, err := requestBodyHash(r, jsonBodyLimit)
	if err != nil {
		return errors.New("helper credential proof invalid")
	}
	message := buildProofMessage(r.Method, requestPathWithRawQuery(r), rawToken, timestampText, nonce, bodyHash)
	publicKey, err := parseProofPublicKey(credential.ProofPublicKey)
	if err != nil {
		return errors.New("helper credential proof invalid")
	}
	signature, err := decodeProofBase64(signatureText)
	if err != nil {
		return errors.New("helper credential proof invalid")
	}
	digest := sha256.Sum256([]byte(message))
	if !ecdsa.VerifyASN1(publicKey, digest[:], signature) {
		return errors.New("helper credential proof invalid")
	}
	return nil
}

// buildProofMessage constructs the canonical message that is signed by the client.
// The domain separator prefix ensures signatures cannot be reused outside this protocol.
func buildProofMessage(method, pathWithQuery, token, timestamp, nonce, bodyHash string) string {
	return strings.Join([]string{
		proofDomain,
		strings.ToUpper(strings.TrimSpace(method)),
		pathWithQuery,
		token,
		timestamp,
		nonce,
		bodyHash,
	}, "\n")
}

// requestPathWithRawQuery returns the full request path including query string
// for inclusion in the proof message. Unlike requestPath(), this does NOT
// sanitize sensitive query parameters.
func requestPathWithRawQuery(r *http.Request) string {
	if r == nil || r.URL == nil {
		return "/"
	}
	if r.URL.RawQuery == "" {
		return r.URL.Path
	}
	return r.URL.Path + "?" + r.URL.RawQuery
}

// requestBodyHash reads and hashes the request body for inclusion in the proof.
// It restores the body after reading so downstream handlers can still access it.
// The maxBytes limit prevents memory exhaustion from oversized bodies.
func requestBodyHash(r *http.Request, maxBytes int64) (string, error) {
	if r == nil || r.Body == nil {
		return hashProofBody(nil), nil
	}
	reader := io.Reader(r.Body)
	if maxBytes > 0 {
		reader = io.LimitReader(r.Body, maxBytes+1)
	}
	body, err := io.ReadAll(reader)
	if err != nil {
		return "", err
	}
	if maxBytes > 0 && int64(len(body)) > maxBytes {
		return "", errors.New("proof body too large")
	}
	r.Body.Close()
	r.Body = io.NopCloser(bytes.NewReader(body))
	return hashProofBody(body), nil
}

// hashProofBody computes a URL-safe base64-encoded SHA-256 hash of the body.
func hashProofBody(body []byte) string {
	digest := sha256.Sum256(body)
	return base64.RawURLEncoding.EncodeToString(digest[:])
}

// parseProofPublicKey decodes and parses an ECDSA public key from base64-encoded
// PKIX/DER format. The key is used to verify credential proof signatures.
func parseProofPublicKey(encoded string) (*ecdsa.PublicKey, error) {
	decoded, err := decodeProofBase64(encoded)
	if err != nil {
		return nil, err
	}
	parsed, err := x509.ParsePKIXPublicKey(decoded)
	if err != nil {
		return nil, err
	}
	publicKey, ok := parsed.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("unexpected proof public key type")
	}
	return publicKey, nil
}

// decodeProofBase64 attempts to decode a base64 value, trying both raw URL
// encoding (no padding) and standard encoding (with padding) for compatibility.
func decodeProofBase64(value string) ([]byte, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(value))
	if err == nil {
		return decoded, nil
	}
	return base64.StdEncoding.DecodeString(strings.TrimSpace(value))
}

// proofNonceTracker prevents replay attacks by tracking used nonces per device
// and rejecting any nonce that has been seen within the replay window.
type proofNonceTracker struct {
	mu     sync.Mutex
	nonces map[string]time.Time
}

// newProofNonceTracker creates an empty nonce tracker.
func newProofNonceTracker() *proofNonceTracker {
	return &proofNonceTracker{nonces: make(map[string]time.Time)}
}

// use checks whether a nonce has been used before. If not, it records the
// nonce with an expiry time of now + proofReplayWindow. Expired nonces are
// lazily cleaned up on each call. Returns false if the nonce is a replay.
func (t *proofNonceTracker) use(key string, now time.Time) bool {
	if key == "" {
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	for existingKey, expiresAt := range t.nonces {
		if !expiresAt.After(now) {
			delete(t.nonces, existingKey)
		}
	}
	if expiresAt, ok := t.nonces[key]; ok && expiresAt.After(now) {
		return false
	}
	t.nonces[key] = now.Add(proofReplayWindow)
	return true
}
