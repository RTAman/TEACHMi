package com.teachmi.backend.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ReportViews.Application;
import com.teachmi.backend.assessment.ReportViews.AttemptResult;
import com.teachmi.backend.assessment.ReportViews.Concept;
import com.teachmi.backend.assessment.ReportViews.Explanation;
import com.teachmi.backend.assessment.ReportViews.NextStep;
import com.teachmi.backend.assessment.ReportViews.ProblemResult;
import com.teachmi.backend.assessment.ReportViews.Quote;
import com.teachmi.backend.assessment.ReportViews.ReportContent;
import com.teachmi.backend.assessment.ReportViews.ReportView;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.AssessmentTransitionRequest;
import com.teachmi.backend.learning.LearningAssessmentService;
import com.teachmi.backend.learning.LearningEvidenceService;
import com.teachmi.backend.learning.LearningEvidenceView;
import com.teachmi.backend.learning.LearningException;
import com.teachmi.backend.learning.LearningMessageView;
import com.teachmi.backend.learning.LearningSessionService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 리포트 준비(publish)와 학습 종료(confirm)를 B의 상태 전환과 함께 저장한다.
 * 계산은 B 트랜잭션 밖에서 끝내고, B의 콜백 안에서는 DB 저장만 한다.
 */
@Service
class AssessmentReportService {
    private final LearningSessionService sessions;
    private final LearningEvidenceService evidence;
    private final LearningAssessmentService transitions;
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentReportRepository reports;
    private final ObjectMapper json;

    AssessmentReportService(LearningSessionService sessions, LearningEvidenceService evidence,
                            LearningAssessmentService transitions, AssessmentProblemRepository problems,
                            AssessmentAttemptRepository attempts, AssessmentReportRepository reports, ObjectMapper json) {
        this.sessions = sessions;
        this.evidence = evidence;
        this.transitions = transitions;
        this.problems = problems;
        this.attempts = attempts;
        this.reports = reports;
        this.json = json;
    }

    /** 모든 문항을 마친 학습의 리포트를 한 번만 만든다. 이미 있으면 저장된 리포트를 그대로 돌려준다. */
    ReportView publish(String memberId, UUID sessionId, String key) {
        sessions.get(memberId, sessionId);
        var existing = reports.findBySessionIdAndMemberId(sessionId, memberId);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        // B 근거는 커밋된 스냅샷으로 읽는다. 이 version으로 만든 리포트만 저장할 수 있다.
        var snapshot = evidence.get(memberId, sessionId);
        var saved = problems.findBySessionIdAndMemberIdOrderBySequence(sessionId, memberId);
        var histories = saved.stream().map(problem -> attempts.findByProblemIdOrderByAttemptNo(problem.id)).toList();
        boolean finished = !saved.isEmpty();
        for (int index = 0; index < saved.size(); index++) {
            finished &= AssessmentProgress.finished(saved.get(index), histories.get(index));
        }
        if (!finished) {
            throw new ApiException(409, "ASSESSMENT_NOT_READY", "모든 문항을 마친 뒤 리포트를 만들 수 있습니다.");
        }
        if (saved.stream().anyMatch(problem -> problem.evidenceVersion != snapshot.version())) {
            throw stale();
        }
        var content = build(snapshot, saved, histories);
        var report = new AssessmentReport(sessionId, memberId, snapshot.version(), json.writeValueAsString(content));
        try {
            transitions.publishReport(memberId, sessionId, key,
                    new AssessmentTransitionRequest(report.id.toString(), snapshot.version()), locked -> {
                        // 계산에 쓴 근거와 잠근 시점의 세션이 다르면 저장하지 않는다.
                        if (locked.version() != snapshot.version()) {
                            throw stale();
                        }
                        reports.saveAndFlush(report);
                    });
        } catch (LearningException conflict) {
            // 같은 학습의 다른 요청이 먼저 리포트를 만들었다면 그 리포트를 돌려준다.
            return reports.findBySessionIdAndMemberId(sessionId, memberId).map(this::view).orElseThrow(() -> conflict);
        }
        return view(report);
    }

