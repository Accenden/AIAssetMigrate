# AIAssetMigrate

A tool for preparing game assets for migration, targeting separate Unity-ready
output folders while preserving source assets.

## Current Status

Step 1 is bootstrapped: a single-module Java project, shared domain and
`AssetManager` contracts, CLI help/version, a JavaFX shell, and unit tests.
Import, persistence, analysis, plan approval, conversion, migration, and live AI
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
- `cli`: picocli help/version entry point, independent of JavaFX startup.
- `gui`: minimal JavaFX application shell.
- `src/test`: JUnit 5 contract and CLI tests, including temporary-directory tests.

The application contract deliberately has no placeholder execution implementation.
Future clients will receive an `AssetManager` implementation through constructor
injection. Persistence, conversion, and AI adapters will be added in their own
packages when implemented. A plan data object is not authorization to write files;
the later application implementation must validate explicit approval and source
fingerprints before execution.
