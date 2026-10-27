package io.github.aiassetmigrate.application;

/** Closing a viewer's subscription must not cancel its migration worker. */
@FunctionalInterface
public interface LogSubscription extends AutoCloseable {
    @Override
    void close();
}
