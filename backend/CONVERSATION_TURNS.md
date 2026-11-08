# B 대화 API

`POST /api/v1/sessions/{sessionId}/turns`의 새 전송은 로그인한 회원의 진행 중 chat 학습에서만 사용할 수 있습니다. 기존 회원 확인·세션 소유권·Gemini 원문 검증 계약을 사용합니다.

## 전송

```http
POST /api/v1/sessions/{sessionId}/turns
Idempotency-Key: answer-1
Content-Type: application/json

{"kind":"answer","text":"사용 기간에 맞춰 원가를 나누어 배분합니다.","expectedVersion":1}
```

`expectedVersion`에는 GET 또는 직전 성공 응답의 version을 넣습니다. answer는 공백이 아닌 최대 4,000자의 text가 필요합니다. 도움 요청은 text를 생략합니다.

| kind | 동작 |
| --- | --- |
| answer | 현재 질문에 대한 설명을 기록하고 충분한지 판정 |
| hint | 한 가지 실마리와 설명을 유도하는 질문 |
| example | 원문 규칙에 근거한 쉬운 사례 |
| dont_know | 난도를 낮춘 짧은 보충·선택지와 재질문 |

예를 들어 힌트는 `{"kind":"hint","expectedVersion":1}`입니다. 도움은 단계를 완료하지 않습니다. 동일 전송을 재시도할 때는 같은 키·본문·원래 버전을 유지하세요. 다른 동작·본문·세션에 같은 키를 사용하면 409입니다.

## 저장과 단계

입력을 먼저 별도 트랜잭션으로 커밋한 뒤 AI를 호출합니다. 성공하면 AI 응답과 학습 단계·완료 응답을 함께 커밋합니다. 사용자 입력 저장과 AI 응답 저장이 각각 버전을 갱신하므로 반환된 version을 사용하고 증가량을 추측하지 마세요.

| 현재 chatStage | 충분한 answer 후 | 부족한 answer·도움 후 |
| --- | --- | --- |
| case | concept 질문 | case에서 보충 |
| concept | reason 질문 | concept에서 보충 |
| reason | wrapup 설명 요청 | reason에서 보충 |
| wrapup | stage를 problem으로 변경 | wrapup에서 보충 |

모델은 현재 단계의 충분 여부와 응답만 제공합니다. 서버는 위 순서로 한 단계씩 이동합니다. wrapup이 충분하면 C의 문제 생성에 사용할 `start_problems` 행동을 반환합니다. C는 이 상태에서 독립 문항을 생성합니다. 문제 풀이·리포트 단계의 새로운 전송은 409로 거절됩니다. 성공한 과거 전송의 재시도는 저장된 성공 응답을 반환합니다.

성공 응답은 기존 SessionView에 다음 필드를 더합니다. GET도 같은 데이터를 반환합니다.

GET은 PostgreSQL의 같은 조회 스냅샷에서 단계·메시지·도움·전송 상태를 읽습니다. 동시에 전송을 처리해도 서로 다른 커밋의 상태가 섞이지 않도록 합니다.

- messages의 순번, 역할, kind, chatStage, 원문, 출처 ID와 supportEventId
- initialAnswerId와 finalExplanationId
- supportEvents의 도움 종류, 대상 질문·요청 메시지·응답 메시지 ID
- pendingTurn과 availableActions

최초 사례 answer ID는 첫 실제 사용자 설명을 가리키며 후속 수정으로 덮어쓰지 않습니다. 최종 설명 ID는 충분한 wrapup의 실제 사용자 메시지입니다. 도움을 받은 직후 설명의 supportEventId로 도움 맥락을 구분할 수 있습니다.

도움 요청도 사용자 메시지로 기록합니다. supportEvents는 요청 시 한 번 생성하고 성공 시 responseMessageId를 연결합니다. C가 실제 제공된 도움 횟수를 집계할 때는 responseMessageId가 있는 이벤트를 사용하세요. 응답 ID가 비어 있으면 아직 도움을 제공하지 못한 요청입니다.

## 오류와 재접속

AI 오류·한도·A 연동 대기에서도 전송한 사용자 입력은 DB에 남습니다. 오류 응답의 currentSession 또는 GET에서 pendingTurn을 확인하세요.

```json
{
  "turnId": "서버가 반환한 UUID",
  "kind": "answer",
  "state": "failed",
  "userMessageId": "저장된 사용자 메시지 UUID",
  "errorCode": "AI_GENERATION_UNCERTAIN",
  "idempotencyKey": "answer-1",
  "expectedVersion": 1
}
```

재접속한 클라이언트는 저장된 userMessageId로 text를 읽고, pendingTurn의 키·원래 버전·kind로 같은 요청을 구성합니다. 도움 요청이면 text를 생략합니다. 새로운 GET version으로 원래 본문을 바꾸면 다른 요청으로 간주되므로 원래 expectedVersion을 사용하세요.

