// Package runtime provides lifecycle management for agent processes, including
// launching, monitoring, restarting, and graceful shutdown. It maintains runtime
// state, captures logs, and handles failure recovery with circuit breaker patterns.
package runtime

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/acquire"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

// connectTokenTTL defines how long a generated bearer token remains valid
// for establishing ACP connections to a runtime.
const connectTokenTTL = 5 * time.Minute

// stoppedRuntimeRetention keeps recently stopped runtimes visible long enough
// for clients and diagnostics to confirm that a stop completed successfully.
const stoppedRuntimeRetention = 10 * time.Minute

var (
	// ErrRuntimeNotFound is returned when a requested runtime ID does not exist
	// in the supervisor's registry or has been pruned.
	ErrRuntimeNotFound = errors.New("runtime not found")

	// ErrAgentNotDetected is returned when the agent binary cannot be located
	// on the current host and automatic installation is not available.
	ErrAgentNotDetected = errors.New("agent is not detected on this host")

	// ErrRemoteStartNotAllowed is returned when the agent's security policy
	// explicitly forbids the helper from launching it remotely.
	ErrRemoteStartNotAllowed = errors.New("agent does not allow helper-managed remote start")

	// ErrUnsupportedLaunch is returned when the launch mode (e.g., container, VM)
	// is not yet implemented by the supervisor.
	ErrUnsupportedLaunch = errors.New("agent launch mode is not supported yet")

	// ErrExecutableNotFound is returned when the agent binary specified in the
	// launch configuration cannot be found on disk or in PATH.
	ErrExecutableNotFound = errors.New("agent executable not found")

	// ErrRuntimeNotRunning is returned when attempting to operate on a runtime
	// that is not in the "running" state.
	ErrRuntimeNotRunning = errors.New("runtime is not running")

	// ErrRuntimeNotConnectable is returned when the runtime's transport mechanism
	// (e.g., stdio, websocket) is not available for connections.
	ErrRuntimeNotConnectable = errors.New("runtime is not connectable")

	// ErrRuntimeAlreadyAttached is returned when attempting to attach stdio to
	// a runtime that already has an active ACP session connection.
	ErrRuntimeAlreadyAttached = errors.New("runtime is already attached to an ACP session")
)

// Status constants represent the possible lifecycle states of a runtime.
const (
	// StatusStarting indicates the runtime process has been launched but has not
	// yet passed readiness and health checks.
	StatusStarting = "starting"

	// StatusRunning indicates the runtime is active and ready to accept connections.
	StatusRunning = "running"

	// StatusStopping indicates a graceful shutdown is in progress.
	StatusStopping = "stopping"

	// StatusStopped indicates the runtime has terminated successfully or was
	// intentionally stopped. This state is retained for pruning.
	StatusStopped = "stopped"

	// StatusFailed indicates the runtime terminated unexpectedly or failed to
	// start. May trigger restart logic depending on circuit breaker state.
	StatusFailed = "failed"
)

// Runtime is the helper-owned view of a launched agent process plus the
// transport metadata needed to hand an ACP session back to Ferngeist.
type Runtime struct {
	ID              string    `json:"id"`
	AgentID         string    `json:"agentId"`
	AgentName       string    `json:"agentName"`
	Status          string    `json:"status"`
	CreatedAt       time.Time `json:"createdAt"`
	PID             int       `json:"pid"`
	Command         string    `json:"command"`
	LastError       string    `json:"lastError,omitempty"`
	Detected        bool      `json:"detected"`
	LaunchMode      string    `json:"launchMode"`
	Transport       string    `json:"transport"`
	RestartAttempts int       `json:"restartAttempts"`
	FailureStreak   int       `json:"failureStreak"`
	CircuitOpen     bool      `json:"circuitOpen"`
	LastFailureAt   time.Time `json:"lastFailureAt,omitempty"`
	// StoppedAt marks when a runtime entered the terminal stopped state so the
	// supervisor can prune it after a short retention window.
	StoppedAt time.Time `json:"stoppedAt,omitempty"`
}

// LogEntry represents a single line of output from a runtime process,
// tagged with a timestamp and stream type (stdout, stderr, or helper).
type LogEntry struct {
	// Timestamp is when this log entry was captured in UTC.
	Timestamp time.Time `json:"timestamp"`

	// Stream identifies the source of the log line (e.g., "stdout", "stderr", "helper").
	Stream string `json:"stream"`

	// Message is the actual log line content.
	Message string `json:"message"`
}

// Summary provides an aggregate view of all runtimes managed by the supervisor,
// including counts by status and recent failure details for diagnostics.
type Summary struct {
	// Total is the total number of runtimes currently tracked.
	Total int `json:"total"`

	// Starting is the count of runtimes that are launching but not yet ready.
	Starting int `json:"starting"`

	// Running is the count of runtimes actively serving ACP sessions.
	Running int `json:"running"`

	// Stopping is the count of runtimes in graceful shutdown.
	Stopping int `json:"stopping"`

	// Stopped is the count of intentionally terminated runtimes still in retention.
	Stopped int `json:"stopped"`

	// Failed is the count of runtimes that crashed or failed to start.
	Failed int `json:"failed"`

	// CircuitOpen is the count of runtimes with restart circuit breaker tripped.
	CircuitOpen int `json:"circuitOpen"`

	// RecentFailures contains the most recent failure summaries, sorted by time.
	RecentFailures []FailureSummary `json:"recentFailures"`
}

// FailureSummary captures diagnostic information about a runtime failure,
// including the error context and recent log lines for troubleshooting.
type FailureSummary struct {
	// RuntimeID is the unique identifier of the failed runtime.
	RuntimeID string `json:"runtimeId"`

	// AgentID identifies which agent failed.
	AgentID string `json:"agentId"`

	// AgentName is the human-readable agent display name.
	AgentName string `json:"agentName"`

	// LastError contains the error message from the process exit or startup failure.
	LastError string `json:"lastError"`

	// CreatedAt is when the runtime was originally launched.
	CreatedAt time.Time `json:"createdAt"`

	// FailedAt is when the failure occurred, if available.
	FailedAt time.Time `json:"failedAt,omitempty"`

	// RecentLogLines contains the last few log entries before the failure for debugging.
	RecentLogLines []LogEntry `json:"recentLogLines"`
}

