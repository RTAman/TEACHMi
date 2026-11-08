package com.teachmi.backend.learning.gemini;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class GeminiHttpTransport implements GeminiTransport {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GeminiHttpTransport.class);
    private final GeminiSettings settings;
    private final HttpClient client;
    private final URI endpoint;

    GeminiHttpTransport(GeminiSettings settings) {
        this(settings, URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                + settings.model() + ":generateContent"));
    }

    // Package-visible endpoint injection is for the local HTTP protocol tests.
    GeminiHttpTransport(GeminiSettings settings, URI endpoint) {
        this.settings = settings;
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String generate(String requestJson) {
        var trace=new Trace();
        try { return send(requestJson,trace); }
        catch(GeminiCallFailure failure) {
            LOG.warn("Gemini transport diagnostic={} phase={} elapsedMs={} httpStatus={} responseBytes={}",
                    GeminiDiagnostics.safe(failure.diagnostic),trace.failurePhase==null?trace.phase:trace.failurePhase,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-trace.started),trace.status,trace.received.get());
            throw failure;
        }
    }

    private String send(String requestJson,Trace trace) {
        var request = HttpRequest.newBuilder(endpoint).timeout(settings.requestTimeout())
                .header("Content-Type", "application/json").header("x-goog-api-key", settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        long deadline=trace.started+settings.requestTimeout().toNanos();
        try {
            pending=client.sendAsync(request,info -> {
                trace.status=info.statusCode();trace.phase=Phase.WAITING_BODY;
                return new LimitedBody(trace);
            });
            // HttpRequest.timeout alone does not bound a stalled body on Java 21.
            var response=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (response.statusCode() == 429) {
                int seconds = response.headers().firstValue("Retry-After").map(value -> {
                    try { return Math.clamp(Integer.parseInt(value), 1, 3600); }
                    catch (NumberFormatException ex) { return 60; }
                }).orElse(60);
                throw new GeminiCallFailure(false, 429, "GEMINI_RATE_LIMITED", seconds);
            }
            if (response.statusCode() >= 500) {
                throw new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,
                        "UPSTREAM_HTTP_" + response.statusCode());
            }
            if (response.statusCode() != 200) {
                String code = switch (response.statusCode()) {
                    case 400 -> "GEMINI_INVALID_REQUEST";
                    case 401, 403 -> "GEMINI_ACCESS_DENIED";
                    case 404 -> "GEMINI_MODEL_NOT_FOUND";
                    default -> "GEMINI_REQUEST_REJECTED";
                };
                throw new GeminiCallFailure(false, 502, code, 60);
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            trace.failurePhase=trace.phase;
            Thread.currentThread().interrupt();
            throw new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,"TRANSPORT_INTERRUPTED");
        } catch (TimeoutException ex) {
            trace.failurePhase=trace.phase;
            throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT");
        } catch (ExecutionException ex) {
            trace.failurePhase=trace.phase;
            var cause=ex.getCause();
            if(cause instanceof java.net.http.HttpConnectTimeoutException)
                throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_CONNECT_TIMEOUT");
            if(cause instanceof java.net.http.HttpTimeoutException)
                throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT");
            // A timeout or a broken connection may happen after Google has accepted/billed the call.
            throw new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,
                    oversized(ex)?"TRANSPORT_RESPONSE_TOO_LARGE":"TRANSPORT_IO_FAILURE");
        } catch(java.util.concurrent.CancellationException ex) {
            trace.failurePhase=trace.phase;
            throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_IO_FAILURE");
        } finally {
            if(pending!=null && !pending.isDone()) {
                // Best-effort local cancellation does not prove that the provider stopped or did not bill.
                pending.cancel(true);
                var subscription=trace.subscription.get();
                if(subscription!=null) subscription.cancel();
            }
        }
    }

    private enum Phase { WAITING_HEADERS,WAITING_BODY,READING_BODY,BODY_COMPLETE }
    private static final class Trace {
        final long started=System.nanoTime();
        final AtomicInteger received=new AtomicInteger();
        final AtomicReference<Flow.Subscription> subscription=new AtomicReference<>();
        volatile Phase phase=Phase.WAITING_HEADERS,failurePhase;
        volatile int status; // 0 means no response headers, never a fabricated provider status.
    }

    private static boolean oversized(Throwable failure) {
        for(int i=0;failure!=null && i<8;i++,failure=failure.getCause()) if(failure instanceof ResponseTooLarge) return true;
        return false;
    }
    private static final class ResponseTooLarge extends IOException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final Trace trace;
        private Flow.Subscription subscription;
        LimitedBody(Trace trace) {this.trace=trace;}
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            trace.subscription.set(value);
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            trace.phase=Phase.READING_BODY;
            for (var item : items) {
                if (trace.received.addAndGet(item.remaining()) > 131072) {
                    subscription.cancel();
                    delegate.onError(new ResponseTooLarge());
                    return;
                }
            }
            delegate.onNext(items);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() {trace.phase=Phase.BODY_COMPLETE;delegate.onComplete();}
    }
}
