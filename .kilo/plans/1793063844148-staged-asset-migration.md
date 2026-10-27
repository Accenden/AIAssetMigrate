# Staged Asset Migration Implementation Plan

## Goal and Decisions

Implement Agent.md F01-F10 as a local, single-user Java application with a shared AssetManager facade, CLI first and JavaFX second. The repository currently contains design documents only; Agent.md is untracked and must not be overwritten. Its diagram links point outside the repository, so do not infer unseen class signatures from them.

- Use Java 21, compatible JavaFX, Maven, JUnit 5, picocli, Jackson, and SQLite JDBC. Pin dependency versions during bootstrap.
- Target Windows first and a Unity 6 LTS preparation profile. Record the exact Unity editor version used for smoke testing; preparation is not proof of successful engine import.
- Initial assets: OBJ/MTL, PNG/JPEG, and copy-only FBX. Initial conversion: PNG/JPEG through Java ImageIO. Blender/model conversion is deferred, not simulated.
- Produce a separate output directory, never edit an existing Unity project or source assets. Unity .meta/GUID management, automatic Unity material creation, other engines, network service/multi-user access, and automatic resume/rollback are out of scope.
- Use local SQLite for projects, inventory, reviews, plan versions, run results, reports, and ordered events. A project is one source root plus persisted application state outside that root. A run is one execution attempt of one approved immutable plan.
- Permit one active migration per project and an exclusively claimed output location. Use a new run-specific output directory; never overwrite previous outputs.
- Preserve Agent.md's OAuth/no-paid-fallback requirement. Mock AI supports development; live AI is blocked until a provider-supported OAuth flow and application registration are verified. No unofficial login flow or silent API-key fallback.

## Architecture and Safety Contract

Use packages for domain, application, persistence/filesystem, conversion, AI/auth, CLI, and JavaFX. Keep initial scope in one Maven module unless a concrete build need requires separation.

- Preserve the report's patterns: AssetManager facade; MigrationCommand/AssetOperationCommand command objects; ConversionStrategy with ModelConverter and TextureConverter; LLMClient/OpenAIAdapter; LogPublisher/LogObserver. ModelConverter truthfully reports unsupported conversions until a real adapter exists.
- Data flow: source scan -> immutable inventory revision -> reviewed suggestions -> compatibility findings -> versioned command plan -> explicit approval -> deterministic execution -> validation/report -> event replay. Both interfaces invoke the same facade and never bypass its checks.
- Commands are typed, serializable data with asset IDs, relative output paths, parameters, dependencies, and converter requirements, never arbitrary shell strings. All paths and actions from AI are untrusted.
- Approval binds the plan version/content hash, inventory/source fingerprints, accepted reviews, target profile version, conversion options, and output location. Relevant edits invalidate approval. Recheck approval, actual source content, destination safety, and tools before execution; reject stale work.
- Sources are read-only. Reject source/output nesting, traversal, absolute command destinations, Windows reserved names, case-insensitive collisions, symlinks/reparse-point escapes, and nonempty/unclaimed output destinations. Recheck containment before each write. Do not scan application state or follow directory links.
- Persist events before notifying observers and before starting each write step. If persistence fails, start no further writes; surface an independent terminal/UI error. Subscriber failure or disconnection must not stop the worker.
- Execution is sequential initially. On failure, stop the run and mark unexecuted dependent commands skipped. Record partial outputs without reporting success. Do not rerun blindly into the same directory.

## Implementation Steps

### 1. Bootstrap the Core and Test Harness

Create the Maven build, package boundaries, executable CLI entry point, and minimal JavaFX launch configuration. Add domain types for Asset, Project, Finding, ReviewedChange, MigrationPlan, MigrationCommand, MigrationRun, Report, and LogEvent, with explicit enums for plan/run/step states.

Define facade contracts for the methods traced in Agent.md. Use constructor injection rather than a framework. Add small synthetic fixtures and temporary-directory tests; avoid third-party assets with uncertain licenses.

**Exit check:** Build and tests run on Windows; CLI help and a JavaFX shell launch. No source-writing behavior exists.

### 2. Persist Project State and Events (F10 Foundation)

Implement versioned SQLite schema migrations and repository transactions. Store stable asset IDs, inventory revisions/fingerprints, accepted metadata, immutable plan versions and approval records, run/step states, reports, and project-scoped monotonic event sequence numbers. Keep credentials outside SQLite.

Implement LogPublisher persistence-first delivery, cursor-based replay, and a race-free replay-to-live handoff. Add `logs --after <sequence> --follow` and project selection to CLI conventions. On startup, mark abandoned running attempts incomplete; retain outputs and history. Handle database locks/corruption explicitly instead of resetting data.

