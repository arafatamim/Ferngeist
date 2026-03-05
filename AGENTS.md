# Repository Guidelines

## Project Structure & Module Organization
Ferngeist is a multi-module Android project (Kotlin + Compose + Hilt + KSP).

- `app/`: Android entry module, navigation host, app theme, DI wiring.
- `feature/serverlist`, `feature/sessionlist`, `feature/chat`: UI features and presentation logic.
- `acp-bridge/`: ACP transport/session integration and connection handling.
- `core/model`, `core/common`, `core/designsystem`: shared models, utilities, and UI primitives.
- `data/database`, `data/datastore`: local persistence layers.
- `gradle/libs.versions.toml`: dependency and plugin version catalog.

Code lives under `src/main/kotlin`; unit tests under `src/test/kotlin`.

## Build, Test, and Development Commands
Use Gradle wrapper from repo root:

- `cmd /c gradlew.bat :app:assembleDebug`  
  Builds a debug APK for full app validation.
- `cmd /c gradlew.bat :feature:chat:compileDebugKotlin`  
  Fast compile check for chat module edits.
- `cmd /c gradlew.bat :acp-bridge:testDebugUnitTest`  
  Runs ACP bridge unit tests.
- `cmd /c gradlew.bat testDebugUnitTest`  
  Runs all debug unit tests across modules.

## Coding Style & Naming Conventions
- Kotlin style: 4-space indentation, idiomatic Kotlin, small composables/functions.
- Keep package names lowercase and module-scoped (for example, `com.tamimarafat.ferngeist.feature.chat`).
- Use `UpperCamelCase` for classes/composables, `lowerCamelCase` for methods/vars, `SCREAMING_SNAKE_CASE` for constants.
- Prefer immutable UI state + reducer/event patterns already used in `feature/chat`.
- Keep comments brief and only for non-obvious logic.

## Testing Guidelines
- Frameworks: JUnit4 (unit tests), AndroidX test/Compose test dependencies in app module.
- Add tests next to changed logic (especially reducers, ACP `session/update` parsing, and streaming edge cases).
- Test naming pattern: `method_or_condition_expectedResult` (example: `appendChunk_afterInterleavedUpdate_keepsSingleMessage`).

## Commit & Pull Request Guidelines
Use this convention:

- Commit format: `type(scope): concise imperative summary`  
  Example: `fix(chat): keep chunk appends in same assistant bubble`.
- PRs should include:
  - What changed and why
  - Risk/rollback notes
  - Test evidence (commands run)
  - Screenshots/video for UI changes (chat, toolbar, dialogs)

## Security & Configuration Tips
- Do not commit secrets/tokens (`local.properties` and runtime auth inputs stay local).
- Review `network_security_config.xml` changes carefully; cleartext settings affect transport security.
