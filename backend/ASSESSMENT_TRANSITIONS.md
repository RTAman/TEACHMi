# C의 평가·리포트 저장과 B 상태 전환

C가 평가·리포트 DB 저장과 B의 상태 변경을 하나의 트랜잭션으로 처리하는 내부 서비스입니다. 사용자용 상태 강제 변경 HTTP API는 추가하지 않습니다. 실제 채점·리포트 생성·학습 종료 기준의 구현은 C가 담당합니다.

## 연결 대기와 완료 기준

현재 저장소에는 운영용 C 평가·리포트 구현이 없습니다. C가 AssessmentStateSource Spring bean을 구현하기 전 새 전환은 503 ASSESSMENT_INTEGRATION_PENDING으로 거절하고 C 저장 콜백도 실행하지 않습니다. 테스트용 리포트 테이블과 어댑터는 src/test에만 있습니다.

전환은 두 동작으로 구분합니다.

| 내부 동작 | 전환 | C가 확인할 내용 |
| --- | --- | --- |
| publishReport | active/problem → active/report | 최신 평가 완료, 실제 리포트 저장·준비, 현재 학습 근거 버전 |
| completeLearning | active/report → completed/report | 같은 리포트·개정·근거 유지, C가 명시한 학습 종료 기준 충족 |

리포트가 준비됐다는 사실만으로 학습을 종료하지 않습니다. 리포트 확인 여부 등 실제 종료 조건은 C와 확정한 뒤 C 어댑터가 completionAllowed로 판정해야 합니다. B는 임의의 점수나 확인 이력을 만들어 승인하지 않습니다. 준비 단계는 계속 `/sessions/active`에서 찾을 수 있으며, 종료 후에는 제외됩니다. 완료된 세션의 기존 GET·근거 조회와 원래 설명·도움·재설명 이력은 유지합니다.

## C의 호출 순서

1. 인증된 회원 ID와 B 근거 조회의 version을 사용해 C의 채점·리포트 결과를 준비합니다. 모델 호출과 계산은 상태 전환 트랜잭션 전에 끝냅니다.
2. C 서버가 아래 내부 서비스를 호출합니다. B는 회원 잠금과 세션 잠금을 먼저 취득하고 소유권·버전·단계를 검사합니다.
3. 검사 통과 후 전달한 Persistence 콜백이 C의 결과를 DB에 저장합니다. 콜백에는 잠근 상태의 SessionView가 전달됩니다.
4. B가 AssessmentStateSource로 방금 저장한 C 기록을 확인합니다. 통과하면 B 단계·버전·C 리포트 연결·성공 응답 이력을 저장합니다.
5. 콜백·검증·후속 C 저장·커밋 중 실패하면 같은 트랜잭션의 C 기록과 B 변경·성공 이력을 함께 롤백합니다.

```java
// C 서비스의 예시입니다. reportStore와 completionStore는 C가 실제 구현해야 합니다.
SessionView ready = learningAssessmentService.publishReport(
    currentMemberId, sessionId, reportRequestKey,
    new AssessmentTransitionRequest(reportId, evidenceVersion),
    locked -> reportStore.savePreparedResult(preparedReport, locked.version()));

// 리포트 준비 후, 별도로 C가 정한 종료 기준을 충족했을 때 호출합니다.
SessionView completed = learningAssessmentService.completeLearning(
    currentMemberId, sessionId, completionRequestKey,
    new AssessmentTransitionRequest(reportId, currentSessionVersion),
    locked -> completionStore.saveApproval(sessionId, reportId));
```

