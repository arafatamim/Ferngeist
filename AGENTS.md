# Repository Guidelines

## Project Overview

Ferngeist is a multi-module Android client for the **Agent Client Protocol (ACP)**. It launches and connects to coding agents, renders their streaming turns as chat, and exposes session/agent management, push notifications, and an optional remote gateway for pairing.

Kotlin + Jetpack Compose + Hilt + KSP. `minSdk 30`, `targetSdk 37`, JVM target 17.

## Architecture & Data Flow

Nine Gradle modules (see `settings.gradle.kts` — the authoritative list; `core/designsystem` and `data/datastore` are **not** modules, despite older docs):

- `app` — `FerngeistApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`), theme, navigation host, workspace panes, FCM + foreground service. Flavour-specific sources live in `src/google/kotlin` and `src/foss/kotlin` (e.g. `FcmTokenBootstrap.kt`, `AddGatewayQrScanner.kt`).
- `acp-bridge` — the ACP seam. `connection/` owns transport, auth headers, capability negotiation, and the connect orchestrator; `session/` owns `SessionBridge`, `SessionMessageReducer`, `SessionRuntime`; `hub/` multiplexes one connection across concurrent chats; `facade/` is what the chat feature sees; `launcher/` is the server list's launch-and-auth seam.
- `gateway-client` — HTTP/WebSocket client for the optional remote gateway (`GatewayRepositoryImpl`), launch/lease, proof auth, credential refresh.
- `core/model` — pure data types (`ChatMessage`, `AssistantSegment`, `ServerConfig`, `GatewaySource`) plus repository *interfaces*. No Android UI deps.
- `core/common` — `MviViewModel<State, Intent, Effect>` (the shared ViewModel base: `MutableStateFlow` for state, `Channel(BUFFERED)` for effects) and shared UI atoms (`EdgeFade`, `ConnectionStatusPill`, `WindowSizeSeam`).
- `data/database` — Room DB, DAOs, entities, migrations, `CredentialEncryptor`.
- `feature/serverlist`, `feature/sessionlist`, `feature/chat` — UI + presentation logic. ViewModels extend `MviViewModel`.

**Event path:** ACP `session/update` → `AcpSessionUpdateMapper` → `SessionMessageReducer` (appends to segments) → `SessionBridge` emits `AppSessionEvent` on a `SharedFlow` → `ChatViewModel` reduces into UI state → Compose.

### Markdown rendering (easy to break — read this)

Assistant turns are stored as **one segment per chunk** so the reducer never copies an accumulating bubble (that O(n²) is what made large transcripts slow). Two consumers need the opposite shape:

- `MessageRuns.kt` — `messageRuns()` / `thoughtRuns()` / `displayBlocks()` fold adjacent same-kind segments into one `SegmentRun`, keyed on the **first** segment id so the cache key and Compose node identity survive a new chunk. `MESSAGE` and `THOUGHT` runs stay separate documents; `TOOL_CALL`/`PLAN` render as `SegmentBlock.Single`.
- `MarkdownStateStore.kt` — parses one markdown document **per run** with an incremental engine, feeding only the new tail of each run. No pacing or animation state.

If these two disagree on what a run is, you get the "renders as the first two characters, then pops in whole" bug. Change both together or neither. Markdown comes from `com.adamglin.compose.markdown` (core engine as a dependency; the Compose renderer vendored under `feature/chat/.../markdown/`).

The streaming reveal (what is visible per frame, the leading-edge fade, height growth, scroll-follow) lives in the composition — `markdown/MarkdownReveal.kt`, see `docs/adr/0001`. `revealLength()` must count exactly what `MarkdownBlocks` draws.

## Key Directories

| Path | Purpose |
|---|---|
| `acp-bridge/src/main/kotlin/.../connection/` | transport, auth, capability, connect orchestration |
| `acp-bridge/src/main/kotlin/.../session/` | bridge, reducer, state engine, runtime |
| `acp-bridge/src/main/kotlin/.../launcher/` | server list's launch, gateway handoff, auth |
| `feature/chat/src/main/kotlin/.../ui/` | `ChatScreen`, `MessageBubble`, composer, scroll policy |
| `feature/chat/src/test/kotlin/.../` | run/segment, scroll policy, view model tests |
| `data/database/` | Room schema + `FerngeistMigrations.kt` |
| `core/common/` | `MviViewModel`, shared UI, window-size seam |

`src/main/kotlin` per module; unit tests in `src/test/kotlin`.

## Development Commands

Run from the repo root. On Windows use `cmd /c gradlew.bat <tasks>`; without `JAVA_HOME` set, invoke the wrapper jar directly:

