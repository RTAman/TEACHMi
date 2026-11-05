# B 재설명과 같은 문항 복귀

오답 이후 보완할 개념을 설명하는 대화를 같은 학습 세션에 저장하고, 완료 후 원래 문항 ID를 C에 돌려줍니다. 재설명마다 고유 ID를 부여해 최초 학습과 발화·도움·최종 설명을 구분합니다.

## C 연결 대기 상태

현재 저장소에는 C의 문항·답안 구현이 없습니다. C는 `ReteachProblemSource` Spring bean을 구현해야 합니다. 미연결 시 `503 LEARNING_PROBLEM_INTEGRATION_PENDING`을 반환하고 problem 단계와 최초 학습 근거를 보존합니다. 테스트용 C 구현은 src/test에만 있습니다.

`loadWrongAttempt(memberId, sessionId, problemId, attemptId)`는 다음을 확인합니다.

1. 현재 회원이 학습·문항·답안을 소유하고, 문항이 해당 학습에 속합니다.
2. 요청 attemptId가 현재 재설명이 가능한 최신 오답이며 judgement는 wrong입니다.
3. 반환한 Target의 회원·학습·문항·시도 ID가 일치합니다.
4. focus는 보완할 개념을 1~2,000자로 설명합니다. C의 정답·풀이·비공개 평가 내용은 제외합니다.

없거나 타인 소유인 기록은 `LearningException.notFound()`로 처리합니다. 정답·미제출·판정 보류·오래된 시도는 409로 거절합니다. C의 최초 독립 답안·판정·보완 항목은 보존하고, 재시도 답안은 C에서 새 시도로 기록해야 합니다.

## 시작 API

B 경계의 API는 아래 경로입니다. C가 담당하는 `/sessions/{id}/problems/{problemId}/reteach` 라우트에서는 B의 `LearningReteachService.start`를 호출하도록 연결할 수 있습니다.

```http
POST /api/v1/sessions/{sessionId}/reteach
Idempotency-Key: reteach-start-1
Content-Type: application/json

{"problemId":"p01","attemptId":"a01","expectedVersion":9}
```

로그인한 회원의 active·problem 학습에서 pendingTurn이 없을 때 시작합니다. expectedVersion은 최신 GET 값을 사용합니다. 문항·시도 ID는 영문·숫자로 시작하는 최대 128자의 영문·숫자·하이픈·밑줄 문자열입니다.

성공 시 200 SessionView와 `stage: chat`, `chatStage: case`, `activeReteach`를 반환합니다. 첫 질문은 `kind: reteach_question`이고 해당 재설명 ID가 붙습니다. C의 오답을 시작 전과 질문 저장 직전에 재확인합니다.

첫 질문을 생성하는 동안 재설명 대상과 원래 키·버전을 저장합니다. 실패 시 activeReteach.state는 failed, availableActions는 retry_reteach입니다. 같은 시작 키·본문·원래 버전으로 재시도하면 같은 기록을 이어갑니다. 생성 중 재전송은 503, 다른 시작 요청은 409입니다. 동일 오답 시도에는 재설명 한 건을 저장합니다.

## 대화와 복원

기존 `POST /api/v1/sessions/{id}/turns`로 답변·hint·example·dont_know를 전송합니다. case → concept → reason → wrapup을 진행하며, 충분한 답변만 다음 단계로 이동합니다. 도움은 단계를 유지합니다.

메시지·도움 이벤트에 reteachId를 저장합니다. Gemini에는 현재 재설명의 질문·대화와 보완할 개념·A의 소유권 검사된 원문을 전달합니다. C의 비공개 정답·풀이를 가져오는 필드는 제공하지 않습니다. 기존 Gemini 출처·인용문·요청 한도·불확실한 호출 재시도 정책을 적용합니다.

재접속하면 GET의 activeReteach와 reteachHistory에서 원래 problemId·attemptId·진행 상태·발화 ID를 복원합니다. 실패한 turns의 사용자 입력은 기존 pendingTurn 키·버전으로 재시도합니다. 재설명 시작 실패는 activeReteach의 idempotencyKey·expectedVersion과 원래 problemId·attemptId로 재시도합니다.

최초 학습의 initialAnswerId·finalExplanationId는 원래 발화를 유지합니다. 재설명의 첫 답변·최종 설명은 reteachHistory 각 항목에서 확인합니다. C는 reteachId가 있는 발화와 도움을 재설명 근거로 구분하고, 도움 제공 횟수에는 responseMessageId가 있는 이벤트를 사용합니다.

## 같은 문항으로 복귀

