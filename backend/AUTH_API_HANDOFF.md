# A파트 API 인수인계: 로그인·자료 등록 프론트 연동

로그인(이메일+비밀번호)과 S02 자료 등록을 붙이는 방법입니다. 요청·응답의 정확한 형식은 [OpenAPI 명세](api/auth-materials.openapi.json)에 있고, 실제 서버 응답과 일치하는지 CI에서 검사합니다. 학습 세션·대화(S03~S05)는 B의 [API_HANDOFF.md](API_HANDOFF.md)와 [api/openapi.json](api/openapi.json)을 보세요. B·C가 쓰는 Java 연결 방법은 [AUTH_MATERIALS.md](AUTH_MATERIALS.md)에 있습니다.

## 1. 한눈에 보기

| 화면 | 호출 | 다음 화면 |
| --- | --- | --- |
| 스플래시 | `GET /api/v1/auth/me` | 200 → 홈, 401 → 로그인 |
| 회원가입 | `POST /api/v1/auth/signup` | 201 → 로그인 화면 (자동 로그인 안 됨) |
| 이메일 로그인 | `POST /api/v1/auth/login` | 200 → 홈 |
| 마이페이지 로그아웃 | `POST /api/v1/auth/logout` | 204 → 로그인 화면 |
| S02-1 파일 업로드 | `POST /api/v1/materials` (multipart) | 202 → S02-2 분석 중 |
| S02-2 분석 중 | `GET /api/v1/materials/{materialId}`를 2초 간격 | `done` → S02-4, `failed` → S02-3 |
| S02-3 분석 실패 | 다시 시도 `POST …/retry` 또는 직접 입력 | 202 → S02-2, 직접 입력 → S02-5 |
| S02-5 직접 입력 저장 | `POST /api/v1/materials` (JSON) | 201 → S03 (materialId, conceptId 전달) |
| S02-4 개념 선택 | `GET /api/v1/materials/{materialId}` | 개념 카드 표시 |
| S02-5 개념 추가·수정 | `POST …/concepts`, `PATCH …/concepts/{conceptId}` | 목록 갱신 |
| S03 시작 | B의 `POST /api/v1/sessions` | B 문서 참고 |

## 2. 꼭 지켜야 할 규칙 4가지

1. **쿠키를 보낸다.** 로그인은 `TEACHMI_SESSION` HttpOnly 쿠키입니다. JS에서 읽을 수 없고 읽을 필요도 없습니다. 모든 요청에 `credentials: 'include'`를 넣습니다.
2. **변경 요청에 CSRF 헤더를 넣는다.** 로그인한 상태의 POST·PUT·PATCH·DELETE는 `X-CSRF-Token` 헤더가 필요합니다. B·C API도 마찬가지입니다. 값은 로그인 응답과 `/auth/me` 응답의 `csrfToken`입니다. 메모리(상태값)에 두고, 새로고침으로 사라지면 `/auth/me`로 다시 받습니다. 회원가입·로그인 요청에는 필요 없습니다.
3. **생성 요청에 Idempotency-Key를 넣는다.** 자료 생성·개념 추가는 `Idempotency-Key` 헤더가 필수입니다. 버튼을 누를 때 `crypto.randomUUID()`로 만들고, 네트워크 오류로 **같은 요청을 다시 보낼 때는 같은 키**를 씁니다. 그래야 자료가 두 번 생기지 않습니다.
4. **같은 사이트에서 호출한다.** 프론트와 백엔드 주소가 다르면 쿠키가 전달되지 않습니다. 개발 중에는 개발 서버 프록시, 배포에서는 Vercel rewrite로 `/api`를 백엔드로 넘깁니다(5장).

## 3. 공통 요청 함수 예시

아래 함수를 그대로 쓰면 1~3번 규칙이 자동으로 지켜집니다.

