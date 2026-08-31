# OMNIX v0.2.0 — Device Validation Report

**Дата:** 2026-08-31
**Базовая ревизия:** `main@834f754` (тег `v0.2.0`)
**Исполнитель:** агент в sandbox-среде
**Физическое Android-устройство:** **ОТСУТСТВУЕТ** — выполнение в Linux-контейнере
(1.9 ГБ RAM, без KVM, без adb-цели). Компиляция Kotlin локально упирается в OOM,
эмулятор невозможен.

**Следствие по правилам ТЗ (§24):** все device-тесты получают статус `BLOCKED`
(«среда не позволяет»), а не PASS и не «скорее работает». Ниже — то, что
доказуемо из этой среды, и готовый исполняемый kit для прогона на устройстве
(`device-validation/`).

---

## 1. Environment (§1 ТЗ) — что зафиксировано фактически

### Device

```text
Device model:      NOT AVAILABLE (нет физического устройства)
Android version / API / RAM / SoC / ABI / BT / battery / locale: BLOCKED
```

### Build (реальные цифры из CI, не из документации)

```text
Git commit:        834f754 (main, tag v0.2.0)
Branch:            main
Build variant:     devDebug (CI-артефакт) — installable artifact
Version:           0.2.0 (versionCode 1)
APK size:          159 205 072 байт ≈ 151.8 МБ
ABI:               arm64-v8a, armeabi-v7a, x86_64
CI build time:     ~6.5 мин (полный Build workflow: tests+coverage+detekt+lint+
                   invariants+R8-smoke+server tests+API-34 instrumentation)
Signing:           devDebug — debug-ключ; prodRelease — НЕ собирается без
                   секретов (fail-fast JARVIS_REQUIRE_SIGNED_RELEASE=true)
Errors/warnings:   сборка зелёная; детальные логи — GH run 33368255495
```

### Server

```text
Deployed instance: НЕ РАЗВЁРНУТ публично (api.jarvis.ai не резолвится —
                   подтверждено ранее в этой сессии)
Environment:       конфигурация готова (deploy/, PRODUCTION_DEPLOYMENT.md),
                   фактического деплоя нет
AI providers:      Groq/Gemini/OpenRouter (конфигурируемые), live-ключей нет
Network latency:   NOT MEASURED (нет цели)
```

**Опережающий вывод для планирования:** даже при наличии устройства
activation/cloud-сценарии потребуют предварительного деплоя сервера.

---

## 2. Что доказано и что заблокировано (матрица §23, сводка)

Полная матрица — `device-validation/RESULTS_TEMPLATE.md`. Сводно:

| Раздел ТЗ | Статус | Основание |
|---|---|---|
| §2 Build validation | **PARTIAL (PASS для devDebug через CI)** | артефакт существует, размер/ABI/время зафиксированы; signed release — BLOCKED (секреты владельца) |
| §3 Installation | **BLOCKED** | нет устройства |
| §4 Activation | **BLOCKED** | нет устройства И нет развёрнутого сервера |
| §5–8 Voice loop, local-first, negative | **BLOCKED** | нет устройства |
| §10 Agent core (re-plan на устройстве) | **BLOCKED** | нет устройства |
| §11–12 Tools / platform limitations | **BLOCKED** | нет устройства; ожидания задокументированы в docs/ANDROID_CAPABILITIES.md (не доказательство) |
| §13–14 Interruption / restart / network | **BLOCKED** | нет устройства |
| §15–16 Bluetooth / background | **BLOCKED** | нет устройства и наушников |
| §17–19 RAM/CPU/battery/network | **NOT MEASURED** (честно, §18) | нет устройства |
| §20 Security на устройстве | **BLOCKED** | нет устройства |
| §21 Observability | **PARTIAL** | маркеры логов верифицированы в коде (route/tool/tts/stt), готов протокол извлечения; полнота диагностической цепочки на инциденте — не проверена |
| §22 Сценарии A–H | **BLOCKED** | нет устройства |

## 3. Готовое обеспечение для прогона (создано)

`device-validation/` — исполняемый kit:

- `README.md` — протокол, маппинг на разделы ТЗ, таблица logcat-маркеров
  для замера latency (wake/STT/route/tool/TTS), оговорка о точности
- `01-environment.sh` — §1: полный дамп характеристик устройства/сборки
- `02-install-launch.sh` — §2–3: fresh install, `am start -W` startup time,
  состояние разрешений, crash/ANR-проверка
- `03-voice-loop-capture.sh` — §5–8: logcat `-v epoch` + screenrecord на серию
  из 20+ прогонов
- `04-resources-battery.sh` — §17–18: снапшоты RAM/threads/battery/wakelocks
  по меткам (start/idle/listening/active/long)
- `05-interruption-recovery.sh` — §13–14, 19: 20 итераций прерываний,
  force-stop → cold start, сеть off/on, симуляция недоступности сервера
- `RESULTS_TEMPLATE.md` — матрица с заранее проставленными PASS/BLOCKED и
  пустыми полями для исполнителя

## 4. Инструментировка замера (осознанное решение)

Точные sub-stage durations (wake_detected→stt_result и т.д.) существующими
логами измеримы приближённо (logcat epoch-ms по маркерам). Выделенная
инструментировка (6–8 trace-точек) сознательно НЕ добавлена: по правилам
проекта не добавлять функциональность до доказательства базового цикла, и
не трогать хрупкий оркестратор без device-тестов. Рекомендация: добавить
`OMNIX_TRACE`-маркеры сразу после первого качественного прогона.

