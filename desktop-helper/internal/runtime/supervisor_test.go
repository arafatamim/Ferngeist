package runtime

import (
	"bufio"
	"context"
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

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/acquire"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestStartIsIdempotentPerAgent(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	supervisor.now = func() time.Time { return time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC) }

	agent := catalog.Agent{
		ID:          "mock-acp",
		DisplayName: "Mock ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", mockAgentBinaryName()),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	}

	first, err := supervisor.Start(agent)
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	second, err := supervisor.Start(agent)
	if err != nil {
		t.Fatalf("Start() second error = %v", err)
	}

	if first.ID != second.ID {
		t.Fatalf("runtime IDs differ: %q vs %q", first.ID, second.ID)
	}

	_, _ = supervisor.StopByAgentID(agent.ID)
}

func TestConnectReturnsDescriptorForRunningRuntime(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	supervisor.now = func() time.Time { return time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC) }

	runtime, err := supervisor.Start(catalog.Agent{
		ID:          "mock-acp",
		DisplayName: "Mock ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", mockAgentBinaryName()),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	descriptor, err := supervisor.Connect(runtime.ID)
	if err != nil {
		t.Fatalf("Connect() error = %v", err)
	}

	if descriptor.WebSocketPath == "" {
		t.Fatal("WebSocketPath should not be empty")
	}
	if descriptor.BearerToken == "" {
		t.Fatal("BearerToken should not be empty")
	}

	_, _ = supervisor.StopByAgentID("mock-acp")
}

func TestRestartLaunchesNewRuntimeWithMergedEnv(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "mock-acp",
		DisplayName: "Mock ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", mockAgentBinaryName()),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{Mode: "immediate"},
		},
		HealthCheck: catalog.HealthCheckConfig{Mode: "none"},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	restarted, err := supervisor.Restart(runtimeInfo.ID, map[string]string{"FERNGEIST_TEST_ENV": "after-restart"})
	if err != nil {
		t.Fatalf("Restart() error = %v", err)
	}
	if restarted.ID == runtimeInfo.ID {
		t.Fatal("Restart() should create a new runtime id")
	}

	stdin, stdout, release, err := supervisor.AttachStdio(restarted.ID)
	if err != nil {
		t.Fatalf("AttachStdio() error = %v", err)
	}
	defer release()
	defer stdin.Close()

	var ready map[string]string
	if err := json.NewDecoder(stdout).Decode(&ready); err != nil {
		t.Fatalf("Decode(ready) error = %v", err)
	}
	if ready["env"] != "after-restart" {
		t.Fatalf("ready env = %q, want %q", ready["env"], "after-restart")
	}

	runtimes := supervisor.List()
	oldStatus := ""
	for _, listed := range runtimes {
		if listed.ID == runtimeInfo.ID {
			oldStatus = listed.Status
			break
		}
	}
	if oldStatus != StatusStopped {
		t.Fatalf("old runtime status = %q, want %q", oldStatus, StatusStopped)
	}

	_, _ = supervisor.StopByRuntimeID(restarted.ID)
}

func TestStoppedRuntimeRemainsVisibleInListAndSummary(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "mock-acp",
		DisplayName: "Mock ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", mockAgentBinaryName()),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	stopped, err := supervisor.StopByRuntimeID(runtimeInfo.ID)
	if err != nil {
		t.Fatalf("StopByRuntimeID() error = %v", err)
	}
	if stopped.Status != StatusStopped {
		t.Fatalf("stopped.Status = %q, want %q", stopped.Status, StatusStopped)
	}

	runtimes := supervisor.List()
	if len(runtimes) != 1 {
		t.Fatalf("len(List()) = %d, want 1", len(runtimes))
	}
	if runtimes[0].ID != runtimeInfo.ID {
		t.Fatalf("List()[0].ID = %q, want %q", runtimes[0].ID, runtimeInfo.ID)
	}
	if runtimes[0].Status != StatusStopped {
		t.Fatalf("List()[0].Status = %q, want %q", runtimes[0].Status, StatusStopped)
	}

	summary := supervisor.Summary()
	if summary.Stopped != 1 {
		t.Fatalf("Summary().Stopped = %d, want 1", summary.Stopped)
	}
	if summary.Total != 1 {
		t.Fatalf("Summary().Total = %d, want 1", summary.Total)
	}
	if _, err := supervisor.Connect(runtimeInfo.ID); err != ErrRuntimeNotRunning {
		t.Fatalf("Connect() error = %v, want %v", err, ErrRuntimeNotRunning)
	}
}

