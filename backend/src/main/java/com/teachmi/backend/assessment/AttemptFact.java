package com.teachmi.backend.assessment;

import java.util.UUID;

/**
 * 저장된 하나의 풀이 시도이다.
 * 채점 결과(judgement)는 정답(correct), 오답(wrong),
 * 검토 대기(pending_review) 또는 채점을 재시도할 수 있는 동안의 null이다.
 */
public record AttemptFact(int attemptNo, String judgement, UUID reteachId, boolean afterSolution) {}