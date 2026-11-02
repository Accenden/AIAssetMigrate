# Feature Implementation Plan

Audit date: 2026-11-02.

Requirements source: `ProjectDesignUpdated(I forgot to add in a diagram).pdf`, especially F01-F10 on pages 1-4 and UC01-UC11 on pages 6-8. The updated document, not the original `ProjectDesign.pdf`, governs this plan. UC11 account connection is supporting setup, not an eleventh core feature.

This is an implementation plan and current-state audit only. Application code has not been changed.

## Current State

The project is a Java 21 Maven application using picocli, JavaFX, Jackson, and JUnit 5. There is useful implementation to retain, but no complete import-to-migration workflow yet. An interface method, enum, or data record is not counted as an implemented feature.

| Feature | Status | Existing code | Remaining work |
| --- | --- | --- | --- |
| F01 Import assets | Partial backend | Recursive scanning; extension-based types; SHA-256, size and modification metadata; warnings and coverage outcomes; revisioned inventory JSON storage. | Shared import service, format/corruption inspection, CLI `import`, GUI Import Project, and workflow integration tests. |
| F02 Categorize assets | Contracts only | Asset type metadata, category analysis kind, suggestion and reviewed-change contracts. | Semantic categories, uncertainty handling, suggestion review/corrections, persistence, AI assistance, and interfaces. File type detection is not categorization. |
| F03 Relationships and materials | Contracts only | Persistable asset-reference records, relationship/material analysis kinds and operation descriptions. | Reference extraction/resolution, grouping and mapping suggestions, explicit review/exclusion, approved output-reference rewriting, and interfaces. |
| F04 Rename and organize | Contracts only | Naming/folder analysis kinds and migration operation data. | Naming rules, output-layout preview, collision/path/reference checks, review, and approved output-copy operations. |
| F05 Compatibility | Contracts only | Target-profile and finding contracts, `checkCompatibility()` boundary. | Target rules, format/reference inspection, installed-tool capability checks, supported conversion choices, and optional AI explanations. |
| F06 Create and approve plan | Data scaffolding | Migration plan, command and status models; facade declarations for draft creation and exact-version approval. | Plan generation, validation, durable versions, approval/invalidation transitions, dependency/conflict checks, and review interfaces. |
| F07 Convert and migrate | Data scaffolding | Command/run/status models and `migrate()` contract; run records contain a step-status map, not complete step results. | Command execution, converter strategies, per-step diagnostics/outputs, preflight approval/fingerprint/path/tool checks, progress, failure handling, and interrupted-run recovery. |
| F08 Validate and reports | Data scaffolding | Report/finding/run models and `validate()` / `history()` declarations. | Actual output checks, report persistence/retrieval, completion-status calculation, missing-history handling, and optional AI summaries. |
| F09 Natural-language commands | Contract only | `command()` and command-result boundary. | AI interpretation, structured allowlisted actions, clarification/rejection, dispatch through the facade, and interfaces. |
| F10 Live logs and replay | Working subsystem with known gaps; partial integration | File-backed ordered events, force-before-callback publication, replay, asynchronous subscriptions, CLI `logs` and polling follow mode. | Cursor/reader-visibility fixes, `AssetManager.watch()`, real workflow producers, GUI viewer, and executor stop-on-log-failure integration. |
| UC11 Connect AI account | Not implemented | No OAuth session, credential store, model client, or OpenAI adapter implementation. | Verify provider-supported authorization first; then PKCE/state, secure storage, refresh/disconnect, usage-limit behavior, and account controls. |

### Evidence and Reuse

Paths below are relative to `src/main/java/io/github/aiassetmigrate/` unless stated otherwise.

