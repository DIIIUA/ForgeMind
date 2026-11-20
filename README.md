# ForgeMind

**AI workspace for Android: chat, assistants, memory, tools, terminal, files and browser.**

[Русский](README.ru.md) · [Website](https://silentfish.dev/) · [Google Play Closed Test](https://play.google.com/apps/testing/com.forgemind.app) · [Google Play](https://play.google.com/store/apps/details?id=com.forgemind.app) · [Changelog](CHANGELOG.md)

[![Android CI](https://img.shields.io/github/actions/workflow/status/DIIIUA/ForgeMind/android-ci.yml?branch=main)](https://github.com/DIIIUA/ForgeMind/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

ForgeMind is a native Kotlin / Jetpack Compose app that connects cloud APIs and self-hosted models to Android workspaces. Use it as a chat client, a configurable assistant, or an agent with tools and approval controls. Configure your own providers and credentials; the public app does not include a connected model account.

Current version: **0.2.11-alpha / code 13 / database 10** (live on Google Play Closed Testing). Recent releases add prominent Accessibility Service disclosure across all entry points, instant TTS pause/resume across all 4 engines, voice barge-in and smoother chunked/prefetched speech, unified Chat/Settings cleanup, offline Ed25519 `SF1` license verification, and full 13-language localization across all UI dialogs. [Development features and where to find their controls](docs/conversation-features.md) · [Chat content controls](docs/chat-content-controls.md).

<a href="https://buymeacoffee.com/silentfish">
  <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="217" height="60">
</a>

## Get ForgeMind on Google Play (Closed Testing & Purchase)

ForgeMind (`v0.2.11-alpha`, Android **10+**, `com.forgemind.app`) is distributed via **Google Play** (₪30 / ~$8.99) and direct site licensing at [silentfish.dev/buy](https://silentfish.dev/buy).

To unlock public Production access, Google Play currently requires at least **12 closed testers opted in for 14 days**. **Closed testers receive a complimentary 100% free single-use Google Play redeem key** (yours to keep permanently):

1. **Join the tester Google Group** (use the same Google account as your Play Store): https://groups.google.com/g/forgemind-testers
2. **Opt in as a tester on the web**: https://play.google.com/apps/testing/com.forgemind.app
3. **Purchase on Google Play** (₪30 / ~$8.99) **or request a complimentary closed-tester key**: email `SilentFishDev@proton.me` or comment in [Issue #6](https://github.com/DIIIUA/ForgeMind/issues/6) — I will send you a personal 100% free redeem link right away.
4. **Install from Google Play**: https://play.google.com/store/apps/details?id=com.forgemind.app — open the app at least once and keep it installed for 14 days.

| Channel | Link | Notes |
| --- | --- | --- |
| **Google Play Closed Testing** | [Join Group](https://groups.google.com/g/forgemind-testers) → [Opt In](https://play.google.com/apps/testing/com.forgemind.app) | Free single-use Google Play keys available for 14-day testers |
| **Google Play Store** | [com.forgemind.app](https://play.google.com/store/apps/details?id=com.forgemind.app) | Official Play Store build (`v0.2.11-alpha`, ₪30 / ~$8.99) |
| **Direct License** | [silentfish.dev/buy](https://silentfish.dev/buy) | Signed Ed25519 license (one person, 3 devices, no expiry) |

**Upgrading from an older sideloaded APK:** releases before 0.2.5 were debug builds signed with debug certificates; Google Play builds use Play App Signing, so Android cannot update an older sideloaded APK in place. Export chats and attachments outside app storage and verify a usable backup before reinstalling. Removing the old installation deletes its local data; install from Google Play and restore the archive only after securing that backup. Provider credentials may need to be entered again. See [signatures, backup and rollback](docs/INSTALL.md). The source code in this repository remains GPL-3.0 if you want to build from source yourself.

## What is available
### Setup guide

The guide opens on first launch and can be reopened from Settings. It remembers the current step, supports manual and automatic modes, and starts with Termux and Android permissions before models, MCP, voice, agent settings and appearance. Forms keep their explicit Save buttons. Automatic mode installs the bundled helper into its own Termux folder and installs only missing Python/tmux packages; changes requiring upgrades to existing packages stop for manual review. On Android 11+, the embedded ADB client supports one-time pairing through a notification and applies selected permissions with readback checks. Wi-Fi and Wireless debugging are required; root, a computer and a separate Shizuku app are not. Manufacturer restrictions may still require manual confirmation. [Setup guide](docs/TERMUX_SETUP.md).

### Models and connections

Five wire protocols: OpenAI Chat Completions, OpenAI Responses, Anthropic Messages, Gemini and Ollama Native. Cloud presets, custom HTTP gateways, multiple API keys, configurable authentication/headers/body, model discovery and large catalog search. Direct host connection and LAN discovery for self-hosted servers; context detection and manual overrides.

### Assistant profiles and instructions

Create, edit, search and categorize profiles with instructions, an optional provider/model and a history limit. Apply a profile to an existing or new chat. Base, global, chat and assistant instructions are composed in the system message; user instructions add to the base instruction. Profiles are preserved when switching models.

### Scoped memory

Notes belong to a chat, assistant, folder, provider/model pair or all chats. Create, edit, enable, disable and delete notes; approved enabled notes enter the appropriate chat context within a budget. The agent can search memory and propose changes. Every proposal needs explicit human approval, including in autopilot; conflicting revisions are checked.

### Local semantic search

Search memory and tool descriptions by meaning using multilingual sentence embeddings. Optional distiluse-base-multilingual-cased-v2 model download (about 138 MB), ONNX Runtime CPU inference, vector cache, text/semantic/automatic modes and visible text fallback. Memory scope and enabled-state filters apply before ranking. Delete the model without deleting your notes.

### Agent control and context

Queue a follow-up with «Дослать», intervene with «Сейчас», or stop a run while preserving saved partial output. Configure history limits, automatic/manual compaction, compaction prompt/model, threshold and preserved tail; restore a compaction checkpoint when compatible with current history. Model fallback includes approval and retry choices. The run/approval panel is available from the browser.

### 67 tools and text tool calling

capabilities_catalog({}) lists every built-in tool by area in one compact call; area, name and query give details, full schemas and search. Tools of MCP servers are described by those servers through MCP. Native, automatic and XML/JSON modes support models without native tool calling. Explicit tool envelopes are parsed; fenced or inline code examples remain text.

### Terminal and command tasks

Embedded Android shell, SSH and a direct Termux helper connection: live PTY input, resizing and Ctrl+C, multiple tabs, four arrow keys and confirmed /term chat commands. The helper uses a separate tmux server. ForgeMind sessions can be created, replaced and retained; these operations do not target native Termux sessions or existing servers. Commands have durable IDs, status, captured output and cancellation; a missing reply never means automatically replaying a command. The main app keeps its own UID and signing key. This connection needs no separate bridge APK.

### Files, editor and rollback

Dual-pane files across app storage, shared phone storage, Android SAF folders and SFTP. Sora editor with TextMate highlighting; file search, code search, edit/append, tolerant replacement, unified patches and pre-edit snapshots. Cross-environment transfer, ZIP archives and text extraction from PDF and supported Office/OpenDocument files; send files into chat.

### Browser, web and preview

Separate user browser and up to eight agent WebView tabs with navigation, DOM inspection, actions, JavaScript, screenshots and batches of up to 30 steps. Turbo resource filtering, page/site extraction to Markdown, file downloads, five search backends and a local preview server. Browser navigation no longer recreates the surrounding screen or cancels generation on tab changes.

### SSH and bidirectional MCP

SSH/SFTP with password or key authentication, including Cloudflare Access WebSocket SSH. MCP clients support Streamable HTTP, HTTP SSE and SSH stdio. An optional bearer-authenticated on-device MCP host exposes selected tools at 127.0.0.1:8765 with write/shell controls. Tool images are forwarded to supported vision models.

### Screen, voice and interface

Optional Accessibility reads exposed Android window trees and numbered controls, performs actions/batches and captures screenshots on Android 11+. Floating chat overlay, notifications, speech input through Android/Groq/OpenAI/custom Whisper (with voice barge-in), and speech output through Android/Edge/OpenAI/XTTS with pause/resume and background prefetch. Theme and terminal palette customization; complete 13-language localization across UI screens and dialogs.

### Chat organization and backup

Chat folders, search, branching, attachments and Markdown export. Full ZIP archives include profiles, approved memory and proposals; selected-chat exports only carry chat-scoped memory. Archive format 3 reads older formats 1/2. Settings can be exported/imported separately with password-encrypted backup and an explicit import confirmation.

## Quick start

1. In Settings, add a cloud provider or a custom endpoint and choose a model. For LAN models, use direct address connection or network discovery. ForgeMind calls the server; it does not run a generative GGUF model inside the APK.
2. Create a chat, optionally select an assistant profile, and configure global/chat instructions. Instructions are sent in the system role, but actual adherence depends on the selected model and provider.
3. Open Memory to add scoped notes. Download the optional semantic model only if you want search by meaning; text search works without it. Embedding computation is on-device after download. Approved notes may still be sent to your selected LLM as part of chat context.
4. Select a file workspace and grant only the Android permissions needed for your task. Enable screen control and floating overlay separately if you need them.
5. For Termux, open the setup guide: grant RUN_COMMAND, enable `allow-external-apps = true`, then install the bundled helper. SSH remains an alternative with manually configured OpenSSH and a server profile. Shared UID is not required.
6. Add remote MCP servers or enable the local MCP host. For USB access from a computer, run `adb forward tcp:8765 tcp:8765` and configure its bearer token in the client.

To intentionally send a shell command from chat, use `/term pwd` or the explicit terminal mode and review the confirmation. Ordinary code pasted into chat is not a terminal command. See [the complete tool list and XML protocol](docs/TOOLS.md).

## Limits and validation

- Semantic search ranks embedding similarity; it is not a separate trained reranker or a guarantee of relevance. The model is optional, downloaded from Hugging Face at a pinned revision with hash checks, and runs on CPU. No automatic download on first launch.
- Memory is scoped and budgeted, not unlimited conversation recall. Compaction summarizes history; token counts are estimates. Back up before downgrading: old APKs do not understand newer database schemas.
- Android can stop background processes or disable Accessibility. Reconnection fixes improve recovery but cannot override OEM restrictions. Secure apps may hide their screen content. Vision, tools, reasoning and provider fallback depend on model/API support.
- The current suite contains **342 JVM tests**. The release report covers the public build, unit tests and publication checks. A passing suite does not establish compatibility with every provider, SSH server or phone.
- All UI screens, settings panes, and conversation/memory/profile/schedule dialogs are localized across all 13 language resource sets (`en`, `ru`, `uk`, `es`, `de`, `fr`, `pt`, `tr`, `zh-CN`, `ja`, `ko`, `hi`, `ar`). Some internal agent tool notices and diagnostic messages remain technical.

## Build and project map

```sh
./gradlew :app:testDebugUnitTest :termux-bridge:testDebugUnitTest :app:assembleDebug
```

JDK 17+, Android SDK 36; Kotlin 2.2.21, Jetpack Compose / Material 3, Room (schema 10), OkHttp, JSch, Sora, PDFBox, Termux terminal view and ONNX Runtime Android 1.23.2. Model weights are not stored in this repository.

[Contributing and CI](CONTRIBUTING.md) · [67 tools](docs/TOOLS.md) · [Install & backup](docs/INSTALL.md) · [Changelog](CHANGELOG.md)

## License & Commercial Rights


Copyright © 2026 **DIIIUA**.

- **Open-Source License (`GPL-3.0`):** This public repository is free and open-source software licensed under the [**GNU General Public License v3.0**](LICENSE) (OSI & FSF approved). Anyone may inspect, build, modify, and redistribute derivative works under the terms of `GPL-3.0`.
- **Commercial Dual-Licensing & Store Distribution:** As the original copyright holder, **DIIIUA** retains the full right to distribute official builds of **ForgeMind** (including paid tiers, proprietary plugins, or store-compliant packages on Google Play, Apple App Store, Galaxy Store, and enterprise channels) under separate commercial terms.
- **Contributor License Grant:** By submitting a pull request or contribution to this repository, you grant **DIIIUA** a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license to use, modify, and distribute your contribution under both `GPL-3.0` and commercial / app-store licenses, while retaining your own copyright. The **ForgeMind** name and branding remain the property of **DIIIUA**.
