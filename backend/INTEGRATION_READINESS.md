# 통합 검증과 Gemini 복구 수정 기록

검증일: 2026-11-09. 기준 main: `d1b5455f47233a0e03fbe34ee55252a9cd125f1f` (PR #38·#39 v4·#40·#41 병합). 수정은 별도 브랜치의 PR #42에 있으며 main 병합·배포는 하지 않았다.

## 현재 판정

**대체 AI를 사용하는 A·B·C 전체 HTTP 여정과 회귀 테스트는 통과했다. 수정 후 실 모델 시연·운영 배포 승인은 아직 보류한다.**

앞선 승인 실시험에서 B의 쉬운 예시가 55자 인용을 두 번 반환해 중단됐다. 이번에는 B 인용 생성·시도 소진 안내와 C 최초 오류의 recovery·진단 누락을 수정했다. A에서 새로 재현된 운영 코드 결함은 없으며 인증·자료 기능을 전체 회귀로 확인했다.

| 검증 | 결과 | 범위 |
|---|---|---|
| 기준 main | 387개 통과 | PR #39 v4 포함, 기존 main CI 성공 |
| PR #42 최초 검증 | 394개 + HTTP 검증 295개 통과 | 공통 세션 GET의 unresolved 상태 복원 |
| 현재 수정 후 전체 회귀/계약 | **406개, 43개 클래스 통과** | 실패·오류·건너뜀 0 |
| 현재 별도 HTTP 여정 | **최종 검증 315개 통과** | 실제 loopback HTTP·격리 PostgreSQL·대체 Gemini; 이전 실행은 314개, 비동기 조회 횟수 차이 |
| 현재 HTTP 대체 호출 | 48회 | 실제 B/C 생성기·원장·검증·저장 사용, 외부 Gemini 0회 |
| Flyway | V1–V12 validate 성공, pending 0 | 새 클러스터·새 테스트 DB |
| 실제 Gemini | 이전 승인 시험 4회 후 중단 | 이번 수정 검증의 실제 호출은 0회 |

환경: Windows, JDK 21.0.12.1, Spring Boot 4.1.2, Gradle 9.8.2, PostgreSQL 17.11. CI에도 별도 HTTP 여정이 포함된다. CI의 합성 자료 결과와 Windows 원본 자료 결과는 구분하며 비동기 조회 횟수에 따라 검증 수가 달라질 수 있다.

## 해결한 문제

### B: 짧고 정확한 인용 생성

새 대화/재설명 요청은 `teachmi-dialogue-v3`/`teachmi-reteach-v3`를 사용한다. A에서 소유권 검증한 원문에서 결정적으로 뽑은 연속 6–40자 후보를 `citationCandidates`와 quote enum으로 제공한다. 청크당 최대 8개/전체 최대 64개로 제한하며 Unicode 문자 경계를 보존한다. 원문 또는 모델이 반환한 인용을 사후 수정하지 않는다.

[Google generateContent 스키마 지원 목록](https://ai.google.dev/api/generate-content#v1beta.GenerationConfig)에는 enum이 포함되지만 문자열 maxLength는 포함되지 않는다. 길이 지시만으로 생성 결과를 보장하지 않도록 후보 선택을 사용하면서 서버의 정확한 부분 문자열·길이·출처 검증을 유지한다. 실제 모델 개선 효과는 별도 승인 시험이 필요하다.

요약/최초 질문 v1과 기존 키 계산은 유지한다. v3 성공 → 같은 입력의 v2 성공 → v1 성공 순서로 현재 원문을 재검증하고 복원한다. 합성 자료로 고정한 v1·v2 요청 JSON의 바이트 일치 및 과거 성공 캐시 조회 시 추가 예약/호출/원장 변경이 없음을 검증했다. prompt 버전 변경도 unresolved 원장을 우회하지 못한다.

### B: 마지막 실패 직후 재시도 차단

동일 요청의 마지막 허용 시도가 검증 실패하면 기존 HTTP 502/`INVALID_GEMINI_RESPONSE`와 구체 진단을 유지하면서 즉시 `retryAllowed=false`, `action=limit_reached`를 반환한다. 대화/재설명 실패 영수증에는 `AI_ATTEMPT_LIMIT` 보류를 저장해 재접속 GET도 wait/limit_reached로 복원한다. 다음 POST를 보내야 한도가 표시되던 문제를 고쳤다.

실패한 **같은 생성 키**의 상태·시도 수만 읽어 판단한다. 다른 해시의 과거 일반 실패로 세션 전체를 차단하지 않는다. 기존 uncertain/running·성공 캐시·시도 수를 초기화하거나 자동 재호출하지 않는다. 상태 확인 DB 오류도 확인 필요로 보류한다.

### C: 최초 오류 recovery와 상세 진단

`AssessmentErrorHandler`는 기존 code/message와 함께 LearningException의 선택적 recovery를 그대로 전달한다. 일반 인증/자료 오류의 JSON 형태는 유지한다.

CitationRules는 고정 Diagnostic enum을 가진 `GeminiValidationException`으로 인용 길이/출처 분류를 전달한다. 공통 포트는 이를 원장과 recovery에 기록하며 원문·원본 응답·예외 전문을 노출하지 않는다. C의 v4 프롬프트, 6–40자 규칙, correct/wrong/pending_review 판정 정책 및 기존 요청 키는 변경하지 않았다. ProblemGenerator/AnswerGrader 자체도 변경하지 않았다.

A 자료 업로드·C 실제 생성/채점을 미구현으로 표시하던 인계 문서도 현재 구현에 맞췄다.

## 실제 HTTP 연동 범위

아래 성공은 현재 수정 후 **대체 모델 응답**으로 확인한 결과다. 의미 기반 AI 품질을 확인한 결과와 다르다.

| 흐름 | 결과/확인 |
|---|---|
| 가입·로그인·인증 | 쿠키 HttpOnly/SameSite=Lax, CSRF 발급; 누락 변경 403, 미인증 조회 401 |
| 자료·개념 | 직접 입력 및 PDF/PPT/PPTX 등록·중복 방지·분석 완료·개념 범위 수정 |
| 파일 분석 복구 | 첫 실행의 분석 큐를 보류한 뒤 재시작; DB 원본으로 세 형식 모두 분석 완료 |
| 파일 오류 | 빈 PDF no_text, 재분석 버전 증가, 미지원 형식 415, 빈 파일 422 |
| 학습·도움 | 처음 학습/복습, 힌트·쉬운 예시·모르겠어요·각 설명·마무리·문제 단계 |
| C 문제·채점 | 두 문제·정답 비공개·출처 검증, 중복 응답 재사용, 최초 정답/오답/보류 저장 |
| 재학습 | 오답 → 재설명/힌트 → 같은 문제 재도전 → 정답; 반복 오답 → 해설 |
| 리포트 | CORRECT_AFTER_RETEACH/WRONG/최초 정답/보류 집계, 도움·재설명 집계 |
| 재접속 | 중간 대화와 성공 응답, 재로그인 후 홈·완료 리포트 복원 |
| 접근·일관성 | 다른 회원의 자료/학습/evidence/문제/리포트 404, 중복 전송/재시작 추가 호출 없음 |
| 저장된 근거 | 성공한 AI 영수증의 인용이 실제 source_chunk.text에 포함됨 |
| B 마지막 검증 실패 | 두 번째 실패 직후 limit_reached; 추가 재시도 429/대체 호출 없음; 재시작 후 같은 보류 |
| C 최초 오류 | 길이 위반 502에 EVIDENCE_QUOTE_TOO_LONG/recovery, timeout 503에 review_required 포함 |

현재 HTTP 시험의 고의 오류는 B 인용 불일치 1건, C 41자 인용 1건, B 시도 소진용 길이 위반 2건, B 모드/B turn/C 생성 timeout 각 1건이다. 실제 Google 오류 재현으로 집계하지 않는다. 고의 C 502 뒤의 명시적 재시도와 실제 생성·저장·리포트 흐름도 통과했다.

최종 대체 원장은 성공 41개, 시도 소진 실패 1개, uncertain 3개를 그대로 보존했다. 성공 행에 이전 last_error_code가 남을 수 있으므로 state와 함께 집계한다. 원 학습 도움 3회와 재설명 2회, 재설명 중 힌트 1회의 집계 범위를 구분했다.

## 사용 자료와 보존

C 전달 원문 `sample-accounting.txt`, `sample-economics.txt`를 로컬에서 읽었다. 회계 원문으로 PDF/PPT/PPTX를 메모리에서 만들고, 회계 전체 학습은 분석된 PDF의 개념으로, 경제는 직접 입력으로 검증했다. 원본 스크립트는 실행하지 않았고 원본 4개 파일을 보존했다. 원문 폴더 미지정 시 합성 영문 자료로 대체한다.

오프라인 대화·문항·판정은 제어된 fixture다. 원본 시나리오 입력을 정확히 재생하거나 실제 모델의 pending_review를 강제한 것이 아니다. 기존 DB·Gemini 원장·캐시·.env는 건드리지 않았다. 각 실행의 테스트 클러스터는 정지 후 증거와 함께 보존했다. 원문·사용자 답변·AI 응답 전문·키를 Git에 포함하지 않았다.

## 선행 승인 실 Gemini 시험 결과

모델 `gemini-3.1-flash-lite`, 요청 40초, 출력 상한 6144, 기존 분당 10/프로젝트 일 100/회원 일 20/동일 요청 시도 2를 유지했다. 모델 조회는 1회/200, 생성은 4회였다.

| 생성 | 구간 | 시간 | 결과 | 입력/출력 토큰 |
|---|---|---:|---|---:|
| 1 | B 최초 사례 | 1.631초 | 200 | 688 / 289 |
| 2 | B 힌트 | 11.992초 | 200 | 1957 / 207 |
| 3 | B 쉬운 예시 | 11.216초 | 502 EVIDENCE_QUOTE_TOO_LONG | 2321 / 291 |
| 4 | 같은 요청 명시 재시도 | 4.404초 | 동일 502 → 중단 | 2321 / 286 |

실패 2건 모두 55자 인용/상한 40자였다. 출처 ID·정확한 원문 일치·JSON 필드 구조는 정상이고 STOP으로 끝났다. provider HTTP는 모두 200이었다. actual usageMetadata 합계는 입력 7,287/출력 1,073/전체 8,360토큰이다. 503/uncertain은 발생하지 않았으며 과거 인용 불일치·타임아웃을 그대로 재현한 것은 아니다.

B가 완료되지 않아 C 호출과 C 문제 생성 502는 모두 0회다. C 품질/채점 정확도/pending_review/재설명/리포트의 **실 모델** 결과는 미검증이다. C 최초 recovery 문제도 이 실시험에서는 관찰하지 못했고 오프라인 재현으로 수정했다.

승인 상한 19회 중 남은 15회는 사용하지 않는다. 이번 수정은 실 API 재시험을 포함하지 않으며, 수정 후 재검증은 별도 승인을 받아 계획한다. 경제 실시험도 실행하지 않는다. 모델·계정·세션·프롬프트를 바꿔 중단한 흐름을 이어가지 않았다.

## 프론트엔드 복구 계약

[A 인증·자료](AUTH_MATERIALS.md), [B 세션](LEARNING_SESSIONS.md), [대화](CONVERSATION_TURNS.md), [재설명](RETEACH.md), [C 평가](ASSESSMENT_API.md) 및 `api/openapi.json`을 참고한다. 홈 resume 경로를 실행하기 전에도 세션 GET으로 상태를 확인한다. 실패한 전송은 원래 키·본문을 보존하고 자동 POST하지 않는다.

| recovery.action/코드 | 클라이언트 동작 |
|---|---|
| wait | 진행 안내와 상한 있는 GET 조회만; 자동 POST 금지, 무한 로딩 금지 |
| review_required / AI_GENERATION_UNCERTAIN | 즉시 로딩·자동 폴링·재전송 종료. 저장된 내용과 관리자 확인 안내 |
| limit_reached / AI_ATTEMPT_LIMIT | 즉시 로딩 종료·재생성 차단. 시간 경과로 해제된다고 안내하지 않음 |
| 502 + retryAllowed=true | 검증 실패 안내; 사용자가 명시적으로 허용된 같은 요청을 재시도 |
| recovery가 없는 구버전 응답 | 터미널 오류 코드를 먼저 분기하고 세션 GET 한 번으로 확인. 메타데이터 부재를 재시도 허용으로 해석하지 않음 |

`availableActions=["wait"]`는 기존 호환 값이며 처리 중 스피너 조건이 아니다. 마지막 검증 실패의 바깥 code가 INVALID_GEMINI_RESPONSE여도 action=limit_reached이면 로딩을 종료한다. 대화/재설명 GET은 저장된 AI_ATTEMPT_LIMIT에서 보류를 복원한다. 기존 과거 원장/영수증을 이 수정으로 일괄 다시 쓰지는 않는다.

## 배포 준비

| 항목 | 확인과 남은 작업 |
|---|---|
| HTTPS 쿠키 | 운영 기본 Secure=true, SameSite=Lax. 로컬 HTTP 시험만 Secure=false. 실제 TLS 도메인에서 재검증 필요 |
| 프론트엔드 출처 | 별도 출처 CORS 미구성(사전 요청 Allow-Origin 없음). 같은 HTTPS 출처 proxy 권장. 분리 도메인은 명시적 allowlist/credential/CSRF 설정 필요 |
| 비밀정보 | Gemini 키/DB 비밀번호는 서버 secret/env. 프론트 번들·로그·PR에 포함 금지 |
| DB·파일 | 영속 PostgreSQL 볼륨 필요. 원본 파일은 V12 DB bytea 저장; 분석·학습 재시작 복구 통과. 운영 백업/복구 시험은 별도 |
| Flyway | V1–V12 validate/pending 0. 기존 SQL 변경·추가 마이그레이션 없음. 운영 적용은 별도 승인 |
| 실행·health | Java 21, bootJar 실행. compose는 DB용. /actuator/health UP은 DB 확인이며 Gemini 가용성/예산 증명은 아님 |
| 업로드 proxy | 기본 파일 20MB, multipart 60/61MB. 실제 proxy body/timeout 설정 확인 필요 |
| Gemini 제한 | 기본 분당 10/일 100/회원 일 20/시도 2. 테스트 fixture 전용 1000/500은 운영 설정이 아님 |
| 오류 운영 | uncertain/running 보존, provider 사용량과 원장 읽기 전용 대조. 관리자 확인 전 자동 재호출/삭제/시도·예산 반환 금지 |
| 실 시연 | 수정 PR 검토·승인된 실 모델 시험·실제 프론트 UI/HTTPS 여정이 남음 |

40초·6144 설정은 선행 실시험에서만 사용했다. 네 응답 모두 40초 이내/출력 상한 이하였으므로 과거 timeout 개선 효과를 확인한 결과는 아니다. timeout은 원격 취소/미과금을 의미하지 않는다. 운영자는 [원장 확인 절차](GEMINI_CONTENT.md#운영자-확인과-안전한-조치)를 따르며 임의 SQL로 uncertain을 failed로 바꾸거나 해제하지 않는다. 안전한 관리자 해제 API는 현재 없다.

필수 남은 작업은 PR 검토, 승인된 실 Gemini 재검증, TLS/출처/secrets/영속 DB/백업 구성, 프론트 복구 UI 확인이다. 부하·장시간 안정성 시험, 보관/모니터링 정책, 배포 이미지/자동화는 후속 개선이다. 외부 배포와 운영 DB 변경은 수행하지 않는다.

## 오프라인 재실행

1. 기존 DB와 다른 새 PostgreSQL 17 클러스터를 loopback 55xxx 포트에 만든다. 운영 계정/URL을 사용하지 않는다.
2. 테스트 프로세스의 DB_URL을 `jdbc:postgresql://127.0.0.1:55xxx/postgres`로, DB_USER/DB_PASSWORD를 해당 클러스터 계정으로 지정한다. Gemini enabled=false, API_KEY/MODEL은 비운다. 기존 .env 대신 테스트 설정을 사용한다.
3. `TEACHMI_OFFLINE_HTTP_TEST=true`, 선택적으로 `TEACHMI_HTTP_SOURCE_DIR`와 비공개 `TEACHMI_HTTP_EVIDENCE_DIR`를 지정한다. 한글 PDF는 기본 Malgun 또는 TEACHMI_HTTP_FONT가 필요하며 원문 미지정 시 영문 합성 자료를 사용한다.
4. backend에서 `./gradlew --no-daemon clean build offlineHttpTest` (Windows gradlew.bat)를 실행한다. HTTP 시험은 새 teachmi_test_http_UUID DB를 만들고 실제 앱을 시작하되 provider transport를 대체한다. 해당 계정은 새 테스트 클러스터 내 CREATE DATABASE 권한이 필요하다.
5. JUnit과 비밀정보 없는 summary.json을 확인하고 클러스터를 정지해 보존한다. 일반 test는 gemini-live/offline-http를 제외하며 실 API 시험은 절대 일반 build에 포함하지 않는다.
