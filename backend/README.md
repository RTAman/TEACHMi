# 백엔드

Java 21, Spring Boot 4.1.2, Spring Data JPA, PostgreSQL 17을 사용합니다. 로컬에서는 각자 PostgreSQL을 띄우고, 세 사람의 코드는 하나의 Spring Boot 서버에서 실행됩니다.

## 처음 실행하기

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

## 담당 기능과 첫 작업

[공통 작업 계획서](../TEACHMi%20백엔드%20작업%20계획서.docx)에서 전체 API 계약과 일정을 확인할 수 있습니다. 각자 아래 패키지에 첫 기능을 추가합니다. 기본 프로젝트가 제공하는 HTTP 경로는 서버 상태 조회이며, 업무 API는 `/api/v1` 아래에 구현합니다.

| 담당 | 패키지 · 이슈 | 개발 가이드 | 먼저 공유할 결과 |
| --- | --- | --- | --- |
| A | `auth`, `material` · [#4](https://github.com/RTAman/TEACHMi/issues/4) | [인증·자료·개념](../TEACHMi%20백엔드%20A%20개발%20가이드.docx) | 현재 회원을 확인하는 공통 기능, 자료 분석 상태와 개념 ID |
| B | `learning` · [#5](https://github.com/RTAman/TEACHMi/issues/5) | [학습 세션·AI 대화](../TEACHMi%20백엔드%20B%20개발%20가이드.docx) | 학습 세션 ID, 재개할 단계, 최초·최종 설명과 도움 기록 |
| C | `home`, `assessment`, `report` · [#6](https://github.com/RTAman/TEACHMi/issues/6) | [홈·평가·리포트·배포](../TEACHMi%20백엔드%20C%20개발%20가이드.docx) | 회원별 홈 응답, 답안 제출 예시, 리포트 조회 결과 |

가입·로그인은 이메일과 비밀번호를 기본안으로 둡니다. A가 공통 인증을 연결하면 B와 C의 학습 API는 서버가 확인한 현재 회원을 사용합니다. 로그인 세션 만료 후에도 학습 기록은 계정에 남아야 합니다.

B의 세션 생성·조회 API, 새 시작 확인과 재시도 규칙, A가 구현할 인증·개념 연결 인터페이스는 [학습 세션 연동 계약](LEARNING_SESSIONS.md)에 정리했습니다.

## DB 변경과 빌드

서버 시작 시 Flyway가 `src/main/resources/db/migration/`의 SQL을 순서대로 적용합니다. V1은 업무 테이블을 담을 `teachmi` 스키마를 만듭니다. 새 테이블은 `teachmi.테이블명`으로 지정하고 `V2__create_users.sql` 같은 새 파일에 작성합니다. 다음 버전 번호와 병합 순서는 PR에서 C와 맞추고, 적용된 SQL은 새 버전으로 수정합니다. Hibernate는 엔티티와 실제 스키마가 일치하는지 검사합니다.

DB가 실행 중인 상태에서 `backend/`에서 Windows는 `.\gradlew.bat build`, macOS·Linux는 `./gradlew build`를 실행하세요. 테스트는 실제 PostgreSQL의 마이그레이션 이력과 서버의 HTTP 상태 응답을 확인합니다. 빌드한 실행용 JAR은 `build/libs/teachmi-backend-0.0.1-SNAPSHOT.jar`입니다. GitHub Actions도 백엔드 PR마다 같은 Compose DB로 빌드와 테스트를 실행합니다.
