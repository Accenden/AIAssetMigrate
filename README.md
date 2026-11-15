# AIAssetMigrate

AIAssetMigrate helps game developers, students and 3D artists move a folder of models,
textures and materials into a clean, engine-ready output folder for **Unity 6000.0** or
**Godot 4.3**. It finds broken references, suggests categories, names and folders, checks
compatibility, builds a migration plan you approve, copies or converts the files, and
validates the result. **Your source folder is never modified.**

An optional AI agent, which uses your ChatGPT account, suggests fixes and can run the
program's read-only tools to prepare a project. The program checks every AI suggestion
before showing it, and only you can approve or run a migration.

- **GUI:** JavaFX, a 5-step wizard (Project → Review → Target → Plan → Migrate)
- **CLI:** picocli, with one command for each feature
- **Stage 2 report:** `stage2-report.pdf` in the repository root

---

## 1. Prerequisites

| Requirement | Notes |
| --- | --- |
| **JDK 21 or newer** | Set `JAVA_HOME` to it (a Java 8 JRE will not work). |
| Internet access | Needed only for the first build, to download dependencies. |
| A desktop session | Needed for the GUI. The CLI works anywhere. |
| ChatGPT account *(optional)* | Needed only for the AI features. |

You do **not** need to install Maven: the included wrapper (`mvnw` / `mvnw.cmd`) downloads it.

## 2. Install dependencies and build

From the repository root:

```powershell
# Windows (PowerShell)
.\mvnw.cmd clean verify
```

```sh
# macOS / Linux
./mvnw clean verify
```

This downloads all dependencies (JavaFX, picocli, Jackson, the Assimp natives), compiles the
code, runs the JUnit tests and packages the runnable JAR **`target/AIAssetMigrate.jar`**.
Add `-DskipTests` to package without running the tests.

The JAR bundles every dependency, including the JavaFX and Assimp native libraries **for the
OS that built it**. A JAR built on Windows runs on Windows, so build it again on macOS or
Linux to run it there.

## 3. Local configuration and API keys

**No API key is used, and no `.env` file is needed.** The AI features sign in with your
ChatGPT account through OAuth (Sign in with ChatGPT, with PKCE). AI requests count against
that account's plan. There is no API-key or paid-API fallback.

