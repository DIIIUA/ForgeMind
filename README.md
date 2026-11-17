# ForgeMind

**AI workspace for Android: chat, assistants, memory, tools, terminal, files and browser.**

[Русский](README.ru.md) · [Website](https://diiiua.github.io/ForgeMind/) · [Download v0.2.5-alpha](https://github.com/DIIIUA/ForgeMind/releases/tag/v0.2.5-alpha) · [Changelog](CHANGELOG.md)

[![Android CI](https://img.shields.io/github/actions/workflow/status/DIIIUA/ForgeMind/android-ci.yml?branch=main)](https://github.com/DIIIUA/ForgeMind/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

ForgeMind is a native Kotlin / Jetpack Compose app that connects cloud APIs and self-hosted models to Android workspaces. Use it as a chat client, a configurable assistant, or an agent with tools and approval controls. Configure your own providers and credentials; the public app does not include a connected model account.

<a href="https://buymeacoffee.com/silentfish">
  <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="217" height="60">
</a>



## Install and upgrade

Android **10+** (API 29), version **0.2.5-alpha**, versionCode **7**, database schema **8**. This is an alpha build.

| APK | Package | Use |
| --- | --- | --- |
| [ForgeMind-v0.2.5-alpha.apk](https://github.com/DIIIUA/ForgeMind/releases/download/v0.2.5-alpha/ForgeMind-v0.2.5-alpha.apk) | `com.forgemind.app` | Standard package |

**Upgrading from an older APK:** releases before 0.2.5 were debug builds signed with debug certificates; v0.2.4-alpha was withdrawn because a debug build lets anyone with USB-debugging access to the phone read the app's data. From 0.2.5 the APK is a release build signed with the ForgeMind release key (fingerprint in [INSTALL](docs/INSTALL.md)). Android cannot update the older builds in place. Export chats and attachments outside app storage and verify a usable backup before reinstalling. Removing the old installation deletes its local data; install the new APK and restore the archive only after securing that backup. Provider credentials may need to be entered again. See [signatures, backup and rollback](docs/INSTALL.md). The release contains one APK built from this public source tree.

Obtainium users: enable prereleases; the release contains one public APK. Automated CI artifacts are testing builds, not update-compatible release downloads.

## What is available
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

### 66 tools and text tool calling

capabilities_catalog({}) lists every built-in tool by area in one compact call; area, name and query give details, full schemas and search. Tools of MCP servers are described by those servers through MCP. Native, automatic and XML/JSON modes support models without native tool calling. Explicit tool envelopes are parsed; fenced or inline code examples remain text.

### Terminal and command tasks

Embedded Android shell, SSH/Termux connections, multiple tabs, four arrow keys and explicit /term commands from chat with confirmation. Managed ForgeMind sessions can be created, replaced or retained independently; cleanup targets owned sessions. Async commands expose task IDs, status, task lists, group wait and cancellation. Termux capabilities depend on the connection mode and permissions.

### Files, editor and rollback

Dual-pane files across app storage, shared phone storage, Android SAF folders and SFTP. Sora editor with TextMate highlighting; file search, code search, edit/append, tolerant replacement, unified patches and pre-edit snapshots. Cross-environment transfer, ZIP archives and text extraction from PDF and supported Office/OpenDocument files; send files into chat.

### Browser, web and preview

Separate user browser and up to eight agent WebView tabs with navigation, DOM inspection, actions, JavaScript, screenshots and batches of up to 30 steps. Turbo resource filtering, page/site extraction to Markdown, file downloads, five search backends and a local preview server. Browser navigation no longer recreates the surrounding screen or cancels generation on tab changes.

### SSH and bidirectional MCP

SSH/SFTP with password or key authentication, including Cloudflare Access WebSocket SSH. MCP clients support Streamable HTTP, HTTP SSE and SSH stdio. An optional bearer-authenticated on-device MCP host exposes selected tools at 127.0.0.1:8765 with write/shell controls. Tool images are forwarded to supported vision models.

### Screen, voice and interface

Optional Accessibility reads exposed Android window trees and numbered controls, performs actions/batches and captures screenshots on Android 11+. Floating chat overlay, notifications, speech input through Android/Groq/OpenAI/custom Whisper, and speech output through Android/Edge/OpenAI/XTTS. Theme and terminal palette customization; 14 locale resource sets with incomplete translation of newer dialogs.

### Chat organization and backup

Chat folders, search, branching, attachments and Markdown export. Full ZIP archives include profiles, approved memory and proposals; selected-chat exports only carry chat-scoped memory. Archive format 3 reads older formats 1/2. Settings can be exported/imported separately with password-encrypted backup and an explicit import confirmation.

## Quick start

1. In Settings, add a cloud provider or a custom endpoint and choose a model. For LAN models, use direct address connection or network discovery. ForgeMind calls the server; it does not run a generative GGUF model inside the APK.
2. Create a chat, optionally select an assistant profile, and configure global/chat instructions. Instructions are sent in the system role, but actual adherence depends on the selected model and provider.
3. Open Memory to add scoped notes. Download the optional semantic model only if you want search by meaning; text search works without it. Embedding computation is on-device after download. Approved notes may still be sent to your selected LLM as part of chat context.
4. Select a file workspace and grant only the Android permissions needed for your task. Enable screen control and floating overlay separately if you need them.
5. For Termux SSH, install/configure OpenSSH in Termux, start `sshd -p 2222` and add your local SSH profile at `127.0.0.1:2222`. For `RUN_COMMAND`, enable external app commands in Termux and grant the matching Android permission. The embedded Android shell works independently of Termux; shared-UID access requires a separate compatible build and signing arrangement.
6. Add remote MCP servers or enable the local MCP host. For USB access from a computer, run `adb forward tcp:8765 tcp:8765` and configure its bearer token in the client.

To intentionally send a shell command from chat, use `/term pwd` or the explicit terminal mode and review the confirmation. Ordinary code pasted into chat is not a terminal command. See [the complete tool list and XML protocol](docs/TOOLS.md).

## Limits and validation

- Semantic search ranks embedding similarity; it is not a separate trained reranker or a guarantee of relevance. The model is optional, downloaded from Hugging Face at a pinned revision with hash checks, and runs on CPU. No automatic download on first launch.
- Memory is scoped and budgeted, not unlimited conversation recall. Compaction summarizes history; token counts are estimates. Back up before downgrading: old APKs do not understand newer database schemas.
- Android can stop background processes or disable Accessibility. Reconnection fixes improve recovery but cannot override OEM restrictions. Secure apps may hide their screen content. Vision, tools, reasoning and provider fallback depend on model/API support.
- The current suite contains **241 JVM tests**. The release report covers the public APK, unit tests and publication checks. A passing suite does not establish compatibility with every provider, SSH server or phone.
- New memory/profile dialogs currently contain Russian text; the existing locale resources do not mean every new feature has 14 complete translations. Additional research ideas remain future work and are not advertised as shipped.

## Build and project map

```sh
./gradlew testDebugUnitTest assembleDebug
```

JDK 17+, Android SDK 36; Kotlin 2.2.21, Jetpack Compose / Material 3, Room (schema 8), OkHttp, JSch, Sora, PDFBox, Termux terminal view and ONNX Runtime Android 1.23.2. Model weights are not stored in this repository.

[Contributing and CI](CONTRIBUTING.md) · [66 tools](docs/TOOLS.md) · [Release details](docs/releases/v0.2.5-alpha.md)

## License & Commercial Rights


Copyright © 2026 **DIIIUA**.

- **Open-Source License (`GPL-3.0`):** This public repository is free and open-source software licensed under the [**GNU General Public License v3.0**](LICENSE) (OSI & FSF approved). Anyone may inspect, build, modify, and redistribute derivative works under the terms of `GPL-3.0`.
- **Commercial Dual-Licensing & Store Distribution:** As the original copyright holder, **DIIIUA** retains the full right to distribute official builds of **ForgeMind** (including paid tiers, proprietary plugins, or store-compliant packages on Google Play, Apple App Store, Galaxy Store, and enterprise channels) under separate commercial terms.
- **Contributor License Grant:** By submitting a pull request or contribution to this repository, you grant **DIIIUA** a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license to use, modify, and distribute your contribution under both `GPL-3.0` and commercial / app-store licenses, while retaining your own copyright. The **ForgeMind** name and branding remain the property of **DIIIUA**.