// ConnectDescriptor contains the connection parameters needed to establish
// an ACP session with a running runtime, including authentication details.
type ConnectDescriptor struct {
	// RuntimeID is the runtime this descriptor connects to.
	RuntimeID string `json:"runtimeId"`

	// Protocol is the connection protocol (currently "acp").
	Protocol string `json:"protocol"`

	// WebSocketPath is the URL path for WebSocket connections.
	WebSocketPath string `json:"websocketPath"`

	// BearerToken is the one-time authentication token for this connection.
	BearerToken string `json:"bearerToken"`

	// TokenExpiresAt is when the bearer token becomes invalid.
	TokenExpiresAt time.Time `json:"tokenExpiresAt"`
}

// Supervisor owns the full runtime lifecycle for helper-managed agents. It is
// intentionally the single place that knows how manifests turn into processes,
// readiness checks, restart behavior, and diagnostic state.
//
// The supervisor manages process launching, monitoring, automatic restart with
// circuit breaker patterns, log capture, and graceful shutdown. All runtime
// state is protected by a mutex for concurrent safety.
type Supervisor struct {
	// logger is the structured logger with component context pre-applied.
	logger *slog.Logger

	// mu protects all mutable state in the supervisor.
	mu sync.Mutex

	// now is a time provider function for testability.
	now func() time.Time

	// runtimes maps runtime ID to its current state and metadata.
	runtimes map[string]Runtime

	// runtimeByAgent maps agent ID to runtime ID for quick lookups.
	runtimeByAgent map[string]string

	// processes holds active process handles for running runtimes.
	processes map[string]*processHandle

	// logs stores bounded in-memory log buffers per runtime.
	logs map[string][]LogEntry

	// baseDir is the root directory for resolving relative agent paths.
	baseDir string

	// store is the optional SQLite persistence layer for runtime state.
	store *storage.SQLiteStore

	// installer handles automatic agent acquisition if not present.
	installer *acquire.Installer
}

// processHandle holds the OS process and lifecycle state for a running runtime.
// It tracks stdin/stdout pipes for stdio transport, attachment status for
// exclusive ACP sessions, and restart configuration.
type processHandle struct {
	// cmd is the underlying OS process being managed.
	cmd *exec.Cmd

	// done is closed when the process exits, signaling waiters.
	done chan struct{}

	// waitErr captures the error from cmd.Wait() after process exit.
	waitErr error

	// stdin is the process's standard input pipe for ACP communication.
	stdin io.WriteCloser

	// stdout is the process's standard output pipe for ACP communication.
	stdout io.ReadCloser

	// attached indicates whether an ACP session is actively using this runtime.
	attached bool

	// stopping indicates an intentional shutdown is in progress.
	stopping bool

	// agent is the catalog agent configuration for this process.
	agent catalog.Agent

	// envOverrides contains environment variables applied at launch.
	envOverrides map[string]string

	// restartAttempt tracks how many times this runtime has been restarted.
	restartAttempt int
}

// shutdownTarget pairs a runtime with its process handle for coordinated shutdown.
// It's used during supervisor shutdown to track which runtimes need to be stopped.
type shutdownTarget struct {
	runtime Runtime
	process *processHandle
}

// NewSupervisor creates a supervisor with default base directory and no persistence.
func NewSupervisor(logger *slog.Logger) *Supervisor {
	return NewSupervisorWithBaseDir(logger, ".", nil)
}

// NewSupervisorWithBaseDir creates a supervisor with custom base directory and
// optional SQLite store for persisting runtime state across restarts.
func NewSupervisorWithBaseDir(logger *slog.Logger, baseDir string, store *storage.SQLiteStore) *Supervisor {
	return NewSupervisorWithBaseDirAndInstaller(logger, baseDir, store, nil)
}

// NewSupervisorWithBaseDirAndInstaller creates a fully configured supervisor with
// custom base directory, optional persistence store, and optional installer for
// automatic agent acquisition.
func NewSupervisorWithBaseDirAndInstaller(logger *slog.Logger, baseDir string, store *storage.SQLiteStore, installer *acquire.Installer) *Supervisor {
	return &Supervisor{
		logger:         logger.With("component", "runtime"),
		now:            time.Now,
		runtimes:       make(map[string]Runtime),
		runtimeByAgent: make(map[string]string),
		processes:      make(map[string]*processHandle),
		logs:           make(map[string][]LogEntry),
		baseDir:        baseDir,
		store:          store,
		installer:      installer,
	}
}

// Start launches a new agent runtime process after validating prerequisites.
// It checks that the agent is detected, allows remote start, and has a supported
// launch mode and transport. If an installer is available and the agent is not
// detected, it attempts automatic acquisition first.
//
// If a runtime for this agent already exists and is attached, it will be stopped
// and replaced. If an unattached runtime exists, it is returned as-is.
//
// Returns the Runtime descriptor on success, or an error if validation or
// launch fails.
func (s *Supervisor) Start(agent catalog.Agent) (Runtime, error) {
	var staleRuntimeID string

	// Check for existing runtime under lock to prevent race conditions
	s.mu.Lock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	// Look up if there's already a runtime for this agent
	if runtimeID, ok := s.runtimeByAgent[agent.ID]; ok {
		if existing, exists := s.runtimes[runtimeID]; exists {
			// Check if the existing runtime has an attached ACP session
			// If attached, it's "stale" and needs to be replaced
			if handle, attached := s.processes[runtimeID]; attached && handle != nil && handle.attached {
				staleRuntimeID = runtimeID
			} else {
				// Runtime exists but not attached - return it without launching a new one
				s.mu.Unlock()
				return existing, nil
			}
		}
	}
	s.mu.Unlock()

	// Stop the stale runtime outside the lock to avoid holding it during I/O
	if staleRuntimeID != "" {
		if _, err := s.StopByRuntimeID(staleRuntimeID); err != nil && !errors.Is(err, ErrRuntimeNotFound) {
			return Runtime{}, err
		}
	}

	// Ensure agent is available - try auto-install if installer is configured
	if !agent.Detected {
		if s.installer != nil {
			acquiredAgent, _, err := s.installer.Ensure(context.Background(), agent)
			if err != nil {
				return Runtime{}, err
			}
			agent = acquiredAgent
		}
	}

	// Validate all prerequisites before attempting launch
	if !agent.Detected {
		return Runtime{}, ErrAgentNotDetected
	}
	if !agent.Security.AllowsRemoteStart {
		return Runtime{}, ErrRemoteStartNotAllowed
	}
	if agent.Launch.Mode != "process" && agent.Launch.Mode != "external" {
		return Runtime{}, ErrUnsupportedLaunch
	}
	if agent.Launch.Transport != "stdio" {
		return Runtime{}, ErrRuntimeNotConnectable
	}

	return s.launchRuntime(agent, nil, nil)
}

