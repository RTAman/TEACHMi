# C 연동용 학습 근거 조회

B가 저장한 실제 설명과 도움을 평가·리포트에서 사용할 수 있도록 제공하는 읽기 전용 계약입니다. AI 호출, 점수 계산, 문제·답안·리포트 생성은 하지 않습니다. 기존 세션 조회를 재사용하며 DB 마이그레이션과 새 환경 변수는 없습니다.

## HTTP와 서버 내부 호출

```http
GET /api/v1/sessions/{sessionId}/evidence
```

기존 CurrentMember/서버 Principal로 현재 회원을 확인합니다. 회원 ID를 쿼리나 본문으로 받지 않습니다. 미인증은 401, 타인 또는 없는 세션은 동일하게 404, 잘못된 UUID는 400입니다. 성공은 200이며 Cache-Control은 no-store입니다. Idempotency-Key는 필요하지 않습니다.

C의 서버 코드는 같은 공개 서비스를 사용할 수 있습니다.

```java
LearningEvidenceView evidence = learningEvidenceService.get(currentMemberId, sessionId);
```

currentMemberId는 인증 계층에서 얻어야 합니다. HTTP와 내부 서비스 모두 저장된 세션 소유권을 확인하며, 저장된 근거를 읽기 위해 A의 원문이나 C의 오답을 다시 조회하지 않습니다. 이 API가 읽혔다고 운영용 A·C 연결이 완료됐다는 의미는 아닙니다.

## 응답 규격

schemaVersion은 현재 1입니다. version은 이 스냅샷을 읽은 시점의 학습 세션 버전이고 updatedAt은 저장된 세션 수정 시간입니다. 매번 조회 시간을 생성하지 않으므로 저장된 데이터와 생성 상태가 같으면 결과도 같습니다. 실행 임대가 만료되면 진행 상태는 interrupted로 바뀔 수 있습니다.

| 필드 | 의미 |
| --- | --- |
| sessionId, version | 학습 식별자와 조회한 세션 버전 |
| materialId, conceptId, concept | 저장된 자료·개념 ID 및 개념 이름·범위·출처 ID 스냅샷 |
| mode, stage, status, updatedAt | 해당 스냅샷의 실제 진행 상태 |
| originalLearning | 최초 학습의 설명 진행 상태와 근거 |
| reteachHistory | 시간·ID 순서로 정렬된 재설명별 근거; 없으면 빈 배열 |
| pendingGeneration | 미완료 생성 작업; 없으면 null |

originalLearning은 state와 evidence를 제공합니다.

| originalLearning.state | 의미 |
| --- | --- |
| not_started | 설명 대화 메시지가 아직 없음; 모드 선택·처음 학습 요약도 해당 |
| in_progress | 설명 대화는 시작했지만 충분한 최종 설명이 아직 저장되지 않음 |
| completed | 충분한 wrapup 답변과 AI 응답이 함께 저장되어 최종 설명이 확정됨 |
| interrupted | 최종 설명을 확정하기 전에 학습이 종료됨 |

completed는 **B의 설명 대화 완료**입니다. 학습 세션 종료나 독립 평가 정답·이해도 점수를 뜻하지 않습니다. 처음에 도움을 요청했으면 첫 설명도 도움을 받은 설명일 수 있습니다. C는 단계를 별도로 검사하고 문제·답안 기록에 따라 평가해야 합니다.

## 실제 설명과 도움 집계

각 evidence에는 다음이 있습니다.

- initialAnswer: 실제 첫 사례 answer 메시지. 부족한 답변이라도 최초 값을 보존합니다. 아직 없으면 null입니다.
- finalExplanation: 성공한 wrapup의 실제 사용자 메시지. AI가 충분하다고 판정해 대화 전환까지 저장되기 전에는 null입니다.
- support.requested: 요청한 도움의 total·hint·example·dontKnow 횟수.
- support.delivered: 실제 응답까지 저장된 도움의 같은 종류별 횟수.
- support.events: 집계에 사용한 실제 SupportEventView 목록.

설명 메시지는 기존 LearningMessageView와 같으며 messageId·sequence·role·kind·chatStage·text·sourceChunkIds·createdAt·supportEventId·선택적 reteachId를 포함합니다. text를 요약하거나 고쳐 쓰지 않습니다. 사용자 설명의 sourceChunkIds는 비어 있을 수 있으며, 개념의 출처는 최상위 concept.sourceChunkIds를 참고합니다.

