package api

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/config"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/discovery"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/gateway"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/logging"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/pairing"
	acpregistry "github.com/tamimarafat/ferngeist/desktop-helper/internal/registry"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/runtime"
)

type Server struct {
	httpServer *http.Server
	logger     *slog.Logger
	cfg        config.Config
	build      BuildInfo
	startedAt  time.Time
	now        func() time.Time
	catalog    *catalog.Service
	runtime    *runtime.Supervisor
	pairing    *pairing.Service
	gateway    *gateway.Service
	discovery  *discovery.Service
	logs       *logging.Service
	registry   registryStatusProvider
}

const protocolVersion = "v1alpha1"

// BuildInfo is injected from the build so status and diagnostics can describe
// the exact helper binary that produced a failure report.
type BuildInfo struct {
	Version   string `json:"version"`
	Commit    string `json:"commit,omitempty"`
	BuiltAt   string `json:"builtAt,omitempty"`
	GoVersion string `json:"goVersion,omitempty"`
}

type errorResponse struct {
	Error string `json:"error"`
}

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

type remoteStatus struct {
	Configured bool   `json:"configured"`
	Mode       string `json:"mode,omitempty"`
	Scope      string `json:"scope,omitempty"`
	Healthy    bool   `json:"healthy"`
	Warning    string `json:"warning,omitempty"`
	PublicURL  string `json:"publicUrl,omitempty"`
}

type agentsResponse struct {
	Agents []agentRuntimeState `json:"agents"`
}

type agentRuntimeState struct {
	catalog.Agent
	Running       bool   `json:"running"`
	RuntimeID     string `json:"runtimeId,omitempty"`
	RuntimeStatus string `json:"runtimeStatus,omitempty"`
}

type pairStartResponse struct {
	ChallengeID string    `json:"challengeId"`
	Code        string    `json:"code"`
	ExpiresAt   time.Time `json:"expiresAt"`
}

type pairCompleteRequest struct {
	ChallengeID string `json:"challengeId"`
	Code        string `json:"code"`
	DeviceName  string `json:"deviceName"`
}

type pairCompleteResponse struct {
	DeviceID   string    `json:"deviceId"`
	DeviceName string    `json:"deviceName"`
	Token      string    `json:"token"`
	ExpiresAt  time.Time `json:"expiresAt"`
}

type runtimesResponse struct {
	Runtimes []runtime.Runtime `json:"runtimes"`
}

type runtimeStartResponse struct {
	Runtime runtime.Runtime `json:"runtime"`
}

type runtimeStopResponse struct {
	Runtime runtime.Runtime `json:"runtime"`
}

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

type runtimeRestartRequest struct {
	Env map[string]string `json:"env"`
}

type runtimeLogsResponse struct {
	RuntimeID string             `json:"runtimeId"`
	Logs      []runtime.LogEntry `json:"logs"`
}

type runtimeCounts struct {
	Starting    int `json:"starting"`
	Total       int `json:"total"`
	Running     int `json:"running"`
	Stopping    int `json:"stopping"`
	Stopped     int `json:"stopped"`
	Failed      int `json:"failed"`
	CircuitOpen int `json:"circuitOpen"`
}

type diagnosticsSummaryResponse struct {
	Runtime runtime.Summary `json:"runtime"`
}

type diagnosticsExportResponse struct {
	GeneratedAt time.Time                     `json:"generatedAt"`
	Helper      diagnosticsHelperSnapshot     `json:"helper"`
	Runtime     runtime.Summary               `json:"runtime"`
	Runtimes    []runtime.Runtime             `json:"runtimes"`
	RuntimeLogs map[string][]runtime.LogEntry `json:"runtimeLogs"`
	HelperLogs  []string                      `json:"helperLogs"`
}

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

