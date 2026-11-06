# B API 인수인계와 실행 예시

B의 HTTP 계약은 [api/openapi.json](api/openapi.json), 실행 도구는 [api/Invoke-LearningRequest.ps1](api/Invoke-LearningRequest.ps1)입니다. OpenAPI 3.0.3을 읽는 편집기·클라이언트에서 JSON 명세를 가져올 수 있습니다. 서버에 Swagger UI나 새 문서 HTTP 엔드포인트를 추가하지 않았습니다.

## 준비 상태와 인증

B의 세션·Gemini 어댑터·대화·복원·재설명·근거 조회와 C용 상태 전환은 구현됐습니다. **A의 쿠키 인증과 직접 입력 자료·개념·원문 연결도 구현됐습니다.** A의 파일 업로드/분석과 C의 문항/평가/리포트는 후속 연결 작업입니다. 직접 입력 학습은 실제 로그인과 내 자료로 시작할 수 있으며 AI 생성에는 Gemini 설정이 필요합니다.

모든 B HTTP 요청은 A의 TEACHMI_SESSION HttpOnly 쿠키로 인증합니다. 변경 요청에는 로그인 또는 GET /api/v1/auth/me 응답의 csrfToken을 X-CSRF-Token으로 보냅니다. 명세의 sessionCookie securityScheme과 변경 요청의 필수 CSRF 헤더에 반영했습니다. 쿠키 이름을 환경 변수로 바꾸면 명세와 클라이언트도 함께 갱신하세요.

PowerShell 도구는 실제 로그인에서 얻은 AuthHeaders 또는 WebRequestSession을 전달받습니다. A가 요구하는 CSRF 등 추가 헤더도 AuthHeaders로 전달합니다. 임의의 X-Member-Id로 인증을 우회하거나 데모 회원을 제공하지 않습니다. 자격 증명은 메모리나 로컬 비밀 저장소에서 읽고 이 문서·스크립트·Git에 입력하지 마세요.

인증이 없으면 401입니다. 인증 후에도 A의 개념 소스가 없으면 생성은 503 CONCEPT_SOURCE_UNAVAILABLE, 원문이 없으면 생성 단계는 503 LEARNING_SOURCE_INTEGRATION_PENDING, C의 오답 소스가 없으면 재설명은 503 LEARNING_PROBLEM_INTEGRATION_PENDING입니다. 저장된 세션·근거 조회 자체는 AI·원문·C의 재조회 없이 가능합니다.

## 공개 HTTP API 8개

| operationId | 요청 | 성공 |
| --- | --- | --- |
| createSession | POST /api/v1/sessions | 201 SessionView, Location |
| getActiveSession | GET /api/v1/sessions/active | 200 SessionView 또는 본문 없는 204 |
| getSession | GET /api/v1/sessions/{sessionId} | 200 SessionView |
| chooseLearningMode | PUT /api/v1/sessions/{sessionId}/mode | 200 SessionView |
| startLearningChat | POST /api/v1/sessions/{sessionId}/chat/start | 200 SessionView |
| sendLearningTurn | POST /api/v1/sessions/{sessionId}/turns | 200 SessionView |
| startReteach | POST /api/v1/sessions/{sessionId}/reteach | 200 SessionView |
| getLearningEvidence | GET /api/v1/sessions/{sessionId}/evidence | 200 LearningEvidenceView |

모든 변경 요청은 Idempotency-Key가 필요합니다. 조회에는 필요하지 않습니다. active와 evidence의 성공 응답은 no-store입니다. 명세는 요청/응답 필수 필드·타입·enum·길이·nullable 및 교체/답변의 교차 필드 조건을 정의합니다. 일반 메시지·도움 이벤트의 reteachId는 값이 없으면 생략되므로 필수 필드가 아닙니다. 그 외 명세에서 required로 표시한 nullable 필드는 응답에 null로 포함됩니다.

## 실행 도구 사용

PowerShell 7에서 backend 폴더를 기준으로 실행합니다. Windows·macOS·Linux의 PowerShell 7에서 같은 스크립트를 사용합니다.

