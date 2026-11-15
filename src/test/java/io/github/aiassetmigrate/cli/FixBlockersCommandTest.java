package io.github.aiassetmigrate.cli;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.BlockerFixes;
import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The CLI Fix blockers flow matches the GUI dialog: same fixes, same defaults, same AI step, same new plan. */
class FixBlockersCommandTest {
    private final UUID project = UUID.randomUUID(), planId = UUID.randomUUID(), newPlanId = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    private final Path output = io.github.aiassetmigrate.TestPaths.absolute("out");

    private final List<String> calls = new ArrayList<>();
    private final List<List<BlockerFixes.Fix>> applied = new ArrayList<>();
    private final List<Optional<?>> proposedWith = new ArrayList<>();
    private final StringWriter out = new StringWriter(), err = new StringWriter();

    private BlockerFixes.Fix fix(String description, boolean recommended, String code, BlockerFixes.Source source) {
        return new BlockerFixes.Fix(code, Optional.of(asset), description,
                List.of(new ReviewedChange(UUID.randomUUID(), asset, ReviewedChange.Kind.CATEGORY, ReviewedChange.Decision.ACCEPTED,
                        Map.of("category", description), Instant.now())), Set.of(), source, recommended);
    }

    private final BlockerFixes.Fix first = fix("first", true, "SOURCE_IMAGE_INVALID", BlockerFixes.Source.RULES);
    private final BlockerFixes.Fix second = fix("second", true, "UNRESOLVED_REFERENCE", BlockerFixes.Source.RULES);
    private final BlockerFixes.Fix lastResort = fix("leave it out", false, "UNRESOLVED_REFERENCE", BlockerFixes.Source.RULES);
    private final BlockerFixes.Fix fromAi = fix("ai choice", true, "UNRESOLVED_REFERENCE", BlockerFixes.Source.AI);

    private BlockerFixes.Proposal rules() { return new BlockerFixes.Proposal(project, 3, List.of(first, second, lastResort), List.of("needs you")); }
    private BlockerFixes.Proposal withAi() { return new BlockerFixes.Proposal(project, 3, List.of(first, second, fromAi), List.of()); }

    private MigrationPlan plan(UUID id, boolean blocked) {
        return new MigrationPlan(id, project, 1, 3, "unity", "6000.0", output, List.of(), List.of(),
                blocked ? List.of(new Finding(UUID.randomUUID(), Optional.of(asset), Optional.empty(), Finding.Severity.ERROR, "X", "m"))
                        : List.of(), PlanStatus.DRAFT, time);
    }

