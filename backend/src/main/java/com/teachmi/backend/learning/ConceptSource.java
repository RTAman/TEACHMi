package com.teachmi.backend.learning;

import java.util.List;

/**
 * A implements this port after verifying that the material and concept belong to the member.
 * Throw LearningException.notFound() for missing or inaccessible data.
 */
public interface ConceptSource {
    Snapshot loadOwnedConcept(String memberId, String materialId, String conceptId);

    record Snapshot(String name, String scope, List<String> sourceChunkIds) {
        public Snapshot {
            if (name == null || name.isBlank() || scope == null || scope.isBlank()) {
                throw new IllegalArgumentException("Concept name and scope are required");
            }
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }
    }
}
