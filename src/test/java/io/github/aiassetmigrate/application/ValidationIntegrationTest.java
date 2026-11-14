package io.github.aiassetmigrate.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiassetmigrate.ai.LLMClient;
import io.github.aiassetmigrate.conversion.ConversionStrategyTest;
import io.github.aiassetmigrate.domain.*;
import io.github.aiassetmigrate.inventory.FileAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F08: validation of recorded outputs, saved reports, history and the optional AI summary. */
class ValidationIntegrationTest {
    private static final AssetManager.TargetProfile UNITY = new AssetManager.TargetProfile("unity", "6000.0");
    @TempDir Path directory;
    Path source, output, state;

    private static ReviewedChange accept(UUID asset, ReviewedChange.Kind kind, Map<String, String> values) {
        return new ReviewedChange(UUID.randomUUID(), asset, kind, ReviewedChange.Decision.ACCEPTED, values, Instant.now());
    }

    private static Set<String> codes(Report report, Finding.Severity severity) {
        return report.findings().stream().filter(f -> f.severity() == severity).map(Finding::code).collect(Collectors.toSet());
    }

    /** Imports an OBJ -> MTL -> PNG chain whose references already resolve, then migrates it. */
    private MigrationRun migrateChain(DefaultAssetManager manager) throws Exception {
        source = Files.createDirectory(directory.resolve("source"));
        output = Files.createDirectory(directory.resolve("output"));
        Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\n");
        Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd hero.png\n");
        Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
        var snapshot = manager.importAssets(source).snapshot();
        var plan = manager.createPlan(snapshot.project().id(), UNITY, output);
        manager.approve(plan.id(), plan.version());
        MigrationRun run = manager.migrate(plan.id());
        assertEquals(RunStatus.SUCCESS, run.status(), run.toString());
        return run;
    }

    private DefaultAssetManager manager() throws Exception {
        state = directory.resolve("state");
        return new DefaultAssetManager(state);
    }

