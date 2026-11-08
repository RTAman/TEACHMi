# 최종 통합 검증과 배포 준비 기록

검증일: 2026-11-09. 기준 `origin/main`: `d1b5455f47233a0e03fbe34ee55252a9cd125f1f`.
PR #38·#39(v4)·#40·#41 병합 상태에서 시작했다. 이 문서의 B 수정과 새 테스트는 별도 브랜치에 있으며, main 병합·배포는 수행하지 않았다.

## 판정과 범위

**대체 Gemini 응답을 사용하는 실제 HTTP 전체 여정은 통과했다. 실제 AI 시연과 운영 배포의 최종 승인은 보류한다.**
회원가입 → 파일 등록/개념 선택 → 학습 → 문제 생성/채점 → 재설명/동일 문항 재도전 → 리포트 → 재로그인/재시작 복원을 실제 Spring 애플리케이션과 PostgreSQL에서 검증했다. B 재접속 복구 상태 누락을 찾아 수정했다. C 최초 오류 응답의 복구 정보 누락은 C 담당 수정 사항으로 남겼다.

이번 실제 Gemini 요청은 **0회**다. 모델의 학습 내용 품질, 실제 CitationRules 준수율, 의미 기반 채점, 지연 시간과 사용량은 미검증이다. 과거 실제 502·503 오류를 이번에 동일하게 재현했다고 판단하지 않는다.

| 검증 | 결과 | 범위와 제한 |
|---|---|---|
| 최신 main 회귀/계약 | 387개 통과 | Java 21, PostgreSQL 17, `GEMINI_ENABLED=false` |
| B 수정 후 전체 회귀/계약 | 394개, 41개 클래스 통과 | 기존 387개 + 읽기 전용 복구 회귀 7개; 실패·오류·건너뜀 0 |
| 별도 HTTP 여정 | 1개 복합 테스트, 검증 295개 통과 | Windows 원본 자료 사용 실행; 비동기 상태 조회 횟수에 따라 검증 수는 달라질 수 있음 |
| Gemini 경계 | 대체 호출 45회, 외부 호출 0회 | 실제 B/C 생성기·검증기·원장 사용, provider transport만 대체 |
| DB/마이그레이션 | V1–V12 검증 성공, pending 0 | 완전히 새 PostgreSQL 클러스터와 새 HTTP 전용 DB |
| 기존 데이터 보존 | 변경 없음 | 기존 DB·원장·캐시·`.env` 및 C 원본 4개 파일 보존; 테스트 클러스터 정지 후 보존 |

환경: Windows, JDK 21.0.12.1, Spring Boot 4.1.2, Gradle 9.8.2, PostgreSQL 17.11. 기준 main의 GitHub Backend CI도 성공 상태를 확인했다. 새 PR에는 Linux CI의 별도 offline HTTP 실행을 추가한다.

## 사용 자료와 입력 차이

- C 전달 폴더의 `sample-accounting.txt`, `sample-economics.txt`를 로컬에서 읽어 사용했다. `Run-TeachmiDemo.ps1`, `test-scenario.txt`를 포함한 원본 4개 파일은 변경하지 않았고 해시를 재확인했다. 원본 스크립트는 실행하지 않았다.
- 회계 원문을 직접 입력하고, 같은 원문으로 새 PDF·PPT·PPTX를 메모리에서 만들어 업로드했다. 회계 전체 학습은 분석된 PDF의 개념으로 진행했다. 경제 전체 학습은 직접 입력 자료로 진행했다. PPT/PPTX는 업로드·분석·개념 복원까지 검증했다.
- 원본 폴더가 없으면 출처 확인용 합성 영문 자료로 대체한다. CI는 이 대체 자료를 쓴다. 원문과 파일 바이트를 Git이나 증거 요약에 저장하지 않는다.
- 이번 대화 입력과 생성 문제는 원본 시나리오를 그대로 재생한 것이 아니다. 대체 transport가 의도한 정답·오답·보류 응답을 반환한다. `pending_review`는 저장/집계 계약만 검증했으며 실제 모델 판단을 확인하거나 강제한 결과가 아니다.

## 실제 HTTP 연동 결과

아래 성공은 모두 외부 AI를 대체한 로컬 HTTP 테스트 결과다. 인증·서비스·출처 검증·DB 저장·C v4 검증과 리포트 코드는 실제 구현을 사용한다.

