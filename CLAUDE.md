# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. BMAD 기획 산출물은 완료됐고 Epic 1~3과 그 회고, RECONNECT 보완 스토리(Story 2.17)가 끝났고, Epic 4(광고 동의·자격과 빈도 제한·중립 화면의 광고 시도·fail-open 4개와 보완 Story 4.5 광고 이벤트·Web Vitals 계측과 성능 kill switch)와 그 회고도 끝났으며 다음은 남은 Epic 4 회고 액션과 Epic 5다. 제품 코드는 `frontend/`(React/Vite 정적 SPA), `backend/`(Spring Boot 최소 실행 기반), `deploy/`(Nginx 동일 출처 구성)로 이뤄져 있다. 절차와 명령의 상세는 `DEVELOPMENT.md`가 소유하고, 이 파일은 세션이 바로 일을 시작하는 데 필요한 규칙과 현재 지점만 담는다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다. 브랜치명·커밋 subject·코드 식별자만 영문이다. 코드 주석도 한국어다 — 새로 쓰거나 고치는 주석은 한국어로 쓰고, 기존 영어 주석은 그 파일을 고칠 때 손대는 범위에서만 바꾸며 주석만 바꾸는 일괄 치환은 하지 않는다(Epic 2 회고 AI-10, 2026-10-06 결정).

## 세션 시작 시 확인 순서

1. `git status`와 `git log --oneline -5`로 현재 브랜치가 `develop`이고 깨끗한지 본다. 사용자 체크아웃은 항상 `develop`에 둔다.
2. `_bmad-output/implementation-artifacts/sprint-status.yaml`에서 `in-progress`·`review` 스토리가 있는지 본다. 있으면 그 스토리의 `spec-<n>-<m>-<slug>.md` frontmatter `status`를 함께 확인한다.
3. 아래 `## 현재 진행 지점`과 `_bmad-output/implementation-artifacts/epic-<n>-context.md`(Epic 1~4는 닫혔다. `epic-5-context.md`는 아직 없고 Epic 5 첫 스토리를 `bmad-build`로 시작할 때 컴파일된다)로 다음 착수 스토리를 확정한다. 남은 회고 액션은 `sprint-status.yaml`의 `action_items` 중 `open`인 것으로 확인한다.
4. 스토리를 시작하거나 코드를 바꾸는 요청이면 `bmad-build` 스킬로 진행한다. 질문·조사·문서 읽기는 스킬 없이 답한다.

## BMAD 스킬 실행

이 저장소의 작업은 `.claude/skills/bmad-*` 스킬로 진행한다. 각 스킬의 `SKILL.md`는 렌더링 명령 하나만 담고 있다.