// launchRuntime is the core transition from a validated catalog agent to a
// tracked runtime. It resolves the launch target, starts the child process,
// records it immediately for diagnostics, then performs readiness and health
// checks before exposing it as running.
func (s *Supervisor) launchRuntime(agent catalog.Agent, previous *Runtime, envOverrides map[string]string) (Runtime, error) {
	// Resolve the executable path and working directory from the launch config
	commandPath, workingDir, err := s.resolveLaunch(agent.Launch)
	if err != nil {
		return Runtime{}, err
	}

	// Create a new runtime descriptor with fresh identity
	runtimeInfo := Runtime{
		ID:         randomToken(18),
		AgentID:    agent.ID,
		AgentName:  agent.DisplayName,
		Status:     StatusStarting,
		CreatedAt:  s.now().UTC(),
		Command:    commandPath,
		Detected:   agent.Detected,
		LaunchMode: agent.Launch.Mode,
		Transport:  agent.Launch.Transport,
		StoppedAt:  time.Time{},
	}

	// If this is a restart, preserve the original runtime identity for diagnostic continuity
	if previous != nil {
		runtimeInfo = *previous
		runtimeInfo.Status = StatusStarting
		runtimeInfo.Command = commandPath
		runtimeInfo.Detected = agent.Detected
		runtimeInfo.LaunchMode = agent.Launch.Mode
		runtimeInfo.Transport = agent.Launch.Transport
		runtimeInfo.LastError = ""
		runtimeInfo.PID = 0
		runtimeInfo.CircuitOpen = false
		runtimeInfo.StoppedAt = time.Time{}
	}

	// Set up the OS process with piped stdio for ACP communication
	cmd := exec.Command(commandPath, agent.Launch.Args...)
	cmd.Dir = workingDir
	cmd.Env = mergeProcessEnv(envOverrides)
	stdinPipe, err := cmd.StdinPipe()
	if err != nil {
		return Runtime{}, err
	}
	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return Runtime{}, err
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		return Runtime{}, err
	}
	if err := cmd.Start(); err != nil {
		return Runtime{}, err
	}

	runtimeInfo.PID = cmd.Process.Pid

	// Create the process handle and start waiting for exit in a goroutine
	handle := &processHandle{
		cmd:            cmd,
		done:           make(chan struct{}),
		stdin:          stdinPipe,
		stdout:         stdoutPipe,
		agent:          agent,
		envOverrides:   cloneStringMap(envOverrides),
		restartAttempt: runtimeInfo.RestartAttempts,
	}
	go func() {
		// Block until process exits, then capture error and signal waiters
		handle.waitErr = cmd.Wait()
		close(handle.done)
	}()

	// Register the runtime in the supervisor's maps while holding the lock
	s.mu.Lock()
	s.runtimes[runtimeInfo.ID] = runtimeInfo
	s.runtimeByAgent[agent.ID] = runtimeInfo.ID
	s.processes[runtimeInfo.ID] = handle
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)

	// Start background goroutines for log capture and process monitoring
	go s.captureLogs(runtimeInfo.ID, "stderr", stderrPipe, os.Stderr)
	go s.watchProcess(runtimeInfo.ID, agent.ID, handle)

	// Perform readiness check - kill process immediately if it fails
	if err := waitForLaunchReadiness(agent.Launch); err != nil {
		handle.stopping = true
		_ = cmd.Process.Kill()
		<-handle.done // Wait for process to fully exit
		checkErr := fmt.Errorf("runtime readiness check failed: %w", err)
		s.cleanupFailedLaunch(runtimeInfo.ID, agent.ID, checkErr)
		return Runtime{}, checkErr
	}

	// Perform health check - kill process immediately if it fails
	if err := runHealthCheck(agent.Launch, agent.HealthCheck); err != nil {
		handle.stopping = true
		_ = cmd.Process.Kill()
		<-handle.done // Wait for process to fully exit
		checkErr := fmt.Errorf("runtime health check failed: %w", err)
		s.cleanupFailedLaunch(runtimeInfo.ID, agent.ID, checkErr)
		return Runtime{}, checkErr
	}

	// Transition to running state only after all checks pass
	s.mu.Lock()
	runtimeInfo = s.runtimes[runtimeInfo.ID]
	runtimeInfo.Status = StatusRunning
	runtimeInfo.CircuitOpen = false
	s.runtimes[runtimeInfo.ID] = runtimeInfo
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)
	return runtimeInfo, nil
}

// Restart stops and relaunches a running runtime with updated environment variables.
// It performs a graceful stop of the existing process, persists the stopped state,
// then launches a new instance with the merged environment overrides.
//
// The runtimeID must refer to a currently running runtime, otherwise
// ErrRuntimeNotFound or ErrRuntimeNotRunning is returned.
//
// Returns the new Runtime descriptor on success.
func (s *Supervisor) Restart(runtimeID string, envVars map[string]string) (Runtime, error) {
	s.mu.Lock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	// Validate runtime exists and is running
	runtimeInfo, ok := s.runtimes[runtimeID]
	if !ok {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotFound
	}
	if runtimeInfo.Status != StatusRunning {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotRunning
	}
	handle, ok := s.processes[runtimeID]
	if !ok || handle == nil {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotRunning
	}

	// Capture agent config and merge environment overrides before stopping
	agent := handle.agent
	mergedEnv := mergeEnvOverrides(handle.envOverrides, envVars)

	// Mark as stopping and remove from active maps immediately
	// This prevents concurrent reconnects to the old runtime
	handle.stopping = true
	runtimeInfo.Status = StatusStopping
	runtimeInfo.StoppedAt = time.Time{}
	s.runtimes[runtimeID] = runtimeInfo
	s.deleteRuntimeByAgentIfMatchesLocked(runtimeInfo.AgentID, runtimeID)
	delete(s.processes, runtimeID)
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)

	// Stop the old process - force kill if graceful shutdown fails
	if err := s.stopProcess(handle, 2*time.Second); err != nil {
		s.logger.Warn("runtime restart required forced termination", "runtime_id", runtimeID, "error", err)
	}

	// Update runtime to stopped state after process termination
	s.mu.Lock()
	if existing, exists := s.runtimes[runtimeID]; exists {
		existing.Status = StatusStopped
		existing.PID = 0
		existing.StoppedAt = s.now().UTC()
		s.runtimes[runtimeID] = existing
		runtimeInfo = existing
	}
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)

	// Launch the new runtime with merged environment
	restarted, err := s.launchRuntime(agent, nil, mergedEnv)
	if err != nil {
		return Runtime{}, err
	}

	// Log the successful restart for diagnostics
	s.appendLog(restarted.ID, LogEntry{
		Timestamp: s.now().UTC(),
		Stream:    "helper",
		Message:   fmt.Sprintf("runtime restarted from %s with updated environment", runtimeID),
	})
	return restarted, nil
}

