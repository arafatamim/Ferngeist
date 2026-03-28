package acquire

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

func TestEnsureDownloadsRawBinaryIntoManagedDir(t *testing.T) {
	sourceDir := t.TempDir()
	sourcePath := filepath.Join(sourceDir, namedBinary("mock-stdio-agent"))
	buildMockStdioAgent(t, sourcePath)

	server := httptest.NewServer(http.FileServer(http.Dir(sourceDir)))
	defer server.Close()

	managedDir := t.TempDir()
	store, err := storage.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatalf("storage.Open() error = %v", err)
	}
	defer store.Close()

	installer := New(slog.New(slog.NewTextHandler(os.Stdout, nil)), managedDir, store)

	agent, changed, err := installer.Ensure(context.Background(), catalog.Agent{
		ID: "codex-acp",
		Launch: catalog.LaunchConfig{
			Mode:      "external",
			Command:   "codex-acp",
			Transport: "stdio",
		},
		Registry: catalog.RegistryInfo{
			CurrentBinaryPath:       namedBinary("mock-stdio-agent"),
			CurrentBinaryArchiveURL: server.URL + "/" + namedBinary("mock-stdio-agent"),
		},
	})
	if err != nil {
		t.Fatalf("Ensure() error = %v", err)
	}
	if !changed {
		t.Fatal("Ensure() should report changed=true on first install")
	}
	if !agent.Detected {
		t.Fatal("agent should be marked detected after install")
	}
	if _, err := os.Stat(agent.Launch.Command); err != nil {
		t.Fatalf("installed binary missing at %q: %v", agent.Launch.Command, err)
	}

	record, err := store.GetAcquiredBinary(context.Background(), "codex-acp")
	if err != nil {
		t.Fatalf("GetAcquiredBinary() error = %v", err)
	}
	if record.Path != agent.Launch.Command {
		t.Fatalf("record.Path = %q, want %q", record.Path, agent.Launch.Command)
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

func namedBinary(name string) string {
	if os.PathSeparator == '\\' {
		return name + ".exe"
	}
	return name
}
