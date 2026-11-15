# Gemini 지연·확인 대기 대응

## 이번 수정과 한계

2026-11-15 발생 시각과 일치하는 운영 로그는 20초 동안 응답 헤더를 받지 못한 `TRANSPORT_REQUEST_TIMEOUT` / `WAITING_HEADERS`를 기록했다. 뒤따른 `LEDGER_CLAIM / AI_GENERATION_UNCERTAIN`은 공급자 전송 전에 거절된 요청이다. 특정 세션 및 Google 처리·과금 여부는 이 로그만으로 확정하지 않는다. 원문·계정·실제 원장 행·응답 전문은 이 문서에 포함하지 않는다.

- 코드·application.yml·개발 예제의 기본 요청 제한을 운영 예제와 같은 40초로 맞춘다. 연결 제한과 요청·일일·회원·시도 한도는 유지한다.
- 명시적인 환경 변수 값은 덮어쓰지 않는다. 예전에 설정한 `GEMINI_REQUEST_TIMEOUT_SECONDS=20`이 있으면 여전히 20초가 적용되며 시작 시 `REQUEST_TIMEOUT_BELOW_OPERATIONAL_BASELINE`을 기록한다.
- AI 활성 시 시작 로그 `Gemini limits`에서 실제 requestTimeoutMs, connectTimeoutMs, maxOutputTokens를 확인한다. API 키와 원문은 출력하지 않는다.
- 시간 제한은 연결·헤더·전체 본문 수신까지 하나의 제한이다. 40초로 늘리는 것은 성공 보장이 아니며 최악의 대기 시간·연결 점유도 늘어난다.
- 프롬프트·모델·출력 토큰 설정·요청 JSON·생성 키 계산·성공 캐시·uncertain 원장은 변경하지 않는다. 제한 변경으로 기존 보류가 해제되지 않는다.

## 문의 번호와 안전한 진단

`/api/v1/sessions` 아래 응답에는 서버가 생성한 UUID `X-Teachmi-Request-Id` 헤더를 제공한다. 요청 본문/API 필수 필드나 기존 오류 코드·recovery 구조는 변경하지 않는다. 프론트의 문의 안내에 이 번호를 표시한다. 클라이언트가 보낸 같은 이름의 헤더는 신뢰하거나 재사용하지 않는다.

로그의 `requestId`로 다음 기록을 연결한다.

1. `Learning request`: 응답 상태와 전체 경과 시간. 처리 도중 예외로 끝나 응답 상태를 확인할 수 없으면 `httpStatus=unresolved`.
2. `Gemini stage=DISPATCH`: sessionId, operation, promptVersion, generationKey, model, requestTimeoutMs, inputBytes, maxOutputTokens. 이는 전송을 시작하는 지점이며 공급자 접수/완료의 증거는 아니다.
3. `Gemini transport`: WAITING_HEADERS / WAITING_BODY / READING_BODY와 elapsedMs, httpStatus, responseBytes. httpStatus=0은 헤더 미수신이며 공급자 HTTP 상태를 추정하지 않는다.
4. `TRANSPORT`, `RESPONSE_VALIDATION`, `LEDGER_CLAIM`: 같은 문의 번호·세션·생성 키에 대한 진단. 원장 차단은 새 유료 호출이 아니다.

이 로그와 식별자는 권한 있는 운영자만 열람한다. URI/쿼리·쿠키·Idempotency-Key·이메일·키·프롬프트·답변·모델 응답 전문을 로깅하지 않는다. inputBytes와 maxOutputTokens는 실제 사용/청구 토큰이 아니다.

## 운영자 읽기 전용 확인

