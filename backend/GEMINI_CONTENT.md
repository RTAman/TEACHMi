# Gemini 콘텐츠 생성

Gemini 어댑터는 기존 `LearningContentGenerator`를 구현합니다. 기존 모드 선택·설명 시작 API의 요청과 응답 형식을 사용하고 요약과 첫 질문을 DB에 저장합니다. GET과 성공 요청의 재전송은 저장한 내용을 돌려줍니다.

B와 향후 소비자가 공유할 Java 호출 계약은 [공통 Gemini 포트](COMMON_GEMINI_PORT.md)의 GeminiGenerationPort입니다.
기존 B 생성기는 같은 실행 엔진·원문/출처 검증·V4 사용량 원장을 사용하며, 기존 프롬프트와 생성 키를 유지합니다.
C의 ProblemGenerator·AnswerGrader 연결은 이 작업에 포함하지 않습니다.

## A 원문 연결

A의 `MaterialLearningSources`가 `LearningSourceReader`를 구현합니다. 직접 입력 자료에서 회원의 소유권, 개념과 자료의 관계, 요청한 원문 조각의 소속을 검증한 뒤 실제 텍스트를 반환합니다. 파일 업로드·분석은 후속 작업이며, A의 인증·자료 사용 방법은 [연동 계약](AUTH_MATERIALS.md)을 참고하세요.

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
| GEMINI_REQUEST_TIMEOUT_SECONDS | 20 | 본문 수신까지의 요청 제한, 1~60초 |
| GEMINI_REQUESTS_PER_MINUTE | 10 | 동일 DB를 쓰는 앱 전체의 최근 1분 호출 한도 |
| GEMINI_REQUESTS_PER_DAY | 100 | 앱 전체 UTC 날짜별 호출 한도 |
| GEMINI_MEMBER_REQUESTS_PER_DAY | 20 | 회원별 UTC 날짜별 호출 한도 |
| GEMINI_MAX_ATTEMPTS | 2 | 같은 콘텐츠의 명시적 생성 시도 한도, 최대 3 |
| GEMINI_MAX_SOURCE_CHARS | 16000 | 실제 원문의 글자 수 상한 |
| GEMINI_MAX_OUTPUT_TOKENS | 2048 | 최대 출력 토큰, 최대 8192 |

요청 수는 전송 전에 예약하고 실패한 호출도 집계합니다. Google 자체 제한은 프로젝트·모델·사용 등급에 따라 적용됩니다. 앱의 UTC 한도는 같은 DB를 쓰는 TEACHMi 호출을 집계합니다. 실제 청구 한도와 다른 앱의 사용량은 Google 프로젝트에서도 관리하세요. [Google 호출 제한](https://ai.google.dev/gemini-api/docs/rate-limits)

## 원문과 응답 검증

API 키는 고정 Google HTTPS 주소의 `x-goog-api-key` 헤더로만 전달하고 외부 리다이렉트를 따르지 않습니다. 처음 학습은 요약·예시·핵심 용어·생각할 점을, 복습은 학생 역할의 구체적인 사례 질문을 생성합니다. 질문에서 정답이나 풀이를 공개하지 않도록 요청합니다. [Gemini API](https://ai.google.dev/api/generate-content)

JSON 스키마를 지정하고 서버에서 필수 필드·타입·목록·길이를 다시 검사합니다. 후보는 하나, 종료 사유는 STOP이어야 합니다. 차단·잘린 응답·잘못된 JSON·예상 밖 필드는 거절합니다. 원문의 지시를 실행하지 않도록 시스템 지시와 원문 데이터를 분리합니다.

응답의 각 출처 ID는 선택 개념의 실제 원문에 존재해야 합니다. 각 ID에는 최소 6글자의 근거 인용문이 필요하며 해당 원문의 정확한 부분 문자열인지 검사합니다. 설명의 의미적 정확성과 학생 역할의 질문 품질은 실제 수업 자료로 추가 검수합니다.

## 사용량·재시도·중복 생성

기존 API의 Idempotency-Key·학습 버전 검사를 유지합니다. 내부 생성 키에는 회원·세션·자료·개념·모드·원문·저장된 요약·모델·프롬프트 버전을 반영하며 JSON 키를 정렬해 재기동 후에도 같은 키를 만듭니다.

V4의 `learning_ai_generation`·`learning_ai_call`에 상태·검증된 결과·시도 수·사용량을 저장합니다. 세션 트랜잭션과 별도로 커밋하므로, AI 결과 저장 뒤 세션 변경이 롤백돼도 재요청은 저장한 결과를 사용할 수 있습니다. 캐시 조회 전에도 A의 현재 소유권과 원문을 확인합니다.

기록 저장에는 기존 spring.datasource 접속 정보로 만든 전용 최대 2개 연결 풀을 씁니다. 같은 회원의 동시 요청이 세션 풀을 차지해도 기록 저장이 교착되지 않게 분리했습니다. 실행 중 같은 생성 키는 중복 호출하지 않습니다.

잘못된 AI 응답은 사용자 명시 재시도로 다시 생성할 수 있으며 최대 시도 수와 호출 한도를 적용합니다. 429의 Retry-After 초 값을 최대 1시간까지 반영하고 해석할 수 없으면 60초를 적용합니다. 내부 자동 반복 호출과 긴 대기는 수행하지 않습니다.

타임아웃·연결 단절·Google 5xx는 원격 처리·과금 여부를 확인하기 어려워 `uncertain`으로 기록하고 재호출을 보류합니다. 앱이 종료되어 120초 임대가 만료된 running 기록도 같은 방식으로 보류합니다. Google 로그·사용량과 해당 회원·세션의 기록을 운영자가 검토한 뒤 재실행 여부를 결정해야 합니다. 성공 기록은 보존합니다.

`last_error_code`는 HTTP 상태나 예외 종류만 기록합니다. 키·프롬프트·원문·원본 오류 본문은 로그에 남기지 않습니다. 검증한 응답과 근거 인용문은 접근이 제한된 내부 DB 기록에 저장합니다. Google generateContent는 이 생성 키의 원격 멱등성을 보장하지 않으므로, 수신 결과를 모를 때는 운영 확인으로 중복 과금 위험을 줄입니다.

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

## 테스트

대화 API는 같은 Gemini 어댑터의 `LearningDialogueGenerator` 구현을 사용합니다. 현재 질문·대화 문맥·실제 원문으로 응답을 생성하고, 도움에 대한 accepted=true 판정은 거절합니다. 전송 저장과 단계 전환 계약은 [대화 API](CONVERSATION_TURNS.md)에 정리했습니다.

`./gradlew build` 또는 Windows `.\gradlew.bat build`는 실 Google 호출을 제외합니다. 기존 B 테스트에 더해 로컬 HTTP 서버·대체 응답·실제 PostgreSQL로 출처, 오류, 한도, 롤백 후 재사용, 동시 요청, 작은 연결 풀의 교착 방지를 확인합니다.

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
