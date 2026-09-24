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
- source_spec: `C:\Users\SSAFY\Desktop\2학기\SideProject\BokriLog\_bmad-output\implementation-artifacts\spec-ci-foundation.md`
  summary: `_bmad/**`·`.agents/skills/**`·`.claude/skills/**`·`.codex/**`·`scripts/check-bmad-env.sh`·`uv.toml` 변경에만 도는 `bmad-contract` 검사를 별도 작업으로 구현한다.
  evidence: CI-PLAN §14가 이 여섯 경로를 BMAD 도구 계약의 검사 대상으로 정했지만 첫 CI(`.github/workflows/ci.yml`의 `ci-required`)는 이를 구현하지 않는다. `scripts/check-bmad-env.sh`가 러너에 `uv`와 관리 Python 다운로드를 요구해 frontend 필수 경로의 시간 예산·신뢰 경계와 달라지므로 이번 범위에서 분리했다. 그 결과 지금은 이 경로만 바뀐 PR도 fail-closed 분류에 걸려 전체 frontend 검사만 돌고, BMAD 계약 자체는 어떤 게이트도 검증하지 않는다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-frontend-lint-policy.md`
  summary: `eslint-plugin-jsx-a11y`가 ESLint 10 peer를 지원하면 별도 의존 갱신 PR(CI-PLAN §11)로 JSX 정적 접근성 lint를 `eslint.config.ts`에 추가한다.
  evidence: 2026-09-05 기준 `eslint-plugin-jsx-a11y` 최신판의 peerDependencies는 `eslint: ^3 || ^4 || ^5 || ^6 || ^7 || ^8 || ^9`로 ESLint 10을 포함하지 않아 이번 lint 정책 범위에서 제외했다. 렌더 결과의 접근성은 E2E axe 검사(`frontend/e2e/entry-screen.spec.ts`)가 담당하지만, `alt` 누락이나 잘못된 `role`처럼 렌더 전에 잡을 수 있는 JSX 수준 위반은 사람 리뷰에만 의존한다.
- source_spec: `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog/_bmad-output/implementation-artifacts/spec-frontend-lint-policy.md`
  summary: index.html noscript 인라인 스타일의 `var(--…, 폴백)` 폴백 값이 tokens.css와 표류하지 않는지 확인하는 시험을 둔다.
  evidence: 폴백 리터럴(`#17211b`, `24px`, `16px`, `28px`, `1.6`, `1.3`)은 stylelint 대상 밖이고 어떤 시험도 tokens.css의 값과 비교하지 않는다. JS 비활성 화면의 색·크기가 토큰 변경을 따라가지 못해도 CI가 알지 못한다.
- source_spec: `{project-root}/_bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md`
  summary: 통합 입력 artifact 이름에 `github.run_attempt`를 넣은 결과 GitHub의 `Re-run failed jobs` 복구 경로가 막히는 문제를 결정한다.
  evidence: `same-origin-integration`만 실패해 `Re-run failed jobs`를 누르면 이미 성공한 `frontend-quality`·`backend-quality`는 다시 실행되지 않아 새 attempt 번호의 artifact를 만들지 않는다. 그런데 통합 job은 `frontend-dist-<run_id>-<run_attempt>`를 찾으므로 존재하지 않는 이름을 내려받으려다 즉시 실패한다. 즉 그 복구 경로가 영구히 막히고 `Re-run all jobs`로 전체를 다시 도는 수밖에 없다. CI-PLAN §8이 "이름에는 workflow run ID, run attempt, PR 번호와 `merge_sha`를 포함하고 overwrite를 금지한다"를 명시적으로 요구하므로 이름을 조용히 바꿔 고칠 수 없다. 취소·대체 실행의 artifact 재사용 금지와 재실행 복구 가능성 중 무엇을 우선할지 문서화된 결정이 필요하다.
- source_spec: `{project-root}/_bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md`
  summary: `same-origin-integration`이 되풀이하는 `npm ci`와 `playwright install chromium --with-deps`를 없앤다.
  evidence: 두 단계 모두 `frontend-quality`가 같은 commit·같은 lockfile로 이미 수행한 일이다. 이 스토리가 세운 "통합 job은 재빌드 없이 산출물을 소비한다"(CI-PLAN §8) 원칙이 `dist`와 jar에만 적용되고 실행 환경에는 적용되지 않는 상태다. 중복 설치가 job 예산을 4분가량 밀어 올려 `same-origin-integration`의 timeout을 16분으로 잡게 만든 원인 중 하나이며, CI-PLAN §10의 시간 예산 조정 순서도 "중복 build·설치 제거"를 첫 번째로 둔다. `node_modules`와 브라우저를 artifact로 넘길지, 통합 job을 준비된 컨테이너에서 돌릴지 등의 선택지를 함께 검토해야 한다.
- source_spec: `{project-root}/_bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md`
  summary: 백엔드의 포매터·정적 분석·아키텍처 규칙 게이트를 도입할 스토리를 정한다.
  evidence: 프런트엔드는 typecheck·ESLint·stylelint·Prettier 네 게이트를 필수로 통과해야 하는데 백엔드의 필수 게이트는 `gradle build` 하나뿐이다. `-Werror`는 컴파일 경고만 보고 서식·명명·금지 API·계층 의존은 아무것도 검사하지 않는다. Spotless·Checkstyle·PMD·ArchUnit 중 무엇을 언제 넣을지 소유한 스토리가 없다(Story 1.3은 Modulith 경계 검사만 더한다). Java 코드가 늘어난 뒤 포매터를 도입하면 전체 재포맷 커밋 하나가 생겨 그 시점 이전의 blame이 끊기므로, 코드가 적은 지금이 가장 싼 시점이다.
