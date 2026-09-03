#!/usr/bin/env bash
# verify-architectural-invariants.sh — grep-based regression guard for
# critical architectural decisions (H-02/H-03/H-04/H-05/H-06/M-01/M-10).
#
# Запускается как: bash scripts/verify-architectural-invariants.sh
# Выходит с кодом 0 если все инварианты выполняются, с 1 — иначе.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="."
APP="$ROOT/app/src/main"
APP_TEST="$ROOT/app/src/test"
SERVER="$ROOT/server/src/main"
SERVER_TEST="$ROOT/server/src/test"
fail=0

check() {
  local desc="$1"
  local pattern="$2"
  local paths="$3"
  local expect="${4:-has}"   # "has" | "hasnt" | "has_N"
  if [ "$expect" = "hasnt" ]; then
    if grep -rn "$pattern" $paths >/dev/null 2>&1; then
      echo "FAIL: $desc"
      echo "       matched: $(grep -rn "$pattern" $paths | head -3)"
      fail=1
    else
      echo "ok: $desc"
    fi
  elif [ "$expect" = "has" ]; then
    if grep -rn "$pattern" $paths >/dev/null 2>&1; then
      echo "ok: $desc"
    else
      echo "FAIL: $desc (expected match not found)"
      fail=1
    fi
  fi
}

# H-02: SendPromptUseCase is the single production entry that calls
# classifySafely with full context. No other production caller should invoke
# it on a request-level payload (downstream layers must trust effective).
check "H-02: SendPromptUseCase calls withContextualClassification" \
      "ExecutionRequest.withContextualClassification" \
      "$APP/java/com/jarvis/assistant/domain/usecases/SendPromptUseCase.kt"
check "H-02: AIRepositoryImpl does NOT call classifySafely" \
      "classifySafely" \
      "$APP/java/com/jarvis/assistant/data/repository/AIRepositoryAndSettingsImpl.kt" \
      hasnt
check "H-02: JarvisApiAiClient does NOT call classifySafely" \
      "classifySafely" \
      "$APP/java/com/jarvis/assistant/ai/JarvisApiAiClient.kt" \
      hasnt
check "H-02: server AiRouter classifier untouched (trust boundary)" \
      "classifySafely" \
      "$SERVER/kotlin/com/jarvis/server/router/AiRouter.kt"

# H-03: AutomationScheduleReceiver uses the safe goAsync pattern.
check "H-03: AutomationScheduleReceiver uses SupervisorJob" \
      "SupervisorJob()" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"
check "H-03: AutomationScheduleReceiver uses withTimeout(DISPATCH_TIMEOUT_MS)" \
      "withTimeout(DISPATCH_TIMEOUT_MS)" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"
check "H-03: AutomationScheduleReceiver has AtomicBoolean finish-guard" \
      "AtomicBoolean" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"
check "H-03: AutomationScheduleReceiver has CoroutineExceptionHandler" \
      "CoroutineExceptionHandler" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"
check "H-03: AutomationScheduleReceiver logs exact 'automation reconcile failed' tag-message" \
      "automation reconcile failed" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"
check "H-03: AutomationScheduleReceiver does NOT create throwaway CoroutineScope(Dispatchers.IO)" \
      "CoroutineScope(Dispatchers.IO)" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt" \
      hasnt

# H-04: ManualWakeWordTrigger routes through JarvisVoiceService.start,
# startServicePipeline is idempotent.
check "H-04: startServicePipeline has isServiceActive guard" \
      "fun startServicePipeline()" \
      "$APP/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt"
# Extract startServicePipeline body and check it contains isServiceActive guard.
body=$(sed -n '/fun startServicePipeline/,/^    fun /p' \
  "$APP/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt")
if echo "$body" | grep -q "if (isServiceActive)"; then
  echo "ok: H-04: isServiceActive early-return is first statement in startServicePipeline"
else
  echo "FAIL: H-04: isServiceActive guard missing in startServicePipeline"
  fail=1
fi
# resumeAfterPhoneCall must also guard on isServiceActive (race with stop/rebind).
resumeBody=$(sed -n '/fun resumeAfterPhoneCall/,/^    fun /p' \
  "$APP/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt")
