package registry

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestClientFetchesAndNormalizesRegistrySnapshot(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		platformKey := currentPlatformKey()
		command := "./codex-acp"
		if strings.HasPrefix(platformKey, "windows-") {
			command = "./codex-acp.exe"
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"version":"1.0.0",
			"agents":[
				{
					"id":"codex-acp",
					"name":"Codex CLI",
					"version":"0.10.0",
					"repository":"https://github.com/zed-industries/codex-acp",
					"distribution":{
						"npx":{"package":"@zed-industries/codex-acp@0.10.0"},
						"binary":{"` + platformKey + `":{"cmd":"` + command + `"}}
					}
				}
			]
		}`))
	}))
	defer server.Close()

	client := New(server.URL, time.Hour)
	snapshot, err := client.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot() error = %v", err)
	}

	if snapshot.Version != "1.0.0" {
		t.Fatalf("Version = %q, want %q", snapshot.Version, "1.0.0")
	}
	entry, ok := snapshot.Agents["codex-acp"]
	if !ok {
		t.Fatal("expected codex-acp entry")
	}
	if entry.Name != "Codex CLI" {
		t.Fatalf("Name = %q, want %q", entry.Name, "Codex CLI")
	}
	if len(entry.DistributionKinds) != 2 {
		t.Fatalf("len(DistributionKinds) = %d, want 2", len(entry.DistributionKinds))
	}
	if entry.DistributionKinds[0] != "binary" || entry.DistributionKinds[1] != "npx" {
		t.Fatalf("DistributionKinds = %v", entry.DistributionKinds)
	}
	if entry.CurrentBinary == nil {
		t.Fatal("CurrentBinary should not be nil for current platform")
	}
	if entry.CurrentBinary.CommandName != "codex-acp" {
		t.Fatalf("CurrentBinary.CommandName = %q, want %q", entry.CurrentBinary.CommandName, "codex-acp")
	}
	if entry.NpxPackage != "@zed-industries/codex-acp@0.10.0" {
		t.Fatalf("NpxPackage = %q, want %q", entry.NpxPackage, "@zed-industries/codex-acp@0.10.0")
	}
	if len(entry.NpxArgs) != 0 {
		t.Fatalf("NpxArgs = %v, want []", entry.NpxArgs)
	}
}

func TestClientParsesNpxDistributionArgs(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"version":"1.0.0",
			"agents":[
				{
					"id":"cline",
					"name":"Cline",
					"version":"2.11.0",
					"distribution":{
						"npx":{"package":"cline@2.11.0","args":["--acp"]}
					}
				}
			]
		}`))
	}))
	defer server.Close()

	client := New(server.URL, time.Hour)
	snapshot, err := client.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot() error = %v", err)
	}

	entry, ok := snapshot.Agents["cline"]
	if !ok {
		t.Fatal("expected cline entry")
	}
	if entry.NpxPackage != "cline@2.11.0" {
		t.Fatalf("NpxPackage = %q, want %q", entry.NpxPackage, "cline@2.11.0")
	}
	if len(entry.NpxArgs) != 1 || entry.NpxArgs[0] != "--acp" {
		t.Fatalf("NpxArgs = %v, want [--acp]", entry.NpxArgs)
	}
}

func TestClientParsesUvxDistribution(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"version":"1.0.0",
			"agents":[
				{
					"id":"fast-agent",
					"name":"fast-agent",
					"version":"0.6.9",
					"distribution":{
						"uvx":{"package":"fast-agent-acp==0.6.9","args":["-x"]}
					}
				}
			]
		}`))
	}))
	defer server.Close()

	client := New(server.URL, time.Hour)
	snapshot, err := client.Snapshot(context.Background())
	if err != nil {
		t.Fatalf("Snapshot() error = %v", err)
	}

	entry, ok := snapshot.Agents["fast-agent"]
	if !ok {
		t.Fatal("expected fast-agent entry")
	}
	if entry.UvxPackage != "fast-agent-acp==0.6.9" {
		t.Fatalf("UvxPackage = %q", entry.UvxPackage)
	}
	if len(entry.UvxArgs) != 1 || entry.UvxArgs[0] != "-x" {
		t.Fatalf("UvxArgs = %v, want [-x]", entry.UvxArgs)
	}
}
