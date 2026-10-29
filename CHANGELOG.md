
## [Unreleased]

### Features

- *(projects)* Use the Material 3 expressive selection menu for the agent filter.
- *(theme)* Use the complete wallpaper-derived dynamic color scheme where available, with a fern palette as fallback.
- *(sessions)* Show working, needs-attention, finished and error status across chat cards, hero/recents, project and agent cards, and the session switcher, using live local state or the gateway's `/v1/turns` snapshot.
- *(chat)* Replace the session switcher count with an attention or error icon, with a brief hop and haptic when another session needs attention, fails, or finishes.
- *(chat)* Group the session switcher by project and show each chat's agent logo and activity shape.
- *(gateway)* Show bundled agent logos when the gateway provides no registry logo.
### Fixes

- *(home)* Hide the refresh indicator over the projects list while keeping pull-to-refresh available.
- *(chat)* Make the session switcher button closer in height to the composer.
- *(sessions)* Dismiss Finished across turn indicators when its chat is opened, including turns that finish while open, without hiding later off-screen completions.
- *(sessions)* Color cards needing attention or reporting a failed turn with tertiary or error container roles.
- *(sessions)* Keep agent logos visible on the needs-attention badge in light and dark themes.
- *(home)* Use the tertiary color pair for the needs-attention hero clover and its agent logo.
- *(home)* Show a streaming verb alongside active-chat counts on project and agent cards.
- *(sessions)* Use working ellipses instead of shimmer on turn indicators and the chat streaming verb.
- *(home)* Rotate the hero clover while its chat is working, and smoothly return it to rest when the turn stops.
- *(chat)* Account for a phone-origin turn finishing after leaving the chat when checking recent activity.
- *(build)* Keep gateway icon selection compatible with data-class conventions.
- *(projects)* Hide projects without visible chats from the home list.
- *(chat)* Show theme-primary working subtitles with animated ellipses on in-progress chat cards, replacing standalone activity shapes while preserving neutral-gray spinning backgrounds behind agent icons.
- *(projects)* Replace the activity spinner with theme-primary active-chat text and an animated ellipsis.
- *(projects)* Crossfade complete subtitle changes, including idle counts, with a slower theme transition.
- *(agents)* Reuse the active-chat subtitle on agent cards, crossfade back to gateway/host details when idle, and remove the separate spinner.
- *(projects)* Match the agent-card tint and color transition only for projects containing an exact live agent/chat connection, without lighting other projects on the same agent.
- *(projects)* Animate refreshed home project rows into their new positions in both list views.
- *(gateway)* Keep protocol compatibility v2-only; reject unverified newer protocol versions.
- *(projects)* Refresh gateway project timestamps when a local turn stops streaming, after any older fetch finishes.
- *(projects)* Keep live projects first in both home list views, including live projects with old or unknown timestamps.
- *(build)* Clear static-analysis findings while preserving gateway, workspace and worktree behavior.

## [1.5.1] - 2026-10-19

### Features

- *(chat)* Open a read's whole file from its tool call sheet ([87c9534](https://github.com/arafatamim/Ferngeist/commit/87c953477953b028ae5354852f17406ed8aa53e6))

### Fixes

