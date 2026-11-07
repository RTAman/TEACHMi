package com.teachmi.backend.assessment;

import java.util.ArrayList;
import java.util.UUID;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.teachmi.backend.assessment.AttemptViews.AttemptView;
import static com.teachmi.backend.assessment.AttemptViews.SubmitRequest;

/**
 * 답안 제출의 진입점. 원격 채점기가 없으면 기존 로컬 채점(submit)을 그대로 쓰고,
 * 있으면 저장(트랜잭션) → 채점(트랜잭션 밖) → 판정 저장(트랜잭션) 3단계로 나눈다.
 * 이 클래스에는 @Transactional을 붙이지 않는다.
 */
@Service
class AssessmentSubmissionService {
    private final AssessmentAttemptService attempts;
    private final LearningSessionService sessions;
    private final ObjectProvider<RemoteAnswerGrader> remotes;
    private final ObjectMapper json;

    AssessmentSubmissionService(AssessmentAttemptService attempts, LearningSessionService sessions,
                                ObjectProvider<RemoteAnswerGrader> remotes, ObjectMapper json) {
        this.attempts = attempts;
        this.sessions = sessions;
        this.remotes = remotes;
        this.json = json;
    }

    AttemptView submit(String member, UUID sessionId, UUID problemId, String key, SubmitRequest request) {
        var remote = remotes.getIfAvailable();
        if (remote == null) {
            return attempts.submit(member, sessionId, problemId, key, request);
        }
        var pending = attempts.recordPending(member, sessionId, problemId, key, request);
        if (pending.done() != null) {
            return pending.done();
        }
        // 1단계가 커밋된 뒤의 세션으로 범위를 만든다. 이 사이에 상태가 바뀌면 3단계에서 거절된다.
        var session = sessions.get(member, sessionId);
        var sources = new ArrayList<String>();
        json.readTree(pending.sourceChunkIds()).forEach(id -> sources.add(id.asText()));
        AnswerGrader.Grade grade;
        try {
            grade = remote.grade(new RemoteAnswerGrader.Input(member, session, pending.attemptId(), pending.question(),
                    pending.expectedAnswer(), pending.solution(), pending.gradingCriteria(), sources,
                    pending.answer(), pending.work()));
        } catch (ApiException | LearningException failure) {
            // 답안은 판정 없이 저장돼 있다. 같은 키와 답안으로 다시 보내면 그 답안을 다시 채점한다.
            throw failure;
        } catch (RuntimeException failure) {
            throw new ApiException(502, "ASSESSMENT_GRADING_FAILED", "채점에 실패했습니다. 같은 키와 답안으로 다시 시도하세요.");
        }
        return attempts.applyGrade(member, sessionId, pending.attemptId(), grade);
    }
}