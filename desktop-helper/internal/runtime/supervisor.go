package runtime

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/acquire"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const connectTokenTTL = 5 * time.Minute

// stoppedRuntimeRetention keeps recently stopped runtimes visible long enough
// for clients and diagnostics to confirm that a stop completed successfully.
const stoppedRuntimeRetention = 10 * time.Minute

var (
	ErrRuntimeNotFound        = errors.New("runtime not found")
	ErrAgentNotDetected       = errors.New("agent is not detected on this host")
	ErrRemoteStartNotAllowed  = errors.New("agent does not allow helper-managed remote start")
	ErrUnsupportedLaunch      = errors.New("agent launch mode is not supported yet")
	ErrExecutableNotFound     = errors.New("agent executable not found")
	ErrRuntimeNotRunning      = errors.New("runtime is not running")
	ErrRuntimeNotConnectable  = errors.New("runtime is not connectable")
	ErrRuntimeAlreadyAttached = errors.New("runtime is already attached to an ACP session")
)

const (
	StatusStarting = "starting"
	StatusRunning  = "running"
	StatusStopping = "stopping"
	StatusStopped  = "stopped"
	StatusFailed   = "failed"
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

type LogEntry struct {
	Timestamp time.Time `json:"timestamp"`
	Stream    string    `json:"stream"`
	Message   string    `json:"message"`
}

type Summary struct {
	Total          int              `json:"total"`
	Starting       int              `json:"starting"`
	Running        int              `json:"running"`
	Stopping       int              `json:"stopping"`
	Stopped        int              `json:"stopped"`
	Failed         int              `json:"failed"`
	CircuitOpen    int              `json:"circuitOpen"`
	RecentFailures []FailureSummary `json:"recentFailures"`
}

type FailureSummary struct {
	RuntimeID      string     `json:"runtimeId"`
	AgentID        string     `json:"agentId"`
	AgentName      string     `json:"agentName"`
	LastError      string     `json:"lastError"`
	CreatedAt      time.Time  `json:"createdAt"`
	FailedAt       time.Time  `json:"failedAt,omitempty"`
	RecentLogLines []LogEntry `json:"recentLogLines"`
}

type ConnectDescriptor struct {
	RuntimeID      string    `json:"runtimeId"`
	Protocol       string    `json:"protocol"`
	WebSocketPath  string    `json:"websocketPath"`
	BearerToken    string    `json:"bearerToken"`
	TokenExpiresAt time.Time `json:"tokenExpiresAt"`
}

// Supervisor owns the full runtime lifecycle for helper-managed agents. It is
// intentionally the single place that knows how manifests turn into processes,
// readiness checks, restart behavior, and diagnostic state.
type Supervisor struct {
	logger         *slog.Logger
	mu             sync.Mutex
	now            func() time.Time
	runtimes       map[string]Runtime
	runtimeByAgent map[string]string
	processes      map[string]*processHandle
	logs           map[string][]LogEntry
	baseDir        string
	store          *storage.SQLiteStore
	installer      *acquire.Installer
}

type processHandle struct {
	cmd            *exec.Cmd
	done           chan struct{}
	waitErr        error
	stdin          io.WriteCloser
	stdout         io.ReadCloser
	attached       bool
	stopping       bool
	agent          catalog.Agent
	restartAttempt int
}

type shutdownTarget struct {
	runtime Runtime
	process *processHandle
}

func NewSupervisor(logger *slog.Logger) *Supervisor {
	return NewSupervisorWithBaseDir(logger, ".", nil)
}

func NewSupervisorWithBaseDir(logger *slog.Logger, baseDir string, store *storage.SQLiteStore) *Supervisor {
	return NewSupervisorWithBaseDirAndInstaller(logger, baseDir, store, nil)
}

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

func (s *Supervisor) Start(agent catalog.Agent) (Runtime, error) {
	s.mu.Lock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())
	if runtimeID, ok := s.runtimeByAgent[agent.ID]; ok {
		if existing, exists := s.runtimes[runtimeID]; exists {
			s.mu.Unlock()
			return existing, nil
		}
	}
	s.mu.Unlock()

	if !agent.Detected {
		if s.installer != nil {
			acquiredAgent, _, err := s.installer.Ensure(context.Background(), agent)
			if err != nil {
				return Runtime{}, err
			}
			agent = acquiredAgent
		}
	}
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

	return s.launchRuntime(agent, nil)
}