- source_spec: `{project-root}/_bmad-output/implementation-artifacts/spec-1-2-same-origin-shell.md`
  summary: production 컨테이너 권한 강화(`user`·`read_only`·`cap_drop`·`security_opt`·자원 제한)를 소유할 스토리를 정한다.
  evidence: `deploy/compose/compose.yaml`의 backend 서비스는 이미지가 만든 비-root 사용자로 내려가지만 nginx 서비스에는 `user`·`read_only`·`cap_drop`·`security_opt`·자원 제한이 하나도 없다. 파일 주석은 이를 Story 3.8이 소유한다고 적었으나 `epics.md` Story 3.8의 AC는 digest·서명·SBOM·메모리 한도·blue/green 순서만 다루고 컨테이너 권한은 언급하지 않는다. 즉 현재 어떤 스토리도 이 항목을 갖고 있지 않다. 개발·CI 전용 프로필이라 지금 당장 위험하지는 않지만, production 프로필이 이 파일을 출발점으로 삼으면 그 차이가 그대로 넘어간다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md`
  summary: `identity`의 행 잠금 대기 상한을 정하고, HTTP 요청 타임아웃이 생기는 스토리와 함께 값을 결정한다.
  evidence: `IdentityAccountRepository.lockLifecycleForUpdate`의 `SELECT … FOR UPDATE`에 `NOWAIT`도 `lock_timeout`도 없고, 서비스 메서드의 `@Transactional`에도 timeout이 없다. 매핑 연결 트랜잭션이 어떤 이유로든 멈추면 같은 계정의 삭제 전환은 상한 없이 기다린다. 지금은 identity에 HTTP 표면이 없어 사용자가 그 대기를 볼 경로가 없지만, Story 1.4가 OIDC 콜백을 열면 그 대기가 곧 사용자 요청의 대기가 된다. 값(몇 초로 끊을지, 끊었을 때 어떤 거부로 답할지)은 요청 타임아웃과 한 쌍으로 정해야 하므로 그 스토리와 함께 결정한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md`
  summary: `(issuer, subject)`로 기존 계정을 찾는 조회 API와, 계정 생성·매핑 연결을 한 단위로 묶는 경로를 Story 1.4에서 정한다.
  evidence: 현재 `AccountLifecycleApi`에는 `createAccount`·`linkSocialIdentity`·`decideSessionCreation`만 있어 "이미 가입한 사용자가 다시 로그인했다"를 판별할 수단이 없다. Story 1.4는 그 판별 없이는 콜백을 완성할 수 없고, 지금 모양대로 `createAccount` 뒤에 `linkSocialIdentity`를 부르면 두 호출이 각각 자기 트랜잭션을 열어 연결이 거부됐을 때 매핑 없는 ACTIVE 계정이 영구히 남는다. 조회 API의 모양(거부 계약을 깨지 않으면서 무엇을 돌려줄 것인가)과 두 연산의 트랜잭션 경계를 함께 정해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md`
  summary: `PostgresTestcontainersConfig`의 공유 컨테이너 수명을 Spring 컨텍스트 캐시와 분리한다.
  evidence: 컨테이너는 `static` 필드이지만 `@Bean`으로 등록돼 있어 Spring이 컨텍스트를 닫을 때 그 인스턴스의 `stop()`을 부를 수 있다. 컨텍스트 캐시가 가득 차 축출이 일어나거나 어떤 시험이 `@DirtiesContext`를 붙이면, 뒤에 오는 시험이 이미 중지된 컨테이너를 물려받는다. 지금은 컨텍스트가 넷뿐이라 축출이 일어나지 않아 드러나지 않는다. 시험이 늘면 재현하기 어려운 실패로 나타나므로, JVM 수명에 묶는 방식(예: `@ServiceConnection` 유지 + 수동 시작과 shutdown hook, 또는 컨테이너 재사용 설정)을 정해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md`
  summary: `verify-same-origin.sh`의 마이그레이션 호출에 이식 가능한 시간 상한을 넣는다.
  evidence: 스크립트의 다른 대기는 모두 `--wait-timeout 180`·`--max-time 15`로 묶여 있는데 `compose --profile migrate build|run`만 상한이 없다. 이 경로에서 실제로 hang을 겪었고, 그때 CI라면 job timeout 15분을 통째로 태운 뒤 원인 없는 실패로 끝난다. macOS의 bash 3.2에는 GNU `timeout`이 없어 `timeout 300 …`을 그대로 쓸 수 없으므로, 백그라운드 실행 + 감시 루프처럼 이식 가능한 구현을 별도로 설계해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md`
  summary: `identity`가 `spring_session`에 직접 SQL을 던지는 결합을 푸는 방법을 정한다.
  evidence: `IdentityAccountRepository.deleteSessionsOf`가 `DELETE FROM spring_session WHERE principal_name = :principalName`을 실행한다. 그 테이블은 `identity`가 소유하지 않고 Spring Session JDBC가 만들고 갱신하며, 술어가 성립하는 근거는 "`principal_name`이 내부 `user_id` 문자열이다"라는 루트 패키지의 결정(`AuthFlowHandlers`가 인증 주체를 그 값 하나로 바꿔 저장한다)뿐이다. 즉 모듈이 남의 테이블과 남의 규약에 동시에 결합돼 있고, 어느 한쪽이 바뀌면 이 DELETE는 예외 없이 0행이 되어 삭제가 조용히 세션을 남긴다. 지금 그 한 쌍을 붙잡는 것은 `AuthDeletionPathTest` 하나이며 경계 검사는 이 결합을 보지 못한다(`ModuleBoundaryTest`는 패키지 의존만 본다). 세션 폐기를 `identity` 공개 API가 요구하고 루트 패키지가 수행하는 이벤트·포트로 뒤집을지, `principal_name` 규약을 두 곳이 공유하는 상수로 승격할지, 아니면 세션 소유를 통째로 루트 패키지로 옮길지를 Story 1.5(로그아웃·세션 만료·의심 세션 차단)가 세션 수명을 손댈 때 함께 정한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md`
  summary: `OnboardingScreen`과 `SignedInScreen`의 중복을 세션 게이트 셸 하나로 합칠지 정한다.
  evidence: 두 화면은 약 280줄 중 카피 참조(`authContent.onboarding` 대 `authContent.dashboard`)와 본문 랜드마크 id만 다르고 구조가 같다. `.module.css`는 141줄 중 첫 주석 한 줄만 다르다(`diff`가 그 한 줄만 낸다). 세션 게이트·건너뛰기 링크·live region·고지 인접 배치·목적지 불일치 처리가 두 곳에 각각 있어, 이 라운드에서 넷을 고칠 때 같은 수정을 두 번 했다. Epic 2가 대시보드에 지표를 넣으면 두 화면은 실제로 갈라지므로 지금 합치는 것이 이르다고 볼 수도 있다. 공통 셸(제목·고지·live region·게이트)과 화면별 본문 슬롯으로 나눌지, CSS만 공유 모듈로 뽑을지, 아니면 갈라질 때까지 중복을 유지할지를 결정한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md`
  summary: 백엔드 import 정렬 관용을 정하고 그것을 강제할 게이트를 미룬 항목 21(백엔드 포매터·정적 분석)과 함께 결정한다.
  evidence: 이 스토리가 더한 파일들의 import 순서가 기존 관용과 어긋난다. `AuthSecurityConfig.java`는 `org.springframework.security.core.Authentication`이 `org.springframework.security.config...`보다 앞에 있어 알파벳 순서가 아니고, `DevOidcStubTest.java`는 `org.jspecify`가 `org.junit`보다 앞이라 정렬 기준이 파일마다 다르다. 프런트엔드는 `perfectionist/sort-imports`가 이것을 기계로 강제하고 `--fix`가 고치는데, 백엔드의 필수 게이트는 `gradle build` 하나이고 `-Werror`는 컴파일 경고만 본다. 사람 리뷰가 매 PR에서 import 순서를 세는 것은 지속되지 않으므로, 관용(알파벳 단일 블록인지 그룹 분리인지)과 그것을 강제할 도구(Spotless의 `importOrder`·Checkstyle의 `CustomImportOrder`)를 미룬 항목 21의 결정에 포함한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-lint-policy-path-separator.md`
  summary: `lint-policy.test.ts`의 `lint:css` 범위 단정을 포함 검사에서 집합 동등 비교로 바꿀지 정한다.
  evidence: 단정이 `expect.arrayContaining`으로 세 파일만 확인하는데 그 glob이 실제로 고르는 CSS는 다섯 개다(Story 1.4가 `OnboardingScreen.module.css`·`SignedInScreen.module.css`를 더했고 둘은 아무 단정도 받지 않는다). `spec-frontend-lint-policy.md`가 이 시험의 목적을 "glob·ignore가 바뀌어 검사 대상이 조용히 줄어드는 것을 막는다"로 적었는데, 지금 형태는 새 CSS 모듈이 glob에서 빠져도 통과한다. 디스크에서 `src/**/*.css`를 독립적으로 수집해 집합으로 비교해야 그 의도가 지켜지며, 그때 `spec-frontend-lint-policy.md`가 못박은 시험 개수와의 정합도 함께 처리해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-lint-policy-path-separator.md`
  summary: `lint-policy.test.ts`에 남은 경로 조립의 플랫폼 가정과 범위 단정의 부정 방향 공백을 정리한다.
  evidence: `prettierConforms`·`prettierIgnores`와 형제 시험이 여전히 `` `${cwd}/${filePath}` `` 문자열 결합으로 입력을 만들어 Windows에서 `C:\…\frontend/src/…` 혼합 구분자가 된다. 지금 통과하는 것은 ESLint·Prettier의 내부 정규화 덕이고 이 브랜치가 없앤 가정과 같은 종류다. 또 `명령 층의 선택 범위` describe가 포함만 보고 "`lint:css`가 `src/` 밖이나 CSS 아닌 파일을 고르지 않는다", "`eslint .`이 `globalIgnores`를 실제로 무시한다", "두 명령의 `--max-warnings 0`이 남아 있다"를 단정하지 않는다. glob 추출 정규식 `/"([^"]+)"/`도 "첫 인용 토큰이 glob"이라는 가정에 의존한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-lint-policy-path-separator.md`
  summary: Windows 전용 결함을 CI가 볼 수 있게 할지, 그렇다면 어느 스토리가 그 비용을 소유할지 정한다.
  evidence: `ci.yml`의 다섯 job이 모두 `ubuntu-24.04`라 이번 결함처럼 Windows에서만 드러나는 실패를 CI가 구조적으로 감지하지 못한다. 이 저장소는 `DEVELOPMENT.md` `## 두 기기 개발`이 명시하듯 Windows와 macOS 양쪽에서 개발하므로 그 공백이 실제 비용으로 돌아온다. matrix leg 추가는 job 수·`runs-on` 개수·artifact 이름을 고정한 `test-ci-scripts.sh`의 단정 다섯 곳을 함께 바꿔야 하고 CI 시간도 늘어난다. 전체 matrix가 아니라 프런트엔드 단위 시험만 Windows에서 한 번 더 돌리는 축소안도 선택지다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-5-logout-session-expiry.md`
  summary: 계층을 넘나드는 계약 리터럴(`X-CSRF-TOKEN` 헤더 이름, 자산 `Cache-Control` 값)의 삼중 중복을 Docker 없이도 도는 검사로 묶을지 정한다.
  evidence: 이 라운드가 두 값을 각각 세 곳에 적었다. `X-CSRF-TOKEN`은 `AuthSecurityConfig.CSRF_HEADER_NAME`·`frontend/src/content/auth.ts`의 `CSRF_HEADER`·`verify-same-origin.sh`의 리터럴에 있고, `public, max-age=31536000, immutable`은 `bokrilog.conf`·`verify-same-origin.sh`의 `expected_asset_cache_control`·`frontend/e2e/auth-entry.spec.ts`에 있다. 세 벌이 어긋나면 로그아웃이 조용히 403이 되거나 캐시 계약이 갈리는데, 그것을 잡는 유일한 검사가 Docker를 요구하는 compose 스크립트다. 같은 문제가 오류 code 문자열에도 이미 있고 `ApiErrorHandler.java:43-45`가 그 중복을 주석으로 경고한다. 프런트엔드의 `lint-policy.test.ts`처럼 저장소 파일을 읽어 세 벌의 동등성을 단정하는 Docker 없는 시험을 둘지, 아니면 계약 값을 생성 산출물로 만들지 정한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-5-logout-session-expiry.md`
  summary: 두 인증 화면의 공통 레이아웃 CSS가 또 갈라져 복사됐다 — 셸 추출 결정을 더 미룰지 재확인한다.
  evidence: 이 라운드가 동일한 5속성 flex `.header` 규칙과 그 주석을 `OnboardingScreen.module.css`와 `SignedInScreen.module.css` 양쪽에 같은 내용으로 더했다. 로그아웃 버튼을 두 화면이 공유하는 컴포넌트로 뽑아 중복을 늘리지 않으려 했는데, 그 버튼을 앉힐 헤더 레이아웃은 결국 두 벌이 됐다. Story 1.4가 남긴 같은 취지의 항목(세션 게이트 셸 통합)이 아직 열려 있고, 이번 라운드가 그 비용을 한 번 더 실측했다는 사실을 근거로 그 결정의 우선순위를 다시 본다.