var wsUpgrader = websocket.Upgrader{
	CheckOrigin: func(*http.Request) bool { return true },
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
	server := &Server{
		logger:    logger.With("component", "api"),
		cfg:       cfg,
		build:     normalizeBuildInfo(build),
		startedAt: time.Now().UTC(),
		now:       func() time.Time { return time.Now().UTC() },
		catalog:   catalogSvc,
		runtime:   runtimeSvc,
		pairing:   pairingSvc,
		gateway:   gatewaySvc,
		discovery: discoverySvc,
		logs:      logSvc,
		registry:  registrySvc,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", server.handleHealth)
	mux.HandleFunc("/v1/status", server.handleStatus)
	mux.HandleFunc("/v1/agents", server.handleAgents)
	mux.HandleFunc("GET /v1/diagnostics/summary", server.handleDiagnosticsSummary)
	mux.HandleFunc("GET /v1/diagnostics/export", server.handleDiagnosticsExport)
	mux.HandleFunc("/v1/pair/start", server.handlePairStart)
	mux.HandleFunc("/v1/pair/complete", server.handlePairComplete)
	mux.HandleFunc("/v1/runtimes", server.handleRuntimes)
	mux.HandleFunc("GET /v1/runtimes/{runtimeId}/logs", server.handleRuntimeLogs)
	mux.HandleFunc("POST /v1/agents/{agentId}/start", server.handleAgentStart)
	mux.HandleFunc("POST /v1/agents/{agentId}/stop", server.handleAgentStop)
	mux.HandleFunc("POST /v1/runtimes/{runtimeId}/connect", server.handleRuntimeConnect)
	mux.HandleFunc("POST /v1/runtimes/{runtimeId}/restart", server.handleRuntimeRestart)
	mux.HandleFunc("GET /v1/acp/{runtimeId}", server.handleACPWebSocket)

	server.httpServer = &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           server.withRequestLogging(mux),
		ReadHeaderTimeout: 5 * time.Second,
	}

	return server
}

func (s *Server) ListenAndServe() error {
	s.logger.Info("api server listening", slog.String("addr", s.cfg.ListenAddr))
	return s.httpServer.ListenAndServe()
}

func (s *Server) Shutdown(ctx context.Context) error {
	return s.httpServer.Shutdown(ctx)
}

func (s *Server) Handler() http.Handler {
	return s.httpServer.Handler
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

	summary := s.runtime.Summary()
	now := s.now()
	writeJSON(w, http.StatusOK, statusResponse{
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
		Remote:            s.remoteStatus(false),
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
	})
}