// launchRuntime is the core transition from a validated catalog agent to a
// tracked runtime. It resolves the launch target, starts the child process,
// records it immediately for diagnostics, then performs readiness and health
// checks before exposing it as running.
func (s *Supervisor) launchRuntime(agent catalog.Agent, previous *Runtime) (Runtime, error) {
	commandPath, workingDir, err := s.resolveLaunch(agent.Launch)
	if err != nil {
		return Runtime{}, err
	}

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

	cmd := exec.Command(commandPath, agent.Launch.Args...)
	cmd.Dir = workingDir
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

	handle := &processHandle{
		cmd:            cmd,
		done:           make(chan struct{}),
		stdin:          stdinPipe,
		stdout:         stdoutPipe,
		agent:          agent,
		restartAttempt: runtimeInfo.RestartAttempts,
	}
	go func() {
		handle.waitErr = cmd.Wait()
		close(handle.done)
	}()

	s.mu.Lock()
	s.runtimes[runtimeInfo.ID] = runtimeInfo
	s.runtimeByAgent[agent.ID] = runtimeInfo.ID
	s.processes[runtimeInfo.ID] = handle
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)

	go s.captureLogs(runtimeInfo.ID, "stderr", stderrPipe, os.Stderr)
	go s.watchProcess(runtimeInfo.ID, agent.ID, handle)

	if err := waitForLaunchReadiness(agent.Launch); err != nil {
		handle.stopping = true
		_ = cmd.Process.Kill()
		<-handle.done
		checkErr := fmt.Errorf("runtime readiness check failed: %w", err)
		s.cleanupFailedLaunch(runtimeInfo.ID, agent.ID, checkErr)
		return Runtime{}, checkErr
	}
	if err := runHealthCheck(agent.Launch, agent.HealthCheck); err != nil {
		handle.stopping = true
		_ = cmd.Process.Kill()
		<-handle.done
		checkErr := fmt.Errorf("runtime health check failed: %w", err)
		s.cleanupFailedLaunch(runtimeInfo.ID, agent.ID, checkErr)
		return Runtime{}, checkErr
	}

	s.mu.Lock()
	runtimeInfo = s.runtimes[runtimeInfo.ID]
	runtimeInfo.Status = StatusRunning
	runtimeInfo.CircuitOpen = false
	s.runtimes[runtimeInfo.ID] = runtimeInfo
	s.mu.Unlock()
	s.persistRuntime(runtimeInfo)
	return runtimeInfo, nil
}

