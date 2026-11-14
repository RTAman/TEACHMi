# A파트 인증·자료 연동 계약

[백엔드 A 이슈 #4](https://github.com/RTAman/TEACHMi/issues/4)의 구현입니다. 이메일·비밀번호 가입과 쿠키 로그인, 가입 없이 체험하는 게스트 로그인, 파일 업로드(PDF·PPT·PPTX)·분석·재시도와 직접 입력 자료, 개념 후보 추가·수정, B가 정의한 `CurrentMember`·`ConceptSource`·`LearningSourceReader` 연결을 제공합니다.

- 요청·응답 명세: [api/auth-materials.openapi.json](api/auth-materials.openapi.json) (`AuthMaterialsOpenApiContractTest`가 실제 응답과 대조)
- 프론트 연동 가이드: [AUTH_API_HANDOFF.md](AUTH_API_HANDOFF.md)

## 로그인 방식

| 항목 | 규칙 |
| --- | --- |
| 인증 쿠키 | `TEACHMI_SESSION`, HttpOnly, SameSite=Lax, Path=/, 7일. 배포(HTTPS)에서는 Secure. 로컬 `.env`는 `AUTH_COOKIE_SECURE=false` |
| CSRF | 로그인 상태의 POST·PUT·PATCH·DELETE는 `X-CSRF-Token` 헤더가 필요합니다. 값은 로그인 응답이나 `GET /auth/me`의 `csrfToken`입니다. 없거나 틀리면 `403 CSRF_TOKEN_INVALID` |
| 저장 | 이메일은 소문자로 정규화해 고유 제약을 둡니다. 비밀번호는 BCrypt, 인증 토큰은 SHA-256 해시만 DB에 저장합니다 |
| 만료·폐기 | 만료되거나 로그아웃한 쿠키로 보호 API를 부르면 401입니다. 학습 데이터는 그대로 남습니다 |
| 게스트 | `POST /auth/guest`가 임시 계정(`guest-<id>@guest.teachmi.invalid`, 아무도 모르는 임의 비밀번호의 BCrypt)을 만들고 로그인과 같은 쿠키를 발급합니다. 일반 가입은 이 도메인을 `422 INVALID_EMAIL`로 거절합니다. B·C에는 일반 회원과 똑같은 `CurrentMember`입니다 |

프론트는 `fetch(..., { credentials: 'include' })`로 쿠키를 보내고, 앱을 열 때 `GET /auth/me`로 로그인 상태와 `csrfToken`을 받아 변경 요청 헤더에 넣습니다.

게스트 생성은 서비스 전체에서 1시간에 `AUTH_GUEST_MAX_PER_HOUR`개(기본 100)로 제한합니다. 프론트(Vercel)가 `/api`를 대신 전달해 사용자 IP를 믿을 수 없으므로 IP별이 아니라 전체 개수로 막습니다. 넘으면 `429 TOO_MANY_GUESTS`입니다. `AUTH_GUEST_ENABLED=false`로 끄면 `404 GUEST_LOGIN_DISABLED`입니다. 게스트도 회원별 AI 하루 한도와 서비스 전체 AI 한도를 그대로 적용받습니다. 게스트 계정과 자료는 자동으로 지우지 않습니다.

로그인 실패 제한은 단일 서버 메모리 방식이며, 만료 항목은 다음 요청에서 이메일 키까지 정리합니다. 저장 한도는 최대 10,000개 이메일이고 동시 실패의 개별 큐도 설정된 실패 횟수만 보관합니다. 모든 슬롯이 아직 유효하면 새로운 이메일 시도는 429로 제한하고 기존 이메일의 제한을 제거하지 않습니다. 만료 또는 성공 로그인 초기화로 슬롯이 비면 다시 허용합니다. 여러 서버에서는 기존 안내대로 공유 DB/Redis 방식이 필요합니다.

## 인증 API

| 요청 | 성공 | 실패 |
| --- | --- | --- |
| `POST /api/v1/auth/signup` `{email, password}` | `201 {id, email}`. 로그인시키지 않음 | 형식 `422 INVALID_EMAIL`·`INVALID_PASSWORD`(8~64자), 중복 `409 EMAIL_ALREADY_REGISTERED` |
| `POST /api/v1/auth/login` `{email, password}` | `200 {id, email, guest, csrfToken, expiresAt}` + Set-Cookie | 미등록·비밀번호 오류 모두 `401 INVALID_CREDENTIALS`, 15분에 5회 실패하면 `429 TOO_MANY_LOGIN_ATTEMPTS` |
| `POST /api/v1/auth/guest` (본문 없음) | `201 {id, email, guest: true, csrfToken, expiresAt}` + Set-Cookie | 1시간 한도 초과 `429 TOO_MANY_GUESTS`, 꺼짐 `404 GUEST_LOGIN_DISABLED` |
| `GET /api/v1/auth/me` | `200 {id, email, guest, csrfToken}` | `401 UNAUTHENTICATED` |
| `POST /api/v1/auth/logout` (CSRF 헤더) | `204`, 서버 세션 폐기 + 쿠키 삭제 | CSRF 누락 `403` |

오류 본문은 `{"code": "...", "message": "..."}`입니다.

## 자료·개념 API

모든 요청은 로그인이 필요하고, 다른 회원의 자료·개념은 존재 여부와 관계없이 `404 NOT_FOUND`입니다. 생성 요청에는 `Idempotency-Key`(1~128자)가 필요하며, 같은 키·같은 본문을 다시 보내면 처음 결과를 돌려줍니다. 같은 키에 다른 본문은 `409 IDEMPOTENCY_KEY_REUSED`입니다.

| 요청 | 성공 | 비고 |
| --- | --- | --- |
| `POST /api/v1/materials` multipart `file`, `title?` | `202 MaterialView`, `Location` | S02-1 파일 업로드. `status: analyzing`으로 접수하고 서버에서 분석. 형식 415, 용량 413(기본 20MB) |
| `POST /api/v1/materials` JSON `{title?, text, concept?: {name, scope}}` | `201 MaterialView`, `Location` | S02-5 직접 입력. 바로 `status: done`. 본문 최대 15,000자 |
| `POST /api/v1/materials/{materialId}/retry` | `202 MaterialView` | 실패한 파일만. `analysisVersion` +1, 다른 상태는 `409 MATERIAL_NOT_FAILED` |
| `GET /api/v1/materials/{materialId}` | `200 MaterialView` | 상태·오류·개념 후보·원문 위치 |
| `POST /api/v1/materials/{materialId}/concepts` `{name, scope, evidenceText?, sourceChunkIds?}` | `201 ConceptView` | 목록 맨 위에 추가. 근거를 고르거나 입력. 직접 입력 자료는 생략하면 자료 전체가 근거 |
| `PATCH /api/v1/materials/{materialId}/concepts/{conceptId}` `{name?, scope?}` | `200 ConceptView` | 이름·범위 수정 |

```json
{
  "materialId": "6f1c…",
  "sourceType": "text",
  "title": "감가상각은 유형자산의 취득원가를…",
  "fileName": null,
  "status": "done",
  "errorCode": null,
  "analysisVersion": 1,
  "concepts": [
    {
      "conceptId": "a83e…",
      "name": "감가상각의 개념",
      "scope": "취득원가를 내용연수에 걸쳐 배분하는 이유",
      "custom": true,
      "sourceChunkIds": ["0d2b…"],
      "location": "직접 입력 1"
    }
  ],
  "sources": [
    {"chunkId": "0d2b…", "order": 1, "locationKind": "input", "locationNumber": 1, "location": "직접 입력 1"}
  ],
  "createdAt": "2026-11-06T10:00:00Z",
  "updatedAt": "2026-11-06T10:00:00Z"
}
```

`status`는 `analyzing`·`done`·`failed`, 실패한 파일의 `errorCode`는 `no_text`(글자 없음, 스캔본 등)·`format`(손상·열 수 없음)·`analysis`(서버 오류)입니다. `size`는 예약값이며 용량 초과는 업로드 즉시 413입니다.

## 파일 분석과 재처리

| 단계 | 동작 |
| --- | --- |
| 접수 | 확장자와 파일 시그니처로 PDF·PPT·PPTX를 확인하고 원본을 `material_file`(비공개, API로 내려보내지 않음)에 저장합니다. 커밋 전에 큐 자리를 확보하고, 작업자는 커밋 완료를 확인한 뒤 전용 스레드 2개(`material-analysis-`)에서 분석합니다. 롤백된 작업은 분석하지 않습니다 |
| 텍스트 추출 | PDFBox(페이지), Apache POI(슬라이드)로 쪽마다 최대 6,000자를 원문 조각으로 저장합니다. 글자가 20자 미만이면 `no_text`, 파일을 열 수 없으면 `format` |
| 개념 후보 | 페이지·슬라이드 제목 기반(`ConceptCandidates`, AI 미사용). 연속된 같은 제목은 한 개념, 반복 머리말·표지·목차·Q&A는 제외, 최대 8개, 개념당 원문 15,000자 이하(B의 AI 원문 한도 16,000자 안) |
| 늦은 결과 | 결과 저장 때 자료를 잠그고 `analysis_version`과 `analyzing` 상태를 다시 확인합니다. 재시도 뒤 이전 버전의 결과는 버립니다 |
| 큐 포화 | 업로드/재시도는 `503 MATERIAL_ANALYSIS_BUSY`. 자료·파일·요청 키·버전 변경을 롤백하므로 잠시 후 사용자가 같은 요청으로 다시 시도할 수 있습니다. 자동 반복하지 않습니다 |
| 서버 재시작 | 시작할 때 `analyzing`인 파일 자료를 다시 분석합니다. 큐에서 거절된 작업은 버전·상태를 확인해 `failed/analysis`로 기록하고 나머지 복구를 계속합니다. 원본 파일은 보존되며 재시도 API를 사용할 수 있습니다 |
| 수동 재처리 | 실패한 자료는 `POST /materials/{id}/retry`. 성공 자료를 재분석하는 관리 API는 없습니다. 기존 학습이 참조하는 원문·개념을 수동 삭제하지 말고 별도 버전 복구 절차를 검토합니다 |

B의 공통 Gemini 포트는 학습 세션(sessionId)이 있어야 호출할 수 있어 세션 생성 전인 자료 분석에는 쓰지 않았습니다. AI 개념 추출이 필요하면 `ConceptCandidates`를 대체하는 별도 생성기로 추가합니다.

사용자 개념도 선택한 sourceChunkIds·직접 입력 근거·전체 자료 대체 경로 모두 합계 15,000자 이하로 제한합니다. B와 같은 Java UTF-16 길이를 사용하며 이모지 등 보조 문자도 같은 기준으로 계산합니다. 초과하면 `422 EVIDENCE_TOO_LARGE`이고 개념·멱등 키는 저장하지 않습니다. 자료 전체 대신 필요한 페이지를 선택하거나 근거 범위를 줄여 다시 등록하세요.

POI의 OOXML·scratchpad는 5.5.1로 맞췄습니다. PPTX 내부 같은 이름의 ZIP 항목은 format 오류로 거절합니다. [Apache의 중복 항목 입력 검증 공지](https://poi.apache.org/#CVE-2025-31672)를 참고하세요.

환경 변수 `MATERIAL_MAX_FILE_SIZE`(기본 `20MB`)로 업로드 한도를 바꿉니다. Spring의 multipart 상한은 60MB로 두어 그 안의 초과는 A의 `413 FILE_TOO_LARGE` 본문으로 안내합니다.

## B·C 연결

| 인터페이스 | 구현 | 동작 |
| --- | --- | --- |
| `learning.CurrentMember` | `auth.AuthCurrentMember` | 인증 필터가 확인한 회원 ID(UUID 문자열). 없으면 B가 401 |
| `learning.ConceptSource` | `material.MaterialLearningSources` | 내 자료이고 분석 완료된 최신 개념만 이름·범위·원문 조각 ID로 반환 |
| `learning.LearningSourceReader` | `material.MaterialLearningSources` | 개념에 연결된 원문 조각만 실제 텍스트로 반환 |

없거나 다른 회원의 자료·개념·조각은 `LearningException.notFound()`입니다. 인증 필터는 Servlet `Principal`에도 같은 회원 ID를 넣습니다. C는 컨트롤러에서 `AuthenticatedMember.require(request).userId()` 또는 B와 같은 `CurrentMember`를 쓰면 됩니다.

## 테스트 회원 준비

시연·통합용 회원은 코드에 넣지 않고 API로 만듭니다. 로컬 서버에서 아래를 두 번(이메일만 바꿔) 실행하면 두 회원이 준비됩니다.

```powershell
$base = 'http://localhost:8080/api/v1'
$body = @{ email = 'student1@example.com'; password = Read-Host '비밀번호(8자 이상)' } | ConvertTo-Json
Invoke-RestMethod -Method Post "$base/auth/signup" -ContentType 'application/json' -Body $body
$login = Invoke-RestMethod -Method Post "$base/auth/login" -ContentType 'application/json' -Body $body -SessionVariable web
$auth = @{ BaseUri = [uri]'http://localhost:8080'; WebSession = $web; AuthHeaders = @{ 'X-CSRF-Token' = $login.csrfToken } }
```

`$auth`는 B의 `api/Invoke-LearningRequest.ps1`에 그대로 넘길 수 있습니다(`& ./api/Invoke-LearningRequest.ps1 @auth -OperationId getActiveSession`).

## DB 변경

`V8__create_members_and_materials.sql`이 `app_user`, `auth_session`, `material`, `source_chunk`, `concept`, `concept_source`, `material_request`를 만듭니다. B의 `learning_session.member_id`·`material_id`·`concept_id`는 문자열 그대로 두었고 FK는 걸지 않았습니다. `V12__add_material_files.sql`이 업로드 원본 테이블 `material_file`과 분석 중 자료 인덱스를 추가합니다. 배포 DB에는 V1부터 번호 순서대로 적용되며 V12는 V8의 `material`에만 의존합니다.

## 배포 검수

배포한 서버에 [api/Test-AuthMaterialsDeployment.ps1](api/Test-AuthMaterialsDeployment.ps1)을 실행하면 A파트 항목 25개(게스트 로그인 포함)를 한 번에 확인합니다. 실행할 때마다 테스트 계정 두 개와 게스트 계정 하나가 생깁니다. Windows PowerShell 5.1과 PowerShell 7(Windows·macOS·Linux)에서 동작합니다.

```powershell
cd backend
# 프론트 rewrite를 거쳐 확인 (쿠키·CSRF가 실제 사용자와 같은 경로)
powershell -ExecutionPolicy Bypass -File ./api/Test-AuthMaterialsDeployment.ps1 -BaseUri https://teachmi-frontend.vercel.app
# 백엔드 주소로 직접 확인
pwsh ./api/Test-AuthMaterialsDeployment.ps1 -BaseUri https://<백엔드 배포 주소>
```

| 확인 항목 | 기대 결과 |
| --- | --- |
| 가입·중복 가입(대소문자 무시) | 201, 409 |
| 틀린 비밀번호·없는 이메일 | 같은 본문의 401 |
| 로그인 쿠키 | HttpOnly, HTTPS 주소면 Secure까지 |
| `/auth/me` 로그인·비로그인 | 200, 401 |
| 직접 입력 자료, CSRF 헤더 없는 변경 | 201, 403 |
| PDF 업로드 → 분석 | 202 → done, 개념 후보 2개 |
| 지원하지 않는 형식 | 415 |
| 글자 없는 PDF → 다시 시도 | failed/no_text → 202 |
| 다른 회원의 자료 조회·개념 수정·학습 시작 | 모두 404 |
| 업로드 자료 개념으로 B 학습 시작 | 201 (AI 호출 없음) |
| 로그아웃 → 이전 쿠키 재사용 → 재로그인 | 204 → 401 → 내 자료 200 |

매 실행마다 `qa-a-<시각>@example.com`, `qa-b-<시각>@example.com` 계정을 새로 만들고 비밀번호는 무작위로 생성해 출력하지 않습니다. 검수 계정과 자료는 서버에 남습니다. 실패가 있거나 서버에 연결할 수 없으면 종료 코드 1입니다.

## 확인 방법

`backend/`에서 `.\gradlew.bat build`. `AuthApiTest`는 가입 정규화·중복·동시 가입, 쿠키 속성, 동일한 로그인 실패 응답과 429, CSRF, 로그아웃 뒤 쿠키 재사용 401, 만료를 검사합니다. `MaterialApiTest`는 직접 입력·키 재사용, 타인 접근 404, 개념 추가·수정, B 어댑터와 실제 `POST /api/v1/sessions` 연결을 검사합니다. `MaterialUploadApiTest`는 테스트 안에서 만든 PDF·PPTX(한글)·PPT로 업로드 → 분석 → 개념 후보 → B 학습 생성, 글자 없는 파일·손상 파일 실패와 직접 입력 복구, 재시도, 늦은 결과 폐기, 재시작 재개, 형식·용량·키 검사를 확인합니다.
