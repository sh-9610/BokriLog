<!--
이 파일은 `bmad-build`가 `defer` 판정마다 항목을 추가만 하는 로그다.
스킬 정의가 기존 항목의 수정을 금지하므로 항목 본문을 손으로 고치지 않는다.

`source_spec`은 항목을 기록한 세션의 spec 파일 경로이며 그 기기의 절대경로다
(`none`도 유효한 값이다). 이 저장소는 macOS와 Windows 양쪽에서 개발하므로
아래 `/Users/hwan/...` 경로는 현재 기기에서 열리지 않는다. 저장소 기준 위치는
`_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`다.
경로가 기기에 묶이는 것은 스킬 출력 형식이며 항목 내용의 오류가 아니다.

각 항목의 해소 여부는 이 파일이 아니라 `deferred-work-resolutions.md`에 기록한다.
-->

- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 장기 미접속 시 Bybit Transaction Log 보존기간 밖 자금흐름을 탐지·격리하는 규칙을 계산 SPEC에서 결정한다.
  evidence: 화면 기반 동기화와 API 보존기간 제한이 함께 적용되면 장기 공백 동안의 자금흐름을 놓칠 수 있다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: Product Brief 부록에서 Forge 정본을 정확한 상대경로 링크로 연결한다.
  evidence: 현재 `forged-idea.md` 표기는 부록 위치를 기준으로 실제 정본 파일을 찾을 수 없는 단순 코드 표기다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: Day 60 고정 IP 전환 실패 시 신규 연결 중단과 Day 90 종료 조건을 PRD 운영 규칙으로 명시한다.
  evidence: 브리프는 전환 가능 여부 판단과 Day 90 이후 요청 중단만 적어 중간 운영 분기가 생략되어 있다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 사용자 요청에 따른 계정·계획·스냅샷·이벤트·백업 삭제 범위와 완료 시한을 개인정보 설계에서 결정한다.
  evidence: 현재 브리프는 연결 해제 시 자격정보 삭제만 정하고 다른 사용자 데이터의 삭제 경로는 정하지 않았다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 공개 베타 활성화 완료율의 분모와 측정 기간을 PRD 지표 정의에 추가한다.
  evidence: 테스트 사용자, 연결 시도자, 유효 키 제출자 중 어느 집단을 80% 기준의 분모로 삼는지 현재 브리프만으로 재현할 수 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 동기화 성공률의 측정 단위와 재시도·API 제한·무효 키 제외 규칙을 PRD에서 정의한다.
  evidence: 현재 95% 기준은 어떤 요청과 실패를 분모에 포함하는지 명시하지 않는다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 이해도 80%를 판정할 문항·합격 기준·응답 대상·측정 시점을 PRD에서 정의한다.
  evidence: 현재 브리프는 네 개 개념을 올바르게 해석한다는 결과만 있고 반복 가능한 평가 방법이 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 주 2시간 운영 목표의 안정화 시작 조건과 측정 주수를 PRD에서 정의한다.
  evidence: 현재 `안정화 후`라는 표현만으로는 측정 시작점을 일관되게 판정할 수 없다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 강한 신호별 자동 정지 발동 조건과 60초 측정 시작·완료 시점을 보안 운영 SPEC에서 정의한다.
  evidence: 현재 정책은 자동 정지 목표 시간은 정하지만 신호별 임계치와 측정 경계는 후속 결정으로 남겨 둔다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-bmad-environment-setup.md`
  summary: 계산 사고에서 `last_trusted_only` 표시를 허용할 계산 버전·입력 무결성 조건을 아키텍처에서 정의한다.
  evidence: 잘못된 계산 버전이 만든 과거 값까지 마지막 신뢰값으로 계속 노출될 가능성을 현재 정책이 명시적으로 차단하지 않는다.
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-1-1-entry-screen.md`
  summary: 프런트엔드 시험을 자동 실행하는 CI 워크플로를 저장소에 추가한다.
  evidence: `playwright.config.ts`가 `process.env.CI`로 `forbidOnly`·`retries`·`workers`를 분기하지만 그 변수를 설정하는 주체가 저장소에 없다. SPEC의 Verification 명령 6개가 사람이 직접 입력할 때만 실행되므로 "시험이 이를 고정한다"는 불변식이 자동으로 지켜지지 않는다.
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-1-1-entry-screen.md`
  summary: 린터·포매터(ESLint·stylelint)를 도입해 토큰 참조 규칙과 import 순서를 기계로 강제한다.
  evidence: `DEVELOPMENT.md`가 "색·크기 리터럴을 컴포넌트 CSS에 직접 쓰지 않는다"를 규칙으로 명문화했지만 이를 검사하는 수단이 없어 같은 커밋에서 네 곳이 규칙을 벗어났다. 사람 검토에만 의존하면 스토리가 쌓일수록 표류한다.
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-1-1-entry-screen.md`
  summary: Playwright 시험 대상에 WebKit(모바일 Safari)을 추가할지 결정한다.
  evidence: 제품은 한국어 모바일 우선이고 UX 계약이 iOS Safari에서 동작이 갈리는 `100dvh`·`env(safe-area-inset-*)`·`:focus-visible`에 의존하는데 현재 시험 대상은 Chromium 하나뿐이다. CI 비용과 함께 판단해야 한다.
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-1-1-entry-screen.md`
  summary: CSP를 비롯한 보안 응답 헤더를 Story 1.2의 Nginx 구성에서 정의한다.
  evidence: "문서 밖 네트워크 요청 0건"이 진입 화면의 핵심 불변식인데 현재는 시험으로만 주장하고 브라우저가 강제하지 않는다. meta CSP를 지금 넣으면 Story 1.2가 응답 헤더로 넣을 CSP와 이중 정의가 되므로 동일 출처 셸을 세우는 스토리에서 한 번에 정하는 편이 낫다.
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-1-1-entry-screen.md`
  summary: Story 1.4에서 진입 화면의 `다음 단계` 카피를 실제 소셜 로그인 버튼으로 교체한다.
  evidence: `frontend/src/content/entry.ts`의 "로그인 수단 선택은 아직 준비 중입니다."는 로그인 수단이 생기는 즉시 거짓이 되는 문장인데, 이를 후속 스토리와 묶는 표시가 코드에도 문서에도 없다.
