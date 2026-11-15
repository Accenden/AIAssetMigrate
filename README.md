# AIAssetMigrate

AIAssetMigrate turns a messy folder of game assets (OBJ, FBX, MTL, PNG, JPG) into a clean,
engine-ready copy for **Unity 6000.0** or **Godot 4.3**. It finds broken references, suggests
names, folders and categories, converts formats where needed, and checks the result.
**Your original files are never changed.** Nothing is written until you approve a plan.

## Requirements

- **Windows 10/11 (64-bit):** nothing to install. The first run downloads Java 21 and Maven.
- **macOS / Linux:** a JDK 21 or newer, with `JAVA_HOME` set.
- Internet access on the first run.
- *Optional:* a ChatGPT account for AI suggestions.

## Install and run

**Windows (PowerShell):**

```powershell
git clone https://github.com/Accenden/AIAssetMigrate.git
cd AIAssetMigrate
.\mvnw.cmd javafx:run
```

**macOS / Linux:**

```sh
git clone https://github.com/Accenden/AIAssetMigrate.git
cd AIAssetMigrate
./mvnw javafx:run
```

This builds the app and opens it. The first run takes a few minutes; after that it starts in
seconds. No git? Use **Code → Download ZIP** on GitHub, extract it and run the last command in
that folder.

### Runnable JAR (optional)

```powershell
.\mvnw.cmd package -DskipTests                      # creates target\AIAssetMigrate.jar
java -jar target\AIAssetMigrate.jar                 # opens the GUI
java -jar target\AIAssetMigrate.jar --help          # any arguments run the CLI
```

The JAR runs only on the OS that built it and needs Java 21. On Windows without Java,
use the copy the build downloaded:

```powershell
& (Resolve-Path "$HOME\.m2\wrapper\jdks\jdk-21*\bin\java.exe" | Select-Object -First 1) -jar target\AIAssetMigrate.jar
```

## Using the app

The window walks you through five steps:

1. **Project:** choose your asset folder and import it. Files the app can't handle are listed;
   click **Leave these files out** to continue without them.
2. **Review** *(optional)*: run a check (Categories, Relationships, Materials, Names, Folders)
   and **Accept** or **Reject** each suggestion, or **Accept all**.
3. **Target:** pick Unity or Godot and check compatibility.
4. **Plan:** choose an empty output folder and create the plan. If something blocks it, click
   **Fix blockers...**. When it's ready, click **Approve**.
5. **Migrate:** run the plan. The output is checked automatically, and **What to do next**
   explains any problem.

**Agent** and **Live logs** open from the header. Ask the agent things like *"prepare this
project for godot 4.3"*.

### Try it on sample projects

```powershell
test-env\setup.cmd      # copies the samples into test-env\local
```

Then import `test-env\local\sources\clean` (everything works) or `...\messy` (one problem per
file). See [test-env/README.md](test-env/README.md) for what each sample contains.

## Command line

On Windows, run commands with `test-env\aam.cmd <command>`. On macOS/Linux, use
`./mvnw -q compile exec:java -Dexec.args="<command>"`, or `java -jar` with the JAR.

Most commands need a state folder (`--state-directory`, or `--state-dir` for short), where
projects, plans and reports are saved. The activity log goes in its `logs` folder unless you
pass `--log-directory`. A typical run:

```powershell
$o = '--state-directory', 'C:\aam\state'

test-env\aam.cmd import C:\path\to\assets @o                    # prints the project ID
test-env\aam.cmd compatibility --project ID --target unity --target-version 6000.0 @o
test-env\aam.cmd plan --project ID --target unity --target-version 6000.0 --output C:\aam\out @o
test-env\aam.cmd approve --plan PLAN-ID --version 1 @o           # plan prints PLAN-ID
test-env\aam.cmd migrate --plan PLAN-ID @o                       # copies, then validates
```

`test-env\demo.cmd` runs this sequence on the clean sample.

| Command | What it does |
| --- | --- |
| `import`, `rescan`, `inventory` | Scan a folder; rescan it (`--exclude PATH` leaves a file out); show the saved scan |
| `analyze KIND` | Suggestions: `categories`, `relationships`, `materials`, `names` or `folders` |
| `review`, `reviews` | Accept or reject a suggestion; list saved decisions |
| `preview-organization` | Show the output layout and the reference fixes it needs |
| `compatibility` | Check against a target (`--list-targets` shows them) |
| `plan`, `show-plan`, `approve` | Create, inspect and approve a plan |
| `fix-blockers --plan PLAN-ID` | List a fix for each blocker, apply the ones you pick (it asks in a terminal; `--ai` asks the AI for the rest) and create a new plan |
| `migrate`, `show-run` | Run an approved plan; show the result |
| `validate`, `history` | Re-check an output; list past reports |
| `command REQUEST...` | Plain-language request to the AI agent |
| `logs [--follow]` | Show or follow a project's activity: scans, reviews, plans, approvals, each migration step, validation |
| `auth` | Connect or disconnect the AI account |
| `benchmark [--ai]` | Score the rules (and AI) on a sample with known answers |

Run any command with `--help` for its options.

## AI suggestions (optional)

Connect once with `test-env\aam.cmd auth connect` (or the **AI account** button in the app).
It signs in with your ChatGPT account in the browser; no API key is needed. Then tick the AI
checkboxes in the Review, Target and Plan steps, or add `--ai` to `analyze`, `compatibility`, `plan`, `migrate`
and `validate`.

- AI suggestions are checked by the app before you see them, and AI can never approve or
  run a migration.
- Only relative file names, types and references are sent to the model, never file contents
  or full paths.
- Sign-in details are saved outside the project: `%APPDATA%\AIAssetMigrate` (encrypted) on
  Windows, `~/.config/aiassetmigrate` on macOS/Linux.

## What it supports

| | |
| --- | --- |
| **Reads** | OBJ, MTL, FBX, PNG, JPG/JPEG |
| **Converts** | PNG ↔ JPG; OBJ/FBX → GLB |
| **Targets** | Unity 6000.0, Godot 4.3 |

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `Build with JDK 21 or newer` | macOS/Linux: point `JAVA_HOME` to a JDK 21+. |
| Java download fails on Windows | You're offline or a proxy blocks `api.adoptium.net`. Install a JDK 21+ and set `JAVA_HOME`. |
| Import says the scan is partial | Some files were skipped. Leave them out (**Leave these files out**, or `rescan ... --exclude PATH`). |
| The plan is blocked | Click **Fix blockers...** (CLI: `fix-blockers --plan PLAN-ID`), or follow the next step printed under each problem. |
| `AI_UNAVAILABLE` | Not signed in, or the session expired. Run `auth connect`. Everything else still works. |
| Migrating again fails | Existing files are never overwritten. Use an empty output folder. |

## Limitations

- Checks prove the output files are valid, not that they look right in the engine.
- FBX files are copied or converted but never edited. An FBX the app can't read must be
  acknowledged or left out.
- Model conversion on Linux needs glibc 2.28 or newer.

## License

See [LICENSE](LICENSE).