- `application/AssetManager.java:11-60` explicitly defines a shared boundary, not an implementation. It already requires explicit review, exact-version approval, execution preflight checks, and allowlisted natural-language actions.
- `inventory/AssetScanner.java:79-95,239-289` implements scanning and fingerprints. Recognized extensions are OBJ, FBX, MTL, PNG, JPG, and JPEG. It deliberately reports `inspectionStatus=NOT_PERFORMED`; arbitrary readable bytes with a recognized extension can currently enter inventory.
- `inventory/FileAssetRepository.java:67-146,177-229` implements inventory reads, cooperative writer locking, immutable numbered revisions, forced writes, and atomic publication. It is inventory storage, not yet storage for reviews, plans, runs, or reports.
- `inventory/SafeStoragePaths.java:15-30,62-102` protects source/state separation and rejects unsafe filesystem entries. Preserve these safeguards when adding output writes.
- `domain/InventorySnapshot.java:12-34` and `inventory/InventoryJsonCodec.java:29-118` provide immutable, versioned inventory persistence. Reuse it rather than introducing another inventory format.
- `cli/AssetMigrateCli.java:8-19` currently registers logs, not the feature command set. `gui/AssetMigrateApplication.java:13-26` is a shell, not a feature UI.
- `README.md:8-12` understates current inventory infrastructure by saying project-state persistence is unimplemented. Update status documentation when implementing the next milestone; do not use the README alone as the feature inventory.

### Existing Gaps to Address, Not Just Missing Features

- `logging/LogPublisher.java:148-156,192-218`: the subscription cursor filters replay, but is not retained for live events. A subscription after sequence 100 can receive new event 3. M1 must reject ahead-of-history cursors or enforce them consistently for live delivery.
- `logging/LogPublisher.java:98-108,130-143` and `cli/LogsCommand.java:35-43`: callbacks follow `force(true)`, but independent polling readers can see a complete record before forcing finishes or fails. Durable-before-delivery is not yet guaranteed for CLI polling. This is a source-level race identified by inspection, not a reproduced test failure.
- `cli/LogsCommand.java:36-42`: the cursor advances before output flush, with no `PrintWriter.checkError()` handling. A failed viewer output can be mistaken for successful consumption.
- `domain/MigrationCommand.java:9-19`: existing operations are only `COPY`, `CONVERT_TEXTURE` and `REWRITE_REFERENCES`. Rename/organization can use approved copy destinations; model conversion needs an explicit operation/schema/validator extension.

## Verified Baseline

- JDK used for this audit: 22.0.2; project compilation targets Java 21.
- `mvnw` is tracked without executable permission. `./mvnw verify` fails with permission denied here; `bash ./mvnw` works without changing tracked permissions.
- Default `bash ./mvnw verify` discovers 111 tests but fails with 4 failures and 46 errors. The failures originate from inventory safety checks rejecting the `/var` symlink in macOS temporary-directory ancestors.
- Setting the temporary directory at test-JVM startup produces **BUILD SUCCESS: 111 tests, zero failures/errors, two skipped**. Both skipped tests require Windows junction support. This validates existing components, not the missing workflows.
- JavaFX was inspected but not launched during this audit. Live AI and external converters cannot be validated because their implementations do not exist.

Reproducible verification command from this repository root, after `target/` exists:

```sh
bash ./mvnw '-DargLine=-Djava.io.tmpdir=/Users/kunt/EECS3311/AIAssetMigrate/target' verify
```

Treat the default temporary-path failure as a portability issue to resolve, not a reason to remove symlink protections. Passing `-Djava.io.tmpdir` only as a Maven property did not resolve it; the test JVM needs the startup argument.

## Implementation Rules