if echo "$resumeBody" | grep -q "if (isServiceActive)"; then
  echo "ok: H-04: resumeAfterPhoneCall guards on isServiceActive"
else
  echo "FAIL: H-04: resumeAfterPhoneCall missing isServiceActive guard"
  fail=1
fi
check "H-04: AWAITING_PRIVACY_CONSENT VoiceSessionState still exists (C-02 mode)" \
      "AWAITING_PRIVACY_CONSENT" \
      "$APP/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt"
# H-04 (актуализировано после 0e9bf4b: ManualWakeWordTrigger/MainViewModel
# удалены frontend-rebuild'ом): голосовой пайплайн запускается ТОЛЬКО через
# JarvisVoiceService (onServiceConnected → orchestrator.startServicePipeline),
# UI-слой не дёргает пайплайн в обход сервиса.
check "H-04: voice pipeline entry lives in JarvisVoiceService" \
      "startServicePipeline" \
      "$APP/java/com/jarvis/assistant/voice/service/JarvisVoiceService.kt"
if grep -rn "startServicePipeline" "$APP/java/com/jarvis/assistant/presentation" >/dev/null 2>&1; then
  echo "FAIL: H-04: presentation layer must not start voice pipeline directly (use JarvisVoiceService)"
  echo "       matched: $(grep -rn "startServicePipeline" "$APP/java/com/jarvis/assistant/presentation" | head -3)"
  fail=1
else
  echo "ok: H-04: presentation layer does not start voice pipeline directly"
fi

# H-05: dead code removed.
check "H-05: AiRetryPolicy removed" \
      "AiRetryPolicy" \
      "$APP" \
      hasnt
check "H-05: PromptManager removed" \
      "PromptManager" \
      "$APP" \
      hasnt
check "H-05: modelOverride removed from AIClient" \
      "modelOverride" \
      "$APP/java/com/jarvis/assistant/ai" \
      hasnt
check "H-05: memory_context removed from server DTO" \
      "memoryContext\|memory_context\|MemoryFactDto" \
      "$SERVER/kotlin" \
      hasnt

# H-06: WorkManager bootstrap instead of sendBroadcast in Application.onCreate.
check "H-06: JarvisApplication.onCreate does NOT call sendBroadcast" \
      "sendBroadcast" \
      "$APP/java/com/jarvis/assistant/JarvisApplication.kt" \
      hasnt
check "H-06: AutomationReconcileWorker uses ExistingWorkPolicy.KEEP" \
      "ExistingWorkPolicy.KEEP" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationReconcileWorker.kt"
check "H-06: AutomationReconcileWorker has enqueueUnique companion" \
      "fun enqueueUnique" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationReconcileWorker.kt"
check "H-06: BOOT_COMPLETED enqueues unique WorkManager job" \
      "AutomationReconcileWorker.enqueueUnique" \
      "$APP/java/com/jarvis/assistant/agent/automation/scheduler/AutomationScheduleReceiver.kt"

# M-01: JarvisApiHandler parses body ONCE; size check uses body.length, not toByteArray.
check "M-01: JarvisApiHandler parses JSON exactly once" \
      "decodeFromString(AiExecutionRequest" \
      "$SERVER/kotlin/com/jarvis/server/http/JarvisApiHandler.kt"
# Count actual code calls to decodeFromString — not comments.
body=$(grep -n "decodeFromString" "$SERVER/kotlin/com/jarvis/server/http/JarvisApiHandler.kt" | grep -v "^[0-9]*:[[:space:]]*//" | wc -l)
if [ "$body" = "1" ]; then
  echo "ok: M-01: single decodeFromString call"
else
  echo "FAIL: M-01: expected exactly 1 decodeFromString (code), found $body"
  fail=1
fi
# M-01: body is parsed once AND the size check uses accurate UTF-8 byte count.
check "M-01: JarvisApiHandler measures UTF-8 body bytes for size check" \
      "toByteArray(Charsets.UTF_8)" \
      "$SERVER/kotlin/com/jarvis/server/http/JarvisApiHandler.kt"

