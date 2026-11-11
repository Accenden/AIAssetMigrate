# AIAssetMigrate

A tool for preparing game assets for migration, targeting separate Unity-ready
output folders while preserving source assets.

## Current Status

F01 provides deterministic asset import through the CLI and JavaFX, with
revisioned inventory storage, metadata, bounded format checks and warnings. The
scanner reads but does not modify source assets. File-backed project event logging,
replay, and live subscriptions are implemented separately, with the limitations
described below. The OAuth foundation provides browser authorization and validated
in-memory credentials, not yet a saved AI account connection.

F02-F04 provide deterministic category, relationship/material and organization
analysis with explicit reviews: uncertain categories stay Uncategorized, broken
OBJ/MTL references get uncertain correction candidates, accepted corrections and
renames/moves become reviewed reference rewrites in output copies, and opaque FBX
files need an explicit acknowledgment or exclusion. Limited target compatibility
assessment is available. Draft plans, explicit approval and migration (copies plus
OBJ/MTL reference rewrites) are available with the restrictions below. Optional
AI suggestions for F02-F04 (`analyze KIND --ai`, or the GUI checkbox) use a
connected ChatGPT account and are validated before display; AI planning,
natural-language commands, format conversion and report workflows are not
implemented. Import and format-level checks do not establish
successful import or rendering in a target engine.

## Build and Run

Install a **JDK 21 or newer**. The Maven wrapper downloads pinned Maven 3.9.9 on
first use; a global Maven installation is unnecessary. Initial dependency downloads
require internet access.

The wrappers use `JAVA_HOME`, falling back to the `java` on `PATH`; the build fails
with "Build with JDK 21 or newer" if that is older (for example a Java 8 JRE). Either
set `JAVA_HOME` to a JDK 21+, or create a git-ignored local launcher that pins your
JDK path and forwards its arguments to the wrapper. `/mvnw-local.cmd` and
`/mvnw-local.sh` are already listed in `.gitignore`, for example:

```bat
@REM mvnw-local.cmd
@SETLOCAL
@SET "JAVA_HOME=%USERPROFILE%\.jdks\ms-21.0.9"
@SET "PATH=%JAVA_HOME%\bin;%PATH%"
@CALL "%~dp0mvnw.cmd" %*
```

Then run `.\mvnw-local.cmd clean verify` in place of `./mvnw.cmd clean verify`.

From PowerShell in the repository root:

```powershell
./mvnw.cmd clean verify
./mvnw.cmd exec:java "-Dexec.args=--help"
./mvnw.cmd exec:java "-Dexec.args=--version"
./mvnw.cmd javafx:run
```

Use `./mvnw` (or `bash ./mvnw` from Git Bash) instead of `./mvnw.cmd` on
macOS/Linux. `.gitattributes` keeps `mvnw` and `*.sh` checked out with LF endings so
`/bin/sh` accepts them. JavaFX requires a graphical desktop. To launch and automatically close the application for a smoke test:

```powershell
./mvnw.cmd javafx:run "-Djavafx.args=--smoke-test"
```

The build produces a library JAR, not a bundled standalone application; use the
Maven launch commands above until application packaging is implemented.

## Structure

- `domain`: immutable asset, plan, command, run, report, and event contracts.
- `application`: the shared `AssetManager` boundary and file-backed import service.
- `inventory`: scanning, limited format inspection and revisioned inventory storage.
- `logging`: file-backed `LogPublisher`, replay, and asynchronous subscriptions.
- `auth`: OpenAI public-client OAuth with PKCE, loopback callbacks, and signed identity validation.
- `analysis`: conservative categorization and bounded OBJ/MTL reference analysis.
- `compatibility`: versioned, documented format rules and coverage findings.
- `cli`: picocli import, analysis, review and logs commands, independent of JavaFX startup.
- `gui`: JavaFX import, inventory, analysis and review displays.
- `src/test`: JUnit 5 contract and CLI tests, including temporary-directory tests.

The shared facade implements import, rescan, inventory loading, analysis, review and compatibility checks.
Later workflow methods explicitly reject unsupported operations. A plan data object is not
authorization to write files; a later execution implementation must validate
explicit approval and source fingerprints before execution.

