# Contributing to ForgeMind

Thank you for your interest in contributing to **ForgeMind**! Whether you are fixing bugs, adding AI provider adapters, improving local GGUF tool-calling reliability, or refining the Android UI, contributions are very welcome.

## Development Setup

### Prerequisites
- **JDK 17** or newer
- **Android SDK** (`compileSdk = 36`, `minSdk = 29`)
- **Android Studio Ladybug+** or command-line Gradle (`./gradlew`)

### Building & Running Tests

```bash
# Clone the repository
git clone https://github.com/DIIIUA/ForgeMind.git
cd ForgeMind

# Run JVM unit tests (includes tool-calling, context auto-detection, and 4,000-model catalog tests)
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Optional: Build side-by-side public package (com.forgemind.app.public)
./gradlew assembleDebug -PappIdOverride=com.forgemind.app.public
```

## Architecture Overview

Before making broad architectural changes, check out:
- [docs/GRAPHIFY_ARCHITECTURE.md](docs/GRAPHIFY_ARCHITECTURE.md) — full 128-file subsystem breakdown across the **External** and **Internal** contours.
- [docs/GRAPHIFY_TREE.html](docs/GRAPHIFY_TREE.html) — interactive Graphify knowledge graph.

Key packages under `app/src/main/java/com/forgemind/app/`:
- `llm/` — Multi-protocol streaming clients (`OpenAiCompatible`, `Anthropic`, `Gemini`, `Ollama`, `Cohere`), `ModelScanner`, `LanAiScanner`, and inline tool-call parsers.
- `agent/` & `tools/` — ReAct autonomous execution loop, compact GGUF schema selector, file/shell/SFTP/browser/Android UI tools, and rollback snapshot manager.
- `mcp/` — External MCP Client (`Streamable HTTP`, `SSE`, `SSH stdio`) and built-in Android MCP Host (`127.0.0.1:8765` with Bearer auth).
- `ssh/` — Native JSch SSH/SFTP client and Cloudflare Access Zero-Trust WebSocket SSH (`wss://`) tunnel transport.
- `ui/` — Jetpack Compose Material 3 screens (`ChatScreen`, `FilesScreen`, `TerminalScreen`, `BrowserScreen`, `SettingsScreen`) and Sora Editor integration.

## Pull Request Guidelines

1. **Keep PRs focused:** One logical feature or bug fix per pull request.
2. **Verify unit tests:** Run `./gradlew testDebugUnitTest` and add unit tests for parser, scanner, or state-management changes.
3. **Never commit secrets or personal endpoints:** Keep API keys, personal server hostnames, and device identifiers out of code, tests, and screenshots.
4. **License:** By contributing to ForgeMind, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
