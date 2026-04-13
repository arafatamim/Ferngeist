# Ferngeist

Ferngeist is an Android client for [ACP](https://agentclientprotocol.com/)-compatible coding agents. It includes a desktop companion daemon that auto-detects local agents and exposes a single authenticated endpoint to the app.

## Screenshots
<img height="500" alt="01-agents-list" src="https://github.com/user-attachments/assets/ea308065-dd67-4dac-af85-978b313642d9" />
<img height="500" alt="03-chat" src="https://github.com/user-attachments/assets/e6317469-0b97-42f4-aab4-44aee6acefee" />

## Usage
Two ways to add ACP agents:
### 1. Desktop companion (optional, for local agents) — available for Windows and Linux:

1. Download the [latest release](https://github.com/arafatamim/ferngeist/releases/latest) for your platform.
2. Extract and run:
   ```powershell
   .\ferngeist.exe daemon install  # Windows: run as Administrator
   .\ferngeist.exe pair            # displays a pairing code
   ```
3. Open Ferngeist on your Android device, tap **Add server**, and enter the tunnel URL with the pairing code.

#### Exposing via tunnel

The daemon listens on `127.0.0.1:5788`. To reach it from a mobile device on a different network:

**ngrok:**
```powershell
ngrok http 5788
# Note the HTTPS URL (e.g. https://xxxx.ngrok.io)
.\ferngeist.exe daemon install --public-url https://xxxx.ngrok.io
```

**Cloudflare Tunnel:**
```powershell
cloudflared tunnel --url http://localhost:5788
# Note the URL (e.g. https://xxxx.trycloudflare.com)
.\ferngeist.exe daemon install --public-url https://xxxx.trycloudflare.com
```
For a persistent tunnel without a terminal, see [Cloudflare Docs](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/).

Then pair and add the tunnel URL as the server host in Ferngeist:

```powershell
.\ferngeist.exe pair
```

The companion can also be self-hosted on a VPS (Linux amd64 binaries are available in each release).

### 2. Add an ACP server manually
Most ACP agents only support `stdio` transport — wrap with a WebSocket bridge first. Check the agent's docs for the correct flags to start in ACP mode.

Example:
```powershell
npx -y stdio-to-ws "npx @qwen-code/qwen-code@latest --acp" --port 8769
```

Then add `ws://<your-pc-ip>:8769` in Ferngeist as the server host.

## Supported Agents

Any agent implementing [ACP](https://agentclientprotocol.com/), including Codex CLI, Claude Code, Gemini CLI, GitHub Copilot CLI, and OpenCode. See the [full list](https://agentclientprotocol.com/get-started/agents).

## Tech Stack

- Kotlin 2.3 / Jetpack Compose + Material 3
- Hilt / Room / Kotlin Coroutines and Flow / KSP
- ACP Kotlin SDK

Requires Android 13+ (`minSdk = 33`).

## Build

```powershell
cmd /c gradlew.bat :app:assembleDebug
```

## Project Structure

```
app/                  Android entry point, navigation, theme, DI
desktop-helper/       Local companion daemon (Go) for agent discovery and pairing
acp-bridge/           ACP transport, connection manager, session bridge
core/common/          Shared UI helpers and utilities
core/model/           Domain models and repository interfaces
data/database/        Room database, DAOs, entities
feature/serverlist/   Saved server management UI
feature/sessionlist/  Session listing and creation
feature/chat/         Streaming chat UI, reducers, markdown state
gradle/               Version catalog
```

## Reporting Issues
Bug reports and feature requests welcome on [GitHub Issues](https://github.com/arafatamim/ferngeist/issues).

## Further Reading
- [Introduction to Agent Client Protocol](https://agentclientprotocol.com/get-started/introduction)
- [Jetpack Compose](https://developer.android.com/compose)

## License

MIT. See [LICENSE](LICENSE).