| 단계 | 결과 | 확인 내용 |
|---|---|---|
| 가입·로그인 | 성공 | 쿠키 `HttpOnly`, `SameSite=Lax`, CSRF 발급; CSRF 없는 변경 403, 미인증 조회 401 |
| 직접 입력·파일 등록 | 성공 | PDF/PPT/PPTX 202, 중복 업로드 같은 자료 반환, 분석 완료·개념 후보·범위 수정 |
| 파일 오류·복구 | 성공 | 빈 PDF `no_text`, 재분석 버전 증가, 미지원 형식 415, 빈 파일 422 |
| 분석 중 서버 재시작 | 성공 | 첫 실행의 분석 큐를 보류한 뒤 서버 재시작; 실제 분석 executor가 DB 원본으로 3개 파일 분석 완료 |
| 세션·모드 | 성공 | 처음 학습 요약과 복습 첫 질문, 자료/선택 개념 스냅샷 |
| 대화·도움 | 성공 | 힌트·예시·모르겠어요·각 설명 단계·마무리·문제 단계 진입 |
| 문제 생성 | 성공 | 2개 생성, 출처 검증, 정답/채점 기준 사전 노출 없음, 중복 요청 재사용 |
| 오답 재학습 | 성공 | 오답 → 재설명/힌트 → 같은 problemId 재도전 → 정답 |
| 반복 오답 | 성공 | 오답 → 재설명 → 다시 오답 → 해설 보기 |
| 리포트 | 성공 | `CORRECT_AFTER_RETEACH`, `WRONG`/풀이 확인, 최초 정답 및 보류 집계 |
| 도움 집계 | 성공 | 원 학습 도움 3회와 재설명 2회; 재설명 중 힌트 1회는 B 재설명 evidence에 별도 보존 |
| 재접속·재로그인 | 성공 | 중간 대화, 원 요청 응답, 홈 최근 리포트 2개, 완료 리포트 복원 |
| 소유권 | 성공 | 다른 회원의 자료·세션·evidence·문제·리포트 접근 404 |
| 중복·재시작 | 성공 | 같은 업로드/turn/attempt/problem 요청의 ID·응답 재사용, 추가 대체 호출 없음 |
| 저장된 출처 | 성공 | 성공한 AI 응답의 인용문이 실제 `source_chunk.text`에 포함되는지 확인 |
| HTTPS·실제 브라우저 | 미검증 | 로컬 HTTP에서 Secure=false를 명시; 실제 TLS/프론트엔드 화면 검증 아님 |
| 실 모델 응답·성능 | 미검증 | 외부 호출 승인 전이므로 실행하지 않음 |

원 학습 도움과 재설명 중 도움의 집계 범위가 다르므로 프론트엔드는 두 값을 임의로 합쳐 리포트 원 학습 도움 수와 비교하지 않는다.

## 의도한 502·503과 발견된 결함

| 주입/관찰 | 결과 | 담당/조치 |
|---|---|---|
| B 부정확한 인용 1회 | 502, `EVIDENCE_QUOTE_NOT_EXACT`, recovery 포함 | 정상 거부; 명시적 재시도 1회 성공 |
| C v4 41자 인용 1회 | 502 `INVALID_GEMINI_RESPONSE` | CitationRules 거부 정상. 원장은 `DOMAIN_RESPONSE_INVALID`; 최초 HTTP 오류에 recovery 없음 |
| B 모드·B turn·C 문제 생성 timeout 각 1회 | 503 `AI_GENERATION_UNCERTAIN`, 원장 `TRANSPORT_REQUEST_TIMEOUT` | 각각 해당 흐름 중단; 재생성/초기화 없음 |
| B 모드 timeout 뒤 재접속 | 수정 전 recovery 누락 및 `select_mode` 표시 | **B 결함 수정**: 활성 세션의 unresolved 원장을 읽기 전용 조회 |
| C timeout 뒤 공통 세션 GET | 수정 전 recovery 누락 및 `start_problems` 표시 | 같은 B 공통 조회 수정으로 `review_required`/`wait` 복원 |
| C 최초 502·503 응답 | recovery 누락 | **C 잔여 결함**: `AssessmentErrorHandler`가 `LearningException.recovery()`를 전달하지 않음 |
| Gemini 비활성 모드 선택 | 503 `LEARNING_CONTENT_UNAVAILABLE` | 미연동 상태를 성공처럼 처리하지 않음; 예상 응답 |

