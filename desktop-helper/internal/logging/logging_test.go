package logging

import (
	"log/slog"
	"strings"
	"testing"
)

func TestServiceTailLinesReadsAcrossRotations(t *testing.T) {
	service, err := NewService(t.TempDir(), "helper.log", 120, 2)
	if err != nil {
		t.Fatalf("NewService() error = %v", err)
	}
	defer service.Close()

	logger := slog.New(slog.NewJSONHandler(service, nil))
	logger.Info("line one")
	logger.Info("line two")
	logger.Info("line three")
	logger.Info("line four")

	lines, err := service.TailLines(10)
	if err != nil {
		t.Fatalf("TailLines() error = %v", err)
	}
	if len(lines) == 0 {
		t.Fatal("TailLines() should return log lines")
	}
	joined := strings.Join(lines, "\n")
	if !strings.Contains(joined, "line four") {
		t.Fatalf("TailLines() output missing latest line: %q", joined)
	}
}
