# Desktop Helper Architecture

This helper should stay narrow. Its job is:

- detect supported agents on the host
- pair trusted Ferngeist clients
- start and stop local agent runtimes
- expose one control API
- expose one ACP-compatible WebSocket bridge

It should not become a second product surface, a transcript store, or a general-purpose desktop platform.

## Main Flow

1. Ferngeist calls `POST /v1/pair/start` and `POST /v1/pair/complete`.
2. Ferngeist calls `GET /v1/agents` to see supported and installed agents.
3. Ferngeist calls `POST /v1/agents/{agentId}/start`.
4. Ferngeist calls `POST /v1/runtimes/{runtimeId}/connect`.
5. Ferngeist connects to `GET /v1/acp/{runtimeId}` with the runtime token.
6. The helper bridges that socket to either:
   - a local ACP WebSocket agent, or
   - a stdio ACP process wrapped by the helper

## Package Map

- `cmd/ferngeist`
  User-facing CLI and daemon entrypoint (`ferngeist daemon run`).
- `internal/api`
  HTTP routing, auth enforcement, and response shaping.
- `internal/catalog`
  Registry-backed agent inventory plus optional local adapter manifests for launch policy.
- `internal/runtime`
  Process lifecycle, readiness checks, restart policy, and runtime logs.
- `internal/gateway`
  Runtime-scoped ACP token issuance and validation.
- `internal/pairing`
  Pairing challenges and helper credential management.
- `internal/registry`
  ACP registry fetch/cache and registry-backed adapter enrichment.
- `internal/storage`
  SQLite persistence for pairings, settings, runtime metadata, and failures.
- `internal/discovery`
  Optional LAN advertisement via mDNS.
- `internal/logging`
  Structured helper log file management.
- `internal/config`
  Environment and persisted-settings config loading.

## Persistence

SQLite stores:

- paired devices
- helper settings
- runtime metadata
- runtime connect tokens
- recent failures

The helper does not persist prompts or transcript content.

## Managed Binary Acquisition

The helper should prefer one steady-state runtime path:

- detect an existing executable
- if missing, acquire a trusted current-platform binary into the helper-managed install root
- then launch that local executable normally

Package-manager-specific fallbacks can exist for compatibility, but they should not be the primary model.

## Boundaries

- The ACP registry decides what can be surfaced as inventory.
- Checked-in local manifests decide what the helper may launch directly.
- The Android client never sends arbitrary commands.
- The gateway should adapt transport, not invent a helper-specific chat protocol.
- Diagnostics should help debug the helper, not turn it into an admin suite.

## Current Non-Goals

- centralized relay
- cloud account system
- transcript storage
- arbitrary shell execution
- rich desktop UI