생성 중에는 pendingTurn.state가 generating이고 availableActions는 wait입니다. 같은 전송의 동시 요청은 `503 TURN_IN_PROGRESS`, 다른 전송은 409입니다. 일반 생성 실패는 retry_turn을 반환하지만 AI_GENERATION_UNCERTAIN·AI_ATTEMPT_LIMIT은 wait로 복원합니다. 성공한 키의 재전송은 저장한 200 응답을 반환하므로 최신 진행 상태는 GET으로 확인합니다.

B의 AI 오류와 세션 GET에는 선택적 `recovery`가 추가됩니다. 기존 code·message·currentSession·availableActions와 필수 응답 필드는 유지합니다. 일반 처리 중/성공 응답에는 이 필드가 생략됩니다. 기존 성공 영수증과 22인자 SessionView 생성자도 계속 사용할 수 있습니다.

```json
{"stage":"TRANSPORT","diagnosticCode":"TRANSPORT_REQUEST_TIMEOUT","retryAllowed":false,"action":"review_required"}
```

`review_required`이면 저장된 입력을 보여주고 재전송을 중단합니다. 운영자는 같은 시각의 공급자 사용량과 원장을 확인해야 합니다. 동일 키로 다시 전송해도 불확실한 전송/재설명의 실행 토큰을 다시 열지 않습니다. 모델·프롬프트·출력 한도를 바꾼 새 생성 키도 같은 학습의 uncertain 또는 running 기록을 우회할 수 없습니다. 이 변경은 기존 원장을 초기화하거나 uncertain을 성공·실패로 판정하는 관리 API를 제공하지 않습니다.

`retry_same_request`는 원문 인용 검증 등 실패에 대한 **사용자의 명시적 재전송**만 뜻하며 시도·사용량 한도가 적용됩니다. 기존 키·kind·본문·원래 expectedVersion을 유지하고 자동 반복하지 마세요. `wait`는 진행 중 결과/사용량 대기, `check_configuration`은 접근권한·모델 설정 확인, `limit_reached`는 시도 한도 도달을 뜻합니다.

### 로딩과 확인 대기 구분

`availableActions=["wait"]`만 보고 스피너를 켜거나 폴링하지 마세요. 호환성을 위해 유지한 동작 이름이며 **자동으로 완료되지 않는 보류**에도 사용됩니다. 실패한 대화는 pendingTurn.errorCode, 재설명 시작은 activeReteach.errorCode를 함께 봅니다. 오류 응답의 code와 recovery, 재접속 GET의 recovery가 우선입니다.

| 조건 | 화면과 안내 | 폴링·재호출 |
| --- | --- | --- |
| AI_GENERATION_UNCERTAIN 또는 action=review_required | 정적 확인 필요 안내: "생성 결과 확인이 필요합니다. 입력한 답변은 저장되어 있습니다. 다시 요청하지 말고 지원 담당자에게 문의해 주세요." | 스피너 종료, 자동 GET/POST 중단, 재시도 비활성화 |
| AI_ATTEMPT_LIMIT 또는 action=limit_reached | 정적 한도 안내: "이 답변의 생성 시도 한도에 도달했습니다. 저장된 답변을 확인하고 지원 담당자에게 문의해 주세요." | 스피너 종료, 자동 GET/POST 중단. 다음 날·120초 후 자동 해제 안내 금지 |
| 실제 pending/재설명 상태가 generating이고 위 보류 조건이 없음 | "답변을 생성하고 있습니다." | 예: GET 2초 간격, 최대 30회/60초까지만. POST 자동 재전송 금지 |
| interrupted·일반 failed | 저장된 입력과 오류를 표시하고 스피너 종료 | 자동 POST 금지. 서버가 허용하는 경우에만 원래 키·본문으로 사용자 명시 재시도 |
| 위 조건도 아니고 wait만 있음 | 진행 상태를 확인하는 정적 화면 | 무한 로딩 금지, 사용자 선택으로 GET 한 번 조회 가능 |

폴링 상한이 끝나면 "처리가 지연되고 있습니다. 저장된 내용을 확인해 주세요."를 표시하고 자동 조회를 종료합니다. 조회 종료는 공급자 호출 취소나 미과금 판정을 뜻하지 않습니다. 직접 누르는 상태 확인 GET은 허용하되, 자동 타이머를 다시 시작하거나 생성 POST로 바꾸지 않습니다.

