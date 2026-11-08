package com.teachmi.backend.assessment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.teachmi.backend.ai.GeminiGenerationPort;
import com.teachmi.backend.learning.LearningMessageView;
import com.teachmi.backend.learning.SessionView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * B의 공통 Gemini 포트로 강의 원문에 근거한 응용문제 2개를 만든다.
 * 임시 문항(ASSESSMENT_DEMO_ENABLED)이 함께 켜져 있어도 이 구현을 쓰도록 @Primary를 붙였다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "teachmi.gemini.enabled", havingValue = "true")
class GeminiProblemGenerator implements ProblemGenerator {
    static final String OPERATION = "assessment/problems";
    // 프롬프트·스키마·검증 규칙을 바꾸면 올린다. 같은 버전의 이전 결과가 재사용되지 않게 한다.
    // v3: 출처 표기 규칙을 명시하고 응답 길이를 줄였다. v2에서 인용 검증 실패(502)가 반복됐다.
    static final String PROMPT_VERSION = "v3";

    private static final String INSTRUCTION = """
            학생이 AI 학생에게 개념을 설명한 직후 혼자 풀 응용문제 2개를 한국어로 만든다.
            - 강의 원문 발췌에 있는 사실·공식·정의만 사용한다. 원문에 없는 내용은 지어내지 않는다.
            - input.caseQuestion의 대화 사례와 조건(숫자, 대상, 상황)을 바꾼 새 문제를 낸다. 두 문제끼리도 조건이 달라야 한다.
            - 개념을 외운 문장으로는 풀 수 없고, 원리를 새 상황에 적용해야 풀리게 한다.
            - question에는 정답이나 풀이를 쓰지 않는다.
            - changedConditions에는 대화 사례와 무엇이 달라졌는지 한 문장으로 쓴다.
            - expectedAnswer는 채점에 쓸 짧은 정답(값이나 핵심 결론)이다.
            - solution은 정답에 이르는 풀이와 근거다.
            - gradingCriteria에는 정답으로 볼 조건과, 답과 함께 설명해야 할 이유를 쓴다.
            - 원문만으로 두 문제를 만들 근거가 부족하면 내용을 반환하지 않는다.
            - 각 항목은 짧게 쓴다. solution은 계산식과 이유 두세 문장, gradingCriteria는 한두 문장이면 충분하다.
            출처 표기 규칙. 하나라도 어기면 서버가 응답 전체를 거절한다.
            - sourceChunkIds에는 실제로 근거로 쓴 원문 조각 ID를 1~2개만 넣는다. 같은 ID를 두 번 넣지 않는다.
            - sourceChunkIds의 ID마다 evidence를 정확히 1개씩 만든다. evidence 없는 ID를 남기지 않는다.
            - evidence.quote는 그 원문 조각에서 6~40글자를 한 글자도 바꾸지 않고 그대로 복사한다.
              띄어쓰기, 기호(÷, -, 괄호), 숫자 표기를 원문과 똑같이 쓰고, 요약하거나 문장을 이어 붙이지 않는다.
            """;

    private final GeminiGenerationPort port;
    private final ObjectMapper json;
    private final JsonNode schema;

    GeminiProblemGenerator(GeminiGenerationPort port, ObjectMapper json) {
        this.port = port;
        this.json = json;
        this.schema = json.readTree(SCHEMA);
    }

    @Override
    public List<Draft> generate(SessionView session) {
        throw new IllegalStateException("Gemini 문제 생성에는 회원 ID가 필요합니다.");
    }

    /** DB 트랜잭션 밖에서 호출된다. 포트는 트랜잭션 안에서 호출하면 409로 거절한다. */
    @Override
    public List<Draft> generate(String memberId, SessionView session) {
        var concept = session.concept();
        var scope = new GeminiGenerationPort.Scope(memberId, session.sessionId(), session.materialId(),
                session.conceptId(), concept.sourceChunkIds());
        var request = new GeminiGenerationPort.Request(scope, OPERATION, PROMPT_VERSION, INSTRUCTION,
                json.valueToTree(input(session)), schema);
        return port.generate(request, this::validate);
    }

