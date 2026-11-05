package com.teachmi.backend.learning.gemini;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.*;

@Timeout(10)
class GeminiHttpTransportTest {
    HttpServer server;
    ExecutorService executor;
    volatile int status = 200;
    volatile String body = "{}";
    volatile int delayMillis;
    volatile String retryAfter;
    AtomicReference<String> key = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            path.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    GeminiHttpTransport client(Duration timeout) {
        var settings = new GeminiSettings("not-a-real-api-key", "test-model", Duration.ofSeconds(1),
                timeout, 10, 100, 20, 2, 16000, 2048);
        return new GeminiHttpTransport(settings, URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1beta/models/test-model:generateContent"));
    }

    @Test void sendsCredentialsInHeaderAndKeepsThemOutOfUrl() {
        assertThat(client(Duration.ofSeconds(2)).generate("{\"test\":true}")).isEqualTo("{}");
        assertThat(key.get()).isEqualTo("not-a-real-api-key");
        assertThat(path.get()).doesNotContain("api-key", "not-a-real");
        assertThat(requestBody.get()).isEqualTo("{\"test\":true}");
    }

    @Test void honorsAnUpstreamRateLimitWithoutIncludingItsBodyInErrors() {
        status = 429;
        retryAfter = "23";
        body = "sensitive upstream error";
        var error = catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.status).isEqualTo(429);
        assertThat(error.retrySeconds).isEqualTo(23);
        assertThat(error.uncertain).isFalse();
        assertThat(error.getMessage()).doesNotContain(body, "not-a-real-api-key");
    }

    @Test void timeoutProducesAnUncertainOutcome() {
        delayMillis = 1000;
        var error = catchThrowableOfType(() -> client(Duration.ofMillis(200)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isTrue();
    }

    @Test void serverFailureIsNotBlindlyRetried() {
        status = 503;
        var error = catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isTrue();
    }

    @Test void rejectsOversizeResponses() {
        body = "x".repeat(140000);
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).generate("{}")).isInstanceOf(GeminiCallFailure.class);
    }

    @Test void rejectedCredentialsDoNotExposeProviderDetails() {
        status = 403;
        body = "key not-a-real-api-key is invalid";
        var error = catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isFalse();
        assertThat(error.getMessage()).isEqualTo("GEMINI_ACCESS_DENIED");
    }
}
