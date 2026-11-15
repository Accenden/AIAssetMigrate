package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.ai.OpenAIAdapter;
import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.BlockerFixes;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.MigrationPlan;
import io.github.aiassetmigrate.domain.ReviewedChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The CLI form of the GUI's Fix blockers dialog: proposes one fix per blocker of a draft plan, with the safe fixes
 * selected and leaving files out offered only as a last resort; the AI can be asked for the broken references the rules
 * cannot settle. The chosen fixes are saved as review decisions (or files left out, with a rescan), exactly as if made
 * by hand, and a new plan is created for the same target and output folder. Nothing is approved or copied.
 *
 * <p>In a terminal the command asks which fixes to apply, as the dialog does. Otherwise it lists the fixes with a
 * proposal code; {@code --yes} or {@code --choose} with that code applies exactly the fixes that were listed.
 */
@Command(name = "fix-blockers", mixinStandardHelpOptions = true,
        description = {"List a fix for each blocker of a draft plan, apply the ones you choose, and create a new plan.",
                "Fixes are saved as review decisions or files left out; nothing is approved or copied."})
public final class FixBlockersCommand extends PlanCommands.Base {
    /** Reads one answer from the user, or returns null when there is no one to ask (not a terminal, or end of input). */
    interface Prompt { String ask(String question) throws IOException; }

    @Option(names = "--plan", required = true, paramLabel = "UUID", description = "The blocked draft plan.") UUID plan;
    @Option(names = "--ai", description = {"Let the connected ChatGPT account (see 'auth') choose targets for broken references",
            "the rules cannot settle (the dialog's 'Ask AI for the rest'). " + BlockerFixes.AI_DISCLOSURE}) boolean ai;
    @Option(names = "--plan-ai", description = "Also ask the AI for suggestions on the new plan, as 'plan --ai' does.") boolean planAi;
    @Option(names = "--yes", description = "Apply the selected (recommended) fixes without asking. Needs --proposal.") boolean yes;
    @Option(names = "--choose", split = ",", paramLabel = "N", description = {"Apply these fixes by number instead, for example",
            "--choose 1,3. Needs --proposal."}) List<Integer> choose;
    @Option(names = "--proposal", paramLabel = "CODE", description = {"The proposal code the list printed. The fixes are applied",
            "only if they are still exactly the ones listed."}) String proposalCode;

    private final AnalyzeCommand.AssistantFactory assistantFactory;
    private final Prompt prompt;

    public FixBlockersCommand() { this(DefaultAssetManager::new, AnalyzeCommand::connectedAssistant, FixBlockersCommand::terminal); }
    FixBlockersCommand(ImportCommand.ManagerFactory factory, AnalyzeCommand.AssistantFactory assistantFactory, Prompt prompt) {
        super(factory);
        this.assistantFactory = assistantFactory;
        this.prompt = prompt;
    }

    @Override public Integer call() {
        PrintWriter err = spec.commandLine().getErr();
        if (yes && choose != null) {
            err.println("Use --yes (the selected fixes) or --choose (fixes by number), not both.");
            return 2;
        }
        if ((yes || choose != null) && (proposalCode == null || proposalCode.isBlank())) {
            err.println("--yes and --choose need --proposal CODE: run fix-blockers without them first, check the list, "
                    + "and pass the proposal code it prints.");
            return 2;
        }
        if (choose != null && (choose.isEmpty() || choose.stream().anyMatch(n -> n == null || n < 1))) {
            err.println("--choose takes fix numbers from the list, starting at 1.");
            return 2;
        }
        return super.call();
    }

    @Override int execute(AssetManager manager, PrintWriter out) throws Exception {
        MigrationPlan draft = manager.loadPlan(plan);
        if (blockers(draft) == 0) {
            out.printf("Plan %s version %d has no blockers; nothing to fix. Approve it with 'approve --plan %s --version %d'.%n",
                    draft.id(), draft.version(), draft.id(), draft.version());
            return 0;
        }
        boolean aiUsed = ai;
        BlockerFixes.Proposal proposal = propose(manager, draft, aiUsed);
        boolean scripted = yes || choose != null;
        if (scripted) return applyScripted(manager, out, draft, proposal);

        List<Boolean> selected = defaults(proposal);
        while (true) {
            print(out, draft, proposal, selected, BlockerFixes.offersAi(proposal) && !aiUsed);
            out.flush();
            if (proposal.fixes().isEmpty()) {
                out.println("Nothing was changed. Fix these yourself in Review, then create a new plan.");
                return 0;
            }
            String answer = prompt.ask(question(selected, BlockerFixes.offersAi(proposal) && !aiUsed));
            if (answer == null) return listOnly(out, draft, proposal, aiUsed);
            answer = answer.strip().toLowerCase(java.util.Locale.ROOT);
            if (answer.equals("q") || answer.equals("cancel")) {
                out.println("No fixes were applied.");
                return 0;
            }
            if (answer.equals("a") && BlockerFixes.offersAi(proposal) && !aiUsed) {
                aiUsed = true;
                out.println("Asking the AI about the blockers the rules could not fix...");
                proposal = propose(manager, draft, true);
                selected = defaults(proposal);
                continue;
            }
            if (answer.isEmpty()) {
                List<BlockerFixes.Fix> chosen = chosen(proposal, selected);
                if (chosen.isEmpty()) {
                    out.println("Choose at least one fix, or q to cancel.");
                    continue;
                }
                return apply(manager, out, draft, proposal, chosen);
            }
            if (answer.equals("all") || answer.equals("none")) {
                boolean value = answer.equals("all");
                selected.replaceAll(ignored -> value);
                continue;
            }
            try {
                for (String part : answer.split("[,\\s]+")) {
                    int number = Integer.parseInt(part);
                    if (number < 1 || number > selected.size()) throw new NumberFormatException();
                    selected.set(number - 1, !selected.get(number - 1));
                }
            } catch (NumberFormatException e) {
                out.println("Type fix numbers between 1 and " + selected.size() + ", all, none, Enter to apply, or q to cancel.");
            }
        }
    }