// StopByAgentID is the public stop path used by the API. It removes the
// runtime from the active maps before waiting on process termination so a
// concurrent reconnect cannot attach to a runtime that is already stopping.
func (s *Supervisor) StopByAgentID(agentID string) (Runtime, error) {
	s.mu.Lock()

	// Look up runtime by agent ID
	runtimeID, ok := s.runtimeByAgent[agentID]
	if !ok {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotFound
	}
	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		// Clean up orphaned mapping
		s.deleteRuntimeByAgentIfMatchesLocked(agentID, runtimeID)
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotFound
	}

	// Already stopped - return as-is
	if runtime.Status == StatusStopped {
		s.mu.Unlock()
		return runtime, nil
	}

	// Mark process as stopping to prevent restart on exit
	process := s.processes[runtimeID]
	if process != nil {
		process.stopping = true
	}

	// Transition to stopping state and remove from active maps
	runtime.Status = StatusStopping
	runtime.StoppedAt = time.Time{}
	s.runtimes[runtimeID] = runtime
	s.deleteRuntimeByAgentIfMatchesLocked(agentID, runtimeID)
	delete(s.processes, runtimeID)
	s.mu.Unlock()
	s.persistRuntime(runtime)

	// Stop the process outside the lock - this may involve I/O and timeouts
	if err := s.stopProcess(process, 2*time.Second); err != nil {
		s.logger.Warn("runtime stop required forced termination", "runtime_id", runtimeID, "error", err)
	}

	// Update final stopped state after process has terminated
	s.mu.Lock()
	runtime = s.runtimes[runtimeID]
	s.mu.Unlock()
	runtime.Status = StatusStopped
	runtime.PID = 0
	runtime.StoppedAt = s.now().UTC()
	s.mu.Lock()
	s.runtimes[runtimeID] = runtime
	s.mu.Unlock()
	s.persistRuntime(runtime)
	return runtime, nil
}

func (s *Supervisor) watchProcess(runtimeID, agentID string, handle *processHandle) {
	<-handle.done
	s.handleProcessExit(runtimeID, agentID, handle)
}

// Shutdown drains all active runtimes best-effort during daemon shutdown. Each
// runtime is first marked stopping in persisted state so diagnostics still show
// an intentional shutdown rather than an unexplained disappearance.
func (s *Supervisor) Shutdown(ctx context.Context) error {
	s.mu.Lock()

	// Snapshot all runtimes under lock, then clear the maps immediately
	targets := make([]shutdownTarget, 0, len(s.runtimes))
	for runtimeID, runtime := range s.runtimes {
		targets = append(targets, shutdownTarget{
			runtime: runtime,
			process: s.processes[runtimeID],
		})
		// Remove from maps so no new connections can be established
		delete(s.processes, runtimeID)
		s.deleteRuntimeByAgentIfMatchesLocked(runtime.AgentID, runtimeID)
		delete(s.runtimes, runtimeID)
	}
	s.mu.Unlock()

	// Stop all runtimes concurrently (best-effort, no goroutines to keep it simple)
	var failures []string
	for _, target := range targets {
		// Persist stopping state first for diagnostic visibility
		target.runtime.Status = StatusStopping
		s.persistRuntime(target.runtime)

		// Attempt graceful stop with context timeout
		if err := stopProcessWithContext(ctx, target.process); err != nil {
			failures = append(failures, fmt.Sprintf("%s: %v", target.runtime.ID, err))
		}

		// Mark as stopped regardless of outcome
		target.runtime.Status = StatusStopped
		target.runtime.PID = 0
		target.runtime.StoppedAt = s.now().UTC()
		s.persistRuntime(target.runtime)
	}

	// Return aggregated errors if any occurred
	if len(failures) > 0 {
		return fmt.Errorf("runtime shutdown failed: %s", strings.Join(failures, "; "))
	}
	return nil
}

// List returns all runtimes currently managed by the supervisor, sorted by
// creation time in descending order (newest first). Stopped runtimes outside
// the retention window are pruned before returning.
func (s *Supervisor) List() []Runtime {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	out := make([]Runtime, 0, len(s.runtimes))
	for _, runtime := range s.runtimes {
		out = append(out, runtime)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].CreatedAt.After(out[j].CreatedAt)
	})
	return out
}

// Connect generates a one-time connection descriptor for a running runtime.
// It creates a short-lived bearer token that expires after connectTokenTTL.
// The descriptor contains all information needed to establish an ACP session.
//
// Returns ErrRuntimeNotFound if the runtime doesn't exist, ErrRuntimeNotRunning
// if it's not in running state, or ErrRuntimeNotConnectable if the transport
// is not stdio.
func (s *Supervisor) Connect(runtimeID string) (ConnectDescriptor, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		return ConnectDescriptor{}, ErrRuntimeNotFound
	}
	if runtime.Status != StatusRunning {
		return ConnectDescriptor{}, ErrRuntimeNotRunning
	}
	if runtime.Transport != "stdio" {
		return ConnectDescriptor{}, ErrRuntimeNotConnectable
	}

	return ConnectDescriptor{
		RuntimeID:      runtime.ID,
		Protocol:       "acp",
		WebSocketPath:  fmt.Sprintf("/v1/acp/%s", runtime.ID),
		BearerToken:    randomToken(24),
		TokenExpiresAt: s.now().UTC().Add(connectTokenTTL),
	}, nil
}

