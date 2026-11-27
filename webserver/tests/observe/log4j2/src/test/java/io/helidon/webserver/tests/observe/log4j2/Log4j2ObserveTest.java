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

package io.helidon.webserver.tests.observe.log4j2;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonObject;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.log.LogObserver;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ServerTest
class Log4j2ObserveTest {
    private static final String PATH = "/observe/log/loggers";

    @SetUpServer
    static void setup(WebServerConfig.Builder server) {
        server.featuresDiscoverServices(false)
                .addFeature(ObserveFeature.builder()
                                    .addObserver(LogObserver.builder().permitAll(true).build())
                                    .build());
    }

    @Test
    void changeLiveLogger(WebClient client) {
        var orders = LoggerFactory.getLogger("com.oracle.orders");
        var sibling = LoggerFactory.getLogger("com.oracle.sibling");
        var jul = Logger.getLogger("test.jul");
        var list = client.get().uri(URI.create(PATH)).requestEntity(JsonObject.class).objectValue("loggers").orElseThrow();
        assertTrue(list.value(jul.getName()).isPresent());
        assertTrue(list.value(orders.getName()).isPresent());
        assertFalse(orders.isDebugEnabled());
        assertFalse(sibling.isDebugEnabled());
        String url = PATH + "/" + orders.getName();
        assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"DEBUG\"}"));
        try {
            assertTrue(orders.isDebugEnabled());
            assertFalse(sibling.isDebugEnabled());
            assertEquals("DEBUG", entry(client, url, orders.getName()).stringValue("configuredLevel").orElseThrow());
        } finally {
            assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
        }
        var inherited = entry(client, url, orders.getName());
        assertEquals("INFO", inherited.stringValue("level").orElseThrow());
        assertTrue(inherited.value("configuredLevel").isEmpty());
        assertFalse(orders.isDebugEnabled());
        assertFalse(sibling.isDebugEnabled());
        assertNull(LogManager.getLogManager().getLogger(orders.getName()));
    }

    @Test
    void sharedName(WebClient client) {
        String name = "com.oracle.shared";
        var app = LoggerFactory.getLogger(name);
        var jul = Logger.getLogger(name);
        String url = PATH + "/" + name;
        try {
            assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"FINE\"}"));
            assertEquals(java.util.logging.Level.FINE, jul.getLevel());
            assertFalse(app.isDebugEnabled());
            assertEquals(Status.NO_CONTENT_204, post(client, url + "?backend=log4j2", "{\"level\":\"DEBUG\"}"));
            assertEquals("DEBUG", entry(client, url + "?backend=log4j2", name).stringValue("level").orElseThrow());
            assertEquals("FINE", entry(client, url, name).stringValue("level").orElseThrow());
            assertTrue(app.isDebugEnabled());
            for (String backend : List.of("jul", "log4j2")) {
                var list = client.get().uri(URI.create(PATH + "?backend=" + backend)).requestEntity(JsonObject.class);
                assertEquals(backend.equals("jul") ? "FINE" : "DEBUG",
                             list.objectValue("loggers").orElseThrow().objectValue(name).orElseThrow()
                                     .stringValue("level").orElseThrow());
            }
            assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
            assertNull(jul.getLevel());
            assertTrue(app.isDebugEnabled());
        } finally {
            jul.setLevel(null);
            client.delete().uri(URI.create(url + "?backend=log4j2")).request(String.class);
        }
        assertFalse(app.isDebugEnabled());
    }

    @Test
    void packageBeforeFirstLogger(WebClient client) {
        String name = "com.oracle.future";
        String url = PATH + "/" + name + "?backend=log4j2";
        var config = context().getConfiguration();
        assertEquals(Status.NOT_FOUND_404, client.get().uri(URI.create(url)).request(String.class).status());
        assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"DEBUG\"}"));
        try {
            assertTrue(LoggerFactory.getLogger(name + ".child").isDebugEnabled());
            assertFalse(LoggerFactory.getLogger(name + ".fixed.child").isDebugEnabled());
            assertFalse(LoggerFactory.getLogger("com.oracle.other").isDebugEnabled());
        } finally {
            assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
        }
        assertFalse(config.getLoggers().containsKey(name));
        assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
    }

    @Test
    void registeredCustomLevel(WebClient client) {
        Level.forName("AUDIT", 350);
        String name = "test.custom";
        String url = PATH + "/" + name + "?backend=log4j2";
        assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"AUDIT\"}"));
        try {
            assertEquals("AUDIT", entry(client, url, name).stringValue("level").orElseThrow());
            var list = client.get().uri(URI.create(PATH + "?backend=log4j2")).requestEntity(JsonObject.class);
            assertTrue(list.arrayValue("levels").orElseThrow().values().stream()
                               .anyMatch(level -> level.asString().value().equals("AUDIT")));
        } finally {
            client.delete().uri(URI.create(url)).request(String.class);
        }
    }

    @Test
    void invalidRequestsDoNotCreateLoggers(WebClient client) {
        String name = "test.invalid.new";
        String url = PATH + "/" + name;
        for (String query : List.of("", "?backend=log4j2")) {
            for (String body : List.of("", "null", "{", "[]", "{}", "{\"level\":42}", "{\"level\":\"NOT_A_LEVEL\"}",
                                       "{\"level\":\"INFO\"} extra")) {
                assertEquals(Status.BAD_REQUEST_400, post(client, url + query, body), body);
            }
        }
        for (String query : List.of("?backend=other", "?backend=jul&backend=log4j2", "?backend=jul&backend=jul")) {
            assertEquals(Status.BAD_REQUEST_400, post(client, url + query, "{\"level\":\"INFO\"}"));
        }
        assertNull(LogManager.getLogManager().getLogger(name));
        assertFalse(context().getConfiguration().getLoggers().containsKey(name));
    }

    @Test
    void roots(WebClient client) {
        var julRoot = Logger.getLogger("");
        var literal = Logger.getLogger("ROOT");
        var julLevel = julRoot.getLevel();
        var initial = literal.getLevel();
        String url = PATH + "/ROOT";
        try {
            assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"CONFIG\"}"));
            assertEquals(java.util.logging.Level.CONFIG, literal.getLevel());
            assertEquals(julLevel, julRoot.getLevel());
            assertEquals(Status.NO_CONTENT_204, post(client, url + "?backend=jul", "{\"level\":\"WARNING\"}"));
            assertEquals(java.util.logging.Level.WARNING, julRoot.getLevel());
            assertEquals(Status.NO_CONTENT_204, post(client, url + "?backend=log4j2", "{\"level\":\"WARN\"}"));
            assertEquals("WARN", entry(client, url + "?backend=log4j2", "ROOT").stringValue("level").orElseThrow());
            for (String backend : List.of("jul", "log4j2")) {
                var clear = client.delete().uri(URI.create(url + "?backend=" + backend));
                assertEquals(Status.BAD_REQUEST_400, clear.request(String.class).status());
            }
        } finally {
            julRoot.setLevel(julLevel);
            literal.setLevel(initial);
            context().reconfigure();
        }
    }

    @Test
    void reloadAndPreserveApplicationConfiguration(WebClient client) {
        var context = context();
        String name = "test.reload";
        String url = PATH + "/" + name + "?backend=log4j2";
        assertEquals(Status.NO_CONTENT_204, post(client, url, "{\"level\":\"DEBUG\"}"));
        context.reconfigure();
        var config = context.getConfiguration();
        var replacement = new LoggerConfig(name, Level.WARN, false);
        var filter = ThresholdFilter.createFilter(Level.ERROR, Filter.Result.ACCEPT, Filter.Result.DENY);
        var appender = config.getAppender("Console");
        replacement.addAppender(appender, null, null);
        replacement.addFilter(filter);
        config.addLogger(name, replacement);
        context.updateLoggers();
        try {
            assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
            assertSame(replacement, config.getLoggers().get(name));
            assertNull(replacement.getExplicitLevel());
            assertSame(appender, replacement.getAppenders().get("Console"));
            assertSame(filter, replacement.getFilter());
            assertFalse(replacement.isAdditive());
        } finally {
            context.reconfigure();
        }
    }

    @Test
    void concurrentUpdates(WebClient client) {
        String name = "test.concurrent";
        String url = PATH + "/" + name + "?backend=log4j2";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writes = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        var status = i % 2 == 0 ? post(client, url, "{\"level\":\"DEBUG\"}")
                                : client.delete().uri(URI.create(url)).request(String.class).status();
                        assertEquals(Status.NO_CONTENT_204, status);
                    }, executor))
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(writes).join();
        }
        assertEquals(Status.NO_CONTENT_204, client.delete().uri(URI.create(url)).request(String.class).status());
        assertFalse(context().getConfiguration().getLoggers().containsKey(name));
    }

    private static LoggerContext context() {
        return (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
    }

    private static Status post(WebClient client, String url, String body) {
        return client.post().uri(URI.create(url))
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body, String.class).status();
    }

    private static JsonObject entry(WebClient client, String url, String name) {
        return client.get().uri(URI.create(url)).requestEntity(JsonObject.class).objectValue(name).orElseThrow();
    }
}
