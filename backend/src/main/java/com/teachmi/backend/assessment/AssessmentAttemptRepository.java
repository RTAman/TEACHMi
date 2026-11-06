package com.teachmi.backend.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, UUID> {
    List<AssessmentAttempt> findByProblemIdOrderByAttemptNo(UUID problemId);

    Optional<AssessmentAttempt> findByMemberIdAndRequestKey(String memberId, String requestKey);
}