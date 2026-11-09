package io.github.aiassetmigrate.analysis;

import io.github.aiassetmigrate.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationPreviewTest {
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static Asset asset(String path) {
        return new Asset(UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)), Path.of(path),
                Asset.Type.OTHER, "0".repeat(64), Map.of());
    }
    private static InventorySnapshot snapshot(List<Asset> assets, List<AssetReference> refs) {
        return new InventorySnapshot(1, 1, new Project(PROJECT, Path.of("/source"), 3, assets, NOW),
                List.of(), InventorySnapshot.ScanStatus.COMPLETE, NOW, List.of(), refs);
    }
    private static ReviewedChange decision(Asset a, ReviewedChange.Kind kind, ReviewedChange.Decision decision, Map<String, String> values) {
        return new ReviewedChange(UUID.nameUUIDFromBytes((a.id() + kind.toString()).getBytes(StandardCharsets.UTF_8)),
                a.id(), kind, decision, values, NOW);
    }
    private static OrganizationPreview.Result preview(InventorySnapshot s, ReviewedChange... changes) {
        return OrganizationPreview.preview(s, PROJECT, 3, List.of(changes));
    }
    private static boolean has(OrganizationPreview.Result result, String code) {
        return result.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    @Test void preservesDefaultsAppliesDecisionsAndIsImmutableAndDeterministic() {
        Asset a = asset("models/a.obj"), b = asset("b.png");
        var s = snapshot(List.of(b, a), List.of());
        var name = decision(a, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", "hero.obj"));
        var folder = decision(a, ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", "characters/hero"));
        var rejected = decision(b, ReviewedChange.Kind.NAME, ReviewedChange.Decision.REJECTED, Map.of("name", "ignored"));
        var result = preview(s, name, folder, rejected);
        assertFalse(result.blocked());
        assertEquals(result, preview(s, rejected, folder, name));
        assertEquals("characters/hero/hero.obj", result.entries().stream().filter(e -> e.assetId().equals(a.id())).findFirst().orElseThrow().destination().orElseThrow());
        assertEquals("b.png", result.entries().getFirst().destination().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.findings().clear());
        assertEquals(Path.of("models/a.obj"), a.relativePath());
    }

    @Test void rejectsUnsafeAndNonportableNamesAndFolders() {
        Asset a = asset("a.obj"); var s = snapshot(List.of(a), List.of());
        for (String name : List.of("../evil", "C:evil", "a\\b", "NUL.txt", "com1.foo", "a.", "a ", "x?y", "", "a\u0000b")) {
            assertTrue(has(preview(s, decision(a, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", name))), "INVALID_DESTINATION"), name);
        }
        for (String folder : List.of("/absolute", "../x", "a//b", "a/./b", "C:/x", "dir\\x", "CON/x")) {
            assertTrue(has(preview(s, decision(a, ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", folder))), "INVALID_DESTINATION"), folder);
        }
        assertTrue(has(preview(s, decision(a, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of())), "INVALID_DESTINATION"));
        assertFalse(preview(s, decision(a, ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", ""))).blocked());
    }

    @Test void catchesDuplicateCaseUnicodeAndFileDirectoryConflicts() {
        Asset a = asset("a"), b = asset("b"); var s = snapshot(List.of(a, b), List.of());
        for (String[] names : List.of(new String[]{"Same.png", "same.PNG"}, new String[]{"\u00e9.png", "e\u0301.png"})) {
            assertTrue(has(preview(s, decision(a, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", names[0])),
                    decision(b, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", names[1]))), "DESTINATION_CONFLICT"));
        }
        assertTrue(has(preview(s, decision(b, ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", "A"))), "DESTINATION_CONFLICT"));
        assertFalse(preview(s, decision(b, ReviewedChange.Kind.FOLDER, ReviewedChange.Decision.ACCEPTED, Map.of("folder", "A")),
                decision(a, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED, Map.of("reason", "skip"))).blocked());
    }

    @Test void marksEveryNestedAndDuplicateConflictDespiteInterveningNames() {
        Asset parent = asset("a"), middle = asset("a/b"), leaf = asset("a/b/c"),
                sibling = asset("a/d"), unrelated = asset("a-else"),
                unicode = asset("\u00e9.png"), unicodeDuplicate = asset("e\u0301.png");
        var s = snapshot(List.of(sibling, unrelated, leaf, middle, unicode, parent, unicodeDuplicate), List.of());
        var result = preview(s);
        var conflicts = result.findings().stream().filter(f -> f.code().equals("DESTINATION_CONFLICT")).toList();
        assertEquals(Set.of(parent.id(), middle.id(), leaf.id(), sibling.id(), unicode.id(), unicodeDuplicate.id()),
                conflicts.stream().map(f -> f.assetId().orElseThrow()).collect(Collectors.toSet()));
        assertEquals(6, conflicts.size());
        assertTrue(conflicts.stream().allMatch(f -> f.message().contains("asset ")));
        var reversed = new ArrayList<>(s.project().assets());
        Collections.reverse(reversed);
        assertEquals(result, preview(snapshot(reversed, List.of())));
    }

    @Test void handlesManyAssetsAndMarksAllMembersOfLargeCollisionGroup() {
        List<Asset> assets = new ArrayList<>();
        List<ReviewedChange> changes = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            Asset asset = asset("source/file" + i + ".png");
            assets.add(asset);
            if (i < 250) {
                changes.add(decision(asset, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED,
                        Map.of("name", i % 2 == 0 ? "same.png" : "SAME.PNG")));
            }
        }
        var result = OrganizationPreview.preview(snapshot(assets, List.of()), PROJECT, 3, changes);
        assertEquals(10000, result.entries().size());
        assertEquals(250, result.findings().stream().filter(f -> f.code().equals("DESTINATION_CONFLICT")).count());
        assertEquals(250, result.findings().stream().map(f -> f.assetId().orElseThrow()).distinct().count());
    }

    @Test void checksRecordedReferencesAndExcludedTargets() {
        Asset a = asset("models/a.obj"), b = asset("mat/a.mtl");
        AssetReference ref = new AssetReference(a.id(), AssetReference.Kind.MATERIAL_LIBRARY, "../mat/a.mtl",
                Optional.of(b.relativePath()), AssetReference.ResolutionStatus.RESOLVED);
        var s = snapshot(List.of(a, b), List.of(ref));
        assertFalse(preview(s).blocked());
        assertTrue(has(preview(s, decision(b, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", "b.mtl"))), "REFERENCE_REWRITE_REQUIRED"));
        assertTrue(has(preview(s, decision(b, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED, Map.of())), "UNAVAILABLE_REFERENCE_TARGET"));
        var unresolved = new AssetReference(a.id(), AssetReference.Kind.TEXTURE, "missing", Optional.empty(), AssetReference.ResolutionStatus.UNRESOLVED);
        assertTrue(has(preview(snapshot(List.of(a, b), List.of(unresolved))), "UNRESOLVED_REFERENCE"));
        assertFalse(preview(snapshot(List.of(a, b), List.of(unresolved)), decision(a, ReviewedChange.Kind.EXCLUSION, ReviewedChange.Decision.ACCEPTED, Map.of())).blocked());
    }

    @Test void rejectsStaleUnknownAndAmbiguousDecisionSetsAndBlocksPartialInventory() {
        Asset a = asset("a"), b = asset("b"); var s = snapshot(List.of(a), List.of());
        assertThrows(IllegalArgumentException.class, () -> OrganizationPreview.preview(s, PROJECT, 2, List.of()));
        assertThrows(IllegalArgumentException.class, () -> OrganizationPreview.preview(s, UUID.randomUUID(), 3, List.of()));
        assertThrows(IllegalArgumentException.class, () -> preview(s, decision(b, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of())));
        var first = decision(a, ReviewedChange.Kind.NAME, ReviewedChange.Decision.ACCEPTED, Map.of("name", "x"));
        var second = new ReviewedChange(UUID.randomUUID(), a.id(), first.kind(), first.decision(), first.values(), NOW);
        assertThrows(IllegalArgumentException.class, () -> preview(s, first, second));
        var partial = new InventorySnapshot(1, 1, s.project(), List.of(), InventorySnapshot.ScanStatus.PARTIAL, NOW, List.of(), List.of());
        assertTrue(has(preview(partial), "PARTIAL_INVENTORY"));
    }
}
