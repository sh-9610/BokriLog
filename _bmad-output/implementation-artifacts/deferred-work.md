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
- source_spec: `_bmad-output/implementation-artifacts/spec-1-10-link-sign-in-method.md`
  summary: `/settings`에서 시작한 재인증이 그 화면이 아니라 세션의 확정 목적지로 돌아온다.
  evidence: 재인증 시작은 복귀 경로를 세션의 `DESTINATION_ATTRIBUTE` 하나에서 가져오고, 그 값은 로그인 시점에 정해져 `/onboarding`이나 `/dashboard`다. 그래서 설정 화면에서 본인 확인을 마친 사용자는 대시보드로 떨어진다. 이 라운드가 두 인증 화면에 설정 링크를 더해 되돌아올 길은 생겼지만 클릭 하나가 더 든다. 곧장 돌아오게 하려면 재인증 시작이 복귀 목적지를 인자로 받아야 하는데, 그것은 Story 1.8의 frozen 경계("그 값의 출처는 세션이 이미 가진 `DESTINATION_ATTRIBUTE` 하나이며 클라이언트는 목적지를 보내지 않는다")를 다시 협상하는 일이다. 로그인 시작이 이미 `?next=`를 닫힌 집합으로 검증해 받으므로 선례는 있고, 그 계약을 여는 판단은 1.8을 소유한 사람이 할 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-10-link-sign-in-method.md`
  summary: 내부 경로 집합이 다섯 곳에 복제됐고 그것들이 서로 같은지 보는 검사가 없다.
  evidence: 이 스토리가 `/settings` 하나를 더하면서 `App.tsx` 경로 맵·Nginx 허용 목록 정규식·`AuthFlowHandlers.ALLOWED_DESTINATIONS`·`useSessionState`의 `DESTINATION_PATHS`·`content/auth.ts`의 `RETURN_DESTINATIONS` 다섯 곳을 함께 고쳐야 했다. 문서와 javadoc은 "네 곳"이라고 적었고 그 넷의 구성도 파일마다 다르게 열거한다 — 세는 사람마다 답이 다르다는 사실 자체가 검사가 없다는 증거다. 타입 검사가 잡아 주는 것은 목적지 유니언 하나뿐이고 나머지 넷은 빠져도 조용하다(각각 404·진입 화면·목적지 접힘·`next` 소실로 증상이 갈린다). 계층 간 계약 리터럴 중복(미해소 항목 33·38)과 같은 뿌리이며 등가 검사를 세우는 일은 별도로 배포 가능한 목표다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-10-link-sign-in-method.md`
  summary: 제공자 버튼 CSS 규칙의 세 번째 사본이 생겼다.
  evidence: `EntryScreen.module.css`·`ReauthPanel.module.css`에 이어 `SettingsScreen.module.css`가 같은 세 규칙을 다시 갖는다. 값은 모두 같은 토큰을 참조하므로 표류해도 조용하고, "세 버튼의 바깥 크기가 서로 같다"를 단정하는 e2e는 진입 화면에서만 돈다. Story 1.9가 이미 적은 두 번째 사본 항목과 같은 뿌리이며 CSS 소유 구조를 정하는 라운드가 함께 다룰 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-unlink-sign-in-method.md`
  summary: 제공자별 닫힌 맵이 화면 카피에 둘 더 생겼고 그 목록들이 서로 같은지 보는 검사가 없다.
  evidence: 이 스토리가 `settings.unlinkLabels`와 `settings.unlinkDialog.titles`를 더해 `SIGN_IN_PROVIDERS`를 키로 쓰는 닫힌 맵이 화면 층에서만 다섯이 됐다. `as const`라 키 누락은 타입 검사가 잡지만 **값이 엉뚱한 제공자를 가리키는 것**은 잡지 못하고, 그 축을 보는 단정도 없다. 제공자 이름 목록의 계층 간·계층 안 중복(미해소 항목 33·38)과 같은 뿌리이며, 등가 검사를 세우는 일은 별도로 배포 가능한 목표다.
- source_spec: `_bmad-output/implementation-artifacts/spec-1-11-unlink-sign-in-method.md`
  summary: 최소 조작 영역 44×44px 규칙의 사본이 CSS 네 곳으로 늘었다.
  evidence: `EntryScreen`·`ReauthPanel`·`SettingsScreen`에 이어 `UnlinkDialog.module.css`가 같은 규칙을 다시 갖는다. 값은 모두 같은 토큰을 참조하므로 표류해도 조용하고, 기하를 실제로 재는 e2e는 화면마다 따로 써야 한다. Story 1.10이 적은 "제공자 버튼 CSS 세 번째 사본" 항목과 같은 뿌리이며 CSS 소유 구조를 정하는 라운드가 함께 다룰 일이다.
- source_spec: none
  summary: Epic 1의 `link-provider`·`unlink-provider` 두 command를 Story 2.1이 정하는 새 confirmation hash 계약으로 개조한다.
  evidence: Story 2.1의 다중 목표 점검에서 갈라낸 목표다. 현재 `ActionGrant.canonicalConfirmationHash`의 세 입력 중 `confirmation`이 command별 상수(`LinkService.CONFIRMATION = "연결"`, `UnlinkService.CONFIRMATION = "해제"`)라 해시가 `(command, target)`의 결정적 함수이고 독립 판별력이 0이다. `ERD-1.0.0` §5가 그 축을 따로 둔 뜻(사용자가 확인한 것과 실행되는 것을 묶는다)이 as-built에서 성립하지 않으며, Epic 1 회고가 항목 7로 같은 것을 지적했다. Story 2.1이 `bybit-connection-create`를 위해 그 계약을 새로 정하지만 기존 두 command를 함께 옮기는 것은 Bybit 연결 없이도 독립으로 검토·시험·머지할 수 있는 별개 PR이며, 2.1은 그 개조 없이도 성립한다. 개조 라운드는 Story 2.13·2.16이 같은 계약을 세 번째·네 번째로 소비하기 전에 도는 것이 싸다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md`
  summary: Bybit permission·retCode 허용 목록이 비어 있어 실제 제공자에서 거절 사유가 오분류될 수 있고, 그 공백을 기계가 읽는 게이트가 없다.
  evidence: SPEC이 목록을 지어내지 말라고 했고 구현은 그대로 비워 둔 채 시험 하나로 '비어 있음'을 고정했다 — 여기까지는 의도한 설계다. 남는 위험은 실연동 시점의 모양이다. `READ_ONLY_PERMISSION_GROUPS`가 비어 있으면 비어 있지 않은 permission 배열이 전부 `WRITE_PERMISSION`이 되어 사용자는 이미 가진 읽기 전용 키를 다시 발급하라는 안내를 받는다. 그리고 `RET_CODE_REASONS`가 비어 있으면 `retCode != 0`이 전부 `AUTHENTICATION_REJECTED`로 접히는데, Bybit의 실제 rate limit·IP 거부는 HTTP 429·503이 아니라 **200 + retCode**로 온다 — 즉 `RATE_LIMITED`·`PROVIDER_UNAVAILABLE`은 스텁만 내는 모양이고 실제 제공자에서는 나오지 않는다. API 스파이크가 표를 채우는 라운드가 이 두 가지를 함께 봐야 하며, 목록이 빈 채로 실연동 게이트를 열지 못하게 하는 기계 판독 가능한 표시가 필요하다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md`
  summary: 모듈별 삭제 경로를 열거하는 포트도 레지스트리도 없고, `operations_restriction`에는 삭제 경로 자체가 없다.
  evidence: `SourceConnectionApi.eraseSourceData`와 `PortfolioMembershipApi.erasePortfolioData`는 운영 코드에 호출자가 없고 시험이 손으로 세 메서드를 부른다. `ON DELETE RESTRICT`가 만든 삭제 순서 계약은 javadoc에만 있다. Epic 5의 orchestration이 모듈 하나를 빠뜨리면 유일한 신호가 런타임 FK 위반이다. `operations_restriction`은 삭제 경로가 아예 없어 `AuthFlowTestBase`가 별도로 TRUNCATE한다. Epic 1 회고가 같은 뿌리를 지적했다 — `AuthDeletionPathTest`가 `action_grant`를 한 번도 언급하지 않는 것. 삭제 참여자를 기계가 열거하게 만드는 일은 Epic 5가 자기 자원과 함께 정할 별도 목표다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md`
  summary: 5분 무장 수명이 화면에 보이지 않고 만료가 구분되지 않는 거부로 끝난다.
  evidence: `BybitConnectionFlow.ARMED_LIFETIME`은 5분인데 카피는 확인 문구가 '이번 연결 요청에만 유효'라고만 말하고 남은 시간도 갱신 수단도 주지 않는다. 그런데 같은 카피가 사용자를 Bybit 콘솔로 보내 키를 발급하고 IP를 등록하게 한다 — 5분 안에 끝나기 어려운 작업이다. 만료는 구분되지 않는 `FORBIDDEN`으로 돌아오고 화면은 '본인 확인을 마친 뒤 다시 시도'라고 말한다. 거부를 하나의 값으로 두는 것은 SPEC이 고정한 계약이라 응답을 나눌 수 없고, 남은 길은 화면이 만료를 스스로 알고 미리 말하는 것이다. 그것은 무장 시각을 클라이언트에 주는 결정이라 별도 판단이 필요하다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md`
  summary: Bybit 왕복 e2e 넷이 `PLAYWRIGHT_BASE_URL` 게이트 안에 있어 기본 실행에서 돌지 않는다.
  evidence: Story 1.4가 세운 관용을 그대로 따른 것이고 매트릭스 19행은 항상 도는 백엔드·Vitest가 덮으므로 감사 기준은 만족한다. 다만 브라우저 층에서 자격정보 비노출을 보는 유일한 시험이 주입 origin 실행에만 있다는 사실은 남는다. 같은 성질의 미해소 항목(주입 origin 게이트 밖에서 보이지 않는 축)과 한 뿌리이며, e2e 게이트 구조를 정하는 라운드가 함께 다룰 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-1-bybit-connection.md`
  summary: `SettingsScreen.test.tsx`의 여러 시험이 연결 상태 요청을 stub하지 않아 Bybit 절이 의도치 않게 `unavailable` 분기로 렌더된다.
  evidence: `해제: 확인하면 목록을 다시 읽고 결과를 말한다`, `해제 성공 뒤 포커스가 결과 문장으로 간다`, 그리고 연결 상태 재확인 시험이 `ready` 게이트를 지나면서 새 상태 요청을 전역 deny에 부딪힌다. 어느 것도 `getObservedRequests()`가 비었음을 단정하지 않아 통과하지만, 그 시험들이 의도한 화면 상태가 아니다. 새 섹션이 기존 화면 시험의 전제를 바꿨다는 뜻이고, 화면 시험이 자기 전제를 명시하게 만드는 일은 화면 시험 구조를 정하는 라운드의 몫이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-3-plan-condition-preview.md`
  summary: 10분 확인 참조가 만료된 뒤에도 계정 삭제 전까지 확인 행이 보존된다.
  evidence: `plan_condition_confirmation.expires_at`은 확인을 거절하는 데 쓰이지만 만료 행을 정리하는 작업이나 삭제 경로가 없다. 계획 화면을 다시 열지 않는 사용자의 만료 참조는 계속 남으므로 확인 데이터 보존 상한을 정하고 청소 작업을 두는 별도 보존 정책 검토가 필요하다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: 사건 분 candle이 공유 증거에 있으면 kline을 다시 부르지 않으므로, 제공자 쪽 사건 분 종가 정정은 관측되지 않고 `PRICE_REVISION`은 fallback 분에서만 생긴다.
  evidence: SPEC AC가 "공유 증거에 사건 분 candle이 있으면 kline을 호출하지 않는다"를 요구하고 `CashflowConversionMarket.resolveCandles`가 그대로 따른다. 통합 시험의 revision 단정도 `sync_market_candle`에 SQL로 새 revision을 넣어 만든다. AC-8의 "더 적합한 승인 공식 가격 → 새 환산 버전"을 실제 경로로 여는 것은 이력 재검증을 소유한 Story 2.12가 캐시 재관측 주기와 함께 정할 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: 환산 기준시각인 Bybit 서버 시간을 로컬(DB) 시계와 비교하는 상한 없이 쓴다.
  evidence: `BybitAdapter.marketTime`과 `SyncJobService.fixReference`는 `timeNano` 형식만 검사한다. 미래로 틀어진 값이 오면 진행 중인 분 candle이 종료로 판정되고, 사용자 식별자 없는 공유 증거로 저장돼 다른 사용자의 환산에도 재사용된다. 허용 skew 값은 `BYBIT-PRICE-1.0`에 없는 새 시간 경계라 SPEC의 Ask First 대상이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: 만료된 lease의 재claim에 횟수 상한이 없어, `RuntimeException` 밖의 오류나 JVM 중단으로 죽는 시도가 약 30초마다 영구히 다시 실행된다.
  evidence: `CashflowConversionRepository.claim()`은 `RUNNING AND lease_until<now()`인 행을 제한 없이 다시 가져가고, 재실행마다 서버 시간·instrument·kline을 다시 부른다. 같은 claim 관용구가 Story 2.4 수집·2.5 판정 작업에도 있어 이 스토리가 만든 결함이 아니다. 시도 횟수 컬럼과 상한은 세 작업이 함께 정할 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: ERD §2.5의 호출 예산(token bucket)·자동 재시도가 없어, 끝난 결과 뒤에도 새 key로 환산을 다시 접수하면 서버 시간·instrument 공개 호출이 사용자 요청 수만큼 반복된다.
  evidence: SPEC Design Notes가 token bucket과 자동 재시도를 Story 2.4처럼 도입하지 않기로 정했다. 화면은 재시도 가능한 이유에만 버튼을 주지만 API는 settled 시도 뒤의 새 접수를 막지 않는다(AC-8의 새 버전 경로가 그 접수를 쓴다). 공유 reserved IP 한 개로 나가는 공개 호출의 예산은 Bybit 호출 전체를 묶는 별도 라운드가 정할 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: 수집·판정·환산 세 상태 섹션이 GET이 계속 실패해도 QUEUED·RUNNING이면 2초마다 끝없이 폴링한다.
  evidence: `InitialInputsCollection.tsx`의 세 `setInterval` 폴링은 연속 실패 횟수를 세지 않는다. Story 2.4·2.5의 폴링을 그대로 옮긴 관용구라 세 섹션을 함께 고쳐야 일관된다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: 수집 섹션의 `start()`가 409를 받으면 거절 문구를 설정한 직후 `refresh()`가 성공하며 그 문구를 지운다.
  evidence: 같은 결함이 이 스토리의 환산 섹션에도 옮겨 와 리뷰가 잡았고 환산 쪽은 이 스토리에서 고친다. Story 2.4의 수집 `start()`(`setError(copy.collection.ineligible)` 뒤 `await refresh()`)는 이 스토리 범위 밖이라 남긴다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md`
  summary: Windows Git Bash에서 `verify-same-origin.sh`가 한글 확인 문구를 `curl --data`로 CP949 바이트로 보내 Bybit 연결 확인이 400으로 끝나고 이후 57건이 연쇄 실패한다.
  evidence: Story 2.6 구현 중 902/959로 끝났고, 로컬 echo 서버로 받은 바이트가 `bfacb0e1…`(기대 UTF-8 `ec97b0…`)였다. 이 스토리의 변경과 무관하며 이미 기록된 "Windows 전용 결함을 CI가 보지 못하는 공백"과 같은 종류다. Linux CI에서는 재현되지 않는다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md`
  summary: 대시보드 조회를 포함한 프런트엔드 조회 훅에 요청 시간 상한이 없어, 응답이 끝나지 않으면 진행 중 가드 때문에 다시 불러오기가 페이지를 새로 열 때까지 무시된다.
  evidence: `useDashboard.ts`의 `fetch`에는 `signal`이 없고 `inFlight`가 settle될 때까지 참으로 남는다. 저장소의 다른 조회 훅(`useFirstSnapshot` 등)도 시간 상한을 쓰지 않는 관용구라, 한 훅만 고치면 동작이 갈린다. 훅 전체에 같은 상한과 실패 문구를 정하는 라운드가 필요하다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md`
  summary: 대시보드의 state·cause·nextAction 코드와 지표 순서가 백엔드 문자열 리터럴·SQL `ORDER BY CASE`·`DashboardTrust.METRICS`·프런트엔드 닫힌 맵과 `METRIC_ORDER`에 따로 적혀 있고, 서로 같은지 대조하는 검사가 없다.
  evidence: 서버가 cause를 하나 바꾸거나 더해도 화면은 안전한 대체 문구로 접을 뿐 어떤 시험도 깨지지 않는다(리뷰가 이름 변경으로 시연했다). 이미 기록된 "계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶는 문제"의 새 사례이며 제공자 이름·내부 경로 집합과 함께 풀 일이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md`
  summary: 계획이 없는 랜딩 화면(`NO_PLAN`·`PREPARING`)에서 화면의 `role=status`와 `FirstSnapshotPanel`의 `aria-live` 영역이 함께 있어 화면당 live region이 둘이다.
  evidence: `FirstSnapshotPanel.tsx`의 `aria-live="polite"` 영역은 Story 2.2부터 있었다. 이 스토리는 `READY`에서 그 패널을 숨겨 대시보드 상태의 단일 live region을 맞췄지만, 온보딩 흐름의 알림을 화면 status 하나로 합치는 일은 첫 공식값 패널의 상태 문장 전체를 옮기는 별도 변경이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md`
  summary: 로그인 수단 해제의 취소가 서버에서 다음 준비와 겹치면, 늦게 끝난 취소가 새 무장(세션 속성)과 새 허가(`(user, command, target)` 무효화)를 지워 그 최종 확인이 403으로 끝난다. 같은 탭은 이 스토리가 막았고 다른 탭 사이는 여전히 열려 있다.
  evidence: PR #45의 CI `same-origin-integration`이 `auth-entry.spec.ts`의 해제 대화상자 시험에서 잡았다. trace상 취소 182ms가 두 번째 준비(취소 시작 59ms 뒤 출발)와 겹쳤고 확인이 403이었다. `UnlinkService.abandon`은 허가를 참조가 아니라 `(user, command, target)`으로 무효화하고 세션 속성 제거는 요청 끝에 저장되므로, 서버 쪽 해소는 참조 단위 무효화와 무장 세대 비교를 함께 정해야 한다(Story 1.11 경로, `LinkService.abandon`도 같은 모양).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-8-official-dashboard.md`
  summary: `InitialInputsCollection.test.tsx`의 "stops following an attempt once its state read is refused (403)"가 Vitest 전체 실행 부하 아래에서 간헐적으로 실패한다.
  evidence: Story 2.8 검증 중 전체 실행 한 번에서 실패했고 그 파일 단독 5회·전체 2회는 모두 통과했다. Story 2.7이 더한 시험이며 이미 기록된 `OnboardingScreen.test.tsx`의 부하 아래 간헐 실패와 같은 종류다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-10-cashflow-adjustment-history.md`
  summary: `AdjustmentHistory.module.css`가 `CalculationEvidence.module.css`의 `.action`(44×44 최소 조작 영역)·`.id`(mono 전체값 줄바꿈)·`.facts`·`.notice` 규칙을 거의 그대로 옮겨 44×44 규칙의 다섯 번째 사본이 됐다.
  evidence: Story 2.10 리뷰가 두 파일의 규칙이 같은 토큰 조합으로 반복된다고 짚었다. Story 1.11이 네 번째 사본을 기록했고 공통 규칙을 한 곳으로 모으는 일은 여러 화면 CSS 모듈을 함께 바꾸는 별도 정리다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-11-incremental-plan-sync.md`
  summary: 증분 동기화는 매번 `(t0, tc]` 전체를 새 후보·판정·환산·계산 흐름으로 다시 쓰고, 값이 바뀌지 않아도 새 공식 버전을 발행한다. 그래서 저장량이 버전 수 × 사건 수로 늘고 근거의 공식 버전 목록에 상한이 없다. 한 단계 트랜잭션이 30초 lease보다 길어지면 heartbeat(`SKIP LOCKED`)가 갱신하지 못해 큰 이력은 `SYNC_INTERRUPTED`로 끝날 수 있다.
  evidence: 2.9·2.10의 "계산 버전 하나가 한 계보를 인용한다" 불변식을 지키려고 SPEC Design Notes가 전체 재처리를 택했다. 진입·재활성화 자동 트리거는 5분마다 접수될 수 있다. "변경 없음이면 발행하지 않음"·보존 상한·단계 트랜잭션 안의 lease 연장은 이력 규모가 보이는 시점에 정할 일이다(Story 2.11 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-11-incremental-plan-sync.md`
  summary: 이어받은 사건의 USD 값이나 판정이 공식 버전 사이에서 바뀌어도(가격 revision·정책 버전 교체) Story 2.10 조정 이력에는 변경 표지도 과거 버전도 나타나지 않는다.
  evidence: 동기화마다 새 판정 묶음을 만들고 `insertBundle`은 `previous_conversion_id`를 같은 판정 안에서만 잇는다. 그래서 버전을 건너는 사슬이 없다. 지금은 캔들 캐시와 고정 정책 때문에 값이 같게 나오지만, 정정을 새 버전으로 반영하는 Story 2.12가 버전 간 연결을 소유해야 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-2-11-incremental-plan-sync.md`
  summary: `검토 필요`로 끝난 동기화(대표적으로 설명되지 않는 저장 사건)의 다음 행동이 `SYNC`인데, 원인이 늦은 사건이면 다시 동기화해도 풀리지 않는다. 발행되지 않은 시도가 저장한 사건이 제공자에서 사라지면 이후 모든 동기화가 검토로 끝난다.
  evidence: frozen SPEC이 `LAST_SYNC_NEEDS_REVIEW`의 다음 행동을 `SYNC`로 정했고, 늦은 사건의 정밀 재검증은 Story 2.12의 범위다. 확장 검증 창이 그 사건을 다시 관측하면 풀리지만, 제공자에서 사라진 사건은 관측되지 않는다(Story 2.11 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-11-incremental-plan-sync.md`
  summary: `SyncControl.module.css`가 44×44 최소 조작 영역 규칙(`min-block-size`·`min-inline-size: var(--space-target-min)`)의 여섯 번째 사본이 됐다.
  evidence: Story 2.10이 다섯 번째 사본을 기록했다. 공통 규칙을 한 곳으로 모으는 일은 여러 화면 CSS 모듈을 함께 바꾸는 별도 정리다.
- source_spec: `_bmad-output/implementation-artifacts/spec-backend-test-speed.md`
  summary: `FirstSnapshotTest.staleFenceCannotPublishAfterLeaseOwnershipChanges`의 `Thread.sleep(2000)`이 `SLOW_WALLET` 스텁 지연 2000ms와 같아, 단정이 늦은 워커가 스텁에서 돌아오기 전에 실행될 수 있다. 그러면 fence가 막는 쓰기를 한 번도 시도하지 않은 채 통과한다.
  evidence: fence 증가는 `RUNNING`을 본 직후이고 지갑 호출은 claim보다 조금 늦게 시작하므로 2초 창이 스텁의 2초 응답을 덮지 못한다. 다른 느린 스텁 대기(582·627·1175·1197)는 500~1000ms 여유가 있다. 이 변경 전부터 있던 창이며 `spec-backend-test-speed.md`는 이 대기를 유지로 분류했다(리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-backend-test-speed.md`
  summary: 느린 스텁 호출 중의 늦은 워커를 기다리는 고정 대기(`FirstSnapshotTest` 582·627·1175·1197·1659, 실행당 약 12.5초)는 시험 표면(`BybitStubConfiguration`)의 "느린 응답을 보냈다" 계수기와 `QUIET_WINDOW`로 바꾸면 결정적이고 짧아진다.
  evidence: 스텁의 응답·지연은 바꾸지 않고 시험 표면에 계수기만 더하면 되지만, 이번 SPEC은 "끝이 관측되지 않는다"로 보고 유지했다. 남은 백엔드 시험 시간에서 가장 큰 고정 대기 묶음이다(리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-backend-test-speed.md`
  summary: 세 fence 시험(`FirstSnapshotTest` 614·1167·1649)은 heartbeat가 스텁 지연 2초 창을 놓친 실행(약 60%)에서만 DB fence(`fencing_generation=:g`)를 지나고, 나머지는 heartbeat가 in-flight lease를 지운 메모리 guard에서 끝난다. DB fence 회귀는 확률적으로만 잡힌다.
  evidence: `FirstSnapshotWorker.heartbeat`가 5초마다 fenced lease를 지우고 워커는 제공자 호출 뒤 메모리 guard를 먼저 본다. `spec-backend-test-speed.md`는 이 확률을 떨어뜨리지 않으려고 시험 heartbeat를 줄이지 않았다. 메모리 guard를 건너뛰고 DB fence만 보는 결정적 시험은 별도 작업이다(리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-backend-test-speed.md`
  summary: 개발·CI compose 스택은 워커 dispatch를 운영 기본 1초로 돌려 `same-origin-integration`의 동기화 왕복(Story 2.11이 회귀 단계를 4 → 6분으로 올린 원인)이 단계마다 실제 tick을 기다린다.
  evidence: `bokrilog.sync.worker.dispatch-interval`은 이제 설정이므로 production이 아닌 compose(`deploy/compose/compose.yaml`)만 짧게 줄 수 있다. 이번 범위는 백엔드 시험이라 compose와 E2E 예산은 건드리지 않았다(리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md`
  summary: 44×44 최소 조작 영역 규칙의 일곱·여덟 번째 사본이 생겼다(`SyncControl.module.css`의 `.secondary`, `VerificationControl.module.css`의 `.button`). `.ranges`는 세 CSS 모듈에, `.facts`·진행 표지 `@keyframes`는 두 곳에 같은 모양으로 있다.
  evidence: Story 2.10·2.11이 다섯·여섯 번째 사본을 기록했다. 공통 규칙을 한 곳으로 모으는 일은 여러 화면 CSS 모듈을 함께 바꾸는 별도 정리다(Story 2.12 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md`
  summary: 현재가치의 측정 시각(`measured_at`, 수집 끝 wallet 응답)과 흐름 확인 상한(`tc`) 사이의 간격에 상한이 없다. page 재시도(최대 2·10·30초)나 CATCH_UP의 마지막 7일 재수집이 그 간격을 넓히고, 그 사이의 입출금은 `E`에는 들어가지만 흐름에는 들어가지 않는다.
  evidence: Story 2.4부터 `tc`를 먼저 고정하고 wallet을 수집 끝에 읽는 설계다(ERD §2.2가 두 시각을 분리한다). 간격 상한은 새 정책 값이라 이 스토리에서 정하지 않았다(Story 2.12 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md`
  summary: Bybit 호출 예산(token bucket·동시 수집 2·IP 403 회로)이 프로세스 메모리에만 있어, 같은 reserved IP 뒤의 둘째 인스턴스는 한도와 차단 회로를 공유하지 않는다.
  evidence: ERD §2.5는 "프로세스 token bucket"을 기본값으로 정했고 지금 배포는 단일 API 프로세스다. Blue-green 전환(Story 3.8) 동안 두 프로세스가 겹치는 시점에 공유 여부를 정할 일이다(Story 2.12 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md`
  summary: 동기화 작업 heartbeat가 `SKIP LOCKED`라서, 긴 page 저장 트랜잭션이 작업·자식 행을 잡고 있는 동안 lease 갱신이 건너뛰어진다. CATCH_UP처럼 page가 많은 작업은 재개(최대 2회)를 소진할 수 있다.
  evidence: Story 2.11이 물려준 heartbeat 관용이다(항목 85와 같은 원인). 단계 트랜잭션 안의 lease 연장은 이력 규모가 보이는 시점에 함께 정할 일이다(Story 2.12 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md`
  summary: V12의 변경 행 backfill은 마이그레이션 시점에 있던 증분 버전만 덮는다. `migrate` 역할이 V12를 적용한 뒤에도 2.11 바이너리가 발행을 계속하면, 그 버전에는 변경 행이 없어 근거·조정 이력이 다음 발행까지 503이다. 옛 바이너리가 VERIFY 작업을 집으면 V12 CHECK에 걸린다.
  evidence: AD-12가 마이그레이션을 API 기동과 분리했고, 아직 운영 배포가 없어 단일 인스턴스에서는 생기지 않는다. 새 바이너리 전환과 expand 마이그레이션의 순서는 Blue-green 배포(Story 3.8)가 정할 일이다(Story 2.12 2차 검토).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-13-plan-early-end.md`
  summary: 계획 중도 종료(`plan-end`)가 재인증 시각을 지우지 않는 민감 command 목록에 더해졌다. 본인 확인 1회가 5분 창 안에서 연결·해제·Bybit 연결·계획 종료를 연쇄 승인할 수 있다.
  evidence: Epic 1 회고가 "재인증 시각(`bokrilog.auth.reauthenticated-at`)을 지우는 코드가 없다"를 실연동 게이트 전에 닫을 액션으로 남겼다(`sprint-status.yaml`의 `action_items`). 이 스토리는 그 관용을 따랐을 뿐이며, 소비 뒤 시각을 지우는 일은 네 command를 함께 바꾸는 별도 정리다(Story 2.13 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-13-plan-early-end.md`
  summary: 44×44 최소 조작 영역 규칙의 아홉·열 번째 사본이 생겼다(`PlanEndControl.module.css`, `PlanEndDialog.module.css`). 진행 표지 `.marker`와 `@keyframes`는 네 모듈에, 확인 대화상자 규칙은 `UnlinkDialog.module.css`와 두 곳에 같은 모양으로 있다.
  evidence: Story 2.10~2.12가 다섯~여덟 번째 사본을 기록했다. 공통 규칙을 한 곳으로 모으는 일은 여러 화면 CSS 모듈을 함께 바꾸는 별도 정리다(Story 2.13 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-13-plan-early-end.md`
  summary: 모달의 기본 동작(문서 capture `keydown`의 Tab 가둠, Esc, `body` 스크롤 잠금, 초점 복귀)이 `UnlinkDialog`와 `PlanEndDialog`에 손으로 두 벌 있다. 둘을 맞춰 줄 공유 훅이나 시험이 없다.
  evidence: `PlanEndDialog`는 SPEC대로 `UnlinkDialog` 관용을 따랐다. 공유 훅으로 묶는 일은 Story 1.11의 대화상자를 함께 바꾸는 정리라 이 스토리 범위 밖이다(Story 2.13 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-14-plan-natural-completion.md`
  summary: 완료 비교 상태 넷과 이유 다섯(보류 이유 포함)이 `content/planCompletion.ts`의 카피 맵과 `session/useDashboard.ts`의 파싱 목록, 백엔드 상수 사본에 따로 적혀 있고 세 목록이 같은지 보는 검사가 없다.
  evidence: 한쪽에만 값을 더해도 타입 검사·시험이 잡지 못한다. Story 2.8이 대시보드 코드 목록에 대해 기록한 "계층을 넘는 리터럴 대조 부재"의 새 사례이며, 대조 검사는 여러 목록을 함께 묶는 별도 정리다(Story 2.14 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-14-plan-natural-completion.md`
  summary: 새 CSS 모듈 둘(`PlanCompletionResult.module.css`, `PostEndValue.module.css`)의 `.region`이 테두리 토큰 하나만 다른 같은 패널 프레이밍 규칙이고, 기존 영역 모듈 규칙의 또 다른 사본이다.
  evidence: Story 2.10~2.13이 같은 종류의 CSS 사본을 기록했다. 공통 규칙을 한 곳으로 모으는 일은 여러 화면 CSS 모듈을 함께 바꾸는 별도 정리다(Story 2.14 리뷰).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-16-bybit-connection-revocation.md`
  summary: 민감 command 확정이 커밋·세션 회전한 뒤 응답을 잃으면, 같은 키 재시도가 회전 전 세션 쿠키로 나가 재생되지 못하고 만료 화면으로 이동한다.
  evidence: 2.16 리뷰(edge-case)가 연결 해제 확정에서 지적했고, 같은 회전 규칙(`SECURITY-ACTION-1.0`)을 쓰는 2.13 중도 종료 확정에도 같은 경로가 있어 이번 스토리가 만든 결함이 아니다.
- source_spec: none
  summary: Epic 2 회고 AI-8의 프런트엔드 묶음(D-4) — 대화상자 모달 동작 3벌(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`)과 그 CSS 모듈, 조회 훅 2벌(`useAdjustmentHistory`·`useCalculationEvidence`)의 진행 중 요청 처리, 의미가 다른 `isInstant` 2벌을 한 정의로 모은다.
  evidence: AI-8을 백엔드 공통 부품(세션 무장 Flow·rotate·허가 무효화·멱등 키·lease 상수·재인증 시각 소비)과 프런트엔드 사본으로 나눴다. 두 묶음은 서로 다른 계층이고 독립적으로 머지할 수 있어, 백엔드 chore(`spec-sensitive-command-commons.md`)를 먼저 하고 프런트엔드는 바로 이어지는 두 번째 chore로 한다(2026-10-06 Epic 3 착수 전 점검).
- source_spec: `_bmad-output/implementation-artifacts/spec-sensitive-command-commons.md`
  summary: 민감 command 실행 성공이 재인증 시각을 지우므로 설정 화면에서 연결·해제 성공 직후의 다음 준비 요청은 403 `reauth-required` 게이트에 떨어진다 — 성공 직후 본인 확인 안내를 다시 보여 주는 화면 동작과 카피가 그 계약에 맞는지 확인하지 않았다.
  evidence: 이 chore는 백엔드 공통 부품만 바꾸고 프런트엔드 제품 코드는 D-4 별도 chore로 뺐다. e2e·검증 스크립트에는 재인증 단계를 더했지만 화면의 안내 문구·버튼 상태는 보지 않았다(리뷰 blind-hunter, 2026-10-06).
- source_spec: `_bmad-output/implementation-artifacts/spec-frontend-dialog-commons.md`
  summary: 계획 중도 종료의 성공 문구(`content/planEnd.ts`)에는 "다음 민감 조작 전 본인 확인을 다시 한다"는 안내가 없다 — 연결·해제·Bybit 연결·해제 성공 문구에만 넣었다.
  evidence: 계획 종료도 같은 `SessionArming`·`SessionRotation` 게이트를 지나므로 성공 직후 다음 민감 command는 403이다. 종료 결과 화면은 대시보드 패널이라 설정 화면의 "아래에서 본인 확인" 문구를 그대로 쓸 수 없어 카피를 따로 정해야 한다(리뷰 blind-hunter, 2026-10-07).
- source_spec: `_bmad-output/implementation-artifacts/spec-frontend-dialog-commons.md`
  summary: `UnlinkDialog.test.tsx`·`PlanEndDialog.test.tsx`가 여전히 가둠·Esc·스크롤 잠금·복귀 같은 메커니즘 시험을 각자 들고 있어 `useConfirmationDialog.test.tsx`(정본)와 겹친다.
  evidence: 이 chore는 신규 `ConnectionRevokeDialog.test.tsx`만 마크업·카피·`restoreFocus` 의미로 좁혔고, 기존 두 파일은 단정을 바꾸지 않는다는 경계 때문에 손대지 않았다(리뷰 blind-hunter, 2026-10-07).
- source_spec: `_bmad-output/implementation-artifacts/spec-frontend-dialog-commons.md`
  summary: "Bybit 연결 성공 → 재인증 없이 해제 준비 → 403 안내" 경로는 Vitest만 고정하고 주입 origin e2e가 없다(로그인 수단 연결 → 해제 준비 경로만 `auth-entry.spec.ts`에 있다).
  evidence: `bybit-connection.spec.ts`에는 해제 시험 자체가 없어 한 단계를 더하는 일이 아니라 해제 왕복을 새로 쓰는 일이다. Epic 2 회고 AI-11(M2-4 브라우저 근거 보강)과 같은 묶음이다(리뷰 blind-hunter·verification-gap, 2026-10-07).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-operator-surface-audit-ledger.md`
  summary: 준비만 하고 방치되거나 5분 허가가 만료된 운영 operation은 원장에 `REQUESTED` 한 행만 남고 결말 행이 없다 — 조회·export가 "만료로 끝남"을 따로 말하지 않는다.
  evidence: 결말 행을 쓰려면 만료를 관측하는 주체(백그라운드 또는 다음 요청)가 필요한데 3.1은 백그라운드를 두지 않는다. 회수·문구 불일치·실행 실패·취소·대체는 결말 행을 남기도록 고쳤다(리뷰 edge-case-hunter, 2026-10-08).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-operator-surface-audit-ledger.md`
  summary: 감사 actor digest에 키 버전·식별자가 없어 `BOKRILOG_AUDIT_DIGEST_KEY`를 교체하면 교체 전후 같은 수행자 행을 묶을 수 없고 어느 키의 digest인지도 알 수 없다.
  evidence: `ActorDigest`는 키 하나로 HMAC만 만들고 `operations_audit_event`에 키 버전 열이 없다. 키 회전 절차는 production 자격정보를 소유하는 Story 3.8의 입력이다(리뷰 blind-hunter, 2026-10-08).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-operator-surface-audit-ledger.md`
  summary: 운영 command 사유는 자유 텍스트이고 이메일 모양만 거절하므로 전화번호·이름·Bybit UID 같은 개인정보가 영구 원장에 들어갈 수 있고, `eraseActor`는 `reason`·`target`을 비식별하지 않는다.
  evidence: `OperatorEnvelopeService`의 사유 검증과 `OperatorAuditRepository.eraseActor`의 UPDATE 범위. 사유 필드의 보존·삭제 분류는 법정 분리 보존 결정(G-5, SPEC Ask First)과 함께 정해야 한다(리뷰 blind-hunter, 2026-10-08).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-scoped-manual-restriction.md`
  summary: 계획 조건 조회(`PlanConditionService.state`)가 제한을 만나면 사용자의 열린 계획 조건 intent를 되돌릴 수 없게 무효화한다 — 운영 제한은 해제되면 끝나는 일시 상태인데 그 사이 진행하던 조건 확인이 사라진다.
  evidence: 무효화는 3.2 이전부터 전역 command 제한에 대해 있던 동작(`PlanConditionService.java` `state()`의 `invalidateOpen`)이고, 3.2의 portfolio·연결 scope가 그 발동 범위를 넓혔을 뿐이다. 제한 중 intent를 보존할지는 해제(3.4)·공지(3.5)와 함께 정해야 한다(리뷰 blind-hunter, 2026-10-09).
- source_spec: none
  summary: 강한 신호 1·8·9번(쓰기 가능 key의 저장 단계 도달, 5분 창 schema 검증 실패, 5분 창 credential 복호화 실패)의 감지 지점 연결을 Story 3.3 후속으로 미룬다.
  evidence: 세 신호는 섞인 원인을 먼저 갈라야 한다 — `SourceVerification.Verified`에 readOnly 근거 없음, `RESPONSE_FORMAT`이 cursor 반복과 섞임, `CredentialCipher.open`이 키 부재와 태그 불일치를 구분하지 않음. 8·9번은 시간 창 집계 저장소도 새로 필요하다. 신호 기반을 먼저 검증하려고 3.3을 나눴다(2026-10-09).
- source_spec: none
  summary: 강한 신호 2번(교차 사용자 데이터 접근 성공·공식 pointer 혼합)은 안전한 감지 지점이 없어 연결하지 않는다.
  evidence: 모든 읽기 쿼리가 `user_id`로 걸러 교차 접근 "성공"을 관측하는 단정이 없고, 대시보드의 계보 불일치(`Dashboard.Unavailable`)는 trust 판정 실패 같은 무결성 무관 원인과 같은 타입이다. 오탐 하나가 금융 read/write·신규 연결·sync 전역 정지가 되므로 원인을 구분한 단정이 먼저다(2026-10-09).
- source_spec: none
  summary: 강한 신호 3번(secret/token의 redaction canary·외부 telemetry 검출, DLP)은 telemetry 경로를 만드는 Story 3.7과 함께 연결한다.
  evidence: 런타임 로그 redaction·canary·telemetry 코드가 없고(`SourceCredentials.toString` redacted 하나), Grafana Alloy 수집은 3.7 범위다(2026-10-09).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-3-strong-signal-auto-stop.md`
  summary: 강한 신호로 연결·동기화가 제한되면 사용자 API(`sync.initialCollectionJob` 등)가 사용자 자신의 수집 상태를 빈 값으로 감춰, 사용자는 무엇이 왜 멈췄는지 화면에서 알 수 없다.
  evidence: 3.3 구현에서 `FirstSnapshotTest`의 상태 확인이 사용자 API로는 불가능해져 DB 직접 조회로 바뀌어야 했다. 제한 중 사용자에게 보일 상태·문장은 제한과 공지를 매핑하는 Story 3.5의 입력이다(리뷰 blind-hunter, 2026-10-09).
- source_spec: none
  summary: Story 3.4 AC-6(베타 전·월 1회 훈련의 5분 수동 전역 정지·60초 자동 정지·선택 범위 복구를 실제 시각과 증거로 기록하고 자동시험과 구분)의 훈련 증거 기록을 3.4에서 떼어 운영 배포 증거(Story 3.8)와 함께 다룬다.
  evidence: 훈련 기록은 해제 command와 독립적으로 배포 가능한 목표이고, "실제 훈련"과 개발·CI 리허설을 가르려면 배포 환경 표지가 필요한데 그 설정은 production 배포를 소유하는 3.8이 정한다. 3.4는 해제·smoke·자동 재개 차단에 집중한다(2026-10-10).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-4-manual-recovery.md`
  summary: 운영 command 무장(`OperatorCommandFlow.Armed`)의 직렬화 버전이 스토리마다 올라가(3.4에서 2→3) 배포 순간 Spring Session JDBC에 남은 무장 세션을 역직렬화하지 못하고, rollback 창에서 옛·새 인스턴스가 서로의 세션을 읽지 못한다.
  evidence: `Armed`에 필드를 더할 때마다 `serialVersionUID`를 올리는 패턴이 3.2부터 반복된다. 진행 중 무장을 버리고 재준비를 요구할지, 호환 역직렬화를 둘지는 blue/green 배포·rollback 절차를 정하는 Story 3.8의 입력이다(리뷰 edge-case-hunter, 2026-10-10).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-5-service-notice.md`
  summary: 운영자 화면의 운영 command 상태 기계(준비·확정·취소·멱등 키·불확실·목록 sequence)가 `useOperatorRestriction`·`useOperatorRecovery`·`useOperatorNotice` 세 벌로 복사됐다 — 다음 운영 command가 네 번째 사본을 만들기 전에 공통 훅 하나로 묶어야 한다.
  evidence: 3.2·3.4가 같은 패턴을 복사했고 3.5가 셋째를 더했다(리뷰 blind-hunter, 2026-10-10). AI-8이 확인 대화상자·참조 조회를 공통 부품으로 정리한 것과 같은 성격의 일이며, 스토리 범위 밖 리팩터로 따로 다룬다.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-inquiry.md`
  summary: 운영자가 잘못 쓴 답변을 정정·보충할 길(`ANSWERED → ANSWERED`)이 없고, 사용자가 답변에 후속 응답하거나 해결 여부를 표시·재개할 길도 없다 — 추가 질문은 새 문의(24시간 5건 상한 적용)로만 가능하다.
  evidence: `update-inquiry` 전이가 `RECEIVED → IN_PROGRESS → ANSWERED → RESOLVED` 단조이고 사용자 API는 접수·조회뿐이다(리뷰 blind-hunter, 2026-10-10). 대화형 스레드는 SUPPORT-1.0 범위 밖의 새 기능이라 실제 운영 데이터가 쌓인 뒤 판단한다.
- source_spec: none
  summary: Story 3.7 중 운영 관측 연결(운영 서버 Grafana Alloy 상주·relabel allowlist 설정, Grafana Cloud 전송, 외부 synthetic 5분 검사, Grafana 장애 시 로컬 회전 로그·OCI 보조 신호, Alloy heartbeat 실측)을 3.8 배포와 함께 다룬다.
  evidence: 운영 호스트·Grafana Cloud 계정·OCI 자원이 아직 없어 이 저장소에서 연결을 검증할 수 없다. 3.7은 저장소 쪽 계측·redaction·경보 규칙 정의와 promtool 시험까지 하고, 실제 연결은 ARM64 배포(3.8)가 소유한다(2026-10-12).
- source_spec: none
  summary: Story 3.7 AC-3의 Web Vitals(LCP·INP·CLS p75) 수집을 3.7에서 떼어 후속으로 미룬다.
  evidence: 브라우저 계측은 동일 출처 beacon 경로·집계 표·화면 코드가 함께 필요한 독립 배포 단위이고, 측정 라이브러리를 쓰면 런타임 의존 승인이 따로 필요하다. 3.7은 서버 계측 기반에 집중한다(2026-10-12).
- source_spec: none
  summary: Story 3.7 AC-3의 운영 작업시간·월 비용 장부(운영자 입력, 주 2시간·월 30,000원 관찰)를 후속으로 미룬다.
  evidence: 운영자 입력 장부와 4주 관찰 상태 표시는 서버 계측과 독립적으로 배포 가능한 운영자 기능이고, 실제 비용 자료는 운영 호스트(3.8) 뒤에야 생긴다(2026-10-12).
- source_spec: none
  summary: Story 3.7 AC-4의 Bybit API·사건 유형 versioned 계약 정기 재검증(실계정·stub 결과 구분, schema 변화 시 발행 거부·경보)을 후속으로 미룬다.
  evidence: 미인식 사건 유형은 3.3 신호 7이 이미 발행을 막는다. 정기 재검증은 실제 검증 계정과 regional reserved IP가 필요한 Epic 2 실연동 게이트와 묶여 있어 지금 실계정 결과를 만들 수 없다(2026-10-12).
- source_spec: none
  summary: Story 3.8 AC-1의 이미지 공급망 파이프라인(CI의 ARM64 이미지 빌드·SBOM 생성·cosign keyless 서명·GHCR 게시, release 브랜치·태그 한정)을 3.8 핵심에서 떼어 별도 chore로 미룬다.
  evidence: 서명은 레지스트리 push가 있어야 실제로 붙고 외부 게시가 생긴다. 3.8은 배포 스크립트가 release manifest(digest·arm64·서명·SBOM·migration 버전)를 검사하는 gate와 고정 입력 시험까지 하고, 실제 빌드·서명·게시는 레지스트리를 쓸 때 다룬다(2026-10-12 사용자 결정).
- source_spec: none
  summary: Story 3.8로 넘어온 운영 연결·운영 강화 항목(Grafana Alloy 수집 설정과 Grafana Cloud·synthetic 연결, 3.4 AC-6 훈련 증거 기록, production 비밀 공급, nginx 컨테이너 권한 강화, 감사 digest 키 회전)을 3.8 핵심(역할 분리·blue/green 절차·rollback·gate·리허설)에서 떼어 후속 3.8 보완 작업으로 미룬다.
  evidence: 운영 호스트·Grafana Cloud 계정·OCI 자원이 없어 이 저장소에서 연결을 검증할 수 없고, 각 항목은 배포 절차와 독립적으로 배포 가능한 단위다. 기존 항목(3.7 관측 연결·3.4 훈련 증거·키 회전·nginx 권한)은 그대로 열려 있다(2026-10-12 사용자 결정).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-8-blue-green-deploy.md`
  summary: production 진입의 TLS 종단(인증서 발급·갱신, 443 listen, HTTP→HTTPS 이동)을 3.8 blue/green 절차에서 떼어 production 진입 구성 보완과 함께 다룬다.
  evidence: 세션 쿠키가 `Secure`라 실제 운영에는 TLS가 필수지만 Story 3.8의 범위·AC는 슬롯 전환·rollback이고, 인증서 발급은 실제 도메인·운영 호스트가 있어야 검증된다. 3.8은 Nginx 설정 한 벌과 upstream 전환만 바꾼다(2026-10-12).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-8-blue-green-deploy.md`
  summary: 운영 Nginx가 서버의 저장소 체크아웃에서 마운트하는 프런트엔드 산출물(`frontend/dist`)과 Nginx 설정(`bokrilog.conf`)을 release manifest·digest 고정·서명 검사·rollback 대상에 넣는다.
  evidence: 3.8의 blue/green은 API 슬롯과 Worker 이미지만 바꾸고 되돌린다. 화면 번들과 Nginx 본문은 manifest 밖이라 전환 중 화면·API 버전이 어긋날 수 있고 rollback도 API만 된다(3.8 리뷰 blind-hunter, 2026-10-12). 3.8은 이 한계를 `DEVELOPMENT.md`에 명시만 한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-3-8-blue-green-deploy.md`
  summary: 배포 리허설에 실제 호환 창과 실제 JVM 종료 경로를 더한다 — migration 집합이 다른 두 release(old API+new schema 실제 기동, 이전 manifest로의 되돌림), 진행 중 작업을 둔 실제 Worker의 drain-timeout 종료 코드 75, 잠금 상실 76, 첫 배포의 실패 경로.
  evidence: 리허설의 두 이미지는 같은 jar라 schema 차이가 생기지 않고, drain 미완료는 결함 주입(`drain-incomplete`)으로만 덮으며, `WorkerDrainTest`는 빈의 종료 코드만 본다(진입점의 shutdown handler·`Runtime.halt` 경로는 시험 밖). 두 release를 만들려면 시험용 migration을 가진 별도 이미지가 필요하다(3.8 리뷰 verification-gap·blind-hunter, 2026-10-12).
- source_spec: `_bmad-output/implementation-artifacts/spec-verify-windows-utf8-body.md`
  summary: `verify-same-origin.sh`의 `fetch` argv 본문 가드를 `.github/scripts/test-ci-scripts.sh`에서 행동 시험으로 고정한다(curl을 기록 함수로 가리고 거부 형태는 실패·미호출, `@<파일>` 형태는 통과를 단정).
  evidence: 가드는 Linux CI에서 보이지 않는 Windows 결함의 유일한 회귀 그물인데, 정상 실행에서는 어떤 호출도 거부 분기를 지나지 않아 패턴이 깨지거나 지워져도 아무 시험도 실패하지 않는다. CI 스크립트를 건드리는 일이라 이 fix의 Ask First 경계 밖이다(2026-10-12).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-9-encrypted-backup.md`
  summary: CI의 백업 리허설(로컬 실측 약 16분 30초)을 `deploy/production/**`·`backend/**`·`deploy/observability/**` 변경에만 돌리도록 경로별 job 분기를 더하고 `deploy-rehearsal` job timeout(25 → 50분)을 실측으로 보정한다.
  evidence: `classify-changes.sh`는 문서-only와 그 밖 둘로만 가르므로 모든 제품 PR의 임계 경로가 백업 리허설만큼 길어진다. 경로 분기는 CI 판정 구조(`aggregate-results.sh`·필수 검사 집계)를 바꾸는 별도 결정이다(3.9 리뷰 blind-hunter, 2026-10-12).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-9-encrypted-backup.md`
  summary: 백업 저장소 사용률의 분모·분자를 계정 단위 실제 사용량(OCI 계정 합산 무료 한도, 같은 계정의 다른 bucket)으로 보정한다.
  evidence: 지금 `bokrilog.backup.store.usage.ratio`는 백업 object bytes만 분자로, 운영자가 적은 quota를 분모로 쓴다. 같은 계정에 다른 사용량이 생기면 70%·85% 경보가 늦다. 실제 사용량은 OCI·외부 업체의 계정 지표가 필요하고 운영 연결(Alloy·Grafana Cloud) 뒤에야 읽을 수 있다(3.9 리뷰 blind-hunter, 2026-10-12).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-9-encrypted-backup.md`
  summary: 백업 기록 대기열(`pending/`)의 재전송에 행 단위 멱등 키를 더해, 기록은 됐지만 뒤의 행 수 확인이 실패한 묶음이 다시 쓰일 때 `SUCCEEDED` 밖의 행(`FAILED`·`PURGED`·`MEASURED`·감사)이 중복되지 않게 한다.
  evidence: 지금은 `SUCCEEDED` 중복만 서비스가 거절한다. 그 밖의 행은 같은 묶음이 두 번 들어가면 두 번 남는다. 드문 경로(기록 성공 직후 psql 확인 실패)이고 성공 판정·gauge에는 영향이 없지만 감사·이력이 부풀 수 있다(3.9 patch 루프 보고, 2026-10-12).
- source_spec: none
  summary: Story 3.10 AC-5의 새 송신 IP 감지(`BYBIT_IP_REBIND_REQUIRED` 상태 표시·동기화 차단)를 3.10 핵심(manifest·격리 복원·tombstone·RPO)에서 떼어 별도 스토리 보완으로 미룬다.
  evidence: 대시보드·설정 화면과 sync admission을 바꾸는 독립 배포 단위의 사용자 기능이고, 복원 절차와 따로 리뷰·머지할 수 있다(2026-10-13 사용자 결정).
- source_spec: none
  summary: 운영 훈련 증거 기록(3.10 AC-6의 베타 전·월 1회 실제 격리 복원 훈련의 시작·완료·실패·RPO·수동 작업시간·발견 결함 감사)을 3.4 AC-6 정지 훈련·3.8 운영 배포 smoke 증거와 함께 하나의 훈련 증거 원장으로 다룬다.
  evidence: 세 스토리의 훈련 증거가 같은 모양(실제 훈련과 자동시험·리허설 구분, 실패한 훈련을 완료로 기록하지 않음)이고 운영 호스트가 있어야 실제 증거가 생긴다. 3.10은 리허설과 RPO 측정까지 한다(2026-10-13 사용자 결정).
- source_spec: none
  summary: Story 3.10 AC-5의 호스트 전환 절차(OCI A1 확보 8시간 실패 시 PAYG/유료 VPS 전환 runbook)를 3.10 핵심에서 떼어 운영 호스트 준비와 함께 다룬다.
  evidence: 운영 계정·호스트가 없어 절차를 실행으로 검증할 수 없고 저장소 코드 변경이 아니다(2026-10-13 사용자 결정).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-10-isolated-restore.md`
  summary: Story 3.10 AC-3 중 격리 복원 환경의 실제 OIDC 로그인 왕복과 승인된 시험 Bybit 계정의 read-only safe sync 검증을 실연동 게이트와 함께 다룬다.
  evidence: 3.10 격리 복원은 외부 provider 호출을 끄고(빈 OIDC·Bybit 값의 fail-closed) 로그인 설정 존재·자격정보 복호화 가능 수·세션 폐기까지만 확인한다. 실제 왕복은 승인된 시험 계정·reserved IP가 필요한 Epic 1·2 실연동 게이트 소관이다(2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-10-isolated-restore.md`
  summary: tombstone 내보내기의 관측(마지막 성공 시각 gauge·정체 경보·`OnFailure` 알림)을 더해 15분 timer가 멈춘 것을 복원 당일이 아니라 그때 알게 한다.
  evidence: 3.10은 복원 시점에 `tombstones/latest`의 신선도를 검사해 오래된 사본으로는 서비스를 열지 않지만, 평시에는 내보내기 실패가 systemd 종료 코드로만 드러난다. 메트릭 경로는 운영 관측 연결(Alloy) 뒤에야 쓸모가 있다(3.10 리뷰 blind-hunter, 2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-10-isolated-restore.md`
  summary: tombstone digest 키의 회전 절차(겹침 기간 두 키 계산, outbox·외부 사본의 key id 전환, 이전 키 폐기 시점)를 정한다.
  evidence: 3.10은 파일 머리에 key id를 싣고 불일치를 거부할 뿐 회전을 지원하지 않는다. digest는 원문 ID 없이 다시 계산할 수 없어 회전에는 살아 있는 계정 전체를 두 키로 대조하는 별도 절차가 필요하다(3.10 리뷰 blind-hunter, 2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-3-10-isolated-restore.md`
  summary: 복구 manifest를 실제 저장소 상태와 대조하는 검사(manifest의 backup key ID가 남은 generation의 key ID 전부를 덮는지, tombstone recipient의 짝 identity가 있는지)를 정기 점검으로 더한다.
  evidence: 3.10 검사기는 manifest 자체의 형식·평문 비밀만 본다. 저장소와의 대조는 실제 manifest가 저장소 밖에 있어 운영자 환경에서만 돌 수 있다(3.10 리뷰 blind-hunter, 2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-operator-command-commons.md`
  summary: `operations_restriction.reinstated_from`에 색인을 더해, 공지 준비·확정·`GET /api/operator/notices`마다 도는 계보 재귀 CTE(`ASCENT`·`DESCENT`)가 단계마다 순차 탐색하지 않게 한다.
  evidence: V20은 `reinstated_from`에 FK·CHECK만 두고 PostgreSQL은 참조 열을 자동 색인하지 않는다. 지금 제한 행 수가 작아 비용이 무시되지만 되돌림이 쌓이면 공지 경로가 행 수에 비례해 느려진다. 새 migration이 필요해 SPEC Ask First 대상이다(운영 command 공통화 리뷰 blind-hunter, 2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-operator-command-commons.md`
  summary: 배포 전 무장 시험(`OperatorArmingVersionTest`)에 실제 이전 빌드(`serialVersionUID` 5L, 칸 넷)가 직렬화한 바이트를 고정 fixture로 더한다.
  evidence: 지금 시험은 현재 빌드의 stream을 고쳐 옛 모양을 흉내 내고(필드 이름·버전 변경, 없는 클래스, 쓰레기 바이트) 실제 v5 stream을 쓰지 않는다. 옛 클래스(`OperatorCommandApi$Lift` 등)가 지금 빌드에 없어 이전 커밋에서 바이트를 떠 와야 한다(운영 command 공통화 리뷰 blind-hunter, 2026-10-13).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-17-bybit-reconnect.md`
  summary: Bybit source 정책 버전이 첫 연결과 재연결 사이에 바뀐 경우를 정한다 — 계획 없는 같은 계정 재연결은 기준 snapshot의 정책 버전이 새 연결과 달라 계획 조건이 `NOT_ELIGIBLE`인데 그 계정의 기준이 있어 새 첫 확인도 열리지 않으며, 활성 계획의 재연결 뒤 근거는 새 연결 ID와 기준 snapshot의 정책 버전을 한 쌍으로 보인다.
  evidence: 지금 source 정책 버전은 하나뿐이라 재현 경로가 없다(latent). 정책 버전을 올리는 스토리가 재기준(rebase) 허용 조건과 근거 표시를 함께 정해야 한다(2.17 리뷰 edge-case-hunter, 2026-10-14).
- source_spec: `_bmad-output/implementation-artifacts/spec-2-17-bybit-reconnect.md`
  summary: 완료 계획의 첫 종료 후 관측이 고정되기 전에 해제하고 다른 계정으로 연결한 경우, 그 계획의 완료 비교를 끝내 보류로 둘지(이전 계정만 관측할 수 있다) 아니면 관측 불가로 닫을지 정한다. 같은 판단에서 종단 계획의 계획 대상(`planTarget`)이 지금 연결로 대체되는 범위(ACTIVE에만 둘지)도 정한다.
  evidence: 2.17은 다른 계정 연결을 종단 계획만 있을 때 허용하고, 가용성 원인만 `CONNECTION_REVOKED` 대신 연결 불가로 바로잡는다. 관측 보류 자체는 계정 단위 귀속(AR-12)의 결과다(2.17 리뷰 blind-hunter·edge-case-hunter, 2026-10-14).
- source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-verdict-round.md`
  summary: `deferred-work.md`의 `- source_spec` 수와 원장 `### N.` 절 수·상태 표 합계를 대조하는 Docker 없는 검사(`.github/scripts/` + `test-ci-scripts.sh`, 문서-only 경로에서도 도는 자리)를 둔다.
  evidence: 판정 라운드 리뷰 — 2026-10-15 규칙(CLAUDE.md 7번)은 산문이고 141·142 누락처럼 건수 어긋남을 기계가 잡지 못한다. 이 chore는 ci.yml·스크립트를 바꾸지 않는다(항목 36과 이어진다).
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-critical-path.md`
  summary: CI-PLAN §1(job 구조)·§5(변경 분류)에 새 `backup-rehearsal` job과 `backup-restore` 분류(프런트엔드·문서-only PR의 백업·복원 리허설 승인된 비적용)를 as-built 새 버전으로 반영한다(Epic 3 회고 AI-10 범위).
  evidence: 스크립트 머리 주석이 정본으로 인용하는 CI-PLAN §1에는 `backup-rehearsal`이 없고 §5 분류 표에도 백업·복원 분기가 없다. 결정 변경은 기존 문서를 덮지 않고 새 버전으로 발행해야 하므로 이 chore 범위 밖이다(spec-ci-critical-path 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-ad-consent.md`
  summary: 광고 동의 결정 행에 사용자당 빈도 제한(문의의 `INQUIRY_RATE_LIMITED` 같은 장치) 또는 행 수 상한을 둘지 정한다.
  evidence: 결정 행은 추가 전용이고 선택이 바뀔 때마다 새 멱등 키로 한 행씩 쌓인다. 스크립트로 `GRANTED`·`DENIED`를 번갈아 보내면 사용자 하나가 표를 계속 키울 수 있다. 4.1 SPEC은 rate limit을 범위에 두지 않았다(4.1 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-1-ad-consent.md`
  summary: 광고 동의 결정 이력의 보존 기한(동의 증거로 남길 기간과 최소 보존 사이)을 정하고, 기한이 정해지면 조회 경로나 별도 정리로 지난 행을 지운다.
  evidence: 지금 결정 행은 계정 삭제 전까지 영구히 남는다. 문의(3.6)는 해결 뒤 90일 삭제 규칙이 있지만 동의는 기획 문서에 기한이 없고, 개인정보 검토(외부 게이트 G-4)가 정할 몫이다(4.1 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-2-ad-eligibility.md`
  summary: 광고 transition 참조를 한 번만 쓰이게(소비) 하거나 수명을 두어, 오래된 ELIGIBLE 결정의 재전송이 광고 노출을 다시 일으키지 못하게 한다.
  evidence: 4.2의 재전송은 ELIGIBLE이면 관문만 다시 보고 같은 `transition`을 돌려준다(SPEC 계약). `transition_ref`에는 만료도 소비 상태도 없으므로, 4.3이 ELIGIBLE 응답마다 광고를 시도하면 같은 jobId의 재전송이 빈도 제한에 세지지 않는 노출이 된다. 노출 시점의 소비 규칙은 중립 화면·adapter를 만드는 4.3 SPEC이 정해야 한다(4.2 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-2-ad-eligibility.md`
  summary: 광고 자격 후보에 신선도 조건(예: 방금 끝난 수동 동기화, 또는 지금 활성 계획의 최신 수동 성공)을 더할지 정한다.
  evidence: 후보 술어(`PlanSyncRepository.publishedManualSync`)는 purpose·trigger·status·`calculation_id`만 보므로, 종단 계획이나 해제된 연결의 오래된 수동 성공도 처음 판정이면 새 reservation을 얻는다. 빈도 제한이 횟수를 묶지만 "방금 동기화한 결과 뒤의 광고"라는 trigger 의미는 강제되지 않는다. 결과 보기 클릭 흐름을 만드는 4.3 SPEC이 정하기 좋다(4.2 리뷰 blind-hunter·edge-case-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-2-ad-eligibility.md`
  summary: `BOKRILOG_AD_PROVIDER_ENABLED`를 운영 compose 환경 전달과 `deploy/production/preflight.sh` 환경 이름 목록에 연결한다.
  evidence: 지금 이 변수는 `application.yml`에만 있다(기본 `false`). `BOKRILOG_SUPPORT_CONTACT`처럼 개발·운영 compose와 preflight에 오르지 않아 컨테이너로 넘길 수도, 출시 게이트 전에 검사할 수도 없다. 기본값이 꺼짐이라 지금은 안전하고, 실제 광고 계정 승인과 함께 출시 게이트가 다룬다(4.2 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-2-ad-eligibility.md`
  summary: `engagement_ad_attempt` 결정 행(SKIPPED 포함)의 보존 기한과 정리 경로를 정한다.
  evidence: 판정한 수동 동기화마다 행이 남고 계정 삭제 외에는 지우는 경로가 없다. 빈도 판정은 하루 남짓의 이력만, 재전송은 짧은 창만 필요하다. 문의의 90일 정리 같은 선례가 있고, 동의 이력 보존(항목 148)과 함께 개인정보 검토(G-4)가 정할 몫이다(4.2 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-neutral-ad-transition.md`
  summary: `/ad-transition` CSP 후보값(`frame-src`의 SafeFrame `*.safeframe.googlesyndication.com`, 트래픽 품질 `*.adtrafficquality.google` 등)을 실제 GPT 요청으로 실측해 보정하고, 공급자를 켠 스택에서 SHOW 왕복(주소 정리 → gpt.js load → interstitial → 결과)을 브라우저 층에서 확인한다.
  evidence: 4.3은 공급자 문서 기준 후보값을 고정 단정하고 e2e는 공급자 꺼짐 경로만 본다. 저장소 e2e는 `page.route` 대역을 쓰지 않고 실제 광고 계정이 없어 SHOW 경로를 브라우저에서 밟을 수 없다. 실제 request field 검사는 출시 게이트 G-4가 소유한다(4.3 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-neutral-ad-transition.md`
  summary: 광고 단위 경로 형식(`^/[0-9]+(/[A-Za-z0-9_.-]+)+$`)이 GAM MCM 경로(`/<parent>,<child>/unit`)를 거부하는지 실제 계정 형식으로 확인하고, 필요하면 백엔드·프런트엔드 두 사본을 공유 fixture 하나로 묶어 함께 넓힌다.
  evidence: 지금 형식이 맞지 않으면 기동 거부 없이 공급자만 조용히 꺼진다(`PROVIDER_DISABLED`). 정규식은 4.3 SPEC의 승인된 계약이라 이 스토리에서 바꾸지 않았고, 실제 network code 형식은 계정 승인(G-4) 때 정해진다(4.3 리뷰 blind-hunter, 2026-10-15).
- source_spec: `_bmad-output/implementation-artifacts/spec-4-3-neutral-ad-transition.md`
  summary: 주입 origin e2e `ad-consent.spec.ts:71`이 간헐적으로 로그인 직후 `/settings`를 미인증 진입 화면으로 열어(광고 동의 region 없음) 5초 시간초과로 실패하는 원인을 trace로 찾는다.
  evidence: 4.3 로컬 검증에서 전체 실행 다섯 번 중 두 번(둘 다 스택을 새로 띄운 뒤 첫 실행) 실패했고, 같은 스택의 재실행·새 스택 재현·4.3 spec을 뺀 실행은 모두 통과했다. 실패 trace가 뒤 실행에 덮여 원인(세션 응답·쿠키·기동 직후 지연)을 가리지 못했다. 4.3은 설정·인증 경로를 바꾸지 않았다(4.3 검증, 2026-10-15).
- source_spec: none
  summary: Story 4.4 AC-4의 광고 이벤트 분리 계측(요청·실노출·닫기·skip/failure 사유·저장 결과 도달)을 동일 출처 beacon 경로와 `TelemetryAllowlist` 행으로 더한다.
  evidence: 4.4를 범위 분할하며 떼었다(사용자 결정 [S], 2026-10-16). 브라우저 → 서버 계측 경로가 아직 없어 beacon 경로·집계·allowlist 설계가 함께 필요한 독립 배포 단위이고, Web Vitals 수집(항목 122)과 같은 경로를 공유하므로 함께 설계하는 편이 낫다. 실노출·닫기는 4.4의 fail-open 이벤트(GPT callback)를 원천으로 쓴다.
- source_spec: none
  summary: Story 4.4 AC-3 후반의 Web Vitals 수집(LCP·INP·CLS p75)과 목표를 위협할 때 광고만 끄는 성능 kill switch를 만든다.
  evidence: 4.4를 범위 분할하며 떼었다(사용자 결정 [S], 2026-10-16). 브라우저 측정(직접 구현 또는 런타임 의존 승인)·beacon·집계 분모와 기간·경보 규칙이 필요하고, 항목 122(3.7이 미룬 Web Vitals 수집)가 4.4를 결정 지점으로 지목했다. 광고만 끄는 수단은 이미 있다 — 운영 command `restrict-advertising`(3.2)과 4.2·4.3 관문의 `ADVERTISING` 판독이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-4-4-ad-fail-open.md`
  summary: Windows 로컬 Vitest에서 운영자 lazy chunk를 기다리는 시험(`App.test.tsx` 운영자 경로, `OperatorScreen`·`OperatorNotice`·`SettingsConnectionRevoke`)이 `findBy` 기본 대기 1초를 넘겨 간헐 실패하는 원인을 고친다.
  evidence: 4.4 검증에서 `develop`(`edac317`) 그대로도 같은 실패가 났다 — 전체 실행 1471건 중 3건, `App.test.tsx`를 다른 두 파일과 함께 돌리면 3회 중 3회(1030~1054ms 시간초과). 파일 하나씩 돌리면 모두 통과하고 CI `frontend-quality`(Linux)는 통과한다. 4.4는 이 파일들과 운영자 화면을 바꾸지 않았다(4.4 검증, 2026-10-16).
- source_spec: `_bmad-output/implementation-artifacts/spec-epic-4-retro-docs-and-verification.md`
  summary: 백엔드 전체 빌드 중에만 나는 계획 종단 시험의 간헐 실패 둘 — `PlanEndTest` 16건의 409 `PLAN_END_UNAVAILABLE`·`RESULT_TOO_RECENT`, `PlanCompletionTest.laterSyncsOnlyMoveThePostEndValue`의 작업 `FAILED` — 의 원인(부하 아래 시각 어긋남 또는 순차 시험 중 Worker 경합 추정)을 찾아 고친다.
  evidence: 2026-10-17 Epic 4 회고 AI-2·AI-3 chore 검증에서 첫 `backend/gradlew --project-dir backend build`가 이 16건으로 실패했고, 같은 코드의 재실행과 `PlanEndTest` 단독 실행(26/26)은 통과했다. patch 뒤 첫 전체 빌드에서는 `PlanCompletionTest` 1건이 실패했고 단독 `--rerun`과 두 번째 전체 빌드는 통과했다. 이 chore는 계획 종료·완료 경로를 바꾸지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-session-attribute-upsert.md`
  summary: 세션 행이 동시 로그아웃·만료 정리로 먼저 지워진 뒤 같은 세션의 다른 요청이 속성을 저장하면 FK 위반(500)이 날 수 있는 경합을 미인증 응답으로 접을지 정한다.
  evidence: upsert는 같은 속성 첫 INSERT의 기본 키 충돌만 닫는다. 세션 행이 사라진 뒤의 속성 INSERT는 `SPRING_SESSION_ATTRIBUTES` FK에서 실패하고, 동시 삭제 뒤 UPDATE는 0행이 된다(2026-10-17 fix 리뷰 edge-case-hunter·blind-hunter). 재현 시험은 아직 없다.
- source_spec: `_bmad-output/implementation-artifacts/spec-session-attribute-upsert.md`
  summary: 세션 확인이 500(일시 오류)이면 화면이 "로그인 상태가 아닙니다"(미인증)로 그리는 대신 일시 오류임을 구분해 안내하는 처리를 정한다.
  evidence: PR #123 CI 실패의 화면 기록에서 서버 500이 미인증 안내로 보였다(run 39039915046). fail-closed 자체는 맞지만 문구가 원인을 잘못 가리킨다(2026-10-17 fix 리뷰 blind-hunter). 재시도로 덮는 것은 SPEC이 금지했다.
- source_spec: `_bmad-output/implementation-artifacts/spec-erasure-audit-lock.md`
  summary: 계정 삭제가 커밋된 뒤 원장에 붙는 운영자 거절·실패 행(롤백 뒤 별도 트랜잭션, 예: `ACCESS_REVOKED`)이 삭제된 운영자의 `actor_user_id`를 다시 붙이지 않게 할지 정한다 — 계정 상태를 보고 수행자 귀속을 비울지, 또는 Epic 5 삭제 완료 검사(5.2·5.4)가 잡게 할지.
  evidence: 원장 잠금 직렬화(이 fix)는 삭제보다 먼저 잠금을 쥔 쓰기만 덮는다. 거절 행은 삭제 커밋 뒤 잠금을 얻어 append하며 그 경로에 계정 상태 판정이 없다(2026-11-03 fix 리뷰 blind-hunter, SPEC Ask First로 범위 밖). 지금은 사용자 삭제 경로가 없어 잠재 결함이다.
- source_spec: none
  summary: `backend-quality`의 시험을 Gradle `maxParallelForks` 2~3으로 JVM 여럿에 나눠(JVM마다 PostgreSQL 컨테이너 하나, 포트는 무작위) 15분을 8~10분으로 줄인다 — `spec-backend-test-speed.md` Never·`spec-ci-critical-path.md` Ask First의 병렬화 경계를 새 SPEC Intent에서 다시 여는 결정이 함께 필요하다.
  evidence: 2026-11-03 CI 시간 분석에서 PR wall-clock 26~30분의 임계 경로가 `backend-quality`(Gradle test 14분 25초, 상위 8개 파이프라인 시험 클래스가 12.6분) → `backup-rehearsal`(12분)이었다. jar 생성 job 분리(첫 목표)와 독립적으로 머지 가능해 단일 목표 규칙으로 분리했다.
- source_spec: none
  summary: 배포·백업·복원 리허설 셋을 백엔드 변경 PR의 필수 경로에서 빼고 `deploy/**`·Dockerfile 변경 PR과 `develop` push에서만 돌릴지 정한다 — `spec-ci-critical-path.md` Always가 "관련 경로 허용 목록은 새 경로를 조용히 건너뛴다"로 거부한 방식이라 fail-closed 분류로는 정의할 수 없는 정책 결정이다(CI-PLAN §5 표의 "backend 변경 → backend 품질·관련 통합" 해석 포함).
  evidence: 2026-11-03 CI 시간 분석. 리허설은 jar 자체를 돌리므로 "백엔드 변경인데 리허설과 무관하다"를 기술적으로 가를 수 없고, 3.9 defer(경로별 job 분기)도 같은 이유로 미해소다. jar 생성 job 분리(첫 목표)와 독립적이라 단일 목표 규칙으로 분리했다.
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-backend-assemble-job.md`
  summary: jar를 내려받는 소비 job 넷(same-origin·배포·백업·복원 리허설)이 내려받은 `bokrilog-backend.jar`의 SHA-256을 생산자 output(`needs.backend-assemble.outputs.jar-sha256`)과 대조하지 않는다 — CI-PLAN §8의 "통합 job이 SHA와 checksum을 검증한다"는 artifact 저장소 왕복에 대해 여전히 가정이다.
  evidence: 이 변경 전에도 소비 job은 내려받은 jar를 검증하지 않았고(`backend-quality`가 올린 것을 그대로 썼다), 이번 digest 판정은 생산자 두 쪽(`backend-assemble`·`backend-quality`)의 일치를 `ci-required`에서 사후에 볼 뿐이다. 내려받기 뒤 `sha256sum -c` 한 단계면 닫히지만 판정은 `.github/scripts/`의 스크립트와 `test-ci-scripts.sh`의 단정을 요구해 네 job의 단계 목록 계약을 함께 바꾸는 별도 범위다(2026-11-03 리뷰 blind-hunter).
- source_spec: none
  summary: 프런트엔드의 동일 출처 JSON POST와 JSON 판독(`readJson`·`objectOf`·`fieldsOf`)을 helper 하나로 모은다(Epic 4 회고 AI-8 `epic-4-retro-item-47`의 ② 프런트엔드 묶음).
  evidence: 2026-11-03 AI-8을 세 목표로 나눠([S]) 이번 작업은 백엔드 컨트롤러 helper·`IdempotencyKey.CONFLICT_CODE`만 한다. 지금 POST를 직접 만드는 곳이 17개 파일, JSON 판독 사본이 여섯 파일에 아홉 개다(`session/adTransition.ts`·`useAdConsent.ts`·`operatorApi.ts`·`useSupportInquiries.ts`·`bybitConnection.ts`·`useServiceNotices.ts`).
- source_spec: none
  summary: 광고 시험 공용 helper(`admitManual`·`grant`·자격 행 삽입·`assertNotFound`)를 한 곳으로 모으고 engagement 모듈의 바깥 의존 허용 목록을 `ModuleBoundaryTest`로 강제한다(Epic 4 회고 AI-8 `epic-4-retro-item-47`의 ③ 시험·경계 묶음, 회고 A-2).
  evidence: 2026-11-03 AI-8을 세 목표로 나눠([S]) 이번 작업은 백엔드 컨트롤러 helper만 한다. `AdEligibilityTest`·`AdTransitionTest`의 `admitManual`은 이미 갈라졌고(접수 시각을 당기는가), `engagement/package-info.java`가 적은 바깥 의존(sync·operations·identity)을 강제하는 시험이 없다.
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-test-forks.md`
  summary: 시험 fork 분할이 의존하는 fork 격리 불변식(`DEFINED_PORT`·고정 루프백 포트·`withFixedExposedPort`·`withReuse`·`System.setProperty`·공유 파일 경로 금지)을 `src/test/java`를 훑는 시험으로 고정한다 — 저장소의 소스 스캔 시험(`ModuleBoundaryTest`·`OperatorAuditTest` 류)은 모두 `src/main/java`만 본다.
  evidence: 2026-11-03 fork 분할 리뷰(blind-hunter·edge-case-hunter·verification-gap 셋 다). 지금 시험 묶음은 조사로 규칙을 만족하지만(`RANDOM_PORT` 2·`NONE` 8, 루프백 서버 셋 모두 포트 0, 파일은 `@TempDir` 하나) 검사가 없어 첫 위반은 다른 fork와 겹칠 때만 나는 간헐 실패(deferred 159 모양)로 드러난다. SPEC Always가 시험 코드 변경을 막아 이 chore에서 더하지 않았다.
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-test-forks.md`
  summary: CI에서 시험 fork가 실제로 셋 떴는지를 기계가 판정하게 한다 — `backend-quality` 로그의 `[test-forks]` executor 줄을 `.github/scripts/`의 스크립트가 세어 2개 미만이면 실패시키고, `gradle.properties`에 `org.gradle.workers.max`가 생기지 않는지 `test-ci-scripts.sh`가 단정한다.
  evidence: 2026-11-03 fork 분할 리뷰(verification-gap). `org.gradle.workers.max=1`이나 뒤에 오는 `maxParallelForks` 재설정이 fork를 1로 접어도 같은 1,610건이 통과하고 아홉 job이 green이라 임계 경로가 조용히 14분으로 돌아간다. 이 chore는 fork별 시험 수·길이를 로그로만 보이게 했고(관측), 판정 스크립트·`test-ci-scripts.sh` 변경은 SPEC Always 밖이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-1-account-deletion-request.md`
  summary: 삭제 진행 화면에 지원(문의) 경로가 없다 — 삭제 중 계정은 문의 접수(`POST /api/support/inquiries`)가 `DELETION_IN_PROGRESS` 거부로 막혀 있어, Epic 5 UX 패턴의 "진행 중 지원 경로를 함께 제공"을 5.1 화면이 지키지 못한다.
  evidence: 2026-11-05 Story 5.1 1차 리뷰(blind-hunter). 진행 화면은 상태·다시 확인·로그아웃만 제공한다. 삭제 중 계정에 열 수 있는 지원 경로(무세션 연락처 고지, 삭제 중에도 허용되는 문의 접수 중 무엇인지)는 실패 범위·재시도를 실제로 만드는 5.2와 완료 안내의 5.4가 정할 결정이라 이 스토리에서 뗐다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-1-account-deletion-request.md`
  summary: 삭제 요청(`identity_deletion_request`, 삭제 진행 상태)은 서비스 DB에만 있고 tombstone outbox·외부 사본에 남지 않아, 요청 뒤 실행(5.3) 전에 요청 이전 generation으로 복원하면 계정이 요청 기록 없이 `ACTIVE`로 돌아온다 — 복원 경로에서의 요청 내구성(외부 기록·복원 적용 시 재진입)을 5.2·5.3이 결정한다.
  evidence: 2026-11-05 Story 5.1 2차 리뷰(blind-hunter). `restore-apply`는 tombstone(완료된 삭제)만 대조해 재삭제하고(3.10), 5.1의 요청 행은 tombstone outbox에 쓰이지 않는다. RPO 24시간 안에 접수된 요청은 그 사이 장애·복원에서 사라질 수 있고, 진행 화면은 그 사실을 알 수 없다. 완료된 삭제를 되살리지 않는다는 화면 문장 자체는 3.10 tombstone으로 성립한다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-2-account-data-erasure.md`
  summary: 계정 삭제 실행의 운영 관측이 없다 — `재시도 필요`로 멈춘 실행 수, 대기·진행 실행의 나이, production·tombstone 키 없음으로 집히지 않는 요청을 운영자가 볼 메트릭(`TelemetryAllowlist` 행)과 경보 규칙(`deploy/observability/alerts.yml`)을 더한다.
  evidence: 2026-11-05 Story 5.2 1차 리뷰(blind-hunter). 실행 로그는 단계 code·결과 code·예외 단순 이름만 남기고(사용자·요청 식별자 없음) 새 메트릭·경보가 없어, 부분 실패와 지연은 사용자 진행 화면에서만 보인다. SPEC은 관측을 범위에 넣지 않았다(새 계측은 표에 행을 먼저 더해야 하는 3.7 규칙).
- source_spec: `_bmad-output/implementation-artifacts/spec-5-2-account-data-erasure.md`
  summary: 삭제 실행·단계 기록(`identity_deletion_run`·`identity_deletion_step`, V31)의 보존 기한과 정리 정책을 정한다 — 운영 완료 시각의 근거로 영구히 남는다.
  evidence: 2026-11-05 Story 5.2 1차 리뷰(blind-hunter). 두 표는 생명주기 행을 FK로 가리키는 삭제 기록이라 계정 삭제 뒤에도 남고(identity 잔여에서 제외), 보존 기한·정리 주체가 SPEC과 정책 어디에도 없다. 백업 파기 추적(5.3)·완료 안내(5.4)가 이 기록을 읽을 수 있어 그 결정과 함께 볼 지점이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-3-backup-destruction-tracking.md`
  summary: 결과 행 없이 끝난 백업 실행(SIGKILL·전원 상실)의 generation이 `generations/`에 실제로 올랐는지 V24 기록만으로는 알 수 없어, 뒤 실행이 기록된 뒤에는 그 generation을 백업 파기 판정에서 막지 않는다 — 저장소 목록 조회로 그런 generation의 존재·파기를 확인하는 판정 근거를 실저장소 게이트에서 정한다.
  evidence: 2026-11-06 Story 5.3 1차 리뷰(edge-case-hunter). `backup.sh`는 SIGTERM이면 `FAILED INTERRUPTED`를 남기지만 SIGKILL은 결과도 대기열 행도 남기지 않는다. 이동 뒤·기록 전에 죽은 generation은 저장소에 남아 29일 파기가 `PURGED`를 쓰기 전까지 계정을 담을 수 있는데, 판정은 저장소를 조회하지 않는다(SPEC Ask First — 저장소 직접 조회).
- source_spec: `_bmad-output/implementation-artifacts/spec-5-3-backup-destruction-tracking.md`
  summary: 백업 대기열(`pending/`)의 결과 행이 계속 기록되지 못한 채 다음 실행의 `STARTED`가 기록되면(`flush_pending` 실패는 경고뿐), 백업 파기 판정이 그 generation을 결과 없는 실행으로 보고 막지 않는다 — 대기열이 남으면 새 실행을 시작하지 않거나 판정이 대기열 상태를 알게 하는 규칙을 정한다.
  evidence: 2026-11-06 Story 5.3 2차 리뷰(edge-case-hunter). 판정은 "결과 없는 STARTED는 그 뒤 STARTED가 기록될 때까지 막는다"를 대기열이 다음 실행의 STARTED보다 먼저 기록된다는 `backup.sh` 순서에 기대는데, 그 순서는 대기열 기록이 성공할 때만 성립한다. `backup.sh` 단계 순서를 바꾸는 것은 SPEC Ask First다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-3-backup-destruction-tracking.md`
  summary: `PURGED` 기록이 끝내 생기지 않는 영향 generation(손으로 지운 generation, 복원 뒤 이미 저장소에서 사라진 generation)이 든 tombstone은 `OVERDUE`로 영구히 남고 critical 경보가 계속 운다 — 운영자가 저장소를 확인한 뒤 저장소별 파기를 표시하는 command나 runbook을 정한다.
  evidence: 2026-11-06 Story 5.3 2차 리뷰(blind-hunter). 만료 삭제는 `DESTROYED` 행만 지우므로(1차 리뷰 patch) 그런 행은 DB와 외부 사본에 남고, 판정은 V24 기록만 보며 저장소를 조회하지 않는다(SPEC Ask First). 해소 경로는 운영 command(3.1 표면) 또는 실저장소 게이트의 절차다.
- source_spec: `_bmad-output/implementation-artifacts/spec-plan-verification-final-sweep-race.md`
  summary: `pg_sleep` 지연 trigger(`slowPageStores`) + 진행 대기 + `expireLease`의 시간 기반 순서로 "수집 중 lease 상실"을 만드는 백엔드 시험들이 남아 있다 — `PlanVerificationTest`의 `aReclaimedContinuationGoesOnFromItsCompleteWindows`·`theThirdInterruptionEndsTheAttemptAsInterrupted`·`aVerificationReclaimedWhileCollectingPublishesTheNextVersion`, `BybitConnectionRevocationTest`·`BybitReconnectionTest`·`PlanEndTest`의 `slowPageStores` 사용 시험. 만료 전에 holder가 그 단계를 벗어나면 같은 모양으로 간헐 실패할 수 있으니, 같은 잠금 문 방식(`FinalSweepDoor`)으로 옮길지 정한다.
  evidence: 2026-11-06 fix 1차·2차 리뷰(blind-hunter). 이 fix는 SPEC Never로 다른 시험을 바꾸지 않았다. `aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal`이 바로 이 구조로 CI(run 42197758258 2차 시도)에서 실패했다. `PlanEndTest`는 159(전체 빌드에서만 나는 계획 종단 시험 간헐 실패, 원인 미확정)의 대상이라 이 구조가 159의 원인 후보인지 함께 본다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-4-deletion-completion-verification.md`
  summary: 전체 삭제 완료 판정자의 sweep(`awaitingDeletionCompletion`·`destroyedAmong`)이 운영 완료 실행 전부를 상한 없이 읽고 `IN (:list)`로 넘기며, `DESTROYED` tombstone이 판정 전에 만료 삭제되면 그 실행은 `OPERATIONAL_DATA_ERASED`에 영구히 남는다 — 묶음 상한과 고착 실행의 메트릭·경보를 정한다.
  evidence: 2026-11-06 Story 5.4 1차 리뷰(blind-hunter·edge-case-hunter). 파기가 끝나지 않는 generation(176)이나 법정 보존 항목이 있는 동안 목록이 커지고 바인드 수 상한에 닿을 수 있다. 판정자가 보존 기한(운영 완료 + 35일) 넘게 멈추면 근거 행이 사라진다. 삭제 실행 메트릭·경보(172)와 같은 주인이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-5-4-deletion-completion-verification.md`
  summary: 복원 리허설의 삭제 표본 seed가 plan의 계획 정의·종료·완료 행과 sync의 공식 계산 결과(`sync_plan_calculation` 계열)를 넣지 않아, `residual_plan=0`·sync 잔여 0 단정이 그 표들의 재삭제를 증명하지 못한다 — 실제 흐름(첫 스냅샷→발행)으로 표본을 만들거나 seed를 넓힌다.
  evidence: 2026-11-06 Story 5.4 1차 리뷰(blind-hunter). 잔여 세기는 카탈로그로 모듈 표 전부를 보지만 행이 없던 표는 재삭제가 검증되지 않는다. 손으로 쓴 열 목록 SQL은 스키마 변경에 약해 로컬 리허설 없이 넓히면 CI 위험이 크다.
- source_spec: `_bmad-output/implementation-artifacts/spec-epic-5-retro-docs-and-verification.md`
  summary: `verify-same-origin.sh`가 tombstone digest 키 값의 로그 누출을 보지만, 그 키를 실제로 쓰는 삭제 실행(tombstone 기록·digest 계산)은 스크립트가 끝난 뒤의 주입 origin e2e(`account-deletion.spec.ts`)에서 처음 돈다. 그래서 삭제 왕복 뒤의 백엔드·Nginx 로그를 같은 누출 판정으로 다시 보는 검사가 없다. e2e 뒤 compose 로그 누출 판정을 `.github/scripts`로 둘지, 스크립트 안에 짧은 삭제 왕복을 둘지 정한다.
  evidence: 2026-11-10 Epic 5 회고 AI-2·AI-3 chore 1차 리뷰(verification-gap). 스크립트 안에는 계정 삭제를 실행하는 단계가 없고, `ci.yml`의 e2e 단계 뒤에는 compose 정리·진단 업로드만 있다. 앱의 `ConfiguredSecrets`·`LogSecretGuard`는 이 키를 감지하지만, 동일 출처 스택에서 그 경로를 지난 로그를 보는 판정은 없다. 한글 문구의 curl 왕복은 CP949 문제(246)로 스크립트에 두지 않기로 했다.
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-flaky-run-verdicts.md`
  summary: develop push run 42134981077(PR #141 머지 `f95ebb5`)의 `same-origin-integration` → `Story 1.1 회귀 (Nginx origin)`에서 `first-snapshot.spec.ts:657`(비-USDT 추정 환산·보류 목록 시험)이 `connectAndSavePlan`(`support/firstPlan.ts:60`)의 첫 값 `1,234.56 USD` 대기(기본 5초)에서 실패했다 — 재실행하지 않았고 원인이 확정되지 않았다. 첫 확인 성공 문구 뒤 값 표시가 늦은 것인지(새 스택·직렬 suite의 앞 시험 부하), 다른 사용자의 값이 섞인 것인지 가르고, 대기 방식이나 fixture를 고칠지 정한다.
  evidence: 2026-11-10 Epic 5 회고 AI-4 chore가 `ci-run-verdicts.md`에 소급 기록(판정 `미조사`). 72건 통과·1건 실패(3.8분), 같은 커밋의 PR #141 run은 green이었고 다음 develop push(42142542381)도 green이다. 진단 artifact `same-origin-diagnostics-42134981077-1`(trace·스크린샷·compose 로그)은 7일 보존이라 2026-11-13T00:23:43Z에 만료된다. job 로그는 저장소 보존 90일(2027-02-04 무렵)까지 남는다.
- source_spec: `_bmad-output/implementation-artifacts/spec-ci-flaky-run-verdicts.md`
  summary: develop push run 42549803177(PR #146 머지 `2441364`)의 `backup-rehearsal` → `백업 리허설`이 `3. 단계 실패 catalog(gen_20260102)` 머리를 낸 뒤 37분 동안 출력 없이 멈춰 단계 상한 40분에 끊겼다 — 재실행하지 않았고 원인이 확정되지 않았다. catalog 결함 주입 경로(`age -d | pg_restore --list` 실패)에서 무엇이 기다리는지(배경 실행 + `wait`, 파이프의 한쪽 정지) 가르고, 단계 상한에 끊길 때도 진단이 남게 할지 정한다.
  evidence: 2026-11-10 Epic 5 회고 AI-4 chore가 `ci-run-verdicts.md`에 소급 기록(판정 `미조사`). 앞선 dump·encrypt·tamper-oci 결함 주입은 각 20초 안에 통과했다. 진단 artifact `backup-rehearsal-diagnostics-42549803177-1`(보존 7일)은 올라가지 않았다 — 단계가 상한에 끊겨 리허설 스크립트가 진단 디렉터리를 채우지 못했고 업로드 단계가 `No files were found`로 경고만 냈다. 남은 증거는 job 로그(저장소 보존 90일, 2027-02-07 무렵까지)뿐이다. 다음 develop push(42563422690)의 같은 job은 green이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-test-container-context-lifecycle.md`
  summary: Windows 로컬 전체 백엔드 build에서 Java 루프백 소켓이 간헐적으로 `Bad address`(`listen`·`connect`)로 실패해 로컬 전체 green을 받을 수 없다 — 원인(보안·네트워크 필터 소프트웨어 등)을 가르고 로컬 검증 절차를 정한다.
  evidence: 2026-11-10·11 로컬 Windows 11 build 관측. 2026-11-11 `AuthDeletionPathTest` 컨텍스트 로드가 `Bad address: listen`으로 실패했다(그때는 공유 컨테이너가 함께 멈춰 연쇄 `Connection refused`가 났고, 이 SPEC이 그 연쇄를 닫았다). 이 SPEC 검증의 전체 build(9분 48초, 1,774건 중 288건 실패, `Connection refused` 0건)에서는 `ActionGrantTest`·`BybitConnectionTransactionTest`·`OperatorRecoveryTest`의 컨텍스트 로드가 `Bad address: listen`, `FirstSnapshotTest` 1건이 `Bad address: connect`였고 나머지는 같은 컨텍스트 구성의 Spring 실패 임계였다. 대상 시험만 돌린 실행에서도 `PlanEndTest`의 컨텍스트 로드가 `Bad address: listen`으로 실패한 적이 있다. 같은 develop 코드(`df5a9c6`)의 CI는 green이고, 이 PC의 소켓 수·동적 포트 범위는 정상이다.
- source_spec: `_bmad-output/implementation-artifacts/spec-lease-loss-test-doors.md`
  summary: 운영 코드(`src/main/java`)에 두 정수 advisory 키(`pg_locks` `objsubid=2`)가 생기지 않게 막는 소스 스캔 — 시험 문 키와 계획 잠금 대기 helper(`awaitBlockedOnPlanLock`·`awaitPlanLockReleased`의 `objsubid<>2` 제외)가 그 전제에 기댄다.
  evidence: 2026-11-11 lease 상실 시험 잠금 문 fix 리뷰(blind-hunter). 지금 운영 advisory 잠금은 모두 bigint 하나(`hashtextextended`, `objsubid=1`)라 전제는 참이지만 그것을 고정하는 검사가 없어, 운영 코드에 두 정수 키가 생기면 계획 잠금 대기 helper가 그 잠금을 조용히 놓친다. 운영 소스 호출 꼴이 여럿(`xact`·session·`shared`)이라 이 fix 범위(시험 코드만)에서 스캔 규칙을 정하지 않았다.
