package io.github.aiassetmigrate.validation;

import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.PortablePath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the user can do about failed or unchecked validation findings, in terms of this tool's own controls.
 * The table is fixed, so the advice is the same with or without AI, and the AI summary may only rephrase it:
 * it is never told to suggest tools or checks the application does not have.
 */
public final class ValidationNextSteps {
    private ValidationNextSteps() {}

    /**
     * One kind of problem: the files it affects and what to do about them. {@code canLeaveOut}: leaving the
     * files out of the next plan (an EXCLUSION) settles it, so the GUI can offer that as a button.
     */
    public record Step(String code, List<String> files, String action, boolean canLeaveOut) {
        public Step {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(action, "action");
            files = List.copyOf(files);
        }
    }

    /** Problems in the file itself, which an EXCLUSION settles; not ones caused by editing outputs after the run. */
    private static final java.util.Set<String> LEAVE_OUT = java.util.Set.of("OUTPUT_FORMAT_INVALID", "OUTPUT_FORMAT_UNCHECKED",
            "OUTPUT_REFERENCE_UNRESOLVED", "OUTPUT_REFERENCE_OUTSIDE_RUN", "OUTPUT_REFERENCES_UNCHECKED");

    /** Outputs are never overwritten and an approved plan runs once, so most fixes end with a new run. */
    public static final String NEW_RUN = "After changing anything, create, approve and run a new plan into an empty output "
            + "folder; each approved plan runs once and outputs are never overwritten.";

    /** The steps for the failed (ERROR) and not checked (UNKNOWN) findings, grouped by problem, in report order. */
    public static List<Step> of(List<Finding> findings) {
        Map<String, Step> steps = new LinkedHashMap<>();
        for (Finding finding : findings) {
            if (finding.severity() != Finding.Severity.ERROR && finding.severity() != Finding.Severity.UNKNOWN) continue;
            String file = finding.sourceRelativePath().map(PortablePath::of).orElse(null);
            String action = action(finding.code(), file);
            if (action == null) continue;
            Step existing = steps.get(finding.code() + "|" + action);
            List<String> files = new ArrayList<>(existing == null ? List.of() : existing.files());
            if (file != null && !files.contains(file)) files.add(file);
            steps.put(finding.code() + "|" + action, new Step(finding.code(), files, action,
                    LEAVE_OUT.contains(finding.code()) && !files.isEmpty()));
        }
        return List.copyOf(steps.values());
    }

    /** The action for one finding code (and file, where the format matters); null for codes needing nothing. */
    static String action(String code, String file) {
        boolean fbx = file != null && file.toLowerCase(Locale.ROOT).endsWith(".fbx");
        return switch (code) {
            case "OUTPUT_FORMAT_INVALID" -> "The file does not decode, so the source is most likely damaged. Replace it in "
                    + "your asset folder and Rescan, or leave it out with an EXCLUSION in Review.";
            case "OUTPUT_FORMAT_UNCHECKED" -> fbx
                    ? "The built-in FBX reader could not read this file: it may be damaged, or use an FBX version the "
                    + "reader does not support. Re-export it from your 3D tool as binary FBX (version 7.4 or later), "
                    + "replace it and Rescan, or leave it out with an EXCLUSION."
                    : "This file could not be fully checked (for example it is very large or uses a variant the checker "
                    + "does not read). Open it in the target engine to confirm it, or leave it out with an EXCLUSION.";
            case "OUTPUT_MISSING", "OUTPUT_CHANGED", "OUTPUT_UNREADABLE", "OUTPUT_NOT_FILE", "OUTPUT_UNSAFE_PATH" ->
                    "The output was removed, edited or replaced after the run. Restore it and use Validate again, or run "
                    + "a new plan.";
            case "OUTPUT_REFERENCE_UNRESOLVED" -> "A copied model or material points to a file that is not in the output. "
                    + "Correct the reference in Review (Relationships), or include the missing file.";
            case "OUTPUT_REFERENCE_OUTSIDE_RUN", "OUTPUT_REFERENCES_UNCHECKED" -> "Some references could not be confirmed "
                    + "inside this run's output. Check them in Review (Relationships) before the next run.";
            case "OUTPUT_HASH_UNKNOWN" -> "The run did not record a checksum for this output, so it cannot be confirmed "
                    + "unchanged. Run a new plan to get a fully recorded output.";
            case "RUN_NOT_COMPLETED" -> "The run did not finish, so its outputs are incomplete. Check the run result, then "
                    + "run a new plan.";
            case "PLAN_ASSET_UNKNOWN" -> "The plan names a file that is not in its inventory. Create a new plan.";
            default -> null;
        };
    }

    /** Plain text for display: one bullet per problem, then the reminder about a new run. Empty if nothing to do. */
    public static String describe(List<Step> steps) {
        if (steps.isEmpty()) return "";
        StringBuilder text = new StringBuilder("What to do next:");
        for (Step step : steps) {
            text.append("\n• ");
            if (!step.files().isEmpty()) {
                text.append(String.join(", ", step.files().stream().limit(3).toList()))
                        .append(step.files().size() > 3 ? " and " + (step.files().size() - 3) + " more" : "").append(": ");
            }
            text.append(step.action());
        }
        return text.append("\n").append(NEW_RUN).toString();
    }
}