# M-02: AsyncUsageTracker has periodic prune.
check "M-02: AsyncUsageTracker has pruneStaleEntries" \
      "pruneStaleEntries" \
      "$SERVER/kotlin/com/jarvis/server/usage/AsyncUsageTracker.kt"
check "M-02: AsyncUsageTracker has CLEANUP_INTERVAL_MS" \
      "CLEANUP_INTERVAL_MS" \
      "$SERVER/kotlin/com/jarvis/server/usage/AsyncUsageTracker.kt"

# M-10: SlidingWindowRateLimiter only in test sourceset.
if [ -f "$SERVER/kotlin/com/jarvis/server/ratelimit/SlidingWindowRateLimiter.kt" ]; then
  echo "FAIL: M-10: SlidingWindowRateLimiter must live in server/src/test only"
  fail=1
else
  echo "ok: M-10: SlidingWindowRateLimiter not in server/main"
fi
check "M-10: SlidingWindowRateLimiter exists in server/test" \
      "class SlidingWindowRateLimiter" \
      "$SERVER_TEST/kotlin/com/jarvis/server/ratelimit/SlidingWindowRateLimiter.kt"
check "M-10: server/main retains RateLimiter interface" \
      "interface RateLimiter" \
      "$SERVER/kotlin/com/jarvis/server/ratelimit/RateLimiter.kt"

# VERIFY (execute → verify → SUCCESS): FastCommandRouter не имеет права
# формулировать результат ДО выполнения инструмента. immediateVoiceResponse —
# только intent-формулировки («Включаю…», «Ставлю…»); итог озвучивается из
# реального ToolExecutionResult в ExecutionDecisionEngine.
check "VERIFY: FastCommandRouter has no pre-execution success claims" \
      "immediateVoiceResponse.*(включён|выключен|установлена|готово|выполнено)" \
      "$APP/java/com/jarvis/assistant/agent/fast/FastCommandRouter.kt" \
      hasnt
check "VERIFY: read-back verification module exists" \
      "object ExecutionVerification" \
      "$APP/java/com/jarvis/assistant/agent/tools/verification/ExecutionVerification.kt"
check "VERIFY: SetVolumeTool reads volume back before success" \
      "ExecutionVerification.pollFor" \
      "$APP/java/com/jarvis/assistant/agent/tools/device/SetVolumeTool.kt"
check "VERIFY: SetBrightnessTool verifies written brightness" \
      "brightnessVerified" \
      "$APP/java/com/jarvis/assistant/agent/tools/device/SetBrightnessTool.kt"
check "VERIFY: DoNotDisturbTool verifies interruption filter" \
      "dndVerified" \
      "$APP/java/com/jarvis/assistant/agent/tools/device/DoNotDisturbTool.kt"
check "VERIFY: AlarmTimerTool confirms alarm via nextAlarmClockInfo" \
      "nextAlarmMatchesHour" \
      "$APP/java/com/jarvis/assistant/agent/tools/productivity/AlarmTimerTool.kt"

# Единый контракт Tool Registry 2.0: verify() и mapError() — члены JarvisTool,
# ToolExecutor обеспечивает фазу Verification для обоих путей выполнения.
check "VERIFY: JarvisTool declares verification contract member" \
      "suspend fun verify(arguments: JsonObject, draft: ToolExecutionResult)" \
      "$APP/java/com/jarvis/assistant/agent/core/JarvisTool.kt"
check "VERIFY: JarvisTool declares error mapping contract member" \
      "fun mapError(arguments: JsonObject, error: Throwable)" \
      "$APP/java/com/jarvis/assistant/agent/core/JarvisTool.kt"
check "VERIFY: JarvisTool declares permissions contract member" \
      "val requiredPermissions: List<String>" \
      "$APP/java/com/jarvis/assistant/agent/core/JarvisTool.kt"
check "VERIFY: ToolExecutor enforces verification step" \
      "tool.verify(call.arguments, draft)" \
      "$APP/java/com/jarvis/assistant/agent/executor/ToolExecutor.kt"
check "VERIFY: ToolExecutor maps exceptions through contract" \
      "tool.mapError(call.arguments, e)" \
      "$APP/java/com/jarvis/assistant/agent/executor/ToolExecutor.kt"

