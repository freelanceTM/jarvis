# Ear Mode: аудит непрерывного переводчика через Bluetooth

**Ревизия:** `main` · Код-ревью цепочки Clip → Bluetooth → Audio → OMNIX →
STT → AI (перевод) → TTS → Clip и её прерываний. Статусы: OK = поведение
уже корректно; FIX = закрыто этой ревизией; NOT MEASURED = требует
устройства (код ≠ измерение).

## Два входа в Ear Mode

1. **Голосом** («переводчик», «синхронный перевод», «переводи собеседника») →
   `VoiceInteractionOrchestrator.processUserQuery` → `startLiveEarInterpreter()`:
   wake-word остановлен, режим `LIVE_EAR_INTERPRETER`, STT-луп непрерывный,
   перевод «последний побеждает» (CR-22: `translationJob` cancel + FLUSH TTS).
2. **Экраном** (`OmnixNavGraph` → `TranslatorRoute` → `LiveInterpreterViewModel`):
   параллельный перевод без остановки записи, TTS через `speakQueued`.

Оба пути используют общие синглтоны: `SpeechRecognizerManager`,
`TextToSpeechManager`, `BluetoothAudioRouter`, `LiveTranslatorEngine`.

## Результаты проверки

| # | Сценарий | Статус | Что в коде |
|---|---|---|---|
| 1 | **disconnect** | FIX | `ACL_DISCONNECTED`/`AUDIO_BECOMING_NOISY` → `routeAudioToSpeaker()` + `checkHeadsetConnection()` (BluetoothAudioRouter.connectionReceiver). Дыра была: `routeAudioToEarbud()` без гарнитуры безусловно ставил `MODE_IN_COMMUNICATION` + `startBluetoothSco` — и вызывался на каждую переведённую фразу (оркестратор-луп и `dispatchParallelTranslation`). Теперь: без наушника — guard «остаёмся в MODE_NORMAL». |
| 2 | **reconnect** | FIX / NOT MEASURED | `ACL_CONNECTED` → `checkHeadsetConnection()`-гейт → `Connected` + `routeAudioToEarbud()`; следующая фраза переводчика переезжает в наушник сама (per-phrase re-route сохранён). Время SCO-reconnect на устройстве — NOT MEASURED. |
| 3 | **phone call** | FIX / NOT MEASURED | Пауза: `JarvisVoiceService.dispatchCallState` (RINGING/OFFHOOK → `pauseForPhoneCall` → `stopAll`: STT+TTS+translationJob общих синглтонов гаснут). Дыры были: (а) после звонка `resumeAfterPhoneCall` сбрасывал в STANDBY, теряя Ear Mode — теперь `interpreterActiveBeforeCall` возвращает `LIVE_EAR_INTERPRETER`; (б) экран переводчика оставался в stale `isListening` и не продолжал после звонка — теперь `LiveInterpreterViewModel.observeOrchestratorMode()` ставит паузу/возобновляет. Без `READ_PHONE_STATE` пауза звонка отключена (логируется) — NOT MEASURED. |
| 4 | **music** | FIX / NOT MEASURED | Audio focus отсутствовал во всём приложении (grep `requestAudioFocus` = 0): TTS говорил поверх музыки, музыка не duck-илась. Теперь `TextToSpeechManager` держит `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (USAGE_ASSISTANT), пока есть не доигравшие utterance; LOSS → `stop()`. Поведение duck на конкретных плеерах — NOT MEASURED. |
| 5 | **notification** | FIX | Тот же фокус: чужие нотификации duck-ят нашу речь (MAY_DUCK обратно), наша речь больше не «поверх всего» без фокуса. `ToneGenerator`-чимы wake/cancel остаются без фокуса (короткие, принятый компромисс). |
| 6 | **competing Bluetooth device** | FIX | (а) `ACL_CONNECTED` от любого BR/EDR-устройства (часы, машина) считался наушником — теперь гейт по реальному аудиовыходу; (б) `routeAudioToEarbud` брал `firstOrNull{SCO‖BLE‖wired}` — произвольное из двух устройств; теперь BT-first: SCO/BLE важнее проводных. |
| 7 | **microphone** | FIX | Экранный путь не останавливал wake-word `AudioRecord` (AlisaStyleWakeWordEngine держит собственный `AudioRecord`) — с Android 10 приоритет у одного захватчика: STT деградировал, wake ловил тишину. Теперь `toggleListening(on)` → `wakeWordDetector.stopListening()`, возврат — `restoreWakeWordIfNeeded()` (только при STANDBY сервиса). Голосовой путь OK: `startLiveEarInterpreter` останавливает wake-word. |
| 8 | **speaker** | FIX | Выход из Ear Mode («стоп» → `handleCancel` → `startStandbyMode`; `stopServicePipeline`; `onCleared` экрана) оставлял `MODE_IN_COMMUNICATION`/SCO навсегда. Теперь: `startStandbyMode` → `restoreDefaultRouting()`, `stopServicePipeline` → `routeAudioToSpeaker()`, экран → `stop()` TTS + `restoreDefaultRouting()`. |
| 9 | **audio focus** | FIX | См. #4/#5: единая точка — `TextToSpeechManager` (все TTS приложения идут через него); политика `mapFocusChange` закреплена `TextToSpeechFocusPolicyTest`. |

## Схема прерываний (после фиксов)

```
звонок RINGING/OFFHOOK ─► dispatchCallState ─► pauseForPhoneCall
                              │                  ├─ запомнить: был ли Ear Mode
                              │                  └─ stopAll (STT/TTS/перевод)
наушник отключился ─► routeAudioToSpeaker (guard: earbud-роутер без гарнитуры — no-op)
наушник вернулся   ─► checkHeadsetConnection-гейт ─► routeAudioToEarbud (BT-first)
фокус LOSS        ─► TTS stop();  фокус TRANSIENT ─► говорим дальше
конец звонка      ─► resumeAfterPhoneCall ─► Ear Mode | STANDBY
выход из режима   ─► restoreDefaultRouting (STANDBY) / routeAudioToSpeaker (pause)
```

## NOT MEASURED (нужно устройство, Clip + наушники)

- Время SCO-connect/reconnect и слышимый щелчок при переключении маршрута.
- Duck-поведение реальных плееров (Spotify/Yandex Music) под USAGE_ASSISTANT.
- Микрофонная слышимость собеседника через Clip-микрофон в SCO vs телефонный.
- Параллельная работа двух наушников (primary/secondary bud) при Ear Mode.
