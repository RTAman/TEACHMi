package com.teachmi.backend.learning;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LearningTurnService {
    private final LearningTurnTransactions transactions;
    private final ObjectProvider<LearningDialogueGenerator> generators;

    LearningTurnService(LearningTurnTransactions transactions, ObjectProvider<LearningDialogueGenerator> generators) {
        this.transactions = transactions;
        this.generators = generators;
    }

    /** Deliberately outside a DB transaction while the external model is running. */
    public SessionView send(String member, UUID sessionId, String key, TurnRequest request) {
        var claim = transactions.begin(member, sessionId, key, request);
        if (claim.replay() != null) return claim.replay();
        try {
            var generator = generators.getIfAvailable();
            if (generator == null) {
                throw new LearningException(503, "LEARNING_DIALOGUE_UNAVAILABLE", "대화 생성 연결이 필요합니다.");
            }
            var reply = java.util.Objects.requireNonNull(generator.reply(claim.context()));
            return transactions.complete(claim, reply);
        } catch (LearningException ex) {
            var current = transactions.fail(claim, ex.code());
            throw ex.withSession(current);
        } catch (RuntimeException ex) {
            var current = transactions.fail(claim, "DIALOGUE_GENERATION_FAILED");
            throw new LearningException(502, "DIALOGUE_GENERATION_FAILED", "답변을 저장했습니다. 같은 요청으로 다시 시도하세요.", current);
        }
    }
}
