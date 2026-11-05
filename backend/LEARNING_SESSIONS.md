# B 학습 세션 생성과 복원

이 문서는 [B 작업 이슈 #5](https://github.com/RTAman/TEACHMi/issues/5)의 첫 구현에 대한 프론트엔드·A·C 연동 계약입니다. 서버 실행은 [백엔드 실행 안내](README.md)를 따릅니다.

## API

| 메서드와 경로 | 요청 | 결과 |
| --- | --- | --- |
| `POST /api/v1/sessions` | 로그인 정보, `Idempotency-Key`, 아래 JSON | `201`, 생성한 학습과 `Location` 헤더 |
| `GET /api/v1/sessions/{sessionId}` | 로그인 정보 | `200`, 저장된 개념 사본·단계·버전·재개 위치 |

```json
{
  "materialId": "m01",
  "conceptId": "c01"
}
```

자료·개념 ID는 A의 타입이 정해지기 전까지 최대 128자의 문자열로 전달합니다. 학습 세션 ID는 서버가 생성하는 UUID입니다. 회원 ID는 요청 본문에서 받지 않고 인증 연결 인터페이스를 통해 구합니다. 숫자형 A ID도 인터페이스에서 문자열로 변환할 수 있습니다.

첫 응답은 `mode: null`, `stage: "mode"`, `status: "active"`, `version: 0`입니다. `concept`에는 A가 검증한 `name`, `scope`, `sourceChunkIds`가 저장되고 `createdAt`·`updatedAt`은 UTC 시각입니다. 응답에서 받은 세션 ID로 GET을 호출하면 저장한 상태를 복원합니다.

```json
{
  "stage": "mode",
  "path": "/api/v1/sessions/서버가-반환한-UUID"
}
```

위 객체는 응답의 `resume` 값입니다. `path`는 복원 상태를 조회하는 API 주소이고 프론트엔드는 `stage`로 화면을 선택합니다. 종료된 학습은 읽을 수 있으며 `resume`은 `null`입니다. 모드 선택·요약·AI 대화 저장은 B의 후속 작업에서 이 상태를 갱신합니다.

## 충돌과 재시도

POST의 `Idempotency-Key`는 회원별로 구분하며 1~128자입니다. 한 번 시작할 때 키를 만들고 네트워크 오류로 재전송할 때 같은 키와 요청을 유지합니다. 같은 키에 같은 요청이면 최초의 `201` 응답을 재사용하고, 다른 요청이면 `409 IDEMPOTENCY_KEY_REUSED`를 반환합니다. 최초 응답 뒤에 학습 상태가 바뀌었을 수 있으므로 현재 진행 단계는 GET으로 확인합니다.

진행 중인 학습이 있으면 `409 ACTIVE_SESSION_EXISTS`와 `currentSession`에 세션 ID·현재 단계·버전을 제공합니다. 사용자가 새 시작을 확인한 경우, 새 키로 다음 요청을 보냅니다.

```json
{
  "materialId": "m01",
  "conceptId": "c02",
  "replaceSessionId": "409-응답에서-받은-UUID",
  "expectedVersion": 0
}
```

교체할 학습과 버전이 일치하면 기존 상태를 `abandoned`로 바꾸고 새 학습을 한 트랜잭션에서 만듭니다. 다른 탭에서 이미 학습을 바꿨으면 409를 반환합니다. 개념 소유권 검사나 저장이 실패하면 기존 학습이 유지됩니다. 회원별 PostgreSQL 트랜잭션 잠금과 진행 중 회원의 고유 인덱스로 동시 시작·교체를 제어합니다.

| HTTP 상태 | 의미 |
| --- | --- |
| 401 | 인증이 없거나 A의 인증 연결이 만료를 판정 |
| 404 | 자료·개념 또는 세션이 없거나 다른 회원의 기록 |
| 409 | 진행 중 학습·버전 충돌 또는 재시도 키의 다른 요청 |
| 422 | 빈 ID, 잘못된 교체 조건, 누락되거나 너무 긴 재시도 키 |
| 400 | 잘못된 JSON 또는 UUID 형식 |
| 503 | A의 자료 조회 연결이 준비되지 않음 |

## A가 연결할 인터페이스

`learning.CurrentMember`의 `requireId(HttpServletRequest)`를 구현한 Spring bean에서 실제 인증 세션의 만료·폐기를 확인하고 회원 ID를 반환합니다. 기본 경로는 Servlet의 인증된 `Principal.getName()`을 회원 ID로 읽습니다. 로그인 방식이 정해지면 A의 어댑터로 명시적으로 매핑합니다. 쿠키 발급·로그아웃·CSRF 방어는 A의 인증 계층에서 연결합니다.

`learning.ConceptSource`의 `loadOwnedConcept(memberId, materialId, conceptId)`를 구현한 Spring bean에서 다음을 확인합니다.

1. 자료가 현재 회원의 소유인지 확인합니다.
2. 개념이 해당 자료에 속하고 학습 가능한 상태인지 확인합니다.
3. 이름·범위·출처 ID 목록을 `ConceptSource.Snapshot`으로 반환합니다.

없거나 타인 소유인 자료·개념은 `LearningException.notFound()`를 던집니다. 어댑터 없이 로그인한 회원이 생성을 요청하면 503이 나옵니다. 테스트용 개념 소스는 `src/test/`에 두었습니다.

## C와 DB 변경

C는 홈에서 회원의 진행 중 세션 ID를 전달하고, 프론트엔드는 그 ID로 GET을 호출해 재개합니다. 서버 내부에서는 `LearningSessionService.get(memberId, sessionId)`로 같은 소유권 검사를 적용할 수 있습니다.

`V2__create_learning_sessions.sql`에 B의 세션과 생성 요청 재시도 테이블을 정의했습니다. 회원·자료·개념 FK는 A의 ID 타입과 테이블명이 확정되면 별도 마이그레이션에서 연결합니다. PR 병합 전 A·C의 마이그레이션과 V2 번호 충돌을 확인하세요.

## 확인 방법

DB를 켠 뒤 `backend/`에서 Windows는 `.\gradlew.bat build`, macOS·Linux는 `./gradlew build`를 실행합니다. 테스트에서 A의 개념 조회를 대체하고 실제 PostgreSQL로 생성·회원 격리·동시 요청·명시적 교체·실패 복구를 검사합니다. 새 애플리케이션 인스턴스가 기존 DB의 단계와 개념 사본을 읽는지도 확인합니다.
