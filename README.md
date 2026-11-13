# TEACHMi

### AI에게 가르치며, 내 이해를 완성하다

[![Backend CI](https://github.com/RTAman/TEACHMi/actions/workflows/backend.yml/badge.svg?branch=main)](https://github.com/RTAman/TEACHMi/actions/workflows/backend.yml)

**TEACHMi는 강의자료 속 개념을 AI 학생에게 설명하고, 응용문제와 재설명으로 이해도를 점검하는 학습 서비스입니다.**

읽을 때는 이해한 것 같았지만, 막상 내 말로 설명하려면 막히는 순간이 있습니다. TEACHMi는 이 순간을 학습의 출발점으로 삼습니다. 사용자가 선생님이 되어 설명하면 AI 학생이 사례와 이유를 묻고, 설명이 어려운 부분은 힌트와 쉬운 예시로 함께 풀어갑니다. 이어지는 문제 풀이와 학습 리포트로 어디까지 스스로 이해했는지 돌아볼 수 있습니다.

> 이 저장소는 해커톤 프로젝트의 **백엔드 MVP와 API 연동 문서**를 제공합니다. `frontend/`에는 현재 연동 가이드가 있으며, 실행 가능한 웹 프론트엔드 빌드는 포함되어 있지 않습니다.

## 어떤 문제를 해결하나요?

- **설명할 기회가 부족한 학습:** 강의자료를 내 말로 풀어내고 AI 학생의 질문에 답하며 개념을 정리합니다.
- **적용 단계에서 드러나는 이해의 빈틈:** 자료에 근거한 응용문제를 풀고, 오답의 원리를 재설명한 뒤 같은 문항에 다시 도전합니다.
- **결과만으로는 보이지 않는 학습 과정:** 최초·최종 설명, 도움 사용, 재학습과 문항별 결과를 저장해 리포트로 확인합니다.

## 사용자 여정

1. **가입·로그인** — 내 계정으로 학습 기록을 관리합니다.
2. **강의자료 등록** — 텍스트를 직접 입력하거나 PDF·PPT·PPTX 파일을 올립니다.
3. **개념 선택** — 분석된 원문과 개념 후보를 확인하고 학습할 범위를 고릅니다.
4. **AI 학생에게 설명** — 처음 학습 또는 복습 모드를 선택하고 사례·개념·이유를 설명합니다. 필요하면 힌트·쉬운 예시·모르겠어요를 사용합니다.
5. **설명 마치기·문제 풀이** — 최종 설명을 확정한 뒤, AI가 생성한 응용문제 두 개에 답과 풀이를 제출합니다.
6. **오답 재학습** — 부족했던 부분을 재설명하고 같은 문항에 다시 도전하거나 해설을 확인합니다.
7. **리포트·이어하기** — 학습 과정과 결과를 확인하고, 다시 로그인해도 저장한 학습을 이어갑니다.

## 주요 기능

| 영역 | 구현 내용 |
| --- | --- |
| 인증·내 자료 | 이메일·비밀번호 가입과 로그인, 세션 쿠키·CSRF, 회원별 자료 소유권 검증 |
| 자료 분석 | 직접 입력·PDF·PPT·PPTX, 페이지·슬라이드별 원문 추출, PPTX SmartArt·발표자 노트 추출, 분석 상태·실패 처리 |
| 개념 학습 | 개념 후보와 직접 추가, 처음 학습 요약, 복습의 첫 사례 질문, AI 학생과 단계별 대화 |
| 학습 도움 | 힌트·쉬운 예시·모르겠어요, 최초·최종 설명과 도움 기록, 설명 마치기 |
| 평가·재설명 | 원문 기반 문항 2개, 정답·오답·판정 보류, 오답 재설명·동일 문항 재도전·해설 보기 |
| 학습 리포트 | 문항별 결과, 도움 사용과 대화 흐름, 망설임·핵심 요소, 비교 가능한 이전 학습과의 비교 |
| 저장·복원 | 세션·대화·답안·리포트 저장, 홈의 진행 중 학습과 최근 리포트, 재접속 복원 |

## TEACHMi가 집중한 점

### 1. 사용자가 설명을 이끄는 AI 학생

AI 학생의 질문에 사례와 개념, 그 이유를 설명하는 과정을 설계했습니다. 도움을 요청했다고 학습 단계가 자동으로 완료되지는 않으며, 사용자의 최종 설명과 설명 마치기를 거쳐 문제 풀이로 이어집니다.

### 2. 강의자료의 출처에 근거한 생성

선택한 개념의 실제 원문을 생성 요청에 연결하고, 출처 ID와 인용문을 서버에서 검증합니다. B의 대화는 원문에서 만든 인용 후보 번호를 선택하게 한 뒤 서버가 정확한 인용을 복원합니다. 문항·채점에도 짧은 정확 인용 후보와 검증 규칙을 적용합니다. 인용 검증과 별개로, AI의 설명·문항·채점 품질은 계속 확인할 대상입니다.

### 3. 오답 이후까지 연결되는 학습

오답에 대한 재설명을 마치면 기존 문항으로 돌아옵니다. 첫 시도의 정답과 재설명 후 정답, 해설 열람, 판정 보류를 구분해 기록하므로 학습 과정을 함께 돌아볼 수 있습니다.

### 4. 중단과 실패를 고려한 저장

중복 전송에는 요청 키와 저장된 결과를 사용합니다. AI 호출 결과가 불확실한 경우에는 원장과 입력을 보존하고 자동 재호출을 차단합니다. 다시 접속했을 때도 저장된 내용과 복구 상태를 확인할 수 있도록 구성했습니다.

## 기술 구성

| 구분 | 기술 |
| --- | --- |
| 백엔드 | Java 21, Spring Boot 4.1.2, Spring MVC |
| 데이터 | PostgreSQL 17, Spring Data JPA·JDBC, Flyway |
| 생성형 AI | Gemini API, 공통 호출 포트·사용량 제한·출처 검증 |
| 파일 처리 | Apache PDFBox, Apache POI |
| 인증 | 비밀번호 해시, 서버 세션 쿠키, CSRF 검증 |
| 실행·검증 | Gradle Wrapper, Docker Compose, GitHub Actions, OpenAPI 계약 테스트 |

백엔드 A·B·C는 하나의 Spring Boot 애플리케이션과 공통 PostgreSQL에서 동작합니다.

| 담당 영역 | 패키지 | 연동 문서 |
| --- | --- | --- |
| A — 인증·자료·개념 | `auth`, `material` | [A 인증·자료 API](backend/AUTH_API_HANDOFF.md) |
| B — 학습·대화·재설명 | `learning`, `ai` | [B 학습 API](backend/API_HANDOFF.md) |
| C — 문항·채점·리포트·홈 | `assessment`, `home` | [C 평가·리포트 API](backend/ASSESSMENT_API.md) |

## 로컬에서 실행하기

**준비물:** JDK 21, Docker와 Docker Compose. 아래 명령은 개발용 PostgreSQL을 사용하며, 기존 `.env`가 있으면 유지합니다.

```powershell
git clone https://github.com/RTAman/TEACHMi.git
cd TEACHMi/backend
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --wait db
.\gradlew.bat bootRun
```

macOS·Linux에서는 환경 파일을 준비한 뒤 `./gradlew bootRun`을 사용합니다. 자세한 운영체제별 절차는 [백엔드 실행 가이드](backend/README.md)를 참고하세요.

- 서버 상태: `http://localhost:8080/actuator/health`
- API 기본 경로: `/api/v1`
- 기본 설정은 `GEMINI_ENABLED=false`입니다. 인증·자료 등록·분석·세션 조회를 실행할 수 있으며, AI 생성 단계는 키와 모델을 설정해야 사용할 수 있습니다.
- API 키와 실제 DB 비밀번호는 서버 환경 변수 또는 Git에서 제외한 `.env`에만 보관합니다. 공개 저장소나 프론트엔드 번들에 넣지 않습니다.

## 검증과 현재 범위

GitHub Actions에서 PostgreSQL 기반 회귀 테스트, OpenAPI 계약, 대체 AI 호출을 사용하는 HTTP 통합 흐름과 재시작 복원, AMD64·ARM64 배포 패키지를 검증합니다. [최신 main CI 결과](https://github.com/RTAman/TEACHMi/actions/workflows/backend.yml?query=branch%3Amain)와 [통합 검증 기록](backend/INTEGRATION_READINESS.md)을 확인할 수 있습니다.

- 실제 Gemini 시험은 일반 자동 테스트와 분리합니다. 기본 빌드·CI는 실제 Gemini를 호출하지 않습니다.
- 백엔드 핵심 API 구현과 웹 프론트엔드·실제 배포 환경의 검증 범위를 구분합니다. 외부 시연 주소와 영상은 이 README에 아직 등록되어 있지 않습니다.
- 텍스트가 없는 스캔 PDF의 OCR, 모든 문서 형식·레이아웃과 다수 사용자 부하는 현재 검증 범위에 포함되지 않습니다.
- 배포 환경 설정과 백업·복원·롤백·AI 호출 결과 불확실 시 대응은 [운영 런북](backend/deploy/RUNBOOK.md)에 정리했습니다.

## 기획·디자인·개발 문서

- [프로젝트 기획안](./1호선톤%203팀%20프로젝트%20기획안%20초안.pdf)
- [Figma 디자인](https://www.figma.com/design/dZTY1TqL3ceEXh8QOU3TD7/TEACHMi?node-id=78-2345) · [와이어프레임](https://www.figma.com/design/dZTY1TqL3ceEXh8QOU3TD7/TEACHMi)
- [백엔드 실행 가이드](backend/README.md) · [프론트엔드 연동 가이드](frontend/README.md)
- [A OpenAPI](backend/api/auth-materials.openapi.json) · [B OpenAPI](backend/api/openapi.json)
- [공통 Gemini 호출 정책](backend/COMMON_GEMINI_PORT.md) · [학습 근거 조회](backend/LEARNING_EVIDENCE.md)

## 함께 개발하기

[이슈](https://github.com/RTAman/TEACHMi/issues)에 작업 범위와 API 변경 사항을 공유하고, 최신 `main`에서 작업 브랜치를 만들어 PR로 검토합니다. 기획·디자인의 결정은 해당 이슈와 화면 링크에 연결합니다. 기존 마이그레이션, 학습 기록, AI 호출 원장과 성공 캐시를 보존하며 변경합니다.