---

## 5. Device Readiness (§25) — честная оценка

```text
Build:                  9/10  (CI-доказано; -1: signed release не собран)
Install:                BLOCKED
Activation:             BLOCKED
Voice:                  BLOCKED
Local Brain:            BLOCKED (локально доказан только контракт+JVM-тесты)
Cloud fallback:         BLOCKED (сервер-интеграционные тесты есть, E2E — нет)
Agent Core:             BLOCKED
Tools:                  BLOCKED
Bluetooth:              BLOCKED
Accessibility Privacy:  BLOCKED (16 JVM-тестов + instrumentation-хонести —
                        не доказательство runtime-поведения на банке/2FA)
Reliability:            BLOCKED
Performance:            BLOCKED
Battery:                NOT MEASURED
Security (static):      8/10 (архитектурные гарантии верифицируемы без устройства;
                        runtime-проверки — BLOCKED)
```

## 6. Ответы на три вопроса (§26)

### 1. Можно ли дать APK реальному пользователю сейчас?

**NO.** Основной голосовой цикл не доказан на устройстве — по правилам этого ТЗ
именно device validation является единственным доказательством. Дополнительно:
подписанного release-артефакта не существует (нет keystore-секретов), и
публичный сервер не развёрнут.

### 2. Что конкретно мешает назвать v0.2.0 production-ready?

Только реальные препятствия:

1. **Отсутствует device validation** всего пользовательского цикла (это и есть
   предмет текущего задания) — ни одного прогона на физическом телефоне.
2. **Нет подписанного release** (keystore+секреты не заданы владельцем) —
   распространение невозможно технически.
3. **Сервер не развёрнут публично** — activation/cloud недоступны вне CI.
4. **Local LLM не поставляется с приложением** (user-installed модель) —
   доля пользователей с работающим Local Brain неизвестна.
5. **Android coverage floor 24%/20%** — низкий барьер регрессий на
   device-специфичных путях.

### 3. Изменения для v0.3

```text
MUST HAVE:
 - Device validation цикла §5–8 по kit'у (≥20 повторов, latency-профиль)
 - Keystore + release secrets + первый подписанный staging-релиз
 - Публичный деплой сервера + activation E2E
 - Accessibility privacy runtime-проверка (банк/2FA/пароль) на устройстве
 - Решение о доставке локальной модели (on-demand download или user-installed
   с чёткой инструкцией) + измерения Local AI (latency/RAM/battery)

SHOULD HAVE:
 - OMNIX_TRACE-инструментация (6–8 точек) после первого качественного прогона
 - Реестр «задача → маршрут → инструмент» с реальной статистикой доли локальных
   исполнений (метрика route_local/route_cloud)
 - Поднятие Android coverage floor (24→35%) за счёт device-путей
 - Hardware QA-чеклист BT/Ear Mode как регулярная процедура

OPTIONAL:
 - Key pools провайдеров (при упоре в лимиты)
 - Neural embeddings активация (качество памяти)
 - Admin control plane (при росте пользователей)
```

## 7. Финальный backlog v0.3 (§27)

Формат: ID / проблема / evidence / severity / компонент / root cause / change / effort / priority.

| ID | Проблема | Evidence | Sev | Компонент | Root cause | Change | Effort | Pri |
|---|---|---|---|---|---|---|---|---|
| DV-01 | Device validation не проводилась | этот отчёт (все device-строки BLOCKED) | P0 | весь продукт | отсутствие физического устройства в цикле | прогон device-validation/kit на телефоне, заполнить матрицу | 2–3 дня | P0 |
| DV-02 | Нет подписанного релиза | build.gradle.kts signing secrets отсутствуют; release.yml не запускался | P0 | release | keystore не создан владельцем | docs/RELEASE.md §1–2 (владелец, ~10 мин + бэкап ключа) | S | P0 |
| DV-03 | Сервер не задеплоен публично | api.jarvis.ai не резолвится | P0 | ops | деплой не выполнялся | PRODUCTION_DEPLOYMENT.md на VPS; latency-смоук | M | P0 |
| DV-04 | Локальная модель не поставляется | docs/LOCAL_AI.md; OnDeviceLocalAi.isReady()==false без модели | P1 | local AI | решение о доставке не принято | on-demand download UX или чёткий first-run флоу + замеры | M | P1 |
| DV-05 | Нет численной телеметрии маршрутов | в логах только текстовые route-маркеры | P1 | observability | инструментировка отложена | OMNIX_TRACE-точки + счётчики route_local/route_cloud | S | P1 |
| DV-06 | Coverage floor device-путей 24/20 | build.gradle.kts | P2 | testing | исторический уровень | +тесты decision/tools до ≥35% | M | P2 |
| DV-07 | Hardware QA не формализован | TEST_QUALITY (gated) удалён из docs; процедура не писана | P2 | QA | ранняя стадия | чеклист BT/SCO/фоны/звонки в device-validation/ | S | P2 |

---
*Отчёт составлен строго по правилам §24: ни один device-тест не помечен PASS
без устройства. Kit готов к исполнению владельцем или агентом с device-доступом.*
