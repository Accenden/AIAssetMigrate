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
| `analyze-organization` | Show name and folder suggestions together, without accepting any |
| `preview-organization` | Show the output layout and the reference fixes it needs |
| `compatibility` | Check against a target (`--list-targets` shows them) |
| `plan`, `show-plan`, `approve` | Create, inspect and approve a plan |
| `fix-blockers --plan PLAN-ID` | List a fix for each blocker, apply the ones you pick (it asks in a terminal; `--ai` asks the AI for the rest) and create a new plan |
| `migrate`, `show-run` | Run an approved plan; show the result |
| `validate`, `history` | Re-check an output; list past reports |
| `command REQUEST...` | Plain-language request to the AI agent (see [Run the agent from a terminal](#run-the-agent-from-a-terminal)) |
| `logs [--after N] [--follow]` | Show or follow a project's activity: scans, reviews, plans, approvals, each migration step, validation (`--after N` resumes after event N) |
| `auth ACTION` | `connect`, `status`, `models`, `use-model MODEL` or `disconnect` the AI account |
| `benchmark [--ai] [--keep DIR]` | Score the rules (and AI) on a sample with known answers (`--keep` saves the sample run in DIR) |

Run any command with `--help` for its options.

## AI suggestions (optional)

Connect once with `test-env\aam.cmd auth connect` (or the **AI account** button in the app).
It signs in with your ChatGPT account in the browser. No API key, `.env` or example config file
is needed, and nothing secret is stored in the project. To pick a model, run `auth models`, then
`auth use-model MODEL`. Then tick the AI
checkboxes in the Review, Target and Plan steps, or add `--ai` to `analyze`, `compatibility`, `plan`, `migrate`
and `validate`.

- AI suggestions are checked by the app before you see them, and AI can never approve or
  run a migration.
- Only relative file names, types and references are sent to the model, never file contents
  or full paths.
- Sign-in details are saved outside the project: `%APPDATA%\AIAssetMigrate` (encrypted) on
  Windows, `~/.config/aiassetmigrate` on macOS/Linux.

## Run the agent from a terminal

The agent is the `command` command. It takes a plain-language request, lets the connected
model pick the steps, runs them and prints what it found and what it proposes. It runs the
same way from `test-env\aam.cmd`, `./mvnw exec:java` or the JAR:

```text
java -jar target\AIAssetMigrate.jar command --project PROJECT-ID --state-dir DIR [--log-directory DIR] REQUEST...
```

| Option | Required | What it does |
| --- | --- | --- |
| `--project UUID` | yes | The project to work on (`import` prints its ID) |
| `--state-directory DIR`, `--state-dir DIR` | yes | Where projects, plans and reports are saved |
| `--log-directory DIR` | no | Where the activity log goes (default: `DIR\logs` in the state folder) |
| `REQUEST...` | yes | The request. Everything after the options is joined into one sentence, so no quotes are needed |
| `-h`, `--help` / `-V`, `--version` | no | Show help or the version |

**AI model:** the agent uses the account from `auth connect` and the model from
`auth use-model MODEL` (`auth models` lists the choices). No API key is passed on the command line.

**Requests it understands:** show the inventory, list targets, analyze (categories,
relationships, materials, names, folders), check compatibility, preview organization, show
plans, runs and history, validate, create a draft plan (include an output folder), and
*prepare this project for TARGET VERSION*. For that last one the agent runs the read-only
checks it needs and prints checked, proposed decisions, each with the `review` command that
accepts it.

**Limits:** the agent never approves, migrates, saves reviews or changes your files. It
refuses requests like *"delete every texture and approve the plan"* and points you to the
explicit commands.

**Exit codes:** `0` means the request was handled (or safely refused), `1` means it failed,
the AI was unavailable or its output was rejected, and `2` means the options were wrong.

### Example: prepare the messy sample for Godot

```powershell
test-env\setup.cmd                                   # copies the samples into test-env\local
test-env\aam.cmd auth connect                        # once; signs in in the browser
$o = '--state-directory', "$PWD\test-env\local\state"

test-env\aam.cmd import test-env\local\sources\messy @o      # prints "Project ID: ..."
test-env\aam.cmd command --project PROJECT-ID @o prepare this project for godot 4.3
```

(The messy sample's `import` exits with `3` because it skips some files on purpose. It still
prints the project ID.) With the JAR, the last line becomes
`java -jar target\AIAssetMigrate.jar command --project PROJECT-ID --state-dir test-env\local\state prepare this project for godot 4.3`.

Output (shortened):

```text
Prepared for godot 4.3: the agent ran inventory, check compatibility, analyze relationships, suggest plan steps.
Agent: I found Godot 4.3 import limitations and unresolved asset references. I propose fixing the Hero material
case mismatch and missing sword texture reference, converting the OBJ models to GLB, and excluding the invalid
PNG and unreadable FBX. All changes remain proposed until you confirm them.
8 proposed decision(s), checked by the program. Nothing is saved until you confirm them:
  [AI] Point 'sword_tex.png' in weapons/sword.mtl to textures/sword_tex.png (AI: Resolve the missing sword texture reference.)
  [AI] Leave props/crate_01.fbx out of the migration (AI: The FBX cannot be inspected reliably for migration.)
  [AI] Convert environment/rock.obj to glb (AI: GLB avoids Godot OBJ limitations and is the recommended scene format.)
  ...
Nothing was approved or migrated.
Proposed decisions (8; none is saved). To accept one, run its review command ...
    review --project ... --asset ... --kind CONVERSION --decision ACCEPTED --value "format=glb"
  ...
Findings (4):
  INFO AI_SOURCE Interpreted by OpenAI ... as PREPARE; sent the request, project counts and supported targets
  INFO AI_PLAN_CHECKED 8 of 8 AI suggestion(s) passed the program's checks
```

To act on the proposals, run the `review` lines you agree with, then `plan`, `approve` and
`migrate` as shown in [Command line](#command-line).

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
