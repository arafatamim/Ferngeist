# Ferngeist Desktop Helper

`desktop-helper/` is the local companion daemon for Ferngeist. It exists to detect local ACP agents, launch them on demand, and present one authenticated ACP-compatible endpoint to the Android app.

If this directory starts feeling like a second product, it is going in the wrong direction.

## Read This First

- [ARCHITECTURE.md](/D:/Projects/Programming/Ferngeist/desktop-helper/ARCHITECTURE.md): what the helper is responsible for
- [DesktopHelperProtocol.md](/D:/Projects/Programming/Ferngeist/docs/DesktopHelperProtocol.md): HTTP and ACP contract
- [DESKTOP_HELPER_PLAN.md](/D:/Projects/Programming/Ferngeist/DESKTOP_HELPER_PLAN.md): staged plan and boundaries

## Layout

- `cmd/helperd`: daemon entrypoint
- `cmd/mock-stdio-agent`: local stdio ACP test agent
- `internal/api`: HTTP API and auth wiring
- `internal/catalog`: manifest loading, supported agents, detection, validation
- `internal/runtime`: process supervision and transport bridging
- `internal/gateway`: runtime token issuance and validation
- `internal/pairing`: pairing and helper credentials
- `internal/registry`: ACP registry fetch/cache
- `internal/storage`: SQLite persistence
- `internal/discovery`: mDNS LAN advertising
- `internal/logging`: helper log files
- `internal/config`: env and persisted settings

## Current Scope

- helper status and diagnostics
- pairing and helper-issued device credentials
- LAN discovery via mDNS
- supported-agent inventory backed by curated manifests and the ACP registry
- process-backed runtime supervision
- ACP-over-WebSocket bridge to helper-managed stdio runtimes
- SQLite persistence for helper state

## Agent Manifests

The visible agent inventory now comes from the ACP registry. Checked-in manifests under [`internal/catalog/manifests`](/D:/Projects/Programming/Ferngeist/desktop-helper/internal/catalog/manifests) are only local helper adapters that add launch policy, readiness, and security rules for specific agents.

That means:

- registry agents can appear even if Ferngeist has no local adapter for them yet
- registry-only agents are visible but not helper-launchable by default
- local manifests no longer define the entire visible agent list

## Managed Installs

When a trusted local adapter exists and the ACP registry exposes a current-platform binary archive, the helper can auto-acquire that binary into a helper-managed install directory before launch.

Default managed install roots:

- Windows: `%LocalAppData%\FerngeistHelper\bin`
- macOS: `~/Library/Application Support/Ferngeist Helper/bin`
- Linux: `~/.local/share/ferngeist-helper/bin`

## Running It

From [desktop-helper](/D:/Projects/Programming/Ferngeist/desktop-helper):

```powershell
go build -o .\bin\mock-stdio-agent.exe .\cmd\mock-stdio-agent
go run .\cmd\helperd
```

Default listen address: `127.0.0.1:5788`

Useful env vars:

- `FERNGEIST_HELPER_LISTEN_ADDR`
- `FERNGEIST_HELPER_ENABLE_LAN`
- `FERNGEIST_HELPER_STATE_DB`
- `FERNGEIST_HELPER_LOG_DIR`
- `FERNGEIST_HELPER_MANAGED_BIN_DIR`
- `FERNGEIST_HELPER_PUBLIC_BASE_URL`
- `FERNGEIST_HELPER_REGISTRY_URL`

Optional real-agent smoke tests:

```powershell
$env:FERNGEIST_RUN_REAL_AGENT_TESTS="1"
go test ./internal/runtime -run TestOptionalInstalledOpenCodeACPSmoke -v
```

## Maintenance Rule

Prefer deleting helper features over adding new helper surfaces. The daemon should stay small enough that one person can still understand the full request flow without a week of archaeology.
