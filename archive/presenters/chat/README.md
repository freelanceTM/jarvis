# ChatUiStateMachine — archived (Этап 6)

Pure-Kotlin UI presenter вынесен из ChatViewModel, чтобы покрывать
event-sequences (debounce, rapid input, confirmation/privacy-consent races)
JVM-тестами без Robolectric.

## Почему в архиве, а не в проде

Был написан и покрыт 18 тестами, но НЕ ПОДКЛЮЧЁН к ChatViewModel: для
подключения требуется 1–2 часа рефакторинга ChatViewModel и доступ к
компилятору для итеративной проверки. В условиях песочницы без JDK это
рискованно вносить перед релизом — лучше оставить как есть (рабочий
код ViewModel протестирован вручную по прошлым спринтам) и вернуться
к подключению state machine на следующей итерации, когда будет
зелёная сборка.

## Как подключить обратно

1. Вернуть файлы из архива в src/main и src/test соответственно.
2. В ChatViewModel.init создать экземпляр:
   `private val stateMachine = ChatUiStateMachine(PrivacyClassifier::classifySafely)`
3. Во всех public methods (onInputChanged, sendTextMessage,
   confirmCloudConsent, denyCloudConsent, confirmPendingAction,
   cancelPendingAction) вызвать соответствующий machine.onXxx(),
   применить machine.snapshot() к _uiState (маппинг ChatUiSnapshot →
   ChatUiState), и обойти ChatUiEffect списком через when().
4. debounce job заменить на вызов machine.onInputIdleAfterTyping().
5. Убедиться, что ChatUiSnapshot privacy mapping и Pending*Ui
   адаптеры не ломают ChatScreen (UI data classes PendingConfirmationUi/
   PendingCloudConsentUi остаются в ViewModel; в машине свои
   PendingActionUi/PendingConsentUi, которые маппятся).
