# Change manifest

Compared with `9196b97` (`main`). Generated artifacts under `build/`, `.gradle/`, `.git/`, and local SDK configuration are excluded.

- Modified: 78
- Added: 24
- Deleted: 3

## Modified

- `.github/workflows/build.yml`
- `README.md`
- `app/lint-baseline.xml`
- `app/src/androidTest/java/com/jarvis/assistant/data/local/JarvisDatabaseMigrationTest.kt`
- `app/src/androidTest/java/com/jarvis/assistant/tools/AccessibilityHonestyInstrumentedTest.kt`
- `app/src/androidTest/java/com/jarvis/assistant/tools/CallSmsHonestyInstrumentedTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/jarvis/assistant/agent/automation/engine/AutomationRuleMatcher.kt`
- `app/src/main/java/com/jarvis/assistant/agent/automation/engine/PersonalAutomationEngine.kt`
- `app/src/main/java/com/jarvis/assistant/agent/automation/engine/RuleEvaluator.kt`
- `app/src/main/java/com/jarvis/assistant/agent/decision/ExecutionAdapters.kt`
- `app/src/main/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt`
- `app/src/main/java/com/jarvis/assistant/agent/decision/ExecutionModels.kt`
- `app/src/main/java/com/jarvis/assistant/agent/decision/ExecutionPorts.kt`
- `app/src/main/java/com/jarvis/assistant/agent/executor/ToolExecutor.kt`
- `app/src/main/java/com/jarvis/assistant/agent/fast/FastCommandRouter.kt`
- `app/src/main/java/com/jarvis/assistant/agent/localai/LocalAiModels.kt`
- `app/src/main/java/com/jarvis/assistant/agent/localai/OnDeviceLocalAi.kt`
- `app/src/main/java/com/jarvis/assistant/agent/media/MediaIntent.kt`
- `app/src/main/java/com/jarvis/assistant/agent/memory/WorkingMemory.kt`
- `app/src/main/java/com/jarvis/assistant/agent/parser/ToolCallParser.kt`
- `app/src/main/java/com/jarvis/assistant/agent/pipeline/AgentPipeline.kt`
- `app/src/main/java/com/jarvis/assistant/agent/planner/ScenarioMatcher.kt`
- `app/src/main/java/com/jarvis/assistant/agent/registry/ToolRegistry.kt`
- `app/src/main/java/com/jarvis/assistant/agent/safety/ToolPermissionManager.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/accessibility/JarvisAccessibilityService.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/accessibility/UiClickTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/accessibility/UiTypeTextTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/communication/CallTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/communication/ContactResolver.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/communication/SmsTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/intelligence/WebSearchTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/tools/productivity/AlarmTimerTool.kt`
- `app/src/main/java/com/jarvis/assistant/agent/weather/WeatherProvider.kt`
- `app/src/main/java/com/jarvis/assistant/core/license/LicenseCodeValidator.kt`
- `app/src/main/java/com/jarvis/assistant/core/license/LicenseManager.kt`
- `app/src/main/java/com/jarvis/assistant/core/license/LicenseServerValidator.kt`
- `app/src/main/java/com/jarvis/assistant/core/security/SecurityManager.kt`
- `app/src/main/java/com/jarvis/assistant/data/remote/JarvisApiClient.kt`
- `app/src/main/java/com/jarvis/assistant/di/HiltModules.kt`
- `app/src/main/java/com/jarvis/assistant/presentation/activation/ActivationViewModel.kt`
- `app/src/main/java/com/jarvis/assistant/presentation/settings/SettingsScreen.kt`
- `app/src/main/java/com/jarvis/assistant/presentation/settings/SettingsViewModel.kt`
- `app/src/main/java/com/jarvis/assistant/voice/service/JarvisVoiceService.kt`
- `app/src/main/java/com/jarvis/assistant/voice/service/SystemEventReceiver.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/xml/network_security_config.xml`
- `app/src/test/java/com/jarvis/assistant/agent/FastCommandRouterTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/automation/AutomationRuleMatcherTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/automation/RuleEvaluatorTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngineTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/executor/ToolExecutorConfirmationQueueTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/localai/OnDeviceLocalAiTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/media/MediaIntentParserTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/memory/WorkingMemoryTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/planner/ScenarioMatcherTest.kt`
- `app/src/test/java/com/jarvis/assistant/benchmark/BenchmarkMetrics.kt`
- `app/src/test/java/com/jarvis/assistant/benchmark/BenchmarkRegressionTest.kt`
- `app/src/test/java/com/jarvis/assistant/benchmark/BenchmarkRunner.kt`
- `app/src/test/java/com/jarvis/assistant/benchmark/BenchmarkRunnerTest.kt`
- `app/src/test/java/com/jarvis/assistant/core/license/LicenseCodeValidatorTest.kt`
- `docs/BENCHMARK.md`
- `docs/EXECUTION_DECISION_ENGINE.md`
- `docs/benchmark/benchmark-report.txt`
- `docs/benchmark/benchmark-results.csv`
- `docs/benchmark/benchmark-results.json`
- `server/.env.example`
- `server/src/main/kotlin/com/jarvis/server/Main.kt`
- `server/src/main/kotlin/com/jarvis/server/config/ServerConfig.kt`
- `server/src/main/kotlin/com/jarvis/server/http/JarvisApiHandler.kt`
- `server/src/main/kotlin/com/jarvis/server/observability/Observability.kt`
- `server/src/main/kotlin/com/jarvis/server/provider/OkHttpTransport.kt`
- `server/src/main/kotlin/com/jarvis/server/provider/ProviderHealth.kt`
- `server/src/main/kotlin/com/jarvis/server/provider/ProviderManager.kt`
- `server/src/main/kotlin/com/jarvis/server/ratelimit/RateLimiter.kt`
- `server/src/main/kotlin/com/jarvis/server/router/AiRouter.kt`
- `server/src/main/kotlin/com/jarvis/server/usage/UsageTracking.kt`
- `server/src/test/kotlin/com/jarvis/server/ApiIntegrationTest.kt`

