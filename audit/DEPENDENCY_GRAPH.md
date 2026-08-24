# Source dependency graph

Static package graph inferred from Kotlin imports. Edge weight is import count.

## Nodes

- `com.jarvis.assistant`: 1 files
- `com.jarvis.assistant.agent.apps`: 2 files
- `com.jarvis.assistant.agent.automation`: 6 files
- `com.jarvis.assistant.agent.briefing`: 1 files
- `com.jarvis.assistant.agent.capability`: 3 files
- `com.jarvis.assistant.agent.core`: 1 files
- `com.jarvis.assistant.agent.decision`: 7 files
- `com.jarvis.assistant.agent.discovery`: 2 files
- `com.jarvis.assistant.agent.engine`: 1 files
- `com.jarvis.assistant.agent.executor`: 1 files
- `com.jarvis.assistant.agent.fast`: 1 files
- `com.jarvis.assistant.agent.localai`: 6 files
- `com.jarvis.assistant.agent.location`: 1 files
- `com.jarvis.assistant.agent.media`: 1 files
- `com.jarvis.assistant.agent.memory`: 20 files
- `com.jarvis.assistant.agent.model`: 1 files
- `com.jarvis.assistant.agent.observation`: 2 files
- `com.jarvis.assistant.agent.parser`: 1 files
- `com.jarvis.assistant.agent.pipeline`: 1 files
- `com.jarvis.assistant.agent.planner`: 4 files
- `com.jarvis.assistant.agent.registry`: 1 files
- `com.jarvis.assistant.agent.router`: 2 files
- `com.jarvis.assistant.agent.safety`: 1 files
- `com.jarvis.assistant.agent.tools`: 37 files
- `com.jarvis.assistant.agent.translator`: 4 files
- `com.jarvis.assistant.agent.weather`: 1 files
- `com.jarvis.assistant.ai`: 4 files
- `com.jarvis.assistant.core`: 12 files
- `com.jarvis.assistant.data`: 10 files
- `com.jarvis.assistant.di`: 1 files
- `com.jarvis.assistant.domain`: 7 files
- `com.jarvis.assistant.presentation`: 22 files
- `com.jarvis.assistant.voice`: 8 files
- `com.jarvis.server`: 1 files
- `com.jarvis.server.api`: 2 files
- `com.jarvis.server.auth`: 1 files
- `com.jarvis.server.billing`: 5 files
- `com.jarvis.server.config`: 3 files
- `com.jarvis.server.http`: 4 files
- `com.jarvis.server.license`: 4 files
- `com.jarvis.server.observability`: 1 files
- `com.jarvis.server.persistence`: 2 files
- `com.jarvis.server.privacy`: 1 files
- `com.jarvis.server.provider`: 5 files
- `com.jarvis.server.ratelimit`: 2 files
- `com.jarvis.server.router`: 1 files
- `com.jarvis.server.usage`: 1 files

## Internal edges

