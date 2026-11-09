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
import com.teachmi.backend.assessment.ReportViews.Flow;
import com.teachmi.backend.assessment.ReportViews.KeyPoints;
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
import com.teachmi.backend.learning.LearningHesitationService;
import com.teachmi.backend.learning.LearningMessageView;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.LearningCommands;
import com.teachmi.backend.learning.SessionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 리포트 준비(publish)와 학습 종료(confirm)를 B의 상태 전환과 함께 저장한다.
 * 계산은 B 트랜잭션 밖에서 끝내고, B의 콜백 안에서는 DB 저장만 한다.
 */
@Service
class AssessmentReportService {
    private static final Logger log = LoggerFactory.getLogger(AssessmentReportService.class);
    private final LearningSessionService sessions;
    private final LearningEvidenceService evidence;
    private final LearningAssessmentService transitions;
    private final AssessmentProblemRepository problems;
    private final AssessmentAttemptRepository attempts;
    private final AssessmentReportRepository reports;
    private final ObjectMapper json;
    private final LearningCommands commands;
    private final ReportComparisons comparisons;
    private final LearningHesitationService hesitations;
    private final ObjectProvider<KeyPointEvaluator> keyPointEvaluators;

    AssessmentReportService(LearningSessionService sessions, LearningEvidenceService evidence,
                            LearningAssessmentService transitions, AssessmentProblemRepository problems,
                            AssessmentAttemptRepository attempts, AssessmentReportRepository reports, ObjectMapper json,
                            LearningCommands commands, ReportComparisons comparisons,
                            LearningHesitationService hesitations, ObjectProvider<KeyPointEvaluator> keyPointEvaluators) {
        this.sessions = sessions;
        this.evidence = evidence;
        this.transitions = transitions;
        this.problems = problems;
        this.attempts = attempts;
        this.reports = reports;
        this.json = json;
        this.commands = commands;
        this.comparisons = comparisons;
        this.hesitations = hesitations;
        this.keyPointEvaluators = keyPointEvaluators;
    }

    /** 모든 문항을 마친 학습의 리포트를 한 번만 만든다. 이미 있으면 저장된 리포트를 그대로 돌려준다. */
    ReportView publish(String memberId, UUID sessionId, String key) {
        commands.validateKey(key);
        sessions.get(memberId, sessionId);
        var prepared = prepareIfAbsent(memberId, sessionId);
        return commands.execute(memberId,sessionId,key,"c/report/publish","",ReportView.class, locked -> {
            var existing=reports.findBySessionIdAndMemberId(sessionId,memberId);
            if (existing.isPresent()) return view(existing.get());
            if (prepared == null) throw notReady();
            if (locked.version() != prepared.evidenceVersion) throw stale();
            transitions.publishPreparedReport(memberId,sessionId,
                    new AssessmentTransitionRequest(prepared.id.toString(),prepared.evidenceVersion),
                    current -> reports.saveAndFlush(prepared));
            return view(prepared);
        });
    }

    private AssessmentReport prepareIfAbsent(String memberId, UUID sessionId) {
        if (reports.findBySessionIdAndMemberId(sessionId,memberId).isPresent()) return null;
        try { return prepare(memberId,sessionId); }
        catch (ApiException conflict) {
            // Another publisher may commit between our absence check and the evidence read.
            // Still execute the command below, so required keys and operation reuse are validated.
            if (conflict.code().equals("ASSESSMENT_EVIDENCE_STALE")
                    && reports.findBySessionIdAndMemberId(sessionId,memberId).isPresent()) return null;
            throw conflict;
        }
    }

    private AssessmentReport prepare(String memberId, UUID sessionId) {
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
        // 처음 학습 대화는 problem 단계부터 바뀌지 않으므로 근거 스냅샷과 따로 읽어도 같은 내용이다.
        var state = sessions.get(memberId, sessionId);
        var original = snapshot.originalLearning().evidence();
        var content = build(snapshot, saved, histories,
                keyPoints(memberId, state, original.initialAnswer(), original.finalExplanation()),
                ReportFlow.of(state.messages(), hesitations.targetMessageIds(sessionId)));
        return new AssessmentReport(sessionId, memberId, snapshot.version(), json.writeValueAsString(content));
    }

    ReportView get(String memberId, UUID sessionId) {
        sessions.get(memberId, sessionId);
        return reports.findBySessionIdAndMemberId(sessionId, memberId).map(this::view)
                .orElseThrow(AssessmentReportService::notReady);
    }

    /** 학생이 리포트를 확인하면 학습을 종료한다. 종료된 학습은 홈의 이어하기 대상에서 빠진다. */
    ReportView confirm(String memberId, UUID sessionId, String key) {
        return commands.execute(memberId,sessionId,key,"c/report/confirm","",ReportView.class, session -> {
            var report=reports.findBySessionIdAndMemberId(sessionId,memberId)
                    .orElseThrow(AssessmentReportService::notReady);
            if (!session.status().equals("completed")) {
                transitions.completePreparedLearning(memberId,sessionId,
                        new AssessmentTransitionRequest(report.id.toString(),session.version()), locked -> {
                            report.confirmedAt=Instant.now().truncatedTo(ChronoUnit.MICROS);
                            reports.saveAndFlush(report);
                        });
            }
            return view(report);
        });
    }

    /** AI 판정은 리포트의 부가 정보다. 연결되지 않았거나 실패하면 null로 두고 리포트는 그대로 만든다. */
    private KeyPoints keyPoints(String memberId, SessionView state, LearningMessageView initial,
                                LearningMessageView last) {
        var evaluator = keyPointEvaluators.getIfAvailable();
        if (evaluator == null || initial == null || last == null) return null;
        try {
            return evaluator.evaluate(new KeyPointEvaluator.Input(memberId, state, initial.text(), last.text()));
        } catch (RuntimeException failure) {
            log.warn("Key point evaluation skipped for report: {}", failure.getClass().getSimpleName());
            return null;
        }
    }

    private ReportContent build(LearningEvidenceView snapshot, List<AssessmentProblem> saved,
                                List<List<AssessmentAttempt>> histories, KeyPoints keyPoints, Flow flow) {
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
                new Concept(snapshot.concept().name(), snapshot.concept().scope()), explanation, application, nextSteps,
                keyPoints, flow);
    }

    private ReportView view(AssessmentReport report) {
        var content = json.readValue(report.content, ReportContent.class);
        return new ReportView(report.id, report.sessionId, report.confirmedAt == null ? "ready" : "confirmed",
                report.createdAt, report.confirmedAt, content.concept(), content.explanation(),
                content.application(), content.nextSteps(), content.keyPoints(), content.flow(),
                comparisons.of(report.memberId, report.sessionId, content));
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