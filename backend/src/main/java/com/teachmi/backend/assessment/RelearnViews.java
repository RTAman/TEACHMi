package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.learning.SessionView;
import jakarta.validation.constraints.PositiveOrZero;

final class RelearnViews {
    private RelearnViews() {}

    /**
     * problemId는 리포트 nextSteps의 문항 ID다. 비우면 보완 항목 없이 같은 개념을 다시 학습한다.
     * 다른 학습이 진행 중이면 409 ACTIVE_SESSION_EXISTS가 나고, 학생이 새 시작을 확인하면
     * 그 학습의 ID와 version을 replaceSessionId, expectedVersion으로 함께 보낸다.
     */
    record RelearnRequest(UUID problemId, UUID replaceSessionId, @PositiveOrZero Long expectedVersion) {}

    /** session은 새로 만든 B 학습 세션이다. focus는 화면에 보여줄 이번 재학습의 보완 개념이다. */
    record RelearnView(SessionView session, UUID originSessionId, UUID reportId, UUID problemId, String focus) {}
}