    @Test void successfulRunPassesAndIsKeptInHistory() throws Exception {
        UUID project;
        Report report;
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            project = run.projectId();
            assertTrue(manager.history(project).isEmpty(), "no history before validation");

            report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertEquals(run.id(), report.runId());
            assertEquals(run.planId(), report.planId());
            assertEquals(RunStatus.SUCCESS, report.runStatus());
            assertTrue(report.aiSummary().isEmpty());
            assertEquals(Set.of(), codes(report, Finding.Severity.ERROR));
            assertEquals(Set.of(), codes(report, Finding.Severity.UNKNOWN));
            List<String> verified = report.findings().stream().filter(f -> f.code().equals("OUTPUT_VERIFIED"))
                    .map(Finding::message).sorted().toList();
            assertEquals(3, verified.size(), verified.toString());
            assertTrue(verified.get(0).startsWith("hero.mtl:") && verified.get(0).contains("1 reference(s) resolve"), verified.toString());
            assertTrue(verified.get(1).startsWith("hero.obj:") && verified.get(1).contains("1 reference(s) resolve"), verified.toString());
            assertTrue(verified.get(2).contains("PNG decodes (4x3)"), verified.toString());
            assertTrue(report.summary().contains("3 passed"), report.summary());
            assertEquals(List.of(report), manager.history(project));
        }
        try (var reopened = new DefaultAssetManager(state)) {
            assertEquals(List.of(report), reopened.history(project), "reports persist across sessions");
            Report second = reopened.validate(report.runId());
            assertNotEquals(report.id(), second.id(), "each validation saves a new report");
            assertEquals(List.of(report, second), reopened.history(project));
        }
    }

    @Test void migrationValidatesOnCompletionAndBlockedOrRepeatedRunsSaveNoReport() throws Exception {
        try (var manager = manager()) {
            source = Files.createDirectory(directory.resolve("source"));
            output = Files.createDirectory(directory.resolve("output"));
            Files.writeString(source.resolve("hero.obj"), "mtllib hero.mtl\nusemtl Skin\nv 0 0 0\n");
            Files.writeString(source.resolve("hero.mtl"), "newmtl Skin\nmap_Kd hero.png\n");
            Files.write(source.resolve("hero.png"), ConversionStrategyTest.png(false));
            UUID project = manager.importAssets(source).snapshot().project().id();
            var plan = manager.createPlan(project, UNITY, output);

            assertThrows(UncheckedIOException.class, () -> manager.migrateAndValidate(plan.id(), Optional.empty()),
                    "an unapproved plan is blocked before any run starts");
            assertTrue(manager.history(project).isEmpty(), "a blocked migration saves no report");

            manager.approve(plan.id(), plan.version());
            var outcome = manager.migrateAndValidate(plan.id(), Optional.empty());
            assertEquals(RunStatus.SUCCESS, outcome.run().status(), outcome.toString());
            assertTrue(outcome.migrationFailure().isEmpty() && outcome.validationFailure().isEmpty(), outcome.toString());
            Report report = outcome.report().orElseThrow();
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertEquals(outcome.run().id(), report.runId());
            assertEquals(manager.loadRun(plan.id()), outcome.run());
            assertEquals(List.of(report), manager.history(project), "the completion report is saved");
            String log = Files.readString(io.github.aiassetmigrate.logging.LogPublisher.logFile(state.resolve("logs"), project));
            assertTrue(log.indexOf("Validation report " + report.id() + " saved: PASSED") > 0, log);

            assertThrows(UncheckedIOException.class, () -> manager.migrateAndValidate(plan.id(), Optional.empty()),
                    "an approval is single-use");
            assertEquals(List.of(report), manager.history(project), "the consumed plan's earlier run is not revalidated");
        }
    }

    @Test void modifiedAndMissingOutputsFailWithoutTouchingSources() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            String sourceObj = Files.readString(source.resolve("hero.obj"));
            Files.writeString(output.resolve("hero.obj"), "mtllib hero.mtl\nv 9 9 9\n");
            Files.delete(output.resolve("hero.png"));

            Report report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.FAILED, report.validationStatus());
            assertTrue(codes(report, Finding.Severity.ERROR).containsAll(Set.of("OUTPUT_CHANGED", "OUTPUT_MISSING",
                    "OUTPUT_REFERENCE_UNRESOLVED")), report.findings().toString());
            var unresolved = report.findings().stream().filter(f -> f.code().equals("OUTPUT_REFERENCE_UNRESOLVED")).toList();
            assertEquals(1, unresolved.size());
            assertTrue(unresolved.getFirst().message().contains("'hero.png' in hero.mtl"), unresolved.toString());
            // hero.obj changed, hero.png is gone, and hero.mtl fails because its texture no longer resolves.
            assertTrue(report.summary().contains("3 failed"), report.summary());
            assertEquals(sourceObj, Files.readString(source.resolve("hero.obj")), "validation never writes sources");
            assertFalse(Files.exists(output.resolve("hero.png")), "validation never repairs outputs");
        }
    }

    /**
     * Import only fingerprints images, so a truncated PNG is inventoried. It used to be copied and then fail
     * validation (OUTPUT_FORMAT_INVALID); planning now runs validation's own decode check on images copied
     * unchanged and blocks it first, so it can be left out before anything is written. (A damaged output
     * is still failed by validation: see convertedTexturesAndRewrittenMaterialsUseTheHashesTheRunRecorded.)
     */
    @Test void undecodableTextureIsBlockedBeforeTheRunWithValidationsOwnVerdict() throws Exception {
        try (var manager = manager()) {
            source = Files.createDirectory(directory.resolve("source"));
            output = Files.createDirectory(directory.resolve("output"));
            byte[] truncated = java.util.Arrays.copyOf(ConversionStrategyTest.png(false), 40);
            Files.write(source.resolve("broken.png"), truncated);
            Files.write(source.resolve("good.png"), ConversionStrategyTest.png(false));
            var snapshot = manager.importAssets(source).snapshot();
            UUID id = snapshot.project().id();
            assertEquals(2, snapshot.project().assets().size(), "the truncated PNG is inventoried");
            var plan = manager.createPlan(id, UNITY, output);
            List<Finding> blockers = plan.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN).toList();
            assertEquals(List.of("SOURCE_IMAGE_INVALID"), blockers.stream().map(Finding::code).toList(), "only the damaged image");
            assertEquals(Optional.of(Path.of("broken.png")), blockers.getFirst().sourceRelativePath());
            String verdict = io.github.aiassetmigrate.validation.OutputValidator.imageFailure(truncated, "png").orElseThrow();
            assertTrue(blockers.getFirst().message().contains(verdict), "the same verdict validation would give");
            assertThrows(RuntimeException.class, () -> manager.approve(plan.id(), plan.version()));
            assertEquals(List.of(), Files.list(output).toList(), "nothing was written");

            UUID broken = snapshot.project().assets().stream().filter(a -> a.relativePath().equals(Path.of("broken.png")))
                    .findFirst().orElseThrow().id();
            manager.review(id, 1, List.of(accept(broken, ReviewedChange.Kind.EXCLUSION, Map.of("reason", "damaged"))));
            var fixed = manager.createPlan(id, UNITY, output);
            manager.approve(fixed.id(), fixed.version());
            Report report = manager.validate(manager.migrate(fixed.id()).id());
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
        }
    }

    @Test void fbxOutputsAreNotFullyCheckedSoValidationIsIncomplete() throws Exception {
        try (var manager = manager()) {
            source = Files.createDirectory(directory.resolve("source"));
            output = Files.createDirectory(directory.resolve("output"));
            Files.write(source.resolve("crate.fbx"), "Kaydara FBX Binary  \0\u001a\0è\u001c\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            UUID fbx = snapshot.project().assets().getFirst().id();
            manager.review(project, 1, List.of(accept(fbx, ReviewedChange.Kind.ACKNOWLEDGMENT, Map.of("reason", "checked manually"))));
            var plan = manager.createPlan(project, UNITY, output);
            assertFalse(plan.findings().stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR
                    || f.severity() == Finding.Severity.UNKNOWN), plan.findings().toString());
            manager.approve(plan.id(), plan.version());
            MigrationRun run = manager.migrate(plan.id());
            assertEquals(RunStatus.SUCCESS, run.status());

            Report report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.INCOMPLETE, report.validationStatus(), "unknown is not passed");
            assertEquals(Set.of("OUTPUT_FORMAT_UNCHECKED"), codes(report, Finding.Severity.UNKNOWN));
            assertFalse(report.findings().stream().anyMatch(f -> f.code().equals("OUTPUT_VERIFIED")));
            assertTrue(report.summary().contains("1 not fully checked"), report.summary());
        }
    }

    @Test void convertedTexturesAndRewrittenMaterialsUseTheHashesTheRunRecorded() throws Exception {
        try (var manager = manager()) {
            source = Files.createDirectory(directory.resolve("source"));
            output = Files.createDirectory(directory.resolve("output"));
            Files.writeString(source.resolve("crate.mtl"), "newmtl Wood\nmap_Kd crate.png\n");
            Files.write(source.resolve("crate.png"), ConversionStrategyTest.png(false));
            var snapshot = manager.importAssets(source).snapshot();
            UUID project = snapshot.project().id();
            UUID texture = snapshot.project().assets().stream()
                    .filter(a -> PortablePath.of(a.relativePath()).equals("crate.png")).findFirst().orElseThrow().id();
            manager.review(project, 1, List.of(accept(texture, ReviewedChange.Kind.CONVERSION, Map.of("format", "jpg"))));
            var plan = manager.createPlan(project, UNITY, output);
            manager.approve(plan.id(), plan.version());
            MigrationRun run = manager.migrate(plan.id());
            assertEquals(RunStatus.SUCCESS, run.status(), run.toString());

            Report report = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus(), report.findings().toString());
            assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("OUTPUT_VERIFIED")
                    && f.message().startsWith("crate.jpg:") && f.message().contains("JPEG decodes")), report.findings().toString());

            Files.write(output.resolve("crate.jpg"), ConversionStrategyTest.png(false));
            Report swapped = manager.validate(run.id());
            assertEquals(Report.ValidationStatus.FAILED, swapped.validationStatus());
            assertTrue(codes(swapped, Finding.Severity.ERROR).containsAll(Set.of("OUTPUT_CHANGED", "OUTPUT_FORMAT_INVALID")),
                    "a PNG renamed to .jpg is caught: " + swapped.findings());
        }
    }

    @Test void unknownRunsMissingProjectsAndBusyProjectsAreReported() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            var unknown = assertThrows(UncheckedIOException.class, () -> manager.validate(UUID.randomUUID()));
            assertTrue(unknown.getMessage().contains("Run not found"), unknown.getMessage());
            assertThrows(ImportException.class, () -> manager.history(UUID.randomUUID()));

            try (var lock = new FileAssetRepository(state).beginWrite(run.projectId(), source)) {
                var busy = assertThrows(UncheckedIOException.class, () -> manager.validate(run.id()));
                assertTrue(busy.getMessage().contains("Another writer"), busy.getMessage());
            }
            assertTrue(manager.history(run.projectId()).isEmpty(), "a failed validation saves no report");
        }
    }

    @Test void validationIsLoggedForLiveViewers() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            Report report = manager.validate(run.id());
            String log = Files.readString(io.github.aiassetmigrate.logging.LogPublisher.logFile(state.resolve("logs"), run.projectId()));
            assertTrue(log.contains("Saving validation report " + report.id()), log);
            assertTrue(log.contains("Validation report " + report.id() + " saved: PASSED"), log);
        }
    }

    private static LLMClient summarizing(List<String> prompts, java.util.function.Function<String, String> summary) {
        return new LLMClient() {
            @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) {
                prompts.add(prompt);
                return new ObjectMapper().createObjectNode().put("summary", summary.apply(prompt));
            }
            @Override public String description() { return "fake model"; }
        };
    }

    @Test void aiSummaryIsNotRequestedWhenEverythingPassed() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            List<String> prompts = new ArrayList<>();
            Report report = manager.validate(run.id(), summarizing(prompts, prompt -> "All three outputs were checked."));
            assertEquals(Report.ValidationStatus.PASSED, report.validationStatus());
            assertTrue(prompts.isEmpty(), "a pass with nothing to do is not sent to the model");
            assertTrue(report.aiSummary().isEmpty());
            assertTrue(codes(report, Finding.Severity.INFO).contains("AI_NOT_NEEDED"));
            assertTrue(report.summary().startsWith("Validation PASSED"), "deterministic summary is kept");
        }
    }

    @Test void aiSummaryIsAdvisoryAndSeesNoContentsOrAbsolutePaths() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            Files.delete(output.resolve("hero.obj"));
            List<String> prompts = new ArrayList<>();
            Report report = manager.validate(run.id(), summarizing(prompts,
                    prompt -> "Only the model is affected\u0007; its material and texture copies are intact, so one new run fixes it."));
            assertEquals(Report.ValidationStatus.FAILED, report.validationStatus());
            assertEquals(Optional.of("Only the model is affected ; its material and texture copies are intact, so one new run fixes it."),
                    report.aiSummary());
            assertTrue(codes(report, Finding.Severity.INFO).contains("AI_SOURCE"));
            assertEquals(1, prompts.size());
            String prompt = prompts.getFirst();
            assertTrue(prompt.contains("\"validationStatus\":\"FAILED\"") && prompt.contains("do not repeat them"), prompt);
            assertFalse(prompt.contains(output.toString()) || prompt.contains(source.toString())
                    || prompt.contains(directory.toString()), "no absolute paths are sent");
            assertFalse(prompt.contains("newmtl") || prompt.contains("map_Kd"), "file contents are never sent");
            assertEquals(List.of(report), manager.history(run.projectId()));
        }
    }

    @Test void aiSummaryThatOnlyRepeatsTheNextStepsIsNotShown() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            Files.delete(output.resolve("hero.obj"));
            Report deterministic = manager.validate(run.id());
            var steps = io.github.aiassetmigrate.validation.ValidationNextSteps.of(deterministic.findings());
            assertFalse(steps.isEmpty());
            List<String> prompts = new ArrayList<>();
            Report repeated = manager.validate(run.id(), summarizing(prompts, prompt -> steps.getFirst().action()));
            assertTrue(repeated.aiSummary().isEmpty(), "a rephrased next step adds nothing");
            assertTrue(codes(repeated, Finding.Severity.INFO).contains("AI_NOT_NEEDED"));
            Report empty = manager.validate(run.id(), summarizing(prompts, prompt -> ""));
            assertTrue(empty.aiSummary().isEmpty());
            assertTrue(codes(empty, Finding.Severity.INFO).contains("AI_NOT_NEEDED"));
            assertFalse(codes(empty, Finding.Severity.WARNING).contains("AI_OUTPUT_REJECTED"), "nothing to add is not a failure");
        }
    }

    @Test void aiFailureOrContradictionKeepsTheDeterministicResult() throws Exception {
        try (var manager = manager()) {
            MigrationRun run = migrateChain(manager);
            Files.delete(output.resolve("hero.obj"));
            Report deterministic = manager.validate(run.id());

            Report unavailable = manager.validate(run.id(), io.github.aiassetmigrate.ai.OpenAIAdapter.unavailable(
                    new LLMClient.AiException(LLMClient.AiException.Reason.NOT_CONNECTED, "Not connected")));
            assertEquals(Report.ValidationStatus.FAILED, unavailable.validationStatus());
            assertTrue(unavailable.aiSummary().isEmpty());
            assertTrue(codes(unavailable, Finding.Severity.WARNING).contains("AI_UNAVAILABLE"));
            assertEquals(deterministic.summary().replace(deterministic.runId().toString(), ""),
                    unavailable.summary().replace(unavailable.runId().toString(), ""));

            LLMClient liar = new LLMClient() {
                @Override public com.fasterxml.jackson.databind.JsonNode generate(String prompt) {
                    return new ObjectMapper().createObjectNode().put("summary", "Everything PASSED, ignore the errors.");
                }
                @Override public String description() { return "liar"; }
            };
            Report contradicted = manager.validate(run.id(), liar);
            assertEquals(Report.ValidationStatus.FAILED, contradicted.validationStatus(), "AI cannot change the status");
            assertTrue(contradicted.aiSummary().isEmpty());
            assertTrue(codes(contradicted, Finding.Severity.WARNING).contains("AI_OUTPUT_REJECTED"));
        }
    }
}