    ReportView get(String memberId, UUID sessionId) {
        sessions.get(memberId, sessionId);
        return reports.findBySessionIdAndMemberId(sessionId, memberId).map(this::view)
                .orElseThrow(AssessmentReportService::notReady);
    }

    /** 학생이 리포트를 확인하면 학습을 종료한다. 종료된 학습은 홈의 이어하기 대상에서 빠진다. */
    ReportView confirm(String memberId, UUID sessionId, String key) {
        var session = sessions.get(memberId, sessionId);
        var report = reports.findBySessionIdAndMemberId(sessionId, memberId)
                .orElseThrow(AssessmentReportService::notReady);
        if (session.status().equals("completed")) {
            return view(report);
        }
        try {
            transitions.completeLearning(memberId, sessionId, key,
                    new AssessmentTransitionRequest(report.id.toString(), session.version()), locked -> {
                        var managed = reports.findById(report.id).orElseThrow(AssessmentReportService::notReady);
                        if (managed.confirmedAt == null) {
                            managed.confirmedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                            reports.saveAndFlush(managed);
                        }
                    });
        } catch (LearningException conflict) {
            // 다른 요청이 먼저 종료했다면 성공으로 본다.
            if (!sessions.get(memberId, sessionId).status().equals("completed")) {
                throw conflict;
            }
        }
        return view(reports.findById(report.id).orElseThrow(AssessmentReportService::notReady));
    }

    private ReportContent build(LearningEvidenceView snapshot, List<AssessmentProblem> saved,
                                List<List<AssessmentAttempt>> histories) {
        var original = snapshot.originalLearning().evidence();
        var delivered = original.support().delivered();
        var explanation = new Explanation(delivered.total() == 0 ? "independent" : "with_support",
                quote(original.initialAnswer()), quote(original.finalExplanation()),
                original.support().requested(), delivered, snapshot.reteachHistory().size());

        var facts = histories.stream().map(history -> history.stream()
                .map(attempt -> new AttemptFact(attempt.attemptNo, attempt.judgement, attempt.reteachId,
                        attempt.afterSolution)).toList()).toList();
        var independent = IndependentApplication.of(facts);
        var results = new ArrayList<ProblemResult>();
        var nextSteps = new ArrayList<NextStep>();
        for (int index = 0; index < saved.size(); index++) {
            var problem = saved.get(index);
            var history = histories.get(index);
            var outcome = independent.outcomes().get(index);
            results.add(new ProblemResult(problem.id, problem.sequence, problem.question, outcome,
                    problem.solutionViewedAt != null, history.stream().map(attempt -> new AttemptResult(attempt.id,
                    attempt.attemptNo, attempt.judgement, attempt.reteachId)).toList()));
            // 첫 시도에 맞히지 못한 문항은 처음 오답에서 받은 보완 개념을 다음 학습 거리로 남긴다.
            if (outcome != ProblemOutcome.FIRST_TRY_CORRECT && outcome != ProblemOutcome.PENDING_REVIEW) {
                history.stream().filter(attempt -> attempt.reteachFocus != null).findFirst()
                        .ifPresent(attempt -> nextSteps.add(new NextStep(problem.id, attempt.reteachFocus)));
            }
        }
        var application = new Application(independent.independentCorrect(), independent.total(),
                independent.pendingReview(), results);
        return new ReportContent(snapshot.schemaVersion(),
                new Concept(snapshot.concept().name(), snapshot.concept().scope()), explanation, application, nextSteps);
    }

    private ReportView view(AssessmentReport report) {
        var content = json.readValue(report.content, ReportContent.class);
        return new ReportView(report.id, report.sessionId, report.confirmedAt == null ? "ready" : "confirmed",
                report.createdAt, report.confirmedAt, content.concept(), content.explanation(),
                content.application(), content.nextSteps());
    }

    private static Quote quote(LearningMessageView message) {
        return message == null ? null : new Quote(message.messageId(), message.text());
    }

    private static ApiException stale() {
        return new ApiException(409, "ASSESSMENT_EVIDENCE_STALE", "문항이 사용한 학습 근거 버전이 변경됐습니다.");
    }

    static ApiException notReady() {
        return new ApiException(409, "REPORT_NOT_READY", "아직 리포트가 없습니다. 문항을 모두 마친 뒤 만드세요.");
    }
}