To turn on AI (optional):

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.args=auth connect"   # opens your browser to sign in
.\mvnw.cmd -q exec:java "-Dexec.args=auth status"            # shows the account and model
```

In the GUI, use the **AI account** button in the header instead. `auth models`,
`auth use-model SLUG` and `auth disconnect` manage the account.

**Where credentials go:** they are saved per user, **outside the repository**:

- Windows: `%APPDATA%\AIAssetMigrate`, encrypted with DPAPI
- macOS/Linux: `~/.config/aiassetmigrate`, with owner-only permissions

Nothing secret is ever written to the project folder, logs or reports, so nothing secret can
be committed.

**Optional local launcher:** if you can't set `JAVA_HOME` globally, create
`mvnw-local.cmd` (already in `.gitignore`) and use it in place of `mvnw.cmd`:

```bat
@SET "JAVA_HOME=C:\path\to\jdk-21"
@SET "PATH=%JAVA_HOME%\bin;%PATH%"
@CALL "%~dp0mvnw.cmd" %*
```

## 4. Run the application

**From the runnable JAR** (after section 2; any OS, needs Java 21+):

```sh
java -jar target/AIAssetMigrate.jar                  # GUI (no arguments)
java -jar target/AIAssetMigrate.jar --help           # CLI: any arguments run the CLI
java -jar target/AIAssetMigrate.jar import ...       # for example
```

JavaFX prints `WARNING: Unsupported JavaFX configuration` when it starts from a single JAR.
The warning is harmless.

**From Maven** (no packaging step):

| | Windows (PowerShell) | macOS / Linux |
| --- | --- | --- |
| **GUI** | `.\mvnw.cmd javafx:run` | `./mvnw javafx:run` |
| **CLI** | `.\mvnw.cmd -q compile exec:java "-Dexec.args=--help"` | `./mvnw -q compile exec:java -Dexec.args="--help"` |
| **CLI shortcut** | `test-env\aam.cmd --help` | — |

With Maven, pass CLI arguments in `-Dexec.args="..."`. On Windows,
`test-env\aam.cmd <command> ...` is easier because it builds when needed and takes arguments
normally.

## 5. Example usage

### Sample data

`test-env\samples\` has three committed sample projects:

| Sample | What it shows |
| --- | --- |
| `clean` | Everything resolves. Plans, migrates and validates as `PASSED`. |
| `messy` | One problem per file: broken references, an unreadable FBX, a damaged PNG, poor names. |
| `benchmark` | 24 files with known right answers, used by `benchmark`. |

Run `test-env\setup.cmd` once. It copies the samples into the git-ignored play area
`test-env\local\`, so the committed samples never change. See
[test-env/README.md](test-env/README.md) for a file-by-file guide to `messy`.

### GUI walkthrough

1. **Project:** import `test-env\local\sources\messy`. Set the workspace (collapsed section) to
   `test-env\local\state`. Skipped files can be left out with **Leave these files out**.
2. **Review:** run a check (Categories, Relationships, Materials, Names, Folders), then
   **Accept**, **Reject** or **Accept all**. Tick **Use AI suggestions** to add AI suggestions.
3. **Target:** pick Unity or Godot and check compatibility. Tick **Explain issues with AI**
   for plain-language explanations.
4. **Plan:** choose an output folder and create the plan. **Fix blockers...** proposes a fix
   for each blocking issue. Then **Approve**.
5. **Migrate:** run the plan. Outputs are validated and the report and **What to do next**
   are shown.

The **Agent** and **Live logs** open in a side drawer from the header. For example, ask the
Agent: *"prepare this project for godot 4.3"*.

### CLI walkthrough (happy path)

```powershell
test-env\setup.cmd
$t = (Resolve-Path test-env).Path
$o = '--state-directory', "$t\local\state", '--log-directory', "$t\local\state\logs"

test-env\aam.cmd import "$t\local\sources\clean" @o                   # prints Project ID
test-env\aam.cmd analyze categories --project PROJECT-ID @o
test-env\aam.cmd compatibility --project PROJECT-ID --target unity --target-version 6000.0 @o
test-env\aam.cmd plan --project PROJECT-ID --target unity --target-version 6000.0 `
    --output "$t\local\output\clean" @o                               # prints Plan ID
test-env\aam.cmd approve --plan PLAN-ID --version 1 @o
test-env\aam.cmd migrate --plan PLAN-ID @o                            # copies, then validates
test-env\aam.cmd history --project PROJECT-ID @o
```

`test-env\demo.cmd` runs this whole sequence automatically.

AI examples (after `auth connect`):

```powershell
test-env\aam.cmd analyze relationships --ai --project PROJECT-ID @o
test-env\aam.cmd plan --ai --project PROJECT-ID --target godot --target-version 4.3 --output "$t\local\output\g" @o
test-env\aam.cmd command --project PROJECT-ID @o prepare this project for godot 4.3
test-env\aam.cmd benchmark --ai
```

---

## Features

| ID | Feature | CLI | GUI | Type |
| --- | --- | --- | --- | --- |
| F01 | Import assets (scan, fingerprint, leave out files) | `import`, `rescan`, `inventory` | Project | Deterministic |
| F02 | Categorize assets | `analyze categories [--ai]` | Review | Hybrid |
| F03 | Find relationships and map materials, fix broken references | `analyze relationships\|materials [--ai]`, `review` | Review | Hybrid |
| F04 | Rename and organize, with a layout preview | `analyze names\|folders [--ai]`, `analyze-organization`, `preview-organization` | Review | Hybrid |
| F05 | Check target compatibility | `compatibility [--ai]` | Target | Hybrid |
| F06 | Create and approve a migration plan, fix blockers | `plan [--ai]`, `show-plan`, `approve` | Plan | Hybrid |
| F07 | Convert and migrate (PNG↔JPG, OBJ/FBX → GLB) | `migrate`, `show-run` | Migrate | Deterministic |
| F08 | Validate outputs and view report history | `validate [--ai]`, `history` | Migrate | Hybrid |
| F09 | Natural-language commands and agent `PREPARE` | `command REQUEST...` | Agent drawer | AI |
| F10 | Live logs and replay | `logs [--follow]` | Live logs drawer | Deterministic |
| F11 | Benchmark of rules against rules + AI *(new in Stage 2)* | `benchmark [--ai]` | — | Hybrid |