자연 발생한 외부 AI 502·503 건수는 평가할 수 없다. 위 502 2건/uncertain 503 3건은 테스트에서 의도한 오류다. C 문제 생성 502 주입은 **1건**이다.

B 수정은 `LearningGenerationRecoverySource`와 `GeminiRecoverySource`를 통해 회원/세션 소유권 확인 후 `uncertain`/`running`만 SELECT한다. 유효 lease는 `wait`, 만료된 running과 uncertain은 `review_required`를 반환한다. 완료 이력과 정상 응답 JSON은 유지한다. 기존 생성 포트·캐시 키·성공 캐시·프롬프트·GeminiLedger·Flyway는 바꾸지 않았다. 원장 상태 전환/해제·생성 예약·재호출은 하지 않는다.

새 테스트에서 원장 40개 성공, 3개 uncertain을 보존했다. 성공 행의 `last_error_code`에는 재시도 전 오류가 남을 수 있다. `state`를 함께 확인해야 하며, last_error만으로 현재 실패 건수를 집계하면 안 된다.

C 담당 권장 수정:

1. 기존 `code`, `message`를 유지하면서 선택적 `recovery`를 오류 응답에 전달하고 계약 테스트를 추가한다. 현재 B 공통 GET은 복구 정보를 돌려주지만 C 최초 응답 누락 자체를 대신 수정한 것은 아니다.
2. CitationRules 실패를 source ID/인용 불일치/길이·스키마 등 안전한 코드로 구분한다. 현재 C 검증 예외는 공통 `DOMAIN_RESPONSE_INVALID`로 합쳐져 운영에서 정확한 하위 원인을 구분하기 어렵다. 원문·AI 응답 전문을 오류에 넣지 않는다.
3. C API 문서/프론트엔드 인계 문서의 이전 미구현 설명을 현재 구현에 맞춰 정리한다. C의 정식 OpenAPI 계약도 추가 검토한다. 이 PR은 A/C 운영 코드와 C 문서를 변경하지 않는다.

## 프론트엔드 인계

요청/응답 필드는 [A 인증·자료](AUTH_MATERIALS.md), [B 세션](LEARNING_SESSIONS.md), [대화](CONVERSATION_TURNS.md), [재설명](RETEACH.md), [C 평가](ASSESSMENT_API.md), `api/openapi.json` 및 인증 OpenAPI를 함께 참고한다. 오래된 문서의 미구현 표시는 현재 controller와 이 HTTP 테스트를 대조한다.

| 흐름 | 주요 경로 |
|---|---|
| 인증 | `/api/v1/auth/signup`, `/login`, `/me`, `/logout` (뒤 세 경로도 `/api/v1/auth` 아래) |
| 자료 | `/api/v1/materials`, `/{materialId}`, `/{materialId}/retry`, `/{materialId}/concepts/{conceptId}` |
| 세션 | `/api/v1/sessions`, `/{sessionId}`, `/active`, `/{sessionId}/mode` |
| 대화·근거 | `/{sessionId}/chat/start`, `/{sessionId}/turns`, `/{sessionId}/evidence` (sessions 아래) |
| 문제·재설명 | `/{sessionId}/problems`, `/problems/{problemId}/attempts`, `/reteach`, `/solution` (아래 설명 참고) |
| 리포트·홈 | `/api/v1/sessions/{sessionId}/report`, `/api/v1/home` |

재설명/풀이의 전체 경로는 `/api/v1/sessions/{sessionId}/problems/{problemId}/reteach`, `/solution`이다. 변경 요청에 CSRF 토큰을 포함하고, 해당 API가 정의한 `Idempotency-Key`와 `expectedVersion`을 사용한다. 타임아웃 후 새 키를 자동 생성해 POST하지 않는다. 홈의 resume 경로도 실행 전에 세션 GET으로 복구 상태를 확인한다.

