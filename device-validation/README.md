# OMNIX v0.2.0 — Device Validation Kit

Исполняемый набор процедур для реальной проверки на физическом Android-устройстве
(разделы 1–23 ТЗ на device validation). Статусы результатов — только
`PASS / FAIL / BLOCKED / NOT TESTED / PARTIAL` (§24); «works in code/unit tests»
доказательством не является.

## Предпосылки

```text
adb в PATH, USB-отладка включена, один подключённый девайс
APK: CI-артефакт JARVIS-v0.2-dev.apk (devDebug) ИЛИ локально собранный
     release после настройки подписи (docs/RELEASE.md)
Сервер: развёрнутый инстанс (см. docs/PRODUCTION_DEPLOYMENT.md) —
        БЕЗ него activation/cloud-сценарии = BLOCKED
Наушники BT — для сценариев Ear Mode
Тестовые данные: лицензионный scratch-code, «банковское»/парольное приложение
```

## Порядок

```text
1. ./01-environment.sh                  → device-env.txt   (§1: характеристики)
2. ./02-install-launch.sh               → install-launch.txt (§2–3: build/install/permissions)
3. Ручная активация + серверные логи    → activation.txt   (§4)
4. ./03-voice-loop-capture.sh           → logcat-epoch.log + screen-*.mp4
   + заполнение RESULTS_TEMPLATE.md §5–8, 22 (20+ повторений на сценарий)
5. ./04-resources-battery.sh            → resources-*.txt  (§17–18: RAM/CPU/battery)
6. ./05-interruption-recovery.sh        → recovery.txt     (§13–14, 19)
7. Ручные BT/A11y/security сценарии     → RESULTS_TEMPLATE.md §15–16, 20
8. Свести матрицу, проставить latency по logcat-epoch
```

## Замер latency по логам

Существующие маркеры приложения (logcat `-v epoch`, миллисекунды):

| Стадия | Маркер (tag: текст) |
|---|---|
| Wake listening start | `WakeWord*: startListening` |
| Wake detected | переход в VERIFYING_KEYWORD (orchestrator) / wake-событие |
| STT start | `SpeechRecognizerManager: startListening session=…` |
| STT result | финальный результат распознавания (mode-переход AI_THINKING) |
| Router decision | `DecisionEngine: route=$type \| reason=… \| confidence=…` |
| Tool exec | `ToolExecutor: …` / `route=DEVICE_TOOL tool=… status=…` |
| TTS | `NeuralVoicePlayer/TTS*` события старта/завершения utterance |

Точный sub-stage тайминг (wake_detected→stt_result и т.п.) по этим маркерам —
**приблизительный** (±детерминированные промежутки между логами). После первого
успешного качественного прогона рекомендуется минимальная инструментировка
(6–8 `Log.i("OMNIX_TRACE", "stage=…")` точек) — отдельным PR, не сейчас.

## Правила фиксации

- Каждый запуск: timestamp, сценарий, наблюдение, PASS/FAIL/BLOCKED.
- Latency: P50/P75/P90/P95/P99/min/max/avg по ≥20 повторам (§6).
- FAIL-фиксация: logcat-фрагмент + видео + ожидаемое vs фактическое.
- Battery/точные значения, если не измеряются честно, помечаются `NOT MEASURED`.
