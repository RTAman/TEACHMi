# 프론트엔드

웹 클라이언트 코드는 이 디렉터리에 둡니다. [Figma 디자인](https://www.figma.com/design/dZTY1TqL3ceEXh8QOU3TD7/TEACHMi?node-id=78-2345)에 맞춰 가입·로그인, 자료 업로드, 개념 선택, AI 대화, 문제 풀이, 리포트를 연결합니다.

첫 화면을 붙일 때에는 [백엔드 담당표](../backend/)에서 해당 담당자를 확인합니다. 로그인·업로드 상태는 A, 학습 재개와 메시지는 B, 홈·채점·리포트는 C와 요청·응답 예시를 맞춥니다. 로딩·빈 결과·오류 화면은 해당 작업 이슈에 Figma 노드와 함께 기록합니다.

B 연결은 [HTTP 명세·실행 예시·인계 안내](../backend/API_HANDOFF.md)와 [OpenAPI JSON](../backend/api/openapi.json)을 사용합니다. A의 로그인 쿠키·CSRF와 직접 입력 자료·원문은 연결됐으며 파일 업로드/분석과 C 문항·평가 어댑터는 후속 작업입니다.

## B AI 복구 화면 규칙

클라이언트 구현 시 [로딩과 확인 대기 구분](../backend/CONVERSATION_TURNS.md#로딩과-확인-대기-구분)을 적용합니다. availableActions=wait은 로딩 여부가 아닙니다. 오류/GET의 recovery.action과 pendingTurn·activeReteach.errorCode를 먼저 검사합니다.

```js
function recoveryUi(body) {
  const session = body.currentSession ?? body;
  const recovery = body.recovery ?? session.recovery;
  const codes = [body.code, session.pendingTurn?.errorCode, session.activeReteach?.errorCode];
  if (codes.includes('AI_GENERATION_UNCERTAIN') || recovery?.action === 'review_required')
    return { screen: 'review_required', spinner: false, autoPoll: false, autoRetry: false };
  if (codes.includes('AI_ATTEMPT_LIMIT') || recovery?.action === 'limit_reached')
    return { screen: 'limit_reached', spinner: false, autoPoll: false, autoRetry: false };
  const job = session.pendingTurn ?? (session.activeReteach?.state !== 'chat' ? session.activeReteach : null);
  if (job?.state === 'generating' && session.availableActions?.includes('wait'))
    return { screen: 'processing', spinner: true, maxGetPolls: 30, pollIntervalMs: 2000, autoRetry: false };
  return { screen: 'saved_or_interrupted', spinner: false, autoPoll: false, autoRetry: false };
}
```

처리 중 폴링도 60초/30회 이후 종료하고 지연 안내로 바꿉니다. 터미널 상태에서는 타이머를 즉시 해제하고 입력한 답변과 문의 안내를 표시합니다. 기다리면 초기화된다는 안내, 임의의 새 키/모델/세션으로 재전송, 자동 POST는 하지 않습니다. recovery가 없는 구버전 응답도 위 오류 코드 분기를 적용합니다. 현재 디렉터리에는 실제 웹 클라이언트가 없으므로 이 코드는 연동 참고이며, UI 구현 시 타이머 해제·재접속·터미널 전환을 함께 검증해야 합니다.
