package logging

import (
	"bufio"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const defaultLogFileName = "helper.log"

// Service is a tiny rolling log writer used by the helper for local diagnostics
// export. It intentionally avoids introducing a heavier logging dependency.
type Service struct {
	mu         sync.Mutex
	dir        string
	fileName   string
	maxSize    int64
	maxBackups int
	file       *os.File
	size       int64
}

// New returns both the structured helper logger and the underlying file
// service so diagnostics export can tail recent log lines.
func New(level, dir string, maxSize int64, maxBackups int) (*slog.Logger, *Service, error) {
	service, err := NewService(dir, defaultLogFileName, maxSize, maxBackups)
	if err != nil {
		return nil, nil, err
	}

	return slog.New(slog.NewJSONHandler(io.MultiWriter(os.Stdout, service), &slog.HandlerOptions{
		Level: parseLevel(level),
	})), service, nil
}

func NewService(dir, fileName string, maxSize int64, maxBackups int) (*Service, error) {
	if maxSize <= 0 {
		maxSize = 1024 * 1024
	}
	if maxBackups <= 0 {
		maxBackups = 3
	}

	service := &Service{
		dir:        dir,
		fileName:   fileName,
		maxSize:    maxSize,
		maxBackups: maxBackups,
	}
	if err := service.ensureFileLocked(); err != nil {
		return nil, err
	}
	return service, nil
}

func (s *Service) Close() error {
	if s == nil {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.file == nil {
		return nil
	}
	err := s.file.Close()
	s.file = nil
	s.size = 0
	return err
}

func (s *Service) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.ensureFileLocked(); err != nil {
		return 0, err
	}
	if s.size+int64(len(p)) > s.maxSize {
		if err := s.rotateLocked(); err != nil {
			return 0, err
		}
	}

	written, err := s.file.Write(p)
	s.size += int64(written)
	return written, err
}

// TailLines reads rotated files from oldest to newest and returns the last
// requested lines. This is optimized for small diagnostic bundles, not for
// arbitrary log browsing.
func (s *Service) TailLines(limit int) ([]string, error) {
	if limit <= 0 {
		return nil, nil
	}

	s.mu.Lock()
	paths := s.logPathsLocked()
	s.mu.Unlock()

	lines := make([]string, 0, limit)
	for _, path := range paths {
		file, err := os.Open(path)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, err
		}

		scanner := bufio.NewScanner(file)
		buffer := make([]byte, 0, 64*1024)
		scanner.Buffer(buffer, 1024*1024)
		for scanner.Scan() {
			lines = append(lines, scanner.Text())
		}
		closeErr := file.Close()
		if err := scanner.Err(); err != nil {
			return nil, err
		}
		if closeErr != nil {
			return nil, closeErr
		}
	}

	if len(lines) > limit {
		lines = lines[len(lines)-limit:]
	}
	return lines, nil
}

func (s *Service) ensureFileLocked() error {
	if err := os.MkdirAll(s.dir, 0o755); err != nil {
		return err
	}
	if s.file != nil {
		return nil
	}

	path := filepath.Join(s.dir, s.fileName)
	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}

	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return err
	}

	s.file = file
	s.size = info.Size()
	return nil
}

// rotateLocked shifts numbered backups upward before reopening the active log
// file. The caller must already hold s.mu.
func (s *Service) rotateLocked() error {
	if s.file != nil {
		if err := s.file.Close(); err != nil {
			return err
		}
		s.file = nil
		s.size = 0
	}

	for index := s.maxBackups - 1; index >= 1; index-- {
		source := s.rotatedPath(index)
		target := s.rotatedPath(index + 1)
		if index == s.maxBackups-1 {
			_ = os.Remove(target)
		}
		if err := os.Rename(source, target); err != nil && !os.IsNotExist(err) {
			return err
		}
	}

	current := filepath.Join(s.dir, s.fileName)
	firstBackup := s.rotatedPath(1)
	if err := os.Rename(current, firstBackup); err != nil && !os.IsNotExist(err) {
		return err
	}

	return s.ensureFileLocked()
}

func (s *Service) rotatedPath(index int) string {
	return filepath.Join(s.dir, fmt.Sprintf("%s.%d", s.fileName, index))
}

func (s *Service) logPathsLocked() []string {
	paths := make([]string, 0, s.maxBackups+1)
	for index := s.maxBackups; index >= 1; index-- {
		paths = append(paths, s.rotatedPath(index))
	}
	paths = append(paths, filepath.Join(s.dir, s.fileName))
	return paths
}

func parseLevel(level string) slog.Level {
	switch strings.ToLower(level) {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
