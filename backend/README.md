# 백엔드

운영 환경 예제, 도쿄 리전 배포 패키지, 프록시·헬스체크, 백업·복원·롤백·uncertain 대응은 [운영 런북](deploy/RUNBOOK.md)을 참고하세요. 실제 배포는 별도 승인 후 진행합니다.

Java 21, Spring Boot 4.1.2, Spring Data JPA, PostgreSQL 17을 사용합니다. 로컬에서는 각자 PostgreSQL을 띄우고, 세 사람의 코드는 하나의 Spring Boot 서버에서 실행됩니다.

B가 제공하는 [공통 Gemini 호출 포트](COMMON_GEMINI_PORT.md)에 원문 검증·공유 한도·중복 방지와 C 문제 생성·채점의 트랜잭션 규칙을 정리했습니다. [통합 검증과 배포 준비 기록](INTEGRATION_READINESS.md)에서 검증 범위와 남은 실연동 시험을 확인하세요.

## 처음 실행하기

B 초기 질문 인용과 전체 응답 deadline의 후속 수정은 [분석·환경 변수·검증 기록](B_OPENING_TIMEOUT.md)을 참고하세요. 새 B 출력 상한은 전역 상한과 GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS 중 작은 값이며 기본 2048입니다.

JDK 21과 Docker Compose가 필요합니다. Docker Desktop 사용자는 엔진을 먼저 켭니다. 저장소 루트에서 아래 명령을 실행하세요. Gradle은 저장소에 포함된 Wrapper가 내려받습니다.

**Windows PowerShell**

```powershell
cd backend
Copy-Item .env.example .env
docker compose up -d --wait db
.\gradlew.bat bootRun
```

**macOS · Linux**

```sh
cd backend
cp .env.example .env
docker compose up -d --wait db
./gradlew bootRun
```