// handleAgents merges static catalog data with live runtime state so clients do
// not need to reconstruct helper policy from multiple endpoints.
func (s *Server) handleAgents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if _, ok := s.requireHelperCredential(w, r); !ok {
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
	if _, ok := s.requireHelperCredential(w, r); !ok {
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
	if _, ok := s.requireHelperCredential(w, r); !ok {
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

func (s *Server) handleRuntimes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if _, ok := s.requireHelperCredential(w, r); !ok {
		return
	}

	writeJSON(w, http.StatusOK, runtimesResponse{Runtimes: s.runtime.List()})
}

func (s *Server) handleRuntimeLogs(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperCredential(w, r); !ok {
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

	challenge, err := s.pairing.StartPairing()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to start pairing")
		return
	}

	writeJSON(w, http.StatusOK, pairStartResponse{
		ChallengeID: challenge.ID,
		Code:        challenge.Code,
		ExpiresAt:   challenge.ExpiresAt,
	})
}

func (s *Server) handlePairComplete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	var request pairCompleteRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	credential, err := s.pairing.CompletePairing(request.ChallengeID, request.Code, request.DeviceName)
	if err != nil {
		switch {
		case errors.Is(err, pairing.ErrInvalidDeviceName):
			writeError(w, http.StatusBadRequest, err.Error())
		case errors.Is(err, pairing.ErrChallengeNotFound), errors.Is(err, pairing.ErrChallengeExpired), errors.Is(err, pairing.ErrCodeMismatch):
			writeError(w, http.StatusUnauthorized, err.Error())
		default:
			writeError(w, http.StatusInternalServerError, "failed to complete pairing")
		}
		return
	}

	writeJSON(w, http.StatusOK, pairCompleteResponse{
		DeviceID:   credential.DeviceID,
		DeviceName: credential.DeviceName,
		Token:      credential.Token,
		ExpiresAt:  credential.ExpiresAt,
	})
}

func (s *Server) handleAgentStart(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperCredential(w, r); !ok {
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
	if _, ok := s.requireHelperCredential(w, r); !ok {
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
	if _, ok := s.requireHelperCredential(w, r); !ok {
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

func (s *Server) handleRuntimeRestart(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireHelperCredential(w, r); !ok {
		return
	}

	runtimeID := r.PathValue("runtimeId")
	var request runtimeRestartRequest
	if r.Body != nil {
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil && !errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "invalid request body")
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

// handleACPWebSocket bridges the helper-facing WebSocket contract onto the
// helper-managed stdio ACP process. Both directions are also mirrored into the
// runtime log buffer so diagnostics can retain recent ACP traffic.
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
	defer clientConn.Close()
	defer release()

	proxyDone := make(chan error, 2)
	go proxyWebSocketToStdio(clientConn, stdin, runtimeID, s.runtime.AppendLog, proxyDone)
	go proxyStdioToWebSocket(stdout, clientConn, runtimeID, s.runtime.AppendLog, proxyDone)
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

	credential, err := s.pairing.ValidateCredential(bearerToken(r))
	if err != nil {
		writeError(w, http.StatusUnauthorized, err.Error())
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

func websocketHost(r *http.Request) string {
	if forwarded := strings.TrimSpace(r.Header.Get("X-Forwarded-Host")); forwarded != "" {
		return forwarded
	}
	return r.Host
}

// websocketHostWithPath bakes the runtime token into the query string because
// the Android ACP path currently expects a directly connectable URL.
func websocketHostWithPath(r *http.Request, path, token string) string {
	values := url.Values{}
	values.Set("access_token", token)
	return fmt.Sprintf("%s%s?%s", websocketHost(r), path, values.Encode())
}

func absoluteWebSocketURL(r *http.Request, path, token string) string {
	return fmt.Sprintf("%s://%s", websocketScheme(r), websocketHostWithPath(r, path, token))
}

func connectResponseFromDescriptor(r *http.Request, descriptor runtime.ConnectDescriptor) runtimeConnectResponse {
	return runtimeConnectResponse{
		RuntimeID:      descriptor.RuntimeID,
		Protocol:       descriptor.Protocol,
		Scheme:         websocketScheme(r),
		Host:           websocketHostWithPath(r, descriptor.WebSocketPath, descriptor.BearerToken),
		WebSocketURL:   absoluteWebSocketURL(r, descriptor.WebSocketPath, descriptor.BearerToken),
		WebSocketPath:  descriptor.WebSocketPath,
		BearerToken:    descriptor.BearerToken,
		TokenExpiresAt: descriptor.TokenExpiresAt,
	}
}

func (s *Server) helperDisplayName() string {
	if name := strings.TrimSpace(s.cfg.HelperName); name != "" {
		return name
	}
	return "ferngeist-helper"
}

func (s *Server) remoteStatus(includePublicURL bool) remoteStatus {
	publicBaseURL := strings.TrimSpace(s.cfg.PublicBaseURL)
	status := remoteStatus{
		Configured: publicBaseURL != "",
		Healthy:    true,
	}

	switch {
	case publicBaseURL != "":
		status.Mode, status.Scope, status.Warning, status.Healthy = classifyRemoteURL(publicBaseURL)
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

func isPrivateHostname(host string) bool {
	if host == "localhost" || host == "127.0.0.1" || host == "::1" {
		return true
	}
	if strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".internal") || strings.HasSuffix(host, ".lan") {
		return true
	}
	return false
}

// proxyWebSocketToStdio adapts ACP-over-WebSocket client messages into the
// newline-delimited stdio framing used by CLI ACP servers. It also mirrors the
// raw client payload into the runtime log buffer as `acp.stdin` traffic.
func proxyWebSocketToStdio(src *websocket.Conn, dst io.WriteCloser, runtimeID string, appendLog func(string, string, string), done chan<- error) {
	for {
		messageType, payload, err := src.ReadMessage()
		if err != nil {
			done <- err
			return
		}
		if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
			continue
		}
		if appendLog != nil {
			appendLog(runtimeID, "acp.stdin", string(payload))
		}
		if _, err := dst.Write(append(payload, '\n')); err != nil {
			done <- err
			return
		}
	}
}

// proxyStdioToWebSocket performs the reverse adaptation by streaming each stdio
// line as one WebSocket text frame. Each line is also mirrored into the runtime
// log buffer as `acp.stdout` traffic before being forwarded to the client.
func proxyStdioToWebSocket(src io.Reader, dst *websocket.Conn, runtimeID string, appendLog func(string, string, string), done chan<- error) {
	scanner := bufio.NewScanner(src)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, 1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		if appendLog != nil {
			appendLog(runtimeID, "acp.stdout", line)
		}
		if err := dst.WriteMessage(websocket.TextMessage, []byte(line)); err != nil {
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

	clientConn, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		release()
		s.logger.Error("websocket upgrade failed", slog.String("error", err.Error()))
		return nil, nil, nil, nil, err
	}
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

func uptimeSeconds(startedAt, now time.Time) int64 {
	if startedAt.IsZero() || now.Before(startedAt) {
		return 0
	}
	return int64(now.Sub(startedAt).Seconds())
}