**Safety rules:**

- AI output is checked before you see it, and AI can never approve or migrate.
- A plan runs only after explicit approval, and only once.
- Existing outputs are never overwritten.
- Validation reports a check that could not run as `INCOMPLETE`, never as a pass.

## Design patterns

| Pattern | Participants | Problem solved |
| --- | --- | --- |
| **Facade** | `AssetManager` / `DefaultAssetManager` | The GUI and CLI share one entry point for every feature. |
| **Command** | `MigrationCommand` (command), `AssetOperationCommand` (concrete), `MigrationPlan` (stores commands), `CopyExecutor` (invoker and receiver) | An approved plan is a fixed list of operations that can be inspected, approved, run once and recorded. |
| **Strategy** | `ConversionStrategy`; `TextureConverter`, `AssimpModelConverter`; selected by `Converters` | Converters can be swapped by format without changing the planner or executor. |
| **Adapter** | `LLMClient` (target), `OpenAIAdapter` (adapter), `AIEngine` (client) | AI logic does not depend on one provider's API. |
| **Observer** | `LogPublisher` (subject), `LogObserver` (observer), `LogFollower` | The live log viewers follow events without blocking work. |

## Project structure

```text
src/main/java/io/github/aiassetmigrate/
  application/    AssetManager facade, DefaultAssetManager, CommandDispatcher, BlockerFixes
  domain/         immutable assets, plans, commands, runs, reports, events
  inventory/      scanner, format checks, JSON repositories
  analysis/       categories, references, materials, organization preview
  compatibility/  Unity / Godot target profiles
  conversion/     ConversionStrategy, TextureConverter, AssimpModelConverter, FbxReader
  migration/      plan builder and CopyExecutor
  validation/     OutputValidator
  ai/             LLMClient, OpenAIAdapter, AIEngine
  auth/           Sign in with ChatGPT OAuth and credential storage
  logging/        LogPublisher, LogFollower
  benchmark/      AI benchmark sample and scoring
  cli/            picocli commands
  gui/            JavaFX wizard
src/test/         JUnit 5 tests
test-env/         sample projects and helper scripts
```

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `Build with JDK 21 or newer` | Point `JAVA_HOME` to a JDK 21+, or use `mvnw-local.cmd` (section 3). |
| `import` exits with code `3` | The scan is `PARTIAL` (for example a `.txt` file was skipped). Leave the file out with `rescan ... --exclude PATH` or **Leave these files out**. |
| A plan has blocking findings | Use **Fix blockers...** in the GUI, or follow the next step printed under each finding. |
| `AI_UNAVAILABLE` finding | Not signed in, session expired or plan limit reached. Run `auth connect`. Rule-based results still work. |
| Migrating the same project again | Outputs are never overwritten. Use an empty output folder, or run `test-env\setup.cmd`. |

CLI exit codes: `0` success, `1` error, `2` bad arguments. `import` and `rescan` also use `3`
(partial scan) and `130` (interrupted).

## Limitations

- Validation proves outputs are well-formed (hashes, decoding, references, GLB structure).
  It does not prove they render correctly in the engine.
- FBX files are read with the bundled Assimp library and are never rewritten. An FBX it
  cannot read must be acknowledged or left out.
- Model conversion uses LWJGL's bundled Assimp natives. On Linux it needs glibc 2.28+.
- The runnable JAR works only on the OS it was built on, and it still needs a Java 21+
  runtime. There is no native installer.

## License

See [LICENSE](LICENSE).
