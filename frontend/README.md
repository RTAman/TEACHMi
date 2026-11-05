# 프론트엔드

웹 클라이언트 코드는 이 디렉터리에 둡니다. [Figma 디자인](https://www.figma.com/design/dZTY1TqL3ceEXh8QOU3TD7/TEACHMi?node-id=78-2345)에 맞춰 가입·로그인, 자료 업로드, 개념 선택, AI 대화, 문제 풀이, 리포트를 연결합니다.

첫 화면을 붙일 때에는 [백엔드 담당표](../backend/)에서 해당 담당자를 확인합니다. 로그인·업로드 상태는 A, 학습 재개와 메시지는 B, 홈·채점·리포트는 C와 요청·응답 예시를 맞춥니다. 로딩·빈 결과·오류 화면은 해당 작업 이슈에 Figma 노드와 함께 기록합니다.

B 연결은 [HTTP 명세·실행 예시·인계 안내](../backend/API_HANDOFF.md)와 [OpenAPI JSON](../backend/api/openapi.json)을 사용합니다. 실제 A 인증과 자료/원문, C 문항·평가 어댑터는 연결 대기 상태이므로 예시의 성공 조건을 먼저 확인하세요.
