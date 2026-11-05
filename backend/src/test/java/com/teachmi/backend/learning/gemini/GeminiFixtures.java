package com.teachmi.backend.learning.gemini;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.teachmi.backend.learning.ConceptSource;
import com.teachmi.backend.learning.LearningContentGenerator;
import com.teachmi.backend.learning.LearningSourceReader;
import tools.jackson.databind.ObjectMapper;

final class GeminiFixtures {
    static final String SOURCE = "컴퓨터 취득원가 600만 원, 잔존가치 0원, 내용연수 3년을 정액법으로 배분하면 연간 감가상각비는 200만 원이다.";
    static final String QUOTE = "연간 감가상각비는 200만 원이다.";

    static ConceptSource.Snapshot concept() {
        return new ConceptSource.Snapshot("감가상각", "정액법", List.of("k01"));
    }

    static LearningContentGenerator.Context context(String member) {
        return new LearningContentGenerator.Context(member, UUID.randomUUID(), "m01", "c01",
                "first", concept(), null);
    }

    static String response(ObjectMapper json, boolean summary) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("text", summary ? "취득원가를 사용 기간에 나눠 배분합니다." : "3년 쓰는 컴퓨터 구입비를 첫해에 모두 비용 처리할까요?");
        payload.put("sourceChunkIds", List.of("k01"));
        payload.put("evidence", List.of(Map.of("sourceChunkId", "k01", "quote", QUOTE)));
        if (summary) {
            payload.put("examples", List.of("600만 원을 3년에 나누면 연간 200만 원입니다."));
            payload.put("keyTerms", List.of("내용연수", "정액법"));
            payload.put("reflectionPrompt", "사용 기간에 나누는 이유를 설명해 볼까요?");
        }
        return envelope(json, json.writeValueAsString(payload), "STOP");
    }

    static String envelope(ObjectMapper json, String text, String reason) {
        return json.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", reason,
                "content", Map.of("parts", List.of(Map.of("text", text)))))));
    }

    static class Sources implements ConceptSource, LearningSourceReader {
        volatile OwnedSource override;

        @Override public Snapshot loadOwnedConcept(String member, String material, String concept) {
            if (!material.equals("m01") || !concept.equals("c01")) {
                throw com.teachmi.backend.learning.LearningException.notFound();
            }
            return GeminiFixtures.concept();
        }

        @Override public OwnedSource readOwned(String member, String material, String concept, List<String> ids) {
            return override != null ? override : new OwnedSource(member, material, concept,
                    List.of(new Chunk("k01", SOURCE)));
        }
    }
}
