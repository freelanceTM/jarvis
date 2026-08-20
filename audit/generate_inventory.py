#!/usr/bin/env python3
"""Regenerate source inventory and internal Kotlin package graph."""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PROD_ROOTS = [ROOT / "app/src/main/java", ROOT / "server/src/main/kotlin"]
TEST_ROOTS = [ROOT / "app/src/test", ROOT / "app/src/androidTest", ROOT / "server/src/test"]

prod_files = sorted(p for root in PROD_ROOTS for p in root.rglob("*.kt"))
test_files = sorted(p for root in TEST_ROOTS for p in root.rglob("*.kt"))
test_text = "\n".join(p.read_text(errors="ignore") for p in test_files)

decl_rx = re.compile(
    r"\b(?:(?:data|sealed|enum|open|abstract|internal|private|public)\s+)*"
    r"(class|interface|object|fun)\s+(`[^`]+`|[A-Za-z_]\w*)"
)
package_rx = re.compile(r"(?m)^\s*package\s+([\w.]+)")
import_rx = re.compile(r"(?m)^\s*import\s+([\w.]+)")


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def declarations(path: Path):
    text = path.read_text(errors="ignore")
    rows = []
    for line_no, line in enumerate(text.splitlines(), 1):
        for kind, raw_name in decl_rx.findall(line):
            name = raw_name.strip("`")
            if name in {"if", "when", "for", "while"}:
                continue
            rows.append((kind, name, line_no))
    return rows


def count_tests(root: Path) -> int:
    return sum(p.read_text(errors="ignore").count("@Test") for p in root.rglob("*.kt"))


inventory = [
    "# Repository inventory",
    "",
    "Generated from the audited source tree on 2026-08-20.",
    "",
]
for root, label in [(PROD_ROOTS[0], "app/src/main/java"), (PROD_ROOTS[1], "server/src/main/kotlin")]:
    files = sorted(root.rglob("*.kt"))
    inventory += [f"## `{label}`", f"{len(files)} Kotlin production files.", ""]
    for path in files:
        decls = declarations(path)
        rendered = ", ".join(f"`{kind} {name}` (L{line})" for kind, name, line in decls)
        names = {name for _, name, _ in decls if len(name) > 1}
        refs = sum(len(re.findall(r"\b" + re.escape(name) + r"\b", test_text)) for name in names)
        inventory.append(f"- `{rel(path)}` — {rendered or 'no named declaration detected'}; test-name references: {refs}")
    inventory.append("")

inventory += ["## Test sources", ""]
for root, label in [
    (TEST_ROOTS[0], "app/src/test"),
    (TEST_ROOTS[1], "app/src/androidTest"),
    (TEST_ROOTS[2], "server/src/test"),
]:
    files = sorted(root.rglob("*.kt"))
    inventory += [f"### `{label}` — {len(files)} files, {count_tests(root)} `@Test` declarations", ""]
    inventory += [f"- `{rel(path)}`" for path in files]
    inventory.append("")

inventory += [
    "## Runtime entry points and transports",
    "",
    "- Android: `JarvisApplication`, launcher `MainActivity`, foreground `JarvisVoiceService`, `SystemEventReceiver`, `JarvisAccessibilityService`.",
    "- Server: `MainKt` / `ServerBootstrap`.",
    "- HTTP: `GET /v1/health`, `POST /v1/ai/execute`, `GET /v1/admin/metrics`.",
    "- External boundaries: JARVIS API, Groq/Gemini/OpenRouter, Open-Meteo, DuckDuckGo/Wikipedia, Android ContentResolver/Room/DataStore/filesystem/MediaPipe.",
    "- No WebView, JavaScript bridge, command-shell execution, queue consumer, or server database was found.",
    "",
]
(ROOT / "audit/REPOSITORY_INVENTORY.md").write_text("\n".join(inventory))

packages = {}
for path in prod_files:
    text = path.read_text(errors="ignore")
    match = package_rx.search(text)
    if match:
        packages[path] = match.group(1)
known_packages = set(packages.values())


def group(package: str) -> str:
    parts = package.split(".")
    if package.startswith("com.jarvis.assistant.agent.") and len(parts) >= 5:
        return ".".join(parts[:5])
    if package.startswith("com.jarvis.assistant.") and len(parts) >= 4:
        return ".".join(parts[:4])
    if package.startswith("com.jarvis.server.") and len(parts) >= 4:
        return ".".join(parts[:4])
    return package

nodes = Counter(group(pkg) for pkg in packages.values())
edges = Counter()
for path, source_package in packages.items():
    text = path.read_text(errors="ignore")
    source = group(source_package)
    for imported in import_rx.findall(text):
        candidates = [pkg for pkg in known_packages if imported == pkg or imported.startswith(pkg + ".")]
        if not candidates:
            continue
        target = group(max(candidates, key=len))
        if source != target:
            edges[(source, target)] += 1

graph = [
    "# Source dependency graph",
    "",
    "Static package graph inferred from Kotlin imports. Edge weight is import count.",
    "",
    "## Nodes",
    "",
]
graph += [f"- `{node}`: {count} files" for node, count in sorted(nodes.items())]
graph += ["", "## Internal edges", "", "| From | To | Imports |", "|---|---|---:|"]
graph += [f"| `{source}` | `{target}` | {count} |" for (source, target), count in sorted(edges.items())]
graph += [
    "",
    "## Main runtime flow",
    "",
    "```text",
    "UI / Voice -> SendPromptUseCase -> AgentPipeline -> ExecutionDecisionEngine",
    "  -> FastCommandRouter -> ToolExecutor -> ToolRegistry -> Android/network tools",
    "  -> CompositeLocalAiExecutor -> MediaPipe Gemma / WorkflowExecutor",
    "  -> AIRepository -> JarvisApiClient -> JARVIS server",
    "",
    "HTTP -> authn -> authz -> SlidingWindowRateLimiter -> AiRouter",
    "  -> server PromptPrivacyClassifier -> ProviderManager",
    "  -> Groq/Gemini/OpenRouter -> Usage + Metrics",
    "",
    "Room -> Message/Memory/Fact/Preference/Procedure/Automation DAOs",
    "System broadcasts -> SystemEventReceiver -> PersonalAutomationEngine -> ToolExecutor",
    "```",
    "",
]
(ROOT / "audit/DEPENDENCY_GRAPH.md").write_text("\n".join(graph))
print(f"inventory: {len(prod_files)} production files, {len(test_files)} test/support files")
print(f"graph: {len(nodes)} nodes, {len(edges)} edges")
