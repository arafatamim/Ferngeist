# Repository Guidelines

## Project Structure & Module Organization
Ferngeist is a multi-module Android app using Kotlin, Jetpack Compose, Hilt, and KSP.

- `app/`: entry point, navigation host, theme, and dependency wiring.
- `feature/serverlist`, `feature/sessionlist`, `feature/chat`: feature UI + presentation logic.
- `acp-bridge/`: ACP connection/session bridge and streaming integration.
- `core/model`, `core/common`, `core/designsystem`: shared models, utilities, and reusable UI.
- `data/database`, `data/datastore`: local persistence and storage.
- `gradle/libs.versions.toml`: central dependency and plugin versions.

Main code is under `src/main/kotlin`; unit tests are in `src/test/kotlin`.

## Build, Test, and Development Commands
Run commands from repository root with the Gradle wrapper:

- `cmd /c gradlew.bat :app:assembleGoogleDebug`: build full debug APK (or `:app:assembleFossDebug` for the F-Droid flavour).
- `cmd /c gradlew.bat :feature:chat:compileDebugKotlin`: fast compile check for chat changes.
- `cmd /c gradlew.bat :acp-bridge:testDebugUnitTest`: run ACP bridge unit tests.
- `cmd /c gradlew.bat :app:testFossDebugUnitTest :app:testGoogleDebugUnitTest`: run app unit tests (bare `testDebugUnitTest` skips the app module once flavours exist).

## Coding Style & Naming Conventions
- Use idiomatic Kotlin with 4-space indentation.
- Prefer small composables/functions and immutable UI state.
- Package names are lowercase and module-scoped (for example, `com.tamimarafat.ferngeist.feature.chat`).
- Naming: `UpperCamelCase` for classes/composables, `lowerCamelCase` for methods/vars, `SCREAMING_SNAKE_CASE` for constants.
- Keep comments brief and only for non-obvious logic.

## Testing Guidelines
- Framework: JUnit4 for unit tests.
- Add tests near changed logic, especially reducers, ACP parsing, and stream/chunk behavior.
- Test names should be descriptive, e.g., `appendChunk_afterInterleavedUpdate_keepsSingleMessage`.

## Commit & Pull Request Guidelines
- Commit format: `type(scope): concise imperative summary`.
- Example: `fix(chat): keep chunk appends in same assistant bubble`.
- PRs should include:
  - what changed and why,
  - risk/rollback notes,
  - test evidence (commands run),
  - screenshots/video for UI changes.

## Security & Configuration Tips
- Never commit secrets or tokens (`local.properties` stays local).
- Review `network_security_config.xml` changes carefully; cleartext settings can weaken transport security.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `arafatamim/Ferngeist`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the default label vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