| 상태 | UI/클라이언트 규칙 |
|---|---|
| `recovery.action=wait` | 진행 중 안내. 제한된 GET 상태 조회만 허용하고 장시간 UI 로딩을 종료할 수 있게 한다. 자동 POST 금지 |
| `review_required` | 즉시 로딩 종료, 생성/재시도 차단. “AI 응답 확인이 필요합니다. 추가 요청 없이 관리자 확인을 기다려 주세요.” |
| `limit_reached` | 즉시 로딩 종료. 같은 요청 재생성 차단, 지원 안내. 단순 대기/날짜 변경으로 복구된다고 안내하지 않음 |
| `AI_GENERATION_UNCERTAIN`/`AI_ATTEMPT_LIMIT`, recovery 없음 | C 최초 응답을 포함한 호환 처리. 즉시 로딩 종료/자동 POST 차단 후 세션 GET으로 상태 확인. 메타데이터 부재를 재시도 허용으로 해석하지 않음 |
| 검증 실패 502 | 성공 내용처럼 표시하지 않음. 안전한 진단 코드와 retryAllowed 확인 후 명시적 사용자 재시도만 허용; 일반 502 일괄 자동 재호출 금지 |
| 401/403/404/409 | 재인증/CSRF 재확인/접근 불가/최신 버전 GET 등 각 계약대로 처리 |

`availableActions=["wait"]`는 기존 API 호환 값이다. 무한 로딩 조건으로 사용하지 않고 반드시 `recovery.action`을 우선한다. `AI_ATTEMPT_LIMIT`의 기존 pending turn/reteach 복구 계약과 회귀 테스트는 유지했다. 다른 요청 해시의 과거 failed 행만으로 세션 전체를 영구 차단하지 않는다.

## 배포 준비 점검

| 항목 | 확인/남은 작업 |
|---|---|
| HTTPS 쿠키 | 운영 기본 `AUTH_COOKIE_SECURE=true`, `SameSite=Lax`. `.env.example`의 false는 로컬용. TLS 도메인에서 로그인/로그아웃/CSRF를 재검증해야 함 |
| 프론트엔드 출처 | 현재 별도 출처 CORS 미구성: 사전 요청 200이어도 Allow-Origin 없음. 같은 HTTPS 출처 reverse proxy를 권장. 별도 도메인이면 A/배포 담당의 명시적 credential 허용 목록/CSRF/헤더 설정 필요 |
| 비밀정보 | Gemini 키/DB 비밀번호는 서버 환경 또는 배포 secret store. 프론트 번들·로그·PR에 포함 금지. 테스트는 실 키를 비우고 대체 transport 사용 |
| DB/파일 영속성 | PostgreSQL 볼륨 필요. 업로드 원본은 V12 DB bytea에 저장. 파일 분석과 학습 기록 재시작 복구 확인. 운영 DB 백업/복구·복제본 업그레이드 검증은 미수행 |
| 마이그레이션 | 새 DB V1–V12/validate 통과, 기존 V3 등 수정 없음. 운영 적용은 별도 승인과 백업 후 수행 |
| 실행 | Java 21에서 `./gradlew bootJar`, `java -jar build/libs/teachmi-backend-0.0.1-SNAPSHOT.jar`. DB URL/계정/비밀을 별도 주입. 현재 compose는 DB만 포함하며 외부 배포는 하지 않음 |
| 헬스체크 | `/actuator/health` UP 및 DB 접근 확인. Gemini 가용성/할당량까지 증명하는 지표는 아님 |
| 업로드 프록시 | 앱 파일 제한 기본 20MB, multipart 서버 상한 60/61MB. 배포 proxy의 body/timeouts도 맞추고 제한·분석 시간 안내 확인 |
| Gemini 한도 | 기본 분당 10, 프로젝트 일 100, 회원 일 20, 요청 최대 시도 2. 실제 프로젝트 quota/예산과 비교 필요. offline 테스트의 1000/500 확대 값은 운영 설정이 아님 |
| 타임아웃 | 기본 연결 3초/요청 20초/출력 2048. 아래 승인안에서만 40초/6144 고정 제안. 긴 대기·토큰 비용 증가, 타임아웃이 원격 취소를 의미하지 않는 점 고려 |
| uncertain 운영 | 원장/사용량 읽기 전용 대조, 세션·operation·stage·진단·시간·attempt만 안전하게 기록. 기존 성공 내용은 조회 가능. 자동 재시도/삭제/초기화/lease 강제 해제 금지 |

운영자는 [기존 Gemini 운영 절차](GEMINI_CONTENT.md)에 따라 provider 사용량과 원장 상태를 먼저 비교한다. 결과 수신/과금 여부가 불명확하면 보류 상태를 유지하고 사용자에게 안내한다. 현재 안전한 관리자 해제 API는 없다. `uncertain`을 `failed`로 바꾸거나 시도/예산을 되돌리는 임의 SQL은 복구 절차가 아니다. 별도의 근거 있는 조정이 필요하면 검토·감사·승인을 거쳐 설계한다. 모델/프롬프트/세션/계정을 바꿔 미확인 호출을 우회하지 않는다.