## Import

F01 imports use a source folder and a separate state directory. Logs are managed
under that state directory, so they cannot accidentally be placed in the source
tree. For the CLI, for example:

```sh
bash ./mvnw exec:java '-Dexec.args=import /absolute/path/to/assets --state-dir /absolute/path/to/state'
```

Use `--state-directory` as an alias for `--state-dir`. Quote individual paths
containing spaces in the `exec.args` value. The state directory must not overlap
the source directory. Unsafe symlinks, junctions and path aliases are rejected,
including aliases in selected paths' ancestors. Path checks are best-effort
against concurrent filesystem replacement and cannot reliably identify bind
mounts; do not use bind-mounted aliases of the source and state locations.

The state layout is:

```text
<state-dir>/projects/<project-uuid>/inventory-<revision>.json
<state-dir>/logs/<project-uuid>.log
```

An import reports its project ID, revision, source root, assets, findings,
coverage, and the matching `logs` command. A failure after publication reports
the committed project ID and revision; it does not imply the saved inventory was
rolled back. To rescan an existing project, either pass `--project` to `import`
or use the compatibility `rescan` command:

```sh
bash ./mvnw exec:java '-Dexec.args=import /absolute/path/to/assets --state-dir /absolute/path/to/state --project PROJECT-UUID'
bash ./mvnw exec:java '-Dexec.args=inventory --state-dir /absolute/path/to/state --project PROJECT-UUID --revision 1'
```

| Input | Type | Current inspection | Output/conversion |
| --- | --- | --- | --- |
| OBJ | Model | Bounded UTF-8/BOM text checks, basic vertices/faces, material-library references | Not implemented |
| FBX | Model | Bounded binary or ASCII header/version recognition | Not implemented |
| MTL | Material | Bounded UTF-8/BOM text checks and texture references | Not implemented |
| PNG | Texture | Extension classification and streaming SHA-256 only; no content validation | Not implemented |
| JPG/JPEG | Texture | Extension classification and streaming SHA-256 only; no content validation | Not implemented |

The scanner never decodes image content. It records image inspection as
`NOT_PERFORMED` with `SHA256_STREAM_ONLY`; arbitrary readable PNG/JPEG-named
bytes remain inventoried. OBJ/MTL references are resolved only against the saved
scan result and retain `RESOLVED`, `MISSING`, `SKIPPED`, `UNRESOLVED`,
`CASE_AMBIGUOUS`, or `UNSAFE` evidence. These checks do not establish complete
file validity, engine import, conversion support, or rendering.

`COMPLETE` means every discovered path was fully traversed or inventoried.
`PARTIAL` means coverage was skipped, failed, or unvisited, for example because
of an unsupported extension, a size limit, or an unsafe/inaccessible path.
Inspection warnings alone do not make a scan partial. CLI exit codes are `0` for
complete, `3` for a saved partial snapshot, `1` for a fatal error, `2` for
argument errors, and `130` for interruption.

Default limits are depth 64, 100,000 entries, 1 GiB per supported file, 64 MiB
of parsed OBJ/MTL text, 1 MiB per text line, 100,000 extracted references per
file, and 64 MiB per serialized inventory snapshot. Inventory revisions preserve
same-path asset IDs on rescan and retain coverage evidence when a directory could
not be visited.

## Analysis and Review

Run an analysis against the latest saved inventory:

```bash
bash ./mvnw -q exec:java -Dexec.mainClass=io.github.aiassetmigrate.cli.AssetMigrateCli \
  -Dexec.args='analyze categories --project PROJECT-UUID --state-directory /tmp/asset-state --log-directory /tmp/asset-state/logs'
```

The analysis kind can be `categories`, `relationships`, `materials`, `names`, or `folders`. Suggestions
are not accepted automatically. The JavaFX Analysis and Review tab can submit an
explicit decision; the CLI can also do so, for example:

```bash
bash ./mvnw -q exec:java -Dexec.mainClass=io.github.aiassetmigrate.cli.AssetMigrateCli \
  -Dexec.args='review --project PROJECT-UUID --inventory-revision 1 --asset ASSET-UUID --kind CATEGORY --decision ACCEPTED --value category=Characters --state-directory /tmp/asset-state --log-directory /tmp/asset-state/logs'
```