- source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-ledger-sync.md`
  summary: 미룬 항목의 판정을 원장에 쓰는 단계를 스킬이나 표준 흐름에 넣을지 정한다.
  evidence: 이번 라운드가 12건의 판정 누락을 고쳤지만 그 누락을 만든 구조는 그대로다. `bmad-build` step-04는 `defer`마다 `deferred-work.md`에 항목을 추가하도록 강제하는 반면 그 항목의 판정을 `deferred-work-resolutions.md`에 쓰는 단계는 어느 스킬에도 없어서, 원장은 사람이 따로 챙길 때만 갱신되고 네 라운드 연속으로 챙기지 못했다. 처방으로 넣은 것은 원장 자신의 `## 갱신 규칙` 한 줄뿐이며 `bmad-build`도 `CLAUDE.md`의 표준 흐름 5(present)·7(done 전환)도 바뀌지 않았다. 즉 강제 수단이 없는 규칙이다. `CLAUDE.md`가 스킬 파일 손수정을 금지하고 동작 변경은 `_bmad/custom/` 또는 `bmad-customize`가 소유한다고 못박았으므로, 그 경로로 present 단계에 판정 확인을 넣을지 아니면 표준 흐름 문서에만 적을지를 소유자와 함께 정해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-ledger-sync.md`
  summary: 해소 원장의 `파일:줄번호`·커밋 인용이 실제로 해석되는지 확인하는 Docker 없는 검사를 둘지 정한다.
  evidence: 원장은 판정마다 검증 가능한 근거를 지목하는 것을 규율로 삼지만 그 근거를 보는 검사가 없다. 이번 라운드의 SPEC `## Verification` 다섯 명령은 전부 개수·형태만 보고, 실제로 첫 구현이 항목 15에서 Story 1.4가 건드리지 않은 줄(`entry.ts:50`)을 "바뀌었다"고 적었는데 그 명령 전부를 통과했다. 리뷰 서브에이전트가 32개 인용을 훑어 잡아냈을 뿐이며 저장소 쪽 장치는 없다. 문서-only 경로는 `changes → ci-required`만 돌지만 `changes` job을 지나므로 붙일 자리는 있다. 기계로 닫히는 축은 셋이다 — 인용 경로의 존재와 줄 범위, 인용 커밋·run id의 해석(`git cat-file -e`), 원장 `### <숫자>.` 절 집합과 `deferred-work.md` `- source_spec:` 개수·순서의 동등. 인용이 그 사실을 실제로 뒷받침하는지는 기계로 닫히지 않으므로 사람 확인으로 남는다. 항목 33이 요구하는 계약 리터럴 계수 검사와 같은 성격이라 함께 설계하는 편이 싸다.
