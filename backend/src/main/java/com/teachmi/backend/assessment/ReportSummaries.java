package com.teachmi.backend.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ReportViews.ReportContent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 홈(home 패키지)이 쓰는 최근 리포트 요약. 리포트 엔티티와 정답은 assessment 패키지 밖으로 내보내지 않는다. */
@Service
public class ReportSummaries {
    private final AssessmentReportRepository reports;
    private final ObjectMapper json;

    ReportSummaries(AssessmentReportRepository reports, ObjectMapper json) {
        this.reports = reports;
        this.json = json;
    }

    /** status는 ready(확인 전) 또는 confirmed(확인 후 학습 종료)다. */
    public record Summary(UUID reportId, UUID sessionId, String conceptName, String status, Instant createdAt,
                          int independentCorrect, int total, String path) {}

    /** 현재 회원의 최근 리포트를 최신순으로 최대 5개 돌려준다. */
    public List<Summary> recent(String memberId) {
        return reports.findTop5ByMemberIdOrderByCreatedAtDesc(memberId).stream().map(report -> {
            var content = json.readValue(report.content, ReportContent.class);
            return new Summary(report.id, report.sessionId, content.concept().name(),
                    report.confirmedAt == null ? "ready" : "confirmed", report.createdAt,
                    content.application().independentCorrect(), content.application().total(),
                    "/api/v1/sessions/" + report.sessionId + "/report");
        }).toList();
    }
}