도움 예시는 아래와 같습니다. 이는 응답의 support 부분 예시입니다.

```json
{
  "requested": {"total": 3, "hint": 1, "example": 1, "dontKnow": 1},
  "delivered": {"total": 2, "hint": 1, "example": 1, "dontKnow": 0}
}
```

위 예시는 집계 필드만 표시했으며 실제 support에는 해당 이벤트 3개도 들어갑니다. responseMessageId가 없으면 요청만 저장된 것이므로 delivered에 포함하지 않습니다. 같은 키로 실패 요청을 재시도하면 기존 이벤트를 재사용하므로 중복 집계하지 않습니다. 이벤트에는 대상 질문·요청·응답의 ID와 요청 시간이 있습니다. 실제 도움 메시지 전문이 필요하면 기존 세션 조회를 사용하고, 별도로 받은 응답을 결합할 때는 version이 같은지 확인하세요.

저장된 ID가 다른 회차나 잘못된 역할의 메시지를 가리키면 500 LEARNING_EVIDENCE_INCONSISTENT로 실패합니다. 끊어진 연결을 빈 설명이나 제공된 도움으로 대신하지 않습니다.

## 재설명과 미완료 작업

각 reteachHistory 항목은 reteachId, problemId, originAttemptId, focus, state, createdAt, completedAt과 별도 evidence를 제공합니다. 최초 학습의 도움 횟수에는 재설명을 합산하지 않습니다. 같은 문항의 반복 오답도 attempt와 회차별로 구분합니다.

state는 generating·failed·chat·returned·interrupted입니다. returned는 B의 재설명이 완료됐다는 뜻이며 원래 문항의 재제출·정답을 뜻하지 않습니다. 종료된 학습의 미완료 재설명은 interrupted입니다. 이미 returned인 이력과 최초 학습의 완료 근거는 유지합니다.

pendingGeneration은 operation(turn 또는 reteach), id, reteachId, state, errorCode만 제공합니다. 실패·생성 중·임대 만료를 구분하고 완료된 것처럼 처리하지 않습니다. 재시도 키와 원래 요청 버전은 기존 GET /sessions/{id}의 pendingTurn 또는 activeReteach에서 읽으세요.

## C의 조회·저장 순서

준비된 리포트를 저장하고 학습을 종료할 때는 [상태 전환 서비스](ASSESSMENT_TRANSITIONS.md)가 B 잠금·버전 검사와 C의 DB 저장을 같은 트랜잭션으로 처리합니다. 리포트 준비와 종료 승인은 별도 동작입니다.

이 서비스는 REQUIRES_NEW, readOnly, REPEATABLE_READ로 **별도 트랜잭션의 커밋된 스냅샷**을 읽습니다. C가 READ_COMMITTED 트랜잭션 안에서 호출해도 버전·설명·도움·회차가 서로 다른 커밋에서 섞이지 않습니다. 바깥 트랜잭션의 미커밋 B 변경은 보이지 않으므로, B 변경을 커밋한 뒤 조회하세요. 바깥 트랜잭션에서 호출하면 스냅샷용 DB 연결 한 개가 추가로 필요합니다.

조회는 쓰기 잠금을 걸거나 결과를 영구 고정하지 않습니다. C가 나중에 문항 생성·채점·리포트를 저장한다면 조회한 version을 함께 보관하고, 저장 시 같은 회원의 B 잠금·현재 버전·단계·미완료 작업을 기존 계약대로 재확인해야 합니다. 이전 version의 자료와 새 도움 집계를 섞어서 평가하지 마세요. 동일 근거의 재현이 필요한 리포트는 C가 실제 사용한 이 응답과 version/schemaVersion을 함께 저장하세요.

C의 원래 독립 답안·판정, 재시도 답안, 점수와 리포트는 여전히 C가 구현해야 합니다. A의 실제 자료 소유권·원문 어댑터도 기존 연동 대기 상태를 유지합니다.

## 검증 범위

실제 PostgreSQL에서 최초·최종 설명 원문, 부족한 첫 답변 보존, 도움 종류별 요청/제공 집계, 실패·재시도·생성 중, 재설명 분리, 중단 이력, 앱 재기동, 소유권, 끊어진 ID 연결, 동시 변경 중 스냅샷과 읽기 전용 동작을 검증합니다. AI·C는 테스트 경계로 대체하며 이 기능의 검증에는 실제 Gemini 호출이 필요하지 않습니다.
