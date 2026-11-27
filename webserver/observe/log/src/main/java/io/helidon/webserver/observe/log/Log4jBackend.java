/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.webserver.observe.log;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import io.helidon.http.NotFoundException;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

final class Log4jBackend implements LoggerBackend {
    // Observers can share a Core context. Serialize the read-modify-update sequence across them.
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    private final LoggerContext context;

    private Log4jBackend(LoggerContext context) {
        this.context = context;
    }

    static LoggerBackend create(ClassLoader applicationLoader) {
        return LogManager.getContext(applicationLoader, false) instanceof LoggerContext core ? new Log4jBackend(core) : null;
    }

    @Override
    public List<String> levels() {
        return Arrays.stream(Level.values())
                .sorted(Comparator.comparingInt(Level::intLevel).thenComparing(Level::name))
                .map(Level::name)
                .toList();
    }

    @Override
    public void forEach(BiConsumer<String, LoggerLevels> consumer) {
        Configuration config = context.getConfiguration();
        Map<String, Level> effective = new HashMap<>();
        config.getLoggers().forEach((name, entry) -> effective.put(name, entry.getLevel()));
        context.getLoggers().forEach(logger -> effective.put(logger.getName(), logger.getLevel()));
        effective.put(LogManager.ROOT_LOGGER_NAME, context.getRootLogger().getLevel());
        effective.forEach((name, level) -> consumer.accept(name, levels(config, name, level)));
    }

    @Override
    public boolean owns(String name) {
        return name.isEmpty() || context.hasLogger(name) || context.getConfiguration().getLoggers().containsKey(name);
    }

    @Override
    public LoggerLevels logger(String name) {
        Configuration config = context.getConfiguration();
        var live = context.getLoggers().stream().filter(logger -> logger.getName().equals(name)).findFirst();
        if (live.isPresent()) {
            return levels(config, name, live.get().getLevel());
        }
        LoggerConfig logger = exact(config, name);
        if (logger == null) {
            throw new NotFoundException("Unknown Log4j2 logger: " + name);
        }
        return levels(config, name, logger.getLevel());
    }

    @Override
    public void set(String name, String level) {
        Level parsed = Level.valueOf(level);
        WRITE_LOCK.lock();
        try {
            Configuration config = context.getConfiguration();
            LoggerConfig exact = exact(config, name);
            if (exact == null) {
                config.addLogger(name, new ObserverLoggerConfig(name, parsed));
            } else {
                exact.setLevel(parsed);
            }
            context.updateLoggers();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    @Override
    public void clear(String name) {
        WRITE_LOCK.lock();
        try {
            Configuration config = context.getConfiguration();
            LoggerConfig exact = exact(config, name);
            if (exact == null) {
                return;
            }
            if (exact instanceof ObserverLoggerConfig && exact.getAppenders().isEmpty()
                    && exact.getFilter() == null && exact.isAdditive()) {
                config.removeLogger(name);
            } else {
                exact.setLevel(null);
            }
            context.updateLoggers();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static LoggerConfig exact(Configuration config, String name) {
        return name.isEmpty() ? config.getRootLogger() : config.getLoggers().get(name);
    }

    private static LoggerLevels levels(Configuration config, String name, Level effective) {
        LoggerConfig logger = exact(config, name);
        Level explicit = logger == null ? null : logger.getExplicitLevel();
        return new LoggerLevels(effective.name(), explicit == null ? null : explicit.name());
    }

    // Mark the actual entry so a configuration reload cannot transfer ownership to a replacement.
    private static final class ObserverLoggerConfig extends LoggerConfig {
        private ObserverLoggerConfig(String name, Level level) {
            super(name, level, true);
        }
    }
}
