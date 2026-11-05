package com.teachmi.backend.learning;

import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
class LearningSessionController {
    private final LearningSessionService sessions;
    private final ObjectProvider<CurrentMember> members;

    LearningSessionController(LearningSessionService sessions, ObjectProvider<CurrentMember> members) {
        this.sessions = sessions;
        this.members = members;
    }

    @PostMapping
    ResponseEntity<SessionView> create(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateSessionRequest request) {
        var result = sessions.create(requireMemberId(http), key, request);
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + result.sessionId())).body(result);
    }

    @GetMapping("/{sessionId}")
    SessionView get(HttpServletRequest http, @PathVariable UUID sessionId) {
        return sessions.get(requireMemberId(http), sessionId);
    }

    private String requireMemberId(HttpServletRequest request) {
        var adapter = members.getIfAvailable();
        var principal = request.getUserPrincipal();
        String id = null;
        if (adapter != null) {
            id = adapter.requireId(request);
        } else if (principal != null) {
            id = principal.getName();
        }
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new LearningException(401, "UNAUTHENTICATED", "로그인이 필요합니다.");
        }
        return id;
    }
}
