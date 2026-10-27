package io.github.aiassetmigrate.application;

import io.github.aiassetmigrate.domain.LogEvent;

/** Receives persisted events; GUI observers marshal updates onto the UI thread. */
@FunctionalInterface
public interface LogObserver {
    void update(LogEvent event);
}
