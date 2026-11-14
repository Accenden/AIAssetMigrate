# Manual testing environment

A sandbox for trying AIAssetMigrate by hand, through the GUI or the CLI. Generated folders
(`sources`, `state`, `output`) are git-ignored; the scripts are not.

| Script | What it does |
|---|---|
| `setup.cmd` | (Re)creates sample sources and empty `state` and `output` folders. `setup.cmd -KeepState` keeps saved projects. |
| `demo.cmd` | Happy path on `sources\clean`: import, plan (Unity 6000.0), approve, migrate (validates on completion), history. Uses `output\demo`, emptied first. |
| `aam.cmd <command> ...` | Runs the CLI from the current build (compiles first if needed). Rebuild after code changes. |

All scripts are PowerShell under the hood; the `.cmd` files bypass the execution policy.

## Paths to use

| GUI field / CLI option | Value |
|---|---|
| Project step, Asset folder / `import SOURCE` | `test-env\sources\clean` or `test-env\sources\messy` |
| Project step, Workspace folder (collapsed) / `--state-directory` | `test-env\state` |
| (GUI: derived from the workspace) / `--log-directory` | `test-env\state\logs` |
| Plan step, Output folder / `--output` | `test-env\output\clean` or `test-env\output\messy` (must be absolute in the CLI) |

The GUI remembers the workspace and the last project, so set the workspace once.

Each approved plan runs once, and outputs are never overwritten. To migrate the same
project again, empty its output folder or run `setup.cmd` (add `-KeepState` to keep projects).

## Trying the AI features

Connect an account first (AI drawer in the GUI, or `aam auth connect`).

- **Measure:** `aam benchmark` prints the rules-only scores on a built-in sample with known
  answers; `aam benchmark --ai` adds the rules + AI column. Use it to check that a change helps.
- **Plan suggestions:** on `messy`, tick **Ask AI for suggestions** in the Plan step and create the
  plan. The **AI suggestions** card lists what passed the program's checks (for example leaving out
  `misc\broken.png` or fixing `sword_tex.png`); dropped ones are listed with the reason.
- **Agent:** in the Agent drawer ask "prepare this project for godot 4.3". The agent runs the checks
  it needs and offers **Review N proposed decisions**; nothing is saved until you apply them.

## sources\clean: the happy path

Textured OBJ/MTL models, a real FBX and a UI icon whose references all resolve. It should plan with
no blocking findings, migrate, and validate as `PASSED`.

- `characters\hero.obj` -> `hero.mtl` -> `hero_diffuse.png`
- `props\crate.obj` -> `crate.mtl` -> `crate_albedo.jpg`
- `props\barrel.fbx` (binary FBX with its texture embedded; read and validated by the built-in FBX reader)
- `ui\menu_icon.png` (transparent PNG)

Try a conversion: accept a `CONVERSION` review with `format=glb` on `characters/hero.obj`
(textured cube, converts with Assimp), `format=glb` on `props/barrel.fbx`, or `format=jpg` on `crate_albedo.jpg`,
then create a new plan.

## sources\messy: one problem per file

Importing it gives a `PARTIAL` scan (CLI exit code 3).

