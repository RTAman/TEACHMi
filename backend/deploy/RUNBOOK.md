# TEACHMi 배포 패키지와 운영 런북

이 문서는 별도 배포 승인 후 사용할 절차다. 이 PR은 서버 생성·실제 배포·Gemini 호출을 실행하지 않는다. 기존 DB, 원장, 성공 캐시, 미채점 답안의 삭제·초기화 절차는 제공하지 않는다. 애플리케이션 API, Gemini 키 계산, Flyway V1–V12는 변경하지 않는다.

## 배포 대상과 남은 준비

사용자가 선택한 OCI 홈 리전은 **일본 도쿄(ap-tokyo-1)**다. 제안 사양은 Ubuntu 24.04 ARM64 / Ampere A1 / 2 OCPU / RAM 6GB / 부트 볼륨 50GB의 단일 VM이다. 실제 무료 대상·계정 전체 사용량·도쿄의 A1 잔여 용량을 생성 화면에서 다시 확인한다. 무료 자원은 용량 부족이나 유휴 인스턴스 회수 가능성이 있으므로 DB 백업은 다른 위치에도 보관한다. [OCI Always Free 공식 조건](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

서버·도메인·TLS·실제 브라우저는 아직 검증 대상이다. 이 저장소에는 배포할 프론트엔드 빌드가 없다. 승인 후 동일 도메인용 빌드를 `backend/deploy/frontend/`에 별도 전달해야 한다. 현재 이 디렉터리는 비어 있어 `/`가 404를 반환한다. 프론트엔드 API 기본 경로는 `/api/v1`, 쿠키 요청은 같은 출처로 구성한다.

| 구성 | 노출 및 보존 |
| --- | --- |
| Caddy → Spring Boot → PostgreSQL 17 | 공인 포트는 80/443만 사용. 앱 8080·DB 5432는 호스트에 게시하지 않음 |
| SSH | OCI 보안 규칙과 OS 방화벽에서 운영자 IP로 제한 |
| DB | 프로젝트별 Docker named volume. 앱은 비슈퍼유저 `teachmi_app`, 관리자는 컨테이너 내부 `postgres` |
| 원본 업로드 | `material_file.content` BYTEA에 보관. DB 백업에 PDF·PPT·PPTX 원본 포함 |
| 임시 분석 파일 | 읽기 전용 앱 파일시스템의 `/tmp` tmpfs. 영구 데이터로 사용하지 않음 |
| HTTPS 인증서 | Caddy data/config named volume 보존 |

운영자에게 Docker Compose 플러그인, Docker Buildx, Python 3, 저장 공간·메모리·보안 업데이트 관리 권한이 필요하다. Docker 접근 권한은 사실상 관리자 권한이다. 로컬 개발용 `backend/compose.yaml`과 `.env.example`은 운영 설정으로 사용하지 않는다.

## 환경 파일 준비

다음 명령부터는 **향후 승인된 서버에서 수동 실행**하는 예시다. 저장소 `backend` 디렉터리에서 시작한다.

```sh
sudo install -d -m 700 /etc/teachmi /var/backups/teachmi
sudo install -m 600 deploy/.env.production.example /etc/teachmi/production.env
sudoedit /etc/teachmi/production.env
```

템플릿의 전체 커밋 SHA, 이미지, 본인 도메인, ACME 연락처, 서로 다른 무작위 DB 비밀번호를 채운다. 비밀번호는 URL-safe 문자 24자 이상이다. 값은 따옴표·쉘 확장 없이 `KEY=value` 형식이며 API 키는 서버 파일에만 입력한다. Git, 이미지, 프론트 번들, 명령행 인자, 공개 로그에 넣지 않는다. 초기값은 `GEMINI_ENABLED=false`, 빈 키, `ASSESSMENT_DEMO_ENABLED=false`다.

| 환경 변수 | 승인된 기준 / 의미 |
| --- | --- |
| `APP_REVISION`, `APP_IMAGE` | 전체 40자리 SHA와 동일 SHA 태그 또는 이미지 digest. 임의 latest 사용 금지 |
| `DB_NAME`, `DB_PASSWORD`, `DB_ADMIN_PASSWORD` | 전용 DB와 앱/관리자별 비밀번호 |
| `AUTH_COOKIE_SECURE`, `AUTH_COOKIE_SAME_SITE` | `true`, `Lax`; HttpOnly 세션 쿠키 + 변경 요청 `X-CSRF-Token` |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` |
| `GEMINI_CONNECT_TIMEOUT_SECONDS`, `GEMINI_REQUEST_TIMEOUT_SECONDS` | `3`, `40` |
| `GEMINI_MAX_OUTPUT_TOKENS`, `GEMINI_INTERACTIVE_MAX_OUTPUT_TOKENS` | C/전역 `6144`, B 상한 `2048` |
| `GEMINI_REQUESTS_PER_MINUTE`, `GEMINI_REQUESTS_PER_DAY`, `GEMINI_MEMBER_REQUESTS_PER_DAY` | `10`, `100`, `20`; 공유 원장 기반 요청 한도이며 금액 한도가 아님 |
| `GEMINI_MAX_ATTEMPTS` | `2`; uncertain을 자동 재시도하도록 허용하는 설정이 아님 |

운영 도구는 부모 셸의 Gemini·DB·COMPOSE 설정을 제거한 환경으로 Compose를 호출한다. `docker compose config` 원문에는 비밀 값이 들어갈 수 있으므로 공개하지 않는다. 대신 다음 검증의 안전한 요약만 사용한다. 이 검증은 DNS·실제 이미지·서버 접속 성공을 보장하지 않는다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod check
```

DB init SQL은 **새 빈 볼륨에서만** 실행된다. 환경 파일의 비밀번호 변경은 기존 DB 역할의 비밀번호를 바꾸지 않는다. 이미 존재하는 DB를 연결하거나 비밀번호를 교체할 때는 별도 계획·백업·승인이 필요하다.

## 빌드와 실행

CI가 통과한 정확한 소스를 사용한다. Java 21로 JAR를 만들고 ARM64 런타임 이미지로 패키징한다. 운영 DB를 대상으로 테스트를 실행하지 않는다. 아래 SHA 자리에는 승인된 전체 SHA를 넣는다.

```sh
./gradlew --no-daemon bootJar
docker buildx build --platform linux/arm64 --load \
  --build-arg APP_REVISION=FULL_40_CHARACTER_SHA \
  -t teachmi-backend:FULL_40_CHARACTER_SHA -f Dockerfile .
```

Dockerfile은 테스트한 JAR만 복사한다. 빌드 컨텍스트는 기본 차단 방식으로 환경 파일·자료·테스트 로그를 제외한다. 런타임은 UID/GID 10001, 한글 폰트, 읽기 전용 루트, 메모리 제한, capability 제거를 사용한다. PG/JRE/Caddy 베이스 이미지는 digest로 고정했다. 정기 보안 갱신 때는 새 digest를 별도 검증한다.

이미지 revision 라벨과 실제 승인된 SHA를 확인하고 환경 파일에 같은 이미지를 지정한다. 레지스트리를 쓰면 digest와 접근 권한을 별도로 관리한다. 이 PR은 이미지를 게시하지 않는다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod compose up --confirm-project teachmi-prod
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod status
```

위 `up`은 비공개 앱·DB만 시작한다. DNS A/AAAA, OCI/OS 방화벽, 도메인 소유권과 프론트 빌드 준비를 확인한 뒤 별도 공개 승인 후에만 다음을 실행한다. `public` 프로필은 Caddy의 실제 인증서 발급과 외부 서비스 공개를 수반한다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod --public compose up --confirm-project teachmi-prod
```

Caddy는 `/api/*` 접두사를 그대로 전달하며 `/actuator/*`를 외부 404로 막는다. API를 다른 출처에 둘 경우 이 패키지의 인증 전제가 달라지므로 임의의 `Access-Control-Allow-Origin: *` 대신 쿠키·CSRF·허용 출처를 별도로 설계한다. 요청 61MB 상한, 프록시 응답 헤더 60초·본문 65초를 적용한다. Gemini 40초보다 길게 두어 앱의 진단 응답을 전달한다. 프록시 재시도는 0이며 keepalive를 꺼 유휴 연결에서 POST가 재전송될 가능성을 줄였다. [Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)

## 헬스체크와 시연 전 필수 확인

이미지 헬스체크는 앱 내부 `/actuator/health`의 DB 상태를 확인하며 AI를 호출하지 않는다. DB healthy 이후 앱을 시작하고 앱 healthy 이후 프록시를 시작한다. 초기 120초 유예, 30초 주기이며 Docker의 unhealthy 표시는 자동 재시작을 뜻하지 않는다. `restart: unless-stopped`는 프로세스 종료에 대응한다. 알람 후 DB·메모리·디스크·안전한 오류 코드를 조사하고 불확실 요청이 있으면 반복 재시작하지 않는다. [Compose 시작 순서](https://docs.docker.com/compose/how-tos/startup-order/)

서버 실검증에서는 다음을 별도 확인한다.

1. HTTPS 인증서 체인·갱신, HTTP→HTTPS 이동, 도쿄 실서버 메모리·디스크 여유.
2. 브라우저 가입·로그인·Secure/HttpOnly/Lax 쿠키·CSRF 거절·로그아웃·재로그인 복원.
3. 동일 출처 프론트에서 API 경로, 업로드 크기, 오류 안내와 로딩 종료.
4. AI를 끈 상태에서 파일 분석·재시작 복구·소유권 차단·백업 복원. AI 시험은 예상 호출량을 제시해 별도 승인.
5. 공유 요청 한도와 Google 프로젝트별 비용 알림·사용량 확인, 호스트 자원 및 백업 실패 알림.

현재 로컬 회귀 검증은 A·B·C 474개, 별도 HTTP 350개다. 새 패키지 CI는 AMD64와 QEMU ARM64에서 기동·합성 PDF 분석·비슈퍼유저·프록시 경로·백업/새 DB 복원·uncertain/캐시/미채점 답안 보존을 검증한다. ARM64 에뮬레이션은 실제 도쿄 VM 성능이나 브라우저 TLS 검증을 대체하지 않는다. 기존 과거 실 Gemini 성공 이력은 재실행하지 않았으며 이번 작업의 실 호출은 0회다.

## 백업과 새 DB 복원

백업은 전체 애플리케이션 DB의 일관된 `pg_dump` custom snapshot이다. Flyway 이력, 업로드 BYTEA, 학습·리포트, 성공 캐시, uncertain 원장, 미채점 답안을 함께 보존한다. 역할 비밀번호와 Caddy 인증서는 DB 백업에 없으므로 비밀 환경 파일과 인증서 볼륨도 별도 암호화 백업 정책에 포함한다. 활성 호출 중의 snapshot은 완료 전 상태를 담을 수 있다. 릴리스 전에는 신규 요청을 차단하고 최대 진행 호출·트랜잭션 종료를 확인한 뒤 백업한다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod backup --output /var/backups/teachmi/release_YYYYMMDD_HHMM.dump
```

새 이름만 허용하며 기존 파일은 덮어쓰지 않는다. `.dump`와 `.dump.json` SHA-256 manifest가 모두 있어야 완료다. 실패한 `.partial`은 조사용으로 남고 복원 대상으로 쓰지 않는다. 백업은 0600이며 자료·개인정보·캐시 원문을 포함하므로 공개 GitHub/로그에 올리지 않는다. 승인된 암호화 원격 저장소에 두 파일을 함께 복제하고 복제본 해시·복원 시험을 확인한다. VM 내부 백업만으로 장애 대비가 끝나지 않는다. 정기 일일 백업·보존 기간·오프호스트 보관 책임자를 운영 전에 지정한다. 이 도구에는 자동 삭제/보존기간 정리 기능이 없다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod restore-new \
  --archive /var/backups/teachmi/release_YYYYMMDD_HHMM.dump \
  --database teachmi_restore_20261109 --confirm-database teachmi_restore_20261109
```

실제 이름은 소문자·숫자·밑줄만 사용한다. 도구는 해시 확인 후 **존재하지 않는 새 DB**만 생성한다. 현재 DB나 이미 존재하는 복원 DB는 거절한다. 실패해도 원본 DB/백업은 남고 새 DB를 자동 삭제하지 않는다. 역할 `teachmi_app`로 소유권을 맞춘 단일 트랜잭션 복원이다. [PostgreSQL 17 pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html)

복원 DB는 AI 비활성·공개 트래픽 차단 상태에서 별도 연결로 검증한다. Flyway/Hibernate 검증, 원본 파일 바이트, 회원·세션·리포트, 원장 상태/호출 수, 캐시, 미채점 답안이 일치해야 한다. 새 연결 대상 변경과 재공개는 별도 승인 단계다. 운영 중 생성된 새 사용자 기록을 버리고 과거 snapshot으로 덮어쓰지 않는다.

## 롤백

1. 변경된 배포를 더 진행하지 않고 신규 변경 요청을 차단한다. 진행 호출을 조사하고 환경의 Gemini를 false로 전환한다. 종료 대기는 앱 60초, 컨테이너 120초다. 진행 호출의 결과가 불확실하면 원장을 보존한다.
2. 현재 DB를 새 파일로 백업한다. 직전 검증 이미지 digest/SHA와 환경 설정, 현재 Flyway 버전을 기록한다.
3. **DB 스키마와 호환되는 직전 이미지**를 지정한 뒤 같은 프로젝트에서 앱을 재생성하고 헬스를 확인한다. 기존 볼륨은 그대로 사용한다. 이 PR은 마이그레이션이 없어 현재 V1–V12와 호환된다.
4. 직전 앱이 현재 스키마를 검증하지 못하면 중단한다. Flyway clean/repair, 임의 SQL 역변환, `down -v`, volume prune을 사용하지 않는다.
5. DB 자체 복구가 필요하면 위 새 DB 복원 절차를 사용한다. 백업 이후 발생한 정상 쓰기 손실을 평가하고 보존·대조 계획을 승인받기 전에는 연결을 전환하지 않는다. 기존 DB와 원장은 계속 보존한다.

## 502·503·uncertain 대응과 프론트 계약

`availableActions=wait`만 보고 무한 로딩을 유지하지 않는다. 구조화된 `recovery`와 안전한 오류 코드를 함께 해석하며 자동으로 변경 요청을 재전송하지 않는다. 자세한 필드는 [B 연동 안내](../API_HANDOFF.md), [공통 Gemini 포트](../COMMON_GEMINI_PORT.md)를 기준으로 한다.

| 상태 | 프론트/운영자 조치 |
| --- | --- |
| `recovery.action=wait` | 서버가 안내한 제한된 GET 재조회만 수행. 영구 스피너 대신 시간 경과 시 정적 안내와 다시 조회 버튼 제공 |
| `recovery.action=review_required` / `AI_GENERATION_UNCERTAIN` | 즉시 로딩 종료, 답안 보존, 생성/재시도 차단, 운영자 확인 안내. 자동 POST 금지 |
| `recovery.action=limit_reached` / `AI_ATTEMPT_LIMIT` | 로딩 종료, 한도 도달 안내. 기다리면 해결된다고 안내하지 않음 |
| 502 `INVALID_GEMINI_RESPONSE` | `EVIDENCE_QUOTE_NOT_EXACT`, 출처 ID, `JSON_SCHEMA_INVALID`, `RESPONSE_MAX_TOKENS` 등 안전한 진단 코드를 분리. 검증 실패를 성공으로 표시하지 않음 |
| 타임아웃/503 | `WAITING_HEADERS`, `WAITING_BODY`, `READING_BODY` 및 원장 상태를 확인. 원문/응답 전문/키/답안을 공개 로그에 남기지 않음 |

이 패키지는 프론트 UI를 구현하지 않는다. C의 응답에 recovery가 누락되더라도 uncertain/attempt-limit 코드를 받은 클라이언트는 로딩을 끝내고 자동 재호출을 차단해야 한다. 실제 C 응답과 프론트 연동을 시연 전 확인한다.

승인된 운영자가 특정 회원/세션의 메타데이터를 읽을 때만 다음을 사용한다. UUID 자리에는 실제 비공개 값을 넣는다. SQL은 `BEGIN READ ONLY`와 5초 statement timeout을 사용하고 원문·답안·성공 응답 JSON은 조회하지 않는다.

```sh
sudo python3 deploy/ops.py --env-file /etc/teachmi/production.env --project teachmi-prod ledger --member MEMBER_UUID --session SESSION_UUID
```

사고 시 신규 AI 요청을 차단하고 generation key, 상태, 시도 수, UTC dispatch 시간, 출력 상한과 진단 단계만 비공개 사고 기록에 남긴다. 서버에는 실제 청구 토큰/비용이 저장되지 않는다. 입력 bytes나 출력 상한을 실제 사용량으로 보고하지 않는다. 운영자가 같은 Google 프로젝트·모델·UTC 시간대의 사용량/청구 지표와 대조한다. 집계 지연·다른 트래픽 때문에 특정 요청의 무과금이나 결과 수신 여부가 입증되지 않을 수 있다.

**uncertain 및 만료된 running을 임의 초기화하지 않는다.** 모델·프롬프트·설정·회원·세션·키를 바꿔 우회하지 않는다. 성공 캐시는 기존 읽기 경로로만 복원한다. 이미 저장된 답안과 시도 ID를 보존한다. 사용량만 확인했다고 실패/미과금으로 단정하지 않는다. 정확한 호출 결과와 중복 과금 여부를 확인할 수 없다면 차단 상태를 유지하고 운영자 안내를 제공한다. 현재 일반 운영자용 원장 해제/재생 도구는 없으며, 확인 후 필요한 조치는 별도 코드 검토·승인으로 다룬다. 이 PR은 uncertain 해제 SQL이나 자동 재호출을 제공하지 않는다.
