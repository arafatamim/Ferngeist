# Ferngeist Desktop Helper

`desktop-helper/` is the local companion daemon for Ferngeist. It exists to detect local ACP agents, launch them on demand, and present one authenticated ACP-compatible endpoint to the Android app.

If this directory starts feeling like a second product, it is going in the wrong direction.

## Layout

- `cmd/ferngeist`: user-facing CLI for daemon run, pairing, and paired-device management
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
go run .\cmd\ferngeist daemon run
```

Expose the helper to your phone over LAN during development:

```powershell
go run .\cmd\ferngeist daemon run --lan
go run .\cmd\ferngeist daemon status
go run .\cmd\ferngeist pair
```

Default listen address: `127.0.0.1:5788`
Default local admin address: `127.0.0.1:5789`

Useful env vars:

- `FERNGEIST_HELPER_LISTEN_ADDR`
- `FERNGEIST_HELPER_ADMIN_ADDR`
- `FERNGEIST_HELPER_ENABLE_LAN`
- `FERNGEIST_HELPER_STATE_DB`
- `FERNGEIST_HELPER_LOG_DIR`
- `FERNGEIST_HELPER_MANAGED_BIN_DIR`
- `FERNGEIST_HELPER_PUBLIC_BASE_URL`
- `FERNGEIST_HELPER_REGISTRY_URL`
- `FERNGEIST_HELPER_PAIRING_ARM_TTL_SECONDS`
- `FERNGEIST_HELPER_PAIRING_MAX_ATTEMPTS`
- `FERNGEIST_HELPER_PAIRING_LOCKOUT_SECONDS`
- `FERNGEIST_HELPER_PAIRING_START_REFILL_SECONDS`
- `FERNGEIST_HELPER_PAIRING_COMPLETE_REFILL_SECONDS`
- `FERNGEIST_HELPER_PAIRING_BURST_PER_IP`
- `FERNGEIST_HELPER_PAIRING_BURST_GLOBAL`
- `FERNGEIST_HELPER_CREDENTIAL_TTL_SECONDS`
- `FERNGEIST_HELPER_ALLOW_REMOTE_DIAGNOSTICS_EXPORT`
- `FERNGEIST_HELPER_ALLOW_REMOTE_RUNTIME_RESTART_ENV`
- `FERNGEIST_HELPER_REQUIRE_PROOF_OF_POSSESSION`
- `FERNGEIST_HELPER_ALLOW_LEGACY_BEARER_CREDENTIALS`

Optional real-agent smoke tests:

```powershell
$env:FERNGEIST_RUN_REAL_AGENT_TESTS="1"
go test ./internal/runtime -run TestOptionalInstalledOpenCodeACPSmoke -v
```

Pair a device from the machine hosting the daemon:

```powershell
go run .\cmd\ferngeist pair
go run .\cmd\ferngeist devices list
```

Pairing security behavior:

- New pairings require a recent local approval action (`ferngeist pair` or local admin pairing start).
- Public `POST /v1/pair/start` returns challenge metadata, but not the short code.
- Public `GET /v1/pair/status/{challengeId}` returns challenge state without exposing the code.
- Pairing endpoints are rate-limited and repeated wrong-code attempts trigger temporary lockout.
- JSON request bodies for control paths are size-limited.
- Paired devices now receive baseline `read` and `control` capabilities by default.
- Remote diagnostics export is disabled by default unless `FERNGEIST_HELPER_ALLOW_REMOTE_DIAGNOSTICS_EXPORT` is enabled.
- Runtime restart with environment overrides is disabled by default unless `FERNGEIST_HELPER_ALLOW_REMOTE_RUNTIME_RESTART_ENV` is enabled.
- Default paired credential lifetime is 7 days unless `FERNGEIST_HELPER_CREDENTIAL_TTL_SECONDS` overrides it.
- Paired devices can silently rotate credentials with `POST /v1/auth/refresh`; refresh returns a new token and invalidates the old one immediately.
- New pairings can bind the credential to a proof key, and helper API requests then require signed proof headers (`X-Ferngeist-Proof-*`).
- Public-mode helpers require proof-of-possession pairing by default and reject legacy bearer-only credentials unless `FERNGEIST_HELPER_ALLOW_LEGACY_BEARER_CREDENTIALS` is explicitly enabled.
- Helper-issued device credentials are hashed before being written to the helper state database.
- ACP runtime handoff returns a clean websocket URL/path; clients should send the returned runtime bearer token in the websocket `Authorization` header instead of query parameters.

## Portable Distribution

The helper is distributed as a standalone archive. No installer is required.

### Downloads

Find the archive for your platform in the [latest release](https://github.com/arafatamim/ferngeist/releases/latest):

| Platform | Archive |
|----------|---------|
| Windows x64 | `ferngeist_<version>_windows_amd64.zip` |
| Windows ARM64 | `ferngeist_<version>_windows_arm64.zip` |
| Linux x64 | `ferngeist_<version>_linux_amd64.tar.gz` |
| Linux ARM64 | `ferngeist_<version>_linux_arm64.tar.gz` |

Verify the archive before extracting:

```powershell
# Windows (PowerShell)
Get-FileHash -Algorithm SHA256 -Path .\ferngeist_<version>_windows_amd64.zip
# Compare against SHA256SUMS in the release
```

```bash
# Linux
sha256sum ferngeist_<version>_linux_amd64.tar.gz
# Compare against SHA256SUMS in the release
```

### Install

Extract the archive to a location of your choice, then register the daemon as a user service.

**Windows**

```powershell
Expand-Archive -Path .\ferngeist_<version>_windows_amd64.zip -DestinationPath .\FerngeistHelper
cd .\FerngeistHelper
.\ferngeist.exe daemon install
.\ferngeist.exe daemon status
```

**Linux**

```bash
tar -xzf ferngeist_<version>_linux_amd64.tar.gz
cd ferngeist_linux_amd64
./ferngeist daemon install
./ferngeist daemon status
```

The daemon listens on `127.0.0.1:5788` by default. On first run, pair your Ferngeist Android app:

```powershell
# Windows
.\ferngeist.exe pair

# Linux
./ferngeist pair
```

### Upgrade

Stop the daemon, replace the binary, then restart:

**Windows**

```powershell
.\ferngeist.exe daemon stop
# Extract the new archive, replacing the contents of the FerngeistHelper directory
.\ferngeist.exe daemon start
.\ferngeist.exe daemon status
```

**Linux**

```bash
./ferngeist daemon stop
# Extract the new archive, replacing the contents of the ferngeist_linux_amd64 directory
./ferngeist daemon start
./ferngeist daemon status
```

### Uninstall

**Windows**

```powershell
.\ferngeist.exe daemon uninstall --purge
# Remove the FerngeistHelper directory
Remove-Item -Recurse -Force .\FerngeistHelper
```

**Linux**

```bash
./ferngeist daemon uninstall --purge
# Remove the extracted directory
rm -rf ~/ferngeist_linux_amd64
```

### Running Without a Service

To run the daemon directly (for development or manual use):

```powershell
# Windows — foreground, Ctrl-C to stop
.\ferngeist.exe daemon run

# Expose on LAN (not recommended for production without firewall review)
.\ferngeist.exe daemon run --lan
```

```bash
# Linux — foreground, Ctrl-C to stop
./ferngeist daemon run

# Expose on LAN (not recommended for production without firewall review)
./ferngeist daemon run --lan
```