func TestStoppedRuntimeIsPrunedAfterRetentionWindow(t *testing.T) {
	supervisor := NewSupervisor(slog.New(slog.NewTextHandler(io.Discard, nil)))
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	supervisor.now = func() time.Time { return now }

	runtimeInfo := Runtime{
		ID:        "run-stopped",
		AgentID:   "mock-acp",
		AgentName: "Mock ACP",
		Status:    StatusStopped,
		CreatedAt: now.Add(-time.Hour),
		StoppedAt: now.Add(-stoppedRuntimeRetention).Add(-time.Second),
	}
	supervisor.runtimes[runtimeInfo.ID] = runtimeInfo
	supervisor.logs[runtimeInfo.ID] = []LogEntry{{Timestamp: now, Stream: "helper", Message: "stopped"}}

	if got := len(supervisor.List()); got != 0 {
		t.Fatalf("len(List()) = %d, want 0 after retention pruning", got)
	}
	if _, err := supervisor.Logs(runtimeInfo.ID); err != ErrRuntimeNotFound {
		t.Fatalf("Logs() error = %v, want %v", err, ErrRuntimeNotFound)
	}
	if summary := supervisor.Summary(); summary.Total != 0 {
		t.Fatalf("Summary().Total = %d, want 0 after retention pruning", summary.Total)
	}
}

func TestStartRejectsUndetectedAgent(t *testing.T) {
	supervisor := NewSupervisor(slog.New(slog.NewTextHandler(io.Discard, nil)))

	_, err := supervisor.Start(catalog.Agent{
		ID:          "codex-acp",
		DisplayName: "Codex ACP",
		Detected:    false,
		Launch:      catalog.LaunchConfig{Mode: "external"},
		HealthCheck: catalog.HealthCheckConfig{Mode: "none"},
	})
	if err != ErrAgentNotDetected {
		t.Fatalf("Start() error = %v, want %v", err, ErrAgentNotDetected)
	}
}

func TestStartSupportsExternalStdioRuntime(t *testing.T) {
	baseDir := t.TempDir()
	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}
	buildMockStdioAgent(t, filepath.Join(binDir, namedBinary("codex-acp")))
	t.Setenv("PATH", binDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "codex-acp",
		DisplayName: "Codex ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "external",
			Command:   "codex-acp",
			Args:      []string{},
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
			Restart: catalog.RestartConfig{
				Mode: "never",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if runtimeInfo.Transport != "stdio" {
		t.Fatalf("Transport = %q, want %q", runtimeInfo.Transport, "stdio")
	}

	stdin, stdout, release, err := supervisor.AttachStdio(runtimeInfo.ID)
	if err != nil {
		t.Fatalf("AttachStdio() error = %v", err)
	}
	defer release()

	scanner := bufio.NewScanner(stdout)
	if !scanner.Scan() {
		t.Fatal("expected ready line from stdio runtime")
	}
	if scanner.Text() == "" {
		t.Fatal("ready line should not be empty")
	}

	if _, err := io.WriteString(stdin, "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"}\n"); err != nil {
		t.Fatalf("WriteString() error = %v", err)
	}
	if !scanner.Scan() {
		t.Fatal("expected echoed stdio line")
	}
	if got := scanner.Text(); got != "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\"}" {
		t.Fatalf("echoed line = %q", got)
	}

	_, _ = supervisor.StopByRuntimeID(runtimeInfo.ID)
}

