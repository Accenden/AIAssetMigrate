# AIAssetMigrate

A tool for preparing game assets for migration, targeting separate Unity-ready
output folders while preserving source assets.

## Current Status

Step 1 is bootstrapped: a single-module Java project, shared domain and
`AssetManager` contracts, CLI help/version, a JavaFX shell, and unit tests.
File-backed project event logging, replay, and live subscriptions are implemented.
Import, project-state persistence, analysis, plan approval, conversion, migration, and live AI
are not implemented. The shell does not read or modify asset files.

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

Use `./mvnw` instead of `./mvnw.cmd` on macOS/Linux. JavaFX requires a graphical
desktop. To launch and automatically close the shell for a smoke test:

```powershell
./mvnw.cmd javafx:run "-Djavafx.args=--smoke-test"
```

The build produces a library JAR, not a bundled standalone application; use the
Maven launch commands above until application packaging is implemented.

## Structure

- `domain`: immutable asset, plan, command, run, report, and event contracts.
- `application`: the shared `AssetManager` boundary and log observer contracts.
- `logging`: file-backed `LogPublisher`, replay, and asynchronous subscriptions.
- `cli`: picocli help/version and `logs` commands, independent of JavaFX startup.
- `gui`: minimal JavaFX application shell.
- `src/test`: JUnit 5 contract and CLI tests, including temporary-directory tests.

The application contract deliberately has no placeholder execution implementation.
Future clients will receive an `AssetManager` implementation through constructor
injection. Persistence, conversion, and AI adapters will be added in their own
packages when implemented. A plan data object is not authorization to write files;
the later application implementation must validate explicit approval and source
fingerprints before execution.

## File Logs

No SQLite is used or required. Each project has a UTF-8 JSON-lines file named
`<project-uuid>.log` in an explicitly chosen application log directory. Keep this
directory outside source assets. A `<project-uuid>.lock` sidecar enforces one
publisher per project while allowing independent readers, including on Windows.
Do not delete lock files or edit/rotate log files while a publisher is running.

Application services can construct and inject a publisher:

```java
try (var logs = new LogPublisher(logDirectory, projectId)) {
    logs.publish(LogEvent.Level.INFO, "Inventory scan started");
    // Optional run/command IDs and structured details are supported by the overload.
}
```

`publish` appends and forces the event to disk before notifying subscribers.
An I/O failure throws `IOException` and disables subsequent writes through that
publisher; callers must stop new asset-writing steps. Sequence numbers resume
after restart. The publisher never logs credentials automatically: callers must
exclude secrets from messages/details.

`subscribe(afterSequence, observer, onFailure)` replays missed events then follows
new events in order on a per-viewer virtual thread. A broken viewer does not stop
logging. A viewer more than 1,024 live events behind is disconnected and notified
through `onFailure`; reconnect from its last processed sequence. Closing a
subscription does not close the publisher. Closing the publisher cancels viewers;
undelivered events remain available for replay.

Read existing logs from the CLI (replace the example UUID with the project ID):

```powershell
./mvnw.cmd exec:java '-Dexec.args=logs --directory "C:/app data/logs" --project 00000000-0000-0000-0000-000000000001 --after 0'
# Add --follow to poll for new events every 250 ms; Ctrl+C stops the viewer.
```

Readers do not create missing history. Malformed records, wrong project IDs, and
sequence gaps are errors. Readers defer an incomplete trailing line, which may
still be being written; reopening a publisher on an incomplete file fails without
changing it. Preserve and inspect damaged files before manually repairing them.
This initial implementation rereads the file for replay/follow and has no rotation
or retention policy; it is intended for modest local logs, not large archives.
The JavaFX log viewer and real workflow event producers are still future work.
