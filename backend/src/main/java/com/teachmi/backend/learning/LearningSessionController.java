package com.teachmi.backend.learning;

import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sessions")
class LearningSessionController {
    private final LearningSessionService sessions;
    private final ObjectProvider<CurrentMember> members;
    private final LearningTurnService turns;
    private final LearningReteachService reteach;
    private final LearningEvidenceService evidence;

    LearningSessionController(LearningSessionService sessions, ObjectProvider<CurrentMember> members,
                              LearningTurnService turns, LearningReteachService reteach, LearningEvidenceService evidence) {
        this.sessions = sessions;
        this.members = members;
        this.turns = turns;
        this.reteach = reteach;
        this.evidence = evidence;
    }

    @PostMapping
    ResponseEntity<SessionView> create(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateSessionRequest request) {
        var result = sessions.create(requireMemberId(http), key, request);
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + result.sessionId())).body(result);
    }

    @GetMapping("/active")
    ResponseEntity<SessionView> active(HttpServletRequest http) {
        return sessions.getActive(requireMemberId(http))
                .map(session -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping("/{sessionId}")
    SessionView get(HttpServletRequest http, @PathVariable UUID sessionId) {
        return sessions.get(requireMemberId(http), sessionId);
    }

    @GetMapping("/{sessionId}/evidence")
    ResponseEntity<LearningEvidenceView> evidence(HttpServletRequest http, @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.get(requireMemberId(http), sessionId));
    }

    @PostMapping("/{sessionId}/turns")
    SessionView turn(HttpServletRequest http, @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TurnRequest request) {
        return turns.send(requireMemberId(http), sessionId, key, request);
    }

    @PostMapping("/{sessionId}/reteach")
    SessionView reteach(HttpServletRequest http, @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody StartReteachRequest request) {
        return reteach.start(requireMemberId(http), sessionId, key, request);
    }

    @PutMapping("/{sessionId}/mode")
    SessionView chooseMode(HttpServletRequest http, @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ChooseModeRequest request) {
        return sessions.chooseMode(requireMemberId(http), sessionId, key, request);
    }

    @PostMapping("/{sessionId}/chat/start")
    SessionView startChat(HttpServletRequest http, @PathVariable UUID sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody StartChatRequest request) {
        return sessions.startChat(requireMemberId(http), sessionId, key, request);
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
