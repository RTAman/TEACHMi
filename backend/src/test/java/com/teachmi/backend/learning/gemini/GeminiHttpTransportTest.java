package com.teachmi.backend.learning.gemini;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.*;

@Timeout(10)
@ExtendWith(OutputCaptureExtension.class)
class GeminiHttpTransportTest {
    HttpServer server;
    ExecutorService executor;
    volatile int status = 200;
    volatile String body = "{}";
    volatile int delayMillis;
    volatile int bodyDelayMillis;
    volatile String retryAfter;
    AtomicReference<String> key = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    AtomicInteger requests=new AtomicInteger();
    CountDownLatch received=new CountDownLatch(1);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();received.countDown();
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            path.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                if(bodyDelayMillis>0 && bytes.length>1) {
                    exchange.getResponseBody().write(bytes,0,1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(bodyDelayMillis);
                    exchange.getResponseBody().write(bytes,1,bytes.length-1);
                } else exchange.getResponseBody().write(bytes);
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

    @Test void timeoutProducesAnUncertainOutcome(CapturedOutput output) {
        delayMillis = 1000;
        var error = catchThrowableOfType(() -> client(Duration.ofMillis(200)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isTrue();
        assertThat(error.diagnostic).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(output.getAll()).contains("phase=WAITING_HEADERS","httpStatus=0").doesNotContain("not-a-real-api-key");
    }

    @Test void serverFailureIsNotBlindlyRetried() {
        status = 503;
        var error = catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isTrue();
        assertThat(error.diagnostic).isEqualTo("UPSTREAM_HTTP_503");
    }

    @Test void deadlineIncludesAStalledBodyAfterHeadersWereReceived(CapturedOutput output) {
        var transport=client(Duration.ofSeconds(1));
        assertThat(transport.generate("{}")).isEqualTo("{}");
        bodyDelayMillis=3000;
        long start=System.nanoTime();
        var error=catchThrowableOfType(() -> transport.generate("private-request-body"),GeminiCallFailure.class);
        assertThat(error).as("A received header must not disable the total response deadline").isNotNull();
        assertThat(error.uncertain).isTrue();
        assertThat(error.diagnostic).isEqualTo("TRANSPORT_REQUEST_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(2500));
        assertThat(output.getAll()).contains("phase=READING_BODY","httpStatus=200","responseBytes=1")
                .doesNotContain("private-request-body","not-a-real-api-key");
        assertThat(requests.get()).isEqualTo(2); // one warmup, one bounded call; no automatic retry
        bodyDelayMillis=0;
        assertThat(transport.generate("{}")).isEqualTo("{}");
        assertThat(requests.get()).isEqualTo(3); // an explicit new call still works after local cancellation
    }

    @Test void interruptionCancelsLocallyAndPreservesTheInterruptFlag() throws Exception {
        delayMillis=2000;var error=new AtomicReference<GeminiCallFailure>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var transport=client(Duration.ofSeconds(4));
        Thread worker=Thread.startVirtualThread(() -> {
            try {transport.generate("{}");}
            catch(GeminiCallFailure failure){error.set(failure);interrupted.set(Thread.currentThread().isInterrupted());}
        });
        assertThat(received.await(2,TimeUnit.SECONDS)).isTrue();worker.interrupt();worker.join(2000);
        assertThat(worker.isAlive()).isFalse();assertThat(error.get()).isNotNull();
        assertThat(error.get().diagnostic).isEqualTo("TRANSPORT_INTERRUPTED");
        assertThat(error.get().uncertain).isTrue();assertThat(interrupted.get()).isTrue();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void rejectsOversizeResponses() {
        body = "x".repeat(140000);
        var failure=catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"),GeminiCallFailure.class);
        assertThat(failure.diagnostic).isEqualTo("TRANSPORT_RESPONSE_TOO_LARGE");
    }

    @Test void rejectedCredentialsDoNotExposeProviderDetails() {
        status = 403;
        body = "key not-a-real-api-key is invalid";
        var error = catchThrowableOfType(() -> client(Duration.ofSeconds(2)).generate("{}"), GeminiCallFailure.class);
        assertThat(error.uncertain).isFalse();
        assertThat(error.getMessage()).isEqualTo("GEMINI_ACCESS_DENIED");
    }
}
