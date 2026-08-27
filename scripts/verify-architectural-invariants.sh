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
check "H-04: ManualWakeWordTrigger calls JarvisVoiceService.start" \
      "JarvisVoiceService.start" \
      "$APP/java/com/jarvis/assistant/presentation/main/MainViewModel.kt"
check "H-04: ManualWakeWordTrigger must NOT call orchestrator.startServicePipeline directly" \
      "orchestrator.startServicePipeline()" \
      "$APP/java/com/jarvis/assistant/presentation/main/MainViewModel.kt" \
      hasnt

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

if [ "$fail" = 1 ]; then
  echo ""
  echo "INVARIANT CHECKS FAILED"
  exit 1
fi
echo ""
echo "All architectural invariants hold."
