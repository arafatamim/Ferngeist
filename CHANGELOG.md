
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