    private int applyScripted(AssetManager manager, PrintWriter out, MigrationPlan draft, BlockerFixes.Proposal proposal) throws Exception {
        String current = code(draft, proposal);
        if (!current.equalsIgnoreCase(proposalCode.strip())) {
            print(out, draft, proposal, defaults(proposal), BlockerFixes.offersAi(proposal) && !ai);
            out.flush();
            spec.commandLine().getErr().println("The fixes are no longer the ones listed with proposal " + ImportCommand.safe(proposalCode)
                    + " (the code is mistyped, or the project changed" + (ai ? ", or the AI chose differently" : "")
                    + "). Nothing was changed. "
                    + "Check the list above and run again with --proposal " + current + ".");
            return 1;
        }
        List<BlockerFixes.Fix> chosen;
        if (yes) {
            chosen = proposal.fixes().stream().filter(BlockerFixes.Fix::recommended).toList();
            if (chosen.isEmpty()) {
                spec.commandLine().getErr().println("No fix is selected by default (only last-resort fixes that leave files out). "
                        + "Choose them by number with --choose. Nothing was changed.");
                return 1;
            }
        } else {
            Set<Integer> numbers = new LinkedHashSet<>(choose);
            chosen = new ArrayList<>();
            for (int number : numbers) {
                if (number > proposal.fixes().size()) {
                    spec.commandLine().getErr().println("There is no fix " + number + "; the list has " + proposal.fixes().size()
                            + ". Nothing was changed.");
                    return 2;
                }
                chosen.add(proposal.fixes().get(number - 1));
            }
        }
        out.println("Applying:");
        for (BlockerFixes.Fix fix : chosen) out.println("  " + label(fix));
        return apply(manager, out, draft, proposal, chosen);
    }