| From | To | Imports |
|---|---|---:|
| `com.jarvis.assistant.agent.apps` | `com.jarvis.assistant.agent.memory` | 2 |
| `com.jarvis.assistant.agent.automation` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.agent.automation` | `com.jarvis.assistant.agent.model` | 2 |
| `com.jarvis.assistant.agent.automation` | `com.jarvis.assistant.voice` | 1 |
| `com.jarvis.assistant.agent.briefing` | `com.jarvis.assistant.agent.memory` | 1 |
| `com.jarvis.assistant.agent.briefing` | `com.jarvis.assistant.agent.tools` | 1 |
| `com.jarvis.assistant.agent.briefing` | `com.jarvis.assistant.core` | 1 |
| `com.jarvis.assistant.agent.briefing` | `com.jarvis.assistant.domain` | 1 |
| `com.jarvis.assistant.agent.capability` | `com.jarvis.assistant.agent.tools` | 1 |
| `com.jarvis.assistant.agent.core` | `com.jarvis.assistant.agent.capability` | 2 |
| `com.jarvis.assistant.agent.core` | `com.jarvis.assistant.agent.model` | 3 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.engine` | 1 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.fast` | 2 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.localai` | 2 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.memory` | 2 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.model` | 3 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.planner` | 3 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.agent.registry` | 1 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.core` | 4 |
| `com.jarvis.assistant.agent.decision` | `com.jarvis.assistant.domain` | 3 |
| `com.jarvis.assistant.agent.discovery` | `com.jarvis.assistant.agent.core` | 1 |
| `com.jarvis.assistant.agent.discovery` | `com.jarvis.assistant.agent.memory` | 1 |
| `com.jarvis.assistant.agent.discovery` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.agent.engine` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.agent.engine` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.agent.engine` | `com.jarvis.assistant.agent.observation` | 3 |
| `com.jarvis.assistant.agent.engine` | `com.jarvis.assistant.agent.planner` | 1 |
| `com.jarvis.assistant.agent.executor` | `com.jarvis.assistant.agent.core` | 1 |
| `com.jarvis.assistant.agent.executor` | `com.jarvis.assistant.agent.model` | 2 |
| `com.jarvis.assistant.agent.executor` | `com.jarvis.assistant.agent.registry` | 1 |
| `com.jarvis.assistant.agent.executor` | `com.jarvis.assistant.agent.safety` | 2 |
| `com.jarvis.assistant.agent.fast` | `com.jarvis.assistant.agent.media` | 2 |
| `com.jarvis.assistant.agent.fast` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.agent.localai` | `com.jarvis.assistant.agent.decision` | 5 |
| `com.jarvis.assistant.agent.localai` | `com.jarvis.assistant.core` | 2 |
| `com.jarvis.assistant.agent.location` | `com.jarvis.assistant.agent.capability` | 2 |
| `com.jarvis.assistant.agent.memory` | `com.jarvis.assistant.agent.discovery` | 1 |
| `com.jarvis.assistant.agent.memory` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.agent.memory` | `com.jarvis.assistant.agent.model` | 2 |
| `com.jarvis.assistant.agent.observation` | `com.jarvis.assistant.agent.memory` | 1 |
| `com.jarvis.assistant.agent.observation` | `com.jarvis.assistant.agent.model` | 3 |
| `com.jarvis.assistant.agent.observation` | `com.jarvis.assistant.agent.planner` | 3 |
| `com.jarvis.assistant.agent.parser` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.agent.pipeline` | `com.jarvis.assistant.agent.decision` | 5 |
| `com.jarvis.assistant.agent.pipeline` | `com.jarvis.assistant.core` | 1 |
| `com.jarvis.assistant.agent.pipeline` | `com.jarvis.assistant.domain` | 2 |
| `com.jarvis.assistant.agent.planner` | `com.jarvis.assistant.agent.model` | 4 |
| `com.jarvis.assistant.agent.planner` | `com.jarvis.assistant.agent.parser` | 1 |
| `com.jarvis.assistant.agent.registry` | `com.jarvis.assistant.agent.core` | 2 |
| `com.jarvis.assistant.agent.registry` | `com.jarvis.assistant.agent.discovery` | 1 |
| `com.jarvis.assistant.agent.registry` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.agent.safety` | `com.jarvis.assistant.agent.capability` | 3 |
| `com.jarvis.assistant.agent.safety` | `com.jarvis.assistant.agent.core` | 2 |
| `com.jarvis.assistant.agent.safety` | `com.jarvis.assistant.agent.model` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant` | 1 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.apps` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.automation` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.briefing` | 1 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.capability` | 74 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.core` | 70 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.location` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.media` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.memory` | 4 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.model` | 71 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.translator` | 2 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.agent.weather` | 3 |
| `com.jarvis.assistant.agent.tools` | `com.jarvis.assistant.core` | 3 |
| `com.jarvis.assistant.agent.translator` | `com.jarvis.assistant.core` | 2 |
| `com.jarvis.assistant.agent.translator` | `com.jarvis.assistant.domain` | 1 |
| `com.jarvis.assistant.agent.weather` | `com.jarvis.assistant.core` | 1 |
| `com.jarvis.assistant.ai` | `com.jarvis.assistant.core` | 2 |
| `com.jarvis.assistant.ai` | `com.jarvis.assistant.data` | 2 |
| `com.jarvis.assistant.ai` | `com.jarvis.assistant.domain` | 4 |
| `com.jarvis.assistant.core` | `com.jarvis.assistant` | 1 |
| `com.jarvis.assistant.data` | `com.jarvis.assistant.agent.automation` | 2 |
| `com.jarvis.assistant.data` | `com.jarvis.assistant.agent.memory` | 2 |
| `com.jarvis.assistant.data` | `com.jarvis.assistant.ai` | 2 |
| `com.jarvis.assistant.data` | `com.jarvis.assistant.core` | 11 |
| `com.jarvis.assistant.data` | `com.jarvis.assistant.domain` | 7 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant` | 1 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.automation` | 1 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.capability` | 2 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.core` | 1 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.decision` | 7 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.localai` | 9 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.location` | 2 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.memory` | 4 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.tools` | 25 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.translator` | 2 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.agent.weather` | 2 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.ai` | 2 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.core` | 12 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.data` | 7 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.domain` | 3 |
| `com.jarvis.assistant.di` | `com.jarvis.assistant.voice` | 2 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant` | 1 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant.agent.decision` | 3 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant.agent.memory` | 1 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant.agent.pipeline` | 1 |
| `com.jarvis.assistant.domain` | `com.jarvis.assistant.core` | 3 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant` | 11 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.agent.automation` | 3 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.agent.translator` | 7 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.core` | 13 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.domain` | 18 |
| `com.jarvis.assistant.presentation` | `com.jarvis.assistant.voice` | 14 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant` | 4 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.automation` | 4 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.decision` | 1 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.executor` | 1 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.memory` | 1 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.model` | 1 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.agent.translator` | 1 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.core` | 3 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.domain` | 4 |
| `com.jarvis.assistant.voice` | `com.jarvis.assistant.presentation` | 1 |
| `com.jarvis.server` | `com.jarvis.server.auth` | 5 |
| `com.jarvis.server` | `com.jarvis.server.billing` | 6 |
| `com.jarvis.server` | `com.jarvis.server.config` | 1 |
| `com.jarvis.server` | `com.jarvis.server.http` | 5 |
| `com.jarvis.server` | `com.jarvis.server.license` | 3 |
| `com.jarvis.server` | `com.jarvis.server.observability` | 2 |
| `com.jarvis.server` | `com.jarvis.server.persistence` | 2 |
| `com.jarvis.server` | `com.jarvis.server.provider` | 7 |
| `com.jarvis.server` | `com.jarvis.server.ratelimit` | 2 |
| `com.jarvis.server` | `com.jarvis.server.router` | 1 |
| `com.jarvis.server` | `com.jarvis.server.usage` | 1 |
| `com.jarvis.server.auth` | `com.jarvis.server.license` | 1 |
| `com.jarvis.server.billing` | `com.jarvis.server.license` | 3 |
| `com.jarvis.server.billing` | `com.jarvis.server.persistence` | 2 |
| `com.jarvis.server.billing` | `com.jarvis.server.provider` | 2 |
| `com.jarvis.server.config` | `com.jarvis.server.billing` | 2 |
| `com.jarvis.server.config` | `com.jarvis.server.license` | 1 |
| `com.jarvis.server.config` | `com.jarvis.server.persistence` | 1 |
| `com.jarvis.server.config` | `com.jarvis.server.provider` | 1 |
| `com.jarvis.server.http` | `com.jarvis.server.api` | 14 |
| `com.jarvis.server.http` | `com.jarvis.server.auth` | 9 |
| `com.jarvis.server.http` | `com.jarvis.server.billing` | 8 |
| `com.jarvis.server.http` | `com.jarvis.server.config` | 4 |
| `com.jarvis.server.http` | `com.jarvis.server.license` | 5 |
| `com.jarvis.server.http` | `com.jarvis.server.observability` | 3 |
| `com.jarvis.server.http` | `com.jarvis.server.ratelimit` | 4 |
| `com.jarvis.server.http` | `com.jarvis.server.router` | 2 |
| `com.jarvis.server.license` | `com.jarvis.server.persistence` | 2 |
| `com.jarvis.server.observability` | `com.jarvis.server.provider` | 2 |
| `com.jarvis.server.privacy` | `com.jarvis.server.api` | 1 |
| `com.jarvis.server.provider` | `com.jarvis.server.config` | 4 |
| `com.jarvis.server.provider` | `com.jarvis.server.observability` | 2 |
| `com.jarvis.server.ratelimit` | `com.jarvis.server.config` | 2 |
| `com.jarvis.server.ratelimit` | `com.jarvis.server.persistence` | 2 |
| `com.jarvis.server.router` | `com.jarvis.server.api` | 3 |
| `com.jarvis.server.router` | `com.jarvis.server.auth` | 1 |
| `com.jarvis.server.router` | `com.jarvis.server.config` | 3 |
| `com.jarvis.server.router` | `com.jarvis.server.observability` | 3 |
| `com.jarvis.server.router` | `com.jarvis.server.privacy` | 1 |
| `com.jarvis.server.router` | `com.jarvis.server.provider` | 5 |
| `com.jarvis.server.router` | `com.jarvis.server.usage` | 2 |

## Main runtime flow

```text
UI / Voice -> SendPromptUseCase -> AgentPipeline -> ExecutionDecisionEngine
  -> FastCommandRouter -> ToolExecutor -> ToolRegistry -> Android/network tools
  -> CompositeLocalAiExecutor -> MediaPipe Gemma / WorkflowExecutor
  -> AIRepository -> JarvisApiClient -> JARVIS server

Internet HTTPS -> Caddy TLS/replaced proxy headers -> private JDK HTTP listener
HTTP -> trusted-origin resolution -> authn -> authz -> SlidingWindowRateLimiter -> AiRouter
  -> server PromptPrivacyClassifier -> ProviderManager
  -> Groq/Gemini/OpenRouter -> Usage + Metrics

License/billing HTTP -> DB-backed auth + PostgreSQL rate limits
  -> PostgreSQL licenses/orders/events -> Paddle/HELEKET + signed webhooks

Room -> Message/Memory/Fact/Preference/Procedure/Automation DAOs
System broadcasts -> SystemEventReceiver -> PersonalAutomationEngine -> ToolExecutor
```
