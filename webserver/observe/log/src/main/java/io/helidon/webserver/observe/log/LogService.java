/*
 * Copyright (c) 2022, 2026 Oracle and/or its affiliates.
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

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Filter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.helidon.http.BadRequestException;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.HttpMediaType;
import io.helidon.http.NotFoundException;
import io.helidon.http.Status;
import io.helidon.http.media.EntityWriter;
import io.helidon.http.media.json.JsonSupport;
import io.helidon.json.JsonArray;
import io.helidon.json.JsonException;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.SecureHandler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

class LogService implements HttpService {
    private static final EntityWriter<JsonObject> WRITER = JsonSupport.serverResponseWriter();
    private static final String DEFAULT_IDLE_STRING = "%\n";
    private static final ThreadLocal<Boolean> LOG_STREAM_WRITE = new ThreadLocal<>();

    private final JulBackend jul = new JulBackend();
    private final LoggerBackend log4j;
    private final Logger root;
    private final Map<Object, Consumer<String>> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean logHandlingInitialized = new AtomicBoolean();
    private final boolean permitAll;
    private final boolean logStreamEnabled;
    private final Header logStreamMediaTypeHeader;
    private final Duration logStreamIdleTimeout;
    private final int logStreamQueueSize;
    private final String logStreamIdleString;
    private final Charset logStreamCharset;

    LogService(LogObserverConfig config) {
        this.log4j = log4jBackend();
        this.root = Logger.getLogger("");

        this.permitAll = config.permitAll();

        LogStreamConfig stream = config.stream();
        this.logStreamEnabled = stream.enabled();
        this.logStreamIdleTimeout = stream.idleMessageTimeout();
        this.logStreamQueueSize = stream.queueSize();
        this.logStreamIdleString = stream.idleString();

        HttpMediaType streamType = stream.contentType();
        this.logStreamMediaTypeHeader = HeaderValues.createCached(HeaderNames.CONTENT_TYPE,
                                                                  streamType.text());
        this.logStreamCharset = streamType.charset()
                .map(Charset::forName)
                .orElse(StandardCharsets.UTF_8);
    }

    private static LoggerBackend log4jBackend() {
        try {
            Class.forName("org.apache.logging.log4j.core.LoggerContext", false, LogService.class.getClassLoader());
        } catch (ClassNotFoundException _) {
            return null;
        }
        return Log4jBackend.create(Thread.currentThread().getContextClassLoader());
    }

    private static JsonObject json(LoggerBackend.LoggerLevels levels) {
        var entry = JsonObject.builder().set("level", levels.effective());
        if (levels.configured() != null) {
            entry.set("configuredLevel", levels.configured());
        }
        return entry.build();
    }

    private static String displayName(String name) {
        return name.isEmpty() ? "ROOT" : name;
    }

    @Override
    public void routing(HttpRules rules) {
        if (!permitAll) {
            rules.any(SecureHandler.authorize("observe"));
        }

        rules.get("/loggers")
                .get("/loggers", this::allLoggersHandler)
                .get("/loggers/{logger}", this::loggerHandler)
                .post("/loggers/{logger}", this::setLevelHandler)
                .delete("/loggers/{logger}", this::unsetLoggerHandler);

        if (logStreamEnabled) {
            rules.get("/", this::logHandler);
        }
    }

    @Override
    public void afterStop() {
        listeners.clear();
    }

    private void logHandler(ServerRequest req, ServerResponse res) throws Exception {
        initializeLogHandling();
        res.header(logStreamMediaTypeHeader);
        // do not cache more than x lines, to prevent OOM
        var q = new ArrayBlockingQueue<String>(logStreamQueueSize);

        // we do not care if the offer fails, it means the consumer is slow and will miss some lines
        listeners.put(req, q::offer);

        OutputStreamWriter out = null;
        try {
            out = new OutputStreamWriter(res.outputStream(), logStreamCharset);
            while (true) {
                try {
                    String poll = q.poll(logStreamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    if (poll == null) {
                        // check if we are still alive
                        writeLogStream(out, logStreamIdleString, true);
                    } else {
                        writeLogStream(out, poll, false);
                    }
                } catch (InterruptedException e) {
                    // interrupted - we should finish
                    return;
                }
            }
        } finally {
            listeners.remove(req);
            if (out != null) {
                closeLogStream(out);
            }
        }
    }

    // Package-private so tests can verify loop prevention without depending on protocol-specific logger names.
    static void writeLogStream(OutputStreamWriter out, String message, boolean flush) throws IOException {
        LOG_STREAM_WRITE.set(true);
        try {
            out.write(message);
            if (flush) {
                out.flush();
            }
        } finally {
            LOG_STREAM_WRITE.remove();
        }
    }

    // Package-private so tests can verify close-time flush loop prevention.
    static void closeLogStream(OutputStreamWriter out) throws IOException {
        LOG_STREAM_WRITE.set(true);
        try {
            out.close();
        } finally {
            LOG_STREAM_WRITE.remove();
        }
    }

    private void initializeLogHandling() {
        if (logHandlingInitialized.compareAndSet(false, true)) {
            for (java.util.logging.Handler handler : root.getHandlers()) {
                handler.setFilter(new LogMessageFilter(handler.getFormatter(), handler.getFilter(), listeners));
                break;
            }
        }
    }

    private void unsetLoggerHandler(ServerRequest req, ServerResponse res) {
        Target target = target(req);
        if (target.name().isEmpty()) {
            throw new BadRequestException("The root logger cannot inherit a level");
        }
        target.backend().clear(target.name());
        res.status(Status.NO_CONTENT_204).send();
    }

    private void setLevelHandler(ServerRequest req, ServerResponse res) {
        Target target = target(req);
        try {
            String content = req.content().asOptional(String.class)
                    .orElseThrow(() -> new BadRequestException("Missing logger level request body"));
            JsonParser parser = JsonParser.create(content.stripTrailing());
            JsonObject body = parser.readJsonObject();
            if (parser.hasNext()) {
                throw new BadRequestException("Unexpected content after logger level");
            }
            String level = body.stringValue("level").orElseThrow(() -> new BadRequestException("Missing level"));
            target.backend().set(target.name(), level);
        } catch (JsonException | IllegalArgumentException e) {
            throw new BadRequestException("Expected a JSON object with a valid string level", e);
        }
        res.status(Status.NO_CONTENT_204).send();
    }

    private void loggerHandler(ServerRequest req, ServerResponse res) {
        Target target = target(req);
        write(req, res, JsonObject.builder()
                .set(displayName(target.name()), json(target.backend().logger(target.name())))
                .build());
    }

    private void allLoggersHandler(ServerRequest req, ServerResponse res) {
        LoggerBackend selected = selectedBackend(req);
        List<LoggerBackend> backends = selected == null && log4j != null
                ? List.of(jul, log4j)
                : List.of(selected == null ? jul : selected);
        var levels = new LinkedHashSet<String>();
        var loggers = new LinkedHashMap<String, LoggerBackend.LoggerLevels>();
        for (LoggerBackend backend : backends) {
            levels.addAll(backend.levels());
            backend.forEach(loggers::putIfAbsent);
        }
        JsonObject.Builder entries = JsonObject.builder();
        loggers.forEach((name, info) -> entries.set(displayName(name), json(info)));
        // ROOT in a listing always represents the actual root, even if a literal ROOT logger exists.
        entries.set("ROOT", json(backends.getFirst().logger("")));
        write(req, res, JsonObject.builder()
                .set("levels", JsonArray.createStrings(List.copyOf(levels)))
                .set("loggers", entries.build())
                .build());
    }

    private Target target(ServerRequest req) {
        String name = req.path().pathParameters().first("logger").orElseThrow();
        LoggerBackend selected = selectedBackend(req);
        if (selected != null) {
            return new Target(selected, "ROOT".equals(name) ? "" : name);
        }
        if (log4j != null && !"ROOT".equals(name) && !jul.owns(name) && log4j.owns(name)) {
            return new Target(log4j, name);
        }
        return new Target(jul, name);
    }

    private LoggerBackend selectedBackend(ServerRequest req) {
        List<String> values = req.query().all("backend", List::of);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new BadRequestException("Specify one backend: jul or log4j2");
        }
        return switch (values.getFirst()) {
        case "jul" -> jul;
        case "log4j2" -> {
            if (log4j == null) {
                throw new NotFoundException("Log4j2 Core is unavailable");
            }
            yield log4j;
        }
        default -> throw new BadRequestException("Unknown logger backend: " + values.getFirst());
        };
    }

    private void write(ServerRequest req, ServerResponse res, JsonObject json) {
        WRITER.write(JsonSupport.JSON_OBJECT_TYPE,
                     json,
                     res.outputStream(),
                     req.headers(),
                     res.headers());
    }

    // Package-private so tests can verify loop prevention without depending on protocol-specific logger names.
    static class LogMessageFilter implements Filter {
        private final Formatter formatter;
        private final Filter filter;
        private final Map<Object, Consumer<String>> listeners;

        LogMessageFilter(Formatter formatter, Filter filter, Map<Object, Consumer<String>> listeners) {
            this.formatter = formatter;
            this.filter = filter;
            this.listeners = listeners;
        }

        @Override
        public boolean isLoggable(LogRecord record) {
            boolean result = filter == null || filter.isLoggable(record);

            if (result
                    && record.getLevel().intValue() >= Level.FINE.intValue()
                    && !Boolean.TRUE.equals(LOG_STREAM_WRITE.get())) {
                // Log records emitted while writing the stream are caused by this endpoint's own response.
                fire(formatter.format(record));
            }

            return result;
        }

        private void fire(String message) {
            if (listeners.isEmpty()) {
                return;
            }
            listeners.values().forEach(it -> {
                try {
                    it.accept(message);
                } catch (Exception ignored) {
                    // ignore exception, we cannot stop printing to stdout
                }
            });
        }
    }

    private record Target(LoggerBackend backend, String name) {
    }
}
