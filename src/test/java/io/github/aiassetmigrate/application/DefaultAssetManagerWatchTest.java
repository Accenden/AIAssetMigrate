package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.LogEvent;
import io.github.aiassetmigrate.inventory.AssetScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultAssetManagerWatchTest {
    @TempDir Path directory;

    @Test void replayFollowAndClosingViewerDoesNotStopRescan() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("asset.obj"), "v 0 0 0\n");
        Path logs = directory.resolve("logs");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), logs)) {
            UUID id = manager.importAssets(source).snapshot().project().id();
            assumeTrue(Files.getFileStore(logs).supportsFileAttributeView("unix"));
            CountDownLatch replay = new CountDownLatch(4);
            CountDownLatch live = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            LogObserver observer = new LogObserver() {
                @Override public void update(LogEvent event) {
                    if (event.sequence() <= 4) replay.countDown(); else live.countDown();
                }
                @Override public void onError(Throwable error) { failure.set(error); }
            };
            try (var subscription = manager.watch(id, 0, observer)) {
                assertTrue(replay.await(5, TimeUnit.SECONDS));
                assertEquals(2, manager.rescan(id, source).snapshot().project().inventoryRevision());
                assertTrue(live.await(5, TimeUnit.SECONDS));
            }
            assertEquals(3, manager.rescan(id, source).snapshot().project().inventoryRevision());
            assertNull(failure.get());
        }
    }

    @Test void observerFailureIsReportedAndDoesNotStopWriter() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path logs = directory.resolve("logs");
        try (var manager = new DefaultAssetManager(directory.resolve("state"), logs)) {
            UUID id = manager.importAssets(source).snapshot().project().id();
            assumeTrue(Files.getFileStore(logs).supportsFileAttributeView("unix"));
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            try (var ignored = manager.watch(id, 0, new LogObserver() {
                @Override public void update(LogEvent event) { throw new IllegalStateException("broken viewer"); }
                @Override public void onError(Throwable failure) { error.set(failure); failed.countDown(); }
            })) {
                assertTrue(failed.await(5, TimeUnit.SECONDS));
                assertEquals("broken viewer", error.get().getMessage());
                assertEquals(2, manager.rescan(id, source).snapshot().project().inventoryRevision());
            }
        }
    }

    @Test void invalidAndMissingProjectsFailWithoutCreatingLogs() throws Exception {
        Path logs = directory.resolve("logs");
        var manager = new DefaultAssetManager(directory.resolve("state"), logs);
        assertThrows(IllegalArgumentException.class, () -> manager.watch(UUID.randomUUID(), -1, event -> {}));
        assertThrows(UncheckedIOException.class, () -> manager.watch(UUID.randomUUID(), 0, event -> {}));
        assertFalse(Files.exists(logs));
        manager.close();
        assertThrows(IllegalStateException.class, () -> manager.watch(UUID.randomUUID(), 0, event -> {}));
    }

    @Test void closingViewerAndManagerDoesNotCloseActiveWriter() throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path state = directory.resolve("state");
        Path logs = directory.resolve("logs");
        UUID id;
        try (var manager = new DefaultAssetManager(state, logs)) {
            id = manager.importAssets(source).snapshot().project().id();
        }
        assumeTrue(Files.getFileStore(logs).supportsFileAttributeView("unix"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean publisherClosed = new AtomicBoolean();
        AtomicBoolean viewerClosed = new AtomicBoolean();
        var manager = new DefaultAssetManager(state, logs, new AssetScanner(), (path, project) ->
                new DefaultAssetManager.Publisher() {
                    @Override public void publish(LogEvent.Level level, String message) throws java.io.IOException {
                        if (message.equals("Scan started")) {
                            started.countDown();
                            try {
                                if (!proceed.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("timed out");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException(e);
                            }
                        }
                        if (publisherClosed.get()) throw new java.io.IOException("publisher closed early");
                    }
                    @Override public LogSubscription subscribe(long cursor, LogObserver observer) {
                        return () -> viewerClosed.set(true);
                    }
                    @Override public void close() { publisherClosed.set(true); }
                });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var subscription = manager.watch(id, 0, event -> {});
            var run = executor.submit(() -> manager.rescan(id, source));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            subscription.close();
            manager.close();
            assertTrue(viewerClosed.get());
            assertFalse(publisherClosed.get());
            proceed.countDown();
            assertEquals(2, run.get(5, TimeUnit.SECONDS).snapshot().project().inventoryRevision());
            assertTrue(publisherClosed.get());
        } finally {
            proceed.countDown();
            manager.close();
        }
    }
}
