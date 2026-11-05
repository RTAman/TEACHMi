package com.teachmi.backend.learning.gemini;

/** Replace this bean in tests. Production requests only go to Google's fixed HTTPS endpoint. */
interface GeminiTransport {
    String generate(String requestJson);
}