```powershell
$invoke = './api/Invoke-LearningRequest.ps1'
$connection = @{ BaseUri = [uri]'http://localhost:8080'; AuthHeaders = @{} }
# 가입/로그인 후 받은 값을 메모리에서 전달합니다. 실제 준비 명령은 AUTH_MATERIALS.md 참고.
# $connection.WebSession = $actualLoginSession
# $connection.AuthHeaders = @{ 'X-CSRF-Token' = $login.csrfToken }

# Preview는 네트워크를 호출하지 않고 인증 헤더·쿠키도 표시하지 않습니다.
& $invoke @connection -OperationId getActiveSession -Preview
$active = & $invoke @connection -OperationId getActiveSession
```

도구는 명세의 operationId에서 method/path를 직접 읽습니다. 반환값은 StatusCode·Location·CacheControl·Body입니다. 4xx/5xx도 이 형태로 반환하므로 Body 사용 전에 StatusCode를 확인하세요. 자동 재시도·리다이렉트는 하지 않으며, 응답의 Set-Cookie나 인증 헤더는 출력하지 않습니다. 실제 API 요청은 Gemini 사용량을 발생시킬 수 있습니다. Preview와 아래 오프라인 검증은 실제 호출을 하지 않습니다.

예시에서 예상 상태를 확인할 작은 함수를 사용합니다.

```powershell
function Assert-BStatus($response, [int]$expected) {
    if ($response.StatusCode -ne $expected) {
        $code = if ($response.Body -is [System.Collections.IDictionary]) { $response.Body['code'] } else { '' }
        throw "Expected HTTP $expected; received $($response.StatusCode) $code"
    }
}
```

### 처음 학습 시작 → 설명

active가 200이면 우선 해당 학습을 이어갑니다. 204이고 실제 A 자료/개념이 준비된 경우 새 학습을 만듭니다. 진행 중 학습 교체는 [세션 계약](LEARNING_SESSIONS.md)의 별도 확인 절차를 따릅니다.

```powershell
Assert-BStatus $active 204
$createKey = [guid]::NewGuid().ToString()
$created = & $invoke @connection -OperationId createSession -IdempotencyKey $createKey -Body @{
    materialId = (Read-Host 'A에서 확인한 내 자료 ID')
    conceptId = (Read-Host '그 자료의 선택 개념 ID')
}
Assert-BStatus $created 201
$sid = [guid]$created.Body.sessionId
$state = & $invoke @connection -OperationId getSession -SessionId $sid
Assert-BStatus $state 200
$modeKey = [guid]::NewGuid().ToString()
$state = & $invoke @connection -OperationId chooseLearningMode -SessionId $sid -IdempotencyKey $modeKey -Body @{
    mode = 'first'; expectedVersion = $state.Body.version
}
Assert-BStatus $state 200
$state.Body.summary
$startKey = [guid]::NewGuid().ToString()
$state = & $invoke @connection -OperationId startLearningChat -SessionId $sid -IdempotencyKey $startKey -Body @{
    expectedVersion = $state.Body.version
}
Assert-BStatus $state 200
$state.Body.messages[-1].text
```

복습은 위 모드 선택에서 review를 사용하고 chat/start를 호출하지 않습니다. 성공 응답의 첫 질문부터 설명합니다. 같은 세션에서 first/review를 차례로 실행하거나 모드를 바꾸지 않습니다.

```powershell
$answerKey = [guid]::NewGuid().ToString()
$answerBody = @{ kind = 'answer'; text = (Read-Host '현재 질문에 대한 내 설명'); expectedVersion = $state.Body.version }
$state = & $invoke @connection -OperationId sendLearningTurn -SessionId $sid -IdempotencyKey $answerKey -Body $answerBody
Assert-BStatus $state 200
$state.Body.stage; $state.Body.chatStage; $state.Body.messages[-1].text
```

충분한 답변만 case → concept → reason → wrapup → problem으로 이동합니다. 응답의 질문·단계·availableActions를 보고 다음 설명을 입력하세요. 정해진 4회 전송으로 완료된다고 가정하거나 version 증가량을 계산하지 않습니다. 다음 새로운 동작마다 새 키와 최신 응답 version을 사용합니다.

힌트 요청은 다음과 같습니다. 쉬운 예시는 example, 모르겠어요는 dont_know로 바꾸며 text를 생략합니다.

```powershell
$helpKey = [guid]::NewGuid().ToString()
$helpBody = @{ kind = 'hint'; expectedVersion = $state.Body.version }
$state = & $invoke @connection -OperationId sendLearningTurn -SessionId $sid -IdempotencyKey $helpKey -Body $helpBody
Assert-BStatus $state 200
```