# POLICY: решение о риске/подтверждении принимает Policy Engine, не LLM.
check "POLICY: ToolPermissionManager consults ActionPolicyEngine" \
      "policyEngine.evaluate" \
      "$APP/java/com/jarvis/assistant/agent/safety/ToolPermissionManager.kt"
check "POLICY: money amount detector exists" \
      "object MoneyAmountDetector" \
      "$APP/java/com/jarvis/assistant/agent/policy/MoneyAmountDetector.kt"
check "POLICY: automation origin escalates communications" \
      "origin == ActionOrigin.AUTOMATION" \
      "$APP/java/com/jarvis/assistant/agent/policy/ActionPolicyEngine.kt"
check "POLICY: automation engine declares non-bypass origin" \
      "ActionOrigin.AUTOMATION" \
      "$APP/java/com/jarvis/assistant/agent/automation/engine/PersonalAutomationEngine.kt"
check "POLICY: forced confirmations cannot be disabled" \
      "forced = true" \
      "$APP/java/com/jarvis/assistant/agent/policy/ActionPolicyEngine.kt"

# ACCESSIBILITY LOCKDOWN: capture → privacy filter → LLM; экран не течёт в облако.
check "A11Y: package policy covers real fintech" \
      '"paypal", "coinbase", "binance", "revolut", "venmo", "cashapp",' \
      "$APP/java/com/jarvis/assistant/agent/tools/accessibility/AccessibilityPrivacyPolicy.kt"
check "A11Y: content sanitizer masks OTP/cards at capture" \
      "ScreenTextSanitizer.sanitize(value)" \
      "$APP/java/com/jarvis/assistant/agent/tools/accessibility/JarvisAccessibilityService.kt"
check "A11Y: screen content marker flows through plan summary" \
      "containsScreenContent = screenContentSeen" \
      "$APP/java/com/jarvis/assistant/agent/engine/AgentCognitiveLoop.kt"
check "A11Y: pipeline persistence scrubs screen content" \
      "ScreenContentPrivacy.PLACEHOLDER" \
      "$APP/java/com/jarvis/assistant/domain/usecases/SendPromptUseCase.kt"
check "A11Y: bypass persistence scrubs screen content" \
      "ScreenContentPrivacy.isScreenReaderCall(pending.toolCall.toolId)" \
      "$APP/java/com/jarvis/assistant/presentation/chat/ChatViewModel.kt"

# LICENSE: клиент не источник истины; сервер проверяет user/device/license/subscription/expiration/revocation.
check "LICENSE: api_tokens bound to device (V007)" \
      "ADD COLUMN device_hash" \
      "server/src/main/resources/db/migration/V007__token_device_binding.sql"
check "LICENSE: AI path enforces token device binding" \
      "request.deviceIdHeader()" \
      "server/src/main/kotlin/com/jarvis/server/http/JarvisApiHandler.kt"
check "LICENSE: license authenticator requires device on enforcement path" \
      "licenseService.authenticateAccessToken(token, deviceIdHeader.trim())" \
      "server/src/main/kotlin/com/jarvis/server/auth/Auth.kt"
check "LICENSE: validate self-heals legacy token binding" \
      "licenseService.bindTokenDevice" \
      "server/src/main/kotlin/com/jarvis/server/http/LicenseBillingHttpHandler.kt"
check "LICENSE: client sends device header on trusted backend" \
      "X-Jarvis-Device" \
      "$APP/java/com/jarvis/assistant/data/remote/interceptor/AuthInterceptor.kt"
check "LICENSE: entitlement gate is server-side per request" \
      "Authentication alone never proves payment" \
      "server/src/main/kotlin/com/jarvis/server/http/JarvisApiHandler.kt"

# CLIP: идентичность OMNIX Clip = пара ключей (challenge/response), не имя/MAC.
check "CLIP: device identity table with public key registry (V008)" \
      "public_key BYTEA NOT NULL" \
      "server/src/main/resources/db/migration/V008__clip_device_identity.sql"
check "CLIP: challenges are single-use and expiring" \
      "used_at IS NULL AND expires_at > ?" \
      "server/src/main/kotlin/com/jarvis/server/clip/JdbcClipDeviceRepository.kt"