```bash
cmd /c gradlew.bat :app:assembleGoogleDebug          # debug APK (or :app:assembleFossDebug)
cmd /c gradlew.bat :feature:chat:compileDebugKotlin    # fast compile check for chat changes
cmd /c gradlew.bat :feature:chat:testDebugUnitTest     # module unit tests
cmd /c gradlew.bat :acp-bridge:testDebugUnitTest
cmd /c gradlew.bat :app:testFossDebugUnitTest :app:testGoogleDebugUnitTest
cmd /c gradlew.bat detekt ktlintCheck
cmd /c gradlew.bat :app:lintFossDebug
```

**Windows caveat:** chaining the app-level tasks into one invocation hangs the Gradle daemon. Run `:app:test*`, `:app:lint*`, and `:app:assemble*` as separate invocations.

## Code Conventions & Patterns

- Idiomatic Kotlin, 4-space indent, 120-col (` .editorconfig`).
- `UpperCamelCase` types/composables, `lowerCamelCase` functions, `SCREAMING_SNAKE_CASE` constants. Packages are lowercase and module-scoped.
- `allWarningsAsErrors = true` — deprecations are compile errors. `warningsAsErrors` is on for Android lint too, so a lint warning fails the build.
- ViewModels: extend `MviViewModel`, `dispatch(intent)`, mutate via `updateState`, one-shot effects via `emitEffect`.
- DI: Hilt, all modules in `SingletonComponent`. Module files are named `*Module.kt` and live next to the type they bind.
- Comments only for non-obvious reasoning — usually *why* a constraint exists. Match that density; don't narrate the code.
- **Never** delete untracked files or the user's own uncommitted work without asking.

### Room/KSP gotcha

Hilt's processor bundles an older `kotlin-metadata-jvm` that cannot read newer Kotlin metadata, breaking `hiltJavaCompileDebug` with `Provided Metadata instance has version X, while maximum supported version is Y`. The root `build.gradle.kts` forces `kotlin-metadata-jvm` onto every kapt/KSP/annotation-processor configuration — update that version when the error reappears.

## Testing & QA

JUnit4. 61 test files. `mockk` + `kotlinx-coroutines-test` + Turbine for flows; Robolectric where the JVM needs Room (see `data/database` tests). No Compose UI tests found — confirm before assuming.

Test names describe behaviour, e.g. `appendChunk_afterInterleavedUpdate_keepsSingleMessage`. Add tests next to changed logic — reducers, ACP parsing, segment runs, stream/chunk handling.

**Verify by running the thing, not by running tests.** After non-trivial work, exercise the changed path and report the observed result. For UI changes: build clean, then report and let the maintainer verify on their own device — **do not drive an emulator or take screenshots unless explicitly asked.** Report unverified work as unverified.

Log reading on Windows: PowerShell-written Gradle logs carry a BOM. Decode trying `utf-8-sig`, `utf-16-le`, `cp1252`. A `gate=0` is not proof — classify the output (`UP-TO-DATE` means the task never ran; `compileDebugUnitTestKotlin FAILED` is a compile error, not a test failure).

## Runtime & Tooling

- Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00, Room 2.8.5, Ktor 3.6.0, ACP SDK 0.33.0.
- `ksp.useKSP2=true`, `android.nonTransitiveRClass=true`, `org.gradle.configuration-cache=true`. `org.gradle.parallel` is off.
- Versions are centralised in `gradle/libs.versions.toml` — change them there, never inline.
- Android SDK path comes from `local.properties` (`sdk.dir`), which stays uncommitted.

## Communication

- **Verification is the maintainer's job.** Do not boot an emulator, install an APK, drive the UI, take screenshots, or do pixel scans unless explicitly asked. Stop at: edit → compile/lint/detekt/unit-test clean → report the change → ask "Ready to commit?". The maintainer verifies on their own device and reports back.
- **Lead with the answer.** Conclusion first, then evidence. No narrated steps, no recap of what you just did, no tables restating a one-line result.
- **No walls of text.** A few lines. Bullets over paragraphs. If the explanation is longer than the change, cut it.
- **Say what is unverified.** Never imply a fix is confirmed when the evidence is a unit test or a plan. If a claim rests on inference, mark it.
- **Don't blur scope.** If work on one thing is done, don't let the report imply other things shipped too.


## Commit & PR Guidelines

`type(scope): concise imperative summary` — e.g. `fix(chat): keep chunk appends in same assistant bubble`.
Commits are **GPG-signed** (`git commit -S`). Never commit without explicit approval; never push without being asked.

PRs state what changed and why, risk/rollback, test evidence (commands run), and screenshots for UI changes.

Never commit: `local.properties`, secrets, `.codebase-memory/`, `TASKLIST.md`, `*.log`, `logs/`, `*.db`, `docs/`, `.superpowers/`, `.worktrees/`, `.pi-lens/`, scratch dirs.

## Security

Never commit tokens or secrets. `CredentialEncryptor` guards stored credentials — keep it in the path. Review `network_security_config.xml` carefully; cleartext settings weaken transport.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `arafatamim/Ferngeist`, operated via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:46cd31e7 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/core-concepts/sync-concepts.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
