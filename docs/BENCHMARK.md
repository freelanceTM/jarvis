# JARVIS Benchmark v1 — QA baseline 2026-08-20

**Итог после аудита: routing accuracy = 99.0% (99/100).**
До исправлений baseline был 73.0% (73/100). Полные актуальные артефакты:
[`docs/benchmark/`](benchmark/) — `benchmark-report.txt`,
`benchmark-results.json`, `benchmark-results.csv`.

## Запуск

```bash
./gradlew :app:testDebugUnitTest --tests '*BenchmarkRunnerTest*'
# артефакты: app/build/benchmark/

./gradlew :app:testDebugUnitTest --tests '*BenchmarkRegressionTest*'
```

## Что измеряется

Прогон идёт через настоящие `FastCommandRouter`, `ExecutionDecisionEngine`,
`CognitivePlanner`, `AgentCognitiveLoop`, `ToolExecutor`, `OnDeviceLocalAi`.
Подменены только внешние границы, недоступные на JVM: нативный MediaPipe
runtime, сеть/AI-провайдеры и Android-инструменты.

Датасет: 100 вручную подготовленных команд:

```text
DEVICE 25 · LOCAL_AI 22 · CLOUD_AI 15 · CLOUD_WEB 10
AGENT 10 · AMBIGUOUS 6 · EDGE_CASE 7 · PRIVACY 5
```

## Результаты после QA-аудита

```text
Routing accuracy:        99.0% (99/100)
DEVICE:                  100.0% (25/25)
LOCAL_AI:                100.0% (22/22)
CLOUD_AI:                100.0% (15/15)
CLOUD_WEB:               100.0% (10/10)
AMBIGUOUS:               100.0% (6/6)
EDGE_CASE:               100.0% (7/7)
PRIVACY:                 100.0% (5/5)
AGENT:                    90.0% (9/10)
False Local:             0
Unnecessary Cloud:       0
Device false positives:  0
```

Исправления, поднявшие точность с 73% до 99%:

1. Общие слова «следующий», «предыдущий», «продолжи», `play` больше не
   считаются media-командой внутри произвольного вопроса.
2. «Интернет» маршрутизируется в Wi-Fi только при явной команде подключения/
   переключения/проверки, а не в web-поисковых запросах.
3. `ScenarioMatcher` больше не ловит `еду` внутри «следующий» и общие слова
   «отчёт/состояние/статус» внутри аналитических запросов.
4. Добавлены формы «ко сну» / «режим сна» и корректный POWER_SAVING route.
5. Пустой запрос отклоняется до локального/облачного executor.
6. Явно недоопределённые команды возвращают clarification, а не выдуманный
   локальной моделью ответ.
7. Длинные задачи с явными признаками анализа/кода/документации не отдаются
   1B-модели; PRIVATE/SENSITIVE при этом остаются локальными.

## Оставшийся неверный кейс

`AGENT-010`: «Сравни десять вариантов ноутбуков и выбери лучший в пределах
бюджета» идёт в `CLOUD_AI`, а датасет ожидает `AGENT`. Для настоящего Agent-route
нужен LLM-план произвольной многошаговой задачи. Создавать фиктивный локальный
план без нужных инструментов было бы хуже честного облачного ответа, поэтому
этот известный пробел не маскировался.

## Ограничения измерения

- JVM, не физическое Android-устройство.
- Local AI cold/warm latency и TTFT симулированы.
- Provider/network latency симулирована; реальный fallback проверяется отдельно
  server integration-тестами.
- RAM, CPU, battery, thermal и Bluetooth-аудио не измерены.
- Стоимость облака неизвестна: тарифов нет в конфигурации.
- Датасет синтетический; human evaluation качества ответов не проводилась.

## Regression gate

`BenchmarkRegressionTest` теперь блокирует:

- routing accuracy ниже 99%;
- DEVICE/LOCAL_AI/CLOUD_AI/CLOUD_WEB/AMBIGUOUS ниже 100%;
- любой device false positive;
- любой false-local;
- любой PRIVATE/SENSITIVE запрос, ушедший в cloud.

Порог намеренно усилен вместе с regression-тестами; прежний плохой baseline
73% больше не считается допустимым.
