package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.AssetReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetScannerReferenceTest {
    @TempDir Path root;

    @Test
    void resolvesMissingSkippedCaseAmbiguousAndUnsafeObjLibrariesInSecondPass() throws Exception {
        Files.writeString(root.resolve("found.mtl"), "newmtl found\n");
        Files.writeString(root.resolve("Case.MTL"), "newmtl case\n");
        Files.writeString(root.resolve("skipped.mtl"), "newmtl\n");
        Files.writeString(root.resolve("model.obj"), "\uFEFF# asset libraries\n"
                + "mtllib found.mtl\nmtllib missing.mtl\nmtllib skipped.mtl\nmtllib case.mtl\nmtllib ../escape.mtl\n"
                + "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");

        var result = new AssetScanner().scan(root);
        Map<String, AssetReference> references = result.references().stream()
                .collect(Collectors.toMap(AssetReference::originalText, reference -> reference));

        assertEquals(5, references.size());
        assertStatus(references, "found.mtl", AssetReference.ResolutionStatus.RESOLVED, Path.of("found.mtl"));
        assertStatus(references, "missing.mtl", AssetReference.ResolutionStatus.UNRESOLVED, Path.of("missing.mtl"));
        assertStatus(references, "skipped.mtl", AssetReference.ResolutionStatus.SKIPPED, Path.of("skipped.mtl"));
        assertStatus(references, "case.mtl", AssetReference.ResolutionStatus.CASE_AMBIGUOUS, Path.of("case.mtl"));
        assertStatus(references, "../escape.mtl", AssetReference.ResolutionStatus.UNSAFE, null);
        for (AssetReference.ResolutionStatus status : new AssetReference.ResolutionStatus[] {
                AssetReference.ResolutionStatus.UNRESOLVED, AssetReference.ResolutionStatus.SKIPPED,
                AssetReference.ResolutionStatus.CASE_AMBIGUOUS, AssetReference.ResolutionStatus.UNSAFE }) {
            assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("REFERENCE_" + status)));
        }
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("CORRUPT_FORMAT")
                && finding.sourceRelativePath().orElseThrow().equals(Path.of("skipped.mtl"))));
    }

    @Test
    void parsesDocumentedMtlOptionsAndPreservesUnambiguousFilenamesWithSpaces() throws Exception {
        String[] names = {"painted wall.png", "shiny metal.png", "normal map.png", "bump map.png",
                "normal channel.png", "alpha map.png"};
        for (String name : names) Files.write(root.resolve(name), new byte[] {1, 2, 3});
        Files.writeString(root.resolve("surface.mtl"), "newmtl surface\n"
                + "map_Kd -s 1 1 1 painted wall.png\n"
                + "map_Ks -clamp on shiny metal.png\n"
                + "map_Bump -bm 0.5 normal map.png\n"
                + "bump -o 0 0 0 bump map.png\n"
                + "norm -imfchan r normal channel.png\n"
                + "map_d -mm 0.2 1 alpha map.png\n");

        var result = new AssetScanner().scan(root);

        assertEquals(6, result.references().size());
        assertEquals(java.util.List.of(names).stream().sorted().toList(),
                result.references().stream().map(AssetReference::originalText).toList());
        assertTrue(result.references().stream().allMatch(reference -> reference.kind() == AssetReference.Kind.TEXTURE
                && reference.resolutionStatus() == AssetReference.ResolutionStatus.RESOLVED));
        assertFalse(result.findings().stream().anyMatch(finding -> finding.code().equals("REFERENCE_SYNTAX_LIMITATION")));
    }

    @Test
    void reportsAmbiguousMtlSyntaxWithoutGuessingAReferenceFilename() throws Exception {
        Files.writeString(root.resolve("surface.mtl"), "newmtl surface\nmap_Kd -unsupported texture.png\n");

        var result = new AssetScanner().scan(root);

        assertEquals(1, result.references().size());
        assertEquals(AssetReference.ResolutionStatus.UNRESOLVED, result.references().getFirst().resolutionStatus());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("REFERENCE_SYNTAX_LIMITATION")));
    }

    @Test
    void marksAbsentReferenceMissingWhenAllScannedPathsAreCovered() throws Exception {
        Files.writeString(root.resolve("model.obj"), "mtllib missing.mtl\n");

        var result = new AssetScanner().scan(root);

        assertEquals(AssetReference.ResolutionStatus.MISSING, result.references().getFirst().resolutionStatus());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("REFERENCE_MISSING")));
    }

    @Test
    void malformedDefiniteObjSyntaxIsSkippedAndReferenceBudgetIsBounded() throws Exception {
        Files.writeString(root.resolve("broken.obj"), "v 0 not-a-number 0\n");
        Files.writeString(root.resolve("one.mtl"), "newmtl one\n");
        Files.writeString(root.resolve("two.mtl"), "newmtl two\n");
        Files.writeString(root.resolve("limited.obj"), "mtllib one.mtl\nmtllib two.mtl\n");

        var result = new AssetScanner(new ScanLimits(64, 100, 1024, 1024, 1)).scan(root);

        assertFalse(result.assets().stream().anyMatch(asset -> asset.relativePath().equals(Path.of("broken.obj"))));
        assertEquals(1, result.references().size());
        assertEquals("one.mtl", result.references().getFirst().originalText());
        assertTrue(result.findings().stream().anyMatch(finding -> finding.code().equals("REFERENCE_LIMIT")));
    }

    private static void assertStatus(Map<String, AssetReference> references, String text,
                                     AssetReference.ResolutionStatus status, Path target) {
        AssetReference reference = references.get(text);
        assertEquals(status, reference.resolutionStatus());
        assertEquals(java.util.Optional.ofNullable(target), reference.normalizedTargetPath());
    }
}
