# Local AI — on-device LLM (Этап 2)

Локальная модель подключена как **execution backend** уже существующего
`ExecutionDecisionEngine`. Маршрутизация Этапа 1 не изменена.

```
FastCommandRouter
        ↓
ExecutionDecisionEngine          ← НЕ изменён
        ↓
LocalAiExecutor (порт Этапа 1)
        ↓
CompositeLocalAiExecutor
        ├── WorkflowExecutor      — процедурная память (макросы, мгновенно)
        └── OnDeviceLocalAi       — локальная LLM
                ↓
        LocalModelManager         — lifecycle, lazy load, unload
                ↓
        LocalModelRuntime         — интерфейс инференса
                ↓
        MediaPipe LLM Inference   — нативный движок
```

---

## 0. ExecutionRouter: локальная обработка там, где cloud не нужен

Цель — **НЕ** «локальная LLM решает всё». Цель — каждая полоса получает
ровно тот класс запросов, для которого облако не требуется. Роутер — это
уже существующие `FastCommandRouter` + `ExecutionDecisionEngine` (новых
слоёв не вводилось):

| Полоса (spec) | Реализация в коде | Примеры |
|---|---|---|
| **LOCAL TOOL** | `FastCommandRouter.route()` → `ExecutionDecisionEngine.tryDeviceTool()` (P1, `DecisionReason.FAST_ROUTER_CONFIDENT`); без LLM вообще | «Открой Telegram», «Громкость 50%», «Поставь таймер», «Включи Bluetooth» |
| **LOCAL AI** | `OnDeviceLocalAi` (Gemma, user-installed) + `WorkflowExecutor` (процедурные макросы) через `LocalAiExecutorAdapter` (P2); AGENT-полоса (P4, `CognitivePlanner` + локальные tools) — многошаговые планы тоже исполняются локально | классификация, простая интерпретация, **короткий перевод** (`LocalLlmTranslationProvider`), разрешение контекста (`WorkingMemory`/`AnaphoraContextEngine`), memory retrieval (локальная Room) |
| **CLOUD** | `runCloud()` (P3) + облачный `LlmTranslationProvider` | reasoning, research, long documents (перевод >500 символов), требует сеть (`requiresWeb`), большой контекст, всё, что локальные полосы честно не взяли |

Ключевые свойства (все закреплены тестами/инвариантами):

- **Local-first с честным fallback**: локальные полосы пробуются первыми;
  их неуспех (`Uncertain`/`Unsupported`/`ModelUnavailable`) передаётся
  следующей полосе — никакая не изображает успех (doctrine Fake-Success).
- **Перевод**: `LiveTranslatorEngine` сортирует провайдеры
  `sortedByDescending { isOffline }` — локальная модель (`local_llm`) отвечает
  первой, облако (`llm`) — fallback. Бонус приватности: PRIVATE/SENSITIVE
  тексты, которые облако блокирует (C-02), локальный провайдер переводит
  на устройстве.
