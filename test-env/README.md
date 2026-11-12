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

## sources\clean: the happy path

Three textured OBJ/MTL pairs and a UI icon whose references all resolve. It should plan with
no blocking findings, migrate, and validate as `PASSED`.

- `characters\hero.obj` -> `hero.mtl` -> `hero_diffuse.png`
- `props\crate.obj` -> `crate.mtl` -> `crate_albedo.jpg`
- `ui\menu_icon.png` (transparent PNG)

Try a conversion: accept a `CONVERSION` review with `format=glb` on `characters/hero.obj`
(textured cube, converts with Assimp) or `format=jpg` on `crate_albedo.jpg`, then create a new plan.

## sources\messy: one problem per file

Importing it gives a `PARTIAL` scan (CLI exit code 3).

| File | Exercises |
|---|---|
| `characters\Hero Model FINAL.obj` | `mtllib Hero.mtl` but the file is `hero.mtl`: `REFERENCE_CASE_AMBIGUOUS`, plus a `REFERENCE_CANDIDATE` correction. Spaces and capitals for the names analysis. |
| `weapons\sword.mtl` | `map_Kd sword_tex.png`, which is really in `textures\`: `REFERENCE_UNRESOLVED` and a `REFERENCE_CANDIDATE` to accept as a correction (the output copy gets the reference rewritten). |
| `textures\sword_tex.png` | Target of that correction; also a `CATEGORY_CONFLICT` (Weapons vs. Textures) and a `NAME_RELATIONSHIP_CANDIDATE` with the sword. |
| `props\weapon_rack.obj` | `CATEGORY_CONFLICT` (Props vs. Weapons); left Uncategorized. |
| `props\crate_01.fbx` | FBX header stub: blocks the preview with `INSPECTION_UNSUPPORTED` until you accept an `ACKNOWLEDGMENT` (or exclude it). Validation reports it as not fully checked. |
| `environment\rock.obj` + `rock_albedo.jpg` | Unreferenced texture sharing the model's name: `NAME_RELATIONSHIP_CANDIDATE`. |
| `environment\grass_alpha.png` | Transparent texture: a `CONVERSION` to `jpg` without `background=#RRGGBB` fails the conversion check. |
| `textures\IMG_0042.PNG`, `ui\MainMenu Button.png` | Poor names and an upper-case extension for the names analysis and rename rules (`snake-case`, `by-type`). |
| `misc\notes.txt` | Unsupported extension: skipped and flagged, which makes the scan `PARTIAL`. |
| `misc\broken.png` | Truncated PNG. Import only fingerprints PNGs, so it is inventoried; if migrated, validation fails it with `OUTPUT_FORMAT_INVALID`. |

Until every problem is dealt with, a plan for `messy` has blocking findings (`PARTIAL_INVENTORY`,
`UNRESOLVED_REFERENCE` x2, `INSPECTION_UNSUPPORTED`). A suggested walkthrough:

1. Import `messy` and run the analyses (Analysis and Review tab, or `aam analyze
   categories|relationships|materials|names --project ID ...`).
2. A partial scan can never be planned: delete `sources\messy\misc\notes.txt` and **rescan**
   (revision 2). Reviews are bound to a revision, so review only after the rescan.
3. Accept the two `REFERENCE_CANDIDATE` corrections (`RELATIONSHIP` for `Hero.mtl`, `MATERIAL_MAPPING`
   for `sword_tex.png`) and an `ACKNOWLEDGMENT` for the FBX; optionally settle the category conflicts.
4. Preview the organization, then plan, approve and migrate to `output\messy`. The two corrections
   become `REWRITE_REFERENCES` commands (the output `weapons\sword.mtl` reads
   `map_Kd ../textures/sword_tex.png`; sources are untouched). The run succeeds, but the completion
   report is `FAILED`: `misc/broken.png` does not decode (`OUTPUT_FORMAT_INVALID`), and the FBX is
   `OUTPUT_FORMAT_UNCHECKED`. To compare, start over and also exclude `broken.png` with an `EXCLUSION` review.

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
