package io.github.aiassetmigrate.inventory;

import io.github.aiassetmigrate.domain.InventorySnapshot;
import io.github.aiassetmigrate.domain.PathOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AssetScannerFailureTest {
    @TempDir Path root;

    @Test
    void unreadableFileKeepsHealthySiblingAndFailedCoverage() throws Exception {
        Path bad = Files.writeString(root.resolve("bad.obj"), "bad");
        Files.writeString(root.resolve("good.png"), "not an image");
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override InputStream open(Path path) throws IOException {
                if (path.equals(bad)) throw new AccessDeniedException(path.toString());
                return super.open(path);
            }
        }).scan(root);
        assertEquals(List.of(Path.of("good.png")), result.assets().stream().map(a -> a.relativePath()).toList());
        assertCode(result, "FILE_READ_FAILED");
        assertOutcome(result, "bad.obj", PathOutcome.Status.FAILED);
        assertOutcome(result, "", PathOutcome.Status.FAILED);
    }

    @Test
    void inaccessibleRootIsFatal() {
        assertThrows(AccessDeniedException.class, () -> scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) throws IOException {
                throw new AccessDeniedException(path.toString());
            }
        }).scan(root));
    }

    @Test
    void inaccessibleSubtreeDoesNotLoseHealthyFile() throws Exception {
        Path bad = Files.createDirectory(root.resolve("bad"));
        Files.writeString(root.resolve("good.mtl"), "good");
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) throws IOException {
                if (path.equals(bad)) throw new AccessDeniedException(path.toString());
                return super.directory(path);
            }
        }).scan(root);
        assertEquals(1, result.assets().size());
        assertCode(result, "DIRECTORY_READ_FAILED");
        assertOutcome(result, "bad", PathOutcome.Status.FAILED);
        assertOutcome(result, "", PathOutcome.Status.FAILED);
    }

    @Test
    void iteratorFailureIsRecoverableBelowRoot() throws Exception {
        Path bad = Files.createDirectory(root.resolve("bad"));
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) throws IOException {
                if (!path.equals(bad)) return super.directory(path);
                return new DirectoryStream<>() {
                    public Iterator<Path> iterator() {
                        return new Iterator<>() {
                            public boolean hasNext() { throw new DirectoryIteratorException(new IOException("fault")); }
                            public Path next() { throw new AssertionError(); }
                        };
                    }
                    public void close() {}
                };
            }
        }).scan(root);
        assertCode(result, "DIRECTORY_READ_FAILED");
    }

    @Test
    void changedSizeAndTimestampRejectUnstableFile() throws Exception {
        Path bad = Files.writeString(root.resolve("bad.obj"), "before");
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override InputStream open(Path path) throws IOException {
                return new FilterInputStream(super.open(path)) {
                    @Override public void close() throws IOException {
                        super.close();
                        Files.writeString(bad, "after with a different size");
                    }
                };
            }
        }).scan(root);
        assertTrue(result.assets().isEmpty());
        assertCode(result, "UNSTABLE_FILE");
    }

    @Test
    void sameSizeTimestampChangeIsUnstable() throws Exception {
        Path bad = Files.writeString(root.resolve("bad.obj"), "before");
        FileTime later = FileTime.fromMillis(Files.getLastModifiedTime(bad).toMillis() + 10_000);
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override InputStream open(Path path) throws IOException {
                return new FilterInputStream(super.open(path)) {
                    @Override public void close() throws IOException {
                        super.close();
                        Files.setLastModifiedTime(bad, later);
                    }
                };
            }
        }).scan(root);
        assertCode(result, "UNSTABLE_FILE");
    }

    @Test
    void growingStreamIsBoundedByOriginalSizePlusOne() throws Exception {
        Files.writeString(root.resolve("growing.obj"), "data");
        AtomicInteger read = new AtomicInteger();
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override InputStream open(Path path) {
                return new InputStream() {
                    public int read() { assertTrue(read.incrementAndGet() <= 5); return 1; }
                };
            }
        }).scan(root);
        assertEquals(5, read.get());
        assertTrue(result.assets().isEmpty());
        assertCode(result, "UNSTABLE_FILE");
    }

    @Test
    void replacementIdentityIsUnstableEvenWithSameSizeAndModificationTime() throws Exception {
        Path original = Files.writeString(root.resolve("asset.obj"), "before");
        AtomicBoolean replaced = new AtomicBoolean();
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                BasicFileAttributes attributes = super.attributes(path);
                if (!path.equals(original)) return attributes;
                String identity = replaced.get() ? "replacement" : "original";
                return new BasicFileAttributes() {
                    public FileTime lastModifiedTime() { return attributes.lastModifiedTime(); }
                    public FileTime lastAccessTime() { return attributes.lastAccessTime(); }
                    public FileTime creationTime() { return attributes.creationTime(); }
                    public boolean isRegularFile() { return attributes.isRegularFile(); }
                    public boolean isDirectory() { return attributes.isDirectory(); }
                    public boolean isSymbolicLink() { return attributes.isSymbolicLink(); }
                    public boolean isOther() { return attributes.isOther(); }
                    public long size() { return attributes.size(); }
                    public Object fileKey() { return identity; }
                };
            }
            @Override InputStream open(Path path) throws IOException {
                return new FilterInputStream(super.open(path)) {
                    @Override public void close() throws IOException {
                        super.close();
                        replaced.set(true);
                    }
                };
            }
        }).scan(root);
        assertTrue(result.assets().isEmpty());
        assertCode(result, "UNSTABLE_FILE");
    }

    @Test
    void interruptedReadAbortsInsteadOfReturningPartial() throws Exception {
        Files.writeString(root.resolve("bad.obj"), "data");
        assertThrows(InterruptedIOException.class, () -> scanner(new AssetScanner.Io() {
            @Override InputStream open(Path path) {
                return new InputStream() {
                    public int read() throws IOException { throw new InterruptedIOException("cancelled"); }
                };
            }
        }).scan(root));
    }

    @Test
    void threadInterruptionInsideReadLoopIsPreserved() throws Exception {
        Files.writeString(root.resolve("bad.obj"), "data");
        try {
            assertThrows(InterruptedIOException.class, () -> scanner(new AssetScanner.Io() {
                @Override InputStream open(Path path) {
                    return new InputStream() {
                        public int read() { Thread.currentThread().interrupt(); return 1; }
                        public int read(byte[] bytes) { Thread.currentThread().interrupt(); return 1; }
                    };
                }
            }).scan(root));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void caseCollisionRejectsAllFilesBeforeOpeningOnEveryHost() throws Exception {
        Files.writeString(root.resolve("asset.obj"), "data");
        AtomicInteger opens = new AtomicInteger();
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) {
                return listing(List.of(root.resolve("asset.obj"), root.resolve("ASSET.OBJ")));
            }
            @Override InputStream open(Path path) { opens.incrementAndGet(); throw new AssertionError(); }
        }).scan(root);
        assertTrue(result.assets().isEmpty());
        assertEquals(0, opens.get());
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("CASE_COLLISION")).count());
        assertEquals(2, result.pathOutcomes().stream().filter(o -> !o.relativePath().toString().isEmpty()).count());
    }

    @Test
    void caseCollidingDirectoriesNeverVisitDescendants() throws Exception {
        Files.createDirectory(root.resolve("models"));
        Files.writeString(root.resolve("models/child.obj"), "data");
        AtomicInteger listings = new AtomicInteger();
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) {
                assertEquals(root, path);
                listings.incrementAndGet();
                return listing(List.of(root.resolve("models"), root.resolve("MODELS")));
            }
        }).scan(root);
        assertEquals(1, listings.get());
        assertTrue(result.assets().isEmpty());
        assertCode(result, "CASE_COLLISION");
        assertOutcome(result, "", PathOutcome.Status.FAILED);
    }

    @Test
    void unicodeCaseCollisionUsesConservativeStringKeys() throws Exception {
        AssetScanner.ScanResult result = scanner(new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) {
                return listing(List.of(root.resolve("I.obj"), root.resolve("\u0131.obj")));
            }
        }).scan(root);
        assertTrue(result.assets().isEmpty());
        assertEquals(2, result.findings().stream().filter(f -> f.code().equals("CASE_COLLISION")).count());
    }

    @Test
    void gatheringStopsAtRemainingBudgetPlusOne() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        AssetScanner scanner = new AssetScanner(new ScanLimits(64, 2, 1, 1, 1), new AssetScanner.Io() {
            @Override DirectoryStream<Path> directory(Path path) {
                return new DirectoryStream<>() {
                    public Iterator<Path> iterator() {
                        return new Iterator<>() {
                            public boolean hasNext() { return true; }
                            public Path next() {
                                int count = consumed.incrementAndGet();
                                assertTrue(count <= 3);
                                return root.resolve(count + ".obj");
                            }
                        };
                    }
                    public void close() {}
                };
            }
        });
        AssetScanner.ScanResult result = scanner.scan(root);
        assertEquals(3, consumed.get());
        assertTrue(result.assets().isEmpty());
        assertCode(result, "ENTRY_LIMIT");
        assertOutcome(result, "", PathOutcome.Status.UNVISITED);
    }

    @Test
    void depthLimitPropagatesToEveryAncestor() throws Exception {
        Files.createDirectories(root.resolve("a/b/c"));
        AssetScanner.ScanResult result = new AssetScanner(new ScanLimits(2, 100, 1, 1, 1)).scan(root);
        assertCode(result, "DEPTH_LIMIT");
        assertOutcome(result, "a/b", PathOutcome.Status.UNVISITED);
        assertOutcome(result, "a", PathOutcome.Status.UNVISITED);
        assertOutcome(result, "", PathOutcome.Status.UNVISITED);
    }

    @Test
    void unsafePortableNameIsReportedAtSafeParent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").startsWith("Windows"),
                "Colon/backslash filenames require Unix filesystem semantics");
        Files.writeString(root.resolve("bad:name.obj"), "data");
        Files.writeString(root.resolve("bad\\name.obj"), "data");
        AssetScanner.ScanResult result = new AssetScanner().scan(root);
        assertTrue(result.assets().isEmpty());
        assertEquals(2, result.findings().size());
        assertTrue(result.findings().stream().allMatch(f -> f.sourceRelativePath().orElseThrow().toString().isEmpty()));
        assertOutcome(result, "", PathOutcome.Status.FAILED);
    }

    private AssetScanner scanner(AssetScanner.Io io) { return new AssetScanner(ScanLimits.DEFAULTS, io); }

    private static DirectoryStream<Path> listing(List<Path> paths) {
        return new DirectoryStream<>() {
            public Iterator<Path> iterator() { return paths.iterator(); }
            public void close() {}
        };
    }

    private static void assertCode(AssetScanner.ScanResult result, String code) {
        assertEquals(InventorySnapshot.ScanStatus.PARTIAL, result.scanStatus());
        assertTrue(result.findings().stream().anyMatch(f -> f.code().equals(code)), result.findings().toString());
    }

    private static void assertOutcome(AssetScanner.ScanResult result, String path, PathOutcome.Status status) {
        assertTrue(result.pathOutcomes().stream().anyMatch(o -> o.relativePath().toString().replace('\\', '/').equals(path)
                && o.status() == status), result.pathOutcomes().toString());
    }
}