- `SKILL.md`가 지시하는 `render_skill.py` 명령을 그대로 한 번 실행하고, 출력된 절대경로 `workflow.md`를 읽어 따른다.
- 렌더링이 실패하면 명령 출력을 보고하고 중단한다. `.claude/skills/` 아래의 워크플로 원본을 직접 읽어 대신 실행하지 않는다.
- `.claude/skills/`(Claude Code)와 `.agents/skills/`(Codex)는 같은 설치본에서 생성된 동일 파일이다. 스킬 파일을 손으로 고치지 않는다. 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 사용한다.
- `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성한다. 직접 수정하지 않는다.

### 스토리·변경 작업의 표준 흐름

`bmad-build`가 다음 순서를 강제한다. 단계를 건너뛰지 않는다.

1. **SPEC 작성** — `_bmad-output/implementation-artifacts/spec-<slug>.md`. 스토리는 `spec-1-3-<slug>.md`처럼 스토리 번호로 시작하고, chore·fix도 SPEC을 쓴다(예: `spec-ci-foundation.md`). SPEC의 `## Intent`·`## Boundaries & Constraints`는 사람이 소유하며 승인 뒤 고치지 않는다.
2. **CHECKPOINT 1(Intent 승인)** — 사용자 승인을 받기 전에는 구현하지 않는다. SPEC 토큰 초과 경고가 나오면 사용자는 보통 `[K] 유지`를, 도구 선택 안이 여럿이면 추천안을 고른다.
3. **구현** — 아래 `## 코드 규칙`을 지킨다. `bmad-build`가 sprint-status를 `in-progress`로 올리고 에픽을 `in-progress`로 든다.
4. **리뷰** — 리뷰 서브에이전트를 병렬로 돌리고 결과를 `patch`·`defer`·`reject`로 분류한다. `defer`는 `deferred-work.md`에 **추가만** 한다. patch 루프와 2차 리뷰의 결과는 SPEC `## Spec Change Log`에 한 줄씩 적고, `review_loop_iteration`은 리뷰 전체를 다시 돌린 2차 리뷰에만 올린다(Epic 2 회고 AI-13, 2026-10-06 결정).
5. **present** — SPEC `status: done`, sprint-status `review`. 여기서 스킬은 끝난다.
6. **PR** — 토픽 브랜치를 push하고 `develop`으로 PR을 연다. `ci-required`가 green이면 `Create a merge commit`으로 머지한다(`gh pr merge --merge`).
7. **done 전환** — 머지 뒤 `chore/sprint-status-<n>-<m>-done` 브랜치에서 sprint-status 한 줄을 `done`으로 바꾸는 docs PR을 따로 올린다(PR #3, #10 패턴). 문서-only 경로라 `changes → ci-required`만 돌고 1분 안에 끝난다. 스토리·chore·fix의 done 전환 PR(done 전환 PR이 없는 작업이면 그 작업의 PR 자체)은 세 부류의 defer 판정을 `deferred-work-resolutions.md`에 함께 적는다 — 그 작업이 `deferred-work.md`에 append한 defer, 그 작업이 해소한 defer, 그 작업을 결정 지점으로 지목한 defer(결정하지 못하고 지나갔으면 미해소·주인 상실로라도 판정). 항목별 절과 상태 표·건수를 같은 PR에서 맞추고 판정을 다음 라운드로 넘기지 않는다(Epic 1 회고 액션 8(`epic-1-retro-item-8`)·Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`)).

토픽 브랜치 작업은 사용자 체크아웃을 옮기지 않고 별도 `git worktree`에서 하고, 머지 뒤 worktree와 브랜치를 모두 지운다.

### 커밋·푸시·머지의 자율 범위

- 커밋·푸시는 사용자가 요청할 때만 한다.
- "진행해줘"·"병합하는 데 문제가 없다면 진행해줘"처럼 명시적 진행 지시가 있고 `ci-required`가 green이며 충돌이 없으면 push → PR → 머지까지 한 번에 진행한다. 사용자는 PR 페이지에서 사후 검토한다.
- "확인해줘"류 지시면 PR까지만 열고 머지는 묻는다.
- 실패한 CI를 재시도로 덮지 않는다. 원인을 찾아 고친 커밋을 올린다.

## 코드 규칙

세 디렉터리 공통 불변식이다. 근거는 `ARCHITECTURE-SPINE.md`의 AD-1~AD-15와 각 스토리 SPEC이다.

- **스택 고정.** Java 25 LTS, Spring Boot 4.1.x(현재 4.1.1), Spring Modulith 2.1.x(현재 2.1.1), React 19.2.x, Vite 8.2.x, Nginx 1.30.x, PostgreSQL 18.x(현재 18.6). 허용 계열은 `ARCHITECTURE-SPINE.md` Stack 표가, 실제 버전은 lockfile·wrapper·이미지 태그가 소유한다. 버전이 해결되지 않으면 조용히 낮추지 말고 보고한다.
- **의존 추가는 SPEC이 승인한다.** JPA·`spring-security-test`는 아직 없다. 각각을 필요로 하는 스토리의 SPEC이 목적과 함께 승인할 때 추가한다. Actuator·`micrometer-registry-prometheus`는 `spec-3-7-observability-foundation.md`가 승인해 운영 classpath에 있고, Actuator에 닿는 설정은 루트 `TelemetryConfig` 한 곳이며 모듈은 Micrometer `MeterRegistry`만 쓴다(`ModuleBoundaryTest`가 모듈의 `boot.actuate` 의존을 막는다). PostgreSQL·Flyway는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 운영 classpath에, Spring Modulith·Testcontainers는 1.3 SPEC이 승인해 시험 classpath에 들어왔다. `ch.obermuhlner:big-math` 2.3.2는 `spec-2-7-first-plan-publication.md`가 승인해 운영 classpath에 있고, 쓰는 곳은 `calculation` 모듈 하나다(`ModuleBoundaryTest`가 강제한다). 경계 검증 라이브러리를 운영 jar에 싣지 않는다. 프런트엔드 런타임 의존 추가도 같다(개발·시험 전용 devDependency는 예외) — `web-vitals` 6.2.3(정확 버전)은 `spec-4-5-ad-telemetry-web-vitals.md`가 승인했고 쓰는 곳은 `frontend/src/telemetry/useTelemetry.ts` 하나다. 프런트엔드에는 라우터도 인증 SDK도 없다 — 경로 대응은 `App.tsx`의 닫힌 맵이고 로그인은 서버가 302로 처리한다.
- **`identity`가 계정 소유권·삭제 상태의 유일한 권위다.** 다른 패키지는 공개 application API(`AccountLifecycleApi`·`AccountAccessApi`·`AccountDeletionApi`)로만 접근하고 `identity.internal`이나 소유 테이블에 직접 닿지 않는다. `identity`에는 HTTP 표면이 없다. 접근 거부는 `AccessDecision.DENIED` 하나이며 타 사용자·미존재·삭제된 대상이 구분되면 안 된다. 상태는 `ACTIVE → DELETION_IN_PROGRESS → DELETED` 단조 전진뿐이다. `ModuleBoundaryTest`가 경계와 미노출을 강제한다.
- **스키마는 별도 migration 역할이 바꾼다(AD-12).** API 기동은 `Flyway::validate`만 하고 `migrate` 프로필만 실제로 마이그레이션한다(`SchemaValidationConfig`). 새 마이그레이션은 `backend/src/main/resources/db/migration/V<n>__<이름>.sql`로 더하고 이미 적용된 파일은 고치지 않는다.
- **동일 출처.** Nginx는 `/api`만 백엔드로 프록시한다. 나머지는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다. 와일드카드 SPA 폴백은 두지 않는다. 화면이 여는 내부 경로는 허용 목록 location(`^/(onboarding|dashboard|settings|plan-conditions|operator|ad-transition)$`)이 이름으로 열고, 그 집합은 `frontend/src/App.tsx`의 경로 맵과 같아야 한다. 그중 로그인 복귀 목적지는 넷(`/onboarding`·`/dashboard`·`/settings`·`/operator`)이고 `/plan-conditions`·`/ad-transition`은 화면 경로이지만 복귀 목적지가 아니며, `/operator`는 서버가 운영자에게만 목적지로 허용하고 아니면 `/dashboard`로 접는다. 라우트를 더할 때는 아래 `## 스토리가 고정한 계약`의 다섯 곳을 함께 고친다. `/operator`의 운영자 조건부 접기는 그 다섯과 별개로 helper 하나(`OperatorAccess.foldDestination`)가 소유하고, 목적지를 산출하는 모든 자리(로그인 성공·재인증 시작·인증 시작 되돌림·세션 응답 `destination`)가 그것을 부른다. access log는 쿼리 문자열을 남기지 않는다 — `$request`를 그대로 쓰면 OIDC 콜백의 `code`·`state`가 로그에 실린다.
- **인증 표면은 루트 패키지가 소유한다.** `identity`는 HTTP를 가질 수 없으므로 Google OIDC 콜백·세션 정책·CSRF는 `com.bokrilog`의 `AuthSecurityConfig`·`AuthSessionService`·`AuthFlowHandlers`·`AuthSessionController`·`SessionGuard`가 맡고, 계정 조회·매핑·판정은 `identity` 공개 API만 쓴다. 경로는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`·`GET /api/auth/session`·`POST /api/auth/logout`·`POST /api/auth/reauthenticate/<provider>`·`GET /api/auth/link`·`POST /api/auth/link/<provider>`·`POST /api/auth/unlink/<provider>`(준비·`/confirm`·`/cancel` 셋) 열이다. `SessionGuard`는 세션 상태 전환을 다루지만 HTTP 표면이 없다 — 상태 설정에 도달하는 사용자 경로를 만들지 않는다(Story 1.5). 검증(PKCE S256, 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce`)은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다. 인증 표면은 `@ConditionalOnWebApplication(type = SERVLET)`로 API 프로세스에만 배선한다(`migrate` 역할에는 `HttpSecurity`가 없다).
- **세션.** Spring Session JDBC로 PostgreSQL에 둔다. 세션 속성 저장은 PostgreSQL upsert(`SessionAttributeConversion`의 `PostgreSqlJdbcIndexedSessionRepositoryCustomizer` 빈)라 같은 세션의 동시 요청이 같은 속성을 처음 저장해도 충돌하지 않고, CSRF 토큰은 인증 성공 요청에서 미리 저장돼 로그인 직후 겹친 세션 확인들이 같은 토큰을 읽는다(`AuthSessionConcurrencyTest`). 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키 하나뿐이고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. CSRF 토큰과 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 건다 — 전역으로 켜면 Story 1.2·1.3이 고정한 404·405가 403으로 뒤집힌다. 복귀 목적지는 서버가 가진 닫힌 허용 목록(`/onboarding`·`/dashboard`·`/settings`·`/operator` — 마지막은 운영자에게만)뿐이다. 수명은 두 축이다 — 유휴 30분과 생성 시각 기준 절대 12시간. 폐기는 세션 행 삭제·`SecurityContext` 정리·쿠키 만료 지시 셋을 함께 만족해야 하며 셋 중 둘만 만족한 상태를 로그아웃 완료로 보고하지 않는다. 클라이언트가 CSRF 토큰을 얻는 경로는 세션 확인 응답의 필드 하나뿐이다(쿠키 저장소를 쓰지 않으므로 브라우저 쿠키가 하나로 유지된다). 반복 로그아웃은 403이고 그것이 계약이다. 세션 저장소가 풀 수 없는 속성 바이트(배포 전 클래스·깨진 값)는 예외도 `null`도 아닌 표지 `UnreadableSessionValue`로 읽고(`SessionAttributeConversion`) 판독마다 fail-closed로 접는다 — 무장은 없는 무장(그 속성만 지운다), 재인증 표지는 표시된 것(`SessionGuard.isMarked` 한 곳), 로그인 문맥은 미인증(주체 색인도 `ReadablePrincipalNameIndexResolver`가 타입으로 읽는다)이며 경고는 세션·속성마다 한 번이다.
- **오류 응답 한 가지.** `{"code":…,"message":…,"correlationId":…}`. Spring과 Nginx가 같은 모양을 돌려주고 `code`는 안정적인 machine code다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다.
- **보안·캐시 헤더는 Nginx만.** CSP를 비롯한 보안 응답 헤더와 `Cache-Control`은 `deploy/nginx/bokrilog.conf`가 유일한 정의다. `<meta>` CSP를 쓰지 않고 Spring Security의 기본 헤더 writer도 끈다(`AuthSecurityConfig`). 켜 두면 같은 헤더가 두 번 나가고 헤더를 전문 비교하는 검증이 먼저 깨진다. 캐시 값은 `$status` → `$request_path` 두 단계 `map`이 갈라 내고, CSP 값은 `$request_path` map `$content_security_policy`가 고르며(`/ad-transition`만 광고 공급자 origin을 더한 정책, 나머지는 기본 정책 전문), 헤더는 상위 `add_header` 블록 **한 곳**이 낸다 — 하위 location에 `add_header`를 하나라도 두면 그 응답에서 상위 헤더 다섯 개가 전부 사라진다. 성공한 `/assets/` 해시 자산만 `immutable`이고 문서와 모든 오류 응답은 `no-store`다. 백엔드에 전역 `no-store`를 켜지 않는다 — `GET /api/status`의 빈 `Cache-Control`을 시험이 고정했다.
- **브라우저 경계.** 외부 폰트·CDN·분석·광고 SDK 금지(인증된 네 화면의 동일 출처 계측 beacon `/api/engagement/telemetry`는 허용 범위다 — 4.5). `localStorage`·`sessionStorage`·쿠키·IndexedDB 쓰기 금지. 유일한 예외는 `/ad-transition` 문서다 — 동의·서버 자격·1회 소비를 통과한 SHOW에서만 `ads/gptAdapter.ts`가 GPT를 load하고, GPT가 쓰는 쿠키·저장소는 4.1 동의가 설명한 범주다(4.3). 진입 화면은 문서·번들 외 요청 0건이며 시험이 고정한다. 인증 토큰과 금융 원본은 브라우저 영속 저장소에 두지 않는다.
- **디자인 토큰.** `frontend/src/styles/tokens.css`가 정본이며 `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴 것이다. 색·타이포그래피·모서리·간격 리터럴을 컴포넌트에 직접 쓰지 않는다. stylelint가 강제하고, 인라인 예외는 `-- 이유`가 붙은 disable 주석만 허용한다. 없는 토큰을 지어내지 않는다.
- **화면 카피.** `frontend/src/content/`가 소유하고 화면과 시험이 같은 문자열을 참조한다. 모든 핵심 화면에 `복리 계획 비교`와 "투자성과·계좌 수익률·투자 추천이 아닙니다" 고지를 인접 배치한다. 실시간·정확 보장, 수익 축하, 손실 공포, 스트릭·배지 표현은 금지다.
- **금융 계산.** `BigDecimal`·`MathContext(34, HALF_EVEN)`만 쓴다. `double`·`float`를 금융 계산에 쓰지 않는다. 표시 반올림은 HALF_UP 2자리 한 번만이다. 상세는 `ERD-1.0.0` §3 `CALC-1.0`.
- **자격정보.** `application.yml`·compose·시험 fixture에 자격정보 모양 리터럴을 넣지 않는다. 값이 가짜여도 gitleaks가 잡는다. 탐지가 나면 규칙을 완화하지 말고 리터럴을 없앤다. 예외는 `.gitleaksignore` 한 줄과 근거 주석으로만 둔다.
- **줄바꿈.** 신규 파일은 LF다. `.gitattributes`가 결정하고 `*.bat`·`*.cmd`만 CRLF다.
- **셸 스크립트.** `set -o pipefail` 아래에서 `생산자 | grep -q`를 조건에 쓰지 않는다. grep이 첫 매치에서 끝나 생산자가 SIGPIPE를 받고 러너에서만 실패한다(PR #9 첫 실행 실패 원인). `grep … >/dev/null`로 입력 끝까지 읽는다. bash 3.2 호환, GNU 전용 플래그(`sed -i`, `grep -P`, `readlink -f`) 금지.
- **판정 논리는 스크립트로.** CI 판정은 `.github/scripts/*.sh`가 소유하고 `test-ci-scripts.sh`가 계약을 단정한다. `ci.yml`의 인라인 `run`에 판정을 넣지 않는다. `ci.yml`을 바꾸면 그 시험도 함께 갱신한다.

## 프런트엔드 실행

`frontend/`는 React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA다. 백엔드·DB·제공자 자격정보 없이 단독으로 빌드·실행·시험이 통과한다. Node는 `frontend/.nvmrc`의 24다. Node 25에서는 Vitest가 내장 `localStorage` 때문에 실패하므로 버전 관리자로 24를 맞춘다. 명령은 저장소 루트에서 `--prefix frontend`로 실행한다.

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run lint:css
npm --prefix frontend run format:check
npm --prefix frontend run build
npm --prefix frontend run test
npm --prefix frontend run test:e2e
```

`test:e2e`는 `vite build` 뒤 Playwright가 산출물을 `preview`로 띄운다. 빌드가 이미 끝난 CI에서는 `test:e2e:run`을 쓴다. 처음 한 번은 `npm --prefix frontend exec -- playwright install chromium`이 필요하다. `PLAYWRIGHT_BASE_URL`을 주면 자체 `preview`를 띄우지 않고 그 origin을 검증하며, `e2e/same-origin-csp.spec.ts`와 `e2e/auth-entry.spec.ts`의 성공 왕복 시험은 이때만 실행된다. 자동 정리는 `npm --prefix frontend run lint -- --fix`, `npm --prefix frontend run lint:css -- --fix`, `npm --prefix frontend run format`이다. 상세는 `DEVELOPMENT.md`의 `## 프런트엔드`.

커밋과 함께 성립해야 하는 효과(초점 이동·`replaceLocation`·타이머·리스너, 그 효과가 읽는 latest-ref)는 `useLayoutEffect`, 요청 시작과 `inert` 배경으로 돌려주는 대화상자의 초점 복귀는 근거 주석을 단 passive `useEffect`이며, 부하에서만 드러나는 시험 결함은 `bash frontend/scripts/stress-test.sh`(CPU 수 × 2 부하, 12회)로 재현·확인한다 — 상세는 `DEVELOPMENT.md`의 `### 구조와 규칙`.

프런트엔드 공통 부품은 넷이다 — 운영 command 상태 기계 `session/useOperatorCommand.ts`(단계·멱등 키·이중 제출 방지·응답 없는 확정의 uncertain·문구 불일치의 무장 유지·목록 콜백·취소; 운영자 화면 하나의 훅 다섯이 command 설정만 넘긴다, 목록 읽기의 순번·중단은 `session/useSequencedRead.ts` — 현재 포인터를 참조하는 단건 조회의 엔진인 `useReferencedRequest`와 달리 참조·401/403/404 매핑 없이 "마지막 목록 읽기만 반영"하는 운영자 목록·상세·집계용이다), 확인 대화상자 메커니즘 `components/useConfirmationDialog.ts`(기본 포커스·가둠·`Esc`·스크롤 잠금·호출점 복귀; 세 대화상자가 위임하고 CSS는 `ConfirmationDialog.module.css` 하나), 현재 포인터를 참조하는 조회(근거·이력류)의 엔진 `session/useReferencedRequest.ts`(sequence·중단·키·401/403/404 매핑; 계산 근거·조정 이력이 위임하고 동시성 정책 `replace`/`ignore`는 훅 인자 — `useDashboard`·`useDashboardSync`·`NextPlanBaselinePanel`·`PlanHistoryPanel`·`PlanConditionPanel`의 읽기 경로를 이 엔진으로 옮기는 것은 별도 결정이다), 서버 시각 판정 `session/instant.ts`의 `isInstant`(엄격판 하나) — 새 운영 command·확인 대화상자·참조 조회·시각 판정은 이 넷을 쓰고 사본을 만들지 않는다.

## 백엔드·동일 출처 실행

`backend/`는 Java 25 + Spring Boot 4.1.1 + Gradle wrapper이고 모듈 경계는 Spring Modulith 2.1.1이 시험에서 검증한다(운영 classpath에는 없다). Story 1.3부터 PostgreSQL 18을 쓰고 Story 1.4부터 Spring Security OAuth2 Client·Spring Session JDBC가 있다. HTTP 엔드포인트는 `GET /api/status`, `GET /api/auth/session`, `POST /api/auth/logout`, `POST /api/auth/reauthenticate/<provider>`, 그리고 Spring Security 필터가 받는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`다. Epic 2가 그 위에 `/api/connections/**`(Bybit 연결), `/api/snapshots/first/**`(첫 스냅샷·수집·판정·환산·계획 발행), `/api/plans/conditions/**`(계획 조건), `GET /api/dashboard`(공식 대시보드 — Story 2.8이 2.7의 `GET /api/plans/active`를 대체했고 옛 경로는 404다), `GET /api/dashboard/evidence`(현재 공식 결과의 계산 근거 — Story 2.9), `GET /api/dashboard/adjustments`(현재 공식 계산에 반영된 외부 자금흐름 조정 이력 — Story 2.10), `POST /api/dashboard/sync`(사용자 시점 증분 동기화 접수 — Story 2.11), `POST /api/dashboard/verification`(정밀 검증·기존 계획 계속 접수 — Story 2.12), `POST /api/dashboard/plan-end`와 그 `/confirm`·`/cancel`(활성 계획 중도 종료의 준비·확정·취소 — Story 2.13)를 더했다. Story 2.14는 새 경로 없이 `GET /api/dashboard`에 `COMPLETED` 상태를 더했다. Story 2.16이 `POST /api/connections/bybit/revocation`과 그 `/confirm`·`/cancel`(Bybit 연결 해제의 준비·확정·취소)을 더했다. Story 3.1이 보호된 운영자 표면 `/api/operator/**`(`GET /api/operator/audit` 감사 조회, `GET /api/operator/operations/{id}` operation 조회, `POST /api/operator/commands/audit-export`와 그 `/confirm`·`/cancel`)를 더했다 — 인증된 비운영자·권한 부족은 어느 경로·메서드에서도 없는 경로와 같은 404다(`OperatorGate`). Story 3.2가 같은 세 경로에 제한 생성 command 넷(`restrict-new-connection`·`restrict-sync`·`restrict-calculation-display`·`restrict-advertising`)과 `GET /api/operator/restrictions`(활성 제한, command 권한)를 더했고, 계산 표시 제한 중에는 `GET /api/dashboard`가 `result` 없이 `calculationDisplay: "RESTRICTED"`를, 근거·조정 이력이 409 `CALCULATION_DISPLAY_RESTRICTED`를 낸다. Story 3.5가 인증 사용자의 `GET /api/notices`(그 사용자에게 적용되는 효력 제한과 그것을 덮는 최신 공지 — `ServiceNoticeController`)와 운영 command `publish-notice`·`GET /api/operator/notices`(사건·버전 이력, command 권한)를 더했다. Story 3.6이 사용자 문의 `GET`·`POST /api/support/inquiries`(내 문의·접수, CSRF·`Origin`·멱등 키)와 운영자 `GET /api/operator/inquiries`·`/{reference}`·`/metrics`(command 권한), 운영 command `update-inquiry`를 더했다. Story 4.1이 인증 사용자의 광고 동의 `GET`·`POST /api/engagement/ad-consent`(조회·선택·철회, `POST`는 CSRF·`Origin`·멱등 키 — `AdConsentController`)를 더했고, Story 4.2가 `POST /api/engagement/ad-eligibility`(수동 동기화 성공 결과의 광고 자격·빈도 제한 판정, CSRF·`Origin` — `AdEligibilityController`)를, Story 4.3이 `POST /api/engagement/ad-transition`(자격 `transition`의 1회 소비, CSRF·`Origin` — `AdTransitionController`)을, Story 4.5가 `POST /api/engagement/telemetry`(Web Vitals·광고 이벤트 beacon, CSRF·`Origin` — `ClientTelemetryController`)를 더했다. 상태·인증 컨트롤러는 루트 패키지 `com.bokrilog`에 있고 모듈 패키지는 `identity`·`source`·`portfolio`·`operations`·`sync`·`plan`·`calculation`·`engagement` 여덟이다(`calculation`은 DB·Spring·시계·다른 모듈에 의존하지 않는 순수 모듈이다). `ARCHITECTURE-SPINE.md`가 적은 여덟 번째 모듈 `engagement`(광고 동의·빈도 제한)는 Story 4.1이 만들었고 지금은 광고 동의와 광고 자격·빈도 제한(4.2)·transition 소비(4.3)·브라우저 계측과 광고 성능 kill switch 판정(4.5)을 소유한다. Story 1.9부터 루트 패키지가 자기 테이블 하나(`action_grant`, `V3`)를 소유한다 — 그전까지 모든 테이블은 `identity` 소유였다. 민감 command의 공통 부품은 셋이다 — 세션 무장 메커니즘 `SessionArming<T>`(루트, 여섯 Flow가 위임), 실행 성공 뒤의 재인증 시각 제거·세션 회전 `SessionRotation`(루트), 멱등 키 규칙·헤더·code `com.bokrilog.sync.IdempotencyKey`(메시지 한 문장은 `ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED`) — 새 민감 command는 이 셋을 쓰고 사본을 만들지 않는다. 루트 패키지 HTTP 표면(컨트롤러·`OperatorGate` 필터·`AuthFlowHandlers`)의 공통 판독·응답은 package-private `ControllerSupport` 한 곳이다 — 세션 사용자 `currentUserId`, 없는 경로와 같은 404로 답하는 SENSITIVE 판정 `allowedUser(AccountAccessApi)`, correlation id `correlationIdOf(HttpServletRequest)`, JSON·`no-store` 오류 응답 `error`와 전용 오류 record용 `noStoreJson`(403을 내는 사용자 판정·WRITE 접근·전용 record의 판정과 본문은 각 호출자가 그대로 가진다). 멱등 충돌 code는 `IdempotencyKey.CONFLICT_CODE` 하나다. 새 코드는 세션 사용자 판독·SENSITIVE 판정·요청 식별자 헤더 판독의 사본도 `"IDEMPOTENCY_CONFLICT"` 리터럴도 만들지 않는다 — `ControllerSupportTest`가 운영 소스(`backend/src/main/java`)를 훑어 `UUID currentUserId(`·`UUID allowedUser(` 정의와 한 문장 안의 `UUID.fromString(`…`getName()`이 `ControllerSupport`에만, `getHeader(ApiErrorHandler.REQUEST_ID_HEADER)`가 `ControllerSupport`에만(한정자 없는 꼴은 상수를 소유한 `ApiErrorHandler`에도), 그 리터럴이 `IdempotencyKey`에만 있음을 단정한다. 그와 별개로 작업 lease 30초의 SQL 조각은 `sync.internal.SyncLease` 한 곳이 소유하고 `SyncLeaseTest`가 운영 소스에 리터럴이 다시 생기지 않게 막는다. 컴파일 경고는 `-Werror`로 실패다.

제공자 자격정보는 여전히 필요 없다. 판정은 **제공자마다 독립**이다 — `BOKRILOG_PUBLIC_BASE_URL`과 그 제공자의 `BOKRILOG_<제공자>_CLIENT_ID`·`_CLIENT_SECRET` 중 하나라도 비면 그 등록만 만들어지지 않고 그 제공자의 인증 시작이 공통 오류 계약으로 fail-closed이며, 다른 제공자의 로그인은 살아 있다. 어느 제공자도 구성되지 않으면 `oauth2Login` DSL 자체를 배선하지 않는다. 서로 다른 두 등록이 같은 issuer로 해석되면 기동을 거부한다 — 조회 키가 `(issuer, subject)`라서 override 하나로 계정 병합이 열리기 때문이다(`DevOidcStubProfileTest`·`AuthNaverSignInTest`가 이 계약을 지킨다). 정상 흐름의 결정성은 `dev-oidc` 프로필에서만 사는 local OIDC 스텁이 만든다. 스텁 논리는 `DevOidcStub`이 소유하고 표면은 두 곳이다 — 개발·CI 스택은 `DevOidcStubConfig`가 `/api/dev-oidc/<제공자>/**`에 제공자마다 인스턴스 하나로 올리고(라우터 빈은 하나다)(브라우저와 백엔드가 같은 origin에서 도달해야 성공 경로를 브라우저 층에서 검증할 수 있다), 백엔드 시험은 `OidcStubConfiguration`이 루프백 HTTP 서버에 올린다. 스텁을 두 번 만들지 않는다. 그 프로필이 어느 제공자든 실제 등록과 함께 활성이면 기동을 거부하고, 실제 제공자 왕복은 Epic 1의 **실연동 미검증** 게이트가 소유한다. 백엔드가 자기 스텁에 닿는 주소는 `BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`이며 브라우저가 보는 origin과 다른 값이다.

리다이렉트의 `Location`은 origin-상대 경로다(`server.tomcat.use-relative-redirects: true`). 기본값에서는 Tomcat이 요청 `Host`로 절대 URI를 만들고 Nginx가 포트를 잃은 값을 넘기므로 로그인 뒤의 이동이 80번 포트로 끊긴다.

**백엔드 시험은 Docker를 전제한다.** Testcontainers가 실제 `postgres:18.6-alpine`을 JVM당 하나 띄운다(`build`는 시험 클래스를 JVM 셋에 나누므로 셋 — 정확히는 fork 수가 `min(3, --max-workers)`이고 `--max-workers` 기본이 CPU 수라 2코어 기기는 fork 2·컨테이너 2다). 데몬이 없으면 `build`가 컨테이너 시작 실패로 멈추며, 인메모리 데이터베이스로 대체하지 않는다.

JDK 25가 필요하고 toolchain 자동 프로비저닝은 꺼져 있다. macOS는 `brew install openjdk@25` 뒤 사용자 홈에 최초 1회 심볼릭 링크를 걸면 `/usr/libexec/java_home -v 25`와 Gradle이 함께 찾는다(sudo 불필요). Windows는 `winget install EclipseAdoptium.Temurin.25.JDK`로 끝난다. PATH의 `java -version`이 17로 남아 있어도 정상이다. `JAVA_HOME`을 셸에서 바꿔 두지 않는다.

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk ~/Library/Java/JavaVirtualMachines/openjdk-25.jdk
backend/gradlew --project-dir backend build
```

`deploy/`는 Nginx가 `frontend/dist/`를 origin 루트에서 제공하고 `/api`만 백엔드로 프록시하는 개발·CI 공용 compose 구성이다. production 프로필이 아니다. 서비스는 넷(`postgres`·`migrate`·`backend`·`nginx`)이고 `migrate`는 `profiles`에 가려 명시 실행만 된다. 두 산출물을 먼저 빌드한 뒤 검증 스크립트 하나로 I/O 계약 전체를 확인한다. Docker Desktop이 떠 있어야 한다. 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)이며 데이터베이스 포트도 게시하지 않는다.

```bash
deploy/compose/verify-same-origin.sh
```

스크립트가 `up` 앞에서 마이그레이션을 1회 실행한다. compose를 직접 다룰 때는 그 순서를 손으로 지켜야 한다. 기본 프로필의 백엔드 기동은 schema 검증만 하므로 빈 데이터베이스에 `up`부터 하면 백엔드가 healthy가 되지 못한다.

```bash
docker compose --file deploy/compose/compose.yaml --profile migrate run --rm --build migrate
```

개발·CI compose에는 비밀번호가 없다. `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. production 자격정보는 Story 3.8이 소유한다.

종료 코드는 0(통과), 1(단정 실패), 2(준비 실패: 산출물이나 docker 없음)다. `--keep-stack`으로 스택을 남기면 같은 origin에 Playwright 회귀를 돌릴 수 있다. 상세는 `DEVELOPMENT.md`의 `## 백엔드`·`## 동일 출처 실행`.

## CI

`.github/workflows/ci.yml`이 유일한 workflow다. 구조는 `changes → frontend-quality·backend-quality·backend-assemble → (assemble) same-origin-integration·deploy-rehearsal·backup-rehearsal·restore-rehearsal → ci-required`이고(백업·복원 리허설은 backend jar만 기다려 배포 리허설과 병렬이다 — `spec-ci-critical-path.md`; backend jar의 생산자는 컴파일 + `bootJar`만 하는 `backend-assemble`이고 jar 소비 job 넷은 그것만 기다린다. `backend-quality`는 `build`로 시험을 돌되 jar를 올리지 않고, 두 job이 각자 낸 jar SHA-256 output을 `ci-required`의 집계가 대조한다 — bootJar는 `build.gradle.kts`의 재현 가능 archive 설정으로 바이트 단위 결정적이다. `spec-ci-backend-assemble-job.md`) 필수 검사 이름은 `ci-required`로 고정이다. GitHub ruleset `protect-integration-branches`가 `develop`·`main`에 PR 필수, merge commit만 허용, `ci-required` 필수(strict), 삭제·force push 금지를 건다.

- 첫 GitHub 실측(2026-09-06, PR #9): wall-clock 4분 3초. 문서-only 경로(`_bmad-output/**`, 최상위 `*.md`)는 `changes`와 `ci-required`만 돌아 1분 안에 끝난다.
- 백업·복원 리허설은 두 번째 분류 `backup-restore`를 따른다. PR의 변경이 전부 `frontend/**`·`_bmad-output/**`·최상위 `*.md`일 때만 `false`로 두 job을 승인된 비적용으로 건너뛰고, 그 밖(목록 밖 경로·비교 기준 없음·빈 diff·push·dispatch)은 `true`다. 게이트는 `!= 'false'`이고 집계는 빈 값(옛 분류기)을 `success` 기대로 읽는다(`spec-ci-critical-path.md`).
- artifact 이름에 `run_attempt`가 들어가 `Re-run failed jobs`는 쓸 수 없다. 재실행은 `Re-run all jobs`다.
- 통합 job timeout 19분은 CI-PLAN §8의 시작값 5분과 다르다. `backend-quality`도 Story 1.3에서 10분 → 15분으로 올렸다(Testcontainers 콜드 실행). Story 2.11이 다시 `backend-quality`를 15 → 20분, 통합 job을 15 → 17분(회귀 단계 4 → 6분, 주입 origin Playwright `globalTimeout` 3 → 5분)으로 올렸다 — PR #49 실측이 백엔드 11분 5초·회귀 2분 30초였고 PR #52의 백엔드가 13분 0초였다. Story 2.17이 통합 job을 17 → 19분(회귀 단계 6 → 8분, `globalTimeout` 5 → 7분)으로 올렸다 — 로컬 CI 모드 회귀 실측이 4분 36초였다. 로컬 백엔드 `clean build`는 시험 fork 분할 전 18분 36초(2026-10-15, Windows 11, 시험 1,496건)·19분 42초(2026-11-03, 분할 전 같은 날)였고, `maxParallelForks = 3`(`spec-ci-test-forks.md`) 뒤 같은 Windows 11에서 11분 44초(2026-11-03, 시험 1,610건·137 클래스, 클래스 시간 합 22.1분을 fork 셋이 11.1분 구간에 겹쳐 돌렸다)다. CI `backend-quality`는 분할 전 13~15분(PR #129 14분 19초)이고 분할 뒤 실측은 이 chore의 PR이 채운다(상세는 `DEVELOPMENT.md`). `deploy-rehearsal`은 백업 리허설을 떼며 70 → 30분, 새 `backup-rehearsal`은 50분이다. 두 값 모두 실측이 몇 회 더 쌓인 뒤 CI-PLAN 새 버전으로 보정하기로 했고 아직 발행 전이다.
- Testcontainers는 러너의 docker 데몬을 그대로 쓴다. `backend-quality`에 서비스 컨테이너도, docker를 설치하는 action도 더하지 않았다. 시험은 기존 `Gradle 빌드` 단계 안에서 돈다.
- 로컬에서 CI 계약을 미리 확인하는 명령은 `bash .github/scripts/test-ci-scripts.sh`다.

## Python 도구 실행

BMAD Python 스크립트는 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다. 이 명령은 BMAD 기획 도구 전용이며 제품 런타임과 무관하다. Windows에서 `python3`는 존재하지 않으므로 직접 호출하지 않는다. 셸 스크립트는 POSIX 셸을 요구하고 Windows에서는 Git Bash로 실행한다. 환경이 의심되면 먼저 점검한다.

```bash
./scripts/check-bmad-env.sh
```

## 산출물 위치와 권위

- `_bmad-output/planning-artifacts/` — `epics.md`(5 에픽·45 스토리 승인), `EPIC-IMPLEMENTATION-READINESS.md`(`ERD-1.0.0`), `architecture/architecture-BokriLog-2026-08-24/`(`ARCHITECTURE-SPINE.md`, `PORTFOLIO-SOLUTION-DESIGN.md`, `CI-PLAN.md`), `prds/prd-BokriLog-2026-08-23/prd.md`, `ux-designs/ux-BokriLog-2026-08-23/`(`DESIGN.md`, `EXPERIENCE.md`), `briefs/`
- `_bmad-output/implementation-artifacts/` — `sprint-status.yaml`, `epic-<n>-context.md`(1~3), `epic-<n>-retro-<날짜>.md`, `spec-*.md`, `deferred-work.md`, `deferred-work-resolutions.md`
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

충돌 시 우선순위는 PRD의 제품 범위 → 아키텍처의 불변식 → `ERD-1.0.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다. `sprint-status.yaml`의 스토리 키 앞부분(`1-3`)이 브랜치 번호이고, 스토리 본문은 `epics.md`의 해당 `### Story` 절이다.

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 기존 항목을 고치거나 지우지 않는다. 해소 여부는 `deferred-work-resolutions.md`에 기록하고 상태 요약 표를 함께 맞춘다. 검사가 실제로 성공한 뒤에만 해소로 적는다.

## 현재 진행 지점

기획은 완료됐고 구현 계약은 `ERD-1.0.0`이 권위 기준이다. **Epic 1(11개)은 닫혔고 회고도 끝났다**(`epic-1-retro-2026-09-25.md`, 판정 `accepted-with-open-items`, 액션 12건은 `sprint-status.yaml`의 `action_items`가 소유). **Epic 2(16개)도 모두 `develop`에 머지됐고** M2-2(2.7)·M2-3(2.12)·M2-4(2.13~2.16) 검증 지점이 닫혔다. Epic 2 회고도 끝났다(`epic-2-retro-2026-10-05.md`, 판정 `accepted-with-open-items`, 액션 13건). 회고 액션 AI-8의 백엔드 묶음(민감 command 공통 부품 정리)과 Epic 1 액션 item-1(재인증 시각 연쇄 승인)은 `spec-sensitive-command-commons.md`(PR #71)가 닫았다. AI-8의 프런트엔드 묶음(D-4)과 재인증 게이트 안내(105번)는 `spec-frontend-dialog-commons.md`(PR #73)가 닫았다. **Epic 3은 Story 3.1(운영자 표면·감사 원장, PR #75), 3.2(영향 범위 수동 제한, PR #77), 3.3(강한 신호 자동 정지, PR #79), 3.4(검증 뒤 수동 복구, PR #81), 3.5(서비스 내부 공지, PR #83), 3.6(문의 접수·영업일 3일 답변 목표, PR #85), 3.7(개인정보를 배제한 관측·경보 계측 기반, PR #87), 3.8(API·Worker 역할 분리와 blue/green 배포·rollback 절차, PR #89 — CI 리허설까지, 운영 배포 미검증), 3.9(일일 암호화 백업의 이중 보관과 30일 파기, PR #92 — MinIO 리허설까지, 실저장소 미검증), 3.10(외부 복구 manifest·격리 복원·삭제 계정 재활성화 차단, PR #94 — 격리 복원 리허설까지, 실제 복원 미검증)이 머지돼 **Epic 3(10개)이 닫혔다**. Epic 3 회고도 끝났다(`epic-3-retro-2026-10-13.md`, 판정 `accepted-with-open-items`, 액션 14건). 사용자 결정(2026-10-13)의 작업 순서 셋 가운데 ① 회고 AI-5(운영 command 상태 기계 공통화)와 AI-1(되돌림 계보를 따르는 서비스 공지)은 `spec-operator-command-commons.md`(PR #97)가 닫았다. ② `RECONNECT-1.0`(Epic 2 회고 AI-1)은 Epic 2 보완 Story 2.17(`spec-2-17-bybit-reconnect.md`, PR #100)이 닫았다(해제한 연결의 같은 계정 재연결·활성 계획 동기화 재개 — Epic 2는 17개가 됐다). **③ Epic 4**(광고 동의와 관계없이 동기화 결과 확인하기)**도 닫혔다** — Story 4.1(광고 동의 선택·철회, `spec-4-1-ad-consent.md`)이 `engagement` 모듈을 처음 만들었고, Story 4.2(`spec-4-2-ad-eligibility.md`)가 수동 동기화 성공 결과의 광고 자격·빈도 제한과 3.2가 둔 `ADVERTISING` 제한의 첫 판독을 연결했고, Story 4.3(`spec-4-3-neutral-ad-transition.md`)이 금융정보 없는 중립 화면 `/ad-transition`에서 transition을 1회 소비해 닫을 수 있는 광고를 시도하게 했고, Story 4.4(`spec-4-4-ad-fail-open.md`)가 모든 광고 실패를 광고 없는 저장 결과로 접는 단일 fail-open을 닫았다(광고 이벤트 계측과 Web Vitals kill switch는 범위 분할로 deferred 156·157에 남았다). Epic 4 회고도 끝났다(`epic-4-retro-2026-10-16.md`, 판정 `accepted-with-open-items`, 액션 8건 — 준비된 광고 slot의 수명 상한(AI-1)은 `spec-ad-slot-lifetime.md`(PR #116)가 닫았고, 156·157의 주인(AI-4)은 Epic 4 보완 Story 4.5로 정했고, 4.5(`spec-4-5-ad-telemetry-web-vitals.md`, PR #119)가 동일 출처 계측 beacon·광고 이벤트와 소비 결말 카운터·Web Vitals 수집·`PERFORMANCE` 출처의 성능 kill switch로 156·157·122를 닫았다 — Epic 4는 5개다). 문서·주석 정합(AI-2)과 검증 공백(AI-3)은 `spec-epic-4-retro-docs-and-verification.md`(PR #121)가 닫았다. SPEC 계획 단계가 열린 회고 액션을 Code Map에 적게 하는 규칙(AI-7)은 `_bmad/custom/bmad-build.toml`의 `persistent_facts`가 소유한다. 간헐 실패 시험의 진단 장치(AI-6)는 `spec-flaky-test-diagnostics.md`(PR #125)가 닫았다. CI 임계 경로의 backend jar 생산자 분리(`backend-assemble`, digest 일치 판정)는 `spec-ci-backend-assemble-job.md`(PR #129)가 닫았고, 그 뒤 남은 임계 경로인 백엔드 시험을 JVM(fork) 셋에 나누는 것(deferred 163)은 `spec-ci-test-forks.md`(`maxParallelForks = 3`, PR #133)가 이었고 163은 부분 해소다(로컬 실측만 — PR CI `backend-quality`가 10분 안이면 PR에서 해소로 올린다), 리허설의 PR 경로 분리는 deferred 164로 남았다. 사용자 삭제 경로를 여는 Epic 5 전에 닫아야 했던 Epic 3 회고 item-27(계정 삭제와 감사 원장 잠금의 직렬화)은 `spec-erasure-audit-lock.md`(PR #127)가 닫았다. Epic 4 회고 AI-8의 ① 백엔드 묶음(루트 컨트롤러 helper `ControllerSupport`·`IdempotencyKey.CONFLICT_CODE`)은 `spec-controller-commons.md`(PR #130)가 닫았고, ② 프런트엔드 POST·JSON 판독 helper와 ③ 광고 시험 helper·engagement 바깥 의존 목록은 deferred 166·167로 남았다. 다음은 남은 Epic 4 회고 액션(AI-5, AI-8 ②·③)과 Epic 5다. Epic 3의 운영·출시 증거는 셋 다 미검증으로 남는다 — 운영 배포 smoke(3.8), 실저장소 백업·파기(3.9), 실제 격리 복원(3.10). Epic 4의 광고 출시 게이트 G-4(정의는 `epics.md`의 게이트 표 G-4)도 미검증이다 — 광고 network 선정·계정·ad unit 승인, request field·CSP 실측과 SHOW 왕복(153), 단위 경로 형식(154), 소재 접근성, 개인정보 검토·동의 카피, 보존 기한(148·152), 공급자 변수 배포 연결(151), 4.5 성능 kill switch 임계(`AD-PERF-1.0`)의 실측 보정을 묶으며 그때까지 공급자는 기본 꺼짐이다. 강한 신호 1·8·9번 연결(3.3 후속), 문의 답변 정정·후속 응답(3.6 defer), 운영 작업시간·비용 장부·Bybit 계약 정기 재검증(3.7 defer), 이미지 공급망 파이프라인·운영 연결 묶음(Alloy·훈련 증거 3.4 AC-6·비밀 공급·nginx 권한 강화·키 회전)·TLS 종단·프런트엔드 산출물의 release 관리·리허설의 실제 호환 창(3.8 defer), 백업 리허설의 CI 경로 분기·계정 단위 저장소 사용률·기록 대기열 재전송 멱등(3.9 defer), 새 송신 IP 재바인딩·훈련 증거 원장·호스트 전환 runbook·격리 환경 실제 provider 왕복·tombstone 내보내기 관측·digest 키 회전·manifest↔저장소 대조(3.10 defer)는 `deferred-work.md`에 남아 있다. item-7(확인 hash 판별력)은 운영 command 쪽이 3.1에서 닫혔고(확인 문구 재입력), Epic 1·2 command의 hash는 ERD 정정 몫으로 남는다. `RECONNECT-1.0`(AI-1)은 Story 2.17(PR #100)이 구현했다 — 2.16 뒤 해제 사용자의 새 연결을 영구 차단하던 accepted deviation은 닫혔다. 실연동 게이트는 둘이며 둘 다 별도 출시 게이트가 소유한다 — Epic 1의 세 제공자 왕복, Epic 2의 Bybit 연결 정책·실연동(실제 검증 계정과 regional reserved IP 필요). 위 G-4는 이 둘과 별개인 광고 출시 게이트이고 실연동 게이트가 아니다.

스토리마다 무엇을 도입했고 리뷰가 무엇을 잡았는지는 아래 표의 SPEC이 소유한다. 그 코드를 건드릴 때 해당 SPEC의 `## Design Notes`와 "구현에서 확정한 것"을 읽는다.

| 스토리 | 상태 | 근거 |
|---|---|---|
| 1.1 진입 화면 | done | `spec-1-1-entry-screen.md`, PR #3 |
| 1.2 동일 출처 셸 | done | `spec-1-2-same-origin-shell.md`, PR #9 머지 커밋 `d9a1fc2`, PR #10 |
| 1.3 소유권·삭제 상태 | done | `spec-1-3-identity-ownership.md`, PR #12 머지 커밋 `89b8948` |
| 1.4 Google 로그인 | done | `spec-1-4-google-sign-in.md`, PR #14 머지 커밋 `2f300f6` |
| 1.5 로그아웃·세션 만료 | done | `spec-1-5-logout-session-expiry.md`, PR #17 머지 커밋 `5b6ae01` |
| 1.6 네이버 로그인 | done | `spec-1-6-naver-sign-in.md`, PR #20 머지 커밋 `7f0f816` |
| 1.7 카카오 로그인 | done | `spec-1-7-kakao-sign-in.md`, PR #22 머지 커밋 `c60729f` |
| 1.8 재인증·세션 회전 | done | `spec-1-8-reauthentication-session-rotation.md`, PR #24 머지 커밋 `b2a4d5d` |
| 1.9 재인증 허가(5분·일회성) | done | `spec-1-9-reauthentication-action-grant.md`, PR #26 머지 커밋 `3798010` |
| 1.10 로그인 수단 연결 | done | `spec-1-10-link-sign-in-method.md`, PR #28 머지 커밋 `97c2d3a` |
| 1.11 로그인 수단 해제 | done | `spec-1-11-unlink-sign-in-method.md`, PR #30 머지 커밋 `59415e7` |
| Epic 1 | **완료** | 11개 스토리 전부 done |
| Epic 1 회고 | done | `epic-1-retro-2026-09-25.md`, 판정 `accepted-with-open-items`, 액션 12건 |
| 2.1 Bybit 연결 검증·저장 | done | `spec-2-1-bybit-connection.md`, PR #34 머지 커밋 `ad16b7a` |
| 2.2 첫 현재가치 스냅샷 | done | `spec-2-2-first-equity-snapshot.md`, PR #36 머지 커밋 `f1df593d0dd233bc72e54e4231cad8a2aacf9e43` |
| 2.3 계획 조건 미리보기 | done | `spec-2-3-plan-condition-preview.md`, PR #36 머지 커밋 `f1df593d0dd233bc72e54e4231cad8a2aacf9e43` |
| 2.4 현재가치·원천 사건 완전 수집 | done | `spec-2-4-bybit-source-event-collection.md`, PR #38 머지 커밋 `0883c24` |
| 2.5 외부 자금흐름 판정 | done | `spec-2-5-cashflow-classification.md`, PR #39 머지 커밋 `6446332` |
| 2.6 사건 시점 USD 환산 | done | `spec-2-6-cashflow-usd-conversion.md`, PR #41 머지 커밋 `d3b8bbe` |
| 2.7 최초 계획 계산·원자 발행 | done | `spec-2-7-first-plan-publication.md`, PR #43 머지 커밋 `568089e` |
| 2.8 공식 대시보드 | done | `spec-2-8-official-dashboard.md`, PR #45 머지 커밋 `ff0d915` |
| 2.9 계산 근거 | done | `spec-2-9-calculation-evidence.md`, PR #47 머지 커밋 `5f770f8` |
| 2.10 조정 이력 | done | `spec-2-10-cashflow-adjustment-history.md`, PR #49 머지 커밋 `752cb1a` |
| 2.11 증분 동기화 | done | `spec-2-11-incremental-plan-sync.md`, PR #52 머지 커밋 `de6290c` |
| 2.12 정밀 재검증 | done | `spec-2-12-precision-reverification.md`, PR #54 머지 커밋 `4551b5b` |
| 2.13 계획 중도 종료 | done | `spec-2-13-plan-early-end.md`, PR #57 머지 커밋 `436f1c2` |
| 2.14 계획 자연 만료·완료 결과 | done | `spec-2-14-plan-natural-completion.md`, PR #60 머지 커밋 `1c92f5f` |
| 2.15 새 정상 기준·후속 계획 | done | `spec-2-15-successor-plan.md`, PR #63 머지 커밋 `816289366f57f6e3d067ed59359d7399c02b433b` |
| 2.16 연결 해제·기록 보존 | done | `spec-2-16-bybit-connection-revocation.md`, PR #65 머지 커밋 `9aae04d` |
| 2.17 해제 연결의 같은 계정 재연결 | done | `spec-2-17-bybit-reconnect.md`, PR #100 머지 커밋 `0c391cb` |
| Epic 2 | **완료** | 17개 스토리 전부 done (2.17은 회고 AI-1의 보완) |
| Epic 2 회고 | done | `epic-2-retro-2026-10-05.md`, 판정 `accepted-with-open-items`, 액션 13건 |
| AI-8 백엔드 공통 부품 chore | done | `spec-sensitive-command-commons.md`, PR #71 머지 커밋 `1e43ebf` |
| AI-8 프런트엔드 공통 부품 chore | done | `spec-frontend-dialog-commons.md`, PR #73 머지 커밋 `b671bf3` |
| 3.1 운영자 표면·감사 원장 | done | `spec-3-1-operator-surface-audit-ledger.md`, PR #75 머지 커밋 `4af890e` |
| 3.2 영향 범위 수동 제한 | done | `spec-3-2-scoped-manual-restriction.md`, PR #77 머지 커밋 `f64e5fe` |
| 3.3 강한 신호 자동 정지 | done | `spec-3-3-strong-signal-auto-stop.md`, PR #79 머지 커밋 `168e471` |
| 3.4 검증 뒤 수동 복구 | done | `spec-3-4-manual-recovery.md`, PR #81 머지 커밋 `c17d54a` |
| 3.5 서비스 내부 공지 | done | `spec-3-5-service-notice.md`, PR #83 머지 커밋 `6a13ad8` |
| 3.6 문의 접수·답변 목표 | done | `spec-3-6-support-inquiry.md`, PR #85 머지 커밋 `1ae2732` |
| 3.7 관측·경보 계측 기반 | done | `spec-3-7-observability-foundation.md`, PR #87 머지 커밋 `a143a6d` |
| 3.8 역할 분리·blue/green 배포 | done | `spec-3-8-blue-green-deploy.md`, PR #89 머지 커밋 `369b3a8` |
| 3.9 암호화 백업·30일 파기 | done | `spec-3-9-encrypted-backup.md`, PR #92 머지 커밋 `37bfbbf` |
| 3.10 격리 복원·tombstone | done | `spec-3-10-isolated-restore.md`, PR #94 머지 커밋 `47a6220` |
| Epic 3 | **완료** | 10개 스토리 전부 done |
| Epic 3 회고 | done | `epic-3-retro-2026-10-13.md`, 판정 `accepted-with-open-items`, 액션 14건 |
| 회고 AI-5·AI-1 운영 command 공통화 chore | done | `spec-operator-command-commons.md`, PR #97 머지 커밋 `aa782e2` |
| 회고 AI-12 CI 임계 경로 단축 chore | done | `spec-ci-critical-path.md`, PR #104 머지 커밋 `571bb3c` |
| 4.1 광고 동의 선택·철회 | done | `spec-4-1-ad-consent.md`, PR #107 머지 커밋 `176190f` |
| 4.2 광고 자격·빈도 제한 | done | `spec-4-2-ad-eligibility.md`, PR #109 머지 커밋 `090efb9` |
| 4.3 중립 화면의 닫을 수 있는 광고 시도 | done | `spec-4-3-neutral-ad-transition.md`, PR #111 머지 커밋 `61aac2b` |
| 4.4 광고 실패의 단일 fail-open | done | `spec-4-4-ad-fail-open.md`, PR #113 머지 커밋 `ec51014` |
| Epic 4 | **완료** | 5개 스토리 전부 done(4.5는 회고 AI-4의 보완). 4.4가 뗀 AC-3 뒷부분·AC-4는 4.5가 닫았다 |
| Epic 4 회고 | done | `epic-4-retro-2026-10-16.md`, 판정 `accepted-with-open-items`, 액션 8건 |
| 회고 AI-1 광고 slot 수명 상한 fix | done | `spec-ad-slot-lifetime.md`, PR #116 머지 커밋 `24e8443` |
| 4.5 광고 이벤트·Web Vitals 계측과 성능 kill switch | done | `spec-4-5-ad-telemetry-web-vitals.md`, PR #119 머지 커밋 `ae06d92` |
| 회고 AI-2·AI-3 문서·주석 정합과 검증 공백 chore | done | `spec-epic-4-retro-docs-and-verification.md`, PR #121 머지 커밋 `1333c62` |
| 동시 첫 세션 확인 500(세션 속성 upsert·CSRF 선저장) fix | done | `spec-session-attribute-upsert.md`, PR #124 |
| 회고 AI-6 간헐 실패 진단 장치 chore | done | `spec-flaky-test-diagnostics.md`, PR #125 머지 커밋 `4e248b8` |
| CI backend jar 생성 job chore | done | `spec-ci-backend-assemble-job.md`, PR #129 머지 커밋 `cfbdaf0` |
| Epic 3 회고 item-27 계정 삭제·감사 원장 잠금 직렬화 fix | done | `spec-erasure-audit-lock.md`, PR #127 머지 커밋 `c6362a7` |
| 회고 AI-8 ① 루트 컨트롤러 공통 부품 chore | done | `spec-controller-commons.md`, PR #130 머지 커밋 `0456622` |
| CI 시험 fork 분할 chore(`maxParallelForks = 3`) | review | `spec-ci-test-forks.md`, PR #133 |

## 스토리가 고정한 계약

여러 스토리에 걸쳐 지켜야 하는 것만 한 줄씩 둔다. 괄호는 근거 SPEC의 스토리 번호다.

**세션·인증 (Epic 1)**
- 반복 로그아웃은 403이 계약이다. 표시(`reauth_required`)된 세션에만 `reauthRequired`와 CSRF 토큰이 실리고, 미인증·폐기 응답 본문은 글자까지 같다. 표시된 세션도 스스로 로그아웃할 수 있다(1.5·1.8).
- 만료의 결과는 이동 하나이고 만료 문장의 출처는 주소 표지(`noticeKindOf`) 하나다 — 제자리 렌더와 이동을 함께 내면 브라우저에서 경합한다(1.5).
- 재인증은 두 번째 인증 경로가 아니라 같은 `oauth2Login` 왕복이다. 인증된 세션에 도착한 무장 없는 콜백은 검증 앞에서 접는다. 복귀 목적지의 출처는 세션 하나이며 클라이언트는 목적지를 보내지 않는다(1.8).
- 최근 인증은 공표된 계약만 쓴다: 공통 `max_age=300`, Google은 essential `auth_time`, 카카오는 `prompt=login`, 네이버는 추가 수단이 없어 실연동에서 fail-closed될 수 있다(1.8).
- 카카오 `sub`은 pairwise라 앱 재생성·REST 키 교체 시 기존 매핑이 고아가 되고, Client Secret은 콘솔에서 켜야 등록이 생긴다(1.7, 상세는 `DEVELOPMENT.md`). 브랜드 값은 제공자가 공표한 수치만 토큰이 된다(1.6·1.7).
- 일회성 허가(`action_grant`): 소비는 다섯 귀속(user·session·command·target·확인 hash)과 미소비·미만료를 함께 보는 조건부 UPDATE 한 문장이고, 소비와 업무 변경은 같은 트랜잭션이다. 시각의 권위는 PostgreSQL, 멱등의 권위는 `(user_id, operation_id)` 부분 유일 색인이다(1.9).
- 허가의 귀속 세션 id는 흐름 record가 들고 있던 값을 쓴다 — OIDC 콜백이 세션을 반드시 회전시키기 때문이다. 콜백의 등록 id가 연결·재인증 분기를 가른다(1.10).
- 마지막 로그인 수단 보호는 생명주기 행 잠금과 DELETE 술어 둘 다로 지킨다. 해제 확정 뒤 현재 세션 외 그 사용자의 세션을 폐기하고 현재 세션만 회전시킨다(1.11).
- 설정 화면의 흐름들(연결·본인 확인·해제)은 게이트를 통과한 뒤에만 서로의 무장을 버린다 — 거절되는 시작 요청은 아무것도 버리지 않는다. 앞선 허가 무효화는 참조 단위다(1.11·2.13).
- 밀어내기는 두 랜딩 화면(`/onboarding`·`/dashboard`) 사이에서만 일어나고 `/settings`는 밀리지 않는다(1.10).
- 인가는 새 접두사에만 건다 — 전역 `authenticated()`는 1.2가 고정한 404·405를 뒤집는다. 인가와 CSRF·`Origin` 판정은 같은 술어를 부른다(2.1).
- 민감 command 실행 성공은 세션 회전과 함께 재인증 시각을 지운다 — 본인 확인 1회는 command 1건이고, 거부·재전송·검증 실패는 지우지 않는다. 명시적 허가 무효화(abandon·supersede·cancel)는 참조 단위 하나(`invalidateReference`)이고 `(user, command, target)` 단위는 `issue()` 안에서만 쓴다(`spec-sensitive-command-commons.md`).

**경로·화면 경계**
- 화면 경로는 여섯(`/onboarding`·`/dashboard`·`/settings`·`/plan-conditions`·`/operator`·`/ad-transition`)이고 `App.tsx` 경로 맵과 Nginx 허용 목록이 같은 집합이어야 한다. 그중 복귀 목적지는 넷(`/plan-conditions`·`/ad-transition` 제외, `/operator`는 운영자에게만 — 접기 규칙은 helper 하나 `OperatorAccess.foldDestination`)이고 서버 `ALLOWED_DESTINATIONS`, 세션 목적지 맵, 복귀 목적지 집합이 같은 집합이어야 한다 — 합쳐 **다섯 곳**이다. 타입 검사가 잡는 것은 하나뿐이다. 근거·조정 이력·종료·완료는 새 경로가 아니라 대시보드 패널 안에서 연다(2.9~2.14).
- CSP는 넓히지 않는다. 폼은 `fetch`로 보내고 `form-action 'none'`을 유지한다(2.1). 예외는 `/ad-transition` 한 경로의 광고 공급자 origin뿐이고 그 값은 출시 게이트 G-4가 실측으로 보정한다(4.3).
- 화면은 대시보드 응답 하나를 한 단위로 교체한다. 늦은 응답은 버리고, 버전 역행·충돌은 교체하지 않고, 실패한 다시 불러오기는 마지막 정상 결과를 유지하며, 401·403은 값을 메모리에서 지운다. 같은 계획의 종단(중도 종료·완료) 뒤 활성 묶음과 새 계획 시작 뒤 이전 계획 응답의 역행은 거절한다(2.8·2.13~2.15).
- 초점은 사용자가 누른 조작의 결과에만 옮긴다. 자동 트리거의 완료는 status 문장 한 번이다. 화면당 live region은 하나이고 대시보드 status는 `aria-atomic="false"`다(2.9·2.11).

**Bybit·수집·계산 (Epic 2)**
- 모듈 레이아웃: 공개 API는 모듈 루트, 구현은 package-private `internal`, DB 접근은 `JdbcClient`. sync는 plan에 의존할 수 없으므로 plan이 필요한 일은 sync가 소유하고 plan이 구현하는 `PlanPublicationPort`로 부른다(2.1·2.7).
- 자격정보는 AES-256-GCM(봉할 때마다 새 96비트 nonce, AAD `user_id/connection_id/credential_id/key_version/algorithm_version`)이다. 외부 호출은 트랜잭션 밖에서 끝내고, 구성이 비면 제공자를 두드리기 전에 422로 닫는다(2.1).
- Bybit permission 허용 목록과 `retCode` 표는 의도적으로 비어 있다 — 실연동 스파이크가 채운다. 실제 rate limit·IP 거부는 200 + `retCode`로 온다(2.1). HTTP 403은 IP 차단 회로, `retCode` `10006`은 재시도 대상이다(2.12).
- 작업은 lease·heartbeat·fencing과 짧은 원자 publish를 쓰고, `tc`를 한 번 고정해 DB 시각 ±60초 안에서만 믿는다. 30일 장기 미동기화 판정의 시계는 admission의 DB 시각이다(2.4·2.11).
- 환산은 `USD`·`USDT`만 직접이고 나머지는 spot 종료 1분봉 close를 `ESTIMATED`로 쓴다. 미종료 candle은 fallback 없이 보류다. 공유 공개 증거(`sync_market_*`)는 사용자 식별자가 없고 삭제 뒤에도 남는다(2.6).
- 계산은 순수 `calculation` 모듈이 한다. `periods`는 정확한 분수를 누적한 뒤 한 번만 반올림한다. `Target <= 0`이어도 발행하고 목표 기반 비율만 계산 불가다. 범위 초과는 발행 전체를 거절한다. `계산 불가`는 계산 성격을 덮지 않는다(2.7).
- 공식 발행은 sync가 소유한 outer 트랜잭션 하나이고 잠금 순서는 계획 advisory → 생명주기 행 → 작업 행이다. portfolio 포인터가 계산 버전의 snapshot을 가리켜야 `READY`다(AD-23). 제한 판정 시각은 `statement_timestamp()`다(2.7·2.8·2.11).
- 대시보드 읽기는 읽기 전용 `REPEATABLE READ` 한 묶음이고 계정 접근을 그 앞뒤로 본다. 세 신뢰 축은 서버가 판정하고 화면은 닫힌 맵으로 카피에 잇는다(모르는 코드는 안전한 카피). 현재 근거·조정 이력은 참조(`planId`·`pointerVersion`·`calculationId`)가 현재 포인터와 같을 때만 200이다. 명시적 과거 조회는 소유 종단 계획의 고정 묶음만 읽고, 불일치는 모두 같은 404, 계보가 어긋나면 503이다(2.8~2.10·2.15).
- 모든 증분 발행은 직전 버전과의 흐름 차이(`sync_plan_calculation_change`)를 같은 트랜잭션에 쓴다. 지속성 검증 검토는 그 기준 계산 동안 끈적이며 작업을 다시 만들지 않는다 — 풀 수 없는 재수집 루프를 막는 규칙이고 완료 계획에도 적용된다(2.12·2.14).
- 계획 상태는 `ACTIVE → ENDED_EARLY | COMPLETED` 단조이고 활성 포인터는 부분 유일 색인 `WHERE status='ACTIVE'`다. 종료·완료 사실은 별도 표(`plan_end`·`plan_completion`)에 두고 상태와 복합 FK로 짝짓는다. 종단 뒤 새 준비가 없으면 계획 조건 경로는 `PLAN_ENDED`이고, 후속 계획은 종단 이후 요청·완료·측정된 양수 정상 기준에 확인→intent→발행을 고정한다. 이전 흐름은 옮기지 않고 새 `t0 < ti`만 반영한다(2.13~2.15).
- 연결 해제는 연결 행을 지우지 않는다 — 자격정보 행 삭제·`ACTIVE → REVOKED`·membership 종료·진행 중 작업 종단을 허가 소비와 같은 트랜잭션 하나로 커밋하고, 기존 `snapshotConnection` 재검사가 늦은 worker를 막는다. 해제 시각은 검증·membership 시작보다 늦게 단조다. 운영 제한은 해제를 막지 않는다(2.16). 해제한 사용자의 새 연결은 거부하지 않고 Story 2.1의 같은 경로·command·무장·허가로 재연결한다 — 저장은 활성 연결 → 다른 사용자의 main UID(`CONNECTION_ACCOUNT_TAKEN`) → 같은 사용자의 기존 provider account 재사용 → 새 provider account 순서로 판정하고, 확정 트랜잭션이 계획 advisory → 생명주기 잠금 아래에서 DB 시각으로 종료 시각이 지난 ACTIVE 계획을 2.14와 같은 자연 완료 전환(`SyncJobApi.completeEndedPlanForReconnection`)으로 `COMPLETED`로 바꾼 뒤, 활성 계획의 provider account와 다르면 저장 없이 422 `CONNECTION_ACCOUNT_MISMATCH`다(종단 계획만이면 새 계정으로 연결, 후속 계획은 2.15 경로). 해제된 이전 연결 행·과거 membership 구간·`plan_definition`의 원래 연결 열은 바꾸지 않는다(2.17).
- 계획은 연결 ID가 아니라 provider account에 귀속한다 — 동기화·정밀 검증·완료 판정의 가용성과 새 attempt·발행 계보는 "계획의 provider account·portfolio와 같은 지금 활성 연결과 그 membership"(`PlanSyncGuard.planSource`)을 쓰고, 그런 연결이 없을 때만 `CONNECTION_REVOKED`·`CONNECTION_UNAVAILABLE`이다. 실행 중 attempt는 접수한 연결에 묶여(`blocked`·수집 완료 재검사의 연결 ID 대조) 해제 이전 연결의 늦은 Worker는 재연결 뒤에도 발행하지 못한다. 운영 제한의 계획 `PROVIDER_CONNECTION` 차원도 지금 연결이다(`PlanConditionApi.planTarget`). 연결 단위 제한은 계정을 따른다 — 판독 술어(`OperationalRestrictionRepository`, evaluator·`matching`·공지 `matchingAmong`이 함께 지난다)가 연결 ID를 같은 사용자·같은 provider account의 연결 전부로 넓히므로(operations port `RestrictionConnectionScope`, 루트 구현) 이전 연결에 건 열린 제한이 같은 계정의 재연결 뒤에도 적용되고 다른 계정의 연결에는 적용되지 않는다. 재연결 자체는 막지 않고 제한은 운영자 해제로만 닫힌다. 계산 근거의 기준 snapshot은 provider account로만 대조한다. 계획이 없을 때 계획 조건의 기준은 활성 연결 계정의 기준 snapshot(`PortfolioMembershipApi.accountBaselineSnapshot` — 다른 계정 snapshot 뒤 그 계정의 첫 snapshot)이고 membership·연결은 지금 활성의 것으로 실린다. 계획 없이 다른 계정으로 다시 연결하면 그 계정의 새 첫 확인만 받고(`accountRebaseAllowed`, 첫 확인 상태 `accountChanged`) 그 발행은 포인터를 한 칸 옮긴다(이전 snapshot·사건은 그대로)(2.17).
- 자연 만료는 사용자 시점의 동기화 admission이 전환한다(백그라운드 없음, `GET`은 쓰지 않음). 첫 종료 후 관측은 한 번 고정하고 교체하지 않으며, 관측 시각은 ms로 잘라 저장·판정한다(2.14).

**운영자 표면 (Epic 3)**
- 운영자는 배포 환경의 allowlist `BOKRILOG_OPERATORS`(`issuer|subject|command,audit;…`)만 정하고 요청마다 `identity` 공개 API로 풀어 세션 주체와 비교한다(세션에 역할을 심지 않는다). 운영 command는 `OperatorCommandService`가 Epic 1의 재인증·허가·`SessionRotation`으로 준비→확정→취소하고, 확정 트랜잭션 안에서 원장 잠금 뒤 최종 권한을 재검사한다. command마다 다른 것(본문 해석·검증·준비 뒤 거절과 미리보기·확정 본문·커밋 뒤 처리·재전송·응답 모양)은 `operations.internal`의 `OperatorCommand` 전략 하나이고, 확정 prologue(트랜잭션 가드 → 원장 잠금 → 최종 권한 → `ACCESS_REVOKED`)와 `COMPLETED` 행은 엔진 템플릿(`OperatorCommandApi.complete`) 한 곳이며, 무장은 검증된 요청 하나(`payload`)만 든다 — 새 command가 고치는 자리는 이름 상수와 `OperatorCommandApi.COMMANDS` 한 줄, 전략 구현 하나와 `OperatorEnvelopeService` 등록표 한 줄, 문장이 따로 필요하면 `OperatorController.COMMAND_REFUSALS`, 모듈 밖 사실이 필요하면 port `OperatorCommandEnvironment`의 메서드, 화면은 `useOperatorCommand` 설정과 `operatorApi`의 응답 해석기다(`OperatorCommandRegistryTest`가 등록 완전성·prologue 우회 금지를, `OperatorCommandContractTest`가 응답 JSON 모양을 고정한다) — 루트 흐름·컨트롤러는 command를 가르지 않는다. 모듈 밖 사실(대상·연락처·계정 상태)은 루트가 구현하는 port `OperatorCommandEnvironment`로만 닿는다. 화면은 `session/useOperatorCommand`(상태 기계)와 `operatorApi`의 command 이름을 받는 준비·확정·취소 한 벌에 command 설정 하나를 준다(AI-5). 감사 원장 `operations_audit_event`는 추가 전용이며 SQL은 `OperatorAuditRepository` 한 곳(INSERT·SELECT와 삭제 port의 비식별 UPDATE 하나)이고, 거절·실패 행은 롤백 뒤 따로 쓴다. 감사 digest 키 `BOKRILOG_AUDIT_DIGEST_KEY`가 비면 API가 뜨지 않는다. 운영자 화면은 `React.lazy` chunk다(3.1).
- 강한 신호의 자동 정지는 `OperationalSignalApi.raise` 하나이고 감지 트랜잭션의 결말과 무관한 별도 트랜잭션에 제한·신호·감사 SYSTEM 행을 함께 커밋한다. 판정은 버전 seed `OPS-SIGNAL-1.0`이 하고, sync 쪽 감지 자리는 `StrongSignals` 하나다. 신호는 제한을 풀거나 줄이지 못하며, 신호가 하나라도 연결된 제한은 출처와 무관하게 `수동 검증 필요`이고 해제(3.4)는 그 표시를 본다(3.3).
- 운영 제한은 `기능(NEW_CONNECTION·SYNC·CALCULATION_DISPLAY·ADVERTISING) × scope(GLOBAL·PLAN·PORTFOLIO·CALCULATION_POLICY_VERSION·PROVIDER_CONNECTION) × target`이고 판독은 `OperationalRestrictionApi.evaluate(기능 집합, RestrictionSubject)` 하나다 — 차단은 일치 행의 합집합, 보고는 가장 구체적인 행이며, 각 검사 지점은 자기가 가진 대상 차원만 넘긴다(넘기지 않은 차원의 구체 제한은 적용되지 않는다). 지점 대응은 연결 생성 → `NEW_CONNECTION`, 첫 스냅샷·초기 수집·증분 동기화(lease 포함) → `SYNC`, 계획 조건·발행·계획 종료 → `SYNC`·`CALCULATION_DISPLAY`, 대시보드 결과·근거·조정 이력 → `CALCULATION_DISPLAY`이고 연결 해제는 보지 않는다. 사용자 귀속 target은 원장에 keyed digest로만 남고 계정 삭제 시 `eraseRestrictionTargets`가 제한 행도 digest로 바꾼다. 제한 행은 제한 전략(`RestrictCommand`)이 템플릿의 원장 잠금 → 최종 권한 뒤 대상 재검사 → 중복 → INSERT → `COMPLETED` 순서로 쓰며, 대상 확인·영향 집계는 루트 `RestrictionTargets`가 각 모듈 공개 API로 한다(3.2).
- 제한이 닫히는 운영 경로는 운영 command `lift-restriction`(target은 제한 ID 하나) 하나다. 확정은 원장 잠금 → 최종 권한 → 제한 행 잠금 → 열림·신호 계보 재검사 → `effective_until`을 쓰는 조건부 UPDATE 한 문장(`OperationalRestrictionRepository.lift`, 운영 소스에서 유일 — `OperatorRecoveryTest`가 훑는다) → `COMPLETED`를 한 커밋으로 쓰고, 그 행 하나만 닫는다. 커밋 뒤 evaluator smoke(후보 전체 `matching`)가 어긋나면 해제를 되돌리지 않고 같은 범위의 새 제한(`reinstated_from`으로 원 제한·출처·신호 계보를 잇는다)과 `FAILED SMOKE_FAILED`를 별도 트랜잭션으로 더하고, 통과는 `APPROVED SMOKE_PASSED`로 남긴다 — 재전송은 그 행으로 smoke를 재구성하며 없으면 `PENDING`이다. 남는 제한·다시 열릴 단계는 `RestrictionTargets.remaining`이 `OperationalRestrictionApi.matching`(evaluator와 같은 술어)으로 센다(3.4).
- 서비스 공지(`operations_notice_incident`·`operations_notice_version`)는 추가 전용이고 SQL은 `ServiceNoticeRepository` 한 곳(INSERT·SELECT)이며 쓰는 경로는 `publish-notice` 하나다 — 영향·계속 가능 기능은 덮는 제한(`covers`)에서 서버가 계산하고, 버전 연속·닫힌 사건은 원장 잠금 아래에서 다시 본다. `GET /api/notices`는 사용자 차원으로 효력 제한을 먼저 읽고 덮는 진행 중 공지가 없으면 `RESTRICTED_WITHOUT_NOTICE`로 싣는다 — 공지는 제한을 풀거나 숨기지 않는다. 지원 연락처 `BOKRILOG_SUPPORT_CONTACT`가 비면 발행이 422이고, 확정 시점의 값이 그 버전에 고정된다. 공지는 제한 하나가 아니라 그 되돌림 계보(`reinstated_from`)를 덮는다 — 효력 제한은 자신이나 조상이 `covers`에 있으면 덮인 것이고, 진행 중 공지는 각 cover의 계보에 열린 제한이 있어야, 복구 공지는 계보 전체가 닫혀야 받는다(준비·확정 둘 다, Epic 3 회고 AI-1). 운영자 사건 목록의 최신 버전은 그 계보에서 지금 열린 제한(`liveCovers`)을 싣고(`ServiceNoticeApi.incidents`가 사건과 함께 한 읽기 전용 트랜잭션에서 읽는다) 화면은 `liveCovers ?? covers`를 지금 활성 목록과 교차해 덮는 제한 기본값(운영자가 고치기 전까지 목록과 함께 다시 계산)·"아직 효력 중" 집계에 쓴다. 화면의 공지 영역(`components/ServiceNotice`)은 대시보드·설정 상단의 정적 region이고 live region을 늘리지 않는다(3.5).

- 문의(3.6)는 `SupportInquiryApi` 하나가 접수·조회·집계를 하고 상태·답변은 운영 command `update-inquiry`만 바꾼다(단조 `RECEIVED → IN_PROGRESS → ANSWERED → RESOLVED`, 답변 완료 건너뛰기 금지). 답변 목표는 버전 seed 달력(`support_business_calendar`, 지금 `KR-HOLIDAY-2026.1`)의 순수 계산 `BusinessDayCalendar`이고, 달력에 없는 연도면 접수는 받고 목표는 `CALENDAR_PENDING`이며 달력이 그 연도를 담게 되면 다음 조회가 채운다(정해진 목표는 소급하지 않는다). 민감값 규칙은 `InquiryRules` 한 벌(NFKC·서식 문자 제거 뒤 판정, 금융값 모양은 3.5 공지 메시지와 공유)이고 화면(`content/support.ts`)이 같은 규칙으로 미리 안내하며, 둘의 일치는 공유 표 `frontend/src/content/inquiryRules.fixture.json`을 두 시험이 함께 읽어 강제한다. 원장·로그에는 reference digest·상태만 남고 제목·본문·이메일·답변은 없다. 해결 뒤 90일 지난 문의는 조회 트랜잭션이 먼저 지우고(백그라운드 없음), 계정 삭제는 `OperationsDeletionApi.eraseInquiries`다. 새 해 달력은 V22를 고치지 않고 새 버전 마이그레이션으로 더한다(`DEVELOPMENT.md`)(3.6).
- 관측(3.7): Actuator는 관리 포트(`BOKRILOG_MANAGEMENT_PORT` 18081, `BOKRILOG_MANAGEMENT_ADDRESS` 기본 127.0.0.1, 게시하지 않음)에서 `prometheus` 하나만 열고 외부 liveness는 `GET /api/status`다. 메트릭 이름·tag 값은 `TelemetryAllowlist`의 닫힌 표만 통과하고(공통 tag `env` = `BOKRILOG_ENV`, `uri`는 템플릿 또는 `UNKNOWN`) 새 계측은 그 표에 행을 먼저 더해야 출력에 나온다. 로그는 모든 환경에서 `RedactingJsonEncoder`의 JSON 한 줄이고(허용 키 열둘, 본문 `event`는 SECRET·EMAIL·WALLET·UUID·숫자 6자리+ 가림, 예외는 단순 이름 `exception`만·스택 없음, `correlationId` 문자 집합은 `ApiErrorHandler.CORRELATION_ID` 하나) 로그 본문에 작업·사용자 ID를 싣지 않는다 — 시험은 `LogLines`로 `event`를 꺼내 단정한다. 실제 구성 비밀값(8자 이상)이 로그에 나오면 그 줄을 가리고 `SECRET_EXPOSED`를 비동기 1회·60초 억제로 올리며(`LogSecretGuard`) 패턴 탐지는 신호가 아니다. 경보 규칙은 `deploy/observability/alerts.yml`(모든 selector `env="production"`, 미측정은 `info`·`unmeasured`)과 promtool 단위시험이고 판정은 `check-alert-rules.sh`가 소유하며 `strong_signal` label은 승인 목록(지금 비어 있다)에만 붙는다(3.7).
- 역할(3.8): 같은 jar를 `BOKRILOG_ROLE=api|worker|all`로 나눈다(`BokrilogRole`, 기본 `all` — 개발·CI·시험). 스케줄링(dispatch·heartbeat·주기 측정)은 `worker`·`all`에만 있고(`FirstSnapshotSchedulingConfig`), `worker`는 공개 포트를 루프백에 묶고 SIGTERM에 drain(claim 중지·heartbeat 유지·`drain-timeout` 120초, 종료 코드 0 drain 완료·75 drain-timeout·76 잠금 상실 — `WorkerDrain`·`WorkerExit`)하며 풀 밖 전용 연결의 PostgreSQL session advisory lock 없이는 뜨지 않는다(`WorkerSingleton`). 주기 측정 gauge(대기 나이·대기 문의)도 스케줄링 역할에만 있다(`SyncQueueAgeMetrics`·`SupportPendingMetrics` — 모듈은 루트 타입 대신 같은 판정의 식을 쓴다, 루트↔모듈 순환 금지). `BOKRILOG_ENV=production`에서 `all`은 기동 거부이고 메트릭 공통 tag에 `role`이 붙는다. 운영 배포는 `deploy/production/deploy.sh` 하나가 gate → 감사 → drain → migrate → 대기 슬롯 검증 → upstream include 교체·reload → 새 Worker 하나 순서로 하고, 단계마다 일회성 `deploy-audit` 역할(웹 없음·Flyway 검사 없음)이 원장에 `command=deploy` SYSTEM 행을 쓴다(SQL은 `OperatorAuditRepository`). Nginx 본문은 한 벌이고 upstream 블록만 include 파일이다(개발 `deploy/nginx/upstream-backend.conf`). CI 리허설만 통과했고 **운영 배포 미검증**이다(3.8).
- 백업(3.9): 운영 호스트 systemd timer가 `deploy/production/backup.sh`를 하루 한 번 돈다 — `pg_dump --format=custom`(운영 postgres 컨테이너, 로컬 소켓) → `age -R active.pub` → SHA-256 → `age -d | pg_restore --list` → 두 S3 호환 저장소(OCI·OCI 밖, rclone) `incoming/` 업로드·원격 SHA-256 대조 → `generations/` 이동이 모두 성공해야 `SUCCEEDED`이고 한쪽만으로는 성공이 아니다. 한쪽 이동이 실패하면 옮긴 쪽을 되돌린다. generation ID는 `gen_<UTC YYYYMMDD>`(같은 날 성공이면 no-op — 파기만 다시는 `backup.sh purge`), 배포와 같은 `state_dir/.lock`을 쥔다 — 잠금 안의 `owner`(kind·pid·operation·시작 시각)를 `lib.sh`의 `lock_acquire`가 두 스크립트에 쓰고, 소유자가 죽었으면 `LOCK_STALE`로 거부할 뿐 지우지 않는다. 잠금을 못 쥔 실패 행·기록하지 못한 행은 JVM 없이 `pending/` 대기열에 두고 잠금을 쥔 다음 실행이 먼저 기록하며, 기록은 psql로 행 수를 확인한다(옛 이미지는 아무것도 쓰지 않는다). 파기는 DB 없이도 저장소 목록으로 돌고(생성 후 29일 초과는 예외 없이), 수량·bytes 상한은 마지막 성공 하나를 남기되 그것을 모르면 건너뛴다. 긴 단계는 배경 실행 + `wait`라 SIGTERM이 바로 정리·`FAILED INTERRUPTED`·잠금 해제로 간다. 결과는 일회성 `backup-record` 역할(웹 없음·Flyway 검사 없음)이 추가 전용 `operations_backup_event`(V24, SQL은 `BackupEventRepository` 한 곳, 순수 운영 데이터)에 쓰고 `PURGE_FAILED`·`PURGE_OVERDUE`는 감사 원장에도 `command=backup` SYSTEM 행을 남긴다. Worker·`all`의 `BackupMetrics`는 기록이 없으면 gauge를 등록하지 않는다(NaN 금지 — `absent()`가 울려야 한다). 측정이 멈춘 저장소는 측정 시각 gauge가 늙어 `BackupStoreMeasurementStale`이 알린다. age·rclone은 `install-backup-tools.sh`의 고정 버전·SHA-256 표로만 받고(설치 manifest로 바이너리를 다시 대조한다), backup key(age identity)·rclone 설정은 `/etc/bokrilog/backup/`(600)에만 둔다 — 앱 key ring·감사 digest 키와 섞지 않고 DB·Git·로그·compose 환경·앱 프로세스에 넣지 않는다(운영 비밀 파일에 있으면 gate `BACKUP_KEY_IN_SECRETS`). 가상 시계(`BOKRILOG_BACKUP_NOW`)·결함 주입은 리허설 전용이고, MinIO 리허설만 통과했으므로 **실저장소 미검증**이다(3.9).
- 복원(3.10): 계정 삭제는 루트 `AccountErasure` 한 트랜잭션(삭제 전환 → tombstone outbox 행 → portfolio·sync·plan·source·operations·engagement erase → payload)이고 사용자 경로가 없다(호출자는 `deletion-sample` — 리허설 표지가 있고 운영 환경이 아닐 때만 — 과 복원 적용뿐, `ModuleBoundaryTest`가 고정, Epic 5). outbox V25 `operations_deletion_tombstone`(SQL은 `DeletionTombstoneRepository` 한 곳, origin `DELETION`·`RESTORE`)은 전용 키 `BOKRILOG_TOMBSTONE_DIGEST_KEY`(감사 digest 키와 바이트가 같으면 앱·gate가 거부)의 `tombstone:` HMAC·요청 ID·시각·+35일만 담는다. 15분 timer의 `tombstone-export.sh`가 기한이 24시간보다 더 남은 행 전체를 머리(키 ID·시각)와 함께 tombstone 전용 age key(backup key와 다르다)로 암호화해 OCI 밖 `tombstones/`·`latest`에 두고 사본 object는 24시간만 남긴다(외부 수명 < 35일) — 앞선 사본의 살아 있는 digest가 빠지면 `TOMBSTONE_SHRANK`로 latest를 지키고, 키 회전은 아직 없다. 백업 안의 outbox는 권위가 아니다(대조에만 쓴다). `restore.sh`는 30일 안의 offsite generation 하나를 같은 호스트의 빈 `bokrilog-restore-*` 프로젝트에 `latest` 신선도(장애 시각 − 1h) → checksum → 이름의 key ID(identity 하나면 된다) → `pg_restore` → migrate → 사본 → `restore-apply`(격리 표지 필수, 키 ID·outbox 대조 뒤 맞은 계정 재삭제·세션 전체 폐기·불변식·자격정보 표본, 수와 code만 담은 보고, 다시 돌려도 안전) 순서로 복원하고 그 뒤에만 api·worker를 `env=restore`로 띄운다(nginx·공개 포트·OIDC·Bybit 없음). 3단계 뒤의 실패는 격리 프로젝트를 volume까지 지우고(`--keep-on-failure` 예외), 남은 격리 스택이 돌면 `deploy.sh`가 거부한다. 잔여 행 검사는 모듈마다 자기 표 접두사의 사용자 열을 카탈로그로 센다(`remainingRowsOf`). 삭제의 operations 비식별(`eraseActor`·`eraseRestrictionTargets`)은 다른 모듈 erase 뒤에 감사 원장 append 잠금(`lockAppends`) 아래에서 돈다 — 앞당기면 sync 행을 쥔 채 신호를 기다리는 감지 트랜잭션과 교착한다. 삭제가 시작된 계정을 겨냥한 늦은 강한 신호는 같은 잠금 아래 operations port `AccountErasureState`(루트 구현)로 판정해 그 트랜잭션에서 비식별하며 신호를 버리지 않는다(`spec-erasure-audit-lock.md`). RPO는 장애 시각 − checksum 파일 둘째 줄의 dump 시작 시각(없으면 generation 날짜 00:00 UTC)이고 24h 초과면 `RPO_EXCEEDED`다. 배포·백업·복원·내보내기는 `state_dir/.lock` 하나를 쓰고(백업은 내보내기를 5분까지 기다리고 내보내기는 남이 쥐면 건너뛴다) 공용 셸 함수는 `backup-lib.sh`에 있다. manifest는 저장소 밖에 두고 `check-recovery-manifest.sh`가 평문 비밀을 거부한다. CI 리허설만 통과했으므로 **실제 복원 미검증**이다(3.10).

**광고 (Epic 4)**
- 광고 동의는 `engagement` 모듈의 추가 전용 결정 행(`engagement_ad_consent_decision`)이고 현재 상태는 사용자 advisory 잠금 아래 순번(`id`)이 가장 큰 행이다. 상태는 `UNDECIDED`(행 없음 또는 옛 정책 버전)·`GRANTED`·`DENIED` 셋이며 "동의 유효"는 지금 정책 버전(`AdConsentApi.POLICY_VERSION`, 지금 `AD-CONSENT-1.0`)의 `GRANTED` 하나다 — 정책 버전을 올리면 모두 다시 묻고 자동 재동의는 없다. 변경 요청은 사용자가 본 설명의 정책 버전을 싣고 다르면 409 `AD_CONSENT_POLICY_CHANGED`다(화면 상수 `AD_CONSENT_POLICY_VERSION`과 서버 상수를 시험이 대조한다 — 설명을 바꾸면 버전을 올린다). 쓰기는 사용자 잠금 → `lockWriteAccess` → 멱등 → INSERT 한 트랜잭션이고 감사 원장·재인증은 없다. 동의 판독(`AdConsentApi`)은 `engagement`와 그 컨트롤러만 의존할 수 있다 — 연결·동기화·대시보드는 동의와 무관하게 같다(`ModuleBoundaryTest`). 4.1은 광고 SDK·외부 요청·CSP·브라우저 저장소를 하나도 들이지 않았고, GPT를 load하는 예외 범위는 중립 route 한정으로 4.3 SPEC이 정한다. 동의 카피는 외부 게이트 G-4(개인정보 검토) 전 초안이다(4.1).
- 광고 자격의 결과 참조는 수동 동기화 attempt의 `jobId`이고, 후보는 그 사용자의 `purpose=SYNC`·`trigger=MANUAL`·`SUCCEEDED`·`calculation_id` 있는 attempt뿐이다(`SyncJobApi.publishedManualSync`, 판정은 attempt 행으로 하고 비후보·남의 것·없는 것은 같은 404). 판정은 사용자 잠금 → `lockWriteAccess` → 기존 행 → 후보 → 관문(동의 → `ADVERTISING` 전역 제한 → 공급자 `BOKRILOG_AD_PROVIDER_ENABLED`, 기본 꺼짐) → 빈도 제한(마지막 자격 뒤 30분·`Asia/Seoul` 하루 3회, 순수 `AdFrequencyPolicy`에 DB 시각) → `(user, jobId)` 한 행(`engagement_ad_attempt`) 순서다. ELIGIBLE 행이 곧 reservation·노출 시도이고 4.3이 쓸 불투명 `transition`을 함께 발급한다. 재전송은 결론을 낮추기만 한다 — ELIGIBLE은 관문을 다시 봐 SKIPPED가 될 수 있고 SKIPPED는 그대로다. 판정은 결과 접근을 막지 않으며 sync·plan·dashboard는 engagement에 의존하지 않는다(`ModuleBoundaryTest`). transition 소비·후보 신선도는 4.3 몫이다(4.2).
- 광고 시도(4.3): 대시보드가 자기 화면에서 ACCEPTED로 접수한 MANUAL SYNC job의 성공에만 판정을 jobId당 한 번 부르고(JOINED·자동·따라가기 이동·검증 제외, 대화상자·새 접수 중이면 이동 안 함), ELIGIBLE이면 `/ad-transition?t=<transition>`으로 문서 이동한다. 중립 화면은 `t`를 메모리로 옮겨 주소에서 지운 뒤(`replaceAddress`) `POST /api/engagement/ad-transition`으로 소비하고 — 사용자 잠금 → `lockWriteAccess` → 행 `FOR UPDATE` → `CONSUMED`·`EXPIRED`(결정 뒤 120초, DB 시각)·4.2와 같은 관문(`AdGate`) → `transition_consumed_at`(V28) 한 트랜잭션, 남의 것·없는 것은 같은 404 — SHOW일 때만 GPT web interstitial을 `결과 보기` anchor에 건다. `광고 닫기`(첫 조작·최초 포커스)와 SHOW가 아닌 모든 결말은 광고 없이 `/dashboard`로 replace한다. 공급자 활성은 `BOKRILOG_AD_PROVIDER_ENABLED`와 형식에 맞는 `BOKRILOG_AD_UNIT_PATH`(기본 빈 값)가 함께일 때다(4.3).
- 광고 fail-open(4.4): adapter 계약은 `showInterstitial(adUnitPath, onFailure(reason), onReady): () => void`(돌려받은 함수가 취소, 사유·준비 콜백은 4.5)이고, 상태 기계는 실패·취소가 종단(먼저 온 하나만)이며 준비는 시한만 해제한다 — 준비는 우리 slot의 `slotOnload`가 부른 뒤 `AD_READY_TIMEOUT_MS`(3초) 안에 오는 것, 실패는 준비 시간초과·우리 slot의 `slotRenderEnded` `isEmpty`(준비 뒤에도)·script `error`·`null` slot·command 예외이며 `onFailure` 한 번이다. 실패·취소는 시한 해제·리스너 제거·`destroySlots` 파기를 단계마다 따로 시도하고, 종단 뒤의 이벤트·시한·command와 다른 slot의 이벤트는 아무것도 부르지 않는다. 화면의 `leave`가 취소를 먼저 부른 뒤 `/dashboard`로 한 번 replace하고, `결과 보기` 클릭 뒤에는 더 이동하지 않으며, bfcache 복원(`pageshow` `persisted`)·언마운트는 취소한다. 중립 화면 전체(소비 응답 대기·준비 중 포함)는 문서 시작(`performance.now()`) 뒤 소비 창 120초(`AD_CLAIM_WINDOW_SECONDS` = `AdTransitionApi.CLAIM_WINDOW_SECONDS`, `AdClaimWindowTest`가 대조)까지만 머물고, 그때까지 `결과 보기`를 누르지 않으면(보조키 클릭은 떠남으로 치지 않는다) 또는 창이 지난 뒤 누르면 `leave`로 취소하고 결과로 간다(Epic 4 회고 AI-1). 감지하는 접근성 실패는 interstitial 미지원(`null` slot)·준비 시간초과·차단뿐이고, 소재 iframe 안 키보드·레이블은 G-4 몫이며 iframe 밖 네이티브 `광고 닫기`가 탈출구다. 재시도·fallback이 없고 adapter는 서버에 보내지 않는다 — 사유·준비는 화면이 4.5 beacon에 싣는다. 4.4가 뗀 광고 계측(156)·Web Vitals·성능 kill switch(157)는 Story 4.5가 이었다(4.4).
- 계측·kill switch(4.5): 브라우저 계측은 동일 출처 beacon `POST /api/engagement/telemetry` 하나다 — `fetch` `keepalive`에 CSRF 헤더(`sendBeacon` 금지), 본문 `{"events":[…]}` 1~16건·8 KiB 이하, 닫힌 어휘(VITAL `LCP|INP|CLS`×`/dashboard|/settings|/plan-conditions|/ad-transition`, AD `REQUESTED|READY|CLOSED|FAILED|RESULT`, `FAILED`만 사유) 밖이 하나라도 있으면 400·기록 없음, 성공 204, 계정 쓰기 거부 404. 네 화면만 `telemetry/useTelemetry`로 문서당 한 번 재고(진입·온보딩·운영자 없음) 화면 세션 토큰이 있을 때만 숨김·`pagehide`·중립 화면 떠남 직전에 보내며 실패는 무시한다(재시도·저장소 없음). 광고 이벤트는 SHOW 뒤에만 이벤트마다 한 번이고, Vitals는 문서당 metric마다 첫 보고 하나이며 `ad`는 보낼 때(그 문서에 `REQUESTED`가 있었는가) 정한다. 어휘의 두 벌은 공유 표 `frontend/src/telemetry/telemetryVocabulary.fixture.json`을 프런트·백엔드 시험이 함께 읽어 묶는다. 서버는 커밋 뒤 닫힌 카운터 셋(`bokrilog_web_vitals_total{metric,route,rating,ad}`·`bokrilog_ad_client_event_total{event,reason}`·`bokrilog_ad_transition_total{outcome}`)을 세고, 광고 문서(`/ad-transition`·`ad=true`) Vitals만 SHOW 소비(`transition_shown`) 10분 안 attempt에서 그 metric이 아직 보고되지 않았을 때(`vitals_reported_metrics`) metric마다 하나씩 사용자 열 없는 표본(V29)으로 남긴다(사용자 잠금 → `lockWriteAccess` → SHOW 행 `FOR UPDATE` 한 트랜잭션 — 한 문서의 Vitals는 여러 묶음으로 온다). Worker·`all`의 `AdPerformanceJudge`가 5분마다 24시간 창(DB 시각) 밖 표본을 지우고, 전역 `ADVERTISING` 제한이 이미 효력 중이면 판정 없이 표본을 비우며(해제 뒤 묵은 표본으로 재발동하지 않는다), 아니면 metric별 표본 ≥ 40·`percentile_disc(0.75)` > 임계면 operations `AdvertisingStopApi.stop`(원장 잠금 → 열린 전역 `ADVERTISING` 없을 때만 INSERT `origin=PERFORMANCE` → 감사 SYSTEM `performance-stop`·`AD-PERF-1.0`)을 부르고 발동 시각까지의 표본을 지운다. `PERFORMANCE`는 수동 검증 필요이고 `lift-restriction`으로만 닫힌다. 경보는 `WebVitalsAboveTarget`(POOR × 4 > 전체·표본 ≥ 40, `increase` 근사)·`WebVitalsUnmeasured`(24시간 증가 0 또는 absent)다. 이 계측만으로 유광고 출시 준비 완료를 적지 않는다(G-4)(4.5).

**시험·CI**
- 순차 시험 중에도 scheduler·virtual worker는 실행된다. DB 초기화는 public 전체 테이블의 NOWAIT 잠금을 확보한 트랜잭션에서 하며, 경합이면 부분 잠금을 모두 롤백한 뒤 기다린다. 정리 시작 뒤 오류는 재시도하지 않는다(PR #63).
- 시험 클래스는 Gradle이 JVM(fork) 셋에 class 단위 round-robin으로 나눈다(`maxParallelForks = 3`). JVM마다 PostgreSQL 컨테이너 하나(`PostgresTestcontainersConfig`의 static 컨테이너)이고 위 NOWAIT 잠금도 그 컨테이너 안이라 fork 사이에 DB·포트·파일을 공유하는 시험이 없어야 한다 — 서버 포트는 전부 임의(`RANDOM_PORT`·루프백 0)이고 고정 포트·`System.setProperty`·공유 파일 경로를 쓰는 시험을 들이지 않는다. JVM 안 병렬(JUnit `junit.jupiter.execution.parallel.*`)과 컨테이너 공유(`reuse`)는 쓰지 않는다(`spec-ci-test-forks.md`).
- 시험의 멱등 키·토큰 모양 값은 리터럴로 쓰지 않는다. gitleaks `generic-api-key`가 잡고, PR 커밋 범위가 스캔되므로 뒤 커밋으로는 지워지지 않는다(PR #60).
- `inert` 배경으로 돌려주는 대화상자의 초점 복귀만 passive 정리이고 나머지 초점 이동은 layout effect다(PR #57·#58, `DEVELOPMENT.md`).

## 미룬 결정

`deferred-work.md`가 항목을 추가만 하는 로그(현재 169건)이고 판정은 `deferred-work-resolutions.md`의 상태 요약 표가 소유한다(해소 32·부분 해소 23·미해소 114·미판정 0). 2026-10-15 판정 라운드(`spec-deferred-work-verdict-round.md`)가 보류 셋(92·129·131)을 빼고 모두 판정했고, 그 셋은 CI 임계 경로 chore(`spec-ci-critical-path.md`, PR #104)의 done 전환이 판정했다(131 해소, 92·129 미해소). Story 4.1이 더한 147(광고 동의 결정 빈도 제한)·148(동의 이력 보존 기한)은 그 done 전환이, Story 4.2가 더한 149·150(transition 소비·후보 신선도, 주인 4.3)·151(공급자 변수 배포 연결)·152(자격 결정 행 보존 기한)는 4.2 done 전환이 미해소로 판정했다. Story 4.3 done 전환은 149·150을 해소로, 4.3이 더한 153(CSP 후보값 실측·SHOW 왕복)·154(광고 단위 경로 형식)·155(주입 origin e2e 새 스택 첫 실행 간헐 실패)를 미해소로 판정했다. Story 4.4 done 전환은 4.4가 더한 156(광고 이벤트 계측)·157(Web Vitals 수집과 성능 kill switch)·158(Windows 로컬 Vitest의 lazy chunk 대기)을 미해소로 판정했고, 4.4를 결정 지점으로 지목한 122의 주인을 157로 옮겼다. Story 4.5 done 전환은 그 셋(122·156·157)을 해소로 판정했다(4.5는 defer 0건). 회고 AI-2·AI-3 chore가 더한 159(전체 빌드에서만 나는 계획 종단 시험 간헐 실패)는 미해소다. 세션 속성 upsert fix가 155(로그인 직후 미인증)의 원인을 고쳐 해소했고, 그 fix가 더한 160(세션 행 삭제 뒤 속성 저장의 FK 위반)·161(세션 확인 500을 미인증 문구로 그림)은 미해소다. 회고 AI-6 chore(간헐 실패 진단 장치)는 158·159를 부분 해소로 판정했다(Windows 재확인·원인 확정이 남았다). 계정 삭제·감사 원장 잠금 직렬화 fix가 더한 162(삭제 커밋 뒤 append되는 운영자 거절·실패 행의 `actor_user_id`, 결정 지점 Epic 5의 5.2·5.4)는 미해소다. 2026-11-03 CI 시간 분석이 더한 163(`backend-quality` 시험의 JVM 병렬화)·164(리허설 셋을 백엔드 변경 PR의 필수 경로에서 빼는 정책)와 그 chore의 리뷰가 더한 165(소비 job 넷의 내려받은 jar 대조 없음)는 CI backend jar 생성 job chore(`spec-ci-backend-assemble-job.md`)가 미해소로 판정했고, 그 chore가 건드리지 않은 20(통합 job의 `npm ci`·Chromium 설치 중복)은 미해소 그대로다. 그 가운데 163은 CI 시험 fork 분할 chore(`spec-ci-test-forks.md`, `maxParallelForks = 3`)가 부분 해소로 판정했다 — 로컬 실측만 있고, PR CI `backend-quality` 실측이 그 SPEC AC 1의 범위(≤ 10분)에 들면 PR에서 해소로 올린다(164·165는 미해소 그대로). 루트 컨트롤러 공통 부품 chore(`spec-controller-commons.md`)가 Epic 4 회고 AI-8을 나누며 더한 166(프런트엔드 POST·JSON 판독 helper)·167(광고 시험 helper·engagement 바깥 의존 허용 목록)은 그 done 전환이 미해소로 판정했고 주인은 `epic-4-retro-item-47`(in-progress)의 다음 chore다. CI 시험 fork 분할 chore의 리뷰가 더한 168(fork 격리 불변식의 `src/test/java` 소스 스캔 시험)·169(CI에서 시험 fork 수의 기계 판정)는 그 chore가 미해소로 판정했다. 판정 재개를 요구한 세 액션 가운데 Epic 2 회고 AI-6(`epic-2-retro-item-18`)·Epic 3 회고 AI-11(`epic-3-retro-item-36`)은 done이고 Epic 1 회고 액션 8(`epic-1-retro-item-8`)은 in-progress다. 출시 전에 닫을 항목(보안·삭제·데이터 정확성) 목록은 원장 상태 요약의 "2026-10-15 갱신(판정 라운드)" 문단이 소유한다. 회고가 실연동 게이트 전에 닫으라고 남긴 둘 가운데 연쇄 승인(재인증 시각을 지우는 코드가 없던 것, AI-8 범위)은 `spec-sensitive-command-commons.md`가 `SessionRotation`으로 닫았고, `onFailure`가 무장을 무조건 버리는 것 하나만 아직 열려 있다.

## 브랜치·커밋

git flow를 따른다. `main`은 릴리스만 받고 직접 커밋하지 않는다. `develop`이 통합 브랜치이며 모든 토픽 브랜치는 `develop`에서 분기한다.

병합은 GitHub PR의 `Create a merge commit`으로만 한다. `develop`·`main`에 직접 push하지 않는다. `Squash and merge`·`Rebase and merge`는 토픽 브랜치 경계를 지우므로 쓰지 않으며 ruleset도 막는다. 병합 후 토픽 브랜치는 삭제한다.

- `feature/<에픽>-<스토리>-<slug>` — 스토리 구현. 예: `feature/1-3-identity-ownership`
- `chore/<slug>` — 도구·환경·저장소 정비, sprint-status `done` 전환
- `docs/<slug>` — 기획 산출물·문서
- `fix/<slug>` — `develop` 위 결함 수정
- `hotfix/<slug>`, `release/<version>` — 릴리스 이후·릴리스 준비에만 사용

slug는 짧은 영문 소문자 kebab-case다. 한글 브랜치명과 도구 이름(`claude` 등) 접두사는 쓰지 않는다. 커밋 메시지는 Conventional Commits의 영문 소문자 subject를 따르고 유형은 `feat` `fix` `chore` `docs` `refactor` `test`다.

## 주의

- BMAD 인스톨러를 다시 실행하면 `_bmad/scripts/`의 로컬 수정(UTF-8 stdout)이 업스트림 원본으로 덮어쓰인다. 절차와 복구 방법은 `DEVELOPMENT.md`의 `## 에이전트 타깃`에 있다.
- 제품 기술 스택은 `ARCHITECTURE-SPINE.md`가 권위 기준이다(Java/Spring/PostgreSQL, React/Vite). 이 저장소의 Python 환경은 기획 도구 전용이며 제품 런타임과 무관하다.
- `.idea/`, `.claude/settings.local.json`, `compose.override.yaml`, `.env*`는 기기별 파일이라 커밋하지 않는다. 저장소가 소유하는 compose 구성은 `deploy/compose/compose.yaml` 하나다.
- 이 파일의 `## 현재 진행 지점`은 스토리가 머지될 때마다 갱신한다. 표에 한 줄을 더하고 다음 스토리를 바꾸며, 여러 스토리에 걸치는 새 계약이 있을 때만 `## 스토리가 고정한 계약`에 한 줄을 더한다. 스토리 서술(도입한 것, 리뷰 경위)은 SPEC에 두고 여기에 쓰지 않는다 — 이 파일은 모든 세션과 서브에이전트에 통째로 실린다. `DEVELOPMENT.md`와 `epic-1-context.md`에 같은 진행 문장이 있으면 함께 맞춘다.
- `AGENTS.md`는 Codex가 읽는 진입 파일이며 이 파일을 대체하지 않는다. 규칙의 권위는 `CLAUDE.md` 하나이고 `AGENTS.md`는 오리엔테이션·실행 명령과 "제품 경로를 바꾸기 전에 `CLAUDE.md`를 읽으라"는 지시만 담는다. 규칙을 그리로 복사하지 않는다 — 두 벌이 되면 갈라지고 맞춰 줄 검사가 없다. `AGENTS.md`의 `bmad:context` 마커 안쪽은 `bmad-project-context` 스킬이 소유하며 refresh 때 교체된다.