```ts
type ApiError = { status: number; code: string; message: string };

let csrfToken: string | null = null;           // 로그인·/auth/me 응답에서 채움
export const setCsrfToken = (token: string | null) => { csrfToken = token; };

const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export async function api<T>(path: string, init: RequestInit & { idempotencyKey?: string } = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  // FormData(파일 업로드)는 브라우저가 Content-Type을 정하므로 JSON 문자열일 때만 지정합니다.
  if (typeof init.body === 'string' && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (UNSAFE.has(method) && csrfToken) headers.set('X-CSRF-Token', csrfToken);
  if (init.idempotencyKey) headers.set('Idempotency-Key', init.idempotencyKey);

  const res = await fetch(`/api/v1${path}`, { ...init, method, headers, credentials: 'include' });
  if (res.status === 204) return undefined as T;
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    const error: ApiError = { status: res.status, code: body?.code ?? 'UNKNOWN', message: body?.message ?? '잠시 후 다시 시도해 주세요.' };
    if (res.status === 401) { setCsrfToken(null); /* 로그인 화면으로 이동 */ }
    throw error;
  }
  return body as T;
}
```

`403 CSRF_TOKEN_INVALID`가 오면 `GET /auth/me`로 토큰을 다시 받은 뒤 한 번만 재시도하세요.

## 4. 화면별 연동

### 스플래시 (2초 후 이동)

```ts
try {
  const me = await api<{ id: string; email: string; csrfToken: string }>('/auth/me');
  setCsrfToken(me.csrfToken);
  // 홈으로
} catch {
  // 로그인 화면으로
}
```

### 회원가입

서버에는 `email`, `password`만 보냅니다. **비밀번호 확인은 프론트에서만** 비교합니다.

```ts
await api('/auth/signup', { method: 'POST', body: JSON.stringify({ email, password }) });
// 성공(201): "가입이 완료됐어요" 안내 후 로그인 화면으로
```

| code | 상황 | 안내 문구 예시 |
| --- | --- | --- |
| `INVALID_EMAIL` (422) | 이메일 형식 오류 | 이메일 형식을 확인해 주세요 |
| `INVALID_PASSWORD` (422) | 비밀번호 8~64자 아님 | 비밀번호는 8~64자로 입력해 주세요 |
| `EMAIL_ALREADY_REGISTERED` (409) | 이미 가입한 이메일 | 이미 가입한 이메일이에요. 로그인해 주세요 |

### 로그인

```ts
const login = await api<{ id: string; email: string; csrfToken: string; expiresAt: string }>(
  '/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
setCsrfToken(login.csrfToken);
// 홈으로
```

| code | 상황 | 안내 문구 예시 |
| --- | --- | --- |
| `INVALID_CREDENTIALS` (401) | 없는 이메일 또는 틀린 비밀번호(일부러 구분하지 않음) | 이메일 또는 비밀번호가 올바르지 않아요 |
| `TOO_MANY_LOGIN_ATTEMPTS` (429) | 15분 안에 5번 실패 | 잠시 후 다시 시도해 주세요 |

로그인은 7일 유지됩니다. 만료되면 어떤 API든 401이 오니 로그인 화면으로 보내 주세요.

### 로그아웃 (마이페이지)

```ts
await api('/auth/logout', { method: 'POST' });
setCsrfToken(null);
// 이전 계정의 화면 상태·작성 중인 입력·로컬 임시 저장을 모두 비운 뒤 로그인 화면으로
```

기기 임시 저장(작성 중인 설명·답안)은 **회원 ID별로 키를 나눠** 저장하고, 로그아웃할 때 비웁니다. 그래야 다른 계정으로 로그인했을 때 이전 사람의 내용이 보이지 않습니다.

### S02-1~3 파일 업로드와 분석

PDF·PPT·PPTX 파일 하나를 올립니다(기본 20MB 이하). 서버는 바로 `202`와 `status: "analyzing"`을 돌려주고 분석을 계속합니다. **사용자가 화면을 닫아도 분석은 진행되니**, 다시 들어오면 같은 `materialId`로 조회하면 됩니다.