1. 문의 번호·UTC 시간·허가된 세션 ID, 실제 배포 커밋과 비밀이 아닌 제한 설정을 비공개 사고 기록에 남긴다. 프론트 URL `/learn/mode`에는 세션 ID가 없으므로 이를 원장 ID로 간주하지 않는다.
2. 기존 운영 접속 도구에서 대상 프로젝트/DB·사용자 소유권·조회 권한을 먼저 확인한다. 비밀번호나 접속 URL을 명령줄·지원 티켓에 넣지 않는다.
3. psql의 이미 준비된 보호된 접속 설정을 이용해 `psql -X -v session_id=<허가된 UUID> -f api/Inspect-LearningRecovery.sql`을 실행한다. 스크립트는 UUID 범위만 조회하고 READ ONLY·5초 제한으로 원장 상태와 예약 정보를 보여준다. 원문/답안/AI JSON을 반환하지 않는다. 결과를 공개 GitHub에 붙이지 않는다.
4. 같은 Google 프로젝트·모델·UTC 시간대의 사용량과 대조한다. 집계 지연과 다른 트래픽 때문에 요청별 처리·무과금이 확정되지 않을 수 있다. Google 조회 자체에 생성 API를 사용하지 않는다.
5. 검증된 성공 결과가 저장돼 있으면 기존 GET으로 확인한다. uncertain/만료 running은 보존하고 자동 재생성하지 않는다. succeeded 원장을 수동으로 세션에 붙이거나, 상태/시도 수를 SQL로 바꾸지 않는다. 최신 미채점 C 답안에 한해 [사용자 확인 재채점](GRADING_RECOVERY.md) 조건을 만족하면, 사용자가 추가 AI 사용량 안내를 확인한 뒤 원래 요청으로 별도 실행을 1회 선택할 수 있다. 이것은 기존 원장 해제나 자동 재호출이 아니다.

현재 관리자 해제·미수신 응답 복구 API는 없다. 공급자가 미처리를 확인했더라도 현재 보류를 임의로 해제하지 않는다. 추가 복구 구현은 권한·원본 기록 보존·동시 실행 차단을 포함한 별도 검토가 필요하며, 새 유료 호출은 별도 사전 승인을 받는다. 새 세션·모델·프롬프트·키로 기존 보류를 우회하지 않는다.

## 프론트엔드 연결 규칙

- 모드 선택·요약 페이지 진입 시 오래된 라우터 상태만 쓰지 말고 GET으로 현재 학습을 확인한 뒤 생성 버튼을 활성화한다.
- 생성 중에는 동기적인 중복 실행 가드와 버튼/모드 변경 잠금을 적용한다.
- review_required / limit_reached / check_configuration은 생성 버튼·모드 변경을 차단하고 정적 안내를 표시한다. wait 또는 결과 불명 네트워크 오류도 자동 POST하지 않고 사용자 선택의 저장 상태 GET만 제공한다.
- 문의 번호가 있으면 표시한다. 요약/첫 질문 생성 단계에서는 아직 사용자 설명이 없을 수 있으므로 '답변이 저장돼 있다'고 단정하지 않는다.
- 같은 작업의 명시 재전송에는 같은 Idempotency-Key와 본문을 유지한다. 확인 대기 오류를 시간 경과나 새 키로 해제하지 않는다.

프론트 구현은 별도 `haerinnii/teachmi-frontend` PR에서 검증한다. 백엔드 PR만 병합했다고 실제 브라우저 수정이 적용됐다고 보고하지 않는다.

## 배포 전·후 검증

이번 PR은 코드와 가짜 공급자 테스트만 포함한다. 운영 환경 변수 수정·재배포·원장 조정·Gemini 생성은 하지 않는다.

승인된 배포 때에는 (1) 명시적 환경 변수와 시작 로그 대조, (2) 프론트/프록시가 서버 제한 전에 요청을 끊지 않는지 확인, (3) 기존 성공 기록·uncertain 차단·읽기 복원 확인 순서로 점검한다. 실제 Gemini 시험은 별도 모델·횟수·토큰 계획 승인 후 격리 환경에서 최소 횟수로 수행한다. 502/503이면 승인된 중단 조건을 따른다.