Relationship reviews use `--value targetAssetId=ASSET-UUID` and can provide
`--value reference=ORIGINAL-REFERENCE` so a later correction to that reference
replaces the previous target. Legacy relationship reviews without a reference
remain separate decisions and must be rejected explicitly if no longer wanted. Material reviews
also require `--value reference=ORIGINAL-REFERENCE`. Decisions are saved for the
current inventory revision and are not carried forward automatically on rescan.
Use the inventory revision printed by `analyze`; a review of an older revision
is rejected if the project has been rescanned, so run analysis again first.
Analysis is rule-based unless `--ai` is given (see below). OBJ/MTL inspection uses bounded,
fingerprint-checked UTF-8 content and reports unsupported, missing or ambiguous
references; it is not a complete parser. Reviews never modify source files.
Manually entered mappings are user assertions, not proof that a reference exists
in the source.

**Categories (F02).** Whole words of the relative path (folders and file stem) are
matched against a fixed vocabulary: Characters, Weapons, Props, Environment,
Vehicles, UI, Effects, Animations, Textures and Materials. Exactly one match is
suggested; conflicting words leave the asset Uncategorized with a
`CATEGORY_CONFLICT` finding. Without any word signal, textures and materials fall
back to Textures/Materials and other assets stay Uncategorized. File types are never
changed.

**Relationships and materials (F03).** Resolved OBJ `mtllib` and MTL texture
references become suggestions. A missing, case-mismatched or unresolved reference
also yields up to five uncertain `REFERENCE_CANDIDATE` suggestions: assets of the
expected type whose filename matches the reference's last segment, ignoring case.
`relationships` additionally suggests reference-less groupings
(`NAME_RELATIONSHIP_CANDIDATE`) for unreferenced textures whose name starts with a
model or material name, for example `crate.obj` and `crate_albedo.png`.

An accepted `RELATIONSHIP` or `MATERIAL_MAPPING` review whose `reference` equals one
of the asset's file references is a **correction**: the preview uses its target
instead of the broken one, and migration rewrites the reference in the output copy.
The target type must match (an `mtllib` needs a material, a texture directive a
texture), or the preview reports `INVALID_CORRECTION`. A `MATERIAL_MAPPING` from a
model whose reference is a `usemtl` material name is validated only: the target
must already be one of the model's (possibly corrected) material libraries, otherwise
`MAPPING_LIBRARY_NOT_REFERENCED` blocks the plan.

**Opaque formats.** FBX references cannot be inspected, so an FBX asset blocks the
preview with `INSPECTION_UNSUPPORTED` until it is excluded or explicitly
acknowledged: `review --kind ACKNOWLEDGMENT --value reason=...`. Acknowledgments are
rejected for OBJ/MTL (which are inspected) and for textures. Use `reviews --project PROJECT-UUID` with the
state directory and its managed `logs` child to list saved decisions, or use **Load Saved
Reviews** in the GUI.

### AI suggestions (hybrid F02-F04)

Rule-based analysis always runs. Adding `--ai` to `analyze` (or ticking **Include
AI suggestions** in the GUI) also asks the connected model, and the results are
merged: rule suggestions are kept, and AI suggestions appear alongside them marked
`Source: AI` with an `AI_SUGGESTION` finding that gives the model's reason.
Accepting one is an ordinary explicit `review`; nothing is applied automatically.

```bash
./mvnw-local.sh exec:java '-Dexec.args=auth connect'
./mvnw-local.sh exec:java '-Dexec.args=analyze categories --ai --project PROJECT-UUID --state-directory /tmp/s --log-directory /tmp/s/logs'
```

| Kind | What the model is asked | Accepted only if |
| --- | --- | --- |
| `categories` | A category for assets the rules left Uncategorized | It is in the fixed vocabulary |
| `relationships` | Targets for broken references; texture groupings | The reference is actually broken for that asset and the target has the expected type |
| `materials` | Targets for broken texture references | As above, textures only |
| `names` | Clearer filenames | Portable, and the extension is unchanged |
| `folders` | An output folder layout (uses accepted categories) | Portable relative path, at most 4 levels |

