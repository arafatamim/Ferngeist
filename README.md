# Ferngeist

Ferngeist is an Android client for [ACP](https://agentclientprotocol.com/)-compatible coding agents. It lets you save server endpoints, browse or create sessions, and continue streamed conversations from a native Jetpack Compose UI.

## What It Does

- Connects to remote ACP servers over `ws://` or `wss://`
- Lists existing sessions and creates new sessions with a configurable working directory
- Opens live chat sessions with streaming assistant output, tool activity, and cancel support
- Persists server and session metadata locally with Room
- Exposes connection diagnostics to help debug transport or RPC issues

## Screenshots
<img src="https://github.com/user-attachments/assets/a076834d-1973-4e2e-858f-8ac477e37177" alt="Server list" height="500">

<img src="https://github.com/user-attachments/assets/a806a4f7-3455-44ac-a6d4-1d4d3f8462e9" alt="Active session" height="500">

## Supported Agents
Any agent that implements the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/), including Codex CLI, Claude Code, Gemini CLI, GitHub Copilot CLI, and OpenCode. 
[List of agents that support ACP (non-exhaustive)](https://agentclientprotocol.com/get-started/agents).

## Tech Stack

- Kotlin 2.3
- Jetpack Compose + Material 3
- Hilt for dependency injection
- Room for local persistence
- Kotlin Coroutines and Flow
- KSP
- ACP Kotlin SDK

## Requirements

- Android Studio with current Android SDKs
- JDK 17
- Android device or emulator running Android 13+ (`minSdk = 33`)

## Getting Started

### Build

Run from the repository root:

```powershell
cmd /c gradlew.bat :app:assembleDebug
```

For a faster compile check while working on chat:

```powershell
cmd /c gradlew.bat :feature:chat:compileDebugKotlin
```

### Test

Run all debug unit tests:

```powershell
cmd /c gradlew.bat testDebugUnitTest
```

Run ACP bridge tests only:

```powershell
cmd /c gradlew.bat :acp-bridge:testDebugUnitTest
```

## Running The App

1. Build and install the debug app from Android Studio or with Gradle.
2. Launch Ferngeist.
3. Add an ACP server with:
   - `Name`
   - `Protocol` (`ws` or `wss`)
   - `Host` such as `localhost:8080`
   - `Working Directory`
   - optional bearer `Token`
4. Open the server, pick an existing session or create a new one, then start chatting.

## Project Structure

```text
app/                  Android entry point, navigation, theme, DI
acp-bridge/           ACP transport, connection manager, session bridge
core/common/          Shared UI helpers and common app utilities
core/model/           Shared domain models and repository interfaces
data/database/        Room database, DAOs, entities, repository impls
feature/serverlist/   Saved server management UI and state
feature/sessionlist/  Session listing, refresh, creation, diagnostics
feature/chat/         Streaming chat UI, reducers, markdown state
gradle/               Version catalog and wrapper configuration
docs/                 Design and implementation notes
```

Main production code lives under `src/main/kotlin`. Unit tests live under `src/test/kotlin`.

## Architecture Notes

Ferngeist is organized as a multi-module Android app. The `app` module wires navigation and dependencies, feature modules own UI and presentation logic, `acp-bridge` translates ACP events into app-level session updates, and shared state is stored through repository interfaces backed by Room.

The main user flow is:

1. Save a server configuration.
2. Connect and fetch sessions from the ACP endpoint.
3. Create or resume a session.
4. Stream chat updates into the Compose chat screen.

## Development Notes

- Kotlin style is idiomatic, with small composables and immutable UI state preferred.
- Dependency versions are centralized in `gradle/libs.versions.toml`.

## License

MIT. See [LICENSE](LICENSE).