충분한 wrapup 뒤 서버는 stage를 problem으로 전환하고 다음과 같은 problemReturn을 반환합니다.

```json
{
  "reteachId": "재설명 UUID",
  "problemId": "p01",
  "originAttemptId": "a01",
  "path": "/api/v1/sessions/학습-UUID/problems/p01"
}
```

availableActions는 retry_problem입니다. C는 이 기존 문항을 열고, 사용자가 다시 제출할 때 새 Attempt와 재설명 ID를 연결합니다. originAttemptId는 재설명의 출발점으로 유지합니다. 문항·답안·채점의 저장은 C가 담당합니다.

C는 자기 DB 트랜잭션 안에서 아래 B 서비스를 호출한 뒤 기존 문항의 재개 상태를 저장합니다. B가 같은 회원의 잠금과 오답 재검증을 먼저 적용하도록 하고, C의 저장이 실패하면 전체 트랜잭션을 롤백하세요.

```java
SessionView state = learningReteachService.acknowledgeProblemReturn(
    currentMemberId, sessionId, reteachId, expectedVersion);
// 같은 트랜잭션에서 C의 기존 문항 재개 상태를 저장합니다.
```

이 호출은 B의 problemReturn 표시를 소비하며 재설명 이력과 원래 답안을 보존합니다. 이미 소비한 복귀의 중복 확인은 해당 학습이 여전히 problem 단계이고 새 재설명이 없을 때 현재 상태를 반환합니다. 다른 복귀·새 재설명·버전 충돌은 409입니다.

같은 문항에서 다음 시도가 다시 오답이면 새로운 attemptId로 새 재설명을 시작합니다. 각 재설명을 별도로 보존하고 최초 독립 오답은 C에서 유지합니다. C의 답안 제출·다음 문항 이동은 학습 단계·버전과 진행 중 재설명을 확인해야 합니다.

## 일관성과 오류

- 성공한 시작·turns 키의 재전송은 당시 성공 응답을 반환합니다. 최신 진행 상태는 GET으로 확인합니다.
- 모든 B 변경 API는 회원별 키 공간을 공유합니다. 다른 동작에 같은 키를 재사용하면 409입니다.
- 시작 생성에는 120초 임대와 실행 토큰을 적용합니다. 이전 작업자는 새 토큰 발급 후 저장할 수 없습니다.
- 생성 실패·유효하지 않은 출처·C 오답 변경은 기록을 보존하고 성공 전환을 거절합니다.
- C의 상태는 시작·재시도·질문 저장·대화 전송·응답 저장·복귀 확인에서 재검증합니다.
- 새 학습 시작으로 기존 학습이 종료되면 진행 중 응답이 종료된 학습을 갱신할 수 없습니다.

| HTTP·코드 | 의미 |
| --- | --- |
| 401 / 404 | 인증 필요 / 타인 또는 없는 학습·문항 |
| 409 WRONG_ATTEMPT_REQUIRED | 현재 오답 시도 필요 |
| 409 RETEACH_ORIGIN_CHANGED | 원래 오답의 보완 항목 변경 |
| 409 RETEACH_ALREADY_EXISTS | 같은 문항·시도의 재설명 이력 존재 |
| 409 SESSION_STATE_CONFLICT | 단계·버전·재설명 상태 충돌 |
| 422 | 입력 형식·키·버전 오류 |
| 503 LEARNING_PROBLEM_INTEGRATION_PENDING | C 연결 대기 |
| 503 RETEACH_GENERATOR_UNAVAILABLE | 재설명 생성기 연결 대기 |
| 503 RETEACH_IN_PROGRESS | 첫 질문 생성 중 |
| 429 / 502 / 503 | 기존 Gemini 제한·출처 검증·생성 오류 |

V6는 nullable 재설명 참조와 learning_reteach 테이블을 추가합니다. V1~V5는 보존합니다. 기존 응답의 새 이력 필드는 빈 목록으로 읽고, 일반 메시지의 reteachId는 생략해 기존 Gemini 문맥과 구분합니다.

## 테스트

일반 build는 실제 PostgreSQL과 테스트 전용 C·AI 경계로 같은 문항 복귀, 최초 근거 보존, 재기동 복원, 반복 오답, C 상태 변경, 실패·재시도·동시 요청·실행 임대와 복귀 트랜잭션을 검증합니다. Gemini 요청에는 보완할 개념과 재설명 대화만 전달되는지도 확인합니다.

운영용 A·C 어댑터를 연결한 뒤 실제 강의자료·문항으로 전체 오답 → 재설명 → 같은 문항 재시도를 추가 검수하세요.