func TestRuntimeRestartsAfterFailureWhenPolicyEnabled(t *testing.T) {
	baseDir := t.TempDir()
	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	stateFile := filepath.Join(baseDir, "restart-state.txt")
	flakySource := filepath.Join(baseDir, "flaky.go")
	source := fmt.Sprintf(`package main
import (
	"os"
	"time"
)
func main() {
	stateFile := %q
	if _, err := os.Stat(stateFile); err != nil {
		_ = os.WriteFile(stateFile, []byte("1"), 0o644)
		time.Sleep(100 * time.Millisecond)
		os.Exit(1)
	}
	for {
		time.Sleep(time.Hour)
	}
}
`, stateFile)
	if err := os.WriteFile(flakySource, []byte(source), 0o644); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	outputPath := filepath.Join(binDir, namedBinary("flaky-acp-agent"))
	command := exec.Command("go", "build", "-o", outputPath, flakySource)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		t.Fatalf("go build flaky agent error = %v", err)
	}

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "flaky-acp",
		DisplayName: "Flaky ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", namedBinary("flaky-acp-agent")),
			Transport: "stdio",
			Args:      []string{},
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
			Restart: catalog.RestartConfig{
				Mode:       "on_failure",
				MaxRetries: 1,
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	t.Cleanup(func() {
		_, _ = supervisor.StopByRuntimeID(runtimeInfo.ID)
	})

	var restarted Runtime
	found := false
	for range 40 {
		runtimes := supervisor.List()
		if len(runtimes) == 1 && runtimes[0].Status == StatusRunning && runtimes[0].RestartAttempts == 1 {
			restarted = runtimes[0]
			found = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !found {
		t.Fatal("expected runtime to restart and return to running state")
	}
	if restarted.ID != runtimeInfo.ID {
		t.Fatalf("runtime ID changed across restart: %q vs %q", restarted.ID, runtimeInfo.ID)
	}
	if restarted.CircuitOpen {
		t.Fatal("CircuitOpen should be false after successful restart")
	}
}

func TestRuntimeOpensCircuitAfterRestartLimitExceeded(t *testing.T) {
	baseDir := t.TempDir()
	binDir := filepath.Join(baseDir, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		t.Fatalf("MkdirAll() error = %v", err)
	}

	flakySource := filepath.Join(baseDir, "always-fail.go")
	source := `package main
import (
	"time"
)
func main() {
	time.Sleep(100 * time.Millisecond)
	panic("boom")
}
`
	if err := os.WriteFile(flakySource, []byte(source), 0o644); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}

	outputPath := filepath.Join(binDir, namedBinary("always-fail-acp-agent"))
	command := exec.Command("go", "build", "-o", outputPath, flakySource)
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		t.Fatalf("go build always-fail agent error = %v", err)
	}

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "always-fail-acp",
		DisplayName: "Always Fail ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", namedBinary("always-fail-acp-agent")),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
			Restart: catalog.RestartConfig{
				Mode:       "on_failure",
				MaxRetries: 1,
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	t.Cleanup(func() {
		_, _ = supervisor.StopByRuntimeID(runtimeInfo.ID)
	})

	var failed Runtime
	found := false
	for range 50 {
		runtimes := supervisor.List()
		if len(runtimes) == 1 && runtimes[0].Status == StatusFailed && runtimes[0].CircuitOpen {
			failed = runtimes[0]
			found = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !found {
		t.Fatal("expected runtime to fail with circuit open after restart limit")
	}
	if failed.RestartAttempts != 1 {
		t.Fatalf("RestartAttempts = %d, want 1", failed.RestartAttempts)
	}
	if failed.FailureStreak < 2 {
		t.Fatalf("FailureStreak = %d, want at least 2", failed.FailureStreak)
	}
}

func TestOptionalInstalledOpenCodeACPSmoke(t *testing.T) {
	if os.Getenv("FERNGEIST_RUN_REAL_AGENT_TESTS") != "1" {
		t.Skip("set FERNGEIST_RUN_REAL_AGENT_TESTS=1 to run installed-agent smoke tests")
	}
	if _, err := exec.LookPath("opencode"); err != nil {
		t.Skip("opencode is not installed on PATH")
	}

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), t.TempDir(), nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "opencode",
		DisplayName: "OpenCode",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "external",
			Command:   "opencode",
			Args:      []string{"acp"},
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
			Restart: catalog.RestartConfig{
				Mode: "never",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	t.Cleanup(func() {
		_, _ = supervisor.StopByRuntimeID(runtimeInfo.ID)
	})

	stdin, stdout, release, err := supervisor.AttachStdio(runtimeInfo.ID)
	if err != nil {
		t.Fatalf("AttachStdio() error = %v", err)
	}
	defer release()

	request := "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"capabilities\":{},\"clientInfo\":{\"name\":\"ferngeist-smoke\",\"version\":\"dev\"}}}\n"
	if _, err := io.WriteString(stdin, request); err != nil {
		t.Fatalf("WriteString() error = %v", err)
	}

	type result struct {
		line string
		err  error
	}
	done := make(chan result, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		if scanner.Scan() {
			done <- result{line: scanner.Text()}
			return
		}
		done <- result{err: scanner.Err()}
	}()

	select {
	case res := <-done:
		if res.err != nil {
			t.Fatalf("scanner error = %v", res.err)
		}
		if strings.TrimSpace(res.line) == "" {
			t.Fatal("expected non-empty ACP response line from installed opencode")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for ACP response from installed opencode")
	}
}

func TestSummaryIncludesRecentFailures(t *testing.T) {
	supervisor := NewSupervisor(slog.New(slog.NewTextHandler(io.Discard, nil)))
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)
	supervisor.now = func() time.Time { return now }

	supervisor.runtimes["run-failed"] = Runtime{
		ID:        "run-failed",
		AgentID:   "mock-acp",
		AgentName: "Mock ACP",
		Status:    StatusFailed,
		LastError: "process exited with status 1",
		CreatedAt: now,
	}
	supervisor.runtimes["run-running"] = Runtime{
		ID:        "run-running",
		AgentID:   "mock-acp",
		AgentName: "Mock ACP",
		Status:    StatusRunning,
		CreatedAt: now.Add(-time.Minute),
	}
	supervisor.logs["run-failed"] = []LogEntry{
		{Timestamp: now, Stream: "stderr", Message: "boom"},
		{Timestamp: now.Add(time.Second), Stream: "stderr", Message: "crash"},
	}

	summary := supervisor.Summary()
	if summary.Total != 2 {
		t.Fatalf("Total = %d, want 2", summary.Total)
	}
	if summary.Running != 1 {
		t.Fatalf("Running = %d, want 1", summary.Running)
	}
	if summary.Failed != 1 {
		t.Fatalf("Failed = %d, want 1", summary.Failed)
	}
	if summary.CircuitOpen != 0 {
		t.Fatalf("CircuitOpen = %d, want 0", summary.CircuitOpen)
	}
	if len(summary.RecentFailures) != 1 {
		t.Fatalf("len(RecentFailures) = %d, want 1", len(summary.RecentFailures))
	}
	if summary.RecentFailures[0].LastError != "process exited with status 1" {
		t.Fatalf("LastError = %q", summary.RecentFailures[0].LastError)
	}
	if len(summary.RecentFailures[0].RecentLogLines) != 2 {
		t.Fatalf("len(RecentLogLines) = %d, want 2", len(summary.RecentFailures[0].RecentLogLines))
	}
}

func TestSummaryCountsLifecycleTransitions(t *testing.T) {
	supervisor := NewSupervisor(slog.New(slog.NewTextHandler(io.Discard, nil)))
	now := time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC)

	supervisor.runtimes["run-starting"] = Runtime{ID: "run-starting", AgentID: "a", AgentName: "A", Status: StatusStarting, CreatedAt: now}
	supervisor.runtimes["run-running"] = Runtime{ID: "run-running", AgentID: "b", AgentName: "B", Status: StatusRunning, CreatedAt: now}
	supervisor.runtimes["run-stopping"] = Runtime{ID: "run-stopping", AgentID: "c", AgentName: "C", Status: StatusStopping, CreatedAt: now}
	supervisor.runtimes["run-stopped"] = Runtime{ID: "run-stopped", AgentID: "d", AgentName: "D", Status: StatusStopped, CreatedAt: now}
	supervisor.runtimes["run-failed"] = Runtime{ID: "run-failed", AgentID: "e", AgentName: "E", Status: StatusFailed, LastError: "boom", CreatedAt: now}
	supervisor.runtimes["run-open"] = Runtime{ID: "run-open", AgentID: "f", AgentName: "F", Status: StatusFailed, LastError: "loop", CircuitOpen: true, CreatedAt: now}

	summary := supervisor.Summary()
	if summary.Total != 6 {
		t.Fatalf("Total = %d, want 6", summary.Total)
	}
	if summary.Starting != 1 {
		t.Fatalf("Starting = %d, want 1", summary.Starting)
	}
	if summary.Running != 1 {
		t.Fatalf("Running = %d, want 1", summary.Running)
	}
	if summary.Stopping != 1 {
		t.Fatalf("Stopping = %d, want 1", summary.Stopping)
	}
	if summary.Stopped != 1 {
		t.Fatalf("Stopped = %d, want 1", summary.Stopped)
	}
	if summary.Failed != 2 {
		t.Fatalf("Failed = %d, want 2", summary.Failed)
	}
	if summary.CircuitOpen != 1 {
		t.Fatalf("CircuitOpen = %d, want 1", summary.CircuitOpen)
	}
}

func TestSummaryIncludesPersistedFailures(t *testing.T) {
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer store.Close()

	failedAt := time.Date(2026, 3, 25, 10, 5, 0, 0, time.UTC)
	if err := store.SaveRuntimeFailure(context.Background(), storage.RuntimeFailureRecord{
		RuntimeID:  "run-failed",
		AgentID:    "mock-acp",
		AgentName:  "Mock ACP",
		LastError:  "process exited with status 1",
		CreatedAt:  time.Date(2026, 3, 25, 10, 0, 0, 0, time.UTC),
		FailedAt:   failedAt,
		LogPreview: `[{"timestamp":"2026-03-25T10:04:59Z","stream":"stderr","message":"boom"}]`,
	}); err != nil {
		t.Fatalf("SaveRuntimeFailure() error = %v", err)
	}

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), t.TempDir(), store)
	summary := supervisor.Summary()

	if len(summary.RecentFailures) != 1 {
		t.Fatalf("len(RecentFailures) = %d, want 1", len(summary.RecentFailures))
	}
	if summary.RecentFailures[0].FailedAt != failedAt {
		t.Fatalf("FailedAt = %v, want %v", summary.RecentFailures[0].FailedAt, failedAt)
	}
	if len(summary.RecentFailures[0].RecentLogLines) != 1 {
		t.Fatalf("len(RecentLogLines) = %d, want 1", len(summary.RecentFailures[0].RecentLogLines))
	}
}