- **Приватность сохраняет приоритет**: PRIVATE/SENSITIVE без согласия
  не доходят до облака в любой полосе (privacy-гейты engine'а).
- **Модель не в APK** (~529 МБ, user-installed): без модели локальные полосы
  честно сообщают `Unsupported`/`ModelUnavailable`, продукт работает через
  облако как раньше.

### Метрики ExecutionRouter (`ExecutionRouterMetrics`)

Считается каждый запрос через `ExecutionDecisionEngine.execute`:

| Счётчик | Что это |
|---|---|
| `total_requests` | все запросы (включая отказы/уточнения) |
| `tool_requests` | полоса LOCAL TOOL |
| `local_requests` | полоса LOCAL AI (+`agent_requests`, +`direct_requests`) |
| `cloud_requests` | реально отправленные в облако (attempt) |
| `failed_local` | LOCAL AI честно отказал (без эскалации) |
| `cloud_escalations` | облако взяло запрос, который локальная полоса опросила и не взяла (skip по `requiresWeb` — НЕ эскалация) |

**Local Execution %** = (tool + agent + local + direct) / total · 100
**Cloud Execution %** = cloud / total · 100

Целевой ориентир первой версии — **Tool/local execution 60–70%+**. Это
метрика, а не жёсткое требование: на роутинг она не влияет; 80%+ без
ухудшения качества — отлично; деградация качества ради процента запрещена.
Проценты за период — в логе (`ExecRouterMetrics`, каждые 25 запросов) и в
`snapshot()` для будущего экрана диагностики.

---

## 1. Выбор runtime

| Runtime | Android | CPU | GPU/NPU | Модели | Интеграция с Kotlin | APK impact | Сложность | Лицензия | Вывод |
|---|---|---|---|---|---|---|---|---|---|
| **MediaPipe LLM Inference** (`tasks-genai`) | API 24+ | да | GPU (OpenCL), авто-fallback | Gemma 1/2/3, Phi-2, StableLM, Falcon | готовый Java/Kotlin API, AAR из Maven | ~26 МБ (arm64) | низкая | Apache 2.0 | **выбран** |
| llama.cpp (JNI) | любой | да, лучший на CPU | частично (Vulkan) | максимум форматов GGUF | нужен свой JNI-слой + NDK-сборка | 2-5 МБ + своя сборка | высокая | MIT | отклонён: нужен собственный C++/JNI и CI-сборка NDK |
| ONNX Runtime Mobile | API 21+ | да | NNAPI/QNN | ONNX; LLM-путь сырой | Java API есть | 10-20 МБ | средняя | MIT | отклонён: генеративный LLM-путь заметно менее готов, чем classification |
| MLC LLM / TVM | API 24+ | да | Vulkan | свой формат | нужна сборка из исходников | большой | высокая | Apache 2.0 | отклонён: сборка из исходников в CI, нет готового Maven-артефакта |

**Почему MediaPipe.** Решающими были три фактора, а не популярность:

1. **Готовый Maven-артефакт** — `com.google.mediapipe:tasks-genai` ставится
   одной строкой. Остальные варианты требуют NDK-сборки в CI, которой у
   проекта сейчас нет (GitHub Actions собирает обычный `assembleDebug`).
2. **Реальная отмена инференса** — в API есть
   `LlmInferenceSession.cancelGenerateResponseAsync()`. Это прямое требование
   пункта 18 ТЗ; проверено по фактическому API из AAR, а не по документации.
3. **Автоматический выбор бэкенда** — `Backend.DEFAULT` пробует GPU и
   откатывается на CPU. Жёстко фиксировать GPU нельзя: на части устройств
   инициализация OpenCL-делегата падает.

### ⚠️ Известный риск: API помечен deprecated

Начиная с **tasks-genai 0.10.33** Google пометил `LlmInference`,
`LlmInferenceSession` и `ProgressListener` как `@Deprecated` — идёт миграция
на LiteRT-LM. При этом:

- артефакта `com.google.ai.edge.litert:litert-lm` на Google Maven **ещё нет** —
  то есть цели миграции пока не существует;
- сам API полностью работоспособен.

Поэтому в `MediaPipeLlmRuntime.kt` стоит **точечный** `@file:Suppress("DEPRECATION")`
(в одном файле, не глобально — у проекта включён `warningsAsErrors`).

Когда LiteRT-LM появится в Maven, менять нужно будет **только**
`MediaPipeLlmRuntime.kt`: контракт `LocalModelRuntime` не изменится, а
`ExecutionDecisionEngine` не знает о существовании MediaPipe вообще.

---

## 2. Выбор модели — Gemma 3 1B IT (int4, QAT)

| Критерий | Значение |
|---|---|
| Размер файла | **529 МБ** (int4 QAT) |
| RAM (RSS) | ~1.1 ГБ (CPU), ~1.2 ГБ (GPU) |
| Контекст | 2048 токенов (в конфиге проекта) |
| Decode | 47-56 ток/с |
| Prefill | 322 ток/с (CPU) … 2585 ток/с (GPU) |
| Time-to-first-token | ~3.1 с (CPU), ~4.5 с (GPU) |
| Языки | 140+, включая русский |
| Лицензия | Gemma Terms of Use |

Источник цифр — официальная карточка
[litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT).

**Почему именно 1B, а не 2B/4B.** Ассистент работает в фоне рядом с STT, TTS,
Room и Compose UI. Gemma 3 4B в int4 — это уже ~3 ГБ RSS, что на устройстве с
6-8 ГБ приведёт к тому, что Android убьёт процесс в фоне. 1B укладывается в
~1.1 ГБ и оставляет запас остальному приложению.

**Альтернативы, которые рассматривались:** Qwen2.5 0.5B (лучше по русскому на
своём размере, но заметно слабее в рассуждениях), Phi-3 mini (3.8B — слишком
тяжёлая), Gemma 3n E2B (мультимодальная, избыточна для текстового этапа).

---

## 3. Модель НЕ входит в APK

529 МБ нельзя класть в APK: лимит Google Play на AAB — 200 МБ для базового
модуля, а размер загрузки критичен для пользователя.

Отсутствие модели — **штатное состояние** `LocalModelState.NotInstalled`.
В этом случае `LocalAi` возвращает `Unsupported`, и движок спокойно уходит в
Cloud AI. Ошибки пользователь не увидит.

### Установка модели для разработки

```bash
# 1. Скачать (нужен доступ к gated-репозиторию Gemma на HuggingFace)
#    https://huggingface.co/litert-community/Gemma3-1B-IT
#    файл: gemma3-1b-it-int4.task

# 2. Положить во внутреннее хранилище приложения
adb push gemma3-1b-it-int4.task /data/local/tmp/
adb shell run-as com.jarvis.assistant mkdir -p files/llm
adb shell "cat /data/local/tmp/gemma3-1b-it-int4.task | run-as com.jarvis.assistant tee files/llm/gemma3-1b-it-int4.task > /dev/null"

# 3. Проверить
adb shell run-as com.jarvis.assistant ls -la files/llm/
```

Ожидаемый путь: `/data/data/com.jarvis.assistant/files/llm/gemma3-1b-it-int4.task`

### Что делать в продакшене (вне Этапа 2)

Рекомендуемый вариант — **скачивание по требованию**: `DownloadManager` или
Play Feature Delivery (`install-time: on-demand`) с прогрессом в настройках и
проверкой SHA-256. Реализация вынесена за рамки этапа: пункт 25 ТЗ запрещает
добавлять новые подсистемы, а загрузчик — это отдельный UX + сеть + storage.

---

## 4. Жизненный цикл модели

```
первый Local AI запрос → initialize() → загрузка (~1-3 с) → Ready
        последующие запросы → инференс без перезагрузки
        onTrimMemory / onLowMemory → unload()
```

- **Lazy**: при старте приложения модель НЕ грузится — иначе +1 ГБ RSS и
  секунды к startup у пользователей, которые локальной моделью не пользуются.
- **Один экземпляр**: `Mutex` в `MediaPipeModelManager` не даёт параллельным
  запросам загрузить модель дважды.
- **Memory pressure**: менеджер подписан на `ComponentCallbacks2`; при
  `TRIM_MEMORY_RUNNING_LOW` и выше нативные ресурсы освобождаются.
- **Проверка RAM перед загрузкой**: если `ActivityManager.MemoryInfo.lowMemory`
  или доступно меньше `minRuntimeMemoryMb`, загрузка не начинается.

---

## 5. Границы ответственности Local AI

| Ситуация | Поведение | Итог |
|---|---|---|
| `requiresWeb = true` | не вызывается вовсе | `Unsupported` → Cloud AI |
| `requiresDeviceControl = true` | не вызывается | `Unsupported` → device path |
| device-команда («Открой Telegram») | до Local AI не доходит — забирает FastCommandRouter | `DEVICE_TOOL` |
| `privacyLevel = PRIVATE` | **обрабатывается локально** | `LOCAL_AI`, облако не вызывается |
| модель не установлена | `Unsupported` | Cloud AI |
| runtime упал | `Error` | `ExecutionResult.Error`, без эскалации в облако |
| запрос > 1200 символов | `Unsupported` | Cloud AI |

Локальная модель **только генерирует текст**: не запускает Activity, не жмёт
кнопки, не меняет настройки, не ходит в сеть. Это закреплено и в system prompt,
и в guard-условиях `OnDeviceLocalAi`.

**Почему `Error` не эскалируется в облако.** Движок Этапа 1 детерминирован
(пункт 16 ТЗ): один проход по цепочке. «Модель сломалась» — честная ошибка, а
не повод молча отправить, возможно приватный, запрос в сеть. Ситуация «модели
просто нет» — это `Unsupported`, и она в облако уходит штатно.

---

## 6. Threading и отмена

- Инференс идёт на `dispatchers.default` (существующий `CoroutineDispatchers`),
  никогда на Main.
- Загрузка модели — тоже на `default`.
- Отмена корутины → `session.cancelGenerateResponseAsync()` через
  `invokeOnCancellation`. `CancellationException` пробрасывается наружу, а не
  превращается в `Error`.

---

## 7. Логи

Тег `LocalAI`:

```
model = gemma3-1b-it-int4 | runtime = mediapipe-llm | loaded = true | loadTimeMs = 1842
inference started | runtime=mediapipe-llm | source=VOICE | privacy=NORMAL | maxTokens=192
inference completed | latencyMs=2310 | ttftMs=780 | promptChars=612 | responseChars=214 | ~tok/s=37.1
```

Содержимое запроса не логируется. Приватный текст дополнительно скрывается
через `ExecutionRequest.loggableText` (`<redacted:N chars>`).

---

## 8. Benchmark

Замеры на реальном устройстве в этой среде **не проводились** — Android SDK и
физического устройства в CI-песочнице нет. Приведённые в разделе 2 цифры взяты
из официальной карточки модели, а не измерены нами.

Что нужно измерить на реальном устройстве перед релизом:

```
model load time      (ожидание: 1-3 с, зависит от storage)
time to first token  (ожидание: 1-3 с CPU)
decode tokens/sec    (ожидание: 40-55 CPU)
peak RSS             (ожидание: ~1.1-1.2 ГБ)
```

Метрики уже собираются в коде (`InferenceMetrics`) и пишутся в logcat — на
устройстве достаточно снять `adb logcat -s LocalAI`.

---

## 9. Android-валидация

| Пункт | Статус |
|---|---|
| minSdk | 29 — выше требования MediaPipe (24) |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86_64`; `x86` исключён |
| Размер native libs | ~26 МБ arm64, ~19 МБ armeabi-v7a, ~29 МБ x86_64 |
| APK impact | +26 МБ на arm64-устройстве (при использовании ABI splits) |
| Модель в APK | нет — внешняя установка |
| R8 / ProGuard | правила добавлены (JNI-классы MediaPipe, protobuf, Guava) |
| Эмулятор | x86_64 поддержан; GPU-делегат на эмуляторе обычно недоступен → CPU |
| Фоновая работа | инференс запускается только по запросу пользователя |

---

## 10. Файлы

Создано:

- `agent/localai/LocalAiModels.kt` — `LocalAiResult`, `GenerationConfig`,
  `InferenceMetrics`, `LocalModelState`, `LocalModelSpec`
- `agent/localai/LocalAiContracts.kt` — `LocalAi`, `LocalModelRuntime`,
  `LocalModelManager`, `LocalPromptBuilder`
- `agent/localai/JarvisLocalPromptBuilder.kt` — chat-шаблон Gemma 3 + system prompt
- `agent/localai/OnDeviceLocalAi.kt` — правила и классификация исходов
- `agent/localai/mediapipe/MediaPipeModelManager.kt` — lifecycle
- `agent/localai/mediapipe/MediaPipeLlmRuntime.kt` — инференс + отмена
- `agent/decision/LocalAiExecutorAdapter.kt` — `CompositeLocalAiExecutor`
- тесты: `OnDeviceLocalAiTest` (14), `LocalAiRoutingIntegrationTest` (8)

Изменено:

- `di/HiltModules.kt` — модуль `LocalAiModule`, порт переключён на
  `CompositeLocalAiExecutor`
- `agent/decision/ExecutionAdapters.kt` — удалён `ProceduralLocalAiExecutor`
  (его роль поглотил `CompositeLocalAiExecutor`)
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — зависимость + abiFilters
- `app/proguard-rules.pro` — правила R8

**Не изменялись:** `ExecutionDecisionEngine`, `FastCommandRouter`,
`ToolExecutor`, `JarvisTool`, `AgentCognitiveLoop`, `CognitivePlanner`, STT, TTS.
