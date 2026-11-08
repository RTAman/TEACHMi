# Gemini 콘텐츠 생성

Gemini 어댑터는 기존 `LearningContentGenerator`를 구현합니다. 기존 모드 선택·설명 시작 API의 요청과 응답 형식을 사용하고 요약과 첫 질문을 DB에 저장합니다. GET과 성공 요청의 재전송은 저장한 내용을 돌려줍니다.

B와 향후 소비자가 공유할 Java 호출 계약은 [공통 Gemini 포트](COMMON_GEMINI_PORT.md)의 GeminiGenerationPort입니다.
기존 B 생성기와 C 문제 생성·채점은 같은 실행 엔진·원문/출처 검증·V4 사용량 원장을 사용합니다. B 초기 질문은 question-v2, 대화/재설명의 새 요청은 v4이며 기존 성공 영수증과 생성 키 계산을 유지합니다. [초기 인용·타임아웃 분석](B_OPENING_TIMEOUT.md)을 참고하세요.

## A 원문 연결

A의 `MaterialLearningSources`가 `LearningSourceReader`를 구현합니다. 직접 입력 및 PDF/PPT/PPTX 분석 자료에서 회원의 소유권, 개념과 자료의 관계, 요청한 원문 조각의 소속을 검증한 뒤 실제 텍스트를 반환합니다. A의 인증·자료 사용 방법은 [연동 계약](AUTH_MATERIALS.md)을 참고하세요.

`OwnedSource(memberId, materialId, conceptId, chunks)`의 회원·자료·개념은 요청과 일치해야 합니다. 각 Chunk에는 고유 ID와 비어 있지 않은 원문을 넣습니다. ID 집합은 세션 생성 시 `ConceptSource`가 제공한 출처 ID와 정확히 일치해야 합니다. 없거나 타인 소유인 자료는 `LearningException.notFound()`를 던집니다.

미연결·원문 누락은 Gemini 호출 전에 503으로 차단합니다. 인증은 A의 `CurrentMember` 계약을 사용합니다. 운영용 샘플 원문은 제공하지 않습니다. 실제 강의자료를 Google에 전달하는 기능이므로 팀이 정한 자료 이용 범위에 맞춰 활성화하세요.

## 환경 변수

키는 OS 환경 변수 또는 Git에서 제외된 `backend/.env`에 넣습니다. 채팅·이슈·PR·예제 파일에 키를 적지 마세요. 예제 파일의 키와 모델은 빈 값입니다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| GEMINI_ENABLED | false | 어댑터 활성화 |
| GEMINI_API_KEY | 빈 값 | Google API 키 |
| GEMINI_MODEL | 빈 값 | 계정에서 generateContent를 지원하는 모델 ID |
| GEMINI_CONNECT_TIMEOUT_SECONDS | 3 | 연결 제한, 1~10초 |
| GEMINI_REQUEST_TIMEOUT_SECONDS | 20 | 연결·헤더·전체 본문 완료까지의 단일 제한, 1~60초 |
| GEMINI_REQUESTS_PER_MINUTE | 10 | 동일 DB를 쓰는 앱 전체의 최근 1분 호출 한도 |
| GEMINI_REQUESTS_PER_DAY | 100 | 앱 전체 UTC 날짜별 호출 한도 |
| GEMINI_MEMBER_REQUESTS_PER_DAY | 20 | 회원별 UTC 날짜별 호출 한도 |
| GEMINI_MAX_ATTEMPTS | 2 | 같은 콘텐츠의 명시적 생성 시도 한도, 최대 3 |
| GEMINI_MAX_SOURCE_CHARS | 16000 | 실제 원문의 글자 수 상한 |
| GEMINI_MAX_OUTPUT_TOKENS | 2048 | 전역 최대 출력 토큰, 최대 8192 |
| GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS | 2048 | 새 B 질문·대화·재설명 상한(256–8192). 전역 상한과 작은 값 사용; 요약/C는 전역 상한 유지 |