    private AssetManager manager(boolean blocked) {
        return (AssetManager) Proxy.newProxyInstance(AssetManager.class.getClassLoader(),
                new Class<?>[]{AssetManager.class, AutoCloseable.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "close" -> null;
                        case "loadPlan" -> { assertEquals(planId, args[0]); yield plan(planId, blocked); }
                        case "proposeBlockerFixes" -> {
                            assertEquals(project, args[0]);
                            Optional<?> assistant = (Optional<?>) args[2];
                            proposedWith.add(assistant);
                            yield assistant.isPresent() ? withAi() : rules();
                        }
                        case "applyBlockerFixes" -> {
                            assertEquals(project, args[0]);
                            assertEquals(3L, args[1]);
                            @SuppressWarnings("unchecked") List<BlockerFixes.Fix> chosen = (List<BlockerFixes.Fix>) args[2];
                            applied.add(chosen);
                            yield new BlockerFixes.Applied(chosen.size(), chosen.size(), 0, false, 3);
                        }
                        case "createPlan" -> {
                            assertEquals(project, args[0]);
                            assertEquals(new AssetManager.TargetProfile("unity", "6000.0"), args[1], "same target as the blocked plan");
                            assertEquals(output, args[2], "same output folder as the blocked plan");
                            yield plan(newPlanId, false);
                        }
                        case "loadInventory" -> new InventorySnapshot(1, 1, new Project(project,
                                io.github.aiassetmigrate.TestPaths.absolute("source"), 3, List.of(), time),
                                List.of(), InventorySnapshot.ScanStatus.COMPLETE, time, List.of(), List.of());
                        default -> fail("unexpected call " + method.getName());
                    };
                });
    }

    private int run(boolean blocked, List<String> answers, String... args) {
        Deque<String> queue = new ArrayDeque<>(answers);
        var command = new FixBlockersCommand((state, logs) -> manager(blocked),
                () -> io.github.aiassetmigrate.ai.OpenAIAdapter.unavailable(new io.github.aiassetmigrate.ai.LLMClient.AiException(
                        io.github.aiassetmigrate.ai.LLMClient.AiException.Reason.NOT_CONNECTED, "offline")),
                question -> queue.poll());
        List<String> all = new ArrayList<>(List.of("--plan", planId.toString(), "--state-dir", "s"));
        all.addAll(List.of(args));
        return new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(all.toArray(String[]::new));
    }

    @Test void promptStartsWithTheSafeFixesSelectedAndAppliesTheUsersChoiceThenCreatesANewPlan() {
        // Tick the last resort (3), untick the first (1), then Enter applies.
        assertEquals(0, run(true, List.of("3", "1", "")));
        assertEquals(List.of(List.of(second, lastResort)), applied);
        String text = out.toString();
        assertTrue(text.contains("[x] 1. first") && text.contains("[x] 2. second") && text.contains("[ ] 3. leave it out"), text);
        assertTrue(text.contains("Fixes (selected)") && text.contains("Last resort: leave files out (not selected)"), text);
        assertTrue(text.contains("Needs you") && text.contains("needs you"), text);
        assertTrue(text.contains(BlockerFixes.note(rules())), text);
        assertTrue(text.contains("Applied 2 fix(es)."), text);
        assertTrue(text.contains("Plan ID: " + newPlanId), text);
        assertTrue(text.contains("No blockers are left") && text.contains("Nothing was approved or migrated"), text);
        assertFalse(calls.contains("approve") || calls.contains("migrate"));
    }

    @Test void askAiForTheRestProposesAgainWithTheAssistantAndShowsItsChoicesForConfirmation() {
        assertEquals(0, run(true, List.of("a", "")));
        assertEquals(2, proposedWith.size());
        assertTrue(proposedWith.get(0).isEmpty() && proposedWith.get(1).isPresent(), "rules first, then with the AI");
        assertTrue(out.toString().contains("Ask AI for the rest: " + BlockerFixes.AI_DISCLOSURE));
        assertTrue(out.toString().contains("[x] 3. [AI] ai choice"));
        assertEquals(List.of(List.of(first, second, fromAi)), applied);
    }

    @Test void cancelAndAnEmptyChoiceApplyNothing() {
        assertEquals(0, run(true, List.of("none", "", "q")));
        assertTrue(applied.isEmpty());
        assertTrue(out.toString().contains("Choose at least one fix, or q to cancel."));
        assertTrue(out.toString().contains("No fixes were applied."));
        assertFalse(calls.contains("createPlan"));
    }

    @Test void withoutATerminalItListsTheFixesAndTheProposalCodeAndChangesNothing() {
        assertEquals(0, run(true, List.of()));
        assertTrue(applied.isEmpty());
        String code = FixBlockersCommand.code(plan(planId, true), rules());
        assertTrue(out.toString().contains("Proposal code: " + code));
        assertTrue(out.toString().contains("Nothing was changed. To apply the selected fixes, run this again with --proposal " + code + " --yes"));
    }

    @Test void yesWithTheListedCodeAppliesExactlyTheSelectedFixes() {
        String code = FixBlockersCommand.code(plan(planId, true), rules());
        assertEquals(0, run(true, List.of(), "--proposal", code, "--yes"));
        assertEquals(List.of(List.of(first, second)), applied);
        assertTrue(out.toString().contains("Applying:"));
    }

    @Test void chooseAppliesFixesByNumber() {
        String code = FixBlockersCommand.code(plan(planId, true), rules());
        assertEquals(0, run(true, List.of(), "--proposal", code, "--choose", "3"));
        assertEquals(List.of(List.of(lastResort)), applied);
    }

    @Test void aChangedProposalOrABadChoiceIsRefusedWithNothingChanged() {
        assertEquals(1, run(true, List.of(), "--proposal", "00000000", "--yes"));
        assertTrue(err.toString().contains("Nothing was changed"), err.toString());
        String code = FixBlockersCommand.code(plan(planId, true), rules());
        assertEquals(2, run(true, List.of(), "--proposal", code, "--choose", "9"));
        assertEquals(2, run(true, List.of(), "--yes"), "--yes needs the proposal code");
        assertEquals(2, run(true, List.of(), "--proposal", code, "--yes", "--choose", "1"));
        assertTrue(applied.isEmpty());
    }

    @Test void theCodeIgnoresNewDecisionIdsAndTimesButNotWhatAFixChanges() {
        MigrationPlan blocked = plan(planId, true);
        BlockerFixes.Proposal again = new BlockerFixes.Proposal(project, 3, List.of(
                fix("first", true, "SOURCE_IMAGE_INVALID", BlockerFixes.Source.RULES),
                fix("second", true, "UNRESOLVED_REFERENCE", BlockerFixes.Source.RULES),
                fix("leave it out", false, "UNRESOLVED_REFERENCE", BlockerFixes.Source.RULES)), List.of("needs you"));
        assertEquals(FixBlockersCommand.code(blocked, rules()), FixBlockersCommand.code(blocked, again));
        assertNotEquals(FixBlockersCommand.code(blocked, rules()), FixBlockersCommand.code(blocked, withAi()));
    }

    @Test void aPlanWithoutBlockersNeedsNoFixes() {
        assertEquals(0, run(false, List.of()));
        assertTrue(out.toString().contains("has no blockers"));
        assertFalse(calls.contains("proposeBlockerFixes"));
    }
}
