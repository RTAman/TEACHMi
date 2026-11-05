package com.teachmi.backend.learning;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

interface LearningSessionRepository extends JpaRepository<LearningSession, UUID> {
    Optional<LearningSession> findByIdAndMemberId(UUID id, String memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LearningSession> findByMemberIdAndStatus(String memberId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LearningSession s where s.id = :id and s.memberId = :memberId")
    Optional<LearningSession> lockOwned(UUID id, String memberId);
}