    /**
     * 재시도 때 같은 결과를 다시 쓰도록 입력은 저장된 값만으로 만든다. 새 ID나 현재 시각을 넣지 않는다.
     * evidenceVersion이 바뀌면(재설명 등) 다른 입력이 되어 새로 생성된다.
     */
    private Map<String, Object> input(SessionView session) {
        var input = new LinkedHashMap<String, Object>();
        input.put("sessionId", session.sessionId().toString());
        input.put("evidenceVersion", session.version());
        input.put("concept", Map.of("name", session.concept().name(), "scope", session.concept().scope()));
        input.put("caseQuestion", clip(firstCaseQuestion(session)));
        input.put("finalExplanation", clip(text(session, session.finalExplanationId())));
        return input;
    }

    /** 원래 학습(재설명이 아닌)의 첫 사례 질문. AI 학생이 낸 질문이라 role이 user가 아니다. */
    private static String firstCaseQuestion(SessionView session) {
        return session.messages().stream()
                .filter(message -> message.reteachId() == null && "case".equals(message.chatStage())
                        && !"user".equals(message.role()))
                .map(LearningMessageView::text).findFirst().orElse("");
    }

    private static String text(SessionView session, java.util.UUID messageId) {
        return session.messages().stream().filter(message -> message.messageId().equals(messageId))
                .map(LearningMessageView::text).findFirst().orElse("");
    }

    private static String clip(String value) {
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    /** 포트의 출처·인용 검사 뒤에 실행된다. DB 쓰기나 AI 호출 없이 업무 규칙만 검사한다. */
    List<Draft> validate(JsonNode response) {
        var items = response.path("problems");
        if (!items.isArray() || items.size() != 2) {
            throw new IllegalArgumentException("문항은 2개여야 합니다.");
        }
        var sources = new ArrayList<String>();
        response.path("sourceChunkIds").forEach(id -> sources.add(id.asText()));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("출처가 없습니다.");
        }
        var drafts = new ArrayList<Draft>();
        var questions = new HashSet<String>();
        for (var item : items) {
            var draft = new Draft(field(item, "question"), field(item, "changedConditions"),
                    field(item, "expectedAnswer"), field(item, "solution"), field(item, "gradingCriteria"), sources);
            if (AnswerExposure.questionRevealsAnswer(draft.question(),draft.expectedAnswer())) {
                throw new IllegalArgumentException("문제에 정답이 공개되어 있습니다.");
            }
            if (!questions.add(draft.question())) {
                // 같은 문제가 두 번 나오면 두 번째 문항으로 독립 적용을 확인할 수 없다.
                throw new IllegalArgumentException("문항이 중복됩니다.");
            }
            drafts.add(draft);
        }
        return List.copyOf(drafts);
    }

    /** 테스트에서 포트가 지원하는 스키마 키만 쓰는지 확인할 때 쓴다. */
    JsonNode schema() {
        return schema.deepCopy();
    }

    private static String field(JsonNode item, String name) {
        var value = item.path(name).asText("").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + "이 비어 있습니다.");
        }
        return value;
    }

    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["problems"],
             "properties":{"problems":{"type":"array","minItems":2,"maxItems":2,
               "items":{"type":"object","additionalProperties":false,
                 "required":["question","changedConditions","expectedAnswer","solution","gradingCriteria"],
                 "properties":{
                   "question":{"type":"string","minLength":10,"maxLength":400},
                   "changedConditions":{"type":"string","minLength":1,"maxLength":150},
                   "expectedAnswer":{"type":"string","minLength":1,"maxLength":100},
                   "solution":{"type":"string","minLength":1,"maxLength":600},
                   "gradingCriteria":{"type":"string","minLength":1,"maxLength":300}}}}}}
            """;
}