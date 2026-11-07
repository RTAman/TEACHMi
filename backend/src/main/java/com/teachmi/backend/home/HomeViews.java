package com.teachmi.backend.home;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.teachmi.backend.assessment.ReportSummaries.Summary;

final class HomeViews {
    private HomeViews() {}

    /** firstVisit이 true면 진행 중 학습도 리포트도 없는 첫 방문이다. */
    record HomeView(boolean firstVisit, InProgress inProgress, List<Summary> recentReports) {}

    /**
     * 이어하기 대상. resumeStage에 맞는 화면으로 가고 resumePath로 필요한 데이터를 불러온다.
     * problem 단계에서 retryProblemId가 있으면 재설명을 마친 그 문항부터 다시 푼다.
     */
    record InProgress(UUID sessionId, String conceptName, String resumeStage, String resumePath,
                      UUID retryProblemId, Instant updatedAt) {}
}