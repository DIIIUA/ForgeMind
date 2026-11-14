<div align="center">

# ForgeMind

**Автономный ИИ-агент, хаб облачных и локальных LLM и мульти-средовая рабочая станция для Android**

[![Latest Release](https://img.shields.io/github/v/release/DIIIUA/ForgeMind?include_prereleases&style=for-the-badge&logo=github&label=%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C%20APK%20(v0.2.0--alpha)&color=10b981)](https://github.com/DIIIUA/ForgeMind/releases/latest)
[![Android CI](https://img.shields.io/github/actions/workflow/status/DIIIUA/ForgeMind/android-ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Android%20CI)](https://github.com/DIIIUA/ForgeMind/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3%20%2B%20Commercial-blue.svg?style=for-the-badge)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-10%2B%20(API%2029--36)-3ddc84?style=for-the-badge&logo=android&logoColor=white)](#установка-и-сборка)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7f52ff?style=for-the-badge&logo=kotlin&logoColor=white)](#стек-технологий)

**Язык документации:** [English](README.md) · **Русский**

*Нативный мобильный командный центр на Kotlin и Jetpack Compose для облачных и локальных GGUF/LAN-моделей, универсального вызова инструментов, управления экраном Android по Window-Tag DOM, встроенного MCP-сервера и клиента, двухпанельного проводника (Память телефона + SSH + SAF), Sora Editor со снэпшотами отката, 8-оконного `⚡ Turbo` браузера и бесплатной нейросетевой озвучки Microsoft Edge TTS.*

[**⬇ Скачать готовый `.apk` (GitHub Releases)**](https://github.com/DIIIUA/ForgeMind/releases/latest) · [Что нового в v0.2.0-alpha](#что-нового-в-v020-alpha) · [Из коробки vs Termux](#что-работает-из-коробки-и-что-даёт-termux-ssh) · [Настройка за 1 минуту](#быстрая-настройка-за-1-минуту-локальные-llm--termux--ssh--mcp) · [Возможности](#возможности) · [Лицензия](#лицензия-и-коммерческие-права)

</div>

---

## Скриншоты интерфейса

| Чат и Diff-карточки агента | Двухпанельные файлы и Sora Editor | Живой Shell и браузер ИИ | Встроенные гайды, SSH и MCP |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01-chat-agent-tools.png" width="210" alt="Чат и карточки инструментов"/> | <img src="docs/screenshots/02-files-sora-diff.png" width="210" alt="Файлы ИИ и Sora Editor"/> | <img src="docs/screenshots/03-terminal-browser-live.png" width="210" alt="Терминал и Агентный браузер"/> | <img src="docs/screenshots/04-mcp-ssh-guides.png" width="210" alt="Гайды настройки SSH и MCP"/> |

---

## Что нового в `v0.2.0-alpha`

- **Универсальный движок инструментов для локальных GGUF и Uncensored моделей:** любые `7B`–`32B` GGUF, Uncensored, Heretic, DeepSeek, Qwen, Llama и Mistral модели через Ollama, LM Studio, `llama.cpp`, KoboldCpp или vLLM теперь полноценно исполняют команды и работают с файлами — даже если шаблон сервера не поддерживает нативный `tools`. ForgeMind автоматически включает компактный базовый набор инструментов, чинит нестрогий/Python-подобный JSON, разбирает 7 текстовых форматов (`<tool_call>`, нативный DeepSeek `<｜tool▁calls▁begin｜>`, DSML, `<function=...>`, `<invoke>`, `[TOOL_CALLS]`, Python-вызовы), прозрачно переключается при HTTP `400`/`422`/`500` и сам исправляет невыполненные `bash`-блоки в чате.
- **Автоопределение окна контекста по модели + быстрый выбор в 1 тап:** приложение само считывает реальный контекст с сервера (`loaded_context_length` в LM Studio, `n_ctx` в `llama.cpp`, `num_ctx` и `*.context_length` в Ollama `/api/show`, метаданные `/v1/models` в облачных роутерах) и даёт планку `Авто` + чипы `4K`..`1M` + `Своё…` прямо в шторке быстрой смены модели.
- **Точечное подключение LAN ИИ по IP/хосту + сканер всей подсети Wi-Fi:** кнопки **Найти по адресу** (~1 сек без обхода 254 адресов) и **Подключить сразу** рядом с полем IP/хоста, а также полный сканер `/24` сети, **Кастомные внешние ИИ** с выбором протокола и авторизации, индикаторы «в сети / оффлайн», удаление кастомных профилей и сброс базового списка с подтверждением.
- **8-оконный фоновый `⚡ Turbo` браузер + скрапер целых сайтов в Markdown:** вертикальная колонка вкладок, пакетное выполнение до 30 шагов без пауз (`browser_agent_batch`), блокировка рекламы/трекеров/тяжёлых шрифтов и параллельная выгрузка документации сайта в `INDEX.md` + `FULL_SITE.md` (`web_scrape_site`).
- **Живой Android Window-Tag DOM и встроенный MCP-сервер телефона (`:8765`):** мгновенное чтение окон `[W1..Wn]` и клики/ввод по тегам `[#N]` + встроенный Streamable HTTP MCP Host, позволяющий подключать телефон к настольным IDE-агентам (Claude Code, Cursor, Codex, Antigravity).
- **Двухпанельные Файлы ИИ (`Память телефона` + `Домашняя ИИ` + `SAF` + `SFTP`), бесплатная озвучка Microsoft Edge Neural TTS и Студия дизайна интерфейса:** перенос файлов между окнами, бесплатные живые голоса без API-ключей, выбор палитры терминала и тонкая настройка цветов и прозрачности всех панелей экрана.

---

## Что работает из коробки и что даёт Termux SSH

Чистый APK **ForgeMind** готов к работе сразу после установки и добавления API-ключа или подключения локального сервера в домашней Wi-Fi сети:

| Контур | Сразу из коробки (только APK) | С чистым Termux (`127.0.0.1:2222`) или внешним SSH |
| :--- | :--- | :--- |
| **Файловые среды** | **Домашняя ИИ (`internal`)** + **Память телефона (`device_storage`)** + **Папка Android (`saf`)** и двухпанельный режим | Полный доступ по SFTP/SSH к Termux (`~`) и удалённым Linux/macOS/Windows серверам |
| **Выполнение команд** | Постоянный процесс `/system/bin/sh` (`EmbeddedAiShell`) со стандартными POSIX / `toybox` утилитами Android | Полноценные пакеты Linux (`python3`, `nodejs`, `git`, `clang`, `ffmpeg`, `pytest`, `uvx`, `npx` и любые CLI) |
| **Документы и медиа** | Текст/код, `PDF` (`pdfbox-android`), `DOCX`/`XLSX`/`PPTX`/`ODT`/`ODS`/`ODP`/`RTF`, `ZIP`, полноэкранный зум и скачивание картинок в чате | Любые собственные OCR / аналитические / медиа-пайплайны в Termux или на сервере (`tesseract`, `pymupdf`, `pandas`) |
| **Редактор и веб** | **Sora Editor**, снэпшоты отката, 8-оконный `⚡ Turbo` браузер ИИ, скрапер сайтов в GFM Markdown, `LocalPreviewServer` | Запуск и живая отладка бэкендов (`FastAPI`, `Vite`, `Flask`, `Next.js`) в Termux или по SSH |
| **Голос, экран и MCP** | **Бесплатный Microsoft Edge Neural TTS**, офлайн Android STT/TTS, Whisper/XTTS/ElevenLabs, MCP-клиент и встроенный MCP-сервер (`:8765`), Window-Tag DOM | Консольные **`SSH_STDIO` MCP-серверы** (`npx`, `uvx`, `python3`), запускаемые прямо внутри Termux или внешнего SSH |

---

## Быстрая настройка за 1 минуту (Локальные LLM · Termux · SSH · MCP)

### А. Локальные нейросети в сети Wi-Fi / Tailscale (Ollama, LM Studio, `llama.cpp`, vLLM)
1. Включите сетевой доступ в вашем локальном сервере на ПК (`OLLAMA_HOST=0.0.0.0` для Ollama или **Serve on Local Network** в LM Studio `:1234`).
2. Откройте **Настройки → Провайдеры AI → Найти локалки в сети**.
3. Введите IP компьютера (`192.168.x.y:1234` или Tailscale `100.x.y.z:11434`) и нажмите **Найти по адресу** (или **Подключить сразу**), либо нажмите **Сканировать всю сеть Wi-Fi / LAN** для автоматического поиска всех серверов, моделей и их контекстных окон.

### Б. Локальный Termux по SSH (1 минута)
1. Установите актуальный [Termux](https://github.com/termux/termux-app/releases) (с F-Droid или GitHub Releases).
2. Выполните в Termux команду (копируется в один тап в **Настройки → Среды ИИ** или **Терминал → `?`**):
   ```bash
   pkg update -y && pkg install -y openssh && passwd && sshd -p 2222
   ```
3. Узнайте логин через `whoami` (например, `u0_a312`), впишите его и пароль в профиль `Termux (локальный)` (`127.0.0.1:2222`) и нажмите **Сохранить**.

### В. Внешний ПК или домашний сервер по SSH (без белых IP)
- **Tailscale:** установите [Tailscale](https://tailscale.com/) на ПК и смартфон и подключитесь по адресу `100.x.y.z:22`.
- **Cloudflare Zero Trust Tunnel:** опубликуйте `ssh://localhost:22` через `cloudflared` на сервере и включите **Туннель Cloudflare Access (`wss://`)** в профиле SSH ForgeMind.

---

## Возможности

- **5 протоколов LLM (`OPENAI_COMPAT`, `OPENAI_RESPONSES`, `ANTHROPIC`, `GEMINI`, `OLLAMA_NATIVE`):** 13+ облачных провайдеров, локальные движки и полностью кастомные внешние API с ротацией ключей и автоконтекстом.
- **55+ встроенных инструментов агента:** файловые операции в 4 средах со снэпшотами отката, встроенный и SSH-терминал, 8-оконный фоновый `⚡ Turbo` браузер, скрапер сайтов в Markdown, управление экраном Android, ветвление диалогов (`< 1/2 >`), сворачиваемая до тонких линий индикаторов полоса связей, подтверждение перед ручным сжатием контекста и индивидуальная инструкция для каждого чата (через меню `⋮` чата в боковой панели).
- **14 языков интерфейса и Студия дизайна:** мгновенное переключение языка и настройка цветов/прозрачности каждого элемента экрана и терминала.

---

## Установка и сборка

### Готовый APK и автообновление через Obtainium
- **GitHub Releases:** скачайте свежий `.apk` на странице [**Releases**](https://github.com/DIIIUA/ForgeMind/releases/latest).
- **[Obtainium](https://github.com/ImranR98/Obtainium):** добавьте ссылку `https://github.com/DIIIUA/ForgeMind` и включите отслеживание pre-release сборок.

### Сборка из исходников
```bash
./gradlew testDebugUnitTest assembleDebug
```

> [!NOTE]
> Репозиторий не содержит встроенных API-ключей, приватных адресов серверов или личных настроек. Все ключи провайдеров, учётные данные SSH и заголовки MCP задаются локально на устройстве и шифруются в `AES256_GCM`.

---

## Стек технологий

- **Язык и сборка:** Kotlin `2.2.21`, Android Gradle Plugin `8.13.2`, KSP `2.2.21-2.0.5`, Java `17` (`minSdk 29`, `compileSdk 36`)
- **UI:** Jetpack Compose BOM `2025.09.00`, Material 3, Sora Editor `0.23.6` (TextMate), Coil `2.7.0`
- **Хранение и безопасность:** Room `2.8.5` (схемы v1–v5), DataStore Preferences, `AES256_GCM`
- **Сеть и парсинг:** OkHttp `4.12.0` (SSE + WebSockets + Edge Neural TTS WSS + DoH), `mwiede/jsch` `2.28.8` (SSH/SFTP), Jsoup `1.20.1`, PDFBox Android `2.0.27.0`

---

## Лицензия и коммерческие права

Copyright © 2026 **DIIIUA**.

- **Открытая лицензия (`GPL-3.0`):** Исходный код этого публичного репозитория распространяется по свободной лицензии [**GNU General Public License v3.0**](LICENSE) (одобренной OSI и FSF). Вы можете свободно изучать, собирать, модифицировать и распространять производные версии при соблюдении условий `GPL-3.0`.
- **Двойное лицензирование и публикация в магазинах приложений:** Как первоначальный правообладатель, **DIIIUA** сохраняет полное право распространять официальные сборки **ForgeMind** (включая платные версии, подписки, закрытые расширения и сборки для Google Play, Apple App Store, Galaxy Store и корпоративных заказчиков) на отдельных коммерческих условиях.
- **Лицензионное соглашение для контрибьюторов:** Отправляя pull request или иной вклад в данный репозиторий, вы предоставляете **DIIIUA** бессрочную, неисключительную, безвозмездную и безотзывную всемирную лицензию на использование, модификацию и распространение вашего вклада как на условиях `GPL-3.0`, так и в коммерческих / магазинных сборках, сохраняя за собой авторство на свой вклад. Название и бренд **ForgeMind** принадлежат **DIIIUA**.
