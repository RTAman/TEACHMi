package com.teachmi.backend.learning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.teachmi.backend.learning.LearningEvidenceView.*;

@Service
public class LearningEvidenceService {
    private final LearningSessionService sessions;

    public LearningEvidenceService(LearningSessionService sessions) { this.sessions = sessions; }

    /**
     * An independent committed snapshot, even if C has a READ_COMMITTED transaction.
     * Call after committing B changes; this method intentionally cannot see outer uncommitted writes.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public LearningEvidenceView get(String memberId, UUID sessionId) {
        var session = sessions.get(memberId, sessionId);
        var messages = new HashMap<UUID, LearningMessageView>();
        for (var message : session.messages()) messages.put(message.messageId(), message);
        var episodeIds = new HashSet<UUID>();
        for (var episode : session.reteachHistory()) episodeIds.add(episode.reteachId());
        Map<UUID, List<SupportEventView>> supports = new HashMap<>();
        for (var event : session.supportEvents()) {
            if (event.reteachId() != null && !episodeIds.contains(event.reteachId())) throw inconsistent();
            validateSupport(event, messages);
            supports.computeIfAbsent(event.reteachId(), ignored -> new ArrayList<>()).add(event);
        }

        var original = explanation(session.initialAnswerId(), session.finalExplanationId(), null, messages, supports);
        boolean started = session.messages().stream().anyMatch(message -> message.reteachId() == null);
        String originalState = original.finalExplanation() != null ? "completed" : !started ? "not_started"
                : session.status().equals("active") ? "in_progress" : "interrupted";
        var episodes = new ArrayList<ReteachEvidence>();
        for (var episode : session.reteachHistory()) {
            var evidence = explanation(episode.initialAnswerId(), episode.finalExplanationId(), episode.reteachId(),
                    messages, supports);
            boolean returned = episode.state().equals("returned");
            if (returned != (evidence.finalExplanation() != null) || returned != (episode.completedAt() != null)) {
                throw inconsistent();
            }
            String state = !returned && !session.status().equals("active") ? "interrupted" : episode.state();
            episodes.add(new ReteachEvidence(episode.reteachId(), episode.problemId(), episode.attemptId(),
                    episode.focus(), state, episode.createdAt(), episode.completedAt(), evidence));
        }
        PendingGeneration pending = null;
        if (session.pendingTurn() != null) {
            var turn = session.pendingTurn();
            var user = messages.get(turn.userMessageId());
            if (user == null) throw inconsistent();
            pending = new PendingGeneration("turn", turn.turnId(), user.reteachId(), turn.state(), turn.errorCode());
        } else if (session.activeReteach() != null && !session.activeReteach().state().equals("chat")) {
            var episode = session.activeReteach();
            pending = new PendingGeneration("reteach", episode.reteachId(), episode.reteachId(), episode.state(), episode.errorCode());
        }
        return new LearningEvidenceView(1, session.sessionId(), session.version(), session.materialId(),
                session.conceptId(), session.concept(), session.mode(), session.stage(), session.status(),
                session.updatedAt(), new OriginalLearning(originalState, original), episodes, pending);
    }

    private ExplanationEvidence explanation(UUID firstId, UUID finalId, UUID episode,
            Map<UUID, LearningMessageView> messages, Map<UUID, List<SupportEventView>> supports) {
        var first = answer(firstId, episode, "case", messages);
        var last = answer(finalId, episode, "wrapup", messages);
        if (last != null && (first == null || first.sequence() >= last.sequence())) throw inconsistent();
        var events = supports.getOrDefault(episode, List.of());
        return new ExplanationEvidence(first, last, new SupportUsage(count(events),
                count(events.stream().filter(event -> event.responseMessageId() != null).toList()), events));
    }

    private LearningMessageView answer(UUID id, UUID episode, String stage, Map<UUID, LearningMessageView> messages) {
        if (id == null) return null;
        var message = messages.get(id);
        if (message == null || !message.role().equals("user") || !message.kind().equals("answer")
                || !stage.equals(message.chatStage()) || !Objects.equals(episode, message.reteachId())) throw inconsistent();
        return message;
    }

    private void validateSupport(SupportEventView event, Map<UUID, LearningMessageView> messages) {
        if (!List.of("hint", "example", "dont_know").contains(event.kind())) throw inconsistent();
        var target = scoped(event.targetMessageId(), event.reteachId(), messages);
        var request = scoped(event.requestMessageId(), event.reteachId(), messages);
        if (!target.role().equals("assistant") || !target.kind().endsWith("_question")
                || !request.role().equals("user") || !request.kind().equals(event.kind())
                || !event.eventId().equals(request.supportEventId()) || request.sequence() <= target.sequence()) throw inconsistent();
        if (event.responseMessageId() != null) {
            var response = scoped(event.responseMessageId(), event.reteachId(), messages);
            if (!response.role().equals("assistant") || !response.kind().equals(event.kind())
                    || !event.eventId().equals(response.supportEventId()) || response.sequence() <= request.sequence()) {
                throw inconsistent();
            }
        }
    }

    private LearningMessageView scoped(UUID id, UUID episode, Map<UUID, LearningMessageView> messages) {
        var message = messages.get(id);
        if (message == null || !Objects.equals(episode, message.reteachId())) throw inconsistent();
        return message;
    }

    private SupportCounts count(List<SupportEventView> events) {
        int hints = 0, examples = 0, unknown = 0;
        for (var event : events) {
            switch (event.kind()) {
                case "hint" -> hints++;
                case "example" -> examples++;
                case "dont_know" -> unknown++;
                default -> throw inconsistent();
            }
        }
        return new SupportCounts(events.size(), hints, examples, unknown);
    }

    private LearningException inconsistent() {
        return new LearningException(500, "LEARNING_EVIDENCE_INCONSISTENT", "저장된 학습 근거의 연결을 확인해야 합니다.");
    }
}
