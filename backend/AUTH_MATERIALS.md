# A파트 인증·자료 연동 계약

[백엔드 A 이슈 #4](https://github.com/RTAman/TEACHMi/issues/4)의 1차 구현입니다. 이메일·비밀번호 가입과 쿠키 로그인, 직접 입력 자료·개념 API, B가 정의한 `CurrentMember`·`ConceptSource`·`LearningSourceReader` 연결을 제공합니다. **파일 업로드·분석·재시도(`POST /materials` multipart, `/retry`)는 다음 PR에서 추가합니다.**

- 요청·응답 명세: [api/auth-materials.openapi.json](api/auth-materials.openapi.json) (`AuthMaterialsOpenApiContractTest`가 실제 응답과 대조)
- 프론트 연동 가이드: [AUTH_API_HANDOFF.md](AUTH_API_HANDOFF.md)

## 로그인 방식

| 항목 | 규칙 |
| --- | --- |
| 인증 쿠키 | `TEACHMI_SESSION`, HttpOnly, SameSite=Lax, Path=/, 7일. 배포(HTTPS)에서는 Secure. 로컬 `.env`는 `AUTH_COOKIE_SECURE=false` |
| CSRF | 로그인 상태의 POST·PUT·PATCH·DELETE는 `X-CSRF-Token` 헤더가 필요합니다. 값은 로그인 응답이나 `GET /auth/me`의 `csrfToken`입니다. 없거나 틀리면 `403 CSRF_TOKEN_INVALID` |
| 저장 | 이메일은 소문자로 정규화해 고유 제약을 둡니다. 비밀번호는 BCrypt, 인증 토큰은 SHA-256 해시만 DB에 저장합니다 |
| 만료·폐기 | 만료되거나 로그아웃한 쿠키로 보호 API를 부르면 401입니다. 학습 데이터는 그대로 남습니다 |

프론트는 `fetch(..., { credentials: 'include' })`로 쿠키를 보내고, 앱을 열 때 `GET /auth/me`로 로그인 상태와 `csrfToken`을 받아 변경 요청 헤더에 넣습니다.

로그인 실패 제한은 단일 서버 메모리 방식이며, 만료 항목은 다음 요청에서 이메일 키까지 정리합니다. 저장 한도는 최대 10,000개 이메일이고 동시 실패의 개별 큐도 설정된 실패 횟수만 보관합니다. 모든 슬롯이 아직 유효하면 새로운 이메일 시도는 429로 제한하고 기존 이메일의 제한을 제거하지 않습니다. 만료 또는 성공 로그인 초기화로 슬롯이 비면 다시 허용합니다. 여러 서버에서는 기존 안내대로 공유 DB/Redis 방식이 필요합니다.

## 인증 API

| 요청 | 성공 | 실패 |
| --- | --- | --- |
| `POST /api/v1/auth/signup` `{email, password}` | `201 {id, email}`. 로그인시키지 않음 | 형식 `422 INVALID_EMAIL`·`INVALID_PASSWORD`(8~64자), 중복 `409 EMAIL_ALREADY_REGISTERED` |
| `POST /api/v1/auth/login` `{email, password}` | `200 {id, email, csrfToken, expiresAt}` + Set-Cookie | 미등록·비밀번호 오류 모두 `401 INVALID_CREDENTIALS`, 15분에 5회 실패하면 `429 TOO_MANY_LOGIN_ATTEMPTS` |
| `GET /api/v1/auth/me` | `200 {id, email, csrfToken}` | `401 UNAUTHENTICATED` |
| `POST /api/v1/auth/logout` (CSRF 헤더) | `204`, 서버 세션 폐기 + 쿠키 삭제 | CSRF 누락 `403` |

오류 본문은 `{"code": "...", "message": "..."}`입니다.

## 자료·개념 API

모든 요청은 로그인이 필요하고, 다른 회원의 자료·개념은 존재 여부와 관계없이 `404 NOT_FOUND`입니다. 생성 요청에는 `Idempotency-Key`(1~128자)가 필요하며, 같은 키·같은 본문을 다시 보내면 처음 결과를 돌려줍니다. 같은 키에 다른 본문은 `409 IDEMPOTENCY_KEY_REUSED`입니다.

| 요청 | 성공 | 비고 |
| --- | --- | --- |
| `POST /api/v1/materials` JSON `{title?, text, concept?: {name, scope}}` | `201 MaterialView`, `Location` | S02-5 직접 입력. 바로 `status: done`. 본문 최대 15,000자 |
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

`status`는 `analyzing`·`done`·`failed`, `errorCode`는 `format`·`size`·`no_text`·`analysis`입니다. 파일 분석은 다음 PR에서 이 값을 채웁니다.

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

`V8__create_members_and_materials.sql`이 `app_user`, `auth_session`, `material`, `source_chunk`, `concept`, `concept_source`, `material_request`를 만듭니다. B의 `learning_session.member_id`·`material_id`·`concept_id`는 문자열 그대로 두었고 FK는 걸지 않았습니다. C의 다음 마이그레이션은 V9부터 사용하세요.

## 확인 방법

`backend/`에서 `.\gradlew.bat build`. `AuthApiTest`는 가입 정규화·중복·동시 가입, 쿠키 속성, 동일한 로그인 실패 응답과 429, CSRF, 로그아웃 뒤 쿠키 재사용 401, 만료를 검사합니다. `MaterialApiTest`는 직접 입력·키 재사용, 타인 접근 404, 개념 추가·수정, B 어댑터와 실제 `POST /api/v1/sessions` 연결을 검사합니다.
