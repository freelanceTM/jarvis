# Memory: три уровня и retrieval перед LLM

**Ревизия:** `main` · Принцип: **в LLM не отправляется всё** — перед моделью
стоит стадия retrieval: `Query → Memory retrieval → Relevant memories only →
AI`. Это уменьшает и latency (меньше токенов, меньше round-trip «модель
просит тул, чтобы вспомнить»), и стоимость (токены = деньги на cloud-полосе).

## Три уровня

```
Conversation Memory      последние N реплик диалога (окно 10, Room `messages`)
        ↓                → request.history → messages[] провайдера
Session Memory           WorkingMemory: слоты диалога (приложение/контакт/
        ↓                человек/место/тема), анафора, LRU-кэш TTL 30 мин
Long-term Memory         JarvisMemoryManager: Room memories/facts/preferences,
                         hybrid retrieval (cosine + TF-IDF + importance +
                         recency + frequency), дедупликация, forget
```

| Уровень | Реализация | Как попадает в LLM | Ограничитель |
|---|---|---|---|
| Conversation | `MessageRepository.getRecentMessages(limit=10)` (SendPromptUseCase) | `ExecutionRequest.history` → `AIRepository` → `messages[]` | окно 10, не вся история |
| Session | `WorkingMemory` ( WorkingMemory.kt): `resolveContextualQuery` (анофора ДО модели), `getWorkingContextSummary()` | сводка слотов внутри retrieval-блока | слоты, LRU-128, TTL 30 мин |
| Long-term | `JarvisMemoryManager.recall()` | `buildPromptMemoryContext(query)` → `ExecutionRequest.memoryContext` | top-3, бюджет ~180 слов / ≤800 симв. |

## Retrieval-стадия (главный фикс)

`buildPromptMemoryContext()` существовал, но **не вызывался ни разу** в
продакшене — long-term память доходила до модели только если LLM сам решал
вызвать `RecallMemoryTool` (лишний round-trip: PLAN → tool → второй AI-вызов).
Теперь:

```
SendPromptUseCase:
  resolvedPrompt ──► memoryManager.buildPromptMemoryContext(query)
                         │  top-3 по гибридному скору; пусто = пусто (не заглушка)
                         ▼
  ExecutionRequest.memoryContext (≤800 символов)
                         │
        ┌────────────────┴─────────────────┐
        ▼ CLOUD                            ▼ LOCAL
RepositoryCloudAiExecutor:        JarvisLocalPromptBuilder:
+блок к systemPrompt               +блок перед запросом
(токены = деньги → bounded)        (офлайн-модель знает факты:
                                   «как зовут дочь?» без сети)
```

Инструмент `RecallMemoryTool` остаётся для явных вопросов «что ты помнишь о…».

## Приватность и бюджет

- `memoryContext` **включён в privacy-классификацию**
  (`withContextualClassification`): память выведена из реплик пользователя —
  приватный факт в памяти не уходит в облако под «безобидным» запросом
  (тест: «пароль от роутера» в памяти → isCloudRestricted).
- Клип: 800 символов ≈ 200 токенов — на фоне экономии от отсутствия
  full-history/full-memory выгрузки; локальная модель (контекст 2048) тоже
  выдерживает.
- Экранный контекст в память не пишется (ScreenContentPrivacy: плейсхолдер в
  истории) — retrieval его не поднимет.
- Server-side Cost Control продолжает работать поверх: history ≥ 4000 симв.
  → класс HARD.

## NOT MEASURED

Качество retrieval (p@3) на реальном корпусе пользователя и субъективная
достаточность top-3 — проверять на устройстве через историю запросов и
`VoiceLatencyMetrics` (сегмент AI).