Only relative paths, asset types, formats, accepted categories and reference text
are sent, for at most 300 assets per request; file contents, fingerprints and
absolute paths never are. Names and paths are framed as untrusted data, and any
proposal that fails validation is dropped and counted in `AI_OUTPUT_REJECTED`. When
nothing is uncertain no request is made (`AI_NOT_NEEDED`). If the account is not
connected, the session expired, the plan's usage limit is reached or the model
returns unusable output, the rule-based result is returned with an `AI_UNAVAILABLE`
finding. There is no API-key or paid fallback.

### AI account

`auth connect` signs in with ChatGPT in the browser (OAuth with PKCE), lists the
models the account may use (`GET /v1/models`, `visibility: "list"`) and selects
`--model SLUG` or the first one. `auth status`, `auth models`,
`auth use-model SLUG` and `auth disconnect` manage the saved account; the GUI has
**Connect AI Account** and **Disconnect AI Account**. Credentials are stored per
user, outside project state, in `%APPDATA%\AIAssetMigrate` (Windows, encrypted with
DPAPI for the current user) or `~/.config/aiassetmigrate` (owner-only file
permissions). Access tokens are refreshed shortly before expiry under a
cross-process lock and the rotated refresh token is saved before use; a rejected
token triggers one forced refresh. `disconnect` revokes the refresh token at the
issuer's discovered revocation endpoint, removes local credentials, and says when
remote revocation was not confirmed. Connecting never changes migration approval.
Requests follow OpenAI's documented Sign in with ChatGPT flow, but the live service
has only been exercised through local fakes in tests.

Name and folder analysis starts with the current names and folders. Edit and
explicitly accept a `NAME` review (`--value name=FILE`) or `FOLDER` review
(`--value folder=RELATIVE-FOLDER`, with an empty value for the output root), then
run `preview-organization --project PROJECT-UUID --inventory-revision 1` with the
state directory and its managed `logs` child. The GUI also provides an organization preview.
The preview is read-only and checks lexical destination conflicts, reference
coverage and inventory revision; it does not inspect an output directory or grant
approval. When an OBJ/MTL file or anything it references moves or is renamed, the
preview lists the reference rewrites its output copy needs (for example
`Rewrite TEXTURE reference: textures/hero.png -> ../tex/hero.png`). References are
rewritten only as single tokens: if the old or new text would need spaces, quotes,
`#`, backslashes, colons or a leading `-`, the preview reports
`REFERENCE_REWRITE_UNSUPPORTED` and blocks instead. Unresolved references without an
accepted correction remain blocking. The plan workflow is described below.

To generate rule-based proposals instead, use `analyze-organization` with
`--name-style preserve|snake-case|kebab-case` and
`--folder-style preserve|by-type|by-category`, along with the project,
state-directory and log-directory options. `by-category` places each asset in a
folder named after its accepted `CATEGORY` review, falling back to its type folder
(Models, Materials, Textures, Other) when it has none. The GUI offers
the same rules. These are combined proposals for the current inventory, not saved
reviews. Review each desired change explicitly and run the organization preview
again before proceeding; its reference and destination checks use the saved
decisions rather than the unaccepted rule proposals.

## Compatibility

The CLI can list available profiles or assess an imported inventory:

```sh
bash ./mvnw exec:java '-Dexec.args=compatibility --list-targets --state-directory /tmp/asset-state --log-directory /tmp/asset-state/logs'
bash ./mvnw exec:java '-Dexec.args=compatibility --project PROJECT-UUID --target unity --target-version 6000.0 --state-directory /tmp/asset-state --log-directory /tmp/asset-state/logs'
```

The GUI also provides target selection and compatibility assessment. Results
identify the target and inventory revision and retain import and reference
findings. Format candidates come from the target's documented format list, not
from a complete decode or actual engine import. Unsupported reference inspection,
incomplete inventories, missing references and unverified content are reported.
Installed converters are not probed and no executable conversion choices are
claimed. The assessment does not approve or perform migration.

