package io.github.aiassetmigrate.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The committed test-env samples are test data. The benchmark sample must stay byte-identical to what
 * {@link BenchmarkSample} writes, since the answer key lives in code; if this fails, run test-env\make-samples.cmd
 * and commit the result.
 */
class CommittedSamplesTest {
    private static final Path SAMPLES = Path.of("test-env", "samples");
    @TempDir Path directory;

    @Test void committedBenchmarkSampleMatchesTheAnswerKeyCode() throws Exception {
        Path generated = directory.resolve("benchmark");
        BenchmarkSample.write(generated);
        Map<String, byte[]> expected = files(generated), committed = files(SAMPLES.resolve("benchmark"));
        assertEquals(expected.keySet(), committed.keySet(), "file list differs; run test-env\\make-samples.cmd");
        for (String path : expected.keySet()) {
            assertArrayEquals(expected.get(path), committed.get(path), path + " differs; run test-env\\make-samples.cmd");
        }
    }

    @Test void cleanAndMessySamplesAreCommitted() throws Exception {
        assertEquals(8, files(SAMPLES.resolve("clean")).size());
        assertEquals(15, files(SAMPLES.resolve("messy")).size());
        assertTrue(files(SAMPLES.resolve("messy")).containsKey("misc/broken.png"));
    }

    private static Map<String, byte[]> files(Path root) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return files;
    }
}
