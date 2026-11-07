package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.learning.AssessmentStateSource;
import com.teachmi.backend.learning.LearningException;
import org.springframework.stereotype.Component;

/**
 * B가 리포트 준비·학습 종료 전환 직전에 C의 실제 저장 기록을 확인하는 연결 부품.
 * B의 트랜잭션 안에서 호출되므로 방금 저장한 리포트도 보인다.
 */
@Component
class AssessmentReportStateSource implements AssessmentStateSource {
    private final AssessmentReportRepository reports;
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;

    AssessmentReportStateSource(AssessmentReportRepository reports, AssessmentProblemRepository problems,
                                AssessmentAttemptRepository attempts) {
        this.reports = reports;
        this.problems = problems;
        this.attempts = attempts;
    }

    @Override
    public State loadOwnedReport(String memberId, UUID sessionId, String reportId) {
        var report = reports.findByIdAndSessionIdAndMemberId(uuid(reportId), sessionId, memberId)
                .orElseThrow(LearningException::notFound);
        var saved = problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId);
        boolean complete = !saved.isEmpty() && saved.stream().allMatch(problem ->
                AssessmentProgress.finished(problem, attempts.findByProblemIdOrderByAttemptNo(problem.id)));
        return new State(memberId, sessionId, reportId, report.revision, report.evidenceVersion, complete,
                !report.content.isBlank(), report.confirmedAt != null);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw LearningException.notFound();
        }
    }
}