요청 수는 전송 전에 예약하고 실패한 호출도 집계합니다. Google 자체 제한은 프로젝트·모델·사용 등급에 따라 적용됩니다. 앱의 UTC 한도는 같은 DB를 쓰는 TEACHMi 호출을 집계합니다. 실제 청구 한도와 다른 앱의 사용량은 Google 프로젝트에서도 관리하세요. [Google 호출 제한](https://ai.google.dev/gemini-api/docs/rate-limits)

## 원문과 응답 검증

API 키는 고정 Google HTTPS 주소의 `x-goog-api-key` 헤더로만 전달하고 외부 리다이렉트를 따르지 않습니다. 처음 학습은 요약·예시·핵심 용어·생각할 점을, 복습은 학생 역할의 구체적인 사례 질문을 생성합니다. 질문에서 정답이나 풀이를 공개하지 않도록 요청합니다. [Gemini API](https://ai.google.dev/api/generate-content)

JSON 스키마를 지정하고 서버에서 필수 필드·타입·목록·길이를 다시 검사합니다. 후보는 하나, 종료 사유는 STOP이어야 합니다. 차단·잘린 응답·잘못된 JSON·예상 밖 필드는 거절합니다. 원문의 지시를 실행하지 않도록 시스템 지시와 원문 데이터를 분리합니다.

응답의 각 출처 ID는 선택 개념의 실제 원문에 존재해야 합니다. 각 ID에는 최소 6글자의 근거 인용문이 필요하며 해당 원문의 정확한 부분 문자열인지 검사합니다. 설명의 의미적 정확성과 학생 역할의 질문 품질은 실제 수업 자료로 추가 검수합니다.

## 사용량·재시도·중복 생성

기존 API의 Idempotency-Key·학습 버전 검사를 유지합니다. 내부 생성 키에는 회원·세션·자료·개념·모드·원문·저장된 요약·모델·프롬프트 버전을 반영하며 새 질문·대화와 공통 포트의 JSON을 정렬해 재기동 후에도 같은 키를 만듭니다. 과거 단독 v1의 필드 순서 변형은 읽기 전용 캐시 조회로 보완합니다.

V4의 `learning_ai_generation`·`learning_ai_call`에 상태·검증된 결과·시도 수·사용량을 저장합니다. 세션 트랜잭션과 별도로 커밋하므로, AI 결과 저장 뒤 세션 변경이 롤백돼도 재요청은 저장한 결과를 사용할 수 있습니다. 캐시 조회 전에도 A의 현재 소유권과 원문을 확인합니다.

기록 저장에는 기존 spring.datasource 접속 정보로 만든 전용 최대 2개 연결 풀을 씁니다. 같은 회원의 동시 요청이 세션 풀을 차지해도 기록 저장이 교착되지 않게 분리했습니다. 실행 중 같은 생성 키는 중복 호출하지 않습니다.

잘못된 AI 응답은 사용자 명시 재시도로 다시 생성할 수 있으며 최대 시도 수와 호출 한도를 적용합니다. 429의 Retry-After 초 값을 최대 1시간까지 반영하고 해석할 수 없으면 60초를 적용합니다. 내부 자동 반복 호출과 긴 대기는 수행하지 않습니다.

타임아웃·연결 단절·Google 5xx는 원격 처리·과금 여부를 확인하기 어려워 `uncertain`으로 기록하고 재호출을 보류합니다. 앱이 종료되어 120초 임대가 만료된 running 기록도 같은 방식으로 보류합니다. 운영자는 아래 절차로 사용량과 원장을 확인합니다. 확인 자체가 재호출을 허용하거나 보류를 해제하지는 않습니다. 성공 기록은 보존합니다.

`last_error_code`는 고정된 진단 코드만 기록합니다. 키·프롬프트·원문·원본 오류 본문·예외 메시지는 로그에 남기지 않습니다. 검증한 응답과 근거 인용문은 접근이 제한된 내부 DB 기록에 저장합니다. Google generateContent는 이 생성 키의 원격 멱등성을 보장하지 않으므로, 수신 결과를 모를 때는 운영 확인으로 중복 과금 위험을 줄입니다.

B 대화(재설명 중 대화 포함)는 `teachmi-dialogue-v5`, 재설명 첫 질문은 `teachmi-reteach-v4`를 사용합니다. 허가된 원문에서 연속된 6~40자 후보를 결정적으로 만들고 `citationCandidates`와 quote enum으로 제공합니다. 후보는 기존 요청 크기 상한 안에서 청크당 최대 8개/전체 최대 64개이며, 원문과 응답을 사후 보정하지 않습니다. 출처 1~2개·ID당 인용 1개·정확한 부분 문자열·6~40자 서버 검증은 유지합니다.

[Google generateContent의 responseJsonSchema 지원 목록](https://ai.google.dev/api/generate-content#v1beta.GenerationConfig)에는 enum이 포함되지만 문자열 maxLength는 포함되지 않습니다. 따라서 maxLength 지시만으로 모델의 길이 준수를 보장하지 않고 짧은 후보 선택과 서버 검증을 함께 사용합니다. 실 모델의 생성 품질 개선은 승인된 별도 시험으로 확인해야 합니다.

요약 v1은 유지합니다. 초기 질문은 question-v2와 짧은 후보를 사용하고 과거 초기 질문·요약 v1의 Map 필드 순서 24가지 키를 일괄 조회합니다. 대화는 v5 → v4 → v3 → v2 → v1, 재설명 첫 질문은 v4 → v3 → v2 → v1 성공 순서로 현재 원문을 재검증하고 추가 호출 없이 복원합니다. 과거 요청 JSON은 바이트 그대로 재구성하며 성공/uncertain 원장·시도 수를 변경하지 않습니다. 원문/설정이 바뀐 요청도 기존 uncertain을 우회하지 못합니다.

### 대화 v5의 단계별 안내

실시험에서 서버는 reason → wrapup으로 올바르게 전환했지만, AI가 사용자 마무리 설명을 받기 전에 직접 요약하고 완료를 안내했다. v5는 현재 단계·도움 종류에 맞춘 `responsePolicy`를 서버에서 구성해 Gemini 입력에만 제공한다. 공개 요청/응답 DTO와 JSON 스키마는 유지한다.

| 현재 답변 단계 | 충분할 때 AI가 할 일 | 대화 완료 안내 |
| --- | --- | --- |
| case | 개념의 의미를 질문 | 금지 |
| concept | 이유와 조건을 질문 | 금지 |
| reason | 사용자에게 의미·방법·이유를 연결한 최종 설명을 요청. AI가 대신 요약하지 않음 | 금지 |
| wrapup | B 대화 마무리를 짧게 확인하고 응용문제가 남아 있음을 안내. 재설명 중이면 같은 문제 재도전을 안내 | 허용, 전체 학습·채점 완료로 표현하지 않음 |

불충분한 답변·힌트·쉬운 예시·모르겠어요는 현재 단계에 머물며 완료를 안내하지 않는다. 현재 설명을 판단하는 `accepted`와 서버의 단계 전환을 구분한다. 과거 AI 문구나 사용자 지시로 완료 상태를 추정하지 않는다. 재설명 중에도 독립 정답·풀이를 공개하거나 정답 처리됐다고 안내하지 않는다.

프롬프트와 입력 정책은 자유 텍스트의 의미를 강제로 보장하지 않는다. 자동 테스트는 단계별 요청 계약·서버 전환·저장·복원·캐시·보류 보호를 대체 전송기로 검증한다. 수정된 v5의 실제 안내 품질은 별도 승인 후 검증해야 한다. 기존 v4 성공 응답과 이미 저장된 메시지는 재작성하지 않으므로 과거 부적절한 문구도 그대로 복원될 수 있다. 프론트엔드는 AI 문장의 완료 주장 대신 [대화 API의 상태 필드](CONVERSATION_TURNS.md)를 사용한다. 실제 Gemini 재시험 없이 프롬프트 변경을 이유로 성공 캐시나 uncertain을 삭제·재생성하지 않는다.

같은 요청의 마지막 허용 시도가 검증 실패하면 502 코드와 구체 진단을 유지하면서 즉시 `retryAllowed=false`, `action=limit_reached`를 반환합니다. 대화·재설명에는 `AI_ATTEMPT_LIMIT` 보류를 저장해 GET에서도 복원하므로 추가 POST로 한도를 확인할 필요가 없습니다. 다른 해시의 과거 일반 실패만으로 세션 전체를 차단하지 않습니다.

| 안전한 진단 | 의미 |
| --- | --- |
| CITATION_ID_INVALID / CITATION_ID_DUPLICATE | 출처 ID 오류 |
| EVIDENCE_QUOTE_NOT_EXACT | 원문과 인용 불일치 |
| EVIDENCE_QUOTE_TOO_SHORT / EVIDENCE_QUOTE_TOO_LONG | 인용 길이 위반; C CitationRules도 같은 고정 코드를 전달 |
| JSON_SCHEMA_INVALID / CONTENT_JSON_INVALID | 응답 계약 / JSON 문법 오류 |
| RESPONSE_MAX_TOKENS | 후보 응답 잘림 |
| TRANSPORT_CONNECT_TIMEOUT / TRANSPORT_REQUEST_TIMEOUT | 연결 / 요청 시간 초과 |
| UPSTREAM_HTTP_5xx / TRANSPORT_IO_FAILURE | 공급자 HTTP 오류 / 통신 중단 |
| LEDGER_SUCCESS_WRITE_FAILED / LEDGER_FAILURE_WRITE_FAILED | 성공 / 실패 영수증 저장 확인 필요 |
| LEDGER_FAILURE_STATUS_READ_FAILED | 실패 후 시도 상태 확인 불가; 자동 재시도 없이 확인 필요 |

진단 단계는 TRANSPORT, RESPONSE_VALIDATION, CACHE_VALIDATION, LEDGER_CLAIM, LEDGER_SUCCESS_WRITE, LEDGER_FAILURE_WRITE로 구분합니다. 영수증 저장 실패는 running으로 남을 수 있으며 임대 만료 후에도 자동 인계하지 않습니다. 기존 `TRANSPORT_HttpTimeoutException` 기록은 읽을 때만 새 진단으로 매핑하고 원본 기록은 변경하지 않습니다. 모델·출력 설정·프롬프트를 변경해 생성 키가 달라져도 같은 학습의 미확인/진행 중 호출을 우회하지 못합니다. [오류 복구 응답](CONVERSATION_TURNS.md)의 review_required는 원장/사용량 확인이 필요하다는 뜻이며, 자동 재시도나 DB 초기화를 의미하지 않습니다.

| HTTP·코드 | 의미 |
| --- | --- |
| 503 LEARNING_SOURCE_INTEGRATION_PENDING | A 원문 연결 대기 |
| 503 LEARNING_SOURCE_INCOMPLETE | 출처·원문 누락이나 불일치 |
| 404 NOT_FOUND | 회원·자료·개념 소유권 불일치 |
| 422 LEARNING_SOURCE_TOO_LARGE | 원문 범위 조정 필요 |
| 503 GEMINI_CONFIGURATION_REQUIRED | 키·모델 설정 필요 |
| 429 AI_USAGE_LIMIT / AI_ATTEMPT_LIMIT | 자체 사용량·시도 한도 |
| 429 GEMINI_RATE_LIMITED / AI_RETRY_LATER | Google 제한 또는 재시도 대기 |
| 503 AI_GENERATION_IN_PROGRESS | 같은 내용 생성 중 |
| 503 AI_GENERATION_UNCERTAIN | 처리 결과 운영 확인 필요 |
| 502 GEMINI_INVALID_REQUEST / GEMINI_ACCESS_DENIED / GEMINI_MODEL_NOT_FOUND | 요청·접근권한·모델 검토 |
| 502 INVALID_GEMINI_RESPONSE | 형식·출처·인용문 검증 실패 |

모든 생성 실패에서 학습 단계·버전을 유지합니다. V1~V3는 수정하지 않고 V4만 추가합니다.

## 운영자 확인과 안전한 조치

`availableActions=["wait"]`는 기존 API의 작업 차단 표시입니다. 무조건 처리 중이라는 뜻이 아닙니다. `AI_GENERATION_UNCERTAIN`/`review_required`와 `AI_ATTEMPT_LIMIT`/`limit_reached`에서는 로딩·자동 폴링·자동 POST를 종료하고 저장된 입력과 확인 안내를 보여줍니다. 재접속 GET의 선택적 recovery에도 같은 규칙을 적용합니다. 세부 화면 규칙은 [대화 API](CONVERSATION_TURNS.md#로딩과-확인-대기-구분)를 따릅니다.

세션 GET의 LearningGenerationRecoverySource는 원장을 읽기만 합니다. pendingTurn이 없는 모드/첫 질문/C 생성의 uncertain과 만료된 running도 복원하며, 소유권 확인 뒤 현재 회원·학습으로 조회 범위를 제한합니다. Gemini 비활성화도 기존 보류를 해제하지 않습니다. 캐시 키, 임대, 시도 수, 원장 상태와 세션 버전을 변경하거나 공급자 호출을 예약하지 않습니다.

1. 지원 건에 회원·세션 식별자, UTC 발생 시각, 오류 코드, recovery.stage/diagnosticCode, 앱 배포 버전과 모델·타임아웃·출력 상한을 기록합니다. 키, 원문, 대화 본문과 AI 응답 전문은 지원 티켓이나 GitHub에 복사하지 않습니다. 회원 소유권을 확인하고 접근 권한이 있는 운영자가 해당 DB를 읽기 전용으로 조회합니다.
2. 해당 회원·세션의 생성 상태·시도 수·마지막 진단·임대 시각과 호출 예약을 비교합니다. 아래 SQL은 바인딩 변수를 쓰는 읽기 전용 템플릿입니다. 운영 도구에서 실제 값을 바인딩하며 원문/content_json은 선택하지 않습니다. 운영 DB를 테스트 DB로 사용하거나 다른 회원의 기록을 넓게 조회하지 않습니다.

```sql
BEGIN READ ONLY;
SELECT generation_key, state, attempts, last_error_code, lease_until, retry_after, updated_at
FROM teachmi.learning_ai_generation
WHERE member_id = :member_id AND session_id = :session_id
ORDER BY updated_at DESC;

SELECT c.id, c.generation_key, c.attempted_at, c.input_bytes, c.max_output_tokens
FROM teachmi.learning_ai_call c
JOIN teachmi.learning_ai_generation g ON g.generation_key = c.generation_key
WHERE g.member_id = :member_id AND g.session_id = :session_id
ORDER BY c.attempted_at;
COMMIT;
```

3. 같은 UTC 시간대·프로젝트·모델의 공급자 사용량/요청 로그와 예약 횟수를 대조합니다. 입력 바이트 수와 max_output_tokens는 실제 청구 토큰 수가 아닙니다. 원장에는 공급자의 실제 토큰 사용량이나 응답 전문이 없는 실패도 있습니다. 로그에 대응 항목이 없다는 사실만으로 미처리·무료라고 판정하지 않습니다. `TRANSPORT_REQUEST_TIMEOUT`은 앱이 결과 수신을 포기한 것이며 원격 처리를 취소했다는 증거가 아닙니다. 40초를 늘리거나 출력 6144를 낮추는 설정 변경은 이후 별도 검증 대상이며 기존 호출 결과를 확정하지 않습니다.
4. 아래 판단표에 따라 지원 건에 근거와 조치를 남깁니다. 이 PR에는 관리자 해제·원장 조정·미수신 응답 복구 API가 없습니다. 현재 가능한 조치는 읽기 조회, 기존 저장 결과 표시, 안내 및 지원 건 기록입니다. 수동 SQL로 결과나 상태를 합성하지 않습니다.

| 확인 결과 | 지금 가능한 안전한 조치 |
| --- | --- |
| uncertain 또는 임대가 만료된 running이며 처리 여부 미확인 | 보류 유지. 공급자 기록 확인/문의 후 사용자에게 확인 중이라고 안내. 재호출 금지. |
| 세션에 이미 검증된 답변/결과가 저장됨 | 소유권을 확인한 기존 GET으로 저장된 학습 기록 표시. 생성 POST를 복구 수단으로 사용하지 않음. |
| 원장만 succeeded이고 세션 결과가 누락됨 | 기존 결과를 보존하고 연결 상태를 조사. 원장 내용을 수동으로 세션에 붙이지 않음. 연결 복구가 필요하면 별도 검토된 복구 도구/변경으로 처리. |
| 공급자가 미처리를 확정함 | 증거를 기록하되 현재 보류는 유지. 재생성이 필요하면 별도로 검토된 복구 절차와 사용자의 신규 호출 승인을 받은 후 수행. 현재 API로 해제 가능하다고 안내하지 않음. |
| AI_ATTEMPT_LIMIT | 마지막 검증 오류와 입력/설정 원인을 조사하고 수정 사항을 별도 검증. 시도 수를 줄이거나 상한을 올려 기존 작업을 재개하지 않음. 사용자는 저장된 입력 확인 및 지원 문의 가능. |

`AI_ATTEMPT_LIMIT`는 생성 키에 누적된 시도 상한이므로 다음 날이나 120초 후에 자동으로 풀리지 않습니다. 분·일 사용량 제한인 `AI_USAGE_LIMIT`, 냉각 시간인 `AI_RETRY_LATER`와 구분합니다. 같은 키 재전송, 새 Idempotency-Key, 모델·프롬프트 변경, 새 세션 생성을 보류 우회 방법으로 안내하지 않습니다. uncertain/running 삭제·초기화, attempts 감소, 강제 failed/succeeded 전환 및 자동 재호출은 금지합니다. 공급자에서 확인된 사용량과 미확인 부분을 구분하여 사용자에게 설명합니다.

## 테스트

대화 API는 같은 Gemini 어댑터의 `LearningDialogueGenerator` 구현을 사용합니다. 현재 질문·대화 문맥·실제 원문으로 응답을 생성하고, 도움에 대한 accepted=true 판정은 거절합니다. 전송 저장과 단계 전환 계약은 [대화 API](CONVERSATION_TURNS.md)에 정리했습니다.

`./gradlew build` 또는 Windows `.\gradlew.bat build`는 실 Google 호출을 제외합니다. 기존 B 테스트에 더해 로컬 HTTP 서버·대체 응답·실제 PostgreSQL로 출처, 오류, 한도, 롤백 후 재사용, 동시 요청, 작은 연결 풀의 교착 방지를 확인합니다.

B의 `GeminiContentIntegrationTest`는 legacy 요청 fixture와 같은 출력 상한 2048을 테스트 클래스 속성으로 고정합니다. 로컬 `.env`나 환경 변수에 `GEMINI_MAX_OUTPUT_TOKENS=6144`가 있어도 이 대체 호출 테스트의 설정에는 영향을 주지 않습니다. 고정 전에는 2048 fixture로 만든 성공 원장을 6144 설정의 생성기로 조회해 캐시가 빗나가고 원장 불변성 검사가 실패했습니다. 출력 상한이 요청과 캐시 키에 포함되는 운영 동작은 그대로 유지합니다.

이 설정 의존성을 비교할 때는 별도 테스트 DB에서 위 환경 변수를 2048/6144로 바꾸고 `cleanTest test --tests '*GeminiContentIntegrationTest.historicalDialogueAndReteachSuccessesAreRestoredWithoutAnotherReservation' --tests '*LegacyDialogueRequestsTest'`를 실행합니다. 각 실행에서 cleanTest(또는 --rerun-tasks)를 사용해 이전 Gradle 테스트 결과가 재사용되지 않게 합니다. 테스트는 외부 전송을 대체하며 운영 DB에서는 실행하지 않습니다.

`gemini-legacy/*.json`은 실제 CR/LF 없이 직렬화한 요청입니다. 문자열 내부의 개행 이스케이프와 파일의 실제 개행은 구분해야 합니다. 캐시 테스트를 통과시키기 위해 fixture를 줄바꿈 정규화하거나 운영 키 계산에서 출력 설정을 제거하지 않습니다.

전용 PostgreSQL 테스트 DB와 키·모델 환경 변수를 준비한 뒤 실 API 테스트를 별도로 실행합니다. 새 터미널에서 환경 변수를 적용하세요. 기본 build와 GitHub CI에는 실 호출이 포함되지 않습니다.

```powershell
cd backend
$env:GEMINI_LIVE_TEST = "true"
.\gradlew.bat geminiLiveTest --tests com.teachmi.backend.learning.gemini.GeminiLiveTest
```

```sh
cd backend
GEMINI_LIVE_TEST=true ./gradlew geminiLiveTest --tests com.teachmi.backend.learning.gemini.GeminiLiveTest
```

테스트는 합성 회계 원문을 사용하며 사용자 문서를 보내지 않습니다. 정상 경로에서 처음 학습 요약과 복습 첫 질문으로 2건을 실제 생성하고 저장·조회·재전송을 확인합니다. 테스트 학습은 정리하고 호출 기록은 비용 확인용으로 해당 테스트 DB에 남깁니다. 이 테스트의 A 연결은 테스트 전용 구현입니다.

대화 실 테스트는 [별도 명령](CONVERSATION_TURNS.md)의 GeminiTurnsLiveTest를 선택하면 정상 경로에서 3건을 호출합니다. 테스트 클래스를 선택하지 않고 geminiLiveTest 전체를 실행하면 두 실 테스트에서 정상 경로 총 5건을 호출합니다.

2026-11-05 실 연동 검증에서는 설정된 gemini-3.5-flash-lite의 생성 요청이 HTTP 503을 반환했습니다. 사용자의 허용을 받아 테스트 프로세스에서 gemini-3.1-flash-lite로 검증했고, 실제 생성 2건과 저장·복원이 통과했습니다. 운영 환경에서는 계정에서 생성 가능한 모델을 직접 선택하세요.
