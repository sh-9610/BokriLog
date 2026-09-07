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