// AttachStdio hands out exclusive access to a stdio runtime because ACP over
// stdio is a single-client stream in this helper. The returned release func
// must be called when the bridge is torn down.
func (s *Supervisor) AttachStdio(runtimeID string) (io.WriteCloser, io.ReadCloser, func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		return nil, nil, nil, ErrRuntimeNotFound
	}
	if runtime.Status != StatusRunning {
		return nil, nil, nil, ErrRuntimeNotRunning
	}
	if runtime.Transport != "stdio" {
		return nil, nil, nil, ErrRuntimeNotConnectable
	}

	handle, ok := s.processes[runtimeID]
	if !ok || handle.stdin == nil || handle.stdout == nil {
		return nil, nil, nil, ErrRuntimeNotConnectable
	}
	if handle.attached {
		return nil, nil, nil, ErrRuntimeAlreadyAttached
	}
	handle.attached = true

	release := func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if existing, exists := s.processes[runtimeID]; exists {
			existing.attached = false
		}
	}
	return handle.stdin, handle.stdout, release, nil
}

// Logs retrieves the buffered log entries for a specific runtime. The buffer
// is bounded to maxEntries (200) and contains the most recent entries.
//
// Returns nil slice if the runtime exists but has no logs, or
// ErrRuntimeNotFound if the runtime is not tracked.
func (s *Supervisor) Logs(runtimeID string) ([]LogEntry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	entries, ok := s.logs[runtimeID]
	if !ok {
		if _, exists := s.runtimes[runtimeID]; exists {
			return nil, nil
		}
		return nil, ErrRuntimeNotFound
	}

	out := make([]LogEntry, len(entries))
	copy(out, entries)
	return out, nil
}

// AppendLog records helper-observed ACP traffic or lifecycle messages using the
// same bounded in-memory buffer returned by the runtime log endpoints.
func (s *Supervisor) AppendLog(runtimeID, stream, message string) {
	if strings.TrimSpace(runtimeID) == "" {
		return
	}
	s.appendLog(runtimeID, LogEntry{
		Timestamp: s.now().UTC(),
		Stream:    stream,
		Message:   message,
	})
}

// Summary aggregates the current state of all runtimes into a summary view.
// It includes counts by status and the most recent failures (up to 5) with
// diagnostic context. Failures are sourced from both in-memory state and
// persisted records if a store is configured.
func (s *Supervisor) Summary() Summary {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	summary := Summary{}
	// Use a map to deduplicate failures by runtime ID
	failuresByRuntimeID := make(map[string]FailureSummary)

	// Count runtimes by status and collect in-memory failures
	for runtimeID, runtime := range s.runtimes {
		summary.Total++
		switch runtime.Status {
		case StatusStarting:
			summary.Starting++
		case StatusRunning:
			summary.Running++
		case StatusStopping:
			summary.Stopping++
		case StatusFailed:
			summary.Failed++
			if runtime.CircuitOpen {
				summary.CircuitOpen++
			}
			// Capture failure summary from in-memory state
			failuresByRuntimeID[runtimeID] = FailureSummary{
				RuntimeID:      runtimeID,
				AgentID:        runtime.AgentID,
				AgentName:      runtime.AgentName,
				LastError:      runtime.LastError,
				CreatedAt:      runtime.CreatedAt,
				FailedAt:       runtime.CreatedAt,
				RecentLogLines: lastLogEntries(s.logs[runtimeID], 5),
			}
		case StatusStopped:
			summary.Stopped++
		default:
			// Unknown states treated as stopped
			summary.Stopped++
		}
	}

	// Merge persisted failures from SQLite store (for runtimes that were pruned)
	if s.store != nil {
		persistedFailures, err := s.store.ListRecentRuntimeFailures(context.Background(), 5)
		if err != nil {
			s.logger.Warn("load runtime failures failed", "error", err)
		} else {
			for _, record := range persistedFailures {
				// Skip if we already have a more recent in-memory failure
				if _, exists := failuresByRuntimeID[record.RuntimeID]; exists {
					continue
				}
				failuresByRuntimeID[record.RuntimeID] = failureSummaryFromRecord(record)
			}
		}
	}

	// Sort failures by time (most recent first) and take top 5
	failures := make([]FailureSummary, 0, len(failuresByRuntimeID))
	for _, failure := range failuresByRuntimeID {
		failures = append(failures, failure)
	}
	sort.Slice(failures, func(i, j int) bool {
		return failureSortTime(failures[i]).After(failureSortTime(failures[j]))
	})
	if len(failures) > 5 {
		failures = failures[:5]
	}
	summary.RecentFailures = failures
	return summary
}

// resolveCommandPath converts a command string to an absolute path.
// If the command is already absolute, it's returned as-is. Otherwise,
// it's resolved relative to the supervisor's base directory.
func (s *Supervisor) resolveCommandPath(command string) string {
	if filepath.IsAbs(command) {
		return command
	}
	return filepath.Join(s.baseDir, command)
}

// resolveLaunch determines the executable path and working directory for an
// agent based on its launch configuration. For "process" mode, it resolves
// the command relative to the base directory. For "external" mode, it searches
// the system PATH.
//
// Returns the absolute command path, working directory, and any error.
func (s *Supervisor) resolveLaunch(launch catalog.LaunchConfig) (string, string, error) {
	switch launch.Mode {
	case "process":
		commandPath := s.resolveCommandPath(launch.Command)
		if _, err := os.Stat(commandPath); err != nil {
			return "", "", fmt.Errorf("%w: %s", ErrExecutableNotFound, commandPath)
		}
		return commandPath, filepath.Dir(commandPath), nil
	case "external":
		commandPath, err := exec.LookPath(launch.Command)
		if err != nil {
			return "", "", fmt.Errorf("%w: %s", ErrExecutableNotFound, launch.Command)
		}
		workingDir, dirErr := os.Getwd()
		if dirErr != nil {
			workingDir = "."
		}
		return commandPath, workingDir, nil
	default:
		return "", "", ErrUnsupportedLaunch
	}
}

// StopByRuntimeID stops a runtime by its unique runtime ID. It transitions the
// runtime to stopping state, removes it from active maps, gracefully terminates
// the process, and persists the final stopped state.
//
// Returns the final Runtime state, or ErrRuntimeNotFound if the ID is invalid.
func (s *Supervisor) StopByRuntimeID(runtimeID string) (Runtime, error) {
	s.mu.Lock()
	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotFound
	}
	if runtime.Status == StatusStopped {
		s.mu.Unlock()
		return runtime, nil
	}
	process := s.processes[runtimeID]
	if process != nil {
		process.stopping = true
	}
	runtime.Status = StatusStopping
	s.runtimes[runtimeID] = runtime
	s.deleteRuntimeByAgentIfMatchesLocked(runtime.AgentID, runtimeID)
	delete(s.processes, runtimeID)
	s.mu.Unlock()
	s.persistRuntime(runtime)

	if err := s.stopProcess(process, 2*time.Second); err != nil {
		s.logger.Warn("runtime stop required forced termination", "runtime_id", runtimeID, "error", err)
	}

	s.mu.Lock()
	runtime = s.runtimes[runtimeID]
	s.mu.Unlock()
	runtime.Status = StatusStopped
	runtime.PID = 0
	runtime.StoppedAt = s.now().UTC()
	s.mu.Lock()
	s.runtimes[runtimeID] = runtime
	s.mu.Unlock()
	s.persistRuntime(runtime)
	return runtime, nil
}