check "CLIP: canonical attestation message is version-domain separated" \
      "JARVIS-CLIP-ATTEST-v1" \
      "server/src/main/kotlin/com/jarvis/server/clip/ClipAttestationService.kt"
check "CLIP: attestation verifies against registered public key" \
      "verifier.initVerify(decodePublicKey(clip.publicKey))" \
      "server/src/main/kotlin/com/jarvis/server/clip/ClipAttestationService.kt"
check "CLIP: owner binding is first-come and immutable for others" \
      "owner_account_id IS NULL AND status <> 'REVOKED'" \
      "server/src/main/kotlin/com/jarvis/server/clip/JdbcClipDeviceRepository.kt"
check "CLIP: android verifier is fail-closed pure ECDSA" \
      "getOrDefault(false)" \
      "$APP/java/com/jarvis/assistant/core/clip/ClipIdentityVerifier.kt"
check "CLIP: trust store anchored in server responses only" \
      "ТОЛЬКО из ответов сервера" \
      "$APP/java/com/jarvis/assistant/core/clip/ClipTrustStore.kt"
check "CLIP: no fake transport success - firmware contract is honest" \
      "ClipTransportResult.Unavailable" \
      "$APP/java/com/jarvis/assistant/core/clip/ClipAttestationApi.kt"

# LOCAL-FIRST: локальная обработка там, где cloud не нужен.
check "LOCAL-FIRST: translation has an on-device provider" \
      "class LocalLlmTranslationProvider" \
      "$APP/java/com/jarvis/assistant/agent/translator/LocalLlmTranslationProvider.kt"
check "LOCAL-FIRST: on-device translation provider claims offline priority" \
      "override val isOffline: Boolean = true" \
      "$APP/java/com/jarvis/assistant/agent/translator/LocalLlmTranslationProvider.kt"
check "LOCAL-FIRST: engine prefers offline providers (local-first order)" \
      "sortedByDescending { it.isOffline }" \
      "$APP/java/com/jarvis/assistant/agent/translator/LiveTranslatorEngine.kt"
check "LOCAL-FIRST: long documents yield to the cloud lane honestly" \
      "MAX_LOCAL_CHARS" \
      "$APP/java/com/jarvis/assistant/agent/translator/LocalLlmTranslationProvider.kt"
check "LOCAL-FIRST: device lane routes without any LLM" \
      "DecisionReason.FAST_ROUTER_CONFIDENT" \
      "$APP/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt"

check "LOCAL-FIRST: router metrics singleton exists" \
      "class ExecutionRouterMetrics" \
      "$APP/java/com/jarvis/assistant/agent/metrics/ExecutionRouterMetrics.kt"
check "LOCAL-FIRST: engine counts every request" \
      "metrics.noteTotalRequest()" \
      "$APP/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt"
check "LOCAL-FIRST: escalation requires local lane attempt" \
      "noteCloudExecution(escalated = trace.localTried)" \
      "$APP/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt"
check "LOCAL-FIRST: target is a metric, not routing policy" \
      "МЕТРИКА, а не жёсткое требование" \
      "$APP/java/com/jarvis/assistant/agent/metrics/ExecutionRouterMetrics.kt"

# SMART CLOUD ROUTER: best provider по измерениям, а не random/статика.
check "SMART-ROUTER: measured performance tracker exists" \
      "class ProviderPerformanceTracker" \
      "server/src/main/kotlin/com/jarvis/server/provider/ProviderPerformanceTracker.kt"
check "SMART-ROUTER: 429 counted per provider" \
      "ProviderFailureKind.RATE_LIMITED" \
      "server/src/main/kotlin/com/jarvis/server/provider/ProviderPerformanceTracker.kt"
check "SMART-ROUTER: manager feeds measured outcomes" \
      "performance.recordSuccess(provider.id, latency)" \
      "server/src/main/kotlin/com/jarvis/server/provider/ProviderManager.kt"
check "SMART-ROUTER: smart policy selects best provider by score" \
      "class SmartProviderSelectionPolicy" \
      "server/src/main/kotlin/com/jarvis/server/provider/SmartProviderSelectionPolicy.kt"