브라우저에서 [서버 상태](http://localhost:8080/actuator/health)를 열어 `{"status":"UP"}`이 보이면 서버와 DB가 준비된 상태입니다. 터미널에서 서버를 종료하려면 `Ctrl+C`를 누릅니다. `docker compose stop db`는 DB를 멈추고 데이터를 보존합니다. 다음 실행부터는 환경 파일 복사 단계를 생략합니다.

| 로컬 설정 | 기본값 | 변경 방법 |
| --- | --- | --- |
| PostgreSQL | `localhost:5433`, DB·계정 `teachmi` | 첫 실행 전에 `.env`의 `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`를 맞춥니다. |
| Spring Boot | `localhost:8080` | 다른 포트가 필요하면 `.env`에 `SERVER_PORT=8081`을 추가합니다. |

Compose와 Spring 모두 `backend/.env`를 읽습니다. 이미 만든 DB 볼륨은 초기 계정 정보를 유지합니다. 원격 PostgreSQL에 연결할 때는 환경 변수 `DB_URL`에 전체 JDBC URL, `DB_USER`와 `DB_PASSWORD`에 해당 계정 정보를 지정합니다. 실제 비밀번호·AI 키는 개인 환경에만 저장하세요.

**이미 실행 중인 PostgreSQL 17을 쓰는 경우**에는 서버 관리자 계정으로 `createuser --pwprompt teachmi`와 `createdb --owner=teachmi teachmi`를 실행해 개발 전용 계정과 DB를 만듭니다. 이미 준비된 계정과 DB가 있으면 그대로 사용하세요. `backend/.env`에 해당 값과 실제 포트(일반적인 로컬 설치는 `DB_PORT=5432`)를 입력한 뒤 위 절차에서 Compose 단계를 건너뛰고 `bootRun`을 실행합니다.

## 백엔드 구성과 담당 영역

[공통 작업 계획서](../TEACHMi%20백엔드%20작업%20계획서.docx)는 초기 작업 분담 자료입니다. 현재 인증·자료·학습·평가·리포트 API가 하나의 애플리케이션에 구현되어 있으며, 업무 API의 기본 경로는 `/api/v1`입니다. 최신 요청·응답은 아래 연동 문서를 기준으로 확인하세요.

| 담당 | 패키지 · 이슈 | 개발 가이드 | 먼저 공유할 결과 |
| --- | --- | --- | --- |
| A | `auth`, `material` · [#4](https://github.com/RTAman/TEACHMi/issues/4) | [인증·자료·개념](../TEACHMi%20백엔드%20A%20개발%20가이드.docx) | 현재 회원을 확인하는 공통 기능, 자료 분석 상태와 개념 ID |
| B | `learning` · [#5](https://github.com/RTAman/TEACHMi/issues/5) | [학습 세션·AI 대화](../TEACHMi%20백엔드%20B%20개발%20가이드.docx) | 학습 세션 ID, 재개할 단계, 최초·최종 설명과 도움 기록 |
| C | `home`, `assessment`, `report` · [#6](https://github.com/RTAman/TEACHMi/issues/6) | [홈·평가·리포트·배포](../TEACHMi%20백엔드%20C%20개발%20가이드.docx) | 회원별 홈 응답, 답안 제출 예시, 리포트 조회 결과 |

가입·로그인은 A의 이메일·비밀번호와 TEACHMI_SESSION 쿠키를 사용합니다. 변경 요청은 X-CSRF-Token이 필요하며 B와 C는 서버가 확인한 현재 회원을 사용합니다. 로그인 세션 만료 후에도 학습 기록은 계정에 남습니다.

### 파트별 연동 문서

프론트엔드와 다른 파트는 아래 표에서 담당 파트의 문서를 찾습니다. 연동 가이드는 `backend/`에, OpenAPI 명세는 `backend/api/`에 둡니다.

| 담당 | 연동 가이드 (프론트·다른 파트) | OpenAPI 명세 | 기능별 계약 |
| --- | --- | --- | --- |
| A파트 (인증·자료) | [AUTH_API_HANDOFF.md](AUTH_API_HANDOFF.md) | [api/auth-materials.openapi.json](api/auth-materials.openapi.json) | [AUTH_MATERIALS.md](AUTH_MATERIALS.md) |
| B파트 (학습·대화) | [API_HANDOFF.md](API_HANDOFF.md) | [api/openapi.json](api/openapi.json) | 아래 각 계약 문서 |
| C파트 (홈·평가·리포트) | [ASSESSMENT_API.md](ASSESSMENT_API.md) | 별도 OpenAPI 문서 없음 | [평가·리포트 상태 전환](ASSESSMENT_TRANSITIONS.md) |

B의 세션 생성·조회 API, 새 시작 확인과 재시도 규칙, A가 구현할 인증·개념 연결 인터페이스는 [학습 세션 연동 계약](LEARNING_SESSIONS.md)에 정리했습니다.

로그인 후 세션 ID 없이 이어하기 대상을 찾으려면 `GET /api/v1/sessions/active`를 사용합니다. 진행 중 학습은 기존 SessionView, 대상이 없으면 204를 반환합니다. C의 홈도 `LearningSessionService.getActive`를 재사용할 수 있습니다.

Gemini 환경 변수, A의 직접 입력 원문 연결, 출처 검증과 실 API 테스트는 [Gemini 콘텐츠 생성 안내](GEMINI_CONTENT.md)를 확인하세요.

사용자 설명과 도움 전송, 단계 전환, 실패한 전송의 재접속·재시도, C에 전달할 메시지 근거는 [대화 API 계약](CONVERSATION_TURNS.md)에 정리했습니다.

C의 오답에서 재설명을 시작하고 같은 문항으로 돌아오는 연결은 [재설명·문항 복귀 계약](RETEACH.md)을 확인하세요.

C가 평가·리포트에 사용할 실제 설명과 도움 집계는 [학습 근거 조회 계약](LEARNING_EVIDENCE.md)을 확인하세요.

C의 평가·리포트 DB 저장과 B의 report·completed 상태를 함께 전환하는 내부 서비스는 [평가·리포트 상태 전환 계약](ASSESSMENT_TRANSITIONS.md)을 확인하세요. 운영용 C 어댑터 연결 전에는 대기 오류를 반환합니다.

## DB 변경과 빌드

프론트·A·C 인수인계는 [B API 명세와 실행 예시](API_HANDOFF.md)를 확인하세요. [OpenAPI JSON](api/openapi.json)과 실제 인증 정보를 전달받는 PowerShell 요청 도구를 제공하며, 명세·HTTP 응답 및 예제 도구를 CI에서 검증합니다.

서버 시작 시 Flyway가 `src/main/resources/db/migration/`의 SQL을 순서대로 적용합니다. V1은 업무 테이블을 담을 `teachmi` 스키마를 만듭니다. 새 테이블은 `teachmi.테이블명`으로 지정하고 `V2__create_users.sql` 같은 새 파일에 작성합니다. 다음 버전 번호와 병합 순서는 PR에서 C와 맞추고, 적용된 SQL은 새 버전으로 수정합니다. Hibernate는 엔티티와 실제 스키마가 일치하는지 검사합니다.

DB가 실행 중인 상태에서 `backend/`에서 Windows는 `.\gradlew.bat build`, macOS·Linux는 `./gradlew build`를 실행하세요. 테스트는 실제 PostgreSQL의 마이그레이션 이력과 서버의 HTTP 상태 응답을 확인합니다. 빌드한 실행용 JAR은 `build/libs/teachmi-backend-0.0.1-SNAPSHOT.jar`입니다. GitHub Actions도 백엔드 PR마다 같은 Compose DB로 빌드와 테스트를 실행합니다.
