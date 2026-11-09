package io.github.aiassetmigrate.gui;

import io.github.aiassetmigrate.application.AssetManager;
import io.github.aiassetmigrate.application.DefaultAssetManager;
import io.github.aiassetmigrate.domain.InventorySnapshot;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Serializes GUI operations and closes each manager on the worker, even during shutdown. */
final class ImportRunner implements AutoCloseable {
    @FunctionalInterface
    interface Factory {
        Managed open(Path state, Path logs) throws Exception;
    }

    @FunctionalInterface
    interface Operation {
        InventorySnapshot run(AssetManager manager) throws Exception;
    }

    record Managed(AssetManager manager, AutoCloseable resource) { }
    record Outcome(InventorySnapshot snapshot, Throwable failure) { }
    record Result<T>(T value, Throwable failure) { }

    @FunctionalInterface
    interface Task<T> {
        T run(AssetManager manager) throws Exception;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "import-project-worker");
        thread.setDaemon(false);
        return thread;
    });
    private final Executor dispatcher;
    private final Factory factory;
    private boolean busy;
    private boolean closed;

    ImportRunner(Executor dispatcher) {
        this(dispatcher, (state, logs) -> {
            DefaultAssetManager manager = new DefaultAssetManager(state, logs);
            return new Managed(manager, manager);
        });
    }

    ImportRunner(Executor dispatcher, Factory factory) {
        this.dispatcher = dispatcher;
        this.factory = factory;
    }

    synchronized boolean submit(Path state, Path logs, Operation operation, Consumer<Outcome> completion) {
        return submitValue(state, logs, operation::run,
                result -> completion.accept(new Outcome(result.value(), result.failure())));
    }

    synchronized <T> boolean submitValue(Path state, Path logs, Task<T> operation, Consumer<Result<T>> completion) {
        if (busy || closed) return false;
        busy = true;
        worker.execute(() -> {
            T value = null;
            Throwable failure = null;
            try {
                Managed managed = factory.open(state, logs);
                try (AutoCloseable ignored = managed.resource()) {
                    value = operation.run(managed.manager());
                }
            } catch (Exception error) {
                failure = error;
            }
            Result<T> outcome = new Result<>(value, failure);
            dispatcher.execute(() -> {
                synchronized (ImportRunner.this) {
                    if (closed) return;
                    busy = false;
                }
                completion.accept(outcome);
            });
        });
        return true;
    }

    synchronized boolean isBusy() {
        return busy;
    }

    @Override
    public synchronized void close() {
        closed = true;
        // Do not interrupt an import that may already have committed a revision.
        worker.shutdown();
    }
}