func TestShutdownStopsRunningProcess(t *testing.T) {
	baseDir := t.TempDir()
	buildMockAgent(t, baseDir)

	supervisor := NewSupervisorWithBaseDir(slog.New(slog.NewTextHandler(io.Discard, nil)), baseDir, nil)
	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "mock-acp",
		DisplayName: "Mock ACP",
		Detected:    true,
		Security:    catalog.SecurityConfig{AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "process",
			Command:   filepath.Join("bin", mockAgentBinaryName()),
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{
				Mode: "immediate",
			},
		},
		HealthCheck: catalog.HealthCheckConfig{
			Mode: "none",
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	if err := supervisor.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown() error = %v", err)
	}
	if got := len(supervisor.List()); got != 0 {
		t.Fatalf("len(List()) = %d, want 0", got)
	}
	if _, err := supervisor.Connect(runtimeInfo.ID); err != ErrRuntimeNotFound {
		t.Fatalf("Connect() error = %v, want %v", err, ErrRuntimeNotFound)
	}
}

func TestStartAutoAcquiresMissingExternalBinary(t *testing.T) {
	sourceDir := t.TempDir()
	sourcePath := filepath.Join(sourceDir, namedBinary("mock-stdio-agent"))
	buildMockStdioAgent(t, sourcePath)

	server := httptest.NewServer(http.FileServer(http.Dir(sourceDir)))
	defer server.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	installer := acquire.New(logger, t.TempDir(), nil)
	supervisor := NewSupervisorWithBaseDirAndInstaller(logger, t.TempDir(), nil, installer)

	runtimeInfo, err := supervisor.Start(catalog.Agent{
		ID:          "codex-acp",
		DisplayName: "Codex ACP",
		Detected:    false,
		Protocol:    "acp",
		Security:    catalog.SecurityConfig{CuratedLaunch: true, AllowsRemoteStart: true},
		Launch: catalog.LaunchConfig{
			Mode:      "external",
			Command:   "codex-acp",
			Transport: "stdio",
			Readiness: catalog.ReadinessConfig{Mode: "immediate"},
			Restart:   catalog.RestartConfig{Mode: "never"},
		},
		HealthCheck: catalog.HealthCheckConfig{Mode: "none"},
		Registry: catalog.RegistryInfo{
			CurrentBinaryPath:       namedBinary("mock-stdio-agent"),
			CurrentBinaryArchiveURL: server.URL + "/" + namedBinary("mock-stdio-agent"),
		},
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	if runtimeInfo.Status != StatusRunning {
		t.Fatalf("Status = %q, want %q", runtimeInfo.Status, StatusRunning)
	}
	if _, _, release, err := supervisor.AttachStdio(runtimeInfo.ID); err != nil {
		t.Fatalf("AttachStdio() error = %v", err)
	} else {
		release()
	}
	if _, err := supervisor.StopByRuntimeID(runtimeInfo.ID); err != nil {
		t.Fatalf("StopByRuntimeID() error = %v", err)
	}
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
