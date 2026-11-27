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

import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

final class JulBackend implements LoggerBackend {
    private final LogManager manager = LogManager.getLogManager();

    @Override
    public List<String> levels() {
        return List.of("OFF", "SEVERE", "WARNING", "INFO", "FINE", "FINER", "FINEST");
    }

    @Override
    public void forEach(BiConsumer<String, LoggerLevels> consumer) {
        manager.getLoggerNames().asIterator().forEachRemaining(name -> {
            Logger logger = manager.getLogger(name);
            // JUL holds weak references. A logger can disappear during enumeration.
            if (logger != null) {
                consumer.accept(name, levels(logger));
            }
        });
    }

    @Override
    public boolean owns(String name) {
        return manager.getLogger(name) != null;
    }

    @Override
    public LoggerLevels logger(String name) {
        return levels(Logger.getLogger(name));
    }

    @Override
    public void set(String name, String level) {
        Level parsed = Level.parse(level);
        Logger.getLogger(name).setLevel(parsed);
    }

    @Override
    public void clear(String name) {
        Logger.getLogger(name).setLevel(null);
    }

    private LoggerLevels levels(Logger logger) {
        Level explicit = logger.getLevel();
        for (Logger current = logger; current != null; current = current.getParent()) {
            Level level = current.getLevel();
            if (level != null) {
                return new LoggerLevels(level.getName(), explicit == null ? null : explicit.getName());
            }
        }
        return new LoggerLevels(Level.INFO.getName(), null);
    }
}
