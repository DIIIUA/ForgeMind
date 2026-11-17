# Installation, signatures and rollback / Установка, подписи и откат

Release: [v0.2.4-alpha](https://github.com/DIIIUA/ForgeMind/releases/tag/v0.2.4-alpha), Android 10+, versionCode 6, Room schema 8.

| Asset | Application ID | Shared Termux UID |
| --- | --- | --- |
| `ForgeMind-v0.2.4-alpha.apk` | `com.forgemind.app` | No |

This release contains exactly one public APK. It is a debug alpha build signed by the maintainer's local Android debug certificate, not a production/store-signed build. The CI runner's own debug certificate is not used for release assets starting with this release.

В релизе ровно один публичный APK. Это отладочная альфа-сборка, подписанная локальным debug-сертификатом сопровождающего, а не production/store-сборка. Начиная с этого релиза APK из CI не заменяет опубликованные файлы.

## Verified certificate SHA-256

- GitHub `v0.2.1-alpha`: `a64c10130e7e1df4fe79aa1f8207eaa1166c064bffe9c25a572a143ab6ed8de3`
- This release / этот релиз: `0942d59cb79a66a1effcddb3554a95bde1fc7d07a306374e491cf7e2bbfbde5b`

The old tag workflow built and uploaded an APK with a runner-generated key. Its private key is not available in this repository; the differing fingerprints prevent an in-place Android update. SHA256SUMS in the release verifies file integrity, not signing identity.

Старый workflow тега собрал и загрузил APK с ключом runner. Закрытого ключа нет в репозитории; разные сертификаты не позволяют обновить приложение поверх. SHA256SUMS в релизе проверяет целостность файлов, а не заменяет проверку подписи.

## Keep data when moving from v0.2.1 / Перенос данных

1. Keep the old app installed. Export its chats/attachments using its available archive controls. Save the archive outside the app's private storage. / Сохраните старое приложение, экспортируйте чаты и вложения в архив вне его внутреннего хранилища.
2. Verify that the archive is usable before reinstalling; if possible, test restoration on another device. Removing the old installation deletes its local data. Only after securing the backup, uninstall the old package, install the release APK and import the archive. Format 3 reads older formats 1/2. / До переустановки проверьте пригодность архива к восстановлению; при возможности проверьте импорт на другом устройстве. Удаление старой установки стирает локальные данные. Только после сохранения проверенной копии удалите старый пакет, установите APK релиза и импортируйте архив. Формат 3 читает старые 1/2.
3. Reconfigure providers, SSH/MCP credentials and permissions as needed; chat archives are not a complete Android app-data backup. Check important chats and attachments after restoration; retain the backup. / При необходимости заново настройте ключи, SSH/MCP и разрешения. Архив чатов не является полной копией данных Android-приложения. Проверьте важные чаты и вложения после восстановления; сохраните резервную копию.

If the currently installed app has the same package and certificate as a release asset, a normal APK update preserves its data and migrates the database. A matching filename alone is insufficient. Never uninstall a data-bearing app just to bypass a signature error without first verifying a usable backup.

При совпадении пакета и сертификата обычное обновление сохраняет данные и мигрирует БД. Одного совпадения имени файла недостаточно. Не удаляйте приложение с данными ради обхода ошибки подписи без проверенной копии.

## Rollback / Откат

Keep the previous APK and a backup made before upgrading. Schema 8 adds assistant profiles and memory; replacing the APK with an older one does not reverse a database migration. Use a separate installation or restore a compatible pre-upgrade backup. The previous source state remains available at tag `v0.2.1-alpha`; do not force a database downgrade.

Сохраните предыдущий APK и копию до обновления. Схема 8 добавляет профили и память; замена APK старым не отменяет миграцию базы. Для отката нужна отдельная установка или совместимая копия до обновления. Старый исходный код доступен по тегу `v0.2.1-alpha`.

## Termux

This APK does not change Termux's package, signing certificate, data or running server sessions. The public build supports the embedded Android shell and configured SSH/RUN_COMMAND routes. Direct access to Termux's shared UID requires a separately compatible build/signing arrangement; installing the ordinary APK does not grant it. Do not reinstall Termux to install this release.

Этот APK не меняет пакет, подпись, данные Termux или работающие серверные сессии. Публичная сборка использует встроенную оболочку Android и настроенные SSH/RUN_COMMAND. Общий UID Termux требует отдельного совместимого варианта и подписи; обычная установка его не даёт. Переустанавливать Termux для этого релиза не нужно.
