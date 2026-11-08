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
import java.util.concurrent.Flow;

final class GeminiHttpTransport implements GeminiTransport {
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
        var request = HttpRequest.newBuilder(endpoint).timeout(settings.requestTimeout())
                .header("Content-Type", "application/json").header("x-goog-api-key", settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8)).build();
        try {
            var response = client.send(request, info -> new LimitedBody());
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
            Thread.currentThread().interrupt();
            throw new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,"TRANSPORT_INTERRUPTED");
        } catch (java.net.http.HttpConnectTimeoutException ex) {
            throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_CONNECT_TIMEOUT");
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT");
        } catch (IOException ex) {
            // A timeout or a broken connection may happen after Google has accepted/billed the call.
            throw new GeminiCallFailure(true, 503, "AI_GENERATION_UNCERTAIN", 0,
                    oversized(ex)?"TRANSPORT_RESPONSE_TOO_LARGE":"TRANSPORT_IO_FAILURE");
        }
    }

    private static boolean oversized(Throwable failure) {
        for(int i=0;failure!=null && i<8;i++,failure=failure.getCause()) if(failure instanceof ResponseTooLarge) return true;
        return false;
    }
    private static final class ResponseTooLarge extends IOException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                received += item.remaining();
                if (received > 131072) {
                    subscription.cancel();
                    delegate.onError(new ResponseTooLarge());
                    return;
                }
            }
            delegate.onNext(items);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
