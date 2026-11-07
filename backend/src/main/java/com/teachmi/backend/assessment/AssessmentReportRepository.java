package com.teachmi.backend.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentReportRepository extends JpaRepository<AssessmentReport, UUID> {
    Optional<AssessmentReport> findBySessionIdAndMemberId(UUID sessionId, String memberId);

    Optional<AssessmentReport> findByIdAndSessionIdAndMemberId(UUID id, UUID sessionId, String memberId);

    List<AssessmentReport> findTop5ByMemberIdOrderByCreatedAtDesc(String memberId);
}