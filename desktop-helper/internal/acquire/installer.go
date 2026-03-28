package acquire

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/tamimarafat/ferngeist/desktop-helper/internal/catalog"
	"github.com/tamimarafat/ferngeist/desktop-helper/internal/storage"
)

// Installer acquires trusted registry-backed binaries into a helper-managed
// install root so launch stays uniform after the first install.
type Installer struct {
	logger     *slog.Logger
	httpClient *http.Client
	managedDir string
	store      *storage.SQLiteStore
}

func New(logger *slog.Logger, managedDir string, store *storage.SQLiteStore) *Installer {
	return &Installer{
		logger: logger.With("component", "acquire"),
		httpClient: &http.Client{
			Timeout: 60 * time.Second,
		},
		managedDir: managedDir,
		store:      store,
	}
}

func (i *Installer) Ensure(ctx context.Context, agent catalog.Agent) (catalog.Agent, bool, error) {
	if agent.Launch.Mode != "external" {
		return agent, false, nil
	}
	if strings.TrimSpace(agent.Registry.CurrentBinaryArchiveURL) == "" || strings.TrimSpace(agent.Registry.CurrentBinaryPath) == "" {
		return agent, false, nil
	}

	targetPath := filepath.Join(i.managedDir, agent.ID, filepath.Base(strings.ReplaceAll(agent.Registry.CurrentBinaryPath, "\\", "/")))
	if i.store != nil {
		record, err := i.store.GetAcquiredBinary(ctx, agent.ID)
		if err == nil {
			if record.Path == targetPath || record.Path != "" {
				if info, statErr := os.Stat(record.Path); statErr == nil && !info.IsDir() {
					targetPath = record.Path
					agent.Launch.Command = targetPath
					agent.Detection = catalog.DetectionConfig{Mode: "local_file", Path: targetPath}
					agent.Detected = true
					return agent, false, nil
				}
			}
		}
	}
	if info, err := os.Stat(targetPath); err == nil && !info.IsDir() {
		agent.Launch.Command = targetPath
		agent.Detection = catalog.DetectionConfig{Mode: "local_file", Path: targetPath}
		agent.Detected = true
		return agent, false, nil
	}

	if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
		return agent, false, err
	}

	tmpFile, err := os.CreateTemp("", "ferngeist-helper-download-*")
	if err != nil {
		return agent, false, err
	}
	tmpPath := tmpFile.Name()
	_ = tmpFile.Close()
	defer os.Remove(tmpPath)

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, agent.Registry.CurrentBinaryArchiveURL, nil)
	if err != nil {
		return agent, false, err
	}
	response, err := i.httpClient.Do(request)
	if err != nil {
		return agent, false, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return agent, false, fmt.Errorf("binary download returned status %d", response.StatusCode)
	}

	downloadFile, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return agent, false, err
	}
	if _, err := io.Copy(downloadFile, response.Body); err != nil {
		_ = downloadFile.Close()
		return agent, false, err
	}
	if err := downloadFile.Close(); err != nil {
		return agent, false, err
	}

	if err := installDownloadedArtifact(tmpPath, targetPath, agent.Registry.CurrentBinaryPath); err != nil {
		return agent, false, err
	}

	i.logger.Info("acquired registry binary", "agent_id", agent.ID, "target_path", targetPath)
	if i.store != nil {
		if err := i.store.SaveAcquiredBinary(ctx, storage.AcquiredBinaryRecord{
			AgentID:     agent.ID,
			Version:     agent.Registry.Version,
			Path:        targetPath,
			ArchiveURL:  agent.Registry.CurrentBinaryArchiveURL,
			InstalledAt: time.Now().UTC(),
		}); err != nil {
			i.logger.Warn("persist acquired binary failed", "agent_id", agent.ID, "error", err)
		}
	}
	agent.Launch.Command = targetPath
	agent.Detection = catalog.DetectionConfig{Mode: "local_file", Path: targetPath}
	agent.Detected = true
	return agent, true, nil
}

func installDownloadedArtifact(sourcePath, targetPath, archiveEntry string) error {
	lower := strings.ToLower(sourcePath)
	switch {
	case strings.HasSuffix(lower, ".zip"):
		return extractZipEntry(sourcePath, targetPath, archiveEntry)
	case strings.HasSuffix(lower, ".tar.gz"), strings.HasSuffix(lower, ".tgz"):
		return extractTarGzEntry(sourcePath, targetPath, archiveEntry)
	default:
		return copyFile(sourcePath, targetPath, 0o755)
	}
}

func extractZipEntry(sourcePath, targetPath, archiveEntry string) error {
	reader, err := zip.OpenReader(sourcePath)
	if err != nil {
		return err
	}
	defer reader.Close()

	needle := normalizeArchiveEntry(archiveEntry)
	for _, file := range reader.File {
		if normalizeArchiveEntry(file.Name) != needle && filepath.Base(file.Name) != filepath.Base(needle) {
			continue
		}
		src, err := file.Open()
		if err != nil {
			return err
		}
		defer src.Close()
		return writeReaderToFile(targetPath, src, 0o755)
	}
	return fmt.Errorf("archive entry %q not found in zip", archiveEntry)
}

func extractTarGzEntry(sourcePath, targetPath, archiveEntry string) error {
	file, err := os.Open(sourcePath)
	if err != nil {
		return err
	}
	defer file.Close()

	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzipReader.Close()

	tarReader := tar.NewReader(gzipReader)
	needle := normalizeArchiveEntry(archiveEntry)
	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if header.FileInfo().IsDir() {
			continue
		}
		if normalizeArchiveEntry(header.Name) != needle && filepath.Base(header.Name) != filepath.Base(needle) {
			continue
		}
		return writeReaderToFile(targetPath, tarReader, 0o755)
	}
	return fmt.Errorf("archive entry %q not found in tar.gz", archiveEntry)
}

func copyFile(sourcePath, targetPath string, mode os.FileMode) error {
	source, err := os.Open(sourcePath)
	if err != nil {
		return err
	}
	defer source.Close()
	return writeReaderToFile(targetPath, source, mode)
}

func writeReaderToFile(targetPath string, source io.Reader, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(targetPath), 0o755); err != nil {
		return err
	}
	file, err := os.OpenFile(targetPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(file, source); err != nil {
		_ = file.Close()
		return err
	}
	return file.Close()
}

func normalizeArchiveEntry(path string) string {
	return strings.TrimLeft(strings.ReplaceAll(path, "\\", "/"), "./")
}