배포 전 필수: B 수정 PR 검토/병합, C 오류 계약 조치 또는 프론트엔드의 명시적 fallback 검증, 승인된 실 Gemini 시험, HTTPS/출처/secret/영속 DB/백업 설정, 실제 프론트엔드 여정 점검. 선택적 개선: 부하/장시간 안정성 시험, 모니터링/보관 정책, 컨테이너 이미지·배포 자동화 정비. 운영 DB와 외부 서비스에 이번 변경을 적용하지 않았다.

## 오프라인 재실행

1. 기존 개발 DB와 다른 **새 PostgreSQL 17 클러스터**를 loopback의 `55xxx` 포트에 준비한다. 운영 계정/DB URL을 사용하지 않는다. HTTP 테스트는 이 클러스터의 postgres DB에 연결해 `teachmi_test_http_<UUID>` DB를 새로 만들며 삭제하지 않는다. 계정에 테스트 클러스터 내 CREATE DATABASE 권한이 필요하다.
2. 해당 프로세스에 `DB_URL=jdbc:postgresql://127.0.0.1:55xxx/postgres`, 테스트 `DB_USER`, `DB_PASSWORD`, `GEMINI_ENABLED=false`, 비어 있는 `GEMINI_API_KEY`와 `GEMINI_MODEL`을 설정한다. 기존 `.env` 대신 테스트 프로세스의 설정을 사용한다.
3. `TEACHMI_OFFLINE_HTTP_TEST=true`를 설정한다. 선택적으로 `TEACHMI_HTTP_SOURCE_DIR`에 로컬 원문 폴더, `TEACHMI_HTTP_EVIDENCE_DIR`에 비공개 증거 폴더를 지정한다. 한글 PDF는 Windows 기본 Malgun 폰트 또는 `TEACHMI_HTTP_FONT` 경로가 필요하다. 원문 미지정 시 영문 합성 자료/기본 폰트를 사용한다.
4. backend에서 `./gradlew --no-daemon clean build offlineHttpTest` (Windows `./gradlew.bat`)를 실행한다. 일반 test는 `gemini-live`와 `offline-http` 태그를 제외한다. HTTP 시험만 앱 설정의 Gemini를 켜되 시작 전에 대체 transport를 주입·검증한다. 실 키·Google HTTP를 사용하지 않는다.
5. JUnit 결과와 `summary.json`의 check/진단/대체 호출 수를 확인한다. 증거에는 원문·생성 전문·이메일·쿠키·키를 넣지 않는다. 클러스터를 정지하고 증거/원장을 보존한다. 이 태스크를 기존 실사용 클러스터에 연결하지 않는다.

## 실 Gemini 시험 승인안 — 아직 실행하지 않음

실행 승인 후에도 테스트 환경을 먼저 고정하고 아래 한도 안에서만 진행한다. 현재 PC 설정과 기존 `.env`는 바꾸지 않는다.

### 권장 1차: 회계 원문 1회 흐름

| 설정 | 제안 |
|---|---|
| 모델 | `gemini-3.1-flash-lite`: 과거 시험과 비교 목적. 현재 계정 가용성은 아직 확인하지 않음. 사용 불가면 중단하고 모델 자동 변경 없음 |
| 프롬프트 | 기존 B content v1/dialogue v2/reteach v2, C 문제·채점 v4 고정 |
| 시간·출력 | 연결 3초, 요청 40초, `maxOutputTokens=6144`, 원문 상한 16000자 |
| 제한 | 기존 분당 10/프로젝트 일 100/회원 일 20/요청 시도 2 유지. 한 번에 한 요청, 최소 8초 간격. 자동 재호출 없음 |
| 격리 | 새 테스트 클러스터·테스트 회원. 이전 실제 uncertain 세션/원장과 분리. 원문과 대화는 승인 시 Google로 전송됨 |

