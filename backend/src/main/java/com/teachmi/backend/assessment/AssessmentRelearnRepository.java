package com.teachmi.backend.assessment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentRelearnRepository extends JpaRepository<AssessmentRelearn, UUID> {}