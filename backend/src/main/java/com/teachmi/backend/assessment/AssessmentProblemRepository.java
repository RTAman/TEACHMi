package com.teachmi.backend.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentProblemRepository extends JpaRepository<AssessmentProblem, UUID> {
    List<AssessmentProblem> findBySessionIdAndMemberIdOrderBySequence(UUID sessionId, String memberId);

    Optional<AssessmentProblem> findByIdAndSessionIdAndMemberId(UUID id, UUID sessionId, String memberId);

    long countBySessionIdAndMemberId(UUID sessionId, String memberId);
}