// StopByAgentID is the public stop path used by the API. It removes the
// runtime from the active maps before waiting on process termination so a
// concurrent reconnect cannot attach to a runtime that is already stopping.
func (s *Supervisor) StopByAgentID(agentID string) (Runtime, error) {
	s.mu.Lock()
	runtimeID, ok := s.runtimeByAgent[agentID]
	if !ok {
		s.mu.Unlock()
		return Runtime{}, ErrRuntimeNotFound
	}
	runtime, ok := s.runtimes[runtimeID]
	if !ok {
		delete(s.runtimeByAgent, agentID)
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
	runtime.StoppedAt = time.Time{}
	s.runtimes[runtimeID] = runtime
	delete(s.runtimeByAgent, agentID)
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

func (s *Supervisor) watchProcess(runtimeID, agentID string, handle *processHandle) {
	<-handle.done
	s.handleProcessExit(runtimeID, agentID, handle)
}

// Shutdown drains all active runtimes best-effort during daemon shutdown. Each
// runtime is first marked stopping in persisted state so diagnostics still show
// an intentional shutdown rather than an unexplained disappearance.
func (s *Supervisor) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	targets := make([]shutdownTarget, 0, len(s.runtimes))
	for runtimeID, runtime := range s.runtimes {
		targets = append(targets, shutdownTarget{
			runtime: runtime,
			process: s.processes[runtimeID],
		})
		delete(s.processes, runtimeID)
		delete(s.runtimeByAgent, runtime.AgentID)
		delete(s.runtimes, runtimeID)
	}
	s.mu.Unlock()

	var failures []string
	for _, target := range targets {
		target.runtime.Status = StatusStopping
		s.persistRuntime(target.runtime)
		if err := stopProcessWithContext(ctx, target.process); err != nil {
			failures = append(failures, fmt.Sprintf("%s: %v", target.runtime.ID, err))
		}
		target.runtime.Status = StatusStopped
		target.runtime.PID = 0
		target.runtime.StoppedAt = s.now().UTC()
		s.persistRuntime(target.runtime)
	}

	if len(failures) > 0 {
		return fmt.Errorf("runtime shutdown failed: %s", strings.Join(failures, "; "))
	}
	return nil
}

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

func (s *Supervisor) Summary() Summary {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneStoppedRuntimesLocked(s.now().UTC())

	summary := Summary{}
	failuresByRuntimeID := make(map[string]FailureSummary)

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
			summary.Stopped++
		}
	}

	if s.store != nil {
		persistedFailures, err := s.store.ListRecentRuntimeFailures(context.Background(), 5)
		if err != nil {
			s.logger.Warn("load runtime failures failed", "error", err)
		} else {
			for _, record := range persistedFailures {
				if _, exists := failuresByRuntimeID[record.RuntimeID]; exists {
					continue
				}
				failuresByRuntimeID[record.RuntimeID] = failureSummaryFromRecord(record)
			}
		}
	}

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

func (s *Supervisor) resolveCommandPath(command string) string {
	if filepath.IsAbs(command) {
		return command
	}
	return filepath.Join(s.baseDir, command)
}

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
	delete(s.runtimeByAgent, runtime.AgentID)
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
		s.mu.Unlock()
		return
	}

	delete(s.processes, runtimeID)
	if handle.waitErr != nil && s.shouldRestart(handle.agent.Launch.Restart, runtime.Transport, runtime.RestartAttempts) && !handle.stopping && runtime.Status != StatusStopping {
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
		go s.restartAfterBackoff(runtime, handle.agent)
		return
	}

	delete(s.runtimeByAgent, agentID)
	if handle.waitErr != nil {
		runtime.Status = StatusFailed
		runtime.LastError = handle.waitErr.Error()
		runtime.PID = 0
		runtime.StoppedAt = time.Time{}
		runtime.FailureStreak++
		runtime.LastFailureAt = s.now().UTC()
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
		s.persistFailure(runtimeID, runtime, lastLogEntries(s.logs[runtimeID], 5), s.now().UTC())
		return
	}

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
func (s *Supervisor) restartAfterBackoff(runtimeInfo Runtime, agent catalog.Agent) {
	time.Sleep(restartBackoff(agent.Launch.Restart))

	restarted, err := s.launchRuntime(agent, &runtimeInfo)
	if err == nil {
		s.appendLog(restarted.ID, LogEntry{
			Timestamp: s.now().UTC(),
			Stream:    "helper",
			Message:   fmt.Sprintf("runtime restarted successfully (attempt %d)", restarted.RestartAttempts),
		})
		return
	}

	s.mu.Lock()
	runtimeInfo, ok := s.runtimes[runtimeInfo.ID]
	if !ok {
		s.mu.Unlock()
		return
	}
	delete(s.runtimeByAgent, runtimeInfo.AgentID)
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
	delete(s.runtimeByAgent, agentID)
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

	const maxEntries = 200
	entries := append(s.logs[runtimeID], entry)
	if len(entries) > maxEntries {
		entries = entries[len(entries)-maxEntries:]
	}
	s.logs[runtimeID] = entries
}

