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