| File | Exercises |
|---|---|
| `characters\Hero Model FINAL.obj` | `mtllib Hero.mtl` but the file is `hero.mtl`: `REFERENCE_CASE_AMBIGUOUS`, plus a `REFERENCE_CANDIDATE` correction. Spaces and capitals for the names analysis. |
| `weapons\sword.mtl` | `map_Kd sword_tex.png`, which is really in `textures\`: `REFERENCE_UNRESOLVED` and a `REFERENCE_CANDIDATE` to accept as a correction (the output copy gets the reference rewritten). |
| `textures\sword_tex.png` | Target of that correction; also a `CATEGORY_CONFLICT` (Weapons vs. Textures) and a `NAME_RELATIONSHIP_CANDIDATE` with the sword. |
| `props\weapon_rack.obj` | `CATEGORY_CONFLICT` (Props vs. Weapons); left Uncategorized. |
| `props\crate_01.fbx` | FBX header stub with no scene: the built-in FBX reader cannot read it, so the preview is blocked with `INSPECTION_UNREADABLE` until you accept an `ACKNOWLEDGMENT` (**Fix blockers** offers it) or exclude it. Validation reports it as not fully checked. |
| `environment\rock.obj` + `rock_albedo.jpg` | Unreferenced texture sharing the model's name: `NAME_RELATIONSHIP_CANDIDATE`. |
| `environment\grass_alpha.png` | Transparent texture: a `CONVERSION` to `jpg` without `background=#RRGGBB` fails the conversion check (**Fix blockers** offers a white background). |
| `textures\IMG_0042.PNG`, `ui\MainMenu Button.png` | Poor names and an upper-case extension for the names analysis and rename rules (`snake-case`, `by-type`). |
| `misc\notes.txt` | Unsupported extension: skipped and flagged, which makes the scan `PARTIAL` and blocks plans until it is left out (**Leave these files out**, or `rescan --exclude misc/notes.txt`). |
| `misc\broken.png` | Truncated PNG. Import only fingerprints PNGs, so it is inventoried; creating a plan decodes it and blocks with `SOURCE_IMAGE_INVALID` (**Fix blockers** leaves it out). |

Until every problem is dealt with, a plan for `messy` has blocking findings (`PARTIAL_INVENTORY`,
`UNRESOLVED_REFERENCE` x2, `INSPECTION_UNREADABLE`, `SOURCE_IMAGE_INVALID`). The quick way: create
a plan, choose **Fix blockers...** and apply the proposed fixes, which gives a plan with no blockers.
After the run, validation is `INCOMPLETE`, not `PASSED`: the FBX stub stays not fully checked (the reader
cannot read it, and not checked is never a pass), and **What to do next** in the
Migrate step says so. Choose **Leave out** there; a new plan without the FBX is created (into the new
`output\messy-2`). Approve and run it to see `PASSED`. Converting this stub to GLB does not
help: it is only a header, so the conversion check blocks it. The walkthrough below does the same by hand:

1. Import `messy` and run the analyses (Analysis and Review tab, or `aam analyze
   categories|relationships|materials|names --project ID ...`).
2. A partial scan cannot be planned: in the Project step choose **Leave these files out**
   (CLI: `rescan ... --exclude misc/notes.txt`). This rescans to revision 2 without touching the
   source folder. Reviews are bound to a revision, so review only after this rescan.
3. Accept the two `REFERENCE_CANDIDATE` corrections (`RELATIONSHIP` for `Hero.mtl`, `MATERIAL_MAPPING`
   for `sword_tex.png`) and an `ACKNOWLEDGMENT` for the FBX; optionally settle the category conflicts.
4. Preview the organization, then plan, approve and migrate to `output\messy`. The two corrections
   become `REWRITE_REFERENCES` commands (the output `weapons\sword.mtl` reads
   `map_Kd ../textures/sword_tex.png`; sources are untouched). The plan is blocked until
   `misc/broken.png` is excluded (it does not decode: `SOURCE_IMAGE_INVALID`). The run then succeeds
   and the report is `INCOMPLETE`, because the FBX is `OUTPUT_FORMAT_UNCHECKED`.

## Example CLI session

```powershell
$t = (Resolve-Path test-env).Path
$c = '--state-directory', "$t\state", '--log-directory', "$t\state\logs"
test-env\aam.cmd import "$t\sources\messy" @c
test-env\aam.cmd analyze relationships --project PROJECT-ID @c
test-env\aam.cmd preview-organization --project PROJECT-ID --inventory-revision 1 @c
test-env\aam.cmd plan --project PROJECT-ID --target unity --target-version 6000.0 --output "$t\output\messy" @c
```

The GUI: `.\mvnw-local.cmd -q -B javafx:run` (or `.\mvnw-local.cmd` with no arguments to build, test and open it).