**Exit check:** Restart preserves state; event cursors neither omit nor duplicate visible events; slow/disconnected observers do not block execution; simulated persistence failure blocks the next write.

### 3. Import and Inspect Assets (F01)

Implement `import <folder>` through AssetManager.importAssets(). Scan without modifying sources, classify supported file types, compute fingerprints, and extract basic metadata. Inspect PNG/JPEG readability/dimensions and OBJ/MTL references. FBX inspection is limited to basic file/header checks; embedded materials and semantic validity are unknown, not verified.

Reject inaccessible roots. Record per-file warnings for unreadable, malformed, unsupported, or escaping references and continue scanning independent files. Give rescans a new inventory revision; identify removed/changed assets and invalidate affected approvals.

**Exit check:** Tests cover inaccessible roots, malformed files, spaces/Unicode paths, missing external textures, links, rescans, and source immutability.

### 4. Compatibility and Reviewable Organization (F02-F05 Deterministic Baseline)

Create a versioned Unity preparation profile distinguishing supported, unsupported, and unknown checks. Report installed conversion capabilities rather than assuming tooling exists. Preserve deterministic findings when AI is unavailable.

Implement `analyze categories`, `analyze relationships`, `analyze materials`, `analyze names`, and `analyze folders`. Begin with file-type/name heuristics and explicit OBJ/MTL references. Uncertain categories remain Uncategorized; ambiguous relationships require user correction or exclusion. Persist suggestions separately from accepted reviews and provide structured CLI accept/edit/reject commands usable without AI.

Preview naming/layout changes and rewrite only supported OBJ-to-MTL and MTL-to-texture references in output copies. Unsupported material directives/reference syntax must be flagged, not silently rewritten. FBX remains opaque: preserve its name and relative layout with associated files where known; block reference-affecting renames/mapping edits and flag unknown dependencies for explicit acknowledgment or exclusion. Do not claim automatic Unity material binding.

**Exit check:** Deterministic fixture tests verify accepted versus proposed data, ambiguity handling, reference parsing, case collisions, path validation, and truthful unknown findings.

### 5. Versioned Plans and Explicit Approval (F06)

Implement `plan` and `approve <plan-id> --version <n>`. Build deterministic plans from selected assets, reviews, compatibility findings, and output selection; AI proposals can enter the same validator later. Include copy, texture-convert, and supported reference-rewrite operations with explicit dependencies.

Validate allowed operations, dependency ordering/cycles, missing references, output conflicts, tool availability, and target constraints. Blocking findings require correction/exclusion; unknown nonblocking checks require visible acknowledgment. Display source-to-output mappings, warnings, conversions, and excluded assets before approval. Acknowledgments become part of the approved version.

**Exit check:** No plan executes without current explicit approval; changed reviews, source content, target, paths, or conversion parameters invalidate it. Reject injected commands and invalid dependency graphs.

### 6. Execute Safe Copy/Texture Migration (F07)

Implement `migrate <plan-id>` with project/output locking and preflight validation. Execute only approved commands; AI is never in the execution authority path. Stage individual files in the claimed output directory and atomically publish completed files where supported. Store start/completion events and output hashes/manifests around each operation; filesystem and database updates are not assumed to be one atomic transaction.

Implement TextureConverter with explicit PNG/JPEG settings. Converting transparency to JPEG requires an approved background-color choice or is blocked. ModelConverter reports no supported model conversions in this release. Rewrite OBJ/MTL output references only after final destinations are known.

Handle cancellation, disk-full, source drift, permissions, and logging failure by stopping new steps and recording failed/partial/incomplete states when storage permits. If recording fails, startup recovery must treat the attempt as incomplete. Preserve sources and prior successful outputs.

**Exit check:** Fault-injection tests cover mid-write crashes, concurrent migration, cancellation, stale approval, output collisions, and source hashes unchanged after successful and failed attempts.

### 7. Validate and Report (F08; First End-to-End Milestone)

Implement `validate <run-id>` and `history`. Check recorded output existence, readability, expected format, image dimensions, copy hashes, and supported OBJ/MTL reference resolution. Mark unsupported FBX/material semantic checks as unknown rather than passed. Save per-step outcomes, findings, exclusions, fingerprints, tool/profile versions, and deterministic summaries; export reports as JSON and readable text.

Distinguish success, partial, failed, and incomplete outcomes with clear validation status. Missing history or deleted outputs produce actionable findings. Only mark success when all planned steps and required supported checks pass.