```ts
const form = new FormData();
form.append('file', file);                       // <input type="file" accept=".pdf,.ppt,.pptx">
// form.append('title', '회계원리 7주차');         // 선택. 생략하면 확장자를 뺀 파일 이름
const key = crypto.randomUUID();                 // 업로드 버튼을 누를 때 한 번. 재전송에는 같은 키
let material = await api<MaterialView>('/materials', { method: 'POST', idempotencyKey: key, body: form });

// S02-2: 분석이 끝날 때까지 2초 간격으로 조회
while (material.status === 'analyzing') {
  await new Promise((resolve) => setTimeout(resolve, 2000));
  material = await api<MaterialView>(`/materials/${material.materialId}`);
}
// done → S02-4 개념 선택, failed → S02-3 실패 화면 (errorCode로 문구 분기)
```

| 업로드 즉시 오류 | 상황 | 안내 문구 예시 |
| --- | --- | --- |
| `415 UNSUPPORTED_FORMAT` | PDF·PPT·PPTX가 아님 | PDF나 PPT 파일만 올릴 수 있어요 |
| `413 FILE_TOO_LARGE` | 20MB 초과 | 20MB 이하 파일로 올려 주세요 |
| `422 FILE_REQUIRED` | 빈 파일 | 파일을 선택해 주세요 |

| 분석 실패 `errorCode` | 상황 | S02-3 안내와 버튼 |
| --- | --- | --- |
| `no_text` | 글자를 읽지 못함 (스캔한 이미지 PDF 등) | "자료에서 글자를 읽지 못했어요" → [학습 내용 직접 입력] 권장 |
| `format` | 파일이 손상됐거나 열 수 없음 | "파일을 열 수 없어요" → [다른 파일 올리기] |
| `analysis` | 서버 처리 중 오류 | "분석 중 문제가 생겼어요" → [다시 시도] |

**다시 시도**는 실패한 파일에만 쓸 수 있습니다. 성공하면 `202`와 `status: "analyzing"`이 오니 다시 조회를 시작합니다.

```ts
material = await api<MaterialView>(`/materials/${materialId}/retry`, { method: 'POST', idempotencyKey: crypto.randomUUID() });
```

**개념 후보**는 페이지·슬라이드 제목으로 만듭니다. 같은 제목이 이어지는 페이지는 하나로 묶여 `location`이 `p.6~9`처럼 나옵니다. 표지·목차·Q&A 같은 페이지는 빠집니다. 후보가 없으면 `concepts`가 빈 배열이니 "개념을 찾지 못했어요"와 [목록에 없는 개념 직접 입력] 버튼을 보여주세요.

### S02-5 직접 입력으로 자료 등록

자료 본문과 학습할 개념을 한 번에 저장합니다. 바로 `status: "done"`이고 개념이 하나 들어 있습니다.

```ts
const key = crypto.randomUUID();   // 저장 버튼을 누를 때 한 번 만들고, 재시도에는 같은 키
const material = await api<MaterialView>('/materials', {
  method: 'POST', idempotencyKey: key,
  body: JSON.stringify({
    text: pastedText,                          // 최대 15,000자
    concept: { name: conceptName, scope },     // 이름 60자, 범위 500자
  }),
});
const conceptId = material.concepts[0].conceptId;
// S03으로 이동: B의 POST /api/v1/sessions 에 { materialId: material.materialId, conceptId } 전달
```

### S02-4 개념 선택 화면

```ts
const material = await api<MaterialView>(`/materials/${materialId}`);
```

| 응답 필드 | 화면에서 쓰는 곳 |
| --- | --- |
| `status` | `analyzing` 분석 중 / `done` 개념 선택 / `failed` 실패 화면 |
| `errorCode` | 실패 원인별 문구: `no_text` 글자를 읽지 못함, `format` 파일을 열 수 없음, `analysis` 분석 오류 (위 표 참고) |
| `sourceType`, `fileName` | `file`이면 업로드한 파일, `text`면 직접 입력 |
| `concepts[].name`, `scope` | 라디오 카드의 개념명과 한 줄 설명 |
| `concepts[].location` | 카드 오른쪽 위치 표시 (예: `p.6~9`, `직접 입력 1`) |
| `concepts[].custom` | 사용자가 직접 추가·수정한 개념 |
| `title`, `fileName` | 상단 자료 이름 |

