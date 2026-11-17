# Contributing to ForgeMind

Use JDK 17+ and Android SDK 36 (minimum device API 29). Configure SDK location locally; never commit `local.properties`, keys, signing stores, APKs, personal endpoints or device exports.

## Build and checks

```sh
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleDebug -PappIdOverride=com.forgemind.app.public
# Android instrumentation requires a suitable emulator/device:
./gradlew connectedDebugAndroidTest
```

The JVM suite has 241 tests in v0.2.4-alpha. Android instrumentation and native semantic inference are separate checks; when reporting results, identify the package/build variant and actual device coverage. On memory-constrained hosts, run Gradle and the emulator sequentially and limit workers/heap.

## Source map

- [Current architecture](docs/GRAPHIFY_ARCHITECTURE.md), [tool list](docs/TOOLS.md), [installation/signing](docs/INSTALL.md).
- `llm/`: OpenAI Chat, OpenAI Responses, Anthropic, Gemini, Ollama clients and discovery.
- `agent/`: execution loop, complete tool schemas, prompt composition, approval and text-call guards.
- `data/db`, `data/memory`, `data/archive`: schema migrations, profiles, scoped memory, semantic retrieval and backup.
- `bridge/`: shell/task/session ownership, SSH/SFTP and workspace editing.
- `ui/`, `web/`, `accessibility/`, `mcp/`, `voice/`: Android presentation and integrations.

## Release practice

CI runs unit tests and builds an APK artifact on main/PRs. It does **not** upload release APKs: ephemeral runner debug keys caused incompatible signatures in earlier releases. Release assets are prepared and verified by the maintainer, with explicit package IDs, certificate fingerprints and SHA256SUMS. Do not publish a CI artifact over a stable-signature asset.

Before publishing, scan the complete public source and APK contents for private data/credentials, inspect new binary assets and licensing, verify version/package/signature, and update both READMEs, the bilingual portal, changelog and release notes. Keep credentials and private signing material outside git. Do not use a shared-UID Termux build as the ordinary public APK.

## Pull requests

Keep changes focused. Add meaningful regression coverage for parser/state/ownership changes and migration checks for schema changes. Preserve explicit memory approval, scope filtering and inert code examples. Describe verified behavior and remaining device/provider limitations.

Contributions follow the [GPL-3.0 license](LICENSE) and the existing [contributor license grant](README.md#license--commercial-rights).
