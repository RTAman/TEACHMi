# B 학습 세션 생성과 복원

이 문서는 [B 작업 이슈 #5](https://github.com/RTAman/TEACHMi/issues/5)의 첫 구현에 대한 프론트엔드·A·C 연동 계약입니다. 서버 실행은 [백엔드 실행 안내](README.md)를 따릅니다.

## API

| 메서드와 경로 | 요청 | 결과 |
| --- | --- | --- |
| `POST /api/v1/sessions` | 로그인 정보, `Idempotency-Key`, 아래 JSON | `201`, 생성한 학습과 `Location` 헤더 |
| `GET /api/v1/sessions/{sessionId}` | 로그인 정보 | `200`, 저장된 개념 사본·단계·버전·재개 위치 |
| `PUT /api/v1/sessions/{sessionId}/mode` | 로그인 정보, `Idempotency-Key`, `mode`, `expectedVersion` | `200`, 요약 또는 첫 사례 질문과 갱신된 학습 |
| `POST /api/v1/sessions/{sessionId}/chat/start` | 로그인 정보, `Idempotency-Key`, `expectedVersion` | `200`, 처음 학습의 설명 시작과 저장된 첫 질문 |

```json
{
  "materialId": "m01",
  "conceptId": "c01"
}
```

자료·개념 ID는 A의 타입이 정해지기 전까지 최대 128자의 문자열로 전달합니다. 학습 세션 ID는 서버가 생성하는 UUID입니다. 회원 ID는 요청 본문에서 받지 않고 인증 연결 인터페이스를 통해 구합니다. 숫자형 A ID도 인터페이스에서 문자열로 변환할 수 있습니다.

첫 응답은 `mode: null`, `stage: "mode"`, `status: "active"`, `version: 0`입니다. `concept`에는 A가 검증한 `name`, `scope`, `sourceChunkIds`가 저장되고 `createdAt`·`updatedAt`은 UTC 시각입니다. 응답에서 받은 세션 ID로 GET을 호출하면 저장한 상태를 복원합니다.

```json
{
  "stage": "mode",
  "path": "/api/v1/sessions/서버가-반환한-UUID"
}
```

위 객체는 응답의 `resume` 값입니다. `path`는 복원 상태를 조회하는 API 주소이고 프론트엔드는 `stage`로 화면을 선택합니다. 종료된 학습은 읽을 수 있으며 `resume`은 `null`입니다.

## 모드 선택과 설명 시작

세션 생성 직후 받은 `version`을 다음 요청의 `expectedVersion`으로 전달합니다. 각 변경에는 새 키를 만들고 같은 변경을 재전송할 때 해당 키를 유지합니다.

```http
PUT /api/v1/sessions/{sessionId}/mode
Idempotency-Key: choose-mode-1
Content-Type: application/json

{"mode":"first","expectedVersion":0}
```

`first`는 근거 있는 요약 `summary.text`, 예시 `examples`, 핵심 용어 `keyTerms`, 생각할 점 `reflectionPrompt`, 출처 `sourceChunkIds`를 저장하고 `stage: "summary"`, `version: 1`을 반환합니다. 사용자가 요약을 읽고 설명 시작을 누르면 다음을 보냅니다.

```http
POST /api/v1/sessions/{sessionId}/chat/start
Idempotency-Key: start-chat-1
Content-Type: application/json

{"expectedVersion":1}
```

첫 사례 질문을 저장한 후 `stage: "chat"`, `chatStage: "case"`, `version: 2`가 됩니다. `messages`에는 질문의 `messageId`, `sequence: 1`, `role: "assistant"`, `kind: "case_question"`, `text`, 출처, 작성 시각이 있습니다.

`mode: "review"`를 고르면 요약 단계를 건너뛰어 같은 형태의 첫 질문과 `stage: "chat"`, `chatStage: "case"`, `version: 1`을 바로 반환합니다. `summary`는 `null`입니다. 세션 GET도 요약과 메시지를 함께 반환하므로 앱을 다시 열 때 저장된 질문을 표시할 수 있습니다.

선택 후 모드를 바꾸거나 요약 전에 설명 시작을 호출하면 409입니다. 종료된 학습도 전환할 수 없습니다. 새 키와 오래된 버전으로 보내면 최신 상태를 포함한 `409 SESSION_STATE_CONFLICT`가 나옵니다. 최초와 같은 키·경로·본문은 저장된 성공 응답을 재사용합니다. 이미 설명을 시작한 처음 학습을 현재 버전과 새 키로 다시 열어도 기존 질문·버전을 돌려줍니다.

생성 요청과 단계 전환 요청은 회원별 키 공간을 공유합니다. 같은 키를 다른 세션·다른 API에 재사용하면 409이므로 각각의 변경에 새 키를 사용하세요. GET으로 현재 상태를 확인한 뒤 충돌을 해소합니다.

## B의 콘텐츠 생성 연결

`LearningContentGenerator` Spring bean을 실제 AI 호출 어댑터로 연결합니다. `summarize(Context)`는 처음 학습 자료를, `openingQuestion(Context)`는 AI 학생의 첫 사례 질문을 반환합니다. Context에는 회원·학습·자료·개념 ID, 선택한 모드, 개념 사본, 저장된 요약이 담겨 있습니다. 어댑터는 A의 소유권 검사된 원문 조회를 사용해 실제 텍스트를 얻고, 외부 모델 호출에는 제한 시간과 오류 처리를 설정합니다.

요약의 각 텍스트와 예시·용어 목록을 검증하고, 요약·질문의 출처 목록이 비어 있거나 선택 개념의 출처 밖이면 502를 반환합니다. 생성과 저장이 성공한 경우에만 세션 단계·버전과 메시지·재시도 응답을 함께 커밋합니다. 실패 시 같은 키로 다시 요청할 수 있습니다.

생성기 미연결은 `503 LEARNING_CONTENT_UNAVAILABLE`, 생성 호출 오류는 `502 LEARNING_CONTENT_FAILED`, 잘못된 출처는 `502 INVALID_LEARNING_CONTENT`입니다. 모두 기존 단계와 버전을 유지합니다. 테스트는 생성기를 대체하며 실제 모델의 질문 품질은 어댑터 연결 후 검수합니다. 이후 사용자 답변·도움 요청 처리는 `/turns` 작업에서 추가합니다.

## 충돌과 재시도

POST의 `Idempotency-Key`는 회원별로 구분하며 1~128자입니다. 한 번 시작할 때 키를 만들고 네트워크 오류로 재전송할 때 같은 키와 요청을 유지합니다. 같은 키에 같은 요청이면 최초의 `201` 응답을 재사용하고, 다른 요청이면 `409 IDEMPOTENCY_KEY_REUSED`를 반환합니다. 최초 응답 뒤에 학습 상태가 바뀌었을 수 있으므로 현재 진행 단계는 GET으로 확인합니다.

진행 중인 학습이 있으면 `409 ACTIVE_SESSION_EXISTS`와 `currentSession`에 세션 ID·현재 단계·버전을 제공합니다. 사용자가 새 시작을 확인한 경우, 새 키로 다음 요청을 보냅니다.

```json
{
  "materialId": "m01",
  "conceptId": "c02",
  "replaceSessionId": "409-응답에서-받은-UUID",
  "expectedVersion": 0
}
```

교체할 학습과 버전이 일치하면 기존 상태를 `abandoned`로 바꾸고 새 학습을 한 트랜잭션에서 만듭니다. 다른 탭에서 이미 학습을 바꿨으면 409를 반환합니다. 개념 소유권 검사나 저장이 실패하면 기존 학습이 유지됩니다. 회원별 PostgreSQL 트랜잭션 잠금과 진행 중 회원의 고유 인덱스로 동시 시작·교체를 제어합니다.

| HTTP 상태 | 의미 |
| --- | --- |
| 401 | 인증이 없거나 A의 인증 연결이 만료를 판정 |
| 404 | 자료·개념 또는 세션이 없거나 다른 회원의 기록 |
| 409 | 진행 중 학습·버전 충돌 또는 재시도 키의 다른 요청 |
| 422 | 빈 ID, 잘못된 교체 조건, 누락되거나 너무 긴 재시도 키 |
| 400 | 잘못된 JSON 또는 UUID 형식 |
| 503 | A의 자료 조회 연결이 준비되지 않음 |
| 502 | 콘텐츠 생성 실패 또는 잘못된 생성 결과 |

## A가 연결할 인터페이스

실제 Gemini 생성 어댑터와 A의 원문 조회 연결은 [Gemini 콘텐츠 생성 안내](GEMINI_CONTENT.md)를 확인하세요.

`learning.CurrentMember`의 `requireId(HttpServletRequest)`를 구현한 Spring bean에서 실제 인증 세션의 만료·폐기를 확인하고 회원 ID를 반환합니다. 기본 경로는 Servlet의 인증된 `Principal.getName()`을 회원 ID로 읽습니다. 로그인 방식이 정해지면 A의 어댑터로 명시적으로 매핑합니다. 쿠키 발급·로그아웃·CSRF 방어는 A의 인증 계층에서 연결합니다.

`learning.ConceptSource`의 `loadOwnedConcept(memberId, materialId, conceptId)`를 구현한 Spring bean에서 다음을 확인합니다.

1. 자료가 현재 회원의 소유인지 확인합니다.
2. 개념이 해당 자료에 속하고 학습 가능한 상태인지 확인합니다.
3. 이름·범위·출처 ID 목록을 `ConceptSource.Snapshot`으로 반환합니다.

없거나 타인 소유인 자료·개념은 `LearningException.notFound()`를 던집니다. 어댑터 없이 로그인한 회원이 생성을 요청하면 503이 나옵니다. 테스트용 개념 소스는 `src/test/`에 두었습니다.

## C와 DB 변경

C가 평가·리포트 저장 후 report 단계와 completed 상태로 전환하는 내부 서비스는 [평가·리포트 상태 전환 계약](ASSESSMENT_TRANSITIONS.md)을 확인하세요.

대화의 최초·최종 설명 ID, 도움 이벤트, 재시도 중인 전송 상태는 [대화 API 계약](CONVERSATION_TURNS.md)을 확인하세요.

C는 아래 진행 중 학습 조회로 세션 ID를 찾고 홈 응답에 연결할 수 있습니다. 프론트엔드는 반환된 상태를 사용하거나 해당 ID로 기존 GET을 호출해 재개합니다. 서버 내부에서는 `LearningSessionService.get(memberId, sessionId)`로 같은 소유권 검사를 적용할 수 있습니다.

### 세션 ID 없이 진행 중 학습 찾기

```http
GET /api/v1/sessions/active
```

기존 CurrentMember 또는 인증된 서버 Principal의 회원 ID만 사용합니다. 클라이언트의 memberId 파라미터로 조회 대상을 바꾸지 않습니다. Idempotency-Key와 본문은 필요하지 않습니다.

| 응답 | 의미 |
| --- | --- |
| 200 SessionView | 현재 회원의 status=active인 학습 한 건 |
| 204, 빈 본문 | 진행 중 학습이 없음; 학습을 새로 만들거나 과거 기록을 대신 반환하지 않음 |
| 401 | 인증 정보가 없거나 유효하지 않음 |

200과 204 모두 Cache-Control: no-store입니다. `/active`는 고정 경로이며 UUID 세션 조회와 구분합니다. 기존 세션 API와 SessionView 규격은 유지합니다.

200 본문은 같은 시점의 `GET /api/v1/sessions/{sessionId}`와 동일한 SessionView입니다. sessionId·자료/개념·mode·stage·version·resume·availableActions뿐 아니라 요약·대화·도움·미완료 전송·재설명·문항 복귀 정보도 포함합니다. resume.path는 복원할 **API 경로**입니다. 프론트의 실제 화면 경로는 프론트 라우터에 연결하세요.

- 모드 미선택은 select_mode, 처음 학습 요약은 start_chat을 제공합니다.
- 대화 생성 중에는 wait, 실패·임대 만료 시에는 retry_turn을 제공합니다. pendingTurn의 저장된 입력 ID·원래 키·원래 expectedVersion으로 재시도합니다.
- 재설명 첫 질문 생성 중·실패·임대 만료도 activeReteach에서 복원합니다. 재시도에는 activeReteach의 원래 키·버전·문항·시도를 사용합니다.
- 재설명 완료 후 같은 문항 복귀는 problemReturn과 retry_problem을 유지합니다.
- status가 completed 또는 abandoned인 세션은 제외합니다. problem 단계나 아직 active인 report 단계는 계속 조회 대상입니다. 설명 대화가 완료됐다는 이유만으로 세션을 종료된 것으로 판단하지 않습니다.

C의 홈에서 사용할 공통 서비스는 다음과 같습니다.

```java
Optional<SessionView> active = learningSessionService.getActive(currentMemberId);
```

currentMemberId는 서버 인증에서 얻습니다. C는 자기 홈 응답에 필요한 sessionId·mode·stage·resume·availableActions 등을 반영하고, 비어 있으면 진행 중 학습이 없는 상태를 표시하세요. A·C·Gemini 생성 어댑터가 아직 없어도 저장된 학습은 읽을 수 있습니다. 이 조회는 해당 어댑터의 구현 완료를 의미하지 않습니다.

조회는 별도 REQUIRES_NEW·readOnly·REPEATABLE_READ 트랜잭션에서 수행해 C의 기존 READ_COMMITTED 트랜잭션 안에서도 세션과 복원 기록이 같은 커밋 스냅샷을 사용합니다. 바깥 트랜잭션의 미커밋 B 변경은 보이지 않으므로 B 변경 커밋 후 호출하세요. 바깥 트랜잭션이 있다면 조회용 DB 연결 한 개가 추가로 필요합니다.

쓰기 잠금·AI 호출·버전 갱신·요청 이력 저장은 하지 않습니다. 기존 회원별 active 고유 인덱스를 사용하므로 DB 마이그레이션과 새 환경 변수도 없습니다. 동시에 새 학습으로 교체하면 조회 시작 시점의 이전 세션이 반환될 수 있습니다. 이후 변경 요청에 409가 발생하면 다시 active 또는 기존 GET으로 상태를 확인하세요. 조회 결과가 이후 쓰기의 성공을 보장하지는 않습니다.

실제 PostgreSQL 테스트에서 회원 격리·빈 결과·종료 기록 제외·기존 단계/실패/재설명 복원·읽기 전용·앱 재기동과 동시 세션 교체 중 스냅샷을 검증합니다. C의 실제 종료/리포트 구현과 프론트 홈 연결은 각 담당자의 후속 작업입니다.

### DB 변경

`V2__create_learning_sessions.sql`에 B의 세션과 생성 요청 재시도 테이블을 정의했습니다. 회원·자료·개념 FK는 A의 ID 타입과 테이블명이 확정되면 별도 마이그레이션에서 연결합니다. PR 병합 전 A·C의 마이그레이션과 V2 번호 충돌을 확인하세요.

`V3__add_learning_preparation_and_messages.sql`은 요약·대화 단계와 메시지 테이블을 추가합니다. V2의 기존 학습은 새 필드가 비어 있는 상태로 유지되고, 이전 버전에서 저장한 생성 응답도 재전송할 수 있습니다. 후속 메시지는 세션별 순번을 이어 사용하세요. V3 번호도 A·C의 변경과 함께 확인합니다.

## 확인 방법

DB를 켠 뒤 `backend/`에서 Windows는 `.\gradlew.bat build`, macOS·Linux는 `./gradlew build`를 실행합니다. 테스트에서 A의 개념 조회를 대체하고 실제 PostgreSQL로 생성·회원 격리·동시 요청·명시적 교체·실패 복구를 검사합니다. 새 애플리케이션 인스턴스가 기존 DB의 단계와 개념 사본을 읽는지도 확인합니다.

모드 전환 테스트는 처음·복습 분기, 요약과 첫 질문의 앱 재기동 복원, 동시 선택·설명 시작, 키 재사용, 오래된 버전, 생성 실패 후 재시도, 출처 검증, 생성기 미연결을 포함합니다.