**Exit check:** Complete CLI workflow `import -> analyze/review -> compatibility -> plan -> approve -> migrate -> validate -> history/logs` passes using synthetic OBJ/MTL/texture fixtures and needs no AI or Unity installation.

### 8. AI Adapter and OAuth Gate (Supporting UC11)

First verify official provider documentation, application registration, scopes, endpoints, permitted subscription usage, and token lifecycle. If unavailable, deliver mock/disabled adapters and a clear unavailable status; leave live AI acceptance blocked without substituting another authentication method.

If supported, implement OAuthSession and CredentialStore using PKCE, state validation, a provider-approved redirect, and Windows secure credential storage. Implement connect/status/disconnect CLI commands, cancellation, token renewal, revocation handling, and redaction. Authentication never changes migration approval. Quotas stop requests; there is no paid fallback.

Define schema-validated LLMClient responses and a deterministic mock for tests. Send only consented relative names and necessary metadata by default, not binary assets or absolute local paths. Bound request size, timeouts, retries, and agent tool iterations. Treat filenames/content as data, not instructions.

**Exit check:** Mock tests cover malformed responses, timeouts, invalid callbacks, expired access, storage failures, quota exhaustion, and disconnect. Real-provider acceptance is separately gated on verified supported access.

### 9. Add AI Assistance and Natural Language (F02-F06, F08-F09)

Implement AIEngine analysis/planning and optional explanations/summaries over existing services. Model suggestions enter the same review, compatibility, and plan validation paths; failures retain deterministic findings and never overwrite accepted reviews.

Implement `command <text>` with allowlisted structured intents dispatched through AssetManager. Ambiguous requests return clarification without execution. Natural language may draft a plan or start an already explicitly approved current plan, but cannot supply approval or bypass preflight. Unsupported operations are rejected; no arbitrary scripts or tool names are accepted.

**Exit check:** Mock end-to-end tests cover plausible and malicious asset names, invented tools, ambiguous instructions, invalid paths, model outages, stale plans, and attempts to approve via AI. Mark live AI features unavailable until Step 8's gate passes.

### 10. Complete JavaFX Parity (F01-F10)

Build project/import inventory, analysis/review, relationship/material mapping, rename/layout preview, compatibility, plan review/approval, migration progress, validation/history, account status, natural-language panel, and logs views. Keep workflow and approval rules in AssetManager, not controllers.

Run scanning, persistence, AI calls, and migration off the JavaFX application thread. Marshal observer updates onto the UI thread, support replay cursors and cancellation, and keep the worker independent of a closed log viewer. Show draft/approved/stale states prominently and disable unsafe actions while preserving server-side-equivalent facade validation. A closed application cancels safely where possible; forced termination is recovered as incomplete.

**Exit check:** CLI-created projects/plans/runs appear correctly in GUI and vice versa. Manual and automated checks cover responsiveness, approval invalidation, live/replayed logs, cancellation, and error recovery.

### 11. Harden and Package

Run the complete fixture suite and adversarial filesystem/AI tests. Smoke-test import of prepared output into a clean Unity 6 LTS project and record the exact editor version and material limitations. Do not mutate a user's existing Unity project for tests.

Package a Windows application/runtime with JavaFX and a usable CLI launcher, for example through jpackage. Test installation and execution from paths with spaces without developer tools. Include schema migration tests and reject newer unsupported database versions without modifying them.

Update README with supported formats, concrete workflow examples, authentication limitations, output safety policy, setup/testing commands, and the distinction between preparation checks and Unity validation. Document feature-to-test traceability against Agent.md without silently claiming deferred model conversion or unavailable live AI is implemented.

**Exit check:** A fresh Windows install completes the deterministic workflow; all F01-F10 have acceptance coverage, with live AI status explicitly gated if OAuth remains unavailable.

## Delivery and Remaining Gates

Deliver each step as a reviewable increment after its exit checks pass. Steps 1-7 are the first useful CLI release; Steps 8-9 add gated AI; Step 10 supplies full UI parity; Step 11 is release validation. Build persistence/logging before writes, and never defer approval/source preservation to a later hardening phase.

No existing application-data migration is required because the repository has no implementation. Establish schema versioning from the first database version. Do not move, rename, or delete existing user assets as part of rollout.

The only external release gate is verified provider-supported OAuth access. If it cannot be satisfied, deterministic functionality and mock tests can ship, but live AI and UC11 must remain explicitly incomplete. Blender conversion, direct Unity integration, and deeper FBX validation require separate future plans.
