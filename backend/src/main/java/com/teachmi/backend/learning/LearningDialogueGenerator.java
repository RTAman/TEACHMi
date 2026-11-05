package com.teachmi.backend.learning;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

public interface LearningDialogueGenerator {
    Reply reply(Context context);

    record Context(LearningContentGenerator.Context learning, UUID turnId, String chatStage,
                   String kind, String text, LearningMessageView targetQuestion, List<LearningMessageView> messages,
                   @JsonInclude(JsonInclude.Include.NON_NULL) ReteachFocus reteach) {
        public Context { messages = List.copyOf(messages); }
        public Context(LearningContentGenerator.Context learning, UUID turnId, String chatStage, String kind,
                       String text, LearningMessageView targetQuestion, List<LearningMessageView> messages) {
            this(learning, turnId, chatStage, kind, text, targetQuestion, messages, null);
        }
    }

    /** accepted means sufficient evidence for the CURRENT stage; the server decides the next stage. */
    record Reply(String text, boolean accepted, List<String> sourceChunkIds) {
        public Reply {
            if (text == null || text.isBlank() || text.length() > 12000) {
                throw new IllegalArgumentException("A bounded dialogue reply is required");
            }
            sourceChunkIds = List.copyOf(sourceChunkIds);
        }
    }
}