- Keep `AssetManager` as the shared facade. Inject its implementation into CLI and GUI; neither interface should coordinate file operations independently.
- Retain the existing inventory repository and logger. Add the missing behavior incrementally instead of renaming working classes solely to match UML labels.
- Use Command for reviewable ordered operations, Strategy for converters, Adapter for the model provider, and Observer for log consumers. Command data alone must never authorize execution.
- Preserve original assets. Rename, organization, mapping and conversion operate on output copies; source, output, state and log locations must not overlap in unsafe ways.
- Persist accepted changes separately from AI suggestions. Treat model output and imported asset contents as untrusted input; validate paths, IDs, operation kinds, references and tool arguments deterministically.
- Bind approval to the exact plan version and its inventory/review inputs, target and output root. Edits invalidate approval; changed source fingerprints block execution even without a rescan.
- Persist events before notifying viewers. A viewer failure must not stop work; an event-storage failure must prevent subsequent asset-write steps.
- Keep findings and reports when optional AI explanations/summaries fail. Do not invent successful validation or silently use a paid model fallback.
- Preserve existing inventory schemas or introduce explicit version handling when persisted inventory fields change. New state types need schema versions, safe publication, and restart tests.

## Ordered Milestones

### M0. Resolve Scope and Baseline Risks

Priority: P0. Start first. The provider feasibility check can run in parallel with M1-M4.

- Make the documented build work on supported systems, including the wrapper executable-mode decision and macOS temporary-path handling. Add regression coverage without permitting traversal through asset symlinks or Windows junctions.
- Specify the first target profile and version. The README proposes Unity-ready output, but the updated design does not define concrete engine-version rules. Do not present generic file checks as proof of successful engine import.
- Publish a small capability matrix: recognized input, inspectable format, supported output, reference-rewrite support, required converter, and unsupported checks. Start with the existing formats rather than expanding import scope.
- Verify that a provider-supported application registration and OAuth flow can authorize the intended OpenAI model usage. Do not assume a ChatGPT subscription grants general API access or reuse credentials from unrelated applications.
- If that OAuth model access is unavailable, record a blocker for live AI and obtain an approved design change before selecting another authentication/billing approach. Continue deterministic development with test doubles, not a hidden fallback.

Exit criteria: reproducible baseline, explicit first target/capability scope, and a documented authorization feasibility outcome.

### M1. Complete Import and Connect Existing Logs

Priority: P0. Depends on baseline decisions in M0. Features: F01 and F10 integration.

- Add a concrete `AssetManager` implementation composed from the scanner, repository and project logger. Unsupported later operations must fail explicitly rather than return fabricated results.
- Implement import, inventory loading and rescan. Hold the project writer lock across relevant state changes; preserve project identity/creation time and increment inventory revisions correctly.
- Define asset identity reconciliation on rescan so unchanged paths do not receive unrelated IDs and orphan later reviews. Changed/deleted assets must invalidate affected plan inputs; do not silently infer ambiguous renames.
- Add bounded format inspection. Distinguish traversal completion, readable bytes and validated format; skip/flag confirmed corrupt assets. Unsupported inspection must be visible, not reported as successful validation.
- Wire scan/save/failure events and `watch()` to the existing logging subsystem. Define how callers report a committed inventory revision if a subsequent logging operation fails.
- Fix subscription cursor semantics and committed-record visibility. Either route CLI logs through the shared facade or retain an intentional standalone reader with equivalent durability/order guarantees. Define watch/read/disconnection errors and advance viewer cursors only after successful output consumption.
- Add CLI `import` and GUI Import Project with folder selection, inventory/warnings display, explicit project/revision context, and background work off the JavaFX thread.

Exit tests: import mixed valid/unsupported/corrupt inputs, reject inaccessible roots, retain healthy siblings, reload after restart, rescan identity/revision handling, interrupted/failed saves, unchanged sources, CLI and GUI import flows, future cursors, concurrent replay/live handoff, partial-write/force failures, committed-record visibility, and event replay across reconnects.

### M2. Add Inspection, Reviewed Metadata and Compatibility

Priority: P0. Depends on M1. Features: deterministic foundations of F02-F05.

