# B 공통 Gemini 호출 포트

공개 Java 계약은 `com.teachmi.backend.ai.GeminiGenerationPort`입니다.
공개 HTTP API를 추가하지 않으며 B와 C의 생성·채점 어댑터가 이 포트를 공유합니다.
GEMINI_ENABLED=true일 때 bean이 생깁니다. 꺼져 있으면 소비자는 ObjectProvider로 미연결 상태를 처리하세요.
기존 환경 변수, 모델 선택, 고정 Google HTTPS 주소와 키 헤더, 타임아웃 설정은 그대로 사용합니다.

## 최신 main과 공통 서비스 호환성

기준 main: d1b5455f47233a0e03fbe34ee55252a9cd125f1f (PR #38·#39 v4·#40·#41 병합).
수정 후 전체 A·B·C 405개 및 별도 HTTP 여정이 통과했습니다. 아래의 잠금·트랜잭션 계약은 유지하며, 세션 GET에는 읽기 전용 생성 보류 복원을 추가했습니다.

| 서비스 | 현재 계약과 AI 연결 위치 |
| --- | --- |
| LearningSessionService | 기존 create는 자체 요청 이력, createInCommand는 MANDATORY 참여로 C 명령의 이력을 사용합니다. 새 포트는 get으로 커밋된 학습 소유권·자료·개념·출처를 확인합니다. |
| LearningAssessmentService | 기존 publishReport/completeLearning과 C의 prepared 메서드를 유지합니다. C 결과 준비는 밖에서, 상태·근거 재검증과 저장은 기존 콜백에서 처리합니다. |
| LearningAssessmentAccess | 회원 → 세션 잠금, 최신 상태 refresh, 설명·근거·복귀 검증을 유지합니다. 외부 AI 호출을 이 잠금 안에 넣지 않습니다. |
| LearningCommands | C 작업 지문과 응답 저장은 B와 같은 요청 키 공간의 원자적 DB 콜백입니다. Gemini 생성 이력과는 별도입니다. 콜백에서 새 AI 포트를 호출하면 409 AI_TRANSACTION_NOT_ALLOWED로 롤백하고 호출하지 않습니다. |

기존 B 요약·질문·대화·재설명은 같은 공통 실행 엔진을 사용합니다.
기존 B의 DTO·생성 키 계산식과 v1·v2 요청 JSON을 보존해 저장한 성공 결과를 다시 사용합니다. 새 대화·재설명 요청은 짧은 인용 후보를 제공하는 v3이며, 과거 성공 캐시는 별도 예약 없이 조회/재검증합니다.
기존 B 준비 API의 트랜잭션 경계도 유지합니다. 새 공개 포트의 트랜잭션 거절 규칙을 기존 B 준비 경로에 소급하지 않습니다.

## 입력과 결과

Request는 Scope, operation, promptVersion, instruction, input, schema를 받습니다.
Scope는 인증된 memberId, 소유 학습 sessionId, 자료·개념 ID와 B 스냅샷의 전체 sourceChunkIds입니다.
operation은 assessment/problems처럼 역할별로 분리하고, 프롬프트·판정 규칙을 변경하면 promptVersion을 올리세요.
input에는 작업의 안정적인 식별자와 필요한 문맥을 넣습니다. 재시도 때 임의의 새 ID를 만들지 마세요.
서버에서 검증한 근거 버전·시도 ID·답안을 쓰는 것은 소비자의 책임입니다.

새 포트는 B 학습의 소유권·자료·개념·출처 집합을 확인하고, A의 실제 원문 조회로 현재 권한을 다시 검증합니다.
캐시 조회도 이 검증을 거칩니다. 원문·입력·스키마·지시·작업·버전·모델이 달라지면 생성 키가 달라집니다.
JSON 객체 키 순서를 정렬하고 배열 순서는 유지합니다. Scope의 출처 순서는 정규화합니다.
요청 JSON은 방어적으로 복사하며 toString에는 지시·답안·소유자 값을 출력하지 않습니다.

schema는 업무 필드만 갖는 object 스키마입니다. sourceChunkIds와 evidence는 포트가 추가하므로 선언하지 마세요.
서버가 지원하는 제한된 스키마는 type(객체·배열·문자열·숫자·정수·불리언·null 및 타입 배열),
properties, required, additionalProperties=false, items, min/maxItems, min/maxLength, minimum/maximum, enum, title/description입니다.
객체마다 properties·required·additionalProperties=false를 선언하세요. 참조·조합·정규식 등 지원하지 않는 키는 호출 전 422입니다.
스키마 깊이는 16, 스키마 JSON 16,000자, input JSON 48,000자, instruction 12,000자로 제한합니다.
원문·출력 토큰 상한은 기존 GEMINI 설정을 사용합니다. 모델의 스키마 지원과 별도로 서버에서 값을 검사합니다.

모든 응답은 선택 원문의 실제 출처 ID와 ID별 최소 6글자의 정확한 부분 문자열 인용문을 가져야 합니다.
알 수 없는/중복 출처, 근거 누락, 잘못된 인용문, 타입·필드·개수·길이·enum·숫자 범위 위반을 거절합니다.
이 기계적 검사는 내용의 의미적 정답을 보장하지 않습니다. Validator<T>에서 업무 구조·ID 연결·판정 의미를 추가 검증하세요.
validator는 캐시 성공 저장 전과 캐시 적중 때 모두 실행하며 DB 쓰기·추가 AI 호출 없이 순수 검증만 해야 합니다.

```java
// 저장 트랜잭션 밖에서, 실제 소유 세션으로 요청을 준비합니다.
var state = learningSessionService.get(memberId, sessionId);
var request = new GeminiGenerationPort.Request(
    new GeminiGenerationPort.Scope(memberId, sessionId, state.materialId(), state.conceptId(),
        state.concept().sourceChunkIds()),
    "consumer/explanation", "v1", "원문을 한국어 한 문장으로 설명하세요.",
    json.readTree("{\"purpose\":\"concept explanation\"}"),
    json.readTree("""
        {"type":"object","properties":{"text":{"type":"string","minLength":1,"maxLength":1000}},
         "required":["text"],"additionalProperties":false}
        """));
String text = geminiPort.generate(request, response -> response.path("text").asText());
// 이후 기존 소유권·단계·버전 검사 아래 업무 결과를 저장합니다.
```

## 한도·중복·실패

B와 새 소비자는 같은 GeminiLedger와 전용 최대 2개 연결 풀을 공유합니다.
분·UTC 일·회원별 한도와 max-attempts는 업무별로 새로 생기지 않고 기존 V4 호출 기록 전체에 함께 적용됩니다.
전송 전 사용량을 예약하고 실패한 호출도 집계합니다. 성공한 AI 결과는 업무 저장 롤백 뒤에도 같은 요청으로 복원할 수 있습니다.
동일 생성 키의 동시 실행은 한 번만 전송하며, 나머지는 생성 중 응답을 받습니다.

타임아웃·연결 단절·5xx·임대 만료는 기존 uncertain 정책으로 재호출을 보류합니다.
429 Retry-After와 명시적 재시도 한도를 유지합니다. 자동 반복 호출은 없습니다.
잘못된 AI/업무 검증 결과는 성공으로 저장하지 않습니다. 성공 저장이나 실패 기록의 DB 확인이 안 되면 uncertain 오류로 돌려 중복 과금을 피합니다.
키·프롬프트·원문·원본 오류 응답을 예외 메시지·로그로 출력하지 않습니다.

추가 오류는 422 INVALID_AI_REQUEST, 409 AI_TRANSACTION_NOT_ALLOWED,
503 AI_SESSION_INTEGRATION_PENDING입니다. 기존 한도·원문·Gemini 오류 코드는 GEMINI_CONTENT.md를 따릅니다.

## C 연결과 안전한 오류 계약

C의 GeminiProblemGenerator·GeminiAnswerGrader는 v4로 연결돼 있습니다. 원격 채점은 입력/작업 임대 저장·커밋 → 트랜잭션 밖 생성 → 최신 버전/실행 토큰 검증·결과 저장으로 분리합니다. 문제별 출처, 정답 비공개, attempt 보존과 stale 결과 거절 계약은 유지합니다.

업무 검증기는 GeminiValidationException과 고정 Diagnostic enum으로 안전한 분류만 전달할 수 있습니다. C CitationRules의 규칙과 v4 프롬프트는 바꾸지 않고 길이/출처 분류를 원장과 recovery에 전달합니다. 임의 예외 메시지는 원문이나 응답 전문과 함께 외부로 내보내지 않으며, 미분류 예외는 DOMAIN_RESPONSE_INVALID로 처리합니다.

C HTTP 오류도 LearningException의 선택적 recovery를 전달합니다. 마지막 허용 시도의 검증 실패는 기존 502·INVALID_GEMINI_RESPONSE와 구체 진단을 유지하면서 retryAllowed=false/action=limit_reached를 반환합니다. 일반 인증·자료 오류의 code/message 형태는 유지합니다.

## 검증과 테스트 대체

단위 테스트에서 GeminiGenerationPort 자체를 대체할 수 있으며 기존 B 생성기는 유지됩니다.
일반 build/CI는 외부 Google 요청을 하지 않습니다. 실제 A 원문·인증, 가짜 전송 응답과 PostgreSQL로
B와 공통 포트의 한도 공유, 캐시 권한 재검증, 기존 생성 키, 동시 호출, 검증·재시도·트랜잭션 경계를 확인합니다.
실 API 검증은 기존 별도 geminiLiveTest 명령을 사용하며 이번 PR에서는 실행하지 않았습니다.
새 환경 변수·의존성·HTTP API·Flyway 마이그레이션은 없습니다.

참고: [Google 구조화 응답](https://ai.google.dev/gemini-api/docs/structured-output),
[generateContent API](https://ai.google.dev/api/generate-content). 응답 값의 업무 검증은 서버에서도 수행합니다.