// handleProcessExit is where crash behavior is normalized. The process watcher
// always funnels through here so restart policy, circuit-open state, and
// failure persistence are applied consistently whether the process exits during
// startup or long after a client connected.
func (s *Supervisor) handleProcessExit(runtimeID, agentID string, handle *processHandle) {
	s.mu.Lock()
	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		// Runtime was already removed (e.g., during intentional stop)
		s.mu.Unlock()
		return
	}

	// Remove process handle immediately - it's no longer valid
	delete(s.processes, runtimeID)

	// Check if we should attempt automatic restart:
	// 1. Process exited with error
	// 2. Restart policy allows it (on_failure mode)
	// 3. Transport is stdio (only supported mode)
	// 4. Haven't exceeded max retry count
	// 5. Not an intentional stop (handle.stopping or runtime status)
	if handle.waitErr != nil && s.shouldRestart(handle.agent.Launch.Restart, runtime.Transport, runtime.RestartAttempts) && !handle.stopping && runtime.Status != StatusStopping {
		// Transition to starting state for restart
		runtime.Status = StatusStarting
		runtime.LastError = handle.waitErr.Error()
		runtime.PID = 0
		runtime.StoppedAt = time.Time{}
		runtime.RestartAttempts++
		runtime.FailureStreak++
		runtime.LastFailureAt = s.now().UTC()
		s.runtimes[runtimeID] = runtime
		s.mu.Unlock()
		s.persistRuntime(runtime)
		s.appendLog(runtimeID, LogEntry{
			Timestamp: s.now().UTC(),
			Stream:    "helper",
			Message:   fmt.Sprintf("restart scheduled after failure: %s", handle.waitErr.Error()),
		})
		// Restart in background with backoff delay
		go s.restartAfterBackoff(runtime, handle.agent, handle.envOverrides)
		return
	}

	// No restart - clean up agent mapping and determine final state
	s.deleteRuntimeByAgentIfMatchesLocked(agentID, runtimeID)
	if handle.waitErr != nil {
		// Process failed - mark as failed and check circuit breaker
		runtime.Status = StatusFailed
		runtime.LastError = handle.waitErr.Error()
		runtime.PID = 0
		runtime.StoppedAt = time.Time{}
		runtime.FailureStreak++
		runtime.LastFailureAt = s.now().UTC()
		// Open circuit breaker if restart mode is on_failure and this wasn't intentional
		runtime.CircuitOpen = restartMode(handle.agent.Launch.Restart) == "on_failure" && !handle.stopping
		s.runtimes[runtimeID] = runtime
		s.mu.Unlock()
		s.persistRuntime(runtime)
		if runtime.CircuitOpen {
			s.appendLog(runtimeID, LogEntry{
				Timestamp: s.now().UTC(),
				Stream:    "helper",
				Message:   "restart limit reached; circuit opened",
			})
		}
		// Persist failure for post-mortem diagnostics
		s.persistFailure(runtimeID, runtime, lastLogEntries(s.logs[runtimeID], 5), s.now().UTC())
		return
	}

	// Clean exit (no error) - mark as stopped
	runtime.Status = StatusStopped
	runtime.PID = 0
	runtime.StoppedAt = s.now().UTC()
	s.runtimes[runtimeID] = runtime
	s.mu.Unlock()
	s.persistRuntime(runtime)
}

// pruneStoppedRuntimesLocked removes stopped runtimes after the retention
// window so live runtime listings do not grow without bound.
func (s *Supervisor) pruneStoppedRuntimesLocked(now time.Time) {
	cutoff := now.Add(-stoppedRuntimeRetention)
	for runtimeID, runtime := range s.runtimes {
		if runtime.Status != StatusStopped {
			continue
		}
		if runtime.StoppedAt.IsZero() || runtime.StoppedAt.After(cutoff) {
			continue
		}
		delete(s.runtimes, runtimeID)
		delete(s.logs, runtimeID)
		delete(s.processes, runtimeID)
	}
}

// restartAfterBackoff preserves the existing runtime identity while retrying
// the launch. That keeps diagnostics stable across a short crash loop.
func (s *Supervisor) restartAfterBackoff(runtimeInfo Runtime, agent catalog.Agent, envOverrides map[string]string) {
	// Wait for configured backoff duration before retrying
	time.Sleep(restartBackoff(agent.Launch.Restart))

	// Attempt to relaunch using the same runtime ID for diagnostic continuity
	restarted, err := s.launchRuntime(agent, &runtimeInfo, envOverrides)
	if err == nil {
		s.appendLog(restarted.ID, LogEntry{
			Timestamp: s.now().UTC(),
			Stream:    "helper",
			Message:   fmt.Sprintf("runtime restarted successfully (attempt %d)", restarted.RestartAttempts),
		})
		return
	}

	// Restart failed - update runtime state and persist failure
	s.mu.Lock()
	runtimeInfo, ok := s.runtimes[runtimeInfo.ID]
	if !ok {
		// Runtime was removed while we were trying to restart
		s.mu.Unlock()
		return
	}
	s.deleteRuntimeByAgentIfMatchesLocked(runtimeInfo.AgentID, runtimeInfo.ID)
	delete(s.processes, runtimeInfo.ID)
	runtimeInfo.Status = StatusFailed
	runtimeInfo.LastError = err.Error()
	runtimeInfo.PID = 0
	s.runtimes[runtimeInfo.ID] = runtimeInfo
	s.mu.Unlock()

	s.persistRuntime(runtimeInfo)
	s.persistFailure(runtimeInfo.ID, runtimeInfo, lastLogEntries(s.logs[runtimeInfo.ID], 5), s.now().UTC())
}

