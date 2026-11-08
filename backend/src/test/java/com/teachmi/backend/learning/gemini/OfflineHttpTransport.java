package com.teachmi.backend.learning.gemini;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Offline protocol fixtures only. This is not an AI quality/semantic grading implementation. */
final class OfflineHttpTransport implements GeminiTransport {
    final tools.jackson.databind.ObjectMapper json=JsonMapper.builder().build();
    final Map<String,Integer> calls=new LinkedHashMap<>();
    final List<Map<String,Object>> audit=new ArrayList<>();
    final AtomicReference<String> fault=new AtomicReference<>();
    synchronized int count() { return calls.values().stream().mapToInt(Integer::intValue).sum(); }

    @Override public synchronized String generate(String requestJson) {
        var request=json.readTree(requestJson);
        var data=json.readTree(request.path("contents").get(0).path("parts").get(0).path("text").asText());
        String operation=data.path("operation").asText();
        calls.merge(operation,1,Integer::sum);
        var chunk=data.path("lectureExcerpts").get(0);
        String id=chunk.path("id").asText();
        String source=chunk.path("text").asText();
        if(id.isBlank() || source.length()<45) throw new IllegalStateException("Offline source is too short");
        String injection=fault.getAndSet(null);
        var safe=new LinkedHashMap<String,Object>();
        safe.put("operation",operation);
        safe.put("promptVersion",data.path("promptVersion").asText());
        safe.put("fault",injection==null?"none":injection);
        safe.put("maxOutputTokens",request.path("generationConfig").path("maxOutputTokens").asInt());
        audit.add(safe);
        if("timeout".equals(injection))
            throw new GeminiCallFailure(true,503,"AI_GENERATION_UNCERTAIN",0,"TRANSPORT_REQUEST_TIMEOUT");
        var output=new LinkedHashMap<String,Object>();
        output.put("sourceChunkIds",List.of(id));
        String quote=source.substring(0,"long-quote".equals(injection)?41:20);
        if(injection==null && !data.path("citationCandidates").isEmpty())
            quote=data.path("citationCandidates").get(0).path("quote").asText();
        if("bad-quote".equals(injection)) quote="실제 원문과 일치하지 않는 인용문입니다.";
        output.put("evidence",List.of(Map.of("sourceChunkId",id,"quote",quote)));
        switch(operation) {
            case "summary" -> {
                output.put("text","선택한 원문의 핵심 원리를 살펴봅니다.");
                output.put("examples",List.of("원문 규칙을 다른 조건에 적용하는 예입니다."));
                output.put("keyTerms",List.of("핵심 원리"));
                output.put("reflectionPrompt","조건이 바뀌면 원리를 어떻게 적용할까요?");
            }
            case "question","reteach_question" -> output.put("text","조건을 바꾼 사례에 원문의 원리를 어떻게 적용할 수 있을까요?");
            case "dialogue" -> {
                boolean answer=data.path("dialogue").path("kind").asText().equals("answer");
                output.put("text",answer?"설명을 들었어요. 그 원리와 이유도 설명해 줄래요?":"작은 단서를 살펴보고 다시 설명해 줄래요?");
                output.put("accepted",answer);
            }
            case "assessment/problems" -> output.put("problems",List.of(
                Map.of("question","첫 번째 새로운 조건의 사례에 원리를 어떻게 적용할까요?","changedConditions","첫 사례의 대상을 바꿈",
                    "expectedAnswer","원리 적용","solution","제공된 원문 근거와 조건을 연결해 풉니다.","gradingCriteria","원리와 조건의 관계를 설명"),
                Map.of("question","두 번째 새로운 조건의 사례에 원리를 어떻게 적용할까요?","changedConditions","둘째 사례의 상황을 바꿈",
                    "expectedAnswer","원리 적용","solution","제공된 원문 근거와 조건을 연결해 풉니다.","gradingCriteria","원리와 조건의 관계를 설명")));
            case "assessment/grade" -> {
                String answer=data.path("input").path("answer").asText();
                String judgement=answer.equals("정답")?"correct":answer.equals("보류")?"pending_review":"wrong";
                output.put("judgement",judgement);
                output.put("reteachFocus",judgement.equals("wrong")?"원문의 핵심 조건과 이유를 다시 설명해 보세요.":"");
                output.put("reason","오프라인 응답 계약 검증용 판정");
            }
            default -> throw new IllegalArgumentException("Unexpected offline operation");
        }
        return json.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP",
                "content",Map.of("parts",List.of(Map.of("text",json.writeValueAsString(output))))))));
    }
}