Godot 4.3 is a version-specific reference profile based on its official
[3D format](https://docs.godotengine.org/en/4.3/tutorials/assets_pipeline/importing_3d_scenes/available_formats.html)
and [image import](https://docs.godotengine.org/en/4.3/tutorials/assets_pipeline/importing_images.html)
documentation. That documentation marks the engine version as no longer supported;
the profile should not be assumed to describe other versions.
The Unity 6000.0 profile is based on the official Unity 6.0 documentation for
[model formats](https://docs.unity3d.com/6000.0/Documentation/Manual/3D-formats.html)
and [texture import](https://docs.unity3d.com/6000.0/Documentation/Manual/ImportingTextures.html).
Material fidelity and unsupported or undocumented requirements remain unknown.

## Migration

Use `plan --project PROJECT-UUID --target unity --target-version 6000.0 --output
/absolute/output` with the state directory and its managed `logs` child to create a draft.
Inspect its sources, destinations and findings using `show-plan --plan PLAN-UUID`.
Then explicitly use `approve --plan PLAN-UUID --version 1`, followed by the
separate `migrate --plan PLAN-UUID` command. Use `show-run --plan PLAN-UUID` to
inspect the recorded outcome, including an interrupted run. The GUI provides the
same separate draft, approval and migration steps.

Plans contain `COPY` commands, plus `REWRITE_REFERENCES` commands for OBJ/MTL
files whose reviewed preview lists rewrites; their parameters record each exact
`from -> to` replacement. Only layouts without blocking findings can be approved.
Approval and execution recheck the inventory, review revision and commands, and the
executor rejects rewrite commands that differ from the current reviewed preview;
each approved plan can be run once. A rewrite changes only the matching `mtllib`
tokens or texture directive values; comments, other lines and line endings are kept
byte for byte, and the step detail records the output SHA-256. The output root must
already exist and be separate from source, state and log directories; missing
destination folders inside it are created during execution, never during preflight. Existing
output files are never intentionally overwritten. Source contents are fingerprinted
and the copied bytes checked before no-replace publication. A failed run can leave
earlier copies in place; there is no rollback or crash-durable output guarantee.
Retryable preflight errors are checked before the single-use run is reserved. If
execution begins and is interrupted, the saved run remains incomplete and the
same plan cannot be run again.

Publication requires hard-link support. On Unix filesystems sources must report
a link count of one and file keys identify directories and files. Windows (NTFS)
exposes neither, so identities fall back to creation time and size, source hard
links are not detected, and directory forcing is skipped; source fingerprints are
still rechecked before every publish.
Path and identity rechecks are best effort against hostile concurrent filesystem
replacement, not a handle-anchored sandbox. Source, output, state and log paths and
their ancestors must remain trusted against concurrent manipulation. Unsupported
reference rewrites and conversions remain blocking or unavailable; no such
operations are silently performed.

## File Logs

No SQLite is used or required. Each project has a UTF-8 JSON-lines file named
`<project-uuid>.log` in `<state-dir>/logs`. Keep the state directory outside
source assets. A `<project-uuid>.lock` sidecar enforces one
publisher per project, while `.gate` coordinates snapshots and commits and
`.commit` identifies the validated log prefix. Do not delete or edit log files
or sidecars while a publisher or reader is running. Older versions do not honor
the new gate and must not access these logs concurrently.

Application services can construct and inject a publisher:

```java
try (var logs = new LogPublisher(logDirectory, projectId)) {
    logs.publish(LogEvent.Level.INFO, "Inventory scan started");
    // Optional run/command IDs and structured details are supported by the overload.
}
```

`publish` appends and forces the event and publishes a versioned, hash-bound
commit marker before notifying subscribers.
An I/O failure throws `IOException` and disables subsequent writes through that
publisher; callers must stop new asset-writing steps. Sequence numbers resume
after restart. The publisher never logs credentials automatically: callers must
exclude secrets from messages/details.

Independent readers coordinate through the gate and read only the validated
prefix identified by the marker. A marker-stage failure may have an indeterminate
outcome: an event can be present after a failed publish. Directory syncing uses
`FileChannel.force` on a read-only directory channel where the provider permits
it; Windows providers commonly reject that operation after the event bytes have
been forced. Java does not guarantee directory-entry durability on every provider.
Path validation also cannot rule out hostile concurrent entry replacement; use a
trusted state directory.

`subscribe(afterSequence, observer, onFailure)` replays missed events then follows
new events in order on a per-viewer virtual thread. A broken viewer does not stop
logging. A viewer more than 1,024 live events behind is disconnected and notified
through `onFailure`; reconnect from its last processed sequence. Closing a
subscription does not close the publisher. Closing the publisher cancels viewers;
undelivered events remain available for replay.

`AssetManager.watch` subscribes through a manager-owned project publisher. Override
`LogObserver.onError` to report asynchronous failures and reconnect from the last
successfully processed sequence; its default implementation does nothing. Invalid
or future cursors fail synchronously. Closing a subscription leaves the publisher
and ongoing work alone; the manager retains the publisher until it closes, or an
active write finishes after manager close. Another manager or process cannot own
that project's publisher during this time.

Read existing logs from the CLI (replace the example UUID with the project ID):

```powershell
./mvnw.cmd exec:java '-Dexec.args=logs --directory "C:/app data/state/logs" --project 00000000-0000-0000-0000-000000000001 --after 0'
# Add --follow to poll for new events every 250 ms; Ctrl+C stops the viewer.
```

`--after` is exclusive. The CLI advances its cursor only after successfully
writing and flushing an event; an output failure stops the viewer with a nonzero
exit status so the failed event can be replayed.

Readers do not create missing history. Malformed records, wrong project IDs,
sequence gaps, and missing or invalid markers are errors. Readers expose only the
marked prefix; reopening a publisher with an uncommitted tail fails without
changing it. Markerless logs require explicit adoption; their historical durability
cannot be proved. The adoption and tail-recovery tools are not implemented yet,
so affected projects cannot currently be reopened for writing. Preserve the
original files and do not use older versions to bypass these checks.
On filesystems without verifiable Unix link counts, strict state-managed publishers
use validated sidecar identity anchors so logs created by this application can be
reopened without trusting manually pre-existing sidecars. This initial
implementation rereads the file for replay/follow and has no rotation or retention
policy; it is intended for modest local logs, not large archives.
Import produces project events. The JavaFX log viewer and integration with later
workflow operations are still future work.

## OAuth Implementation Notes

`OAuthSession` implements OpenAI's documented [Sign in with ChatGPT flow for
locally hosted open-source apps](https://developers.openai.com/siwc/token-sharing-open-source/sign-in).
It uses dynamic registration for a new account, or a previously validated issued
client ID and subject for returning authorization. It does not borrow another
application's client ID or read another application's credentials.

The caller must supply a stable, already-persisted UUIDv4 host identifier such as
`urn:uuid:<uuid>`. Generate it once per installation, not per sign-in. The OAuth
component deliberately does not choose a storage location or write any files.

```java
var oauth = new OAuthSession(persistedHostId);
OAuthCredentials credentials = oauth.authorize();
// Securely save validated credentials in the account-storage increment before
// announcing a saved connection. Never print, log, or put them in project state.
```

Authorization starts an ephemeral `127.0.0.1` listener before opening the system
browser, generates fresh state/nonce/S256 PKCE values, exchanges the one-use code,
checks granted plan-usage scopes, and verifies the ID token's RS256 signature,
issuer, audience, nonce, timestamps and returning account identity. The default
deadline is five minutes; cancellation and all completion paths release the
listener. `authorize(BrowserLauncher, Duration)` supports an injected browser
launcher and a deadline of at most ten minutes; call `cancelAuthorization()` to
cancel an active attempt. These methods must run off the JavaFX application thread.

Credentials are returned only in memory and have a redacted `toString()`.
Do not log their fields, the authorization URL, callbacks or token responses.
`OAuthException.reason()` supplies a sanitized failure category. A returning
session accepts `Optional.of(new OAuthSession.Registration(savedClientId,
savedSubject))`, using only a previously validated registration.

`refresh(credentials)` uses `grant_type=refresh_token` with the issued client ID and
keeps the existing grant; `revoke(credentials)` uses the discovered revocation
endpoint. Persistence and the account commands are described under
[AI account](#ai-account). Authorization never changes migration approval and never
falls back to API-key billing. Tests use a local fake provider and
local signing keys; live account/provider availability has not been verified.
