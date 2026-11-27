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

interface LoggerBackend {
    List<String> levels();

    void forEach(BiConsumer<String, LoggerLevels> consumer);

    boolean owns(String name);

    LoggerLevels logger(String name);

    void set(String name, String level);

    void clear(String name);

    record LoggerLevels(String effective, String configured) {
    }
}
