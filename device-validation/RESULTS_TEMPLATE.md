# OMNIX v0.2.0 — Results Matrix (заполняется по ходу device-тестов)

Статусы: только `PASS / FAIL / BLOCKED / NOT TESTED / PARTIAL`.
`PASS` = доказано на устройстве. Заполнено заранее только то, что доказуемо
без устройства (сборка); всё остальное — пусто, заполняет исполнитель.

## §1–3 Environment / Install

| Test | Expected | Actual | Status | Evidence |
|---|---|---|---|---|
| CI build (main@834f754) | success | success, 6.5 мин workflow, APK devDebug 151.8 МБ (arm64-v8a/armeabi-v7a/x86_64) | **PASS** | GH run 33368255495 |
| Release build (signed) | собирается+подписан | signing secrets не заданы владельцем | **BLOCKED** | docs/RELEASE.md §1–2 |
| APK install (fresh) | install OK, no crash | | | |
| Cold launch | TotalTime ≤ 2000ms (цель) | | | |
| Permission flows | запрашиваются корректно | | | |

## §4 Activation

| Test | Expected | Actual | Status |
|---|---|---|---|
| Valid scratch-code redeem | token выдан, ready | | |
| Invalid code | честная ошибка | | |
| Duplicate redeem | идемпотентно | | |
| Server unreachable | честная ошибка, retry | | |
| Expired license | корректный статус | | |

## §5–8 Voice loop / Local-first / Negative

Каждая строка — ≥20 повторов, latency P50/P95.

| Command | Expected route | Cloud invoked? | Tool result | TTS | Status | P50/P95 |
|---|---|---|---|---|---|---|
| «Открой Telegram» | DEVICE (open_app) | НЕТ | app launched | есть | | |
| «Открой Chrome» | DEVICE | НЕТ | | | | |
| «Поставь таймер на 1 минуту» | DEVICE (alarm_timer) | НЕТ | | | | |
| «Громкость 50%» | DEVICE (volume) | НЕТ | | | | |
| «Сделай скриншот» | DEVICE | НЕТ | | | | |
| «Покажи заряд» | DEVICE (battery) | НЕТ | | | | |
| «Открой настройки» | DEVICE / честный отказ | НЕТ | | | | |
| «Объясни квантовую запутанность» | CLOUD | ДА | — | | | |
| «Сравни два телефона» | CLOUD | ДА | | | | |
| «Найди в интернете…» | CLOUD+web | ДА | | | | |
| «Напиши письмо» | CLOUD | ДА | | | | |
| Unnecessary cloud escalation | не наблюдать | — | — | — | FAIL если да | |

## §10 Agent Core

| Test | Expected | Actual | Status |
|---|---|---|---|
| Multi-step с наблюдением и re-plan | Plan→Act→Observe→Re-plan виден в логах/поведении | | |

## §11–12 Tools (реальный результат, не успех из кода)

| Tool | Expected (по ANDROID_CAPABILITIES) | Actual | Status |
|---|---|---|---|
| wifi | переключение Quick Settings / честный USER_ACTION_REQUIRED | | |
| bluetooth | ограничения платформы честно | | |
| brightness | реальное изменение | | |
| open_app (valid/invalid) | launch / честная ошибка | | |
| sms / call | permission flow + подтверждение | | |
| screenshot | capture + storage | | |
| accessibility | guard: банк/пароль/2FA = BLOCKED | | |
| notifications | чтение с фильтрацией | | |
| calendar / alarms | create/fire/cancel | | |
| volume/flashlight/dnd/battery/time/clipboard/share/translate/weather/web | по таблице возможностей | | |

## §13–14 Interruption / Recovery (≥20 итераций)

| Test | Expected | Actual | Status |
|---|---|---|---|
| Interrupt during processing | cancel/replace, без stale | | |
| Duplicate TTS | не воспроизводится | | |
| Force stop → restart | state восстановлен, без corrupt | | |
| Process death → restart | сессия корректно пересоздана | | |
| Network OFF→ON | честная ошибка → восстановление | | |
| Server restart mid-request | ошибка/таймаут без зависаний | | |

## §15–16 Bluetooth / Background

| Test | Expected | Actual | Status |
|---|---|---|---|
| Earbuds connect/disconnect/reconnect | аудио маршрутизируется | | |
| Call interruption | SCO освобождается, возврат | | |
| Screen locked/background | wake word / FGS поведение | | |
| Battery drain (BT) | измерено /ч | NOT MEASURED пока | |

## §17–19 Resources / Battery / Network

| Test | Expected | Actual | Status |
|---|---|---|---|
| Idle 5м | RAM стабильно | | |
| Active 15м / Long 30м+ | нет утечек/роста heap | | |
| Battery idle/listening/heavy | /ч (или NOT MEASURED) | | |
| Wi-Fi poor / mobile / intermittent | таймаут+retry+feedback | | |

## §20–21 Security / Observability

| Test | Expected | Actual | Status |
|---|---|---|---|
| Token storage | Keystore-backed, не в логах | | |
| Banking app на экране | guard BLOCK, контент не уходит | | |
| Password field | не читается/не вводится | | |
| Логи | без токенов/паролей/контента экрана | | |
| Failed request диагностика | что/где/почему видно за 5 мин | | |
