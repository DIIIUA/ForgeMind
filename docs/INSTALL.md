# Installation, signatures and moving data / Установка, подписи и перенос данных

Release: [v0.2.6-alpha](https://github.com/DIIIUA/ForgeMind/releases/tag/v0.2.6-alpha), Android 10+, versionCode 8, Room schema 8.

| Asset | Application ID | Shared Termux UID |
| --- | --- | --- |
| `ForgeMind-v0.2.6-alpha.apk` | `com.forgemind.app` | No |

The release contains exactly one APK: a **release build** (not debuggable) signed with the ForgeMind release key. The key is kept outside this repository; CI never signs or uploads release assets.

В релизе ровно один APK: **релизная сборка** (без режима отладки), подписанная ключом релизов ForgeMind. Ключ хранится вне репозитория; CI не подписывает и не загружает релизные файлы.

## Certificate SHA-256 / Отпечаток сертификата

- **ForgeMind release key (0.2.5-alpha and later):** `7ced078e33f7bf877763312f4992608dc183f82df3dfc3c09f288032eeb18ba8`
- Older debug-signed APKs (do not use): v0.2.1-alpha `a64c10130e7e1df4fe79aa1f8207eaa1166c064bffe9c25a572a143ab6ed8de3`; v0.2.4-alpha (withdrawn) `0942d59cb79a66a1effcddb3554a95bde1fc7d07a306374e491cf7e2bbfbde5b`.

Check the signer before installing: `apksigner verify --print-certs ForgeMind-v0.2.6-alpha.apk`. SHA256SUMS in the release verifies file integrity, not signing identity.

Проверьте подпись перед установкой: `apksigner verify --print-certs ForgeMind-v0.2.6-alpha.apk`. SHA256SUMS в релизе проверяет целостность файла, а не подпись.

## Why older builds must be replaced / Почему старые сборки нужно заменить

Releases before 0.2.5 were debug builds. On a phone with USB debugging enabled, anyone with access to it could read their chats and settings. Android will not update them in place because the certificate is different.

Релизы до 0.2.5 были отладочными сборками. На телефоне с включённой отладкой по USB любой, у кого есть к нему доступ, мог прочитать их чаты и настройки. Обновить их поверх Android не позволит: сертификат другой.

## Move your data / Перенос данных

1. In the old app, export chats and attachments (chat archive) and settings (encrypted settings file) and save both outside the app's private storage. / В старом приложении экспортируйте чаты с вложениями (архив чатов) и настройки (зашифрованный файл настроек) и сохраните их вне внутреннего хранилища приложения.
2. Check that the files are complete before uninstalling: removing the app deletes its local data. / Перед удалением проверьте, что файлы полные: удаление приложения стирает его локальные данные.
3. Uninstall the old app, install `ForgeMind-v0.2.6-alpha.apk`, then import the settings file and the chat archive. Archive format 3 also reads formats 1 and 2. / Удалите старое приложение, установите `ForgeMind-v0.2.6-alpha.apk`, затем импортируйте файл настроек и архив чатов. Формат архива 3 читает и форматы 1 и 2.
4. Re-grant Android permissions (files, notifications, accessibility) and check important chats. Keep the backup. / Заново выдайте разрешения Android (файлы, уведомления, специальные возможности) и проверьте важные чаты. Сохраните резервную копию.

Future releases signed with the same release key update in place and keep data. / Следующие релизы с тем же ключом обновляются поверх с сохранением данных.

## Rollback / Откат

Schema 8 adds assistant profiles and memory; installing an older APK does not reverse a database migration. Keep the backup made before upgrading. / Схема 8 добавляет профили и память; установка старого APK не отменяет миграцию базы. Сохраняйте копию, сделанную до обновления.

## Termux

This APK does not replace Termux or change its signing certificate/UID. The setup guide installs a bundled helper into its own Termux folder and a separate background task; it provides live PTY, files and tracked tasks. Existing server sessions are preserved. SSH and RUN_COMMAND remain available. Do not reinstall Termux. See [setup and recovery](TERMUX_SETUP.md).

Этот APK не заменяет Termux и не меняет его подпись/UID. Мастер устанавливает встроенный помощник в собственную папку и отдельную фоновую задачу Termux: доступны живой PTY, файлы и задачи команд. Работающие серверные сессии сохраняются. SSH и RUN_COMMAND остаются доступны. Переустанавливать Termux не нужно. [Настройка и восстановление](TERMUX_SETUP.md).
