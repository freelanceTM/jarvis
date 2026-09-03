# Accessibility Lockdown: приватность чтения экрана

**Ревизия:** `main` · Слой: `agent/tools/accessibility/` + точки персистентности

## Требуемый пайплайн

```
Accessibility → capture UI → privacy filter → LLM        (реализовано)
Accessibility → полный экран → Cloud LLM                 (закрыто: это была утечка)
```

Экральный контент можно **показать** и **озвучить** (локальные поверхности),
но нельзя **сохранить в историю чата** — история (`getRecentMessages(10)`)
попадает в `ExecutionRequest.relatedContent`/`history` и дальше в облачный
запрос. Гарантия: в облако экральный текст не уходит.

## Четыре слоя фильтрации

| Слой | Где | Что делает |
|---|---|---|
| 1. Пакетная политика | `AccessibilityPrivacyPolicy` (без изменений) | Banking/Wallet/2FA/password/чувствительные пакеты и lock-screen/systemui/settings — экран не читается ВООБЩЕ (`PrivacyBlocked`, честный отказ в tool-результате) |
| 2. Парольные поля | `JarvisAccessibilityService.getScreenContent` | `isPassword`-узлы пропускаются; нечитаемый флаг → fail-closed (считаем паролем) |
| 3. Контентный санитайзер | `ScreenTextSanitizer` (новый) | OTP-подобные числа (6–8 цифр), короткие коды в код-контексте («код RXT4Q»), картоподобные последовательности (13–19 цифр) маскируются `••••` на этапе capture — для «2FA внутри обычного приложения» (код входа в мессенджере) |
| 4. Запрет распространения | `ScreenContentPrivacy` (новый) | Успешный `accessibility.screen_reader` помечает результат `containsScreenContent` по всей цепочке; в БД сообщений пишется placeholder вместо текста |

## Реальные сценарии (проверены тестами)

**Запрещено** (слой 1; `SENSITIVE_PACKAGE_HINTS` расширен реальными пакетами):
- банки без слова «bank» в пакете: `com.chase.sig.android`, `com.tinkoff.app`,
  `com.sber.android`, `privat24`;
- платёжки/кошельки: `com.paypal.android`, `com.coinbase.android`,
  `com.binance.dev`, `com.revolut.revolut`, `com.venmo`, `cash.app`,
  `com.eg.android.AlipayGphone`, `com.samsung.android.spay`,
  `com.google.android.apps.nbu.paisa.user`;
- 2FA/пароль-менеджеры: `com.google.android.apps.authenticator2`,
  `com.azure.authenticator`, `com.authy.authy`, `org.freeotp.app`,
  `com.beemdevelopment.aegis`, `com.valvesoftware.android.steam.community`.

**Разрешено** (проверено): `com.spotify.music`, `com.whatsapp`,
`org.telegram.messenger`, `ru.yandex.searchplugin`, карты, Chrome и т.п.
False positive эвристики лечится пользовательским allow-листом (кроме
lock-screen/systemui/settings — они не перекрываются никогда).

**Слой 3** (маскирует, но не блокирует): «Ваш код входа: 482913» →
«код ••••», «4276 1600 1234 5678» → «••••». НЕ маскируется: время «18:00»,
суммы «50 000», годы «2020», телефоны (группы < 5 цифр).

## Слой 4: цепочка маркера

```
ScreenReaderTool (успех)
  → PlanExecutionSummary.containsScreenContent   (AgentCognitiveLoop)
  → ExecutionResult.containsScreenContent        (CognitiveAgentExecutor / executeDeviceTool)
  → PromptExecutionResult.DirectAnswer           (AgentPipeline)
  → SendPromptUseCase: в MessageRepository пишется PLACEHOLDER, не текст
  → ChatViewModel.confirmPendingAction: то же для bypass-пути
```

Ответ ассистента в моменте показывается и озвучивается как обычно; в истории
остаётся `[Содержимое экрана прочитано и показано, но не сохраняется — данные
экрана остаются на устройстве, сэр.]`. При следующем запросе — включая
согласие пользователя на облако — провайдер получает placeholder, а не текст
экрана. Экранный текст нигде не персистируется: ни в БД сообщений, ни в
working memory (`updateEntityFromResponse` получает placeholder).

## Инварианты (CI)

5 guards `A11Y:*` в `scripts/verify-architectural-invariants.sh`: покрытие
финтех-hints, санитайзер на capture, флаг в cognitive loop, скраб в обеих
точках персистентности. Тесты: `AccessibilityPrivacyPolicyTest` (реальные
пакеты), `ScreenTextSanitizerTest`, `AgentCognitiveLoopTest` (флаг +/−),
`SendPromptUseCasePrivacyGateTest` (placeholder в БД, raw-текст отсутствует).

## Ограничение

История, сохранённая ДО внедрения слоя 4, не размечена — гарантия действует
вперёд. Ретроспективная очистка — отдельная миграция БД, если потребуется.
