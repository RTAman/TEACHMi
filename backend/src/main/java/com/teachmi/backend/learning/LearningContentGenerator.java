package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;

/**
 * B supplies a Spring bean backed by the chosen AI provider.
 * Resolve the authorized source text using the context; return evidence IDs from the snapshot.
 */
public interface LearningContentGenerator {
    Summary summarize(Context context);
    Question openingQuestion(Context context);

    record Context(String memberId, UUID sessionId, String materialId, String conceptId, String mode,
                   ConceptSource.Snapshot concept, Summary summary) {}

    record Summary(String text, List<String> examples, List<String> keyTerms,
                   String reflectionPrompt, List<String> sourceChunkIds) {
        public Summary {
            requireText(text);
            requireText(reflectionPrompt);
            examples = requireTexts(examples);
            keyTerms = requireTexts(keyTerms);
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }
    }

    record Question(String text, List<String> sourceChunkIds) {
        public Question {
            requireText(text);
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Generated content must not be blank");
        }
    }

    private static List<String> requireTexts(List<String> values) {
        var copy = List.copyOf(values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Generated examples and terms are required");
        }
        copy.forEach(LearningContentGenerator::requireText);
        return copy;
    }
}
