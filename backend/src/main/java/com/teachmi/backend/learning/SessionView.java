package com.teachmi.backend.learning;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record SessionView(
        UUID sessionId, String materialId, String conceptId, ConceptSource.Snapshot concept,
        String mode, String stage, String status, long version, Instant createdAt, Instant updatedAt,
        Resume resume, LearningContentGenerator.Summary summary, String chatStage,
        List<LearningMessageView> messages, UUID initialAnswerId, UUID finalExplanationId,
        List<SupportEventView> supportEvents, TurnProgress pendingTurn, List<String> availableActions,
        ReteachView activeReteach, ProblemReturn problemReturn, List<ReteachView> reteachHistory,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        GenerationRecovery recovery) {
    public SessionView {
        // Creation responses saved by the previous release have no messages property.
        messages = messages == null ? List.of() : List.copyOf(messages);
        supportEvents = supportEvents == null ? List.of() : List.copyOf(supportEvents);
        availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
        reteachHistory = reteachHistory == null ? List.of() : List.copyOf(reteachHistory);
        var terminal=pendingTurn==null?null:GenerationRecovery.forTerminalError(pendingTurn.errorCode());
        if(terminal==null && activeReteach!=null) terminal=GenerationRecovery.forTerminalError(activeReteach.errorCode());
        // A saved terminal error takes precedence over any stale processing hint after reconnect.
        if(terminal!=null) recovery=terminal;
    }
    /** Preserve existing A/B/C source callers and old saved success payloads. */
    public SessionView(UUID sessionId,String materialId,String conceptId,ConceptSource.Snapshot concept,
            String mode,String stage,String status,long version,Instant createdAt,Instant updatedAt,
            Resume resume,LearningContentGenerator.Summary summary,String chatStage,List<LearningMessageView> messages,
            UUID initialAnswerId,UUID finalExplanationId,List<SupportEventView> supportEvents,TurnProgress pendingTurn,
            List<String> availableActions,ReteachView activeReteach,ProblemReturn problemReturn,List<ReteachView> reteachHistory) {
        this(sessionId,materialId,conceptId,concept,mode,stage,status,version,createdAt,updatedAt,resume,summary,chatStage,
                messages,initialAnswerId,finalExplanationId,supportEvents,pendingTurn,availableActions,activeReteach,problemReturn,reteachHistory,null);
    }
    public record Resume(String stage, String path) {}
}
