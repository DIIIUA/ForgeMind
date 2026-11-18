# Termux and Android setup / Настройка Termux и Android

## Русский

В ForgeMind 0.2.6 мастер открывается при первом запуске. Его можно отложить или снова открыть в Настройках. Выберите ручной или автоматический режим. У каждого разрешения есть отдельная проверка; провайдеры и ключи задаются пользователем.

1. Установите Termux из поддерживаемого источника и один раз откройте его для подготовки среды. Уже работающий Termux переустанавливать не нужно.
2. В `~/.termux/termux.properties` включите `allow-external-apps = true`. В мастере ForgeMind разрешите исполнение команд Termux. Это обязательные предварительные действия: без них приложение не может запустить установщик внутри Termux.
3. Нажмите «Установить / подключить помощник Termux». Скрипт уже находится внутри APK. Интернет нужен только для недостающих Python и tmux. Установщик проверяет план apt и отказывается автоматически обновлять или удалять существующие пакеты. Если это необходимо, сначала разберите изменения в Termux вручную, затем явно повторите подготовку.
4. Помощник работает в собственной фоновой задаче Termux и в папке `~/.local/share/forgemind-helper`. Он слушает только loopback и требует случайный токен. Живой терминал, файлы и команды ForgeMind используют это подключение. SSH остаётся отдельным доступным вариантом.
5. В терминале ForgeMind создайте свою сессию. «Заменить» создаёт новую; сессия с отметкой сохранения остаётся открытой. У помощника собственный tmux-сервер, поэтому эти кнопки не обращаются к сессиям обычного Termux. Новая оболочка начинается в домашней папке и не исполняет пользовательские login-скрипты.

Основной APK сохраняет собственный UID и релизный ключ. Отдельный APK-плагин, общая подпись, root или переустановка Termux не нужны. Помощник не меняет SSH-пароли, не перезапускает пользовательские серверы и не запускает sshd. Команды агента при этом действительно исполняются с правами пользователя Termux; явная команда может изменить его файлы. Подтверждения опасных действий и резервные копии остаются значимыми.

### Автонастройка разрешений Android

На Android 11+ доступны встроенный ADB-клиент и сопряжение через уведомление. Android 10 использует ручные системные экраны.

1. Подключитесь к Wi-Fi, включите режим разработчика и беспроводную отладку.
2. В автоматическом режиме мастера откройте шаг разрешений и нажмите «Сопряжение через уведомление». Разрешите уведомления.
3. В системном экране беспроводной отладки откройте «Подключить устройство с помощью кода». Оставьте этот диалог открытым и введите его шестизначный код в уведомлении ForgeMind.
4. Вернитесь в ForgeMind. После подключения отображается проверенный Android shell UID 2000. Отметьте нужные разрешения и нажмите «Применить выбранные разрешения и проверить».
5. При проблемах обнаружения используйте ручной ввод портов и разделённый экран. Порт сопряжения из диалога и порт подключения с основного экрана — разные значения. После перезапуска беспроводной отладки порт подключения может измениться.

Компьютер и приложение Shizuku для этого процесса не требуются. Это обычное разрешённое Android сопряжение; производитель может дополнительно ограничивать выдачу прав через shell. Неуспешная выдача остаётся выключенной в результатах проверки. Доступность службы управления экраном проверяется по её фактическому подключению. Разрешение на захват экрана MediaProjection по-прежнему подтверждается системным диалогом для соответствующей сессии; его этот мастер не обходит.

После перезагрузки телефона включите необходимые службы и при необходимости переподключите помощник кнопкой мастера. Сопряжение сохраняется локально, но включённость беспроводной отладки и доступность Termux не гарантируются после перезагрузки или ограничений энергосбережения. Ключ ADB и токен помощника не входят в переносимый экспорт настроек. Переустановка ForgeMind требует повторной настройки связи; не удаляйте старое состояние помощника без проверки работающих задач.

## English

Open the first-run guide or reopen it from Settings. Choose manual or automatic setup. Install and initialize Termux, enable `allow-external-apps = true` in `~/.termux/termux.properties`, and grant ForgeMind the Termux command permission. Then select **Install / connect Termux helper**.

The helper script is bundled in the main APK. Only missing Python/tmux packages need downloading. The installer refuses package upgrades/removals for manual review. It uses its own Termux folder, background task and tmux server, with authenticated loopback connections for PTY, files and tracked command tasks. Native Termux sessions and existing servers are not targets of session replacement or cleanup. ForgeMind retains its own UID and release certificate; no companion APK, shared UID or Termux reinstall is required. Explicit agent shell commands still have the Termux user's file access.

On Android 11+, connect to Wi-Fi and enable Developer options → Wireless debugging. Start notification pairing from the guide, leave Android's pairing-code dialog open, and enter the code in the ForgeMind notification. After the app verifies a local shell connection, select permissions and apply them. Manual pairing/connection ports are available as a fallback; they are separate ports. OEM restrictions can require additional manual confirmation. MediaProjection retains its system consent dialog.

After reboot, wireless debugging or the helper may need to be restarted/reconnected. Pairing keys and helper credentials are device-local and excluded from settings exports. Do not erase existing helper state during recovery without checking its running tasks.
