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

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.log.LogObserver;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ServerTest
class Log4j2SecurityTest {
    @SetUpServer
    static void setup(WebServerConfig.Builder server) {
        server.featuresDiscoverServices(false)
                .addFeature(ObserveFeature.just(LogObserver.builder().permitAll(false).build()));
    }

    @Test
    void protectedLogger(WebClient client) {
        var app = LoggerFactory.getLogger("test.protected");
        String path = "/observe/log/loggers/" + app.getName() + "?backend=log4j2";
        assertEquals(Status.FORBIDDEN_403, client.get().uri(URI.create(path)).request(String.class).status());
        assertEquals(Status.FORBIDDEN_403, client.post().uri(URI.create(path))
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .submit("{\"level\":\"DEBUG\"}", String.class).status());
        assertEquals(Status.FORBIDDEN_403, client.delete().uri(URI.create(path)).request(String.class).status());
        assertFalse(app.isDebugEnabled());
    }
}