서비스는 Spring의 REQUIRED 쓰기 트랜잭션을 사용합니다. 호출자가 트랜잭션을 열지 않으면 B가 시작하고, C가 이미 트랜잭션을 열었다면 같은 트랜잭션에 참여합니다. C 저장은 반드시 **같은 DataSource·트랜잭션 관리자·스레드**를 사용해야 합니다. 콜백이나 어댑터에서 REQUIRES_NEW·비동기 저장·별도 DB·외부 HTTP 저장을 사용하면 원자성을 보장할 수 없습니다. 콜백은 DB 저장만 수행하고 실패를 전파하세요. C는 이 서비스 전에 B/C 상태를 잠그거나 저장하지 말고, B의 회원 잠금부터 시작하는 순서를 유지해야 합니다.

성공한 요청 재전송과 사전 검사 실패는 C 저장 콜백을 실행하지 않습니다. 콜백 안에서 B의 새 학습 생성 등 다른 B 변경을 실행하면 재검증으로 거절하며 전체 롤백합니다. 읽기 전용 트랜잭션에서는 전환을 거절합니다.

## 요청·중복 규칙

AssessmentTransitionRequest는 reportId와 expectedVersion을 받습니다. reportId는 영문·숫자로 시작하는 최대 128자의 영문·숫자·하이픈·밑줄 문자열, expectedVersion은 필수 0 이상의 버전입니다. memberId는 서버 인증에서 얻고 현재 회원의 세션 소유권을 검사합니다.

- B의 키는 기존과 같이 회원별 1~128자이며 모든 B 변경 작업과 같은 키 공간을 공유합니다.
- B는 동작·세션·reportId·expectedVersion을 요청 지문으로 저장합니다. 같은 키·본문은 당시 성공한 SessionView를 재사용하며, 이후 상태가 바뀌어도 다시 C 저장을 실행하지 않습니다.
- 다른 동작·본문의 키 재사용은 409입니다. 완료 후 새 키로 같은 전환을 다시 실행하는 것도 단계 검사로 거절합니다.
- **콜백 안의 C 고유 저장 본문은 B 지문에 포함되지 않습니다.** C의 외부 API는 그 본문까지 포함하는 요청 지문·멱등성을 별도로 검증해야 합니다. 같은 B 키로 다른 C 저장 본문을 보내지 마세요.
- 실패한 트랜잭션은 B 성공 이력도 남기지 않으므로 같은 키·원래 본문으로 재시도할 수 있습니다. 성공 응답 재전송은 과거 스냅샷이며 최신 상태는 GET으로 확인합니다.

## AssessmentStateSource 계약

```java
State loadOwnedReport(String memberId, UUID sessionId, String reportId);

record State(String memberId, UUID sessionId, String reportId,
             long revision, long evidenceVersion,
             boolean assessmentComplete, boolean reportReady, boolean completionAllowed) {}
```

C 어댑터는 같은 DB 트랜잭션의 실제 저장 기록에서 다음을 검증해 반환합니다.

- 회원이 평가·답안·리포트를 소유하며, 리포트가 이 학습에 속합니다. 없거나 접근할 수 없으면 null 또는 LearningException.notFound()입니다.
- 해당 학습의 최신 필요한 평가가 실제 완료됐고, 미채점·미제출·재시도 대기와 진행 중 작업이 없습니다. assessmentComplete와 reportReady를 임의로 true로 반환하지 마세요.
- evidenceVersion은 리포트가 실제 사용한 B 근거의 version입니다. publishReport에서는 잠긴 현재 세션 버전과 일치해야 합니다.
- revision은 0 이상의 **리포트 내용·근거 개정 번호**입니다. 단순 확인·종료 승인으로 이 번호를 바꾸지 않습니다.
- completionAllowed는 C가 확정한 종료 정책을 실제 기록으로 확인한 결과입니다.

B는 reportId·revision·evidenceVersion을 저장하고 종료 때 동일한 연결인지 다시 검사합니다. 리포트 준비 자체가 B 버전을 한 번 갱신하므로, 종료 요청의 expectedVersion은 준비 후 최신 B 버전을 사용하되 C의 evidenceVersion은 리포트가 사용한 원래 버전을 유지합니다. 준비 후 내용을 바꾼 리포트나 다른 리포트로의 종료는 거절합니다. 이 서비스에는 리포트 재발행·이전 단계 복귀 동작이 없습니다. 수정이 필요한 경우 C와 별도 계약을 정하세요.