새로 추가한 개념은 목록 맨 위에 옵니다. 선택은 서버에 저장하지 않으니, 고른 `conceptId`를 들고 S03으로 넘어가면 됩니다.

### S02-5 개념 추가·수정 시트

```ts
// 목록에 없는 개념 추가 (새 입력 모드)
const added = await api<ConceptView>(`/materials/${materialId}/concepts`, {
  method: 'POST', idempotencyKey: crypto.randomUUID(),
  body: JSON.stringify({ name, scope, evidenceText }),   // evidenceText: "자료 내용 붙여넣기" 칸 (선택)
});

// 이름·범위 수정 (수정 모드). 바꾼 항목만 보냅니다.
const updated = await api<ConceptView>(`/materials/${materialId}/concepts/${conceptId}`, {
  method: 'PATCH', body: JSON.stringify({ scope }),
});
```

| code | 상황 |
| --- | --- |
| `CONCEPT_REQUIRED` (422) | 개념 이름이나 범위가 비어 있음. 저장 버튼 비활성으로 미리 막아 주세요 |
| `TEXT_REQUIRED` (422) | 자료 내용이 비어 있음 |
| `NOT_FOUND` (404) | 없거나 다른 회원의 자료. "자료를 찾을 수 없어요" 후 홈으로 |
| `IDEMPOTENCY_KEY_REUSED` (409) | 같은 키로 다른 내용을 보냄. 새 키로 다시 보내면 됨 |

## 5. 개발·배포 연결

**개발 중 (Vite 예시)**: 프론트 개발 서버가 `/api`를 로컬 백엔드로 넘기면 쿠키가 같은 사이트로 처리됩니다.

```ts
// vite.config.ts
export default defineConfig({
  server: { proxy: { '/api': 'http://localhost:8080' } },
});
```

Next.js라면 `next.config.js`의 `rewrites`에 같은 규칙을 넣습니다.

**배포 (Vercel)**: 백엔드 주소가 정해지면 `vercel.json`에 rewrite를 추가합니다. 브라우저는 `teachmi-frontend.vercel.app/api/...`만 호출하므로 쿠키가 막히지 않습니다.

```json
{
  "rewrites": [{ "source": "/api/:path*", "destination": "https://<백엔드 배포 주소>/api/:path*" }]
}
```

## 6. 로컬에서 직접 해보기

1. 백엔드 실행: [backend/README.md](README.md)의 "처음 실행하기"
2. 회원가입·로그인을 화면에서 하거나, 테스트 계정은 [AUTH_MATERIALS.md](AUTH_MATERIALS.md)의 "테스트 회원 준비"를 따라 만듭니다.
3. 명세 보기: [api/auth-materials.openapi.json](api/auth-materials.openapi.json)을 [Swagger Editor](https://editor.swagger.io)에 붙여 넣으면 화면으로 볼 수 있습니다. TypeScript 타입이 필요하면 `npx openapi-typescript backend/api/auth-materials.openapi.json -o src/api/auth-materials.d.ts`로 생성합니다.

## 7. 알아둘 제한

| 항목 | 현재 동작 |
| --- | --- |
| 파일 최대 용량 | 기본 20MB. 기획에서 정하면 서버 환경 변수 `MATERIAL_MAX_FILE_SIZE`로 바꿉니다 |
| 스캔한 이미지 PDF | 글자 인식(OCR)을 하지 않아 `no_text`로 실패합니다. 직접 입력으로 안내해 주세요 |
| 개념 후보 | AI가 아니라 페이지·슬라이드 제목으로 만듭니다. 이름·범위는 S02-5에서 고칠 수 있습니다 |
| 분석 취소 | 업로드 취소 API는 없습니다. 다른 파일을 올리면 새 자료로 처리됩니다 |

변경이 생기면 이 문서와 명세를 같은 PR에서 함께 고칩니다.