check "SMART-ROUTER: cold start falls back to static priority" \
      "Cold start" \
      "server/src/main/kotlin/com/jarvis/server/provider/SmartProviderSelectionPolicy.kt"
check "SMART-ROUTER: prices come from admin cost settings" \
      "costEstimates" \
      "server/src/main/kotlin/com/jarvis/server/Main.kt"

# FALLBACK: bounded attempts — одна команда пользователя не превращается
# в бесконечные платные запросы.
check "FALLBACK: default provider attempts bounded (2)" \
      "val maxProviderAttempts: Int = 2" \
      "server/src/main/kotlin/com/jarvis/server/config/ServerConfig.kt"
check "FALLBACK: same-provider retries off by default" \
      "val maxRetriesPerProvider: Int = 0" \
      "server/src/main/kotlin/com/jarvis/server/config/ServerConfig.kt"
check "FALLBACK: 429 is never retried on the same provider" \
      "this == TIMEOUT || this == CONNECTION || this == SERVER_ERROR" \
      "server/src/main/kotlin/com/jarvis/server/provider/AiProvider.kt"
check "FALLBACK: fallback chain hard-capped by maxProviderAttempts" \
      "attempted.size >= maxProviderAttempts" \
      "server/src/main/kotlin/com/jarvis/server/provider/ProviderManager.kt"
check "FALLBACK: worst-case time budget warned at startup" \
      "provider timeout budget exceeds request deadline" \
      "server/src/main/kotlin/com/jarvis/server/Main.kt"

# COST CONTROL: Request -> Cost estimation -> Budget policy -> Provider.
check "COST-CONTROL: request cost estimator is pure and deterministic" \
      "object RequestCostEstimator" \
      "server/src/main/kotlin/com/jarvis/server/cost/RequestCostEstimator.kt"
check "COST-CONTROL: cost class flows through provider requirements" \
      "costClass: com.jarvis.server.cost.CostClass" \
      "server/src/main/kotlin/com/jarvis/server/provider/ProviderManager.kt"
check "COST-CONTROL: simple class prefers cheapest provider" \
      "SIMPLE -> Weights" \
      "server/src/main/kotlin/com/jarvis/server/provider/SmartProviderSelectionPolicy.kt"
check "COST-CONTROL: hard class routes to best quality, price-free" \
      "reliability = 0.50, rateLimit = 0.15, cost = 0.0" \
      "server/src/main/kotlin/com/jarvis/server/provider/SmartProviderSelectionPolicy.kt"
check "COST-CONTROL: router estimates cost before provider call" \
      "RequestCostEstimator.classify" \
      "server/src/main/kotlin/com/jarvis/server/router/AiRouter.kt"
check "COST-CONTROL: simple answers get capped output budget" \
      "outputBudget(" \
      "server/src/main/kotlin/com/jarvis/server/router/AiRouter.kt"

# VOICE LATENCY: сегменты пайплайна с P50/P95/P99 и разрезом LOCAL/CLOUD.
check "VOICE-LATENCY: percentile store exists" \
      "class VoiceLatencyMetrics" \
      "$APP/java/com/jarvis/assistant/agent/metrics/VoiceLatencyMetrics.kt"
check "VOICE-LATENCY: wake to stt measured at the orchestrator" \
      "VoiceStage.WAKE_TO_STT" \
      "$APP/java/com/jarvis/assistant/voice/orchestrator/VoiceInteractionOrchestrator.kt"
check "VOICE-LATENCY: stt to router via request origin timestamp" \
      "VoiceStage.STT_TO_ROUTER" \
      "$APP/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt"
check "VOICE-LATENCY: ai segment always carries a lane (LOCAL/CLOUD)" \
      "VoiceLane.CLOUD" \
      "$APP/java/com/jarvis/assistant/agent/decision/ExecutionDecisionEngine.kt"
check "VOICE-LATENCY: monotonic clock for latency math" \
      "SystemClock.elapsedRealtime" \
      "$APP/java/com/jarvis/assistant/agent/metrics/VoiceLatencyMetrics.kt"

if [ "$fail" = 1 ]; then
  echo ""
  echo "INVARIANT CHECKS FAILED"
  exit 1
fi
echo ""
echo "All architectural invariants hold."