- source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-ledger-sync.md`
  summary: 해소 원장에 항목별 소유자·마지막 확인일을 담는 표를 둘지 정한다.
  evidence: 판정이 두 스프린트 밀린 직접 원인은 주인 없는 항목이 보이지 않는 것이었는데, 이번 라운드는 그 가시성을 산문 한 문단으로만 고쳤다. `## 상태 요약` 표는 여전히 상태·건수 3행이라 미해소 18건 중 열이 주인이 없다는 사실을 보려면 그 문단을 읽어야 하고, 그 문단 자체가 손으로 유지되는 열거라 다음 라운드에 다시 낡는다. frontmatter에도 `updated:` 하나뿐이어서 항목별 `last_checked`도 다음 확인 시점(에픽 회고·스프린트 계획)도 없다. 번호·제목·판정·결정 지점·마지막 확인일 열을 가진 표를 두면 34개 절을 읽지 않고 주인 없는 항목과 오래 확인되지 않은 항목을 볼 수 있지만, 표와 절이 어긋날 새 드리프트 축이 생긴다. 그 교환을 정한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: 제공자 이름 목록이 계층·언어를 넘나들며 열 곳 넘게 복제됐고 그 목록들이 서로 같은지 보는 검사가 없다.
  evidence: 이 라운드가 같은 집합을 `AuthSecurityConfig.SUPPORTED_REGISTRATION_IDS`, `DevOidcStubConfig.PROVIDERS`, `OidcStubConfiguration`의 인라인 목록 둘, `DevOidcStubProfileTest.PROVIDERS`, `StatusApiTest`의 인라인 배열, `verify-same-origin.sh`의 heredoc 셋과 `case` 분기와 하드코딩된 기대값 `'2'` 셋, `frontend/src/content/auth.ts`의 `SIGN_IN_PROVIDERS`에 나눠 놓았다. Java 안의 다섯 곳은 상수 하나로 접을 수 있지만 bash·TypeScript 쪽은 언어가 달라 접히지 않으므로 처방은 통합이 아니라 계층 간 동등성 검사다. 이미 열려 있는 항목 33(계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶는 문제)과 같은 성격이며 Story 1.7(카카오)이 그 전부를 손으로 고쳐야 한다. 이 스토리가 실제로 겪은 결함이 "목록에서 빠진 leg이 붉게 나오지 않고 사라진다"는 형태였다는 사실이 그 검사의 값을 실측한 것이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: 두 스텁의 서명 키·issuer 격리(한쪽 토큰이 다른 쪽 등록의 콜백에서 거절되는지)를 지나는 단정이 한 층도 없다.
  evidence: `DevOidcStubConfig`·`OidcStubConfiguration`·`verify-same-origin.sh`의 주석이 모두 "한쪽 스텁이 서명한 ID Token은 다른 쪽의 JWKS로 검증되지 않는다"를 계약으로 적지만 그 부정 경로를 실제로 지나는 시험이 없고, issuer 문자열이 다르다는 비교로 대체돼 있다. 게다가 `AuthFlowTestBase.issueCode`가 흐름의 제공자로만 code를 발급하도록 구조로 막아 두어 현재 시험 층에서는 그 케이스를 만들 수조차 없다. 확인하려면 `DevOidcStub`을 직접 불러 다른 스텁의 서명 토큰을 다른 등록의 콜백에 제시하는 낮은 층 단정 하나가 필요하다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: 한 제공자만 구성된 스택을 HTTP·화면 층에서 지나는 검사가 없고, 그 구성에서 사용자가 SPA가 아니라 원본 오류 JSON을 볼 수 있다.
  evidence: 제공자별 fail-closed 판정을 보는 시험 둘(`AuthNaverSignInTest`·`DevOidcStubProfileTest`)은 `ProviderClientRegistrations.from()`을 직접 불러 맵 내용만 본다. `StatusApiTest`는 **어느 제공자도 구성되지 않아** `oauth2Login` DSL 자체가 배선되지 않은 다른 상태를 보고, compose·Playwright는 `dev-oidc`의 `@Primary` 등록이 언제나 두 제공자를 함께 만들므로 부분 구성 스택이 존재하지 않는다. 그래서 "필터는 배선됐고 그 등록만 없다"는 상태가 어느 층에도 없다. 그 상태에서 화면은 제공자 버튼을 조건 없이 그리고(`SIGN_IN_PROVIDERS`), 세션 확인 응답에는 어떤 제공자가 구성됐는지 알 방법이 없고(필드 셋뿐), 인증 시작은 최상위 내비게이션이라 사용자는 `{"code":"INTERNAL_ERROR",…}` 본문을 브라우저에서 그대로 본다. 지금은 개발·CI가 두 제공자를 모두 스텁으로 구성하고 production 배포가 없어(Story 3.8) 도달하지 않지만, 실제 제공자 등록이 하나씩 들어오는 시점에 그 창이 열린다. 구성 여부를 응답에 싣는 것은 Story 1.4가 "소비자 없는 필드를 만들지 않는다"로 거부한 방향이라 처방이 자명하지 않다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: 두 제공자 버튼의 레이블·로고 크기 차이가 "동등한 시각 면적·추천 표지 없음"으로 읽히는지 판단하는 층이 없다.
  evidence: 대비 하한을 만족시키려 네이버 레이블을 20px/700, 로고를 24px로 둔 결과 Google 버튼(14px/600, 로고 18px)과 콘텐츠 크기가 갈렸다. 유일한 guard는 `auth-entry.spec.ts`의 두 버튼 폭·높이 동일 단정이고, 그 등식은 두 버튼의 콘텐츠 높이가 **둘 다** `--component-social-sign-in-min-target-size` 아래에 머무는 동안만 성립한다. 어느 한쪽이 그 값을 넘으면 "시각 면적" 회귀가 아니라 무관해 보이는 픽셀 불일치로 나타난다. 네이버 가이드의 "타사 버튼과 함께 쓸 때 크기를 줄이지 말라"는 만족하지만, 반대 방향(한 제공자가 강조되어 보이는 것)을 DESIGN.md가 금지하고 그것을 보는 검사가 없다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: `verify-same-origin.sh`의 나머지 두 제공자 루프(콜백 탐침·업스트림 오류)에는 완주 수 가드가 없다.
  evidence: 이 라운드가 왕복 루프에만 `왕복 루프가 제공자 수만큼 돌았다`를 넣었다. 콜백 탐침(`CALLBACK_PROVIDERS`)과 업스트림 오류(`UPSTREAM_ERROR_PROVIDERS`) 루프는 `docker compose exec`를 부르지 않아 이번 stdin 결함에는 걸리지 않았지만, 한 목록에서 이름이 빠지면 그 제공자를 조용히 건너뛴다는 형태는 같다. "없는 단정은 붉게 나오지 않는다"가 이 스크립트에서 두 번 더 성립할 수 있다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: `compose()` 함수 자체에 stdin 차단을 두어 `docker compose exec`의 stdin 삼킴을 원천 차단할지 정한다.
  evidence: 이 라운드는 원인을 `psql_value`·`psql_exec` 두 함수에서 닫았다. 그것이 실제 결함 경로를 없애지만, 앞으로 누가 루프 안에서 다른 `compose exec`를 부르면 같은 결함이 다시 난다. `compose()` 한 곳에 `</dev/null`을 두면 클래스가 원천 차단되는데 그 함수는 `up`·`run --rm migrate`·`logs`도 태우므로 그 호출들이 stdin을 쓰지 않는다는 확인이 함께 필요하다. 검증이 통과하는 상태에서 넓히는 변경이라 이 스토리에서 하지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md`
  summary: `application.yml`이 제공자 엔드포인트 재정의 네 이름을 선언하지 않는다(Google·네이버 둘 다).
  evidence: 이 저장소의 규약은 "이름은 `application.yml`이 정하고 값은 환경이 준다"인데 `issuer-uri`·`authorization-uri`·`token-uri`·`jwk-set-uri` 네 이름은 어느 제공자에도 선언돼 있지 않다. 시험(`OidcStubConfiguration`)이 `DynamicPropertyRegistry`로 실제로 그 이름을 주므로 이름은 존재하고 Boot의 relaxed binding으로 동작하지만, 개발·CI가 무엇을 덮는지가 코드에서만 발견된다. 이 스토리의 SPEC Execution 항목은 네이버에 그 넷을 더하라고 적었으나 Google이 선언하지 않은 상태에서 한쪽만 더하면 비대칭이 생겨 두 제공자를 함께 정하는 것으로 미뤘다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: 제공자별 왕복 시험이 제공자 비종속 축을 통째로 복제하는 형태가 셋째 사본까지 왔다.
  evidence: `AuthKakaoSignInTest`가 인가 시작 불변식·신규·기존·취소·제공자 오류·토큰 장애·이미 인증된 세션을 `AuthNaverSignInTest`에서 그대로 되풀이한다. 이 축들은 제공자로 파라미터화된 코드(`AuthFlowHandlers`·`AuthSessionService`)를 지나므로, 같은 SPEC이 `AuthTokenValidationTest`의 검증 실패 18축을 "제공자 비종속이라 복제하지 않는다"로 거절한 것과 같은 성질이다. 1.6이 세운 형태를 1.7이 따랐을 뿐이지만 넷째 제공자는 넷째 사본을 만든다. 파라미터화한 공통 시험 하나와 제공자 고유 구성만 보는 작은 시험으로 가르는 것이 대안이고, 그 판단은 제공자를 더 늘리는 스토리가 아니라 시험 구조를 소유하는 라운드가 해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: `credentials()` fixture와 그 근거 주석이 시험 세 파일에 동일하게 복제됐다.
  evidence: `AuthNaverSignInTest`·`DevOidcStubProfileTest`·`AuthKakaoSignInTest`가 같은 본문과 같은 "자격정보 모양 리터럴을 시험 fixture에도 두지 않는다" 주석을 각자 들고 있다. `AuthFlowTestBase`나 작은 시험 헬퍼로 올리면 하나가 되지만, 그 파일은 이 스토리가 무변경으로 둔 곳이라 여기서 옮기지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: `DevOidcStubConfig`의 스텁 기동 guard가 제공자마다 손으로 쓴 OR 사슬이라 하나를 빠뜨리면 열린 채로 통과한다.
  evidence: guard는 `hasCredentials(google) || hasCredentials(naver) || hasCredentials(kakao)`이고 `PROVIDERS`와도 `AuthProperties`의 성분과도 컴파일 시점 연결이 없다. 이것이 스텁과 실제 제공자가 함께 뜨는 것을 막는 두 층 중 하나이므로, 넷째 성분을 record에 더하고 이 줄을 잊으면 실제 자격정보가 스텁 옆에서 살아 있게 된다. `AuthProperties`에 `providers()` 접근자를 두어 `from()`과 이 guard가 함께 순회하면 그 실패 모양이 사라지지만, 그 변경은 `from()`의 구조를 바꾸므로 검증이 통과한 상태에서 넓히지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: 같은 Java 패키지 안에서도 제공자 목록 사본이 넷이고, `SUPPORTED_REGISTRATION_IDS`와 `from()`이 실제로 만들 수 있는 id 집합이 같은지 보는 단정이 없다.
  evidence: `DevOidcStubProfileTest.PROVIDERS`·`OidcStubConfiguration.PROVIDERS`·`AuthProviderIndependenceTest.PROVIDERS`·`StatusApiTest`의 인라인 배열이 모두 같은 패키지의 `DevOidcStubConfig.PROVIDERS`를 참조할 수 있다. 이것은 SPEC이 Never로 잘라낸 **계층 간** 등가성 검사가 아니라 계층 안 중복이라 성질이 다르다. 특히 `SUPPORTED_REGISTRATION_IDS`(404와 500을 가르는 집합)와 `from()`이 채우는 맵의 키 집합이 어긋나면 아는 제공자가 조용히 404가 되는데, `containsExactlyInAnyOrderElementsOf` 한 줄이 그 둘을 묶는다. 미해소 항목 33·38과 같은 뿌리이며 그쪽이 열려 있는 동안 이 값싼 절반만 먼저 닫을지가 판단할 지점이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: 카카오 심볼이 공식 래스터에서 추출한 경로인데 그 추출을 재현할 산출물이 저장소에 없다.
  evidence: 카카오는 네이버와 달리 버튼 벡터 애셋을 공표하지 않고 가이드와 리소스 도구가 PNG·PSD만 낸다. 그래서 이 라운드는 공식 래스터에서 심볼을 추출해 타원과 직선 꼬리로 복원했고 근거(RMS 0.11px·최대 0.42px, 52.9px 기준)를 주석에 적었지만, 추출 스크립트도 원본 해시도 적합도 데이터도 커밋되지 않았다. 자동 검사는 종횡비와 fill 두 가지뿐이다. 가이드의 첫 금지가 "심볼의 형태·비율 변경"이므로 가이드가 갱신될 때 그 주장을 다시 검증할 방법이 필요하다 — 추출 산출물을 저장소에 두거나 실연동 게이트의 항목으로 올리는 두 길이 있다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md`
  summary: `OnboardingScreen.test.tsx`의 목적지 이동 단정이 머신 부하 아래에서 간헐적으로 깨진다.
  evidence: 이 라운드의 검증 중 백엔드 Gradle 빌드와 프런트엔드 Vitest를 동시에 돌렸을 때 `OnboardingScreen.test.tsx:162`의 `replaceLocation('/dashboard')`가 호출 0건으로 실패했고, 부하가 없는 재실행에서는 157/157이 통과했다. 이 파일은 Story 1.7이 한 줄도 바꾸지 않았으므로 `develop`에 이미 있던 취약성이며, CI 러너가 붐빌 때 같은 방식으로 붉게 나올 수 있다. 비결정적 실패는 재실행으로 덮이면서 신뢰를 갉아먹으므로 대기 조건을 명시적으로 바꾸는 편이 낫다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md`
  summary: `dev_subject` 파라미터 이름이 Java 상수와 셸 스크립트에 각각 리터럴로 있고 둘이 같은지 보는 검사가 없다.
  evidence: `DevOidcStub.SUBJECT_OVERRIDE_PARAMETER`가 이름을 정의하고 `verify-same-origin.sh`가 두 자리에서 `&dev_subject=`를 손으로 조립한다. Java 상수를 고치면 브라우저 층 왕복이 조용히 "요청마다 새 subject"로 되돌아가고, 그 결과는 재인증 절이 "미연결 식별자"로 실패하는 모양이라 원인이 즉시 읽히지 않는다(이 스토리의 첫 실행이 정확히 그 자리에서 멈췄다). 계층 간 계약 리터럴 중복이라는 이미 열려 있는 항목(33·38)과 같은 뿌리이고, 등가성 검사를 세우는 일은 별도로 배포 가능한 목표다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md`
  summary: `ReauthPanel.module.css`가 `EntryScreen.module.css`의 제공자 버튼 규칙 셋을 그대로 복제한다.
  evidence: 두 화면이 같은 세 버튼을 같은 토큰으로 그리는데 규칙 집합이 두 벌이다. 이 스토리는 마크를 `ProviderMark`로 올려 공식 애셋 경로 데이터의 복제는 없앴지만 버튼 규칙은 남겼다. 한쪽만 고치면 "세 버튼의 바깥 크기가 서로 같다"를 단정하는 e2e가 진입 화면에서만 통과한다. 두 인증 화면의 레이아웃 CSS 중복(이미 열려 있는 항목)과 함께 CSS 소유 구조를 정하는 라운드가 다룰 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md`
  summary: 아는 이름이지만 구성되지 않은 제공자로 무장하면 최상위 내비게이션이 원본 오류 JSON에 도달한다.
  evidence: 재인증 시작은 이름만 보고 204를 돌려주고(등록 조회는 인가 시작이 소유한다는 결정), 이어지는 `GET /api/auth/authorize/<제공자>`가 fail-closed 500을 공통 오류 계약으로 낸다. 그 요청은 `fetch`가 아니라 최상위 이동이므로 사용자가 JSON 본문을 보게 된다. 일반 로그인에도 같은 창이 있고 미해소 항목 40이 그것을 소유한다 — 이 스토리는 두 번째 진입점을 더했을 뿐이며, 화면 층에서 부분 구성 스택을 다루는 일은 그 항목의 범위다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md`
  summary: 절대 수명 12시간의 끝에 성공한 재인증이 몇 분 뒤 만료로 끝난다.
  evidence: 세션 회전은 `CREATION_TIME`을 바꾸지 않고(그래야 재인증 반복으로 절대 수명을 무한히 늘릴 수 없다), 그래서 11시간 59분 된 세션이 본인 확인을 마친 직후 `SessionGuard`의 `EXPIRED` 판정을 만난다. 사용자는 방금 신원을 증명하고 만료 안내를 본다. 대안은 남은 수명이 무장 수명보다 짧으면 시작을 거절하는 것이지만, 그 판정은 1.5가 소유한 수명 계약의 경계를 건드리므로 그 계약을 다시 여는 라운드가 정할 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md`
  summary: `useSignOut`·`useReauthentication`의 중복 제출 가드가 React 상태를 읽어 같은 tick의 두 번째 호출을 막지 못한다.
  evidence: 두 훅 모두 `if (pending) return` 뒤에 `setPending(true)`를 하는데 그 값은 재렌더까지 갱신되지 않는다. 브라우저는 클릭을 별도 task로 보내고 React는 discrete event마다 flush하므로 실제 브라우저에서 이 창은 사실상 닫혀 있지만, 프로그래밍 방식 연속 dispatch에서는 열린다. 재인증 쪽 결과가 더 무겁다 — 두 번째 무장이 첫 번째를 덮어 사용자가 고른 제공자와 실제로 열리는 제공자가 달라진다. `useRef` 래치 하나가 두 훅을 함께 닫으므로 훅 하나만 고쳐 관용을 갈라 놓지 않는다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md`
  summary: 소비된 허가 행에 보존 상한이 없어 민감 작업마다 `result` 문자열이 무기한 쌓인다.
  evidence: sweep은 미소비 행만 지운다(소비된 행이 곧 멱등 기록이라 의도된 설계다). 그래서 소비된 행은 계정 삭제의 FK cascade 전까지 영구히 남고, 민감 작업이 늘수록 테이블과 그 안의 결과 문자열이 단조 증가한다. 적절한 보존 기간은 "재전송이 도착할 수 있는 최대 시간"이고 그것은 첫 소비자의 클라이언트 재시도 정책이 정한다 — 이 스토리에는 그 소비자가 없으므로 값을 지어내지 않았다. 1.10이 그 정책과 함께 상한을 정하고 sweep 술어를 넓히는 것이 맞다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md`
  summary: 허가 포트가 `MockHttpSession`으로만 구동되고 실제 Spring Session JDBC 세션으로 발급→소비 왕복을 지나는 층이 없다.
  evidence: 세 시험 파일의 모든 세션이 `new MockHttpSession(null, <uuid>)`이다. 실제 로그인을 지나는 두 축도 세션 id만 꺼내 mock에 실어 넣으므로, `HttpSession#getId()`가 `session_id` 귀속과 같은 값이라는 사실은 관측되지 않고 `sessionIdOf(cookie)`와의 동일성으로 전제된다. 포트에 HTTP 표면이 없는 것이 이 스토리의 Never이므로 여기서 그 층을 만들 수 없다 — 엔드포인트를 만드는 첫 소비자 스토리(1.10)가 실제 세션으로 같은 계약을 다시 단정해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md`
  summary: 같은 `(user, command, target)`으로 동시에 들어온 두 발급이 각자 무효화 후 삽입해 살아 있는 허가를 둘 남길 수 있다.
  evidence: 리뷰 반영으로 발급이 같은 귀속의 미소비 허가를 먼저 무효화하게 했지만, 두 발급이 동시에 그 순서를 밟으면 서로의 삭제를 지나친 뒤 각자 INSERT한다. 발급은 확인 화면이 몰고 오는 요청이라 같은 사용자의 이중 제출이 필요하고, 소비는 여전히 허가 단위로 한 번뿐이므로 민감 작업이 두 번 실행되지는 않는다 — 다만 "재인증 한 번에 살아 있는 허가 하나"라는 더 강한 성질은 성립하지 않는다. 닫으려면 발급 경로에 행 잠금이나 `(user_id, command, target)` 부분 유일 색인이 필요하고 SPEC은 그것을 요구하지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md`
  summary: sweep이 발급 사용자 범위로 좁혀져 다시 발급하지 않는 사용자의 만료 미소비 행이 영구히 남는다.
  evidence: 전체 테이블 DELETE가 모든 발급마다 도는 형태를 리뷰가 지적해 사용자 범위로 좁혔다. 그 대가로 한 번 발급한 뒤 돌아오지 않는 사용자의 만료 행은 아무도 지우지 않는다. 계정 삭제의 FK cascade가 유일한 퇴로이며, 위의 "소비된 행 보존 상한" 항목과 같은 뿌리다 — 보존 정책을 정하는 라운드가 두 경우를 함께 다뤄야 한다.
