package com.teachmi.backend.ai;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** B-owned grounded JSON generation. No HTTP endpoint; callers prepare outside write/lock transactions. */
public interface GeminiGenerationPort {
    <T> T generate(Request request, Validator<T> validator);

    record Scope(String memberId, UUID sessionId, String materialId, String conceptId, List<String> sourceChunkIds) {
        public Scope { sourceChunkIds = sourceChunkIds == null ? List.of() : List.copyOf(sourceChunkIds); }
        @Override public String toString() { return "Scope[redacted]"; }
    }

    /** schema describes domain fields only; sourceChunkIds/evidence are added and checked by the port. */
    record Request(Scope scope, String operation, String promptVersion, String instruction, JsonNode input, JsonNode schema) {
        public Request {
            input = input == null ? null : input.deepCopy();
            schema = schema == null ? null : schema.deepCopy();
        }
        @Override public JsonNode input() { return input == null ? null : input.deepCopy(); }
        @Override public JsonNode schema() { return schema == null ? null : schema.deepCopy(); }
        @Override public String toString() { return "GeminiRequest[redacted]"; }
    }

    /** Domain validation runs before success is cached, and again on cache hits. Must not write or call AI. */
    @FunctionalInterface interface Validator<T> { T validate(JsonNode response); }
}