    private int apply(AssetManager manager, PrintWriter out, MigrationPlan draft, BlockerFixes.Proposal proposal,
                      List<BlockerFixes.Fix> chosen) throws Exception {
        BlockerFixes.Applied applied;
        try {
            applied = manager.applyBlockerFixes(draft.projectId(), proposal.inventoryRevision(), chosen);
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage() + ". Some fixes may have been saved; check 'reviews' before trying again.", e);
        }
        out.println(BlockerFixes.summary(applied) + " Creating a new plan for " + draft.targetProfile() + " "
                + draft.targetProfileVersion() + " with the same output folder.");
        var target = new AssetManager.TargetProfile(draft.targetProfile(), draft.targetProfileVersion());
        MigrationPlan created;
        try {
            created = planAi ? manager.createPlan(draft.projectId(), target, draft.outputRoot(), assistant())
                    : manager.createPlan(draft.projectId(), target, draft.outputRoot());
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage() + ". The fixes were saved; create the plan again with 'plan'.", e);
        }
        PlanCommands.printPlan(manager, out, created, true);
        long left = blockers(created);
        out.println(left == 0
                ? "No blockers are left. Check the files it will write, then approve it with 'approve --plan " + created.id()
                        + " --version " + created.version() + "'. Nothing was approved or migrated."
                : left + " issue(s) still block this plan. Run fix-blockers --plan " + created.id()
                        + " again, or fix them yourself and create a new plan. Nothing was approved or migrated.");
        return 0;
    }

    private int listOnly(PrintWriter out, MigrationPlan draft, BlockerFixes.Proposal proposal, boolean aiUsed) {
        out.println("Nothing was changed. To apply the selected fixes, run this again with --proposal " + code(draft, proposal)
                + " --yes" + (aiUsed ? " --ai" : "") + "; to pick fixes, use --choose with their numbers instead of --yes.");
        return 0;
    }

    private BlockerFixes.Proposal propose(AssetManager manager, MigrationPlan draft, boolean useAi) {
        return manager.proposeBlockerFixes(draft.projectId(), draft, useAi ? Optional.of(assistant()) : Optional.empty());
    }

    private LLMClient assistant() {
        try { return assistantFactory.create(); }
        catch (LLMClient.AiException e) { return OpenAIAdapter.unavailable(e); }
    }

    private void print(PrintWriter out, MigrationPlan draft, BlockerFixes.Proposal proposal, List<Boolean> selected, boolean offerAi) {
        out.printf("Fix blockers for plan %s version %d (%d blocker(s))%n", draft.id(), draft.version(), blockers(draft));
        out.println(BlockerFixes.header(proposal));
        List<BlockerFixes.Fix> fixes = proposal.fixes();
        boolean recommendedHeading = false, lastResortHeading = false;
        for (int i = 0; i < fixes.size(); i++) {
            BlockerFixes.Fix fix = fixes.get(i);
            if (fix.recommended() && !recommendedHeading) {
                out.println();
                out.println("Fixes (selected)");
                recommendedHeading = true;
            } else if (!fix.recommended() && !lastResortHeading) {
                out.println();
                out.println("Last resort: leave files out (not selected)");
                out.println("  No safe fix was found for these. Leaving a file out means it is not migrated.");
                lastResortHeading = true;
            }
            out.printf("  [%s] %d. %s%n", selected.get(i) ? "x" : " ", i + 1, label(fix));
        }
        if (!proposal.unfixable().isEmpty()) {
            out.println();
            out.println("Needs you");
            proposal.unfixable().forEach(line -> out.println("  - " + ImportCommand.safe(line)));
        }
        out.println();
        out.println(BlockerFixes.note(proposal));
        if (offerAi) out.println("Ask AI for the rest: " + BlockerFixes.AI_DISCLOSURE);
        out.println("Proposal code: " + code(draft, proposal));
    }

    private static String question(List<Boolean> selected, boolean offerAi) {
        long count = selected.stream().filter(Boolean::booleanValue).count();
        return "Enter = apply " + count + " fix(es); numbers (e.g. 2 or 1,3) = tick or untick; all / none"
                + (offerAi ? "; a = ask AI for the rest" : "") + "; q = cancel: ";
    }

    private static String label(BlockerFixes.Fix fix) {
        return (fix.source() == BlockerFixes.Source.AI ? "[AI] " : "") + ImportCommand.safe(fix.description());
    }

    private static List<Boolean> defaults(BlockerFixes.Proposal proposal) {
        return new ArrayList<>(proposal.fixes().stream().map(BlockerFixes.Fix::recommended).toList());
    }

    private static List<BlockerFixes.Fix> chosen(BlockerFixes.Proposal proposal, List<Boolean> selected) {
        List<BlockerFixes.Fix> chosen = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) if (selected.get(i)) chosen.add(proposal.fixes().get(i));
        return chosen;
    }

    private static long blockers(MigrationPlan plan) {
        return plan.findings().stream()
                .filter(f -> f.severity() == Finding.Severity.ERROR || f.severity() == Finding.Severity.UNKNOWN).count();
    }

    /**
     * A short code for exactly these fixes of this plan, so a later --yes or --choose applies what the user saw. Decision
     * IDs and times are left out: they are new on every proposal, while what a fix changes is not.
     */
    static String code(MigrationPlan draft, BlockerFixes.Proposal proposal) {
        StringBuilder text = new StringBuilder().append(proposal.projectId()).append('|').append(proposal.inventoryRevision());
        if (draft != null) text.append('|').append(draft.id()).append('|').append(draft.version());
        for (BlockerFixes.Fix fix : proposal.fixes()) {
            text.append("\n").append(fix.code()).append('|').append(fix.assetId().map(UUID::toString).orElse(""))
                    .append('|').append(fix.description()).append('|').append(fix.source()).append('|').append(fix.recommended());
            for (ReviewedChange change : fix.changes()) {
                text.append("\n  ").append(change.assetId()).append('|').append(change.kind()).append('|').append(change.decision())
                        .append('|').append(new TreeMap<>(change.values()));
            }
            fix.leaveOut().stream().map(path -> path.toString().replace('\\', '/')).sorted()
                    .forEach(path -> text.append("\n  leave out ").append(path));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Asks on the console only when one is attached to a real terminal, so piped and scripted runs never wait. */
    private static String terminal(String question) {
        Console console = System.console();
        if (console == null || !isTerminal(console)) return null;
        return console.readLine("%s", question);
    }

    private static boolean isTerminal(Console console) {
        try {
            // Java 22+ returns a console even when redirected and reports whether it is a terminal; Java 21 returns none.
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException olderJava) {
            return true;
        }
    }
}
