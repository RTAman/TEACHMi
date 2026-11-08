# B 초기 질문 인용·타임아웃 수정

2026-11-09, 기준 main `40ab956f883a907f859ade69d7a89448bbadb9e1`(PR #42 병합). 실제 Gemini 호출 없이 수행했다. main 병합·외부 배포는 하지 않는다.

## 확인된 원인과 한계

| 항목 | 확인 결과 |
|---|---|
| 초기 질문 502 | 기존 teachmi-content-v1 question은 짧은 인용 후보를 사용하지 않았다. 선행 실응답은 출처 ID가 맞고 공백을 무시하면 원문과 같았지만 정확한 부분 문자열이 아니었다. EVIDENCE_QUOTE_NOT_EXACT 거부는 정상 |
| 과거 초기 질문 캐시 | 내부 데이터 JSON에 순서가 고정되지 않은 Map.of를 사용했다. JVM에 따라 필드 순서/키가 달라져 한 순서로만 조회하면 성공 캐시를 놓친다. 합성 golden 및 DB 회귀에서 재현 |
| 과거 503 | 40,000ms 후 TRANSPORT_REQUEST_TIMEOUT. 당시 계측으로는 헤더/본문 대기나 Gemini 처리 완료·과금·세부 지연 원인을 구분할 수 없음 |
| 요청 크기 | 13,412바이트, 대화 본문 679자. 과도한 입력이 원인이라고 단정할 근거는 없음. 학습 컨텍스트와 메시지 메타데이터 중복은 확인 |
| 별도 전송 결함 | Java 21의 기존 전송은 헤더/일부 본문 수신 후 본문이 멈추면 제한을 넘겨 기다린다. 로컬 HTTP 서버의 지연 회귀가 수정 전에 실패함 |

본문 대기 결함이 과거 40초 오류의 직접 원인이었다고 주장하지 않는다. 실제 호출 없이 공급자/네트워크 상태를 단정하거나 타임아웃을 자동으로 늘리지 않았다.

## 변경

### B 질문·대화 요청

초기 질문은 `teachmi-question-v2`로 분리해 소유권 검증된 원문에서 연속 6–40자 후보와 quote enum을 제공한다. 처음 학습의 chat/start와 복습 시작에 적용한다. 모델이 반환한 공백·개행·기호를 사후 보정하지 않으며 서버의 정확한 부분 문자열 검사를 유지한다.

대화/재설명은 `teachmi-dialogue-v4`/`teachmi-reteach-v4`다. top-level context와 중복된 dialogue.learning을 제거하고, 최근 대화 창의 모든 본문·순서·역할·종류·단계, 현재 답변, targetQuestion, 선택·원문·재설명 focus는 보존한다. DB 메시지는 변경하지 않는다.

새 B 질문/대화/재설명은 `min(GEMINI_MAX_OUTPUT_TOKENS, GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS)`를 쓴다. 새 선택 변수의 기본값은 2048, 허용 범위는 256–8192다. 요약과 C는 기존 전역 상한을 사용한다. 원장에는 실제 요청의 출력 상한을 기록하며 요청·일·회원 한도 및 최대 시도 수는 유지한다.

실패 당시 요청을 외부 전송 없이 재구성한 결과 **13,412 → 10,885바이트(약 18.8% 감소)**였다. 원문·선택·답변·대화 본문/순서 일치를 확인했고 출력 상한은 6144 → 2048이다. 모델 품질·지연 개선을 실증한 결과는 아니다. 출력이 잘리면 기존 RESPONSE_MAX_TOKENS로 거부하고 자동 재호출하지 않는다.

### 이전 캐시와 uncertain

새 질문·대화 JSON은 정렬해 결정적으로 만든다. 요약 v1의 요청·출력 설정은 그대로 두고 동일한 과거 필드 순서 조회 보호를 적용한다. 대화/재설명은 v4 → v3 → v2 → v1 성공 순서로 조회한다. 초기 질문은 v2 성공을 먼저 읽고, 초기 질문·요약의 과거 v1 네 데이터 필드 순서 **24가지**를 기존 해시 계산으로 재구성해 **회원/세션으로 제한한 일괄 SELECT**로 찾는다. 기존 키·원장·캐시 payload를 바꾸거나 조회 시 예약하지 않는다.

합성 v1/v2/v3 golden, 다른 JVM에서 추출한 초기 v1 순서의 바이트 복원, 무호출 캐시 재사용을 검증했다. 과거 uncertain을 새 질문/대화/재설명 버전으로 우회할 수 없으며 원래 행과 시도 수를 그대로 보존하는지도 각각 검증했다.

### 전체 응답 제한과 안전한 진단

고정 HTTPS 주소와 키 헤더를 유지한다. sendAsync의 전체 본문 완료 future에 단일 deadline을 적용해 연결·헤더·본문을 기존 요청 제한 안에 포함한다. 자동 재시도는 없다. 128KiB 응답 제한, 429 Retry-After, 4xx/5xx 분류와 인터럽트 플래그 보존도 유지한다.

오류 로그는 고정 diagnostic, phase, elapsedMs, httpStatus, responseBytes만 기록한다. 키·주소·원문·답변·AI 응답·원본 예외 메시지는 출력하지 않는다.

| 로그 phase | 의미 |
|---|---|
| WAITING_HEADERS | 헤더 미수신. httpStatus=0은 알 수 없다는 내부 표시 |
| WAITING_BODY | 헤더 수신, 본문 바이트 대기 |
| READING_BODY | 본문 일부 수신 |
| BODY_COMPLETE | 본문 완료 후 상태 분류 등 |

로그 phase는 내부 진단이며 **공개 recovery.stage enum은 바꾸지 않는다**. 타임아웃은 기존 503/AI_GENERATION_UNCERTAIN, stage=TRANSPORT, diagnosticCode=TRANSPORT_REQUEST_TIMEOUT, retryAllowed=false, action=review_required를 유지한다.

로컬 취소는 최선의 자원 정리 시도다. [Java 21 HttpClient 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html#sendAsync(java.net.http.HttpRequest,java.net.http.HttpResponse.BodyHandler))도 취소가 적용되는 시점이나 서버 전송 여부를 보장하지 않는다. 따라서 취소를 공급자 처리 취소·미과금으로 해석하지 않고 uncertain 보류를 유지한다.

## 검증

| 검증 | 결과 |
|---|---|
| 전체 A·B·C 회귀/계약 | **420개, 44개 클래스 통과**; 실패·오류·skip 0 |
| 실제 loopback HTTP 여정 | **316개 검증 통과**, 대체 transport 48회. 비동기 조회 횟수에 따라 검사 수 변동 |
| 실제 Gemini | **0회**. 모델 조회도 하지 않음 |
| 본문 지연 | 수정 전 deadline 회귀 실패 → 수정 후 제한 내 uncertain 처리 |
| 전송 | 헤더/본문 단계 로그, 인터럽트·취소·후속 독립 요청 자원 사용, 자동 재시도 없음 |
| 전체 서비스 연결 | 로컬 실제 본문 timeout → DB uncertain → 답변 보존 → 재전송 차단/원장 불변 |
| 초기 인용 | 공백을 바꾼 인용은 거부하고 같은 요청의 명시적 재시도만 허용 |
| 이전 캐시 | 초기 질문·요약 v1 및 대화/재설명 v1–v3 성공을 추가 예약·호출·행 변경 없이 복원 |
| 예산 | 실제 B cap 기록, 요약/C 전역 cap 유지 |
| 실제 기존 DB | 정지 상태 **1,373개 파일 집합 SHA-256 작업 전후 동일** |
| Flyway·다른 파트 | V1–V12 validate, pending 0. A/C 업무 코드·마이그레이션 수정 없음 |

합성 자료/로컬 가짜 전송기와 별도 신규 PostgreSQL 클러스터를 사용했다. HTTP 여정은 로컬 전달 자료를 읽되 원문·대화·응답 전문을 Git에 포함하지 않았다. 보존 대상 기존 실제 시험 클러스터는 시작하지 않았고 해당 원장 SQL을 실행하지 않았다.

## 남은 확인

수정 후 실 모델의 인용 준수율·응답 시간은 미검증이다. 기존 uncertain 요청은 공급자 사용량과 원장을 읽기 전용으로 먼저 대조해야 한다. 초기화·시도 수 감소·새 키/모델/프롬프트로 우회하지 않는다. 실 Gemini 재시험과 외부 배포는 별도 승인 대상이다.