## Added

- `app/src/main/java/com/jarvis/assistant/agent/decision/PrivacyClassifier.kt`
- `app/src/main/java/com/jarvis/assistant/core/network/BoundedResponseBody.kt`
- `app/src/main/java/com/jarvis/assistant/core/security/AccessTokenPolicy.kt`
- `app/src/test/java/com/jarvis/assistant/agent/decision/PrivacyClassifierTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/executor/ToolExecutorBehaviorTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/localai/LocalAiModelsBoundaryTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/parser/ToolCallParserTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/registry/ToolRegistryValidationTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/tools/WebSearchToolNetworkSafetyTest.kt`
- `app/src/test/java/com/jarvis/assistant/agent/tools/communication/ContactResolverNumberTest.kt`
- `app/src/test/java/com/jarvis/assistant/core/network/BoundedResponseBodyTest.kt`
- `app/src/test/java/com/jarvis/assistant/core/security/AccessTokenPolicyTest.kt`
- `audit/CHANGE_MANIFEST.md`
- `audit/DEPENDENCY_GRAPH.md`
- `audit/FINAL_AUDIT_REPORT.md`
- `audit/REPOSITORY_INVENTORY.md`
- `audit/dependencies.txt`
- `audit/generate_inventory.py`
- `audit/kotlin-stdlib-dependency-insight.txt`
- `server/src/main/kotlin/com/jarvis/server/privacy/PromptPrivacyClassifier.kt`
- `server/src/test/kotlin/com/jarvis/server/AuthConfigRateLimitTest.kt`
- `server/src/test/kotlin/com/jarvis/server/HttpProvidersTest.kt`
- `server/src/test/kotlin/com/jarvis/server/ObservabilityUsageTest.kt`
- `server/src/test/kotlin/com/jarvis/server/ProviderResilienceTest.kt`

## Deleted

- `app/src/main/java/com/jarvis/assistant/core/license/LicenseRemoteConfig.kt`
- `app/src/main/java/com/jarvis/assistant/core/license/LocalChecksumVerifier.kt`
- `app/src/main/java/com/jarvis/assistant/voice/tts/NeuralVoicePlayer.kt`