- *(chat)* Keep the file sheet header to one line for long names ([85be659](https://github.com/arafatamim/Ferngeist/commit/85be6595a6a21065cec00cb400f63a23d7040e66))
- *(build)* Drop dependency metadata block from APKs for F-Droid ([d4046a0](https://github.com/arafatamim/Ferngeist/commit/d4046a08a8becafc6270aea39563400d119cc4f1))

## [1.5.0] - 2026-10-19

### Documentation

- *(readme)* Add DeepWiki badge ([1523daa](https://github.com/arafatamim/Ferngeist/commit/1523daaa60c0394f4d806ca07459b39026ca3c59))

### Features

- *(chat)* Compare the working tree against another branch ([7ae52c5](https://github.com/arafatamim/Ferngeist/commit/7ae52c5f369cdf93d7fd57cf363a8aca6d356ca3))
- *(chat)* Show the walk's size on the History tab ([5103e3e](https://github.com/arafatamim/Ferngeist/commit/5103e3e8aea10aee4cd1b5e6db500f23c90aa797))
- *(chat)* Open the git sheet comparing against the default branch ([1f715d5](https://github.com/arafatamim/Ferngeist/commit/1f715d599e803577d125f3da0882ad70aa07e2c8))
- *(chat)* Open files named in assistant replies ([332a29f](https://github.com/arafatamim/Ferngeist/commit/332a29f2891d5604c3a4cc1d80303b4d076fa37f))
- *(chat)* Open a diff's whole file from the git panel ([67e6d85](https://github.com/arafatamim/Ferngeist/commit/67e6d85c50bb4cd352196c87e201dcfc02894482))
- *(chat)* Mark where a branch's own commits stop in history ([728f77c](https://github.com/arafatamim/Ferngeist/commit/728f77cc9e08c0f5e16292c3de82b6ef04080786))
- *(chat)* Count a day's commits beside its history header ([13d7a09](https://github.com/arafatamim/Ferngeist/commit/13d7a09afa0b354173222a3fbae83a76eef4c574))

### Fixes

- *(chat)* Stop long file names pushing out the diff counts ([4ea42ee](https://github.com/arafatamim/Ferngeist/commit/4ea42ee85a6ea30f4f8a061517a303af1a4cfe69))
- *(chat)* Re-read git status on sheet open, resume and re-entry ([ac06be4](https://github.com/arafatamim/Ferngeist/commit/ac06be4ba9e95ec885c319bb596a56641247e22c))
- *(gateway)* Sign proofs against the gateway's clock to survive device skew ([e609fc8](https://github.com/arafatamim/Ferngeist/commit/e609fc8913c51920f9c76e3115c92860f2e0be00))
- *(android)* Request local network permission on Android 17 (#8) ([42d7265](https://github.com/arafatamim/Ferngeist/commit/42d726524302df4e3d1aaae3b3a35b0264be6056))
- *(gateway)* Keep the status handshake on the versioned path ([6aa5d06](https://github.com/arafatamim/Ferngeist/commit/6aa5d0685cfe73d88bebf893316d77643f3c4010))
- *(build)* Keep datastore native lib unstripped for reproducible builds ([4c4b388](https://github.com/arafatamim/Ferngeist/commit/4c4b388a6732c52fbdef0af8fd33cd0e9a66620f))

### Merge

- *(chat)* Open files named in assistant replies ([49c4e46](https://github.com/arafatamim/Ferngeist/commit/49c4e46dc88445f77e3e2c3ce9779268caa244f3))
- *(chat)* Mark a branch's own commits in history ([6754936](https://github.com/arafatamim/Ferngeist/commit/675493640675bd44cdd7590939957b5e3eecdf59))
## [1.4.0] - 2026-10-18

### Features

- *(chat)* Show file-type icons and chips for files ([fc54f73e](https://github.com/arafatamim/Ferngeist/commit/fc54f73e5c6b4625a5d51a9ec1b5d671465497c4))

### Fixes

- *(fastlane)* Skip release notes when promoting between tracks ([d73bad00](https://github.com/arafatamim/Ferngeist/commit/d73bad00a9df854889dca1f89f99b43c57241994))
- *(acp-bridge)* Avoid ConcurrentHashMap.toMap() NSEE during reconnect ([e2f131d2](https://github.com/arafatamim/Ferngeist/commit/e2f131d2265cf2eb1b8229449aa7ac9d8c2c6992))
- *(workspace)* Correct 3-pane threshold comment and pin breakpoints ([21d71a83](https://github.com/arafatamim/Ferngeist/commit/21d71a83728ccacd79a3c7249ceab4e83935f501))
- *(workspace)* Reset the sessions pane per agent ([cc7af884](https://github.com/arafatamim/Ferngeist/commit/cc7af884927b6b1ca8a6107e3d93f7eaaecdc5d2))
- *(gateway)* Resume disconnected sessions before connecting a fresh agent ([00a1033f](https://github.com/arafatamim/Ferngeist/commit/00a1033f25881f388e45c3c56811692e88aaebfd))
- *(chat)* Show accurate diffs for tool calls and turn changes ([a47e24a6](https://github.com/arafatamim/Ferngeist/commit/a47e24a6610d85242208666568e4223b9ce3206e))
- *(chat)* Separate staged from unstaged files in the git view ([c2566dd3](https://github.com/arafatamim/Ferngeist/commit/c2566dd3be39bcf0e5cc6cb3540a487de2ddea22))
- *(chat)* Open every diff a turn file's row counted ([1cf1a913](https://github.com/arafatamim/Ferngeist/commit/1cf1a913d51b7a1eeeef4ebdcae9ce907f205540))
- *(chat)* Keep the tool call sheet from settling half-expanded ([ae9690de](https://github.com/arafatamim/Ferngeist/commit/ae9690de0dacee7fe3adde73ff1768e9c6fb84a7))

## [1.3.0] - 2026-10-15

### Documentation

- *(readme)* Lead with what the app does for users ([1a70dcb](https://github.com/arafatamim/Ferngeist/commit/1a70dcb874f2be29be14f84297ab2dd94f1fc350))
- *(readme)* Note the installer already starts the gateway ([0d17cc7](https://github.com/arafatamim/Ferngeist/commit/0d17cc7f74153335c0239562a07e64c2533d890c))
- *(readme)* Add demonstration video ([a9d3cda](https://github.com/arafatamim/Ferngeist/commit/a9d3cda4a1b2224f1498a5ac49f8a1dea4941f26))
- *(privacy)* Note the avatar lookups for commit authors ([96c3865](https://github.com/arafatamim/Ferngeist/commit/96c3865466ae6454996781d24cb91cf472c53eb0))

### Features

- *(chat)* Live one-line reasoning tail in the tool rail ([146fbe6](https://github.com/arafatamim/Ferngeist/commit/146fbe6eb0761af3ee03c95fdfddeece3951563b))
- *(chat)* Rework transcript scroll around a prompt runway ([e0c1adb](https://github.com/arafatamim/Ferngeist/commit/e0c1adb7a2879ed6cf5f696d3b3eee80f579e615))
- *(chat)* Time live tool groups and render reasoning as markdown ([1563b3d](https://github.com/arafatamim/Ferngeist/commit/1563b3dc11ae6bd6952ace4534332924046ea46d))
- *(chat)* Show each turn's time and finish in its footer ([7cd84f8](https://github.com/arafatamim/Ferngeist/commit/7cd84f8f45e42240bd811f62ebe2256adb0eccd7))
- *(gateway)* Read git history and commit files from the gateway ([604056b](https://github.com/arafatamim/Ferngeist/commit/604056bfc04ebd1e824019402da47e6b0431b798))
- *(acp-bridge)* Expose git history through the chat facade ([2ae220b](https://github.com/arafatamim/Ferngeist/commit/2ae220bdba8ccbdcafce8e0b59fdd95ac297d1b4))
- *(chat)* Browse commit history in the git sheet ([b41c74f](https://github.com/arafatamim/Ferngeist/commit/b41c74fca1a31cee87a9813d869ce0671b02533e))
- *(chat)* Show commit authors' pictures ([7266e60](https://github.com/arafatamim/Ferngeist/commit/7266e60fedd8dfd4e3f3d2aa68b5ef77665d64f3))
- *(chat)* Translate the git history strings ([3bad0bb](https://github.com/arafatamim/Ferngeist/commit/3bad0bb615d7766985a53e7eca27339f0cffb1e5))
- *(chat)* Follow live tool rails instead of streaming text ([4da6dec](https://github.com/arafatamim/Ferngeist/commit/4da6dec4227eb16b780df9f6357918e3f3dd7090))
- *(chat)* Lead the tool group header with a chevron on the rail ([4aeee5c](https://github.com/arafatamim/Ferngeist/commit/4aeee5cc642b543a37e4b35e642f3c28d14924cc))
- *(chat)* Show git history as file-style cards with faded edges ([4eda461](https://github.com/arafatamim/Ferngeist/commit/4eda461fbf3b4fca86ca7e33cdf3cf107ef34e67))
- *(chat)* Show a ref's full name on long press in the commit view ([b759e18](https://github.com/arafatamim/Ferngeist/commit/b759e18724c8287253af114a567f20fd8ba3ab17))
- *(chat)* Refresh git history at the end of each turn ([a743110](https://github.com/arafatamim/Ferngeist/commit/a743110f1fb72c1a45e1439a803ec2a60381aba2))
- *(chat)* Show full ref names and expandable titles in the commit view ([cb513f9](https://github.com/arafatamim/Ferngeist/commit/cb513f9dad670077be29e5b5d49c0bbe49628fd1))
- *(chat)* Group git history by day ([b82c7e2](https://github.com/arafatamim/Ferngeist/commit/b82c7e25b49285c67e868d82de16e7147492a1b2))

### Fixes

- *(chat)* Match turn diff counts to git ([fc91eda](https://github.com/arafatamim/Ferngeist/commit/fc91edad8f41ee74b8bbc882e5ec44389f8c4a5c))
- *(chat)* Move the transcript with the keyboard and composer ([ff10562](https://github.com/arafatamim/Ferngeist/commit/ff1056230f5b6e83dae72357de2c3cf9fa0a441c))
- *(chat)* Restore a chat's scroll once, not on every save ([112daa6](https://github.com/arafatamim/Ferngeist/commit/112daa69bcfa5bee46e62c37cfc2d0fcaea08236))
- *(chat)* Let go of the held reply once the reader scrolls ([f69eac5](https://github.com/arafatamim/Ferngeist/commit/f69eac5a3b5797b04c29b47f00ebc989b6e3d549))
- *(chat)* Leave a reply closed by a reattach untimed ([a519df2](https://github.com/arafatamim/Ferngeist/commit/a519df29603a842294dce7327b587556ed9c2ceb))
- *(chat)* Time a turn by the gateway's duration, not when the phone hears it end ([feabf55](https://github.com/arafatamim/Ferngeist/commit/feabf5536fd787f367064e74a30f101e76d25096))
- *(app)* Resize for the keyboard instead of panning the window ([0901bef](https://github.com/arafatamim/Ferngeist/commit/0901beffacadf579202342d01cc669c6204305d4))
- *(chat)* Fetch git status once a loaded session is ready ([06fb695](https://github.com/arafatamim/Ferngeist/commit/06fb695221a9bde0dce80d9b8e9377e611795767))
- *(chat)* Glide the first prompt sent after opening a chat ([241bf43](https://github.com/arafatamim/Ferngeist/commit/241bf43c2c71084149d7acf648694990517287ab))
- *(service)* Hang up every transport from the notification's Stop action (#7) ([4a6115b](https://github.com/arafatamim/Ferngeist/commit/4a6115b4766280e45d5312d74e63220ed9557163))
- *(chat)* Keep loaded history pages when it refreshes ([aadc168](https://github.com/arafatamim/Ferngeist/commit/aadc1685733503e815c8ada983182ad5ec553aa1))
- *(chat)* Keep history dates current while the sheet is open ([3a040f5](https://github.com/arafatamim/Ferngeist/commit/3a040f55a5a99c35ae2cb3492605dfcafe05c3a9))
- *(chat)* Date history rows by when they were committed ([5aab34b](https://github.com/arafatamim/Ferngeist/commit/5aab34beb0cfc682c283c761ef19e848371de7fd))

### Maintenance

- Make the Gradle wrapper executable ([68ddc73](https://github.com/arafatamim/Ferngeist/commit/68ddc73ce7941bd7ab0b8897b690deab550b0080))
- *(release)* Bump versionCode to 1607 ([dbacf2e](https://github.com/arafatamim/Ferngeist/commit/dbacf2edb71970de2379e39cb4b11186aec2c5f1))

### Testing

- *(app)* Pass the turn timing repository to the chat view model factory ([275da20](https://github.com/arafatamim/Ferngeist/commit/275da208e9f351ed812daf3d18214acb3fdfd016))
- *(chat)* Run the view model's I/O on the test scheduler ([e5a05f3](https://github.com/arafatamim/Ferngeist/commit/e5a05f385af5a2d44705e5b28cb7cd86bf58d2b5))

### Merge

- *(chat)* Browse commit history in the git sheet ([b9f47e4](https://github.com/arafatamim/Ferngeist/commit/b9f47e4895288d474d76577be06a9d240809670d))

### Release

- *(1.2.0)* Bump versionCode to 1606, Play notes follow ([b0b02bb](https://github.com/arafatamim/Ferngeist/commit/b0b02bbc7200bee6b9dbdffbef918390e8a54d7b))

## [1.2.0] - 2026-10-11

### Documentation

- *(readme)* Refresh versions and simplify gateway setup ([c137f43](https://github.com/arafatamim/Ferngeist/commit/c137f430041126b2b4ee1e7665f1af7ce389d1dc))

### Features

- *(nav)* Morph server card into session list on open ([1e3e18b](https://github.com/arafatamim/Ferngeist/commit/1e3e18b12c1e471f12e1e345df55f48dc6068518))

### Fixes

- *(chat)* Surface the server's own error for failed prompts and connects ([45299f8](https://github.com/arafatamim/Ferngeist/commit/45299f81afb9dcb2ae5b7a9a4d3233019e065900))
- *(bridge)* Close leaked gateway sessions and drop dead code ([6ce9a52](https://github.com/arafatamim/Ferngeist/commit/6ce9a5274365fe32b7d2ead79c4b214005e192c8))
- *(app)* Keep the battery prompt up until the user decides ([3877006](https://github.com/arafatamim/Ferngeist/commit/3877006b2a9113a2287aa75b9f066b04f2453c7c))
- *(bridge)* Detect mismatched gateway protocol on server connect ([0b992fb](https://github.com/arafatamim/Ferngeist/commit/0b992fba729dce7f325b3adf88046771600d5ca1))

### Refactoring

- *(bridge)* Restructure acp-bridge and adopt gateway v2 recovery ([6d5a3c5](https://github.com/arafatamim/Ferngeist/commit/6d5a3c5a5361a6ab4b88f0da3945cbb52625ccdb))

### Testing

- *(e2e)* Stress concurrent agent chats while the app is backgrounded ([1b0cdd5](https://github.com/arafatamim/Ferngeist/commit/1b0cdd52a6d6db3643fcbdef014baa2490db65d4))

## [1.1.0] - 2026-10-11

### Documentation

- Rewrite AGENTS.md for the nine-module layout ([ad94e42](https://github.com/arafatamim/Ferngeist/commit/ad94e4273403e578fa66d8c7dcbdbdb1f6b6e722))

### Features

- *(ui)* Collapse app bars on short windows ([bab709a](https://github.com/arafatamim/Ferngeist/commit/bab709a2aae55b248262b89da500bef4fa7778c1))
- *(chat)* Show tool group summary in present continuous while running ([cc7d289](https://github.com/arafatamim/Ferngeist/commit/cc7d28926e60e8955e2a607fe52f2ed14d824a94))
- *(chat)* Pin prompt chip only for answers longer than a viewport ([7a65d6d](https://github.com/arafatamim/Ferngeist/commit/7a65d6dbe3435e65df68b709ec3586721a524350))
- *(chat)* Fade pinned prompt chip out as its bubble re-enters ([cc468e2](https://github.com/arafatamim/Ferngeist/commit/cc468e27b4dfac71370162ce59cd401b2f37dfa7))
- *(chat)* Add ACP elicitation form and URL sheets ([f34e75f](https://github.com/arafatamim/Ferngeist/commit/f34e75fc3c4fcae7d2ec37141d885d78ad4e4a18))
- *(push)* Receive notifications over UnifiedPush instead of FCM ([0378936](https://github.com/arafatamim/Ferngeist/commit/0378936cbeb16da842425e5a72bef006b0e0e58e))
- *(core)* Add pointer and key affordance modifiers ([9ae0f6e](https://github.com/arafatamim/Ferngeist/commit/9ae0f6ea397d1a524e2d5b496f7ed9032cbdb837))
- *(chat)* Attach pasted and dropped content in the composer ([13657d4](https://github.com/arafatamim/Ferngeist/commit/13657d489e743a10ddaca6e2d206266fcf2da242))
- *(chat)* Add pointer and keyboard affordances to the chat surfaces ([839f576](https://github.com/arafatamim/Ferngeist/commit/839f5760712bc81bd9e17fc229e36c79ff3d34e0))
- *(serverlist)* Add pointer and keyboard affordances ([b687a0f](https://github.com/arafatamim/Ferngeist/commit/b687a0f9aff5c3c6e01dec77003a328ea2cd5b73))
- *(sessionlist)* Add pointer and keyboard affordances ([70796f0](https://github.com/arafatamim/Ferngeist/commit/70796f08624d70765be4c481af097157de044023))
- *(chat)* Scope workspace calls to the chat's ACP session ([01a776b](https://github.com/arafatamim/Ferngeist/commit/01a776b1ca9737f4e683342a5de45de36b4a4217))
- *(sessionlist)* Keep a repo's worktree chats under it in the cwd filter ([d95f245](https://github.com/arafatamim/Ferngeist/commit/d95f245d0b73804cbffb80f537338eaa648eb0b8))
- *(sessionlist)* Poll while visible and filter by cwd locally ([a5df892](https://github.com/arafatamim/Ferngeist/commit/a5df89244e3310747007384f877c5acb1dcc5c5d))
- *(gateway-client)* Add the gateway worktree API ([ca9bd82](https://github.com/arafatamim/Ferngeist/commit/ca9bd821e7eb80e55a132a5827e2b06581a2f84e))
- *(sessionlist)* Open and manage chats in gateway worktrees ([3c13beb](https://github.com/arafatamim/Ferngeist/commit/3c13beb84e5553c32ed067a6f0e9908927c2bdfd))
- *(sessionlist)* Open new chats in an existing worktree ([a7117b9](https://github.com/arafatamim/Ferngeist/commit/a7117b9f22eff90987dfbf65b4c37ffc50a81365))
- *(chat)* Steer queued prompts into running turns ([dd4d58d](https://github.com/arafatamim/Ferngeist/commit/dd4d58d818c93e1335574ca7bdbc53736c4e328d))
- *(chat)* Animate the mode label and resize the toolbar on mode change ([dcbcc23](https://github.com/arafatamim/Ferngeist/commit/dcbcc233150c70179c6b08563457f16611438d8d))

### Fixes

- *(serverlist)* Clarify custom agent command runs on the gateway host ([94a7037](https://github.com/arafatamim/Ferngeist/commit/94a70377c4f8508f8a417ae369475d1f5d8508a8))
- *(data)* Cascade target deletion to that target's sessions ([690a6ad](https://github.com/arafatamim/Ferngeist/commit/690a6ad564930e1466b51eaa9dbd6e777e0a13ca))
- *(chat)* Restore the git status indicator's long press ([178fbf6](https://github.com/arafatamim/Ferngeist/commit/178fbf69feae0a3186b095f414cfb7947486f370))
- *(gateway)* Keep the pairing when a refresh is rejected for a stale proof ([e7db2a3](https://github.com/arafatamim/Ferngeist/commit/e7db2a35e55215cf65e3ca1314bad3f63cc3fd98))
- *(chat)* Stop composer expand/collapse recomposing the whole screen ([4690c67](https://github.com/arafatamim/Ferngeist/commit/4690c67ac80b5105582c59aa336ce2c12fd440a1))
- *(chat)* Write a session's row on mint and touch it when a turn ends ([7783265](https://github.com/arafatamim/Ferngeist/commit/7783265fb6aab5797b6795f2f20aa96b68a9edb6))
- *(chat)* Steady the pinned prompt chip's fade and visibility ([a9b0a13](https://github.com/arafatamim/Ferngeist/commit/a9b0a1353f524c76915a8d8cbde395a3809c67d8))
- *(chat)* Name only running calls in the tool group summary and animate its changes ([5b03528](https://github.com/arafatamim/Ferngeist/commit/5b035281be095e2e8c9cc1d3204f7ce53250cad1))
- *(push)* Compare gateway-owned ids when suppressing the on-screen chat's pushes ([095f47f](https://github.com/arafatamim/Ferngeist/commit/095f47f9a272e61e3f00147b11a07db27002c4a8))
- *(service)* Name the connected agents in the persistent notification ([129a033](https://github.com/arafatamim/Ferngeist/commit/129a033eb7cce3ea51ce90cc6e0b189b53037ead))
- *(push)* Open the tapped session's agent and quiet stale working notifications ([f570234](https://github.com/arafatamim/Ferngeist/commit/f570234e6752cda4e4b1e64daf4d359fed436e0b))
- *(chat)* Hold queued prompts until the turn ends and pin them above the composer ([d81b4ef](https://github.com/arafatamim/Ferngeist/commit/d81b4ef2747a286c1ba6dfa5cb483fe568786fdb))
- *(session)* Fail tool calls left unfinished when a turn ends ([3fd6570](https://github.com/arafatamim/Ferngeist/commit/3fd6570f2e8061e544948ebe9ef6c534de47e0a7))

### Maintenance

- *(release)* Bump versionCode to 1602 for internal testing ([f941cc8](https://github.com/arafatamim/Ferngeist/commit/f941cc891d55a8471ec6ea3f8a1f4a441bee7b97))
- *(deps)* Upgrade Gradle, AGP, Kotlin and libraries to their latest releases ([df20f32](https://github.com/arafatamim/Ferngeist/commit/df20f322df30518bb4f7894e173978e85c8d1f30))
- *(app)* Bump versionCode to 1604 ([9b81672](https://github.com/arafatamim/Ferngeist/commit/9b8167220ae73512b4ca60206da72d9395126b72))

## [1.0.0] - 2026-10-05

### Documentation

- *(play)* Refresh screenshots from the alpha11 build ([7287a71](https://github.com/arafatamim/Ferngeist/commit/7287a713084e9321c46387c442923e5231c11f8d))

### Features

- *(chat)* Fill the loading skeleton to the viewport and fade it out ([1623026](https://github.com/arafatamim/Ferngeist/commit/162302620710d8304aa2a121c42d06de0dfb7531))
- *(chat)* Swipe to close a session from the switcher sheet ([90cc1e2](https://github.com/arafatamim/Ferngeist/commit/90cc1e2431cbd5aabdcafd8965c8b2f816eadc01))
- *(launch)* Better first launch experience ([9cd25b0](https://github.com/arafatamim/Ferngeist/commit/9cd25b0d15ee0a84b747a9207c7df2387634b870))
- *(serverlist)* Agent logo badges, busy sun replaces icon in place ([d64f985](https://github.com/arafatamim/Ferngeist/commit/d64f9856227c1010a0618805ab7ebfba88a42b57))
- *(app)* Bundle Geist Mono variable font ([65c573e](https://github.com/arafatamim/Ferngeist/commit/65c573e5c37e8435eb8edc6400e7ad8e2c81dabd))
- *(chat)* Add randomised empty-session hero ([1209531](https://github.com/arafatamim/Ferngeist/commit/120953167fbf046198b464e7ab2ab3faa17170db))
- *(chat)* Reveal streamed markdown in the composition ([dc5741f](https://github.com/arafatamim/Ferngeist/commit/dc5741fafb750bd9a98e8a9cc5e06cfc7f7926f5))
- *(chat)* Ease the jump-to-top and jump-to-bottom scrolls ([b52d5b0](https://github.com/arafatamim/Ferngeist/commit/b52d5b061ef1f70542a733c1c6f6ed3223256854))
- *(ui)* Drift the session list in on a short fade-and-slide ([b77ba58](https://github.com/arafatamim/Ferngeist/commit/b77ba586d8b9b191d39ed0e21ca7e24e557b19c5))
- *(ui)* Add motion to lists, tool calls, plans and counts ([1cef668](https://github.com/arafatamim/Ferngeist/commit/1cef668a9388c50d42f1b4118a1c64bce3b1a612))
- *(chat)* Shimmer a live session's title in the switcher ([3c8b40b](https://github.com/arafatamim/Ferngeist/commit/3c8b40b54af5ce08eca25b3263878d7e99f3d090))
- *(acp)* Carry tool-call locations through to the message model ([62bede3](https://github.com/arafatamim/Ferngeist/commit/62bede3ea0b4dd8613ee6e81b4f619895b8fea6f))
- *(chat)* Replace the tool-call cards with an activity rail ([2270241](https://github.com/arafatamim/Ferngeist/commit/2270241284fff4f494ef060a281145530f222442))
- *(chat)* Pin the turn's prompt above its answer while it scrolls ([4c9aa37](https://github.com/arafatamim/Ferngeist/commit/4c9aa37622bc2ef8143f5731ccd6ea97eb43a03d))

### Fixes

- *(chat)* Clip switcher row ripple to card shape ([8a1b7de](https://github.com/arafatamim/Ferngeist/commit/8a1b7de769f29ed8d4a5141fc90123a16ccd3fed))
- *(acp-bridge)* Fold adjacent message segments at turn close ([e46cbfb](https://github.com/arafatamim/Ferngeist/commit/e46cbfb21cd5c052432a724492649fe6bd2efb30))
- *(sessionlist)* Paint nothing while the session cache is unread ([5f8a5f2](https://github.com/arafatamim/Ferngeist/commit/5f8a5f2d51d4c2ace7f5b67ce1a7ea1fc6fdf6e3))
- *(chat)* Keep the transcript on its end as the bottom grows ([f49e533](https://github.com/arafatamim/Ferngeist/commit/f49e533a889bc25658b02072d9d311945f13dc66))
- *(chat)* Resume following after a hand scroll to the bottom ([2fed4da](https://github.com/arafatamim/Ferngeist/commit/2fed4dacc8da5fab8adc468d7fffc0a8a546f44a))
- *(chat)* Take the session title from the agent, never from the prompt ([d2e18e5](https://github.com/arafatamim/Ferngeist/commit/d2e18e5f19330f666940314943c3fdca46823b0b))
- *(chat)* Render the reasoning row with the streaming indicator ([427298c](https://github.com/arafatamim/Ferngeist/commit/427298cb9a630511f858beab8a03fc4f9205c7f9))
- *(app)* Hold the launch splash only on a cold start ([2616521](https://github.com/arafatamim/Ferngeist/commit/2616521a77a48edee12cdade14b9787d6787c0e4))
- *(workspace)* Keep the open chat across a window class change ([0ce920f](https://github.com/arafatamim/Ferngeist/commit/0ce920fba0bd2680ba9e50e22e678e82e187a73c))
- *(acp)* Announce Connected only after the reconnect handshake ([bb44391](https://github.com/arafatamim/Ferngeist/commit/bb44391c581a1a74cd0e57a64f1bd1aa1b42fdf3))
- *(acp)* Restore sessions before announcing Connected ([5c82279](https://github.com/arafatamim/Ferngeist/commit/5c82279186579bc51987ab94150e24f49c04284a))
- *(chat)* Key pending echoes so the list does not restart ([ccdd403](https://github.com/arafatamim/Ferngeist/commit/ccdd4031b43eb5cdde89504c65235610cda064c6))
- *(chat)* Window the transcript by id, not by size ([9371403](https://github.com/arafatamim/Ferngeist/commit/93714035ccd53224cdbb55d1ce9099ac5479bdb5))
- *(chat)* Chase the streaming end in short settles ([8e37072](https://github.com/arafatamim/Ferngeist/commit/8e37072b76b45142f78baa5d2c01cc6efc900c3e))
- *(chat)* Cap agent text and switch long elicitation options to radio choices ([b56ce96](https://github.com/arafatamim/Ferngeist/commit/b56ce96ff71868e48f8682da79a587bbecf7da8e))
- *(chat)* Keep the switcher bubble square beside a wide composer ([49e1ce8](https://github.com/arafatamim/Ferngeist/commit/49e1ce802cf4f0054c71309beb5d4da20b58180e))
- *(session)* Survive switching away, transport resets and backgrounding ([0ade0a7](https://github.com/arafatamim/Ferngeist/commit/0ade0a7120cb416bfbef38b315744b229507e195))
- *(acp)* End a turn the gateway reports finished on an earlier connection ([fbf7f1c](https://github.com/arafatamim/Ferngeist/commit/fbf7f1c40608ed58d8077226cb6959417e4e09cd))

### Performance

- *(acp-bridge)* Stop rebuilding accumulated text on every chunk ([ee44d1d](https://github.com/arafatamim/Ferngeist/commit/ee44d1d4868905ca8d74d607496e0b3deed14c74))
- *(acp)* Build reducer output with persistent collections ([03b04f7](https://github.com/arafatamim/Ferngeist/commit/03b04f7f5fb798f2a679e48f4532a365e115cdb2))
- *(app)* Use the Android HTTP engine and bump to 0.16.0-alpha8 ([be046ba](https://github.com/arafatamim/Ferngeist/commit/be046ba0ac7e120c8cef512e99568cf544cfd9d4))
- *(chat)* Smooth session swipe and workspace pane switching ([d851102](https://github.com/arafatamim/Ferngeist/commit/d8511029d8244e9b2913df0add17b37e29d7e368))

### Refactoring

- *(acp-bridge)* Make modelSelectionEvents non-null ([e22eda8](https://github.com/arafatamim/Ferngeist/commit/e22eda83a1f54099c653f5dbf54be9d1c3fba93e))
- *(chat)* Group a turn's file changes and expose the diff stats row ([1cbe98c](https://github.com/arafatamim/Ferngeist/commit/1cbe98c5777bc9af91f3f806652d70fbf4eb7ef5))

### Release

- *(0.16.0-alpha4)* Version bump and internal-track notes ([11c3e4f](https://github.com/arafatamim/Ferngeist/commit/11c3e4fad557873b8835b106e1ff2669124d7862))
## [0.16.0-alpha3] - 2026-09-27

### Features

- *(acp)* Resume sessions on agents that advertise session/resume ([9d17185](https://github.com/arafatamim/Ferngeist/commit/9d171859a5d59e937745a21793c381abb57e8dcd))
- *(chat)* Localize facade error messages at display ([e5be13e](https://github.com/arafatamim/Ferngeist/commit/e5be13eae981ee694f43dec33adf4328b1be0bd6))
- *(chat)* Mark a transcript resumed without history replay ([9fee4f3](https://github.com/arafatamim/Ferngeist/commit/9fee4f3ad046ba461b4147526e356ca23e0873df))

### Fixes

- *(acp)* Surface held-session attach as an actionable error ([6af6580](https://github.com/arafatamim/Ferngeist/commit/6af6580ffe6dde771a04590c3f39d3782f09d41c))
- *(acp)* Stop reading a generic params code as a held session ([ca61369](https://github.com/arafatamim/Ferngeist/commit/ca61369d9b88540a3c72186dc189f100b9d5bead))
- *(acp)* Stop reading a failed session listing as an empty one ([c655a95](https://github.com/arafatamim/Ferngeist/commit/c655a9535869d6e67f2687e7d8eed645b3694d81))
- *(acp)* Propagate cancel and config failures instead of swallowing them ([8ecae62](https://github.com/arafatamim/Ferngeist/commit/8ecae624244b0ab786e2c8e4f0b99aeb0572b6d3))
- *(acp)* Require a measured transport state before recovering a load ([b1d0a55](https://github.com/arafatamim/Ferngeist/commit/b1d0a55f3191a1e9d78eff61280d2be900b3809b))
- *(acp)* Classify cancellation from the type, not the wording ([de0715e](https://github.com/arafatamim/Ferngeist/commit/de0715ea6ae0625b46f3c25fdb99d468ccd925a9))
- *(gateway)* Read a held runtime lease from the response body ([0b7769e](https://github.com/arafatamim/Ferngeist/commit/0b7769e005668c2364ec631ce2e8945f46ee4bd9))
- *(auth)* Refuse to overwrite env values that cannot be read ([2a5945b](https://github.com/arafatamim/Ferngeist/commit/2a5945b050c8992ac945c1a68d329cd059b9a97a))

### Refactoring

- *(acp)* Collapse session attach onto one RPC decision point ([ea91f3a](https://github.com/arafatamim/Ferngeist/commit/ea91f3aca42c1a452d92da037ef843106c830301))

## [0.16.0-alpha2] - 2026-09-26

### Features

- *(chat)* Enable session-switch drag from composer pill ([447f0ea](https://github.com/arafatamim/Ferngeist/commit/447f0ea0d16638bd6eeeea5f6239ecc39e80f438))
- *(sessionlist)* Add disconnect/delete menu with lease reconciliation ([7bdbdc3](https://github.com/arafatamim/Ferngeist/commit/7bdbdc3f27a945b894c47de13c20bd56b9599d4f))
- *(chat)* Shimmer skeleton for cold open, larger status sunny with transition ([778253d](https://github.com/arafatamim/Ferngeist/commit/778253dbc07905c26dedf21efe02ba84f70a7e44))
- *(serverlist)* Prewarm sessions during connect for single loading state ([6403f5f](https://github.com/arafatamim/Ferngeist/commit/6403f5f6eb1e1702f145049b75a63fa0ed6f78e0))

### Fixes

- *(loading)* Bound REST timeouts and unblock cached screens ([18ef625](https://github.com/arafatamim/Ferngeist/commit/18ef625e56bb6e0ef880eb9a7afcf7bd1453d254))

## [0.16.0-alpha] - 2026-09-22

### Features

- *(chat)* Add live-session switcher with drag switch and coach-mark hint ([f7787b2](https://github.com/arafatamim/Ferngeist/commit/f7787b2698ed2772bb7c5b15412bb666297af7fa))

### Fixes

- *(sessionlist)* Filter by the working directory without the agent ([58b2077](https://github.com/arafatamim/Ferngeist/commit/58b2077261c72c2a9a42f471b3aa5d6c3dde1fd9))

## [0.15.0] - 2026-09-17

### Documentation

- *(readme)* Show fresh phone and tablet screenshots with labels ([25663c2](https://github.com/arafatamim/Ferngeist/commit/25663c29adfa7bb3c4f7baed8f22d7dc0dc02c6c))

### Features

- *(serverlist)* Create and delete custom agents on a paired gateway ([0998566](https://github.com/arafatamim/Ferngeist/commit/0998566f50df3893ef71f26cda7e59ce530ed9ac))

### Fixes

- *(ui)* Show the multi-pane workspace on 7-inch landscape ([f94f9d5](https://github.com/arafatamim/Ferngeist/commit/f94f9d55335316b33cdd771cf536be1ed7eeacd5))
- *(gateway)* Retry a held runtime lease as an isolated spawn ([552ce36](https://github.com/arafatamim/Ferngeist/commit/552ce36a0eae507edb2f803e36818d573744432a))

## [0.14.1] - 2026-09-15

### Features

- *(build)* Add google/foss distribution flavors for F-Droid ([caeca3b](https://github.com/arafatamim/Ferngeist/commit/caeca3b1cc24d4df95f075e6dc5d12b45e66713f0))

### Docs

- *(metadata)* Add F-Droid listing icon and screenshots ([d02648d](https://github.com/arafatamim/Ferngeist/commit/d02648d7c5e932d8092ba0c436468d74858742b9))

## [0.14.0] - 2026-09-13

### Features

- *(app)* Add the pinned-chat three-pane workspace ([815db4d](https://github.com/arafatamim/Ferngeist/commit/815db4d425d1853274495fe6a29c9d8129ae2525))

### Fixes

- *(ui)* Make the existing screens width-aware ([ff07b52](https://github.com/arafatamim/Ferngeist/commit/ff07b52900e1fa0b32215b0015c0898605243720))
- *(app)* Bundle Roboto Mono so the theme font never falls back ([ea8a06a](https://github.com/arafatamim/Ferngeist/commit/ea8a06a4f4df3119a5f448a5735e1f1e2c8edcf3))

### Maintenance

- Add the adaptive dependencies and the window size seam ([06c4623](https://github.com/arafatamim/Ferngeist/commit/06c462370997d1cf29ef799df28beb5c3e7717a6))

## [0.13.0] - 2026-09-12

### Features

- *(sessionlist)* Cwd suggestions bottom sheet ([f93b1a2](https://github.com/arafatamim/Ferngeist/commit/f93b1a25f86f3ee19ec10f573423ca53cad57680))
- *(gateway)* Thread a fresh-process flag through runtime launch ([5232c90](https://github.com/arafatamim/Ferngeist/commit/5232c9052c3c46b3a6d29462dcf78532ff75f5be))
- *(acp,chat)* Add the chat connection hub with a hot-connection cap ([686bceb](https://github.com/arafatamim/Ferngeist/commit/686bcebe6f95133b4e24f8c0a56c6ce157d575df))
- *(db)* Record the gateway session id on session rows (v15) ([03f0b2f](https://github.com/arafatamim/Ferngeist/commit/03f0b2ffe40e326940c11c00a87a91a0e8694cc3))
- *(chat,sessionlist)* Track open chats and live gateway sessions ([4fa34a1](https://github.com/arafatamim/Ferngeist/commit/4fa34a117323f5ffdc8a87d6ac1ec8c3bfc77d80))
- *(chat)* Fade messages into the surface beneath the composer ([6f1df5f](https://github.com/arafatamim/Ferngeist/commit/6f1df5feb8e8ab73117c780d8af6d01ca414d60d))

### Fixes

- *(acp-bridge)* Catch exceptions from SDK session close and bridge operations ([91f3c29](https://github.com/arafatamim/Ferngeist/commit/91f3c29c85a9d566535f923ee708a5617fd62641))
- *(push)* Survive an expired gateway credential at startup ([4e16dcd](https://github.com/arafatamim/Ferngeist/commit/4e16dcd6019cef09adbc050a679fe91e753f4288))
- *(sessionlist,acp)* Session-list review fixes ([fb41eaf](https://github.com/arafatamim/Ferngeist/commit/fb41eaf5b6f237573ff48f2ed57363aa62004630))
- *(acp,gateway)* Pin each chat to its own gateway runtime ([f675c44](https://github.com/arafatamim/Ferngeist/commit/f675c44b7c66304bfc66fb66156114ab68a24ccf))
- *(acp,chat)* Keep turns, permissions and queued prompts alive when the screen dies ([35bb715](https://github.com/arafatamim/Ferngeist/commit/35bb715c7b5265a261eaef944a83680580243309))
- *(acp,chat)* End interrupted turns and keep queued prompts durable ([2cc745b](https://github.com/arafatamim/Ferngeist/commit/2cc745bce919b6c3e69e011af4f92ad313368520))
- *(acp,db)* Count leased sessions at the device cap, claim atomically ([8783182](https://github.com/arafatamim/Ferngeist/commit/87831820e1847c493de77b02c6a5726a6cfc7204))
- *(gateway)* Propagate launch cancellation, and stop lying in the notification ([d720952](https://github.com/arafatamim/Ferngeist/commit/d720952f7448f0926ce98d7b0f7024667a6c2056))
- *(chat)* Make the model picker search actually filter ([8aa92ad](https://github.com/arafatamim/Ferngeist/commit/8aa92adc45dd5e6ae6017e11701f4fbe70aa1bb2))
- *(acp)* Keep the context reading across a session reload ([da1dbad](https://github.com/arafatamim/Ferngeist/commit/da1dbadf247f08433e1ea2380fc20cc05e108956))
- *(chat,acp)* Surface a failed session load instead of a stuck spinner ([e65fd31](https://github.com/arafatamim/Ferngeist/commit/e65fd31a21cba81c8da6ea7def5682f72f295863))
- *(acp)* Keep the reconnect loop alive across its own attempt ([ae2806d](https://github.com/arafatamim/Ferngeist/commit/ae2806d9d7dd315f49fdda8d6fa492eaec4dc341))
- *(chat)* Drive the app bar from the message list, not the composer ([6058989](https://github.com/arafatamim/Ferngeist/commit/60589890df46abf63c4aa4f1dc11e924ba381535))
- *(chat)* Size the composer to its content and animate it with the theme ([4003884](https://github.com/arafatamim/Ferngeist/commit/4003884908a584d0f88018df8e55f918597cd43b))

### Maintenance

- *(build)* Treat compiler warnings as errors and fix all findings ([480363c](https://github.com/arafatamim/Ferngeist/commit/480363cf1b2c011c2c1349edc51c64a4a2cf8377))
- *(lint)* Fix every warning and enforce warningsAsErrors ([47fce9a](https://github.com/arafatamim/Ferngeist/commit/47fce9abe42407896493c378f914afec2c8f7c3d))
- Run the gate on master, and fetch tags so the version provider resolves ([c24c85e](https://github.com/arafatamim/Ferngeist/commit/c24c85e071ed3320617c5184250b79563170b9b1))

### Refactoring

- *(acp)* Give every owner its own connection manager ([61680b9](https://github.com/arafatamim/Ferngeist/commit/61680b933c823fd8076932359205581c0568237c))
- *(acp,model)* Make the hub own presence, and dedupe the session load paths ([2871d98](https://github.com/arafatamim/Ferngeist/commit/2871d98ffbd832e65b8255777090983e7d5d99bf))
- *(acp)* Pull listing and teardown behind the hub seam ([ae722b3](https://github.com/arafatamim/Ferngeist/commit/ae722b370ec96002f94dd78fd6669a1b585ac43a))

### Testing

- *(db)* Cover the 14->15 migration with exported Room schemas ([0b88849](https://github.com/arafatamim/Ferngeist/commit/0b888490832e1c3e62d6926d365eebceb849d02c))

## [0.12.2] - 2026-08-20

### Features

- *(chat)* Animate git status pill appearance in top bar ([20e6621](https://github.com/arafatamim/Ferngeist/commit/20e6621d28adb87d7711669099301eea04547888))

### Fixes

- *(sessionlist)* Reconnect before creating a session while disconnected ([1b23452](https://github.com/arafatamim/Ferngeist/commit/1b234527122ac3fc024b23963dc5542be630cb71))
- *(acp)* Survive network failures in reconnect loop ([b913a0d](https://github.com/arafatamim/Ferngeist/commit/b913a0de9e0f048003a938365f0e6b111c862f1b))
- *(ui)* VerySunny connecting indicator with outline contrast ([f067ff7](https://github.com/arafatamim/Ferngeist/commit/f067ff77c94d238b3f741aedc7cd1c84b07eae75))

### Refactoring

- *(gateway)* Share gateway launch and connect/initialize across VMs ([0fa5bae](https://github.com/arafatamim/Ferngeist/commit/0fa5bae6ad5a43f9b97180d87262398d41eb539f))

## [0.12.1] - 2026-08-19

### Fixes

- *(sessionlist)* Prompt for cwd instead of defaulting to root when creating session ([51b4e94](https://github.com/arafatamim/Ferngeist/commit/51b4e94b5fa40f66f756b253d96ed3e642baeeae))
- *(chat)* Retry git status fetch when session becomes ready ([b45f440](https://github.com/arafatamim/Ferngeist/commit/b45f4406e97687e5122aae5793498740840ff9a9))

### Maintenance

- *(release)* Add 0.12.0 Play changelog ([6e7e636](https://github.com/arafatamim/Ferngeist/commit/6e7e6360628f41d08120ae468bbaac3557cb592a))
- *(lint)* Enforce ktlint gate and fix all findings ([e2d9b5e](https://github.com/arafatamim/Ferngeist/commit/e2d9b5e51ef674a2eff46f5fd3e11e279bc78faf))

## [0.12.0] - 2026-08-18

### Features

- *(ui)* Expressive error and empty states ([815940f](https://github.com/arafatamim/Ferngeist/commit/815940fad965401d179c8bcd6e068c11ba76f195))
- *(serverlist)* Non-collapsing app bar and sheet gesture fixes ([5df9dba](https://github.com/arafatamim/Ferngeist/commit/5df9dba01dff88b590592b83b19f9604b1a28010))
- *(serverlist)* Fading edges on agents list ([a71fc55](https://github.com/arafatamim/Ferngeist/commit/a71fc555dbbbe5cd89cdc5ceb8189de2db3753a7))
- *(acp)* Exponential backoff with full jitter for reconnects ([b721b9b](https://github.com/arafatamim/Ferngeist/commit/b721b9bbedc151cb9f6e95c32f44a46f8bef76d0))

### Fixes

- *(chat)* Hide scroll-to-bottom button when at bottom ([86b01e8](https://github.com/arafatamim/Ferngeist/commit/86b01e849a9ecd6f9e121b661539ea9a2e2a618d))
- *(serverlist)* Animate backdrop sheet collapse on release ([f73f2fe](https://github.com/arafatamim/Ferngeist/commit/f73f2fe26ebfebfa0bf2c0ad59a39938bce6792a))
- *(serverlist)* Stabilize shared title transition on sheet restore ([883969b](https://github.com/arafatamim/Ferngeist/commit/883969bab934623093249b27d8c8ce86e12805d1))
- *(chat)* Recompute derived picker selections on id change ([11322e6](https://github.com/arafatamim/Ferngeist/commit/11322e683457ecd304136a25fbbcd08f78dbf59b))
- *(ui)* Shared EdgeFade with quintic curve, scroll-coupled bands, theme fade color ([ccc4233](https://github.com/arafatamim/Ferngeist/commit/ccc4233ea57b235aed062ad24b851a78f6ec7294))
- *(chat,serverlist)* Tweak sheet drag behaviour and UI colours ([3bec892](https://github.com/arafatamim/Ferngeist/commit/3bec8928f820920756fc68ca03be797e9548fe8e))
- *(chat)* Render submodule git status entries as directories ([86b864f](https://github.com/arafatamim/Ferngeist/commit/86b864f2c970ac97f48e4af9398d68a5c8f59878))
- *(sessionlist)* Atomically replace sessions to stop refresh flicker ([0c1f283](https://github.com/arafatamim/Ferngeist/commit/0c1f2832b9f6bebf8617e27381da84e966c31c5b))
- *(chat)* Exact 2/2 diff blocks for balanced changes ([dfac03a](https://github.com/arafatamim/Ferngeist/commit/dfac03a6f27d79ea07f0b8a6d21b9fa5f4505603))
- *(chat)* Localize git status and load-earlier strings ([34c6039](https://github.com/arafatamim/Ferngeist/commit/34c6039fecff2a28e6645b9997a03905fe78b622))
- *(chat)* Let message list extend edge-to-edge behind nav bar ([cc195e6](https://github.com/arafatamim/Ferngeist/commit/cc195e621eed56b78dce274267bd3f99caa5e79b))

### Maintenance

- *(release)* Add 0.11.0 Play changelog ([0702da5](https://github.com/arafatamim/Ferngeist/commit/0702da5a7456c093dc24520d8e33e744ea8d3638))
- Run full lint, detekt, and ktlint pass ([5381929](https://github.com/arafatamim/Ferngeist/commit/53819295f3d15d379f03109678d6630b7b89b7e1))
- Make detekt and ktlint mandatory in check, CI, and pre-commit ([b3698f6](https://github.com/arafatamim/Ferngeist/commit/b3698f6fab37a89ea5ad7654b6cf1f5c9fbb7e17))

### Refactoring

- Resolve all detekt findings across modules ([7cbd70c](https://github.com/arafatamim/Ferngeist/commit/7cbd70c0e4f5fa9d970189100647e0f048ac2b1a))

## [0.11.0] - 2026-08-07

### Features

- *(push)* Coalesce progress pushes per session ([ec77565](https://github.com/arafatamim/Ferngeist/commit/ec77565d06e82ad3df9342fe84d515fe4df8881a))
- *(chat)* Rich tooltip on session title long-press ([bf2f1e2](https://github.com/arafatamim/Ferngeist/commit/bf2f1e2216c7157ef2732fa347058c5a2295b0fa))
- *(chat)* Gateway workspace diff indicator and git status sheet ([0d60b67](https://github.com/arafatamim/Ferngeist/commit/0d60b671ee34b8d3eb054fec1ffdd72a0ab5f57a))
- *(gateway-client)* Surface expired gateway credentials for re-pairing ([dd0a2d0](https://github.com/arafatamim/Ferngeist/commit/dd0a2d0d96df6a7a860bb9f4860292483f0202cc))
- *(gateway-client)* Gate gateway access on protocol version ([9b21cb7](https://github.com/arafatamim/Ferngeist/commit/9b21cb7c1b2c997bc534fbcc7c2c29b7903bd018))
- *(chat)* Per-file git diff viewer with hunk rendering ([4380cef](https://github.com/arafatamim/Ferngeist/commit/4380cef6a537108156be2bbfe007541e638445ce))

### Fixes

- *(acp)* Refuse blank cwd on session creation, stop sending / ([fb50057](https://github.com/arafatamim/Ferngeist/commit/fb50057487273de137d10b3cc969751554959196))
- *(acp-bridge)* Fast-path already-loaded detection via INVALID_PARAMS code ([194734e](https://github.com/arafatamim/Ferngeist/commit/194734e29a708087d6dcb48b7757fd5eea1d9424))
- *(acp-bridge)* Re-authenticate with stored method after reconnect ([7319bfb](https://github.com/arafatamim/Ferngeist/commit/7319bfb45c53ac7f7af2851173f4ac0b040bfafe))
- *(acp-bridge)* Gate SDK session close on session/close capability ([69f6267](https://github.com/arafatamim/Ferngeist/commit/69f6267aa0b40f35726ef4747b2aaffc205799d4))
- *(chat)* Keep title tooltip on-screen on narrow devices ([6fbf370](https://github.com/arafatamim/Ferngeist/commit/6fbf370c1230f9a90fcf9c3c4196eb14954e2968))
- *(chat)* Render tool-call diffs without nested scroll container ([c4dba44](https://github.com/arafatamim/Ferngeist/commit/c4dba44e2040a40fec7447af4da5647b09a1a882))

### Maintenance

- *(deps)* Upgrade all dependencies to latest versions ([30f72e4](https://github.com/arafatamim/Ferngeist/commit/30f72e434831bab98cd834f47cf2c6093d1fd99c))

## [0.10.1] - 2026-07-20

### Fixes

- *(acp)* Reconnect on connectivity return so the offline queue drains ([2c07048](https://github.com/arafatamim/Ferngeist/commit/2c070483165c75ba01caded45f92c6859e7da5bd))
- *(chat)* Trigger a reconnect when queuing a prompt while offline ([b21d033](https://github.com/arafatamim/Ferngeist/commit/b21d03307f36155aff61b8cca048b54f1a3ae7fe))

### Maintenance

- Gitignore signing keystores (\*.jks, \*.keystore) ([2a29d5b](https://github.com/arafatamim/Ferngeist/commit/2a29d5b6bdd910b31b8f71be21957d60bb8d07a4))
- *(release)* Add fastlane Play Store deployment ([f13625c](https://github.com/arafatamim/Ferngeist/commit/f13625cdc65e34402b9993169f91d45a46b6961e))

## [0.7.0] - 2026-05-18

### Features

- *(sessionlist)* Add recent working directory history ([f9aa6b3](https://github.com/arafatamim/Ferngeist/commit/f9aa6b3eea7753681c5c452eba002a7b5b1cd24b))
- *(chat)* Migrate tool call output to structured content + extract renderers ([ee0a304](https://github.com/arafatamim/Ferngeist/commit/ee0a3041345292d940ed8030b0ef85f7684fd2fe))
- *(chat)* Enhance tool call display with raw input and selectable content ([7b2436c](https://github.com/arafatamim/Ferngeist/commit/7b2436c915c812e5a43b96bf05725b1bb1bd1abc))
- *(chat)* Introduce unified diff rendering for tool call content ([66cdb78](https://github.com/arafatamim/Ferngeist/commit/66cdb78bf894c32babb06be0a5c49cbb1111c304))
- *(chat)* Add context usage indicator ([f2ddc65](https://github.com/arafatamim/Ferngeist/commit/f2ddc65caeae19740c329d01bb616530196bde66))
- *(chat)* Add tooltips to mode selection menu ([69cc71f](https://github.com/arafatamim/Ferngeist/commit/69cc71f5db22881d1e7889069c5783522cfa5ed3))
- *(chat)* Replace configuration picker dialog with bottom sheet ([e7a69ca](https://github.com/arafatamim/Ferngeist/commit/e7a69cae3fd9a4f3c2d1ff3225100f51383911bd))
- *(chat)* Upgrade command handling and unify selection UI ([7bd3be4](https://github.com/arafatamim/Ferngeist/commit/7bd3be49cfd4c45d2627b638db35f0818cf635d0))
- *(chat)* Implement recent selections for commands and configuration options ([ca561bc](https://github.com/arafatamim/Ferngeist/commit/ca561bcbdb8b5dd27dd90cdb3b0e70311798a412))
- Implement comprehensive localization and externalize strings ([beacbd2](https://github.com/arafatamim/Ferngeist/commit/beacbd2cfedc5480dcba336d00a91edbdc99e6ea))
- *(i18n)* Add Spanish (es) translations ([d029d9a](https://github.com/arafatamim/Ferngeist/commit/d029d9abb759c872d63746870eeca4ce3ca72b10))
- *(i18n)* Add Portuguese (pt) translations ([a4340b2](https://github.com/arafatamim/Ferngeist/commit/a4340b20456f19db24b4ea4afc8702c75ae0aa77))
- *(i18n)* Add Bengali (bn) translations ([701260f](https://github.com/arafatamim/Ferngeist/commit/701260f2bb6bd0a27834a955d948a083823ef98c))
- *(i18n)* Add Russian (ru) translation strings ([09a2b5c](https://github.com/arafatamim/Ferngeist/commit/09a2b5c2c7be0d9e506235d024fbd91e5a9a363f))
- *(i18n)* Introduce Simplified Chinese (zh) translations ([a78cb91](https://github.com/arafatamim/Ferngeist/commit/a78cb9111922a2cc61d35420b0dcdaf100ce62a1))

### Fixes

- *(service)* Use remote messaging foreground type ([be423c4](https://github.com/arafatamim/Ferngeist/commit/be423c4966c8455b838689e9b7f63a1b1f175778))
- *(chat)* Remove fallback session creation on load timeout ([4374810](https://github.com/arafatamim/Ferngeist/commit/4374810e2eafeb75f4d465733ebf4566af47c8ea))

### Maintenance

- *(release)* Add CHANGELOG and update cliff config ([765a1a1](https://github.com/arafatamim/Ferngeist/commit/765a1a1b3663949c833d2c35a8cc3a7ce7afee5e))
- *(onboarding)* Remove leftover onboarding files ([19cbe9f](https://github.com/arafatamim/Ferngeist/commit/19cbe9fa9b414088bf653593e6f9aba7b1ff8368))

### Refactoring

- *(prefs)* Use DataStore for preferences ([0ddaa48](https://github.com/arafatamim/Ferngeist/commit/0ddaa48953c2e857b5b925dd5bda4d062f79fac6))
- *(chat)* Use SDK ToolKind and ToolCallStatus enums instead of strings ([4d2bdd6](https://github.com/arafatamim/Ferngeist/commit/4d2bdd64a2dd0b355dca3d18ae05ba6038fa0359))
- *(acp-bridge)* Generalize session usage cost tracking ([224fc3a](https://github.com/arafatamim/Ferngeist/commit/224fc3a1ca9ec568c044aea685f6cfd8ad70610e))
- *(chat)* Robust auto-scroll system and UI component refactor ([e233af3](https://github.com/arafatamim/Ferngeist/commit/e233af356ec794f4d021754c11630d3c329c9334))

## [0.6.0] - 2026-05-10

### Documentation

- Update README and privacy policy ([aead1b3](https://github.com/arafatamim/Ferngeist/commit/aead1b3cdb0ac179043eca24407a0596b7c72ad2))
- Adjust download button width ([0d4924f](https://github.com/arafatamim/Ferngeist/commit/0d4924fdb9a94089e936f836f7575f8c8dda3a52))
- *(readme)* Add Keep Android Open banner ([78fc41e](https://github.com/arafatamim/Ferngeist/commit/78fc41e963a0c36175aa1ccc70bb3edc7d87d766))
- Update privacy policy ([7949f69](https://github.com/arafatamim/Ferngeist/commit/7949f696245a21d5a068b0a2cd696cbfee246c38))

### Features

- Reuse existing agent bridge when tapping connected agent ([d843b39](https://github.com/arafatamim/Ferngeist/commit/d843b391a0e35967d87fbbd8877b1608814df87d))
- *(ui)* Improve chat screen top bar ([5198bd0](https://github.com/arafatamim/Ferngeist/commit/5198bd001ebc29c39e72073df5d0badc3e7c3c74))
- *(chat)* Top bar ui improvements ([e4cfcdb](https://github.com/arafatamim/Ferngeist/commit/e4cfcdbbd8bd4c78418940be6106a3380b069d15))
- *(nav)* Centralize battery dialog and request notifications ([ddd31f0](https://github.com/arafatamim/Ferngeist/commit/ddd31f04d388db5dc8629e66b61ee07d2ad01870))
- *(ui)* Add shared server name transition ([0d48828](https://github.com/arafatamim/Ferngeist/commit/0d48828bb28157d4943961af5a570e3b5d3d2d91))

### Fixes

- *(ui)* Minor UI tweaks ([c74a663](https://github.com/arafatamim/Ferngeist/commit/c74a663d5163f9b145b1bf6f58f4c1148330f134))
- *(ui)* Minor UI tweaks ([2e820c2](https://github.com/arafatamim/Ferngeist/commit/2e820c2b3c11b76615de27192780280c5ff49063))
- *(gateway)* Return original on refresh error ([b59e7ce](https://github.com/arafatamim/Ferngeist/commit/b59e7ce46795fa534cdee9f4c91502580549f0e7))

### Maintenance

- *(app)* Fix version derivation ([38682bf](https://github.com/arafatamim/Ferngeist/commit/38682bfafba4bb7d89b3fae9d2d03e0804370a70))
- Remove gateway source from repo ([2a256a8](https://github.com/arafatamim/Ferngeist/commit/2a256a85089469ee91fcf5203392af4d72e820e5))
- Delete gateway release github workflow file ([bac5c67](https://github.com/arafatamim/Ferngeist/commit/bac5c67ad71896fc8ee62990b283acf5b39037da))
- Apply code style, upgrades, and security fixes ([0b25001](https://github.com/arafatamim/Ferngeist/commit/0b25001131a40528bfc07c66dba010a1374d1e52))

### Refactoring

- *(gateway)* Rename helper to gateway ([4f4a2bf](https://github.com/arafatamim/Ferngeist/commit/4f4a2bfe962695e053a733f54bc3e5e771801160))
- *(crypto)* Migrate deprecated credential encryption to AndroidKeyStore-backed AES-GCM ([a8287df](https://github.com/arafatamim/Ferngeist/commit/a8287df5ab2cc537d16c0b9ec3c35223a65f58b3))
- *(core.common)* Extract ConnectionStatusPill ([76cfb69](https://github.com/arafatamim/Ferngeist/commit/76cfb69d2b3c99b808ade669e6e3c7aa8c2300d0))

## [0.5.0] - 2026-04-19

### Features

- *(serverlist)* Add agent launch disclaimer and consent dialog ([524afe3](https://github.com/arafatamim/Ferngeist/commit/524afe3c8c0b453047fe5722575a07c1d9826fc8))

### Maintenance

- *(release)* Derive app version from git tag ([ea32cc3](https://github.com/arafatamim/Ferngeist/commit/ea32cc3639ed67451d926f73e700b881260a09e3))

## [0.4.0] - 2026-04-18

### Documentation

- *(readme)* Update README ([df9c0b8](https://github.com/arafatamim/Ferngeist/commit/df9c0b894c29ac3b2af121d1720018884c9115e0))
- *(privacy)* Add privacy policy ([9d05305](https://github.com/arafatamim/Ferngeist/commit/9d05305d0252734c2774afa7286c1a6f4181b358))

### Features

- *(serverlist)* Start pairing if no challenge ([23f7413](https://github.com/arafatamim/Ferngeist/commit/23f74137003eca3b06ee9bbb81f5a080244be836))
- *(app)* Add ML Kit barcode scanner config ([7176284](https://github.com/arafatamim/Ferngeist/commit/7176284e4b6a945ab7817f9b936e95cff9505468))
- *(serverlist)* Improve pairing and QR flow ([6fa29b2](https://github.com/arafatamim/Ferngeist/commit/6fa29b2500a808f18b5c89da37b55dda7684f3c5))
- *(desktop-helper)* Implement ACP JSON-RPC mock agent and fix resolveCommandPath ([b690420](https://github.com/arafatamim/Ferngeist/commit/b690420918a409434783e498ff430e00f82ba407))

### Fixes

- *(service)* Open battery settings intent ([ef660d0](https://github.com/arafatamim/Ferngeist/commit/ef660d074d8e4e3447975cda3225164e943426a7))

### Maintenance

- *(helper-release)* Require and resolve release tag ([c8526ff](https://github.com/arafatamim/Ferngeist/commit/c8526ffb8f691321d59223e5e518381b873c7b4d))
- *(docs)* Setup workflow for docs ([e569b8c](https://github.com/arafatamim/Ferngeist/commit/e569b8c1cd7ac096fb4b2087ed77028a6b69d061))
- *(docs)* Add Jekyll build and restrict trigger ([acd7412](https://github.com/arafatamim/Ferngeist/commit/acd7412f56b7788e4114341a361f227723cfe13d))
- *(release)* Create tag-triggered releases and fix Jekyll builds ([70e6117](https://github.com/arafatamim/Ferngeist/commit/70e611775010a8a79432e313e07029afab1f423a))

## [0.3.0] - 2026-04-12

### Documentation

- *(desktop-helper)* Add portable distribution ([ad89fde](https://github.com/arafatamim/Ferngeist/commit/ad89fde913e1ab52a55b8c4f56c61cb5fe9191b4))

### Feat

- *(desktop-helper)* Render pairing QR config ([53c73c7](https://github.com/arafatamim/Ferngeist/commit/53c73c727f8aa67c25d6df45dee3068092408afa))

### Features

- *(desktop-helper)* Add initial local pairing and ACP bridge ([f9338a3](https://github.com/arafatamim/Ferngeist/commit/f9338a3a4df8849390307fdad7a6a95113b34d24))
- *(desktop-helper)* Rely on ACP registry launch metadata ([2a86491](https://github.com/arafatamim/Ferngeist/commit/2a8649102349549caea9c220972ea990d75ab2cb))
- *(serverlist)* Add desktop companion pairing and launch flow ([f623fe2](https://github.com/arafatamim/Ferngeist/commit/f623fe2d572d44297495398177e81f2ca7008f3f))
- *(desktop-helper)* Restart runtimes with env overrides ([fab37b4](https://github.com/arafatamim/Ferngeist/commit/fab37b4fd0eecf4c29cf918baec2bcedb4ddc024))
- *(auth)* Gate ACP auth on session requests ([b3eefaa](https://github.com/arafatamim/Ferngeist/commit/b3eefaa86b176edc5895f253ff85984e7b30eddf))
- *(serverlist)* Change FAB icon and label ([d8aaee6](https://github.com/arafatamim/Ferngeist/commit/d8aaee696c01c570076c709f3ff2b138c48d5aa6))
- *(desktop-helper)* Add ferngeist CLI and admin client ([160b305](https://github.com/arafatamim/Ferngeist/commit/160b3051801e92fc657fe5da79400f2f9b68f1af))
- *(desktop-helper)* Add daemon status command ([91ffb8a](https://github.com/arafatamim/Ferngeist/commit/91ffb8a6057085737f2b06acec62545bb2c55a0e))
- *(desktop-helper)* Add hint when daemon is down ([73da99c](https://github.com/arafatamim/Ferngeist/commit/73da99cad01ca658f37d7537f90f65f977129f70))
- *(desktop-helper)* Improve lifecycle and add tests ([d08e616](https://github.com/arafatamim/Ferngeist/commit/d08e616dcdae5eeaa543ec5f26a603f730307f82))
- *(desktop-helper)* Add pairing security config ([04e5724](https://github.com/arafatamim/Ferngeist/commit/04e57245babe72259e0d584ab5aa8871dab3fafd))
- *(helper-auth)* Add PoP auth and credential refresh plumbing ([416012d](https://github.com/arafatamim/Ferngeist/commit/416012d953e098545bc5e91c78863c1ecedb99d8))
- *(serverlist-pairing)* Align pairing payload flow with challengeId contract ([5d0d8c9](https://github.com/arafatamim/Ferngeist/commit/5d0d8c929a8f73f2a4b1273a1c4c32a0446b3aa6))
- *(serverlist-ui)* Add warning for insecure ws selection ([636fbba](https://github.com/arafatamim/Ferngeist/commit/636fbbafe449193068ee3178254dd13c1aa51ff9))
- *(desktop-helper)* Add service management for Linux ([3ff3f69](https://github.com/arafatamim/Ferngeist/commit/3ff3f696ed6e9bfa83637f383bb217f6c96ba213))
- *(desktop-helper)* Add Windows service manager implementation ([04eda89](https://github.com/arafatamim/Ferngeist/commit/04eda89819ca42ed66c2e8ea7fc399538e6ac99a))
- *(desktop-helper)* Add daemon install host and public URL options ([dc256be](https://github.com/arafatamim/Ferngeist/commit/dc256be91be3eed436c68ccd6013d66eafd93269))
- *(app)* Add About dialog and privacy policy ([094170b](https://github.com/arafatamim/Ferngeist/commit/094170b3143160e017847895f6fddb5cd724ed65))
- *(app)* Keep active ACP connections in a foreground service ([af0557d](https://github.com/arafatamim/Ferngeist/commit/af0557d695a7fbf3453b2f59e755414e71cb0678))
- *(app)* Add disconnect action icon to connection notification ([b240bac](https://github.com/arafatamim/Ferngeist/commit/b240bacbbf7b4332cd22e65a61608b9d0a4eb9a6))
- *(app)* Prompt user to disable battery optimization for reliable background connections ([d745eaa](https://github.com/arafatamim/Ferngeist/commit/d745eaace9d42f811ba76daf5eb2e50743250c66))
- *(acp-bridge)* Add server display name ([eeed7ea](https://github.com/arafatamim/Ferngeist/commit/eeed7ea582c189c12ce51c540e9eb38facf6fc42))

### Fixes

- *(desktop-helper)* Enforce helper API contract ([db36a29](https://github.com/arafatamim/Ferngeist/commit/db36a296b3311b36c6ebd5271021e94a61007a0f))
- *(serverlist, chat)* Optimize threading and improve auto-scroll reliability ([6b93993](https://github.com/arafatamim/Ferngeist/commit/6b939932bad47c61452885700bf42cc820861777))
- *(chat)* Recover from destroyed bridge stream ([d85f8ca](https://github.com/arafatamim/Ferngeist/commit/d85f8cab4cd19d0272efaeb915c184a257a68dd3))
- *(app)* Use dedicated adaptive notification icon instead of launcher icon ([22a404c](https://github.com/arafatamim/Ferngeist/commit/22a404c9e1811de33fd002cf1a619e7b22831a54))
- *(app)* Use dedicated monochrome notification icon instead of launcher adaptive icon ([7e836b8](https://github.com/arafatamim/Ferngeist/commit/7e836b86aed162b87fc817fb41964296e9c4dc37))
- *(app)* Show agent name in connection notification once initialized ([e901c94](https://github.com/arafatamim/Ferngeist/commit/e901c946a244744551d5b93e9d011e4895402397))
- *(connection)* Keep idle helper ACP sessions alive ([07d9b23](https://github.com/arafatamim/Ferngeist/commit/07d9b23c1bea1ff1a87af11318ae247fc13f705a))
- *(database)* Encrypt credentials at rest with EncryptedSharedPreferences ([1f97e24](https://github.com/arafatamim/Ferngeist/commit/1f97e24c30aa418eaf2c93c9317783994a033ddb))

### Maintenance

- Update project dependencies and Android module configurations ([e9c0787](https://github.com/arafatamim/Ferngeist/commit/e9c0787e31fb1563f5e61e0a11a8b2b4129ac7be))
- *(desktop-helper)* Switch to coder/websocket ([fa14252](https://github.com/arafatamim/Ferngeist/commit/fa14252891f9a8760e4a764ddea515fc5536ea8e))
- *(helper-release)* Fix helper build workflow file ([e8ad560](https://github.com/arafatamim/Ferngeist/commit/e8ad560ae2dcbcb71ea70d4e0f963c0267e9623d))
- *(workflows)* Bump GitHub Actions versions ([b29cba5](https://github.com/arafatamim/Ferngeist/commit/b29cba5586bb7b79a80d8c2c477f219de5801980))
- *(release)* Bump version to 0.3.0 ([8aa3dd6](https://github.com/arafatamim/Ferngeist/commit/8aa3dd68287c8a821ea72b1b687a1997dfc0893b))

### Performance

- *(chat)* Restrict debug tracing to debug builds ([245dad5](https://github.com/arafatamim/Ferngeist/commit/245dad5b7677ddab3b15b28b7c0919047959c4ae))

### Refactoring

- *(serverlist)* Model desktop helpers as first-class targets ([2378422](https://github.com/arafatamim/Ferngeist/commit/2378422c86e8f71455f1f96b238cdd39e9f8b1d6))
- *(sessionlist)* Move cwd to per-agent session settings ([bd7f557](https://github.com/arafatamim/Ferngeist/commit/bd7f557351c400c48d6234e944b6ee0571e88f35))
- *(serverlist)* Simplify server card metadata ([39b8870](https://github.com/arafatamim/Ferngeist/commit/39b887075a00284939df7fc4e8dad339a8582dfc))
- *(serverlist)* Tighten companion agent metadata ([ec868b1](https://github.com/arafatamim/Ferngeist/commit/ec868b17ec6bdcbe2faa36ab8f712295c782405b))
- *(serverlist)* Rename helper to companion ([0755097](https://github.com/arafatamim/Ferngeist/commit/075509732e289b24541df6d34baddf1b7091dbab))
- *(ui)* Remove first-launch onboarding and improve server setup guidance ([d768a34](https://github.com/arafatamim/Ferngeist/commit/d768a349601cfee7a6d5a1c5ffa2a6b8c1b2565c))
- *(desktop-helper)* Extract daemon CLI ([0cb7a8a](https://github.com/arafatamim/Ferngeist/commit/0cb7a8a3ecf33ef417cd0860d376ce5b0f10276a))
- *(desktop-helper)* Remove helperd shim and add CLI version flag ([8ec3dd6](https://github.com/arafatamim/Ferngeist/commit/8ec3dd665e541628ff5eaec720fc7e8ac7c2acff))

## [0.2.0-beta01] - 2026-03-12

### Features

- *(app)* Update splash screen and update launcher icon ([a57e284](https://github.com/arafatamim/Ferngeist/commit/a57e28468a8fc4530405b90e583bc5ca9c9ed676))
- *(chat)* Persist sessions locally when agent listing is unsupported ([e61bda4](https://github.com/arafatamim/Ferngeist/commit/e61bda4bc072c525b52a5b369eaee837a1a0e08f))
- *(chat)* Implement scroll position persistence and restoration ([744a0b5](https://github.com/arafatamim/Ferngeist/commit/744a0b529aac4d8cc2be60a3599f4855ef9200ee))
- *(chat)* Implement automatic session bridge recovery ([e2dae04](https://github.com/arafatamim/Ferngeist/commit/e2dae04250243f04c886e2f3235d25ea8ca0f2a1))
- *(chat)* Generalize session configuration picker ([d36d2b2](https://github.com/arafatamim/Ferngeist/commit/d36d2b2164d24108d19c784d3f05532464f0418e))
- *(chat)* Overhaul tool call and permission UI with bottom sheets ([f06988f](https://github.com/arafatamim/Ferngeist/commit/f06988f8546db6ca403f3483a0ab35978163fd05))
- *(chat)* Add shimmer animation to streaming text ([4dbd62b](https://github.com/arafatamim/Ferngeist/commit/4dbd62b8b28bb2c92f5bb54467e45e6e61ba0eb8))

### Fixes

- *(sessionlist)* Preserve local sessions on app restart ([8cec21a](https://github.com/arafatamim/Ferngeist/commit/8cec21abc3eaf5209a2c4964be78298f497d3751))
- *(chat)* Refine composer layout and responsiveness ([a7391fd](https://github.com/arafatamim/Ferngeist/commit/a7391fd47500f9b8d037f135245e3bea8c1938cf))
- *(acp-bridge)* Improve session config option synchronization ([ed4d673](https://github.com/arafatamim/Ferngeist/commit/ed4d67389357e639f3e0a99381f79eaa6436337c))
- *(acp-bridge)* Improve session config option synchronization ([1f1ee6d](https://github.com/arafatamim/Ferngeist/commit/1f1ee6d56a39d87c867680aa521d679924587c76))

### Maintenance

- Clean up project configuration and fix build issues ([eb0a2a5](https://github.com/arafatamim/Ferngeist/commit/eb0a2a5e91c0906c66df02b22a5349737c7d8cd5))
- *(app)* Update SDK versions and release metadata ([b5bfa90](https://github.com/arafatamim/Ferngeist/commit/b5bfa90f5072b9c04837318894f53c2049eeefc8))

### Refactoring

- *(chat)* Decompose `ChatScreen` into smaller UI components ([6282812](https://github.com/arafatamim/Ferngeist/commit/6282812b24ea9d4d20fb7da370824937b6ccdde5))
- *(acp-bridge)* Improve transport management and error handling ([baab306](https://github.com/arafatamim/Ferngeist/commit/baab306269e234c1c52883fb7f1136d5977432c9))
- *(chat)* Rewrite auto-scroll logic using a state machine ([141572c](https://github.com/arafatamim/Ferngeist/commit/141572c09bd25b5b983f3bf21c6b9696976e2e65))
- *(acp-bridge)* Unify session configuration and mode management ([8224ee5](https://github.com/arafatamim/Ferngeist/commit/8224ee50107237695624d476eed2fe546fac3b7a))

### Style

- *(onboarding)* Clean up ([8d870f1](https://github.com/arafatamim/Ferngeist/commit/8d870f1524fb1f68cc080432758ce3eec3cb4bee))
- *(chat)* Refine composer UI ([5392598](https://github.com/arafatamim/Ferngeist/commit/5392598eb3d61118cf650bb63533a8ebb8643c59))

## [0.1.0-alpha01] - 2026-03-08

### Documentation

- *(repo)* Add contributor guide ([bf35c85](https://github.com/arafatamim/Ferngeist/commit/bf35c8522e6b1448c5b9f6503690670d3e482775))
- Add MIT License to the project ([77f33ed](https://github.com/arafatamim/Ferngeist/commit/77f33ed939b57d1ac36a5ae4bf9274a71569081a))
- Add README with project overview and architecture ([78e78be](https://github.com/arafatamim/Ferngeist/commit/78e78be15879c5c8c4593d8ed7802715089465c4))
- Update README ([585fa0a](https://github.com/arafatamim/Ferngeist/commit/585fa0af8cd23561f070b7b36ed4df2b6031bba5))

### Features

- *(app)* Bootstrap multi-module Android app ([15b9332](https://github.com/arafatamim/Ferngeist/commit/15b933260c724f64c6d008f9573f7755376d3f57))
- *(sessionlist)* Enhance UI feedback and loading states ([f9cf58a](https://github.com/arafatamim/Ferngeist/commit/f9cf58a04520be7352765a38ed718d2d001c1c0c))
- *(chat)* Update loading polygon variety and count ([6169210](https://github.com/arafatamim/Ferngeist/commit/6169210cd44a6f3c6ab1e56a6b384c43d7bcbfa8))
- *(serverlist)* Refactor and enhance server card UI ([fad66f5](https://github.com/arafatamim/Ferngeist/commit/fad66f57b59815360092693ccbbe303fa61f5b1b))
- *(ui)* Implement shared element transitions between session list and chat ([7c5f3cd](https://github.com/arafatamim/Ferngeist/commit/7c5f3cd14744ea5b954404682d10144fa5447989))
- *(sessionlist)* Implement pull-to-refresh and refine UI ([6da96bd](https://github.com/arafatamim/Ferngeist/commit/6da96bd1e47903e54629360db8d7da20d6210b93))
- *(acp)* Implement Agent Client Protocol (ACP) authentication ([4443b13](https://github.com/arafatamim/Ferngeist/commit/4443b13c94b1e697697db476a60a85bb06a5e563))
- *(acp)* Implement agent capabilities discovery and enforcement ([438ea44](https://github.com/arafatamim/Ferngeist/commit/438ea44d725bd643e50e90d18909823ed5fbb86f))
- *(acp-bridge)* Define client fs capabilities ([b71d971](https://github.com/arafatamim/Ferngeist/commit/b71d971c43ed2a9385001b781a3b7edde19ff456))
- *(chat)* Adjust action button size on ChatScreen ([432d627](https://github.com/arafatamim/Ferngeist/commit/432d627ee3499f9dbcbbc8916dd25f91961717dd))
- *(onboarding)* Add first-launch agent setup guide ([9fbf2e6](https://github.com/arafatamim/Ferngeist/commit/9fbf2e6fc5da7c5c6cb1d3c0cf534e5904238e82))

### Fixes

- *(acp)* Bypass authentication for Claude Agent ACP ([b645490](https://github.com/arafatamim/Ferngeist/commit/b645490d22adad7c5e0753f0ff8e5c6544e89f03))

### Maintenance

- *(github)* Add CI and release automation workflows ([4dc4dbe](https://github.com/arafatamim/Ferngeist/commit/4dc4dbe4d4a06fd0f5388d2cc81b457d69a8ecf1))
- Configure repo-relative SQLite temporary directory for Room/KSP ([f5f08d1](https://github.com/arafatamim/Ferngeist/commit/f5f08d136d32aa736c98fe55818dc3ced5664941))

### Refactoring

- *(chat)* Extract scroll logic into `ChatScrollState` ([97dd51c](https://github.com/arafatamim/Ferngeist/commit/97dd51c2a5fa45775e694add876a7cb4276e993f))
- *(chat)* Decouple session management and markdown parsing from `ChatViewModel` ([8bcf0cd](https://github.com/arafatamim/Ferngeist/commit/8bcf0cda46e1809422feb9c74e0c31906cf88662))
- *(core/common)* Extract `ConnectionDiagnosticsDialog` to common UI module ([9597f8b](https://github.com/arafatamim/Ferngeist/commit/9597f8b855c2a8ff7f3e8e52d00fd35126abefeb))
- *(acp)* Modularize connection management and upgrade SDK ([8c2871d](https://github.com/arafatamim/Ferngeist/commit/8c2871de1c5db9ace88f90bd037c3db05cedef9b))

### Style

- *(ui)* Top bar typography ([8226910](https://github.com/arafatamim/Ferngeist/commit/82269102b5234cbb0fe1c818ad22a511e368ebed))
- *(ui)* Refine session list and chat screen visuals ([8e3f079](https://github.com/arafatamim/Ferngeist/commit/8e3f0793532160f93d270c965eddfb357cfa0634))