### 재접속·실패 복구

다시 getActiveSession을 호출하고 200 본문의 sessionId·resume·pendingTurn·activeReteach·problemReturn을 확인합니다. 생성 중 wait는 조회로 확인하며 새 입력을 보내지 않습니다. 실패/임대 만료 전송을 재시도할 때는 GET의 최신 version으로 원래 요청 본문을 고치지 않습니다.

```powershell
$state = & $invoke @connection -OperationId getSession -SessionId $sid
Assert-BStatus $state 200
$pending = $state.Body.pendingTurn
if ($null -ne $pending -and $pending.state -in @('failed', 'interrupted')) {
    if ($pending.errorCode -eq 'AI_GENERATION_UNCERTAIN') { throw '원격 처리·과금 여부를 운영자가 확인해야 합니다.' }
    $saved = $state.Body.messages | Where-Object { $_.messageId -eq $pending.userMessageId }
    $retryBody = @{ kind = $pending.kind; expectedVersion = $pending.expectedVersion }
    if ($pending.kind -eq 'answer') { $retryBody.text = $saved.text }
    $retried = & $invoke @connection -OperationId sendLearningTurn -SessionId $sid -IdempotencyKey $pending.idempotencyKey -Body $retryBody
    # 실패·사용량 제한·대기 응답은 반복 호출하지 않고 코드와 Gemini 재시도 계약을 확인합니다.
    Assert-BStatus $retried 200
    $state = & $invoke @connection -OperationId getSession -SessionId $sid
}
```

같은 성공 키의 재전송은 당시 응답을 반환하므로 최신 진행 상태가 필요하면 GET을 호출합니다. [대화 복구 계약](CONVERSATION_TURNS.md)과 [Gemini 재시도 정책](GEMINI_CONTENT.md)을 참고하세요.

### 오답 재설명 → 같은 문항 복귀

실제 C의 오답이 준비되고 B가 problem 단계일 때만 실행합니다. C 소스 미연결 상태를 임의의 문항 ID나 샘플 정답으로 대신하지 않습니다.

```powershell
$state = & $invoke @connection -OperationId getSession -SessionId $sid
Assert-BStatus $state 200
$reteachKey = [guid]::NewGuid().ToString()
$reteachBody = @{
    problemId = (Read-Host 'C가 확인한 원래 문항 ID')
    attemptId = (Read-Host 'C가 확인한 현재 오답 시도 ID')
    expectedVersion = $state.Body.version
}
$state = & $invoke @connection -OperationId startReteach -SessionId $sid -IdempotencyKey $reteachKey -Body $reteachBody
Assert-BStatus $state 200
```

이후 같은 turns API를 사용합니다. 충분한 최종 재설명 후 problemReturn은 기존 문항을 가리킵니다. 해당 문항 경로와 재제출·채점은 C가 연결할 계약이며, B가 구현한 추가 HTTP 엔드포인트가 아닙니다. 재설명 시작 실패는 activeReteach의 원래 키·버전·문항/시도로 재시도합니다. C의 복귀 확인은 아래 내부 서비스를 사용합니다.

근거 조회는 다음과 같습니다. 최초·최종 설명, 실제 제공된 도움과 회차별 이력을 제공합니다.

```powershell
$evidence = & $invoke @connection -OperationId getLearningEvidence -SessionId $sid
Assert-BStatus $evidence 200
$evidence.Body.originalLearning
```

## A·C 내부 연결 인계

| 담당 | Java 인터페이스/서비스 | 연결할 내용 |
| --- | --- | --- |
| A | CurrentMember.requireId | 실제 인증 회원·만료/폐기 확인 |
| A | ConceptSource.loadOwnedConcept | 소유 자료·개념 관계와 개념 스냅샷 |
| A | LearningSourceReader.readOwned | 선택 출처의 소유권·실제 원문 |
| C 홈 | LearningSessionService.getActive | 진행 중 학습 Optional; 비어 있으면 없음 |
| C 평가·리포트 | LearningEvidenceService.get | 최초/재설명 분리 근거와 실제 도움 집계 |
| C 오답 | ReteachProblemSource.loadWrongAttempt | 소유권·현재 오답·보완 개념 |
| C 문항 재개 | LearningReteachService.acknowledgeProblemReturn | 같은 트랜잭션에서 원래 문항 복귀 표시 소비 |
| C 평가 상태 | AssessmentStateSource.loadOwnedReport | 실제 평가 완료·리포트 개정/근거·종료 승인 |
| C 상태 저장 | LearningAssessmentService.publishReport / completeLearning | B 잠금 → C DB 저장 → 상태 검증 → 함께 커밋 |

