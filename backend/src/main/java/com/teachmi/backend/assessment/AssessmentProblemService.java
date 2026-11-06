package com.teachmi.backend.assessment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ProblemViews.ProblemSetView;
import com.teachmi.backend.assessment.ProblemViews.ProblemView;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class AssessmentProblemService {
    private final LearningSessionService sessions;
    private final AssessmentProblemRepository problems;
    private final ProblemGenerator generator;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    AssessmentProblemService(LearningSessionService sessions, AssessmentProblemRepository problems,
                             ProblemGenerator generator, TransactionTemplate transaction, ObjectMapper json) {
        this.sessions = sessions;
        this.problems = problems;
        this.generator = generator;
        this.transaction = transaction;
        this.json = json;
    }

    /** 문항을 한 번만 고정한다. 다시 호출하면 저장된 문항을 그대로 돌려준다. */
    ProblemSetView start(String memberId, UUID sessionId) {
        // 세션이 없거나 다른 회원의 세션이면 B 서비스가 404를 낸다.
        var session = sessions.get(memberId, sessionId);
        var existing = load(sessionId, memberId);
        if (!existing.isEmpty()) {
            return view(sessionId, existing);
        }
        requireReady(session);
        var drafts = generator.generate(session);
        if (drafts.isEmpty() || drafts.size() > 2) {
            throw new ApiException(502, "INVALID_PROBLEM_SET", "문항을 만들지 못했습니다. 다시 시도하세요.");
        }
        var sources = json.writeValueAsString(session.concept().sourceChunkIds());
        var created = new ArrayList<AssessmentProblem>();
        for (int index = 0; index < drafts.size(); index++) {
            var draft = drafts.get(index);
            created.add(new AssessmentProblem(sessionId, memberId, index + 1, draft.question(),
                    draft.changedConditions(), sources, draft.expectedAnswer(), draft.solution(),
                    draft.gradingCriteria(), session.version()));
        }
        try {
            transaction.executeWithoutResult(status -> problems.saveAllAndFlush(created));
        } catch (DataIntegrityViolationException concurrent) {
            // 동시에 들어온 다른 요청이 먼저 저장했다. 아래에서 그 문항을 읽어 돌려준다.
        }
        return view(sessionId, load(sessionId, memberId));
    }

    ProblemView get(String memberId, UUID sessionId, UUID problemId) {
        var problem = problems.findByIdAndSessionIdAndMemberId(problemId, sessionId, memberId)
                .orElseThrow(AssessmentProblemService::notFound);
        return view(problem, (int) problems.countBySessionIdAndMemberId(sessionId, memberId));
    }

    static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "문항을 찾을 수 없습니다.");
    }

    private void requireReady(SessionView session) {
        boolean ready = session.status().equals("active") && session.stage().equals("problem")
                && session.finalExplanationId() != null && session.pendingTurn() == null
                && session.activeReteach() == null;
        if (!ready) {
            throw new ApiException(409, "PROBLEMS_NOT_READY", "설명을 마친 뒤 문제를 시작할 수 있습니다.");
        }
    }

    private List<AssessmentProblem> load(UUID sessionId, String memberId) {
        return problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId);
    }

    private ProblemSetView view(UUID sessionId, List<AssessmentProblem> saved) {
        int total = saved.size();
        return new ProblemSetView(sessionId, total, saved.stream().map(item -> view(item, total)).toList());
    }

    private ProblemView view(AssessmentProblem problem, int total) {
        return new ProblemView(problem.id, problem.sequence, total, problem.question);
    }
}