- Add bounded reference inspection, beginning with OBJ/MTL and their texture paths. Resolve only within the source inventory; record missing, ambiguous, unsafe and unsupported references explicitly.
- Add deterministic categorization/name/folder/group suggestions and editable review state. Keep semantic category separate from file type; uncertain assets remain Uncategorized. This is an intermediate deterministic foundation, not completion of the hybrid AI features.
- Represent accepted relationships/material bindings and exclusions with sufficient structure to validate them. Suggestions currently carry a scalar value and reviews a string map; add typed, validated semantics only where necessary.
- Persist accepted corrections and their revision. Ensure saving changes and invalidating affected approval cannot leave an approved plan referring to old reviews.
- Implement target-profile rules and converter capability discovery. Separate directly compatible, convertible, blocked and unknown cases; report missing tools and unsupported checks.
- Expose analysis/review and `compatibility` through the facade and a thin CLI path. Add corresponding GUI review panels alongside the service work where practical.

Exit tests: reference parsing/path containment, missing/ambiguous mappings, persisted corrections, uncertain categories, portable duplicate names, target rules, absent converters, and findings retained when optional explanations fail.

### M3. Implement Draft Plans and Exact-Version Approval

Priority: P0. Depends on M2. Feature: F06 foundation and F03/F04 planned changes.

- Build ordered draft commands from inventory, accepted changes, target/output selection and compatibility findings. Use a deterministic builder initially; add AI proposals in M6 through the same validator.
- Validate command allowlists, referenced asset IDs, dependencies/order, duplicate destinations, portable paths, source/output separation, supported tools and unresolved references before approval.
- Persist immutable plan versions and explicit approval records. Include enough input identity to detect stale inventory/reviews, target changes and output-root edits.
- Implement approval/invalidation as application-controlled transitions. Reject an old displayed version and never trust a deserialized APPROVED status without validating its recorded approval and inputs.
- Reject scaffold-only plans without a positive, real persisted inventory revision. Make review/invalidation, approval and run-start authoritative atomic transitions; concurrent rescan/edit/approval cannot produce incompatible state. `migrate(planId)` must capture the exact approved immutable version, not silently select a newer draft. Prohibit conflicting edits during an active run or explicitly define their effect.
- Add plan preview and explicit CLI `plan` / `approve` actions plus GUI Generate Plan / Approve controls. Generating a plan must not write migrated assets or approve anything.

Exit tests: valid draft/approval/reload, stale-version and revision-zero rejection, invalidation after every relevant edit/rescan, concurrent review/approval/run-start, unsupported commands, missing/cyclic dependencies, output conflicts, and no output writes during analysis/planning/approval.

### M4. Execute Safe Output Copies and Save Results

Priority: P0. Depends on M3. Features: F04, F07/F08 foundations, F10 enforcement.

- Implement execution of approved copy/rename/organization commands. Begin with sequential execution; parallel scheduling and automatic resume are not required for a safe first workflow.
- Recheck exact approval, current source fingerprints, paths, destination conflicts and tool capabilities before writes. Enforce one active project writer/run and prevent silent overwrite of existing outputs.
- Bind the bytes actually copied/converted to the approved fingerprints, using verified stable staging or equivalent checks. Preflight alone does not prevent later input changes. Enforce containment/conflict safety at the actual write as well as during planning.
- Persist a run before starting, then step results and recorded output paths. Stage output files safely and finalize them without touching sources.
- Define allowed run/step transitions, timestamps, complete command-status coverage and failure/skip reasons. Recover conservatively if output finalization succeeds but result persistence fails. Preserve execution status separately from validation status and record enough output identity for validation after restart.
- Apply reference rewrites only for explicitly supported formats and approved mappings. Block renames/moves that would leave unresolved references; never use unrestricted text replacement on arbitrary model files.
- Stop dependent work on failure; a conservative first implementation may stop the whole run and record remaining steps as skipped. On interruption/restart, retain partial outputs and mark unfinished work incomplete.
- Integrate durable progress events. Test that log persistence failure stops new asset writes, while viewer disconnection has no effect on execution.
- Surface logger failure outside the failed logger: nonzero CLI operation result and structured GUI failure, including any already-committed inventory/output. Do not imply rollback, recursively log the error, or replace the publisher to bypass its failure gate. Check logging health before each new writing step, including converter dispatch.
- Implement minimum deterministic output validation and report/history persistence in this milestone, so even copy-only attempted runs have retrievable outcomes. Add CLI `migrate`, `validate`, `history` and initial GUI equivalents.