// cleanupFailedLaunch removes a runtime that never reached running state and
// persists it as a failure for post-mortem diagnostics.
func (s *Supervisor) cleanupFailedLaunch(runtimeID, agentID string, err error) {
	s.mu.Lock()
	runtimeInfo, ok := s.runtimes[runtimeID]
	if !ok {
		s.mu.Unlock()
		return
	}
	delete(s.processes, runtimeID)
	s.deleteRuntimeByAgentIfMatchesLocked(agentID, runtimeID)
	delete(s.runtimes, runtimeID)
	s.mu.Unlock()

	runtimeInfo.Status = StatusFailed
	runtimeInfo.LastError = err.Error()
	runtimeInfo.PID = 0
	s.persistRuntime(runtimeInfo)
	s.persistFailure(runtimeID, runtimeInfo, lastLogEntries(s.logs[runtimeID], 5), s.now().UTC())
}

// captureLogs copies process output into the in-memory ring buffer while also
// mirroring it to the optional sink for local developer visibility.
func (s *Supervisor) captureLogs(runtimeID, stream string, source io.Reader, sink io.Writer) {
	scanner := bufio.NewScanner(source)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, 1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		if sink != nil {
			_, _ = fmt.Fprintln(sink, line)
		}
		s.appendLog(runtimeID, LogEntry{
			Timestamp: s.now().UTC(),
			Stream:    stream,
			Message:   line,
		})
	}
	if err := scanner.Err(); err != nil {
		s.appendLog(runtimeID, LogEntry{
			Timestamp: s.now().UTC(),
			Stream:    stream,
			Message:   "log stream error: " + err.Error(),
		})
	}
}

func (s *Supervisor) appendLog(runtimeID string, entry LogEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Maintain a bounded ring buffer of 200 entries per runtime
	// to prevent unbounded memory growth
	const maxEntries = 200
	entries := append(s.logs[runtimeID], entry)
	if len(entries) > maxEntries {
		// Drop oldest entries when buffer exceeds limit
		entries = entries[len(entries)-maxEntries:]
	}
	s.logs[runtimeID] = entries
}

// deleteRuntimeByAgentIfMatchesLocked removes the agent-to-runtime mapping only
// if it points to the specified runtime ID. This prevents accidentally deleting
// a mapping that has been updated to point to a different runtime.
func (s *Supervisor) deleteRuntimeByAgentIfMatchesLocked(agentID, runtimeID string) {
	if currentRuntimeID, ok := s.runtimeByAgent[agentID]; ok && currentRuntimeID == runtimeID {
		delete(s.runtimeByAgent, agentID)
	}
}

// cloneStringMap creates a shallow copy of a string map to avoid mutations
// of shared state. Returns nil for empty maps.
func cloneStringMap(values map[string]string) map[string]string {
	if len(values) == 0 {
		return nil
	}
	cloned := make(map[string]string, len(values))
	for key, value := range values {
		cloned[key] = value
	}
	return cloned
}

// mergeEnvOverrides combines current environment overrides with new updates.
// New values overwrite existing keys. Returns nil if both inputs are empty.
func mergeEnvOverrides(current map[string]string, updates map[string]string) map[string]string {
	if len(current) == 0 && len(updates) == 0 {
		return nil
	}
	merged := cloneStringMap(current)
	if merged == nil {
		merged = make(map[string]string, len(updates))
	}
	for key, value := range updates {
		merged[key] = value
	}
	return merged
}

// mergeProcessEnv merges environment variable overrides with the current
// process environment. Base environment variables are loaded first, then
// overrides are applied on top. The result is sorted alphabetically.
func mergeProcessEnv(overrides map[string]string) []string {
	// Start with the current process environment
	base := os.Environ()
	if len(overrides) == 0 {
		return base
	}

	// Parse base env into a map for easy merging
	merged := make(map[string]string, len(base)+len(overrides))
	for _, entry := range base {
		if key, value, ok := strings.Cut(entry, "="); ok {
			merged[key] = value
		}
	}

	// Apply overrides (they take precedence)
	for key, value := range overrides {
		merged[key] = value
	}

	// Convert back to KEY=VALUE slice and sort for deterministic ordering
	out := make([]string, 0, len(merged))
	for key, value := range merged {
		out = append(out, key+"="+value)
	}
	sort.Strings(out)
	return out
}

// lastLogEntries returns the last N entries from a log buffer, or the entire
// buffer if it contains fewer than limit entries. Returns nil for invalid limits.
func lastLogEntries(entries []LogEntry, limit int) []LogEntry {
	if limit <= 0 || len(entries) == 0 {
		return nil
	}
	// Extract the tail of the log buffer
	if len(entries) > limit {
		entries = entries[len(entries)-limit:]
	}
	// Return a copy to prevent holding references to the full buffer
	out := make([]LogEntry, len(entries))
	copy(out, entries)
	return out
}

// failureSortTime returns the appropriate timestamp for sorting failures.
// It prefers FailedAt if available, otherwise falls back to CreatedAt.
func failureSortTime(failure FailureSummary) time.Time {
	if !failure.FailedAt.IsZero() {
		return failure.FailedAt
	}
	return failure.CreatedAt
}

// failureSummaryFromRecord converts a persisted SQLite failure record into
// a FailureSummary struct. It attempts to decode the JSON log preview,
// falling back to an error message if deserialization fails.
func failureSummaryFromRecord(record storage.RuntimeFailureRecord) FailureSummary {
	var logLines []LogEntry
	if strings.TrimSpace(record.LogPreview) != "" {
		// Attempt to deserialize the persisted log preview
		if err := json.Unmarshal([]byte(record.LogPreview), &logLines); err != nil {
			// Provide a sentinel error if deserialization fails
			logLines = []LogEntry{{
				Timestamp: record.FailedAt,
				Stream:    "helper",
				Message:   "failed to decode persisted log preview",
			}}
		}
	}

	return FailureSummary{
		RuntimeID:      record.RuntimeID,
		AgentID:        record.AgentID,
		AgentName:      record.AgentName,
		LastError:      record.LastError,
		CreatedAt:      record.CreatedAt,
		FailedAt:       record.FailedAt,
		RecentLogLines: logLines,
	}
}

