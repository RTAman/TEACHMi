package com.teachmi.backend.home;

import java.util.UUID;
import com.teachmi.backend.assessment.ReportSummaries;
import com.teachmi.backend.home.HomeViews.HomeView;
import com.teachmi.backend.home.HomeViews.InProgress;
import com.teachmi.backend.learning.LearningSessionService;
import com.teachmi.backend.learning.SessionView;
import org.springframework.stereotype.Service;

/** 현재 회원의 진행 중 학습(B)과 최근 리포트(C)를 합쳐 홈 화면 데이터를 만든다. 아무것도 저장하지 않는다. */
@Service
class HomeService {
    private final LearningSessionService sessions;
    private final ReportSummaries reports;

    HomeService(LearningSessionService sessions, ReportSummaries reports) {
        this.sessions = sessions;
        this.reports = reports;
    }

    HomeView get(String memberId) {
        var active = sessions.getActive(memberId).map(this::inProgress).orElse(null);
        var recent = reports.recent(memberId);
        return new HomeView(active == null && recent.isEmpty(), active, recent);
    }

    private InProgress inProgress(SessionView session) {
        String base = "/api/v1/sessions/" + session.sessionId();
        String path = switch (session.stage()) {
            // 문항 시작 API는 다시 호출해도 같은 문항을 돌려주므로 이어하기에 그대로 쓴다.
            case "problem" -> base + "/problems";
            case "report" -> base + "/report";
            default -> session.resume() == null ? base : session.resume().path();
        };
        UUID retry = session.problemReturn() == null ? null : UUID.fromString(session.problemReturn().problemId());
        return new InProgress(session.sessionId(), session.concept().name(), session.stage(), path, retry,
                session.updatedAt());
    }
}