구버전 서버/영수증에 recovery가 없더라도 두 터미널 오류 코드를 먼저 분기합니다. 재접속 GET은 저장된 오류 코드에서 review_required/limit_reached를 복원하며, generating처럼 보이는 오래된 힌트보다 이 오류가 우선합니다. 사용자에게는 원문·키·내부 원장 SQL을 노출하지 말고 학습 기록 보기·지원 문의를 제공합니다. [운영자 확인 절차](GEMINI_CONTENT.md#운영자-확인과-안전한-조치)를 따르며, 현재 API는 uncertain 영수증을 해제하거나 pending 답변에 결과를 강제로 붙이는 기능을 제공하지 않습니다.

활성 학습의 GET과 /active는 현재 회원·학습의 미확인 원장도 읽기 전용으로 확인합니다. 모드 선택·처음 질문·C 생성 등 pendingTurn이 없는 단계의 uncertain 및 임대가 만료된 running도 review_required·wait로 복원합니다. 생성 설정이 꺼져 있어도 기존 보류를 숨기지 않습니다. 진행 중 running은 retryAllowed=false인 wait 안내만 제공하며 자동 재전송을 허용하지 않습니다. 성공/일반 실패 영수증은 이 조회로 세션 전체를 차단하지 않고, 완료 이력의 응답은 유지합니다.

멱등 재전송은 과거 성공 응답을 반환할 수 있으므로 재접속 진행 화면은 최신 GET을 사용합니다. C 오류 응답에 recovery가 없더라도 AI_GENERATION_UNCERTAIN·AI_ATTEMPT_LIMIT 코드에서 먼저 로딩과 자동 재전송을 멈춥니다. 최신 GET으로 보류를 확인할 수 있으며 원장을 초기화하거나 키·모델·새 학습으로 우회하지 않습니다.

120초 실행 임대가 만료되면 interrupted 상태로 표시하고 같은 전송을 새 실행 토큰으로 이어갈 수 있습니다. 이전 작업자는 토큰이 바뀐 뒤 응답을 저장할 수 없습니다. Gemini의 처리 여부가 불명확한 기록은 기존 호출 기록 정책에 따라 자동 재호출이 보류됩니다. [Gemini 재시도 정책](GEMINI_CONTENT.md)

생성 중 사용자가 새 학습 시작을 명시적으로 선택하면 기존 학습을 종료합니다. 이전 AI 응답은 종료된 학습의 단계·메시지를 갱신할 수 없습니다.

| HTTP | 의미 |
| --- | --- |
| 200 | 메시지·단계 저장 완료 또는 성공 응답 재사용 |
| 401 / 404 | 인증 필요 / 타인 또는 없는 학습 |
| 409 | 오래된 버전, 잘못된 단계, 미완료 전송, 키 충돌 |
| 422 | 잘못된 kind·text·버전·키 또는 너무 큰 AI 문맥 |
| 429 | 기존 Gemini 사용량·시도 제한 |
| 502 | 생성 결과 오류, 입력은 보존 |
| 503 | 생성 중·생성기/A 원문 미연결·불확실한 원격 결과 |

## Gemini와 C 연결

C는 [학습 근거 조회](LEARNING_EVIDENCE.md)로 최초·최종 설명 원문과 실제 제공된 도움을 같은 스냅샷에서 읽을 수 있습니다. 재설명은 회차별로 분리하며 이 조회가 점수를 계산하지는 않습니다.

오답 뒤에는 [재설명 API](RETEACH.md)로 시작한 별도 기록에서 같은 turns를 사용합니다. 재설명 발화·도움은 reteachId로 구분하고 최초 학습 근거를 보존합니다.

`LearningDialogueGenerator`는 테스트에서 대체할 수 있습니다. Gemini 어댑터는 기존 콘텐츠 생성 인터페이스와 함께 대화 인터페이스를 구현합니다. 현재 질문, 최근 최대 20개·24,000자 이내 메시지, 실제 원문으로 응답을 만들고 출처·인용문·판정 타입을 검증합니다. 모든 메시지는 DB에 보존하고 AI에 전달하는 최근 문맥만 제한합니다.

A의 직접 입력 자료·원문은 `MaterialLearningSources`로 연결됐습니다. 실제 로그인 쿠키와 변경 요청의 X-CSRF-Token이 필요하며, CSRF 거절은 사용자 입력 저장 전에 발생합니다. 원문 어댑터가 비활성화된 환경에서는 입력을 보존하고 503을 반환합니다. C는 최초·최종 메시지와 도움 이벤트로 리포트 근거를 구성하고, problem 진입 후 문항·채점·리포트를 구현합니다. C의 단계 변경은 현재 버전과 pendingTurn 부재를 확인해야 합니다.

V5는 전송·도움 이벤트와 nullable 복원 필드를 추가합니다. V1~V4는 보존합니다. 이전 생성·모드 응답의 JSON도 읽을 수 있도록 새 목록 필드는 빈 목록으로 초기화합니다.

## 테스트

일반 `build`는 AI를 대체하고 실제 PostgreSQL로 단계·도움·복원·AI 실패·재시도·동시 요청·임대 만료·새 학습 교체를 검사합니다.

별도 실 API 검증은 합성 원문과 테스트 전용 A 연결을 사용합니다. 준비한 전용 DB·Gemini 환경 변수로 다음을 명시적으로 실행하면 정상 경로에서 첫 질문·힌트·답변 처리로 실제 호출 3건이 발생합니다.

```powershell
cd backend
$env:GEMINI_LIVE_TEST = "true"
.\gradlew.bat geminiLiveTest --tests com.teachmi.backend.learning.gemini.GeminiTurnsLiveTest
```

실 API 테스트는 일반 build·CI에서 제외됩니다. 사용자 문서를 보내지 않으며, 테스트의 A 연결은 운영용 구현과 구분합니다.
