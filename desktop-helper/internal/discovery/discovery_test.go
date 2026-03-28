package discovery

import (
	"io"
	"log/slog"
	"testing"
)

func TestNewServiceHasDefaultSnapshot(t *testing.T) {
	service := New(slog.New(slog.NewTextHandler(io.Discard, nil)))

	snapshot := service.Snapshot()
	if snapshot.ServiceType != "_ferngeist-helper._tcp" {
		t.Fatalf("ServiceType = %q, want %q", snapshot.ServiceType, "_ferngeist-helper._tcp")
	}
	if snapshot.Active {
		t.Fatal("Active should be false before Start")
	}
}
