package com.teachmi.backend.assessment;

import java.util.UUID;
import com.teachmi.backend.assessment.RelearnViews.RelearnRequest;
import com.teachmi.backend.assessment.RelearnViews.RelearnView;
import com.teachmi.backend.assessment.ReportViews.NextStep;
import com.teachmi.backend.assessment.ReportViews.ReportContent;
import com.teachmi.backend.auth.ApiException;
import com.teachmi.backend.learning.CreateSessionRequest;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import com.teachmi.backend.learning.LearningCommands;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Prepare immutable report choices, then atomically create B + C link + the operation's receipt. */
@Service
class AssessmentRelearnService {
    private final LearningSessionService sessions;
    private final AssessmentReportRepository reports;
    private final AssessmentRelearnRepository relearns;
    private final ObjectMapper json;
    private final LearningCommands commands;

    AssessmentRelearnService(LearningSessionService sessions, AssessmentReportRepository reports,
                             AssessmentRelearnRepository relearns, ObjectMapper json, LearningCommands commands) {
        this.sessions=sessions;this.reports=reports;this.relearns=relearns;this.json=json;this.commands=commands;
    }
    private record Fingerprint(UUID reportId, RelearnRequest request) {}

    RelearnView start(String memberId, UUID originId, String key, RelearnRequest body) {
        commands.validateKey(key);
        var request=body==null?new RelearnRequest(null,null,null):body;
        if ((request.replaceSessionId()==null)!=(request.expectedVersion()==null)) {
            throw invalid("새 시작에는 replaceSessionId와 expectedVersion을 함께 보내세요.");
        }
        sessions.get(memberId,originId);
        var report=reports.findBySessionIdAndMemberId(originId,memberId).orElseThrow(AssessmentReportService::notReady);
        if (report.confirmedAt==null) throw unconfirmed();
        var content=json.readValue(report.content,ReportContent.class);
        String focus=request.problemId()==null?null:content.nextSteps().stream()
                .filter(step -> step.problemId().equals(request.problemId())).map(NextStep::focus).findFirst()
                .orElseThrow(() -> invalid("리포트의 보완 항목에 있는 문항을 고르세요."));
        var fingerprint=json.writeValueAsString(new Fingerprint(report.id,request));
        return commands.execute(memberId,originId,key,"c/relearn",fingerprint,RelearnView.class,origin -> {
            var current=reports.findByIdAndSessionIdAndMemberId(report.id,originId,memberId)
                    .orElseThrow(AssessmentReportService::notReady);
            if (current.confirmedAt==null || !origin.status().equals("completed")) throw unconfirmed();
            var created=sessions.createInCommand(memberId,new CreateSessionRequest(origin.materialId(),
                    origin.conceptId(),request.replaceSessionId(),request.expectedVersion()));
            var link=relearns.saveAndFlush(new AssessmentRelearn(created.sessionId(),current,request.problemId(),focus));
            return view(created,link);
        });
    }
    private static RelearnView view(SessionView session,AssessmentRelearn link) {
        return new RelearnView(session,link.originSessionId,link.reportId,link.problemId,link.focus);
    }
    private static ApiException unconfirmed() {
        return new ApiException(409,"REPORT_NOT_CONFIRMED","리포트를 확인한 뒤 다시 학습할 수 있습니다.");
    }
    private static ApiException invalid(String message) { return new ApiException(422,"INVALID_REQUEST",message); }
}
