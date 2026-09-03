# Battery: фазы и архитектура «idle → wake → heavy → idle»

**Ревизия:** `main` · Код-ревью фаз батареи. Статусы: OK = поведение уже
корректно в коде; FIX = закрыто в этой ревизии; NOT MEASURED = нужно
подтверждение на реальном устройстве (доктрина аудита v0.3: код ≠ измерение).

## Архитектура (постановка)

```
Idle → lightweight wake detection → user speaks → heavy processing → return idle
```

## Фазовая таблица

| Фаза | Что происходит | Статус | Якоря |
|---|---|---|---|
| **Idle** | FGS `microphone` в STANDBY держит STT-частичные результаты для wake-word (системный `SpeechRecognizer` = lightweight detection делегирован системе); **тяжёлая модель НЕ в памяти** (не грузится на старте; теперь выгружается по idle — см. ниже) | FIX | `JarvisVoiceService`, `MediaPipeModelManager` |
| **Wake-word** | детект → `VERIFYING_KEYWORD` (anti-false-trigger) → `LISTENING_USER_QUERY`; часы `VoiceLatencyMetrics` фиксируют сегмент | OK | `VoiceInteractionOrchestrator` |
| **Listening** | запись запроса системным STT, silence-таймеры закрывают фазу | OK | `SpeechRecognizerManager`, `SILENCE_AFTER_PARTIAL_MS` |
| **Local inference** | ленивая загрузка модели (~1–3 c) → инференс → **после 5 минут неактивности автоматическая выгрузка** (`IdleUnloadScheduler`); окно больше худшего tool-таймаута (≤4 c) — выгрузка не закроет движок посреди генерации; memory pressure остаётся немедленной выгрузкой | FIX | `MediaPipeModelManager.modelIdleUnloadMs`, `runtimeOrNull()` → `noteUsed()` |
| **Bluetooth** | SCO/`setCommunicationDevice` только в активных фазах ( Ear Interpreter / разговор); отключение отслеживается ACL disconnect → `Disconnected` | NOT MEASURED | `BluetoothAudioRouter` |
| **TTS** | движок переиспользуется (не создаётся на каждый speak), shutdown на destroy, гонки под `speakMutex` | OK | `TextToSpeechManager` |
| **Background** | FGS по назначению (микрофон wake-word); `BatteryOptimizationHelper` — пользовательское исключение из Doze; состояние «background» не добавляет тяжёлых задач (модель выгружена по idle) | NOT MEASURED | манифест, `BatteryOptimizationHelper` |

## Главный фикс: «не держать тяжёлую модель активной постоянно»

Было: модель выгружалась только по memory pressure — после первого инференса
~529 МБ RSS оставались резидентными до давления системы. Стало:

```
первый Local AI запрос → загрузка → инференс(ы) → idle 5 мин → unload → idle
                                     ↑ следующий запрос — ленивая перезагрузка
```

Каждое использование (`runtimeOrNull()`/успешная загрузка) вызывает
`IdleUnloadScheduler.noteUsed()`; окно `modelIdleUnloadMs` = 5 минут.
Диалог не платит перезагрузкой, пауза освобождает память. Потоки:
`OnDeviceLocalAi` (Local AI полоса) и `LocalLlmTranslationProvider` (перевод)
оба идут через `runtimeOrNull()` — единая точка учёта.

## Измерение на устройстве (следующий шаг, NOT MEASURED)

Проверить на реальном Clip/телефоне: дрейф батареи за час в STANDBY (wake
word), за 10 локальных инференсов подряд + выгрузка по idle (RSS до/после),
TTS-цикл, SCO-сессия. Инструменты: `dumpsys batterystats`, RSS по
`dumpsys meminfo <pkg>`, `VoiceLatencyMetrics` для времени фаз.