Exit tests: approved copy workflow, source changes after approval and during input consumption, traversal/symlink/output-overlap/overwrite rejection, rewritten reference correctness, step failure, interruption/restart, failure between output finalization and result storage, log failure before/after a step, missing outputs/history, and source-byte preservation.

### M5. Add Real Conversion and Complete Validation

Priority: P1. Depends on M4 and M0 capability choices. Features: F03, F05, F07 and F08.

- Introduce `ConversionStrategy` with model and texture implementations for the declared capability matrix. Evaluate the selected model-conversion software before advertising support; a changed extension or copied bytes is not conversion.
- Extend the command operation vocabulary, persistence version handling and deterministic validator for model conversion before exposing it to plans or AI tools.
- Invoke external tools with validated argument lists, bounded execution time, captured diagnostics and exit-code checks. Do not execute model-generated shell commands.
- Support texture conversion with explicit policies for alpha loss, compression and other lossy changes. Include consequential choices in the reviewed plan.
- Reuse the same capability checks in compatibility, planning and execution. Missing or changed tools must block affected operations rather than silently select another algorithm.
- Complete output validation: existence, readability, actual format, supported metadata checks and resolved references. Keep engine-specific or unsupported checks visibly unknown rather than asserting success.
- Calculate success, partial, failed and incomplete outcomes consistently from execution and validation evidence; retain deterministic reports if AI summaries are unavailable.

Exit tests: small real conversion fixtures, malformed inputs, missing/failed/timed-out tools, lossy-choice approval, valid and broken output references, unsupported checks and report/history round-trips after restart.

### M6. Add Authorized AI Assistance and Natural Language

Priority: P1. Depends on the M0 authorization gate and the validated service paths in M2-M5. Features: hybrid completion of F02-F06/F08, F09 and UC11.

- Implement `CredentialStore`, `OAuthSession`, `LLMClient`, `OpenAIAdapter` and `AIEngine` as separate responsibilities. Store credentials in supported OS-secure storage; never put tokens in project state, plans or logs.
- Implement browser authorization with PKCE/state, callback validation, cancellation, code exchange, refresh, disconnect and failure handling. A connection is established only after valid credentials are stored. Account connection never changes migration approval.
- Disconnect clears stored and in-memory credentials without cancelling the provider subscription or changing migration approval.
- Give the agent a bounded inspection loop, allowed read-only tools and validated structured outputs. Enforce step/time/response limits and separate file contents from application instructions.
- Add AI suggestions for categories, relationships/materials, names/folders and draft steps. Preserve type information, accepted reviews and deterministic compatibility findings on model failure; require explicit review of changes.
- Add optional compatibility explanations and report summaries with deterministic fallback summaries. Handle revoked/expired credentials, rate/usage limits, malformed responses and network failure without paid fallback.
- Implement `command` interpretation into an allowlisted action or clarification and route it through `AssetManager`. Unsupported/ambiguous requests must not execute; natural-language dispatch must not grant approval or bypass preflight checks.
- Add CLI `auth connect/status/disconnect` and `command`, plus GUI account controls and an agent command panel.

Exit tests: fake-provider contract tests for all authorization/model failures, invalid state/PKCE callback handling, credential deletion/storage failure, token redaction, bounded agent loops, invalid tool/action requests, clarification, approval bypass attempts and deterministic results surviving optional AI failure. Run a separate authorized live smoke test only when supported access is available.