func lastLogEntries(entries []LogEntry, limit int) []LogEntry {
	if limit <= 0 || len(entries) == 0 {
		return nil
	}
	if len(entries) > limit {
		entries = entries[len(entries)-limit:]
	}
	out := make([]LogEntry, len(entries))
	copy(out, entries)
	return out
}

func failureSortTime(failure FailureSummary) time.Time {
	if !failure.FailedAt.IsZero() {
		return failure.FailedAt
	}
	return failure.CreatedAt
}

func failureSummaryFromRecord(record storage.RuntimeFailureRecord) FailureSummary {
	var logLines []LogEntry
	if strings.TrimSpace(record.LogPreview) != "" {
		if err := json.Unmarshal([]byte(record.LogPreview), &logLines); err != nil {
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

	logPreview, err := json.Marshal(logLines)
	if err != nil {
		s.logger.Warn("serialize runtime failure log preview failed", "runtime_id", runtimeID, "error", err)
		return
	}

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

func stopProcessWithContext(ctx context.Context, handle *processHandle) error {
	if handle == nil || handle.cmd == nil || handle.cmd.Process == nil {
		return nil
	}

	select {
	case <-handle.done:
		return nil
	default:
	}

	if handle.stdin != nil {
		_ = handle.stdin.Close()
	}
	if err := handle.cmd.Process.Signal(os.Interrupt); err != nil && !errors.Is(err, os.ErrProcessDone) {
		if killErr := handle.cmd.Process.Kill(); killErr != nil && !errors.Is(killErr, os.ErrProcessDone) {
			return killErr
		}
	}

	select {
	case <-handle.done:
		return nil
	case <-ctx.Done():
		if err := handle.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
			return err
		}

		select {
		case <-handle.done:
			return nil
		case <-time.After(250 * time.Millisecond):
			return ctx.Err()
		}
	}
}

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
func waitForLaunchReadiness(launch catalog.LaunchConfig) error {
	switch readinessMode(launch) {
	case "immediate":
		return nil
	default:
		return fmt.Errorf("unsupported readiness mode %q", launch.Readiness.Mode)
	}
}

func readinessMode(launch catalog.LaunchConfig) string {
	if launch.Readiness.Mode != "" {
		return launch.Readiness.Mode
	}
	if launch.Transport == "stdio" {
		return "immediate"
	}
	return ""
}

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

func restartMode(restart catalog.RestartConfig) string {
	if restart.Mode == "" {
		return "never"
	}
	return restart.Mode
}

func restartMaxRetries(restart catalog.RestartConfig) int {
	if restart.MaxRetries < 0 {
		return 0
	}
	return restart.MaxRetries
}

func restartBackoff(restart catalog.RestartConfig) time.Duration {
	if restart.BackoffSeconds <= 0 {
		return 0
	}
	return time.Duration(restart.BackoffSeconds) * time.Second
}

// runHealthCheck is the final gate before a runtime becomes connectable. The
// current checks are intentionally small and manifest-driven so the runtime
// package does not need agent-specific branching.
func runHealthCheck(_ catalog.LaunchConfig, healthCheck catalog.HealthCheckConfig) error {
	switch healthCheckMode(healthCheck) {
	case "none":
		return nil
	default:
		return fmt.Errorf("unsupported healthCheck mode %q", healthCheck.Mode)
	}
}

func healthCheckMode(healthCheck catalog.HealthCheckConfig) string {
	if healthCheck.Mode == "" {
		return "none"
	}
	return healthCheck.Mode
}

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
