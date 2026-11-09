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

Deterministic category, relationship and material analysis and explicit review
storage are available as described below. AI assistance, plan approval, conversion,
migration and report workflows are not implemented. Import does not claim
compatibility with any game engine.

## Build and Run

Install a **JDK 21 or newer** and set `JAVA_HOME` to its installation directory.
The Maven wrapper downloads pinned Maven 3.9.9 on first use; a global Maven
installation is unnecessary. Initial dependency downloads require internet access.

From PowerShell in the repository root:

```powershell
# Example for the JDK already installed on this machine:
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.9"
./mvnw.cmd clean verify
./mvnw.cmd exec:java "-Dexec.args=--help"
./mvnw.cmd exec:java "-Dexec.args=--version"
./mvnw.cmd javafx:run
```

Use `bash ./mvnw` instead of `./mvnw.cmd` on macOS/Linux; the tracked wrapper
does not currently have executable permission. JavaFX requires a graphical
desktop. To launch and automatically close the application for a smoke test:

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
- `cli`: picocli import, analysis, review and logs commands, independent of JavaFX startup.
- `gui`: JavaFX import, inventory, analysis and review displays.
- `src/test`: JUnit 5 contract and CLI tests, including temporary-directory tests.

The shared facade implements import, rescan, inventory loading, analysis and review.
Later workflow methods explicitly reject unsupported operations. A plan data object is not
authorization to write files; a later execution implementation must validate
explicit approval and source fingerprints before execution.

## Import

Select **Import Project** in the GUI, then choose a source folder and separate
state and log directories. For the CLI, for example:

```sh
bash ./mvnw exec:java '-Dexec.args=import /absolute/path/to/assets --state-directory /absolute/path/to/state --log-directory /absolute/path/to/logs'
```

Quote individual paths containing spaces in the `exec.args` value. State and log
directories must not overlap each other or the source directory. Unsafe symlinks,
junctions and path aliases are rejected, including aliases in selected paths'
ancestors. Path checks are best-effort against concurrent filesystem replacement
and cannot reliably identify bind mounts; do not use bind-mounted aliases of the
source, state or log locations. An import reports its project ID, inventory
revision, assets, findings and path coverage. A failure after publication reports the committed
project ID and revision; it does not imply the saved inventory was rolled back. To update an
existing project's inventory, use its UUID from the import output:

```sh
bash ./mvnw exec:java '-Dexec.args=rescan /absolute/path/to/assets --project PROJECT-UUID --state-directory /absolute/path/to/state --log-directory /absolute/path/to/logs'
```

| Input | Type | Current inspection | Output/conversion |
| --- | --- | --- | --- |
| OBJ | Model | Bounded text sample; not complete validation | Not implemented |
| FBX | Model | Limited binary-header checks where applicable; otherwise unverified | Not implemented |
| MTL | Material | Bounded text sample; not complete validation | Not implemented |
| PNG | Texture | Limited PNG header checks | Not implemented |
| JPG/JPEG | Texture | Limited JPEG marker checks | Not implemented |

Supported readable files are retained as **unverified** with a warning when
inspection cannot establish validity. Confirmed invalid checked headers and
unsupported extensions are skipped and flagged. These checks do not decode the
complete files or verify references, engine import, conversion support or actual
rendering. Warnings make a scan `PARTIAL`, even if every directory was traversed;
the path outcomes separately describe traversal coverage. Inventory revisions
preserve same-path asset IDs on rescan and retain coverage evidence when a
directory could not be visited. The facade and CLI expose rescan and revision
loading; the GUI can load a saved revision or rescan a project by its UUID.
On filesystems that do not expose Unix link counts (including typical Windows
configurations), logging currently fails closed rather than risk writing through
a hard link to a source asset. Import and rescan therefore cannot complete there;
saved-inventory loading remains available.
Inventory snapshots are limited to 64 MiB; per-file warnings and long paths can
cause a large scan to reach that limit before its 100,000-entry traversal limit.

## Analysis and Review

Run an analysis against the latest saved inventory:

```bash
bash ./mvnw -q exec:java -Dexec.mainClass=io.github.aiassetmigrate.cli.AssetMigrateCli \
  -Dexec.args='analyze categories --project PROJECT-UUID --state-directory /tmp/asset-state --log-directory /tmp/asset-logs'
```

The analysis kind can be `categories`, `relationships`, or `materials`. Suggestions
are not accepted automatically. The JavaFX Analysis and Review tab can submit an
explicit decision; the CLI can also do so, for example:

```bash
bash ./mvnw -q exec:java -Dexec.mainClass=io.github.aiassetmigrate.cli.AssetMigrateCli \
  -Dexec.args='review --project PROJECT-UUID --inventory-revision 1 --asset ASSET-UUID --kind CATEGORY --decision ACCEPTED --value category=Characters --state-directory /tmp/asset-state --log-directory /tmp/asset-logs'
```

Relationship reviews use `--value targetAssetId=ASSET-UUID`; material reviews
also require `--value reference=ORIGINAL-REFERENCE`. Decisions are saved for the
current inventory revision and are not carried forward automatically on rescan.
Use the inventory revision printed by `analyze`; a review of an older revision
is rejected if the project has been rescanned, so run analysis again first.
Analysis is rule-based, not AI-assisted. OBJ/MTL inspection uses bounded,
fingerprint-checked UTF-8 content and reports unsupported, missing or ambiguous
references; it is not a complete parser. Reviews do not modify source files or
apply mappings. Migration plans and execution remain unimplemented.

## File Logs

No SQLite is used or required. Each project has a UTF-8 JSON-lines file named
`<project-uuid>.log` in an explicitly chosen application log directory. Keep this
directory outside source assets. A `<project-uuid>.lock` sidecar enforces one
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
`FileChannel.force` on a read-only directory channel; Java does not guarantee that
this persists directory entries on every provider. Consequently crash durability
is not yet established across platforms. Path validation also cannot rule out
hostile concurrent entry replacement; use a trusted log directory.

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
that project's publisher during this time. Watching an existing project also
inherits the documented non-Unix logging limitation.

Read existing logs from the CLI (replace the example UUID with the project ID):

```powershell
./mvnw.cmd exec:java '-Dexec.args=logs --directory "C:/app data/logs" --project 00000000-0000-0000-0000-000000000001 --after 0'
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
Filesystems without verifiable Unix link counts currently fail closed for logging.
This initial implementation rereads the file for replay/follow and has no rotation
or retention policy; it is intended for modest local logs, not large archives.
Import produces project events. The JavaFX log viewer and integration with later
workflow operations are still future work.

## OAuth Foundation (Part 1)

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

OS-secure credential persistence, refresh/disconnect of saved accounts, CLI
`auth` commands, GUI account controls and AI requests are subsequent increments.
This part neither establishes a durable connection nor changes migration approval,
and it never falls back to API-key billing. Tests use a local fake provider and
local signing keys; live account/provider availability has not been verified.
