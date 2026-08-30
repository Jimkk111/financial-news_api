---
name: project-context
description: "Use whenever a user asks to understand, document, initialize, refresh, or reuse a software project's basic context—especially its stack, directory structure, entry points, modules, crawlers, run/test/deploy commands, APIs, conventions, current status, or recent architectural changes. Trigger for onboarding an unfamiliar repository, planning a code change from project context, or maintaining a project context summary even when they do not say 'project context'."
---

# Project Context

Maintain and reuse a short, trustworthy project summary so a new session can orient itself without scanning the whole repository.

## Start with the saved context

1. Find the repository root and read `PROJECT_CONTEXT.md` there before broad exploration.
2. Treat it as an index, not as authority. For the requested area, read only the named source/configuration files needed to confirm the answer.
3. Do not recursively inspect generated, runtime, IDE, dependency, upload, or cache directories such as `target/`, `logs/`, `.idea/`, `node_modules/`, and uploaded files unless the user explicitly asks about them.
4. If the summary is absent, clearly stale, or the user asks to initialize/refresh it, perform a focused inventory before answering. Prefer, in order: current source and configuration, deployment files, existing project documentation, then recent Git history.

## Focused inventory

Adapt the inventory to the repository instead of assuming a language or framework. Look for:

- package/build manifests and lockfiles;
- application entry points and top-level modules;
- runtime configuration and safe environment templates;
- container/orchestration and deployment files;
- database schema or migrations;
- routes/controllers and the services behind the requested feature;
- test directories, CI configuration, and documented commands;
- recent commits when architecture or current status matters.

For a question about one module, use the summary first and then inspect that module and its direct dependencies. Do not turn a targeted question into a full-repository audit.

## Facts, inferences, and drift

Label statements when useful:

- **Fact** — directly confirmed in current source, configuration, or a command output.
- **Inference** — a conventional command or behavior not explicitly documented by the repository.
- **Verify** — a claim that may have changed or conflicts with another source.

Prefer current implementation over historical documentation. Preserve known discrepancies in the summary rather than silently choosing one version. Mention missing README/CI/tests or undocumented commands when they affect the user's decision.

## Updating `PROJECT_CONTEXT.md`

Only write or refresh the summary during initialization, an explicit refresh, or when the saved file is demonstrably stale and updating it is necessary for the request. Ordinary project questions are read-only.

Keep the summary compact and stable. Include:

1. project purpose and authoritative sources;
2. stack, build tool, and runtime versions;
3. directory map and application entry point;
4. local run, test, and deployment commands, marking inferred commands;
5. configuration keys and external services without secret values;
6. main API/route and business-module map;
7. data model overview;
8. feature-specific architecture (for example, a workflow or crawler);
9. conventions and security boundaries;
10. branch/commit and a short “known gaps or drift” list;
11. source paths with line ranges and a refresh timestamp.

When refreshing, re-check the existing claims and change only what current evidence supports. Do not paste full source files, logs, build output, or transient metrics into the summary.

## Secret and local-file handling

Read configuration names and template structure when needed, but never copy or reveal API keys, JWT secrets, passwords, mail credentials, tokens, private URLs, or other secret values. Do not persist values from real `.env` files or ignored local configuration such as `application-dev.yml`; record only the variable/key name and whether a value is required or present. Treat generated artifacts and local logs as non-authoritative.

## Response format

Lead with the answer to the user's question. Then provide only the relevant context, citing repository paths and line ranges. If you refreshed the summary, state which sections changed and note any unresolved or inferred items. Avoid large file dumps.

A useful entry looks like this:

```markdown
- **Fact:** Runtime is Java 23 (`pom.xml:20-26`).
- **Inference:** `mvn test` is the conventional test command; no test source directory was found.
- **Verify:** `docs/` describes an older crawler flow; confirm against the current crawler orchestrator before changing it.
- **Config:** `CRAWLER_AI_API_KEY` is required; the value is intentionally not recorded.
```

## Current-repository hints

For this repository, start with `PROJECT_CONTEXT.md`, then use the paths listed in its “Sources” section. In particular, the current crawler implementation is under `src/main/java/com/financial/news/service/crawler/`, while `docs/crawler-agent-delivery.md` and `docs/refactor.md` may lag behind the current Java implementation. Treat `src/main/resources/application-dev.yml`, real environment files, `logs/`, and `target/` as local or generated material, not context sources to persist.
