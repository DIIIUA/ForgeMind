# Changelog

All notable changes to **ForgeMind** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.2.1-alpha] - 2026-11-14

### Fixed
- **1,000+ Model Aggregator Catalog Crash Fix (Chat & Settings):** Fixed `IllegalArgumentException: Key "..." was already used` in Compose `LazyColumn` when opening model pickers for OpenAI-compatible aggregator gateways returning >1,000 models or duplicate model IDs (e.g., `aimlapi/dynamic-router`).
- **Category Chip Overflow Protection:** Replaced uncapped eager horizontal rows with virtualized `LazyRow` chips capped at the top 20 model categories by frequency, preventing `Constraints` width overflow on massive catalogs.

### Improved
- **Zero-Lag 4,000+ Model Search & Virtualization:** Added `PreparedModelCatalog` with `O(1)` ID lookups, pre-indexed lowercase search blobs, `LazyColumn` `contentType` reuse, filtered/total model count badges, and capped saved-model chip previews (`24` + expand) in Settings.
- **Large Catalog Network Probe Guard:** Skipped redundant 3x local context-window HTTP fallback probes when `/v1/models` returns more than 250 models.
- **Automated Regression Suite:** Added `LargeModelCatalogTests` verifying duplicate-ID merging, whitespace normalization, category capping, favorite sorting, and `<250ms` search filtering across 3,500–4,000 model catalogs.

## [0.2.0-alpha] - 2026-11-14

### Added
- **Universal GGUF & Local Model Tool-Calling Engine:** Automatic compact core toolset for local/`<=32B` models, parser for 7 inline tool-call syntaxes (`<tool_call>`, native DeepSeek `<｜tool▁calls▁begin｜>`, DSML, `<function=...>`, `<invoke>`, `[TOOL_CALLS]`, Pythonic calls), loose dictionary repair, automatic wire-history downgrade on `HTTP 400/422/500`, and unexecuted shell-block nudging.
- **Live Context Window Auto-Detection & 1-Tap Override:** Automatic loaded KV context probing for LM Studio (`/api/v0/models`), `llama.cpp` (`/props`), Ollama (`/api/show`), and `/v1/models`, plus instant `Auto | 4K | 8K | 16K | 32K | 64K | 128K | 1M` chips in the model switcher.
- **Targeted 1-Second Host Connect + `/24` LAN Scanner:** Direct `IP:port` / Tailscale / hostname probing alongside parallel subnet discovery for Ollama, LM Studio, `llama.cpp`, KoboldCpp, vLLM, Jan, OpenWebUI, and LiteLLM.
- **Complete Graphify Architecture Map:** Published [GRAPHIFY_ARCHITECTURE.md](docs/GRAPHIFY_ARCHITECTURE.md) and interactive [GRAPHIFY_TREE.html](docs/GRAPHIFY_TREE.html) covering 128 source files across External and Internal connectivity contours.
- **Live UI Theme Designer & Vertical Browser Tabs:** Per-component color/opacity customization, terminal color palettes, and vertical tab drawer for the 8-window `⚡ Turbo` agent browser.

## [0.1.0-alpha] - 2026-11-13

### Added
- Initial open-source release of **ForgeMind** (`GPL-3.0`): native Kotlin + Jetpack Compose autonomous AI workspace for Android.
- **External Contour:** 13+ cloud providers across 5 native wire protocols, arbitrary custom HTTP gateways (6 auth modes, custom headers, DNS-over-HTTPS), remote SSH/SFTP, Cloudflare Access Zero-Trust WebSocket SSH (`wss://`), and external MCP Client (`Streamable HTTP`, `SSE`, `SSH stdio`).
- **Internal Contour:** 4 file environments (App Sandbox, Phone Storage, Android SAF, SFTP) in a dual-pane manager, Sora code editor with unified diffs and automatic rollback snapshots, persistent `/system/bin/sh`, Termux SSH integration, built-in Android MCP Host (`:8765`), 8-window `⚡ Turbo` agent browser + whole-site Markdown scraper, Accessibility Window-Tag `[#N]` screen navigation, floating overlay chat, and free Microsoft Edge Neural TTS.
