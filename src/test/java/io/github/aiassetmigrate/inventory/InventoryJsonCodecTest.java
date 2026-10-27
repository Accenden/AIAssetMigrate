package io.github.aiassetmigrate.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiassetmigrate.domain.Asset;
import io.github.aiassetmigrate.domain.AssetReference;
import io.github.aiassetmigrate.domain.Finding;
import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import io.github.aiassetmigrate.domain.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryJsonCodecTest {
    private final InventoryJsonCodec codec = new InventoryJsonCodec();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void roundTripsPartialInventoryWithUnicodePathsAndBothOptionalStates() throws IOException {
        InventorySnapshot snapshot = snapshot(Path.of("source assets", "\u96ea").toAbsolutePath());

        byte[] encoded = codec.encode(snapshot);
        JsonNode tree = json.readTree(encoded);

        assertEquals(snapshot, codec.decode(encoded));
        assertEquals("models/\u96ea.obj", tree.at("/project/assets/0/relativePath").textValue());
        assertTrue(tree.at("/project/sourceRoot").textValue().startsWith("file:///"));
        assertTrue(tree.at("/project/sourceRoot").textValue().contains("%20"));
        assertFalse(tree.at("/project/sourceRoot").textValue().contains("\\"));
        assertEquals("", tree.at("/pathOutcomes/0/relativePath").textValue());
        assertEquals("2026-10-27T02:00:00.123456789Z", tree.get("scannedAt").textValue());
        assertTrue(tree.at("/findings/1/assetId").isNull());
        assertTrue(tree.at("/findings/1/sourceRelativePath").isNull());
        assertTrue(tree.at("/references/1/normalizedTargetPath").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../asset.obj", "./asset.obj", "models/../asset.obj", "models/./asset.obj",
            "/asset.obj", "models//asset.obj", "models/", "models\\asset.obj", "C:asset.obj",
            "https://example.com/asset.obj", "file:relative", "file:///asset.obj?query", "file:///asset.obj#part"})
    void rejectsMalformedPathsBeforePlatformNormalization(String path) throws IOException {
        ObjectNode tree = (ObjectNode) json.readTree(codec.encode(snapshot(Path.of("source"))));
        ((ObjectNode) tree.get("project")).put("sourceRoot", path);
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(tree)));

        ObjectNode assetTree = (ObjectNode) json.readTree(codec.encode(snapshot(Path.of("source"))));
        ((ObjectNode) assetTree.at("/project/assets/0")).put("relativePath", path);
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(assetTree)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "-1", "2147483648", "1.0", "\"1\"", "null"})
    void rejectsUnknownOrMalformedSchemaVersions(String version) throws IOException {
        ObjectNode tree = (ObjectNode) json.readTree(codec.encode(snapshot(Path.of("source"))));
        tree.set("schemaVersion", json.readTree(version));
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(tree)));
    }

    @Test
    void rejectsMissingSchemaAndTrailingTokens() throws IOException {
        ObjectNode tree = (ObjectNode) json.readTree(codec.encode(snapshot(Path.of("source"))));
        tree.remove("schemaVersion");
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(tree)));
        String encoded = new String(codec.encode(snapshot(Path.of("source"))), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> codec.decode((encoded + " {}").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsEmptyAssetPathAndNonStringPaths() throws IOException {
        ObjectNode tree = (ObjectNode) json.readTree(codec.encode(snapshot(Path.of("source"))));
        ((ObjectNode) tree.at("/project/assets/0")).put("relativePath", "");
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(tree)));
        ((ObjectNode) tree.at("/project/assets/0")).put("relativePath", 42);
        assertThrows(IOException.class, () -> codec.decode(json.writeValueAsBytes(tree)));
    }

    private InventorySnapshot snapshot(Path root) {
        UUID assetId = UUID.fromString("a9179859-7f44-46ea-9515-45c449298c10");
        Instant time = Instant.parse("2026-10-27T02:00:00.123456789Z");
        Path assetPath = Path.of("models", "\u96ea.obj");
        Asset asset = new Asset(assetId, assetPath, Asset.Type.MODEL, "a".repeat(64),
                Map.of("label", "\u96ea"));
        Project project = new Project(UUID.fromString("f983a0e5-584e-42e0-904c-49c1961df534"),
                root.toAbsolutePath(), 1, List.of(asset), time);
        List<Finding> findings = List.of(
                new Finding(UUID.randomUUID(), Optional.of(assetId), Optional.of(assetPath),
                        Finding.Severity.WARNING, "MISSING_TEXTURE", "Missing \u96ea texture"),
                new Finding(UUID.randomUUID(), Optional.empty(), Optional.empty(),
                        Finding.Severity.ERROR, "SCAN_FAILED", "Could not finish scan"));
        return new InventorySnapshot(InventorySnapshot.CURRENT_SCHEMA_VERSION,
                InventorySnapshot.CURRENT_INSPECTION_VERSION, project, findings,
                InventorySnapshot.ScanStatus.PARTIAL, time,
                List.of(new PathOutcome(Path.of(""), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.TRAVERSED),
                        new PathOutcome(assetPath, PathOutcome.Kind.FILE, PathOutcome.Status.INVENTORIED),
                        new PathOutcome(Path.of("unreadable"), PathOutcome.Kind.DIRECTORY, PathOutcome.Status.FAILED)),
                List.of(new AssetReference(assetId, AssetReference.Kind.MATERIAL_LIBRARY,
                                "../materials/\u96ea.mtl", Optional.of(Path.of("materials", "\u96ea.mtl")),
                                AssetReference.ResolutionStatus.RESOLVED),
                        new AssetReference(assetId, AssetReference.Kind.TEXTURE, "missing.png", Optional.empty(),
                                AssetReference.ResolutionStatus.UNRESOLVED)));
    }
}
