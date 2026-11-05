package com.teachmi.backend.learning;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;

@Service
@Validated
public class LearningReteachService {
    private final LearningReteachTransactions transactions;
    private final ObjectProvider<LearningReteachGenerator> generators;

    LearningReteachService(LearningReteachTransactions transactions, ObjectProvider<LearningReteachGenerator> generators) {
        this.transactions = transactions; this.generators = generators;
    }
    public SessionView start(String member, UUID session, String key, @Valid StartReteachRequest request) {
        var claim = transactions.begin(member, session, key, request);
        if (claim.replay() != null) return claim.replay();
        try {
            var generator = generators.getIfAvailable();
            if (generator == null) throw new LearningException(503, "RETEACH_GENERATOR_UNAVAILABLE", "재설명 생성 연결이 필요합니다.");
            return transactions.complete(claim, java.util.Objects.requireNonNull(generator.opening(claim.context())));
        } catch (LearningException ex) {
            var current = transactions.fail(claim, ex.code());
            throw new LearningException(ex.status, ex.code(), ex.getMessage(), current);
        } catch (RuntimeException ex) {
            var current = transactions.fail(claim, "RETEACH_GENERATION_FAILED");
            throw new LearningException(502, "RETEACH_GENERATION_FAILED", "재설명 질문 생성에 실패했습니다. 같은 요청으로 다시 시도하세요.", current);
        }
    }
    public SessionView acknowledgeProblemReturn(String member, UUID session, UUID reteach, long expectedVersion) {
        return transactions.acknowledgeReturn(member, session, reteach, expectedVersion);
    }
}