### M7. Finish Interface Parity and End-to-End Acceptance

Priority: P1. Depends on the relevant service milestones. Features: complete F01-F10 user workflows.

- Complete CLI coverage: `import`, `analyze categories/relationships/materials/names/folders`, explicit review/edit support, `compatibility`, `plan`, `approve`, `migrate`, `validate`, `history`, `command` and `logs`, plus account setup.
- Complete GUI coverage: project import/inventory, reviewable analyses, mapping/name/layout previews, compatibility findings, exact-version plan approval, migration progress/results, validation/history, agent panel, account setup and Live Logs.
- Keep workflow logic in the facade. Run blocking operations off the JavaFX thread, marshal updates onto it, disable conflicting actions during work, and surface structured failures rather than freezing or silently succeeding.
- Track each viewer's last successfully processed event sequence and support replay/reconnect. Preserve the logger's existing slow/broken-viewer isolation.
- Closing/switching a GUI project or exiting a CLI viewer closes only that subscription, never the project publisher/worker. Bound GUI handoff queues; scheduling `Platform.runLater()` is not yet successful consumption. Handle output/UI failures without falsely advancing the cursor.
- Run CLI and GUI acceptance flows over the same fixture project. Add macOS/Linux/Windows verification, including Windows junction tests on Windows, and refresh README/help to match actual capabilities.

Exit criteria: all core scenarios and exceptions in UC01-UC10 pass through the required interfaces; UC11 setup is verified separately. Include viewer output failure, queue overflow/reconnect, project-switch cleanup and closing a viewer during migration. No feature is marked complete solely because its button, command name or data model exists.

## Dependencies and Delivery Slices

Primary sequence: **M0 baseline -> M1 import -> M2 inspection/review/compatibility -> M3 approval -> M4 safe migration/reports -> M5 conversion -> M6 AI completion -> M7 full acceptance**.

The authorization feasibility spike starts in M0 rather than waiting until the end. AI adapter work can proceed in parallel after that gate using test doubles, but AI-generated operations cannot execute before the shared deterministic validator and approval/execution path exist. GUI/CLI work should accompany each service milestone; M7 closes gaps rather than deferring every interface until last.

Recommended first deliverable: complete M1 using the existing scanner, repository and logger. Recommended first end-to-end migration slice: a supported, copy-only project with reviewed names/layout, exact-version approval, validated references, durable events and a saved report. Treat this as an intermediate milestone, not completion of conversion or hybrid AI requirements.

## Final Acceptance Checklist

- [ ] F01: Inventory and warnings can be imported, saved and displayed; inaccessible roots are rejected and confirmed corrupt/unsupported files are skipped and flagged.
- [ ] F02: Categories can be suggested, corrected and reloaded; uncertain/model-failure cases preserve safe metadata.
- [ ] F03: Relationships and material mappings can be reviewed; missing/ambiguous references require correction or exclusion before affected migration.
- [ ] F04: Names/layout are previewed and validated; only approved output copies are changed and references remain valid.
- [ ] F05: Target/tool compatibility findings and conversion choices remain available without optional AI explanations.
- [ ] F06: Drafts are versioned; explicit current-version approval is required and invalidated by relevant edits.
- [ ] F07: Execution rechecks approval/inputs, preserves sources, records per-step progress and safely handles failure/interruption.
- [ ] F08: Deterministic validation, all run outcomes, report history and missing-history errors survive restart and AI-summary failure.
- [ ] F09: Supported language requests dispatch through the facade; unsupported/ambiguous requests and approval bypass attempts are rejected or clarified.
- [ ] F10: Durable ordered events replay and follow in both interfaces; viewer loss is isolated and log-storage failure stops new write steps.
- [ ] UC11: Supported authorization, secure credential lifecycle and usage limits are verified independently of migration approval.
