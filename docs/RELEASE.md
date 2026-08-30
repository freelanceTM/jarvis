# Release Engineering — подписанные релизы JARVIS

Этот документ описывает полный цикл выпуска подписанного релиза (P0-1):
генерацию ключа, настройку секретов, локальную и CI-подпись, проверку подписи
и чеклист релиза.

## 1. Ключ подписи (one-time)

```bash
# Генерация release-keystore (RSA 4096, срок 30+ лет).
# ВАЖНО: keystore и пароли НЕ покидают владельца; потери ключа = невозможность
# обновить приложение для существующих установок (Android требует ту же подпись).
keytool -genkeypair -v \
  -keystore jarvis-release.jks \
  -alias jarvis \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype JKS
```

Backup: сохраните `jarvis-release.jks` + пароли в офлайн-хранилище
(парольный менеджер владельца / зашифрованный носитель). Keystore запрещено
коммитить: `*.jks`, `*.keystore`, `keystore.properties` — в `.gitignore`.

## 2. Секреты GitHub (one-time)

Repository → Settings → Secrets and variables → Actions:

| Secret | Значение |
|---|---|
| `JARVIS_RELEASE_KEYSTORE_B64` | `base64 -w0 jarvis-release.jks` |
| `JARVIS_SIGNING_STORE_PASSWORD` | пароль keystore |
| `JARVIS_SIGNING_KEY_ALIAS` | `jarvis` |
| `JARVIS_SIGNING_KEY_PASSWORD` | пароль ключа |

## 3. Выпуск подписанного релиза

### CI (основной путь)

Actions → **Release JARVIS (signed)** → Run workflow → выбрать `staging`
или `prod`. Пайплайн:

```text
checkout → JDK 17 → Gradle 8.7 → fail-fast проверка secrets
        → восстановление keystore из секрета → проверка dependency locks
        → :app:bundleProdRelease :app:assembleProdRelease
          (JARVIS_REQUIRE_SIGNED_RELEASE=true — unsigned-сборка провалится)
        → apksigner verify --print-certs
        → upload AAB+APK (retention 90 дней)
```

### Локально

```bash
# keystore.properties в корне проекта (не коммитится):
#   storeFile=/абсолютный/путь/jarvis-release.jks
#   storePassword=...
#   keyAlias=jarvis
#   keyPassword=...
bash ./gradlew :app:assembleProdRelease
# Проверка:
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/prod/release/app-prod-release.apk
```

## 4. Что подписывается чем

| Вариант | Подпись | Назначение |
|---|---|---|
| `devDebug` | debug-ключ | локальная разработка |
| `stagingRelease` | release-ключ | staging-проверки на устройстве |
| `prodRelease` | release-ключ | публичный релиз (AAB для Play / APK) |

R8 minify + resource shrink включены для всех release-вариантов;
правила — `app/proguard-rules.pro` (узкие, расширяются только при
воспроизведённом сбое — см. комментарии в файле).

## 5. Воспроизводимость

- Версии зависимостей зафиксированы `gradle.lockfile` +
  `gradle/verification-metadata.xml` (checksum verification); CI проверяет,
  что лок-файлы не «поехали» (`git diff --exit-code`).
- Gradle wrapper — единственный источник версии Gradle (8.7).
- Действия GitHub зафиксированы по digest (не по тегам).
- Docker-образы в `deploy/` — по digest.
- Сборка не зависит от локального состояния: `--no-daemon` в CI,
  фиксированный JDK 17 (temurin).

## 6. Чеклист релиза

1. `main` зелёный (Build JARVIS APK + Supply Chain Security).
2. Версия поднята в `app/build.gradle.kts` (`versionCode`/`versionName`)
   + запись в `CHANGELOG.md`.
3. `Release JARVIS (signed)` на нужном окружении — зелёный.
4. AAB/APK скачан из артефактов; `apksigner verify --print-certs` показывает
   ожидаемый отпечаток сертификата.
5. Установка на устройство, смоук: активация лицензии, голосовая команда,
   облачный запрос, локальная команда.
6. Отметить релиз тегом `vX.Y.Z` (после подтверждения смоука).

## 7. Компрометация ключа

1. Отозвать/перевыпустить: новый ключ = новое приложение с точки зрения
   Android (требуется новая установка либо Play App Signing с key upgrade).
2. Для Play: использовать Play App Signing (Google хранит release-ключ,
   upload-ключ можно сбросить) — рекомендуемая конфигурация при публикации.
3. Инцидент — по процедуре SECURITY.md.
