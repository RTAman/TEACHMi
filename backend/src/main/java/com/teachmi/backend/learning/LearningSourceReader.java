package com.teachmi.backend.learning;

import java.util.List;

/**
 * A must verify the active member owns the material, the concept belongs to that material,
 * and every requested chunk belongs to the selected concept before returning raw text.
 * Missing/inaccessible data must throw LearningException.notFound().
 */
public interface LearningSourceReader {
    OwnedSource readOwned(String memberId, String materialId, String conceptId, List<String> chunkIds);

    record Chunk(String id, String text) {}
    record OwnedSource(String memberId, String materialId, String conceptId, List<Chunk> chunks) {}
}
