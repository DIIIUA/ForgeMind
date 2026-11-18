# Contributing to ForgeMind

Use JDK 17+ and Android SDK 36 (minimum device API 29). Configure SDK location locally; never commit `local.properties`, keys, signing stores, APKs, personal endpoints or device exports.

## Build and checks

```sh
./gradlew :app:testDebugUnitTest :termux-bridge:testDebugUnitTest :app:assembleDebug
# Android instrumentation requires a suitable emulator/device:
./gradlew :app:connectedDebugAndroidTest
```

The main JVM suite has 290 tests in v0.2.6-alpha, with 33 additional tests in the experimental bridge module. Android instrumentation and native semantic inference are separate checks; when reporting results, identify the package/build variant and actual device coverage. On memory-constrained hosts, run Gradle and the emulator sequentially and limit workers/heap.

Building the optional Termux bridge APK requires the public GitHub Termux test keystore outside the repository. Pass its path as `-PtermuxBridgeKeystore=/path/to/app_dev_keystore.jks` to `:termux-bridge:assembleRelease`. The signing task verifies the certificate fingerprint. Debug bridge clients require an explicit SHA-256 certificate property, `-PbridgeDebugCertificate=<64-hex-digits>`; release bridges never enable this debug allowance. The synthetic bridge test client is included only with `-PbridgeTests=true` and must only be used on an emulator, never included in release assets. The main app's ordinary build and bridge JVM tests do not need this keystore.

## Source map

- [Tool list](docs/TOOLS.md), [installation/signing](docs/INSTALL.md).
- `llm/`: OpenAI Chat, OpenAI Responses, Anthropic, Gemini, Ollama clients and discovery.
- `agent/`: execution loop, complete tool schemas, prompt composition, approval and text-call guards.
- `data/db`, `data/memory`, `data/archive`: schema migrations, profiles, scoped memory, semantic retrieval and backup.
- `bridge/`: shell/task/session ownership, SSH/SFTP and workspace editing.
- `ui/`, `web/`, `accessibility/`, `mcp/`, `voice/`: Android presentation and integrations.

## Release practice

Publish exactly one main app APK per release: `ForgeMind-v<version>.apk`, built from this public repository with package `com.forgemind.app`. The bundled Python helper is shipped inside this main APK. Do not attach the experimental companion `com.forgemind.termuxbridge` or a personal APK to the release. Personal builds, alternate main-app packages and test clients must not be included in release assets or release validation reports.

CI runs unit tests and builds an APK artifact on main/PRs. It does **not** upload release APKs: ephemeral runner debug keys caused incompatible signatures in earlier releases. Release assets are built locally with `assembleRelease`, signed with the release key kept outside the repository (see `app/build.gradle.kts`), and verified by the maintainer with explicit package IDs, certificate fingerprints and SHA256SUMS. Do not publish a CI artifact over a stable-signature asset.

Before publishing, scan the complete public source and APK contents for private data/credentials, inspect new binary assets and licensing, verify version/package/signature, and update both READMEs, the bilingual portal, changelog and release notes. Keep credentials and private signing material outside git. Do not use a shared-UID Termux build as the ordinary public APK.

## Pull requests

Keep changes focused. Add meaningful regression coverage for parser/state/ownership changes and migration checks for schema changes. Preserve explicit memory approval, scope filtering and inert code examples. Describe verified behavior and remaining device/provider limitations.

Contributions follow the [GPL-3.0 license](LICENSE) and the existing [contributor license grant](README.md#license--commercial-rights).