## B의 상태 검사

새 요청은 active 상태, 정확한 expectedVersion과 해당 출발 단계가 필요합니다. pendingTurn·activeReteach·아직 소비하지 않은 problemReturn이 있으면 거절합니다. 같은 문항 복귀 표시는 기존 acknowledgeProblemReturn으로 처리하고, 이후 실제 평가가 완료됐는지 C가 확인해야 합니다. 최초 학습의 저장된 최종 설명은 해당 세션의 user/answer/wrapup 메시지여야 하며 재설명 메시지로 대신하지 않습니다.

완료되면 stage=report, status=completed, resume=null, availableActions=[]입니다. GET /sessions/active는 다른 진행 중 학습이 없다면 204를 반환하며 새 학습을 시작할 수 있습니다. 기존 학습을 새 학습으로 이미 교체했다면 늦게 도착한 평가 전환은 409입니다.

## 오류와 호환성

아래는 내부 LearningException 코드입니다. C의 컨트롤러는 C API의 오류 규격에 맞춰 매핑해야 합니다. B 컨트롤러의 Advice를 C에 자동 적용하지는 않습니다. 내부 메서드 입력 제약 위반은 ConstraintViolationException입니다.

| 상태·코드 | 의미 |
| --- | --- |
| 404 NOT_FOUND | 없는 세션·리포트 또는 소유권/연결 불일치 |
| 409 SESSION_STATE_CONFLICT | 단계·버전·종료·진행 중 작업 충돌 |
| 409 IDEMPOTENCY_KEY_REUSED | 기존 B 키에 다른 요청 |
| 409 FINAL_EXPLANATION_REQUIRED | 최초 학습의 최종 설명 근거 없음 |
| 409 ASSESSMENT_NOT_READY | 평가·리포트 저장 미완료 |
| 409 ASSESSMENT_EVIDENCE_STALE | 오래된 B 근거로 만든 리포트 |
| 409 ASSESSMENT_REPORT_CHANGED | 준비된 리포트·개정·근거 변경 |
| 409 LEARNING_COMPLETION_NOT_APPROVED | C의 종료 승인 없음 |
| 409 REPORT_HANDOFF_REQUIRED | 검증된 리포트 준비 연결 없음 |
| 409 WRITABLE_TRANSACTION_REQUIRED | 읽기 전용 트랜잭션에서 쓰기 호출 |
| 422 INVALID_IDEMPOTENCY_KEY | 키 형식 오류 |
| 502 INVALID_ASSESSMENT_STATE | C가 반환한 개정·근거 버전 오류 |
| 503 ASSESSMENT_INTEGRATION_PENDING | 운영용 C 어댑터 미연결 |

V7은 learning_session에 nullable assessment_report_snapshot만 추가합니다. C가 검증한 ID·개정·근거 버전만 저장하며 리포트 본문·점수는 C가 저장합니다. V1~V6, 기존 SessionView·HTTP API와 요청 이력 형식은 유지합니다. 과거 report 단계에 검증된 연결이 없는 기록은 임의로 채우거나 완료시키지 않고 REPORT_HANDOFF_REQUIRED를 반환합니다. 새 환경 변수와 Gemini 호출은 없습니다.

## 검증

실제 PostgreSQL의 테스트 전용 C 테이블로 성공 전환, 최초 학습 근거 보존, /active 제외, 명시적 종료 승인, 실패·바깥 트랜잭션 롤백·같은 키 재시도, 잘못된 소유권·근거·개정, 동시 중복·새 학습 교체, 재설명 복귀 대기, 재기동 복원과 미연결 상태를 검증합니다. 운영용 A·C 어댑터를 연결한 실제 자료·평가 전체 검증은 별도 연동 작업입니다.
