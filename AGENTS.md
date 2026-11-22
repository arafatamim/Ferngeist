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
- **The `android` CLI is the Android surface.** Build, deploy, inspect, and profile through `android` ([Android CLI](#android-cli)); `./gradlew` stays the gate.
- **Do the task in the prompt.** It is the whole scope. No tangents, no unsolicited refactors, no "while I'm here" cleanups, no exploring code the task doesn't touch. Investigate only what the task needs to be done correctly; for a new feature that includes the broad picture ([Challenge the plan](#challenge-the-plan)).
- **A test guards behaviour or it does not exist.** No test ships that cannot fail for the behaviour it names. Read [Writing Tests](#writing-tests) before adding, keeping, or reviewing one.
- Think in atomic steps: read the code first, surface assumptions (name competing interpretations instead of silently picking one), turn the task into verifiable criteria, then execute.

### Challenge the plan

- **A new feature gets a design pass before code.** Map the seam it lands in: which modules and layers it crosses, who else owns that data, what already exists nearby. Scope bounds the *change*; the investigation covers the whole picture the change lands in.
- **Name architectural smells in the plan** before writing a line: a layer reaching upward, a second source of truth, logic in the wrong module, a seam with one caller, more files on `feature/chat`'s flat pile.
- **Push back on the maintainer's decisions** when you are confident one is a bad architectural choice. Say it plainly, cite the evidence (the code, the constraint, the failure mode), and recommend the better path. A sound codebase beats agreement. The maintainer makes the call, with the full picture in hand.

### Clean cutovers

- **Replace, then delete.** When a behaviour, format, or API changes, cut the old path in the same change: no compat shims, dual-read paths, deprecated aliases, or flags kept "just in case".
- **The exception is an *already shipped* client.** Keep compatibility only where removing it breaks something already released: a shipped APK's persisted data (Room schema → `FerngeistMigrations`, DataStore keys) or a wire contract a released app or gateway still speaks. Unreleased code on `master` is not shipped; cut it freely.
- When compatibility stays, name the shipped client that needs it and when it can go.

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

## Coding Standards

### Layering & modularization

- Everything has one place and lives in it. Push low-level helpers into `core/model`, `core/common`, `acp-bridge`, or the module's base package.
- Compose the blocks that already exist before writing a new one. Check `core/common` and `core/model` first.
- Layer 0 depends on nothing; layer N may depend only on layers ≤ N-1. Reduce a file's layer, never raise it.
- Directory hygiene: ≤ 7 files. Past that, split by domain. `feature/chat`'s root is the existing exception — new code goes into subpackages by domain, not the flat pile.
- Report boundary violations instead of quietly working around them.

### Small functions

- Function body ≤ 60 lines (the detekt/Konsist cap); aim for 20-50.
- Inline one-expression functions called exactly once — a name that carries no meaning is indirection.
- Split on logical units of abstraction, not on line count.
- Exempt: `@Composable` functions — long by structure (parameter list + UI tree), not by logic.

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
- Don't rerun the gate after every small edit while iterating with the maintainer; run it once before commit.
- **Fix findings; never silence them.** Applies to detekt, ktlint, and Android lint alike: no `@Suppress`, `tools:ignore`, or `//noinspection`; no `detekt-baseline.xml`; no raising a threshold, setting `active: false`, or adding an exclusion glob; no deleting the offending call to dodge the rule. If a finding is genuinely wrong, say why in your report and let the maintainer decide. Silencing a finding to get a green build is a failed task, not a passing one.
- Device verification is off-limits unless specifically asked — no booting emulators, APK installs, UI driving, or device screenshots/pixel scans. When it is asked for, use the [Android CLI](#android-cli) workflow, not raw `adb`.
- **Previews are encouraged; emulator driving is not.** For a UI change, render the changed composables with `android preview` into the harness scratch dir (or `/tmp`), inspect the PNG, and show it in the final reply when the harness can display images. Do not add a preview for every trivial change.
- A green unit test only counts if the test guards behaviour. Every test you add, keep, or review passes the [Writing Tests](#writing-tests) dependency check first.
- A thin detekt report is inconclusive, not a clean bill of health: `src/test` analysis can be limited by classpath.

### Shell & tooling

- Exit code 0 is not proof: classify output. `UP-TO-DATE` means the task never ran; a `compile…UnitTestKotlin FAILED` is a compile error, not a test failure.
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

## Writing Tests

> **Every test must guard behaviour. This is a guardrail, not a preference.** A test earns its place only if it changes when the behaviour it protects changes, and fails when that behaviour regresses. It gets to fail for exactly one reason: **that behaviour regressed.**
>
> A test that guards nothing is worse than none: it turns red for the wrong reason and gets muted for the wrong reason. It does not get written, kept, or reviewed in.
>
> **Three questions, every test, every time:**
>
> 1. What behaviour does this test depend on?
> 2. Which credible regression makes it fail?
> 3. Does another test already own that behaviour?
>
> Three real answers, or the test is a defect. The maintainer's tests are not exempt, and neither is a test you are only reviewing.

### This repo's tests

Tests are JUnit; `mockk` + `kotlinx-coroutines-test` + Turbine for flows, Robolectric where the JVM needs Room (see `data/database` tests).

Test names describe behaviour (`action_condition_outcome`), not implementation. Add tests next to changed logic — reducers, ACP parsing, segment runs, stream/chunk handling.

### Dependency check: what does this test depend on?

A test stays only if it depends on the **intended behaviour** — nothing else. Read the test, name its dependency out loud. Here are the four things a test can wrongly depend on; any one of them means prune.

**Depends on the past — prune.** The test asserts that removed code stays gone, or that a moved module is absent from its old path. Bringing either back would be a decision, not a regression, so the test cannot flag one.

**Depends on nothing — prune.** No change to the code can make it fail, so it has no reason of its own to change.

- It tests the platform: a `<button>` renders, or a click event fires. The platform is already tested.
- It mocks the unit under test, then asserts what the mock returns.
- It asserts what the type system already guarantees: non-null returns, sealed `when` exhaustiveness, enum membership, `data class` `equals`/`copy`, constructor field assignment, generics, a getter forwarding a field, an assertion that only compiles against the implementation. A widened or narrowed parameter belongs here too — if the old type could not express the new value, the compiler already fails every call site.

**Depends on the implementation — prune.** The expected value is derived from the same logic as the code, or the test asserts private state, internal call order, or which helper was invoked; either way it changes whenever the code does, even when behaviour did not.

**Depends on a copy — prune.** If another test already guards the same behaviour, keep one owner and cut the duplicate. One owner per contract; a second layer needs its own transport or lifecycle risk to justify itself.

A regression test fails before the fix and passes after; report when no baseline proof exists. One owner regression per bug — never replay it at every crossed layer.

Also rejected on sight: self-comparisons, copied inventories, source greps, implementation and call-shape assertions (`verify { collaborator.foo() }` proving an internal call happened), test-only production seams (proof belongs at the real boundary), expected values produced by the subject under test, mocks supplying the behaviour being asserted, negative controls that fail at the wrong guard, and names that don't match the exercised behaviour.

### What to keep

Independent contracts: ACP wire format and parsing, Room schema and `FerngeistMigrations`, `CredentialEncryptor`, observable ordering, config and defaults. Static or slow is not a reason to delete. A red baseline may be a bug to repair, not a test to remove.

### Audit

Before deleting a test, read its owner, callers, and history, and record what proof survives. Delete obsolete production seams together with their tests. Never optimize for a deletion count — prune per the dependency check, not for a smaller number.

After non-trivial work, exercise the changed path and report the observed result. Report unverified work as unverified.

> ^^^ Everything above is the harness doctrine. Everything below is this repo's context. ^^^

---

## Project Overview

Ferngeist is a multi-module Android client for the **Agent Client Protocol (ACP)**. It launches and connects to coding agents, renders their streaming turns as chat, and exposes session/agent management, push notifications, and an optional remote gateway for pairing.

Kotlin + Jetpack Compose + Hilt + KSP; SDK levels and JVM target are set per module in its `build.gradle.kts`.

## Architecture & Data Flow

Gradle modules (see `settings.gradle.kts` — the authoritative list):

- `app` — `FerngeistApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`), theme, navigation host, workspace panes, UnifiedPush (`FerngeistPushService`, `PushRegistrar`) + foreground service. Flavour sources live in `feature/serverlist/src/{google,foss}/kotlin` (`AddGatewayQrScanner`).
- `acp-bridge` — the ACP seam. `connection/` owns transport, auth headers, capability negotiation, and the connect orchestrator; `session/` owns `SessionBridge`, `SessionMessageReducer`, `SessionRuntime`; `hub/` multiplexes one connection across concurrent chats; `facade/` is what the chat feature sees; `launcher/` is the server list's launch-and-auth seam.
- `gateway-client` — HTTP/WebSocket client for the optional remote gateway (`GatewayRepositoryImpl`), launch/lease, proof auth, credential refresh.
- `core/model` — pure data types (`ChatMessage`, `AssistantSegment`, `ServerConfig`, `GatewaySource`) plus repository *interfaces*. No Android UI deps.
- `core/common` — `MviViewModel<State, Intent, Effect>` (the shared ViewModel base: `MutableStateFlow` for state, `Channel(BUFFERED)` for effects) and shared UI atoms (`EdgeFade`, `ConnectionStatusPill`, `WindowSizeSeam`).
- `core/git` — chat git status/log/diff loaders, diff stats, and the branch graph; depends only on `core/model` + `core/common`.
- `data/database` — Room DB, DAOs, entities, migrations, `CredentialEncryptor`.
- `feature/serverlist`, `feature/sessionlist`, `feature/chat` — UI + presentation logic; `feature/chat` holds `ChatScreen`, `MessageBubble`, the composer, and scroll policy. ViewModels extend `MviViewModel`.
- `e2e` — `com.android.test` harness against the app; not shipped.
- `konsist` — JVM-only Konsist tests: module layering plus Kotlin conventions (naming, wildcard imports, 60-line functions, data-class shape). No production code; run `./gradlew :konsist:test`.

**Event path:** ACP `session/update` → `AcpSessionUpdateMapper` → `SessionMessageReducer` (appends to segments) → `SessionBridge` emits `AppSessionEvent` on a `SharedFlow` → `ChatViewModel` reduces into UI state → Compose.

Production code lives in `src/main/kotlin`; tests in `src/test/kotlin` (`konsist` is test-only).

## Development Commands

Run from the repo root. `android` (Google's CLI, on `PATH`; SDK from `android info`) is the default interface to the app — build, deploy, inspect, profile, docs. The verification gate and tests stay on `./gradlew`.

### Android CLI

| Goal | Command |
|---|---|
| Variant → APK map | `android describe` |
| Build a Gradle task | `android build :app:assembleGoogleDebug` (also `:feature:chat:compileDebugKotlin`) |
| Install APK, no launch | `android install app/build/outputs/apk/google/debug/app-google-debug.apk` |
| Install + launch APK | `android run --apks=app/build/outputs/apk/google/debug/app-google-debug.apk` (add `--activity=`, `--device=`) |
| UI tree (JSON) | `android layout` (`--flat`, `--full`, `-o file`) |
| Screenshot (device) | `android screen capture -o /tmp/screen.png` |
| Compose preview | `android preview list :feature:chat` → `android preview render :feature:chat --fqn=<from-list> -o <scratch>` |
| Locate what layout misses | `android screen capture --annotate -o /tmp/screen.png`, then `android screen resolve --screenshot /tmp/screen.png --string "tap #3"` |
| Tap / type / scroll | `android input tap "text=Send"`, `android input text "hello world" "clz=EditText"`, `android input scroll "clz=RecyclerView" --dir=up --until "text=Settings"` |
| Emulators | `android emulator list`, `android emulator start <avd>`, `android emulator stop <avd>` |
| Official docs | `android docs search "<topic>"` → `android docs fetch kb://…` |
| Artifact size and optimization | `android analyze <apk-or-aab> --analysis=optimization` |
| Trace / heap | `android profiler systemtrace start` / `stop`, `android profiler heapdump` |

- Target elements by selector (`text=`, `clz=`, `clickable=`, `resource-id=`) with `android input`; reserve raw coordinates for the annotated-screenshot fallback — they rot first across screen sizes.
- `layout` is the primary inspection; screenshot is secondary. Read the PNG before acting on it. Pass `--device=<serial>` whenever more than one device is attached (`adb devices` lists serials).
- `android preview` renders without a device or emulator. It works on any module without product flavours; on flavoured modules (`:app`, `:feature:serverlist`) it resolves a plain `debug` variant and fails, as does `android run :app` — pass `run` an `--apks=` path.
- `--fqn` is the exact string `android preview list` prints (usually `<File>Kt.<Preview>`), not the source-level annotation path. Renders land under `-o`; write them to harness scratch or `/tmp`, never the repo.
- Install/launch/input/screen/layout are device work: the CLI is the default tool, not a default permission ([guardrail](#verification-gate)).

### Gradle gate

The CLI has no dedicated test/lint reporting; run the gate directly:

```bash
./gradlew :feature:chat:testDebugUnitTest  # touched module; compiles main + test
./gradlew detekt ktlintCheck                # gate
./gradlew :konsist:test :app:lintFossDebug  # architecture + Android lint
```

- SDK from `local.properties` (`sdk.dir=`) or `ANDROID_HOME`; JDK on `PATH` (no `JAVA_HOME` needed).
- `chmod +x gradlew` if the exec bit is lost; add `--no-configuration-cache` after a build-script edit.

## Code Conventions & Patterns

- Formatting and line length follow `.editorconfig`.
- Kotlin warnings and Android lint warnings fail the build; a deprecation is a compile error.
- ViewModels: extend `MviViewModel`, `dispatch(intent)`, mutate via `updateState`, one-shot effects via `emitEffect`.
- DI: Hilt, all modules in `SingletonComponent`; module files are named `*Module.kt`.

## Runtime & Tooling

- Versions live in `gradle/libs.versions.toml` — change them there, never inline.
- Build flags live in `gradle.properties`.

## Communication

- **Stop at the gate.** Edit → compile/lint/detekt/unit-test clean → report the change → ask "Ready to commit?". Device verification belongs to the maintainer ([guardrails](#verification-gate)).
- **Lead with the answer.** Conclusion first, then evidence. No narrated steps, no recap, no walls of text; if the explanation is longer than the change, cut it.
- **Say what is unverified.** Mark inference; never imply a unit test or a plan confirms more than it does.
- **Don't blur scope.** Close with the cost: what you skipped, what stayed unverified, and the risk that leaves.

## Commit & PR Guidelines

`type(scope): concise imperative summary` — e.g. `fix(chat): keep chunk appends in same assistant bubble`.
Commits are **GPG-signed** (`git commit -S`).

PRs state what changed and why, risk/rollback, and the exact commands run as test evidence. Device screenshots come from the maintainer; preview renders you produced are fine to include.

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