| 순서 | 정상 예상 생성 수 | 성공 판정 |
|---|---:|---|
| 복습 첫 질문 → 힌트·예시 → 4단계 답변/마무리 | 7 | 실제 선택 개념/출처에 근거, B 인용 정확 일치, 단계·저장 일치 |
| C v4 응용 문제 2개 | 1 | 두 문항 모두 schema·출처 ID·정확한 인용·CitationRules 통과 |
| 한 문항 정답·다른 문항 오답 채점 | 2 | 실제 문항/원문을 바탕으로 작성한 답의 모델 판단·근거를 기록 |
| 오답 문항 재설명 시작/4단계 | 5 | 같은 원문/오답/문항에 연결, B 재설명 출처 검증, problemId 유지 |
| 같은 문항 재도전 채점 | 1 | 실제 판단 기록, 리포트/시도 이력과 일치 |
| 리포트·재로그인·재시작 복원 | 0 | 추가 생성 없이 저장된 내용 복원 |
| 합계 | **16회** | 모델이 예상 판단을 하지 않으면 사실대로 기록하고 미실행 분기는 미검증 처리 |

승인 요청 상한은 **생성 19회**(정상 16 + 명시적 검증 실패 재시도 최대 총 3회)와 모델 메타데이터 조회 1회, 즉 Google HTTP **최대 20회**다. 각 요청 해시의 기존 max-attempts=2를 넘기지 않는다. 재시도는 결과가 알려진 검증 실패에 한해 사람이 확인하고 실행하며 자동으로 하지 않는다. 모델 판단을 강제하거나 다음 단계 진입을 위해 DB를 편집하지 않는다.

예상 입력 3만–8만, 출력 0.5만–1.6만 토큰. 추정치이며 비용/사용량 상한을 보장하지 않는다. 설정상 19회 × 출력 상한 6144는 116,736 토큰이며 실제 usage와 reasoning 계수 등은 공급자 응답 기준으로 확인한다. 과거 캐시가 없는 새 요청의 추정이다. 승인 후 테스트 전용 안전한 계측으로 operation/model/시간/status/diagnostic/`usageMetadata` 토큰 수만 기록한다. DB의 input_bytes/출력 설정을 실제 토큰 사용량으로 오인하지 않는다. timeout으로 usage를 못 받으면 unknown으로 기록한다.

### 선택 2차: 경제 원문

별도 승인 권장. 복습 첫 질문 1 + 모르겠어요 1 + 4단계 4 + 문제 생성 1 + 채점 2 = 정상 **9회** 추가. 예상 입력 2만–4만/출력 0.3만–0.9만 토큰. `pending_review`는 실제 판단 그대로 기록하며 항상 보류가 나오도록 강제하지 않는다. 반복 오답/풀이 보기 저장 흐름은 이미 offline에서 통과했으므로 실 모델에서 불필요하게 전부 반복하지 않는다.

두 자료를 함께 승인한다면 정상 25회, 검증 재시도 포함 생성 최대 28회 + 메타데이터 조회 1회이며 예상 입력 5만–12만/출력 0.8만–2.5만 토큰이다. 두 시험 회원은 사전에 각각 준비하며, 회원 한도를 올리지 않는다. 어느 흐름에서든 uncertain이 발생하면 다른 회원으로 이어서 호출하지 않는다.

### 중단/진단 규칙

- 502: 요청 단계와 B/C를 구분해 횟수를 기록한다. B의 `EVIDENCE_QUOTE_NOT_EXACT`, source ID, `JSON_SCHEMA_INVALID`, 응답 finishReason/잘림을 안전한 코드로 분리한다. C 문제 생성 502도 별도 계수한다. C 하위 규칙이 운영 진단에 노출되지 않는 경우 그 제한을 명시하고 비공개 테스트 계측으로만 식별한다. 응답 전문/원문/개인정보를 로그나 Git에 올리지 않는다.
- `AI_GENERATION_UNCERTAIN` 503: **즉시 모든 실 생성 중단**, 원장/캐시 보존, provider 사용량과 안전한 진단부터 확인한다. DB 초기화, 자동 재호출, 새 세션/계정/모델/프롬프트 변경으로 우회하지 않는다.
- `AI_ATTEMPT_LIMIT`, 예산/할당량 소진, 모델 사용 불가, 출처/스키마 반복 실패 또는 승인 상한 도달 시 중단한다. 알려진 검증 실패 1건 이후의 재시도도 명시적으로 확인하며 상한을 소비한다.
- 성공/실패/미검증을 분리한다. 과거 원본 입력과 달라진 답변·매번 달라지는 생성 문항·미재현 오류를 표시한다. 실제 모델의 정답/오답/보류 판단을 대체하거나 수정하지 않는다.

현재 요청 단계는 오프라인 1·2단계와 승인안 작성까지다. 실 호출과 배포는 각각 별도 승인 전까지 수행하지 않는다.
