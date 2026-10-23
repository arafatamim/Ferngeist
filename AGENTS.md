# Ferngeist

Kotlin + Jetpack Compose Android client for the Agent Client Protocol (ACP).

> Your work is judged by the **Code Commander** — a veteran engineer who has shipped
> systems older than most frameworks. Ruthlessly strict about craft, and still remembers
> what a first 2 AM production deploy felt like. Mention him (directly or metaphorically)
> after code edits.

---

## Our Core Principles

> **Override:** if the user writes `I as code commander ask you to …`, the guardrails below
> are bypassed for that single turn. `$cc` expands to that exact phrase.

### Our way of thinking

- **The index comes first.** `codebase-memory` is the first tool, not an optional one: `search_graph` the concept before any grep/glob/read sweep — see [Codebase intelligence](#codebase-intelligence). A grep-first sweep is how you miss the helper that already exists.
- **Do the task in the prompt.** It is the whole scope. No tangents, no unsolicited refactors, no "while I'm here" cleanups, no exploring code the task doesn't touch. Investigate only what the task needs to be done correctly.
- Think in atomic steps, then execute them.
- Read the existing code for full context before editing.
- Surface assumptions. Name competing interpretations instead of silently picking one.
- Simplicity first: the minimum code that solves the task, no speculative features.
- Surgical changes: touch only what the task requires, match the surrounding style, remove only the orphans your change created.
- Turn the task into verifiable criteria, then execute.

### The smallest complete change

Laziness applies to the solution, never to the change. Take the first rung that holds:

1. Does it need to exist? Cut the feature, option or flexibility nobody asked for, and name the cut in one line.
2. Already in this codebase — a helper, component, service, pattern? Use it the way the surrounding code does.
3. Standard library or platform feature? Use it, unless the project has its own; a house component beats a native widget.
4. An installed dependency? Use it; never add one for a few lines.
5. One line a reader gets at a glance.
6. Otherwise, the minimum that works.

Keep values in the form the platform gives you: no wrapper, conversion, option, config, or "for later" code. Deletion beats addition. A shortcut with a known limit is worth one line: `shortcut: <the limit>, <when to upgrade>`.

A bug fix lands once in the shared code: find every caller before editing, never patch each call site. Never cut validation at trust boundaries, error handling that prevents data loss, security, accessibility, calibration real hardware needs, or anything the user asked for.

Adapted from [ponytail](https://github.com/DietrichGebert/ponytail).

### Our core values

- **Concision**: small files, small functions.
- **Organization**: layering, modularization, consistency.
- **Clarity**: a human should understand it in one pass.
- **Standards**: type safety, consistent naming, exactly one way to lint and build.

## Coding Standards

### Layering & modularization

- Everything has one place and lives in it. Push low-level helpers into `core/model`, `core/common`, `acp-bridge`, or the module's base package.
- Compose the blocks that already exist before writing a new one. Check `core/common` and `core/model` first.
- Layer 0 depends on nothing; layer N may depend only on layers ≤ N-1. Reduce a file's layer, never raise it.
- Directory hygiene: ≤ 7 files. Past that, split by domain. `feature/chat` predates this rule with 84 files — new code goes into subpackages by domain, not into the flat pile.
- Report boundary violations instead of quietly working around them.

### Small functions

- Function body ≤ 60 lines (matches detekt `LongMethod`); aim for 20-50.
- Inline one-expression functions called exactly once — a name that carries no meaning is indirection.
- Split on logical units of abstraction, not on line count.
- Exempt: `@Composable` functions, long by structure (parameter list + UI tree) and already excluded from `LongMethod` in `detekt.yml`.

### Clean code

- At most 2 nested levels of `if` / `when` / `try`. Deeper means extract.
- Prefer no `try` / `catch`: let it bubble to the boundary that can act on it. No chains of fallbacks — they hide which primary path failed.
- Declarative and functional first (`when`, `map`, `fold`) over imperative mutation.
- Types at every boundary. `Any`, `Any?`, and raw collections are not types.
- Naming: `UpperCamelCase` types and composables, `lowerCamelCase` functions, `SCREAMING_SNAKE_CASE` constants; packages lowercase and module-scoped.

### Comments

- Never delete an existing comment unless asked; shorten or reword instead.
- Comments explain **why**, usually a constraint that isn't visible in the code. Never narrate what the code already says.
- Keep the existing density — deliberately sparse here, not zero.

## Guardrails

> Non-negotiable. Only the Code Commander phrase bypasses them, for one turn.

### Codebase intelligence

- **`codebase-memory` is the first tool, not an optional one.** When the MCP server is connected, reach for it before any grep/read sweep: `search_graph` for symbols, `trace_path` for callers/callees and blast radius, `get_code_snippet` to read a declaration, `get_file_outline` for a file's structure, `query_graph` for multi-hop or aggregation, `get_architecture` for a module overview.
- **Keep the index current.** After adding, renaming or deleting files, re-index (`index_repository`) before trusting a lookup; use `check_index_coverage` / `index_status` to confirm the paths or modules you care about are actually indexed. An index that lags your edits is worse than no index.
- Fall back to `read`/`grep`/`glob` for literals, for files the index does not cover, and whenever a cited path does not exist. An index hit is a lead, not proof — check the cited path before acting on it.

### Verification gate

- Gate before reporting: `./gradlew detekt ktlintCheck` plus the touched module's `testDebugUnitTest`.
- **Fix findings; never silence them.** Applies to detekt, ktlint, and Android lint alike: no `@Suppress`, `tools:ignore`, or `//noinspection`; no `detekt-baseline.xml`; no raising a threshold, setting `active: false`, or adding an exclusion glob; no deleting the offending call to dodge the rule. If a finding is genuinely wrong, say why in your report and let the maintainer decide. Silencing a finding to get a green build is a failed task, not a passing one.
- Device verification is off-limits unless specifically asked — no emulator, APK installs, UI driving, screenshots, or pixel scans.
- `detekt` runs in `--analysis-mode full`; findings in `src/test` can be artefacts of the main-only classpath, so don't treat a thin report as a clean bill of health.

### Shell & tooling

- Exit code 0 is not proof: classify output. `UP-TO-DATE` means the task never ran; `compileDebugUnitTestKotlin FAILED` is a compile error, not a test failure.
- Avoid `rg -v` without explicit input (recurses the tree and inverts = output explosion). Use `grep -v`.

### Git

- Read-only and allowed: `git status`, `git diff`, `git log`, `git branch`, `git show`.
- Never commit without explicit approval; never push, merge, rebase, or reset unless asked. Commits are GPG-signed (`git commit -S`).
- Never delete untracked files or the maintainer's uncommitted work.

### Secrets

- Never `echo $VAR` or `printenv VAR`. Check existence with `[[ -n "$VAR" ]] && echo set || echo unset`.
- No credentials, tokens, or API keys in code, scripts, logs, or commit messages.

### Destructive operations

Confirm first for: `rm -rf` outside `/tmp` and scratch dirs, any Room schema drop or truncate, global package installs, and anything rewriting git history.

### Hands-off directories

Do not create or edit without being asked: `.idea/`, `.vscode/`, `.claude/`, `.cursor/`, `.gradle/`, `build/`.

> ^^^ Everything above is the harness doctrine. Everything below is this repo's context. ^^^

---

## Project Overview

Ferngeist is a multi-module Android client for the **Agent Client Protocol (ACP)**. It launches and connects to coding agents, renders their streaming turns as chat, and exposes session/agent management, push notifications, and an optional remote gateway for pairing.

Kotlin + Jetpack Compose + Hilt + KSP. `minSdk 30`, `targetSdk 37`, JVM target 17.

## Architecture & Data Flow

Eleven Gradle modules (see `settings.gradle.kts` — the authoritative list):

- `app` — `FerngeistApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`), theme, navigation host, workspace panes, FCM + foreground service. Flavour-specific sources live in `src/google/kotlin` and `src/foss/kotlin` (e.g. `FcmTokenBootstrap.kt`, `AddGatewayQrScanner.kt`).
- `acp-bridge` — the ACP seam. `connection/` owns transport, auth headers, capability negotiation, and the connect orchestrator; `session/` owns `SessionBridge`, `SessionMessageReducer`, `SessionRuntime`; `hub/` multiplexes one connection across concurrent chats; `facade/` is what the chat feature sees; `launcher/` is the server list's launch-and-auth seam.
- `gateway-client` — HTTP/WebSocket client for the optional remote gateway (`GatewayRepositoryImpl`), launch/lease, proof auth, credential refresh.
- `core/model` — pure data types (`ChatMessage`, `AssistantSegment`, `ServerConfig`, `GatewaySource`) plus repository *interfaces*. No Android UI deps.
- `core/common` — `MviViewModel<State, Intent, Effect>` (the shared ViewModel base: `MutableStateFlow` for state, `Channel(BUFFERED)` for effects) and shared UI atoms (`EdgeFade`, `ConnectionStatusPill`, `WindowSizeSeam`).
- `data/database` — Room DB, DAOs, entities, migrations, `CredentialEncryptor`.
- `feature/serverlist`, `feature/sessionlist`, `feature/chat` — UI + presentation logic. ViewModels extend `MviViewModel`.
- `e2e` — `com.android.test` harness against the app; not shipped.
- `konsist` — JVM-only Konsist tests: module layering plus Kotlin conventions (naming, wildcard imports, 60-line functions, data-class shape). No production code; run `./gradlew :konsist:test`.

**Event path:** ACP `session/update` → `AcpSessionUpdateMapper` → `SessionMessageReducer` (appends to segments) → `SessionBridge` emits `AppSessionEvent` on a `SharedFlow` → `ChatViewModel` reduces into UI state → Compose.

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
| `konsist/src/test/kotlin/` | architecture + Kotlin convention rules (Konsist) |

`src/main/kotlin` per module; unit tests in `src/test/kotlin`.

## Development Commands

Run from the repo root.

**Linux / macOS:**

```bash
./gradlew :app:assembleGoogleDebug                  # debug APK (or :app:assembleFossDebug)
./gradlew :feature:chat:compileDebugKotlin            # fast compile check for chat changes
./gradlew :feature:chat:testDebugUnitTest             # module unit tests
./gradlew :acp-bridge:testDebugUnitTest
./gradlew :app:testFossDebugUnitTest :app:testGoogleDebugUnitTest
./gradlew detekt ktlintCheck
./gradlew :konsist:test                              # architecture tests over every module
./gradlew :app:lintFossDebug
```

- Wrapper permissions are tracked (`100755`), but a clone that loses the bit needs `chmod +x gradlew` — CI does this explicitly.
- No `JAVA_HOME` needed when `java` is on `PATH`; Gradle 9 requires JDK 17+.
- Android SDK comes from `local.properties` (`sdk.dir=`). If that file is absent, create it or export `ANDROID_HOME`.
- Gradle daemon and configuration cache persist across runs; `--no-configuration-cache` when a build script change isn't picked up.

## Code Conventions & Patterns

- Idiomatic Kotlin, 4-space indent, 120-col (`.editorconfig`).
- `allWarningsAsErrors = true` — deprecations are compile errors. `warningsAsErrors` is on for Android lint too, so a lint warning fails the build.
- ViewModels: extend `MviViewModel`, `dispatch(intent)`, mutate via `updateState`, one-shot effects via `emitEffect`.
- DI: Hilt, all modules in `SingletonComponent`. Module files are named `*Module.kt` and live next to the type they bind.

### Room/KSP gotcha

Hilt's processor bundles an older `kotlin-metadata-jvm` that cannot read newer Kotlin metadata, breaking `hiltJavaCompileDebug` with `Provided Metadata instance has version X, while maximum supported version is Y`. The root `build.gradle.kts` forces `kotlin-metadata-jvm` onto every kapt/KSP/annotation-processor configuration — update that version when the error reappears.

## Testing & QA

JUnit4. 110 `*Test.kt` files. `mockk` + `kotlinx-coroutines-test` + Turbine for flows; Robolectric where the JVM needs Room (see `data/database` tests). No Compose UI tests found — confirm before assuming.

Test names describe behaviour, e.g. `appendChunk_afterInterleavedUpdate_keepsSingleMessage`. Add tests next to changed logic — reducers, ACP parsing, segment runs, stream/chunk handling.

**Never write tests for what the type system already guarantees** — non-null returns, sealed `when` exhaustiveness, enum membership, `data class` `equals`/`copy`, constructor field assignment, generics. Same for getters that forward a field and assertions that only compile against the implementation.

### Authoring gate

Answer all four before adding a test:

1. Which observable behaviour, invariant, or independent contract does it protect?
2. Which credible regression makes it fail?
3. Why does existing proof miss it? One owner per contract — a second layer needs a distinct transport or lifecycle risk.
4. Does it need an export, flag, wrapper, or injection hook with no production caller? Then the proof belongs at the real boundary.

A regression test fails before the fix and passes after. Report when no baseline proof is available. One owner regression per bug — never replay it at every crossed layer.

### Junk tests — reject

- Self-comparisons, copied inventories, source greps, and implementation or call-shape assertions (`verify { collaborator.foo() }` proving an internal call happened).
- Duplicate contracts across layers, test-only production seams, expected values produced by the subject under test, mocks supplying the behaviour being asserted.
- Negative controls that fail at the wrong guard; names that don't match the exercised behaviour.

### What to keep

Independent contracts: ACP wire format and parsing, Room schema and `FerngeistMigrations`, `CredentialEncryptor`, observable ordering, config and defaults. Static or slow is not a reason to delete. A red baseline may be a bug to repair, not a test to remove.

### Audit

Before deleting a test, read its owner, callers, and history, and record what proof survives. Delete obsolete production seams together with their tests. Never optimize for a deletion count.

After non-trivial work, exercise the changed path and report the observed result. Report unverified work as unverified.

## Runtime & Tooling

- Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00, Room 2.8.5, Ktor 3.6.0, ACP SDK 0.33.0.
- `ksp.useKSP2=true`, `android.nonTransitiveRClass=true`, `org.gradle.configuration-cache=true`. `org.gradle.parallel` is off.
- Versions are centralised in `gradle/libs.versions.toml` — change them there, never inline.

## Communication

- **Stop at the gate.** Edit → compile/lint/detekt/unit-test clean → report the change → ask "Ready to commit?". Device verification belongs to the maintainer ([guardrails](#verification-gate)).
- **Lead with the answer.** Conclusion first, then evidence. No narrated steps, no recap of what you just did, no tables restating a one-line result.
- **No walls of text.** A few lines. Bullets over paragraphs. If the explanation is longer than the change, cut it.
- **Say what is unverified.** Never imply a fix is confirmed when the evidence is a unit test or a plan. If a claim rests on inference, mark it.
- **Don't blur scope.** If work on one thing is done, don't let the report imply other things shipped too.
- **Close with the cost.** End with one or two lines: what you skipped, what stayed unverified, and the risk that leaves.


## Commit & PR Guidelines

`type(scope): concise imperative summary` — e.g. `fix(chat): keep chunk appends in same assistant bubble`.
Commits are **GPG-signed** (`git commit -S`).

PRs state what changed and why, risk/rollback, and the exact commands run as test evidence. Screenshots for UI changes come from the maintainer, not from you.

Never commit: `local.properties`, `fastlane/play-store-service-account.json`, `ferngeist-release.jks`, `*.log`, `logs/`, `*.db`, `.superpowers/`, `.worktrees/`, scratch dirs.

## Security

`CredentialEncryptor` guards stored credentials — keep it in the path. Review `network_security_config.xml` carefully; cleartext settings weaken transport.

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

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`.

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
Codex: use the `beads` skill at `.agents/skills/beads/SKILL.md` (project) or `~/.agents/skills/beads/SKILL.md` (global); Codex 0.129.0+ can load Beads context through native hooks — `/hooks` toggles them. Commands and rules are above.
<!-- END BEADS CODEX SETUP -->
