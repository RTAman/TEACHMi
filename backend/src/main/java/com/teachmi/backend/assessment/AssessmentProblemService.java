package com.teachmi.backend.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ProblemViews.ProblemSetView;
import com.teachmi.backend.assessment.ProblemViews.ProblemView;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningAssessmentAccess;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class AssessmentProblemService {
    private final LearningSessionService sessions;
    private final AssessmentProblemRepository problems;
    private final LearningAssessmentAccess access;
    private final ObjectProvider<ProblemGenerator> generators;
    private final TransactionTemplate transaction;

    AssessmentProblemService(LearningSessionService sessions, AssessmentProblemRepository problems,
                             LearningAssessmentAccess access, ObjectProvider<ProblemGenerator> generators,
                             TransactionTemplate transaction) {
        this.sessions = sessions; this.problems = problems; this.access = access;
        this.generators = generators; this.transaction = transaction;
    }

    ProblemSetView start(String memberId, UUID sessionId) {
        var session = sessions.get(memberId, sessionId);
        var existing = load(sessionId, memberId);
        if (!existing.isEmpty()) return view(sessionId, existing);
        requireReady(session);
        var generator = generators.getIfAvailable();
        if (generator == null) {
            throw new ApiException(503, "ASSESSMENT_GENERATION_PENDING", "실제 원문 기반 문제 생성기 연결이 필요합니다.");
        }
        // Generation stays outside DB locks. The current implementation is explicitly demo-only.
        var drafts = generator.generate(memberId, session);
        if (drafts == null || drafts.size() != 2 || drafts.stream().anyMatch(this::invalid)) {
            throw new ApiException(502, "INVALID_PROBLEM_SET", "문항을 만들지 못했습니다. 다시 시도하세요.");
        }
        return transaction.execute(status -> {
            access.lockReady(memberId, sessionId, session.version());
            var saved = load(sessionId, memberId);
            if (!saved.isEmpty()) return view(sessionId, saved);
            var created = new ArrayList<AssessmentProblem>();
            for (int index = 0; index < drafts.size(); index++) {
                var draft = drafts.get(index);
                // 원문에 근거하지 않은 임시 문항은 빈 배열을 저장한다. 출처를 지어내지 않는다.
                created.add(new AssessmentProblem(sessionId, memberId, index + 1, draft.question(),
                        draft.changedConditions(), draft.sourcesJson(), draft.expectedAnswer(), draft.solution(),
                        draft.gradingCriteria(), session.version()));
            }
            problems.saveAllAndFlush(created);
            return view(sessionId, created);
        });
    }

    ProblemView get(String memberId, UUID sessionId, UUID problemId) {
        sessions.get(memberId, sessionId);
        var problem = problems.findByIdAndSessionIdAndMemberId(problemId, sessionId, memberId)
                .orElseThrow(AssessmentProblemService::notFound);
        return view(problem, (int) problems.countBySessionIdAndMemberId(sessionId, memberId));
    }

    static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "문항을 찾을 수 없습니다.");
    }

    private boolean invalid(ProblemGenerator.Draft draft) {
        return draft == null || List.of(draft.question() == null ? "" : draft.question(),
                draft.changedConditions() == null ? "" : draft.changedConditions(),
                draft.expectedAnswer() == null ? "" : draft.expectedAnswer(),
                draft.solution() == null ? "" : draft.solution(),
                draft.gradingCriteria() == null ? "" : draft.gradingCriteria()).stream().anyMatch(String::isBlank);
    }

    private void requireReady(SessionView session) {
        if (!session.status().equals("active") || !session.stage().equals("problem")
                || session.finalExplanationId() == null || session.pendingTurn() != null
                || session.activeReteach() != null || session.problemReturn() != null) {
            throw new ApiException(409, "PROBLEMS_NOT_READY", "설명을 마친 뒤 문제를 시작할 수 있습니다.");
        }
    }

    private List<AssessmentProblem> load(UUID sessionId, String memberId) {
        return problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId);
    }
    private ProblemSetView view(UUID sessionId, List<AssessmentProblem> saved) {
        return new ProblemSetView(sessionId, saved.size(), saved.stream().map(item -> view(item, saved.size())).toList());
    }
    private ProblemView view(AssessmentProblem problem, int total) {
        return new ProblemView(problem.id, problem.sequence, total, problem.question);
    }
}