이 내부 메서드는 HTTP 경로가 아닙니다. [재설명 계약](RETEACH.md), [근거 계약](LEARNING_EVIDENCE.md), [평가·종료 계약](ASSESSMENT_TRANSITIONS.md)의 잠금·트랜잭션·승인 규칙을 따르세요. completed는 C의 명시적 승인 뒤 설정되며, 설명 완료나 리포트 준비만으로 자동 종료하지 않습니다. C의 실제 점수·리포트·종료 기준과 A의 파일 업로드/분석 연결이 남아 있습니다.

## 오류·계약 검증 범위

인증 쿠키가 없거나 만료·폐기되면 401입니다. 로그인 상태에서 CSRF가 없거나 틀리면 B 실행 전에 403 CSRF_TOKEN_INVALID를 반환합니다. 이 공통 필터 응답은 code·message 두 필드이며 currentSession은 없습니다. 아직 B 입력이 저장되지 않았으므로 /auth/me로 토큰을 확인한 뒤 같은 키·본문으로 재전송하세요. 실제 A 필터·쿠키·원문과 B의 학습·재로그인 복원은 LearningAuthenticatedContractTest에서 검증합니다. AI만 테스트 경계로 대체합니다.

B의 도메인/입력 오류 본문은 code·message·nullable currentSession입니다. 401/404/409/422/429/502/503과 evidence의 500을 명세화했습니다. Spring MVC의 JSON/UUID 바인딩 400은 본문이 비거나 프레임워크 형식일 수 있습니다. 이 응답은 x-body-optional로 표시하고 계약 테스트는 400의 body.missing만 예외 처리합니다. 성공/도메인 오류 검증과 204 본문 금지에는 이 예외를 적용하지 않습니다. 프레임워크의 405/415 등도 고정 ErrorBody라고 가정하지 마세요.

openapi.json의 x-error-codes는 현재 대표 코드 목록이며 전체 오류 코드 enum은 아닙니다. 클라이언트는 알 수 없는 코드도 처리해야 합니다. 인증 전송 방식·의미적 AI 품질·운영 A/C의 실제 DB/권한 로직까지 OpenAPI 검증이 보장하지는 않습니다.

```powershell
# 실제 DB가 준비된 backend 폴더에서 실행. AI는 테스트 경계로 대체합니다.
.\gradlew.bat test --tests com.teachmi.backend.learning.LearningOpenApiContractTest
.\gradlew.bat build
# DB·인증·네트워크·AI 없이 실행 도구만 검증합니다.
./api/Test-LearningRequest.ps1
```

일반 build/CI는 명세 파싱, 공개 8경로와 실제 컨트롤러의 일치, 요청/응답 record 필드, 실제 MockMvc HTTP 교환의 필수 필드·타입·enum·nullable·헤더·상태 코드를 검사합니다. 잘못된 JSON/교차 필드 입력, 생성 중/실패/만료, 처음/복습·도움·재설명·근거·종료 기록을 포함합니다. 필드 삭제·잘못된 타입/enum·미문서화 응답 필드·204 본문을 일부러 넣은 대조 테스트가 검증기의 실패도 확인합니다. 요청 도구는 명세의 경로를 사용하며 별도 CI 단계에서 네트워크 대체 호출로 JSON·인증 전달·응답 처리·자동 재시도/리다이렉트 금지를 확인합니다.

구현이나 DTO를 변경할 때 명세와 예시도 같은 PR에서 갱신하세요. 기존 HTTP 동작·DB 마이그레이션·런타임 의존성·환경 변수는 이번 문서/테스트 작업에서 바꾸지 않았습니다.

명세 형식은 [공식 OpenAPI 3.0.3 규격](https://spec.openapis.org/oas/v3.0.3)을 사용하고 테스트 전용 검증기는 [Atlassian OpenAPI Request Validator](https://github.com/atlassian/openapi-request-validator)를 사용합니다.