// persistFailure stores a small failure summary instead of raw transcript or
// full logs. That keeps the helper useful for debugging without turning SQLite
// into a session store.
func (s *Supervisor) persistFailure(runtimeID string, runtime Runtime, logLines []LogEntry, failedAt time.Time) {
	if s.store == nil {
		return
	}

	// Serialize log preview for storage
	logPreview, err := json.Marshal(logLines)
	if err != nil {
		s.logger.Warn("serialize runtime failure log preview failed", "runtime_id", runtimeID, "error", err)
		return
	}

	// Store failure record in SQLite (best-effort)
	if err := s.store.SaveRuntimeFailure(context.Background(), storage.RuntimeFailureRecord{
		RuntimeID:  runtimeID,
		AgentID:    runtime.AgentID,
		AgentName:  runtime.AgentName,
		LastError:  runtime.LastError,
		CreatedAt:  runtime.CreatedAt,
		FailedAt:   failedAt,
		LogPreview: string(logPreview),
	}); err != nil {
		s.logger.Error("persist runtime failure failed", "runtime_id", runtimeID, "error", err)
	}
}

func (s *Supervisor) stopProcess(handle *processHandle, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return stopProcessWithContext(ctx, handle)
}

// stopProcessWithContext gracefully terminates a process with context-based timeout.
// It follows a multi-stage shutdown sequence:
// 1. Close stdin to signal EOF to the process
// 2. Send SIGINT for graceful shutdown
// 3. If timeout expires, send SIGKILL as last resort
// 4. Wait briefly for SIGKILL to take effect
func stopProcessWithContext(ctx context.Context, handle *processHandle) error {
	if handle == nil || handle.cmd == nil || handle.cmd.Process == nil {
		return nil
	}

	// Check if process already exited
	select {
	case <-handle.done:
		return nil
	default:
	}

	// Stage 1: Close stdin to signal EOF to the process
	if handle.stdin != nil {
		_ = handle.stdin.Close()
	}

	// Stage 2: Send SIGINT for graceful shutdown
	if err := handle.cmd.Process.Signal(os.Interrupt); err != nil && !errors.Is(err, os.ErrProcessDone) {
		// SIGINT failed - escalate to SIGKILL immediately
		if killErr := handle.cmd.Process.Kill(); killErr != nil && !errors.Is(killErr, os.ErrProcessDone) {
			return killErr
		}
	}

	// Stage 3: Wait for graceful shutdown or timeout
	select {
	case <-handle.done:
		return nil
	case <-ctx.Done():
		// Timeout expired - force kill
		if err := handle.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
			return err
		}

		// Stage 4: Brief wait for SIGKILL to take effect
		select {
		case <-handle.done:
			return nil
		case <-time.After(250 * time.Millisecond):
			return ctx.Err()
		}
	}
}

// randomToken generates a URL-safe base64-encoded random token of the specified
// byte length. It panics if the system random source fails, as this indicates
// a critical system issue.
func randomToken(byteLen int) string {
	buf := make([]byte, byteLen)
	if _, err := rand.Read(buf); err != nil {
		panic(fmt.Errorf("runtime token generation failed: %w", err))
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

// waitForLaunchReadiness is intentionally transport-specific. It answers only
// "is the child ready enough for the next probe?" while the separate health
// check answers "is it actually usable as ACP?"
//
// Currently supports "immediate" mode which assumes readiness as soon as the
// process starts. Other modes may be added for more complex startup sequences.
func waitForLaunchReadiness(launch catalog.LaunchConfig) error {
	switch readinessMode(launch) {
	case "immediate":
		return nil
	default:
		return fmt.Errorf("unsupported readiness mode %q", launch.Readiness.Mode)
	}
}

// readinessMode determines the readiness check strategy for a launch config.
// It uses the explicit mode if set, otherwise defaults based on transport type.
func readinessMode(launch catalog.LaunchConfig) string {
	if launch.Readiness.Mode != "" {
		return launch.Readiness.Mode
	}
	if launch.Transport == "stdio" {
		return "immediate"
	}
	return ""
}

// shouldRestart determines whether a failed runtime should be automatically
// restarted based on the restart mode, transport type, and retry count.
func (s *Supervisor) shouldRestart(restart catalog.RestartConfig, transport string, attempts int) bool {
	mode := restartMode(restart)
	if mode != "on_failure" {
		return false
	}
	if transport != "stdio" {
		return false
	}
	if attempts >= restartMaxRetries(restart) {
		return false
	}
	return true
}

// restartMode returns the restart mode from config, defaulting to "never" if
// not specified.
func restartMode(restart catalog.RestartConfig) string {
	if restart.Mode == "" {
		return "never"
	}
	return restart.Mode
}

// restartMaxRetries returns the maximum number of restart attempts allowed.
// Negative values are treated as 0 to prevent infinite loops.
func restartMaxRetries(restart catalog.RestartConfig) int {
	if restart.MaxRetries < 0 {
		return 0
	}
	return restart.MaxRetries
}

// restartBackoff returns the delay duration before attempting a restart.
// Non-positive values result in zero delay (immediate restart).
func restartBackoff(restart catalog.RestartConfig) time.Duration {
	if restart.BackoffSeconds <= 0 {
		return 0
	}
	return time.Duration(restart.BackoffSeconds) * time.Second
}

// runHealthCheck is the final gate before a runtime becomes connectable. The
// current checks are intentionally small and manifest-driven so the runtime
// package does not need agent-specific branching.
//
// Supported modes:
//   - "none": Skip health checks entirely (default for stdio transport)
func runHealthCheck(_ catalog.LaunchConfig, healthCheck catalog.HealthCheckConfig) error {
	switch healthCheckMode(healthCheck) {
	case "none":
		return nil
	default:
		return fmt.Errorf("unsupported healthCheck mode %q", healthCheck.Mode)
	}
}

// healthCheckMode returns the health check mode from config, defaulting to
// "none" if not specified.
func healthCheckMode(healthCheck catalog.HealthCheckConfig) string {
	if healthCheck.Mode == "" {
		return "none"
	}
	return healthCheck.Mode
}

// persistRuntime saves the current runtime state to the SQLite store if one
// is configured. Errors are logged but not propagated to callers since
// persistence is best-effort for diagnostics.
func (s *Supervisor) persistRuntime(runtime Runtime) {
	if s.store == nil {
		return
	}

	if err := s.store.SaveRuntime(context.Background(), storage.RuntimeRecord{
		RuntimeID: runtime.ID,
		AgentID:   runtime.AgentID,
		AgentName: runtime.AgentName,
		Status:    runtime.Status,
		Command:   runtime.Command,
		PID:       runtime.PID,
		LastError: runtime.LastError,
		CreatedAt: runtime.CreatedAt,
	}); err != nil {
		s.logger.Error("persist runtime failed", "runtime_id", runtime.ID, "error", err)
	}
}
