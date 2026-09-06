---
title: 'Story 1.2 — 동일 출처에서 서비스 화면과 서버 상태 확인하기'
type: 'feature'
created: '2026-09-06'
status: 'done'
review_loop_iteration: 0
baseline_commit: '3903deaa6d2f342df92d593b9ab8004a5de83ce2'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 저장소에는 `frontend/` 정적 SPA만 있고 서비스 진입점과 백엔드가 없다. 방문자는 `vite preview`로만 화면을 볼 수 있어 실제 배포 형태의 서비스 경계 — 같은 origin에서 화면과 서버 응답을 함께 받는 것 — 를 확인할 수 없고, Epic 1의 이후 스토리가 올라갈 서버 기반도 없다.

**Approach:** `backend/`에 Gradle·Java 25·Spring Boot 4.1.1 최소 애플리케이션과 인증이 필요 없는 `/api/status` 하나를 세우고, `deploy/`에 Nginx가 `frontend/dist/`를 루트로 제공하고 `/api`만 백엔드로 프록시하는 개발·CI 공용 Docker Compose 구성을 만든다. curl 기반 통합 스크립트와 Nginx origin을 향한 Story 1.1 Playwright 회귀 실행으로 루트·상태·알 수 없는 API·백엔드 비가용을 재현 가능하게 검증하고, CI를 `frontend-quality`·`backend-quality` → `same-origin-integration` → `ci-required` 구조로 전환한다.

## Boundaries & Constraints

**Always:**
- 스택은 `ARCHITECTURE-SPINE.md` Stack 표를 따른다. Java 25 LTS, Spring Boot 4.1.x(4.1.1 GA 확인), Nginx 1.30.x stable(`nginx:1.30.4-alpine` 확인), 런타임 이미지 `eclipse-temurin:25-jre-alpine-3.24`. Gradle 버전은 wrapper가 소유하고 toolchain 자동 프로비저닝은 끈다.
- Nginx는 `/api`만 백엔드로 프록시한다. 나머지 경로는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다.
- 오류 응답은 성공·빈 데이터로 위장하지 않고 안정적인 machine code·사용자 메시지·correlation ID만 담는다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다.
- 백엔드는 데이터베이스·사용자 테이블·제공자 자격정보 없이 기동한다.
- 브랜치 보호가 참조하는 필수 검사 이름은 `ci-required`로 유지한다.
- Story 1.1의 Vitest·Playwright 시험은 시험 본문 수정 없이 계속 통과한다.
- 신규 파일은 LF다. `gradlew`·`*.jar`·`*.bat` 규칙은 `.gitattributes`에 이미 있다.

**Ask First:**
- 고정된 스택 버전(Spring Boot 4.1.1, Java 25, Nginx 1.30.4)이 해결되지 않을 때. 조용히 낮추지 말고 보고한다.
- gitleaks가 `application.yml`이나 compose 파일에서 무언가를 탐지할 때. 규칙을 완화하지 말고 자격정보 모양 리터럴 자체를 없앤다.
- CI wall-clock p95가 CI-PLAN §4·§10 예산(백엔드 도입 후 8분)을 넘을 때. 필수 검사를 빼서 맞추지 않는다.
- Spring Modulith·Actuator·Spring Security·PostgreSQL 의존성 추가.

**Never:** 사용자 DB·마이그레이션·세션 저장소. 실제 소셜 인증·OIDC·제공자 SDK. Bybit 연결. production compose 프로필·이미지 digest 고정·Terraform·Alloy·배포 자동화. 프런트엔드 **애플리케이션 코드**에 `/api` 호출·라우터·`app-shell` 전역 탐색 추가(시험 구성 파일은 예외). 브라우저 저장소 쓰기·외부 폰트·CDN·분석. `<meta>` CSP.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 서비스 루트 | compose 실행, `GET /` | 200, `frontend/dist/index.html`, Story 1.1 진입 화면 | N/A |
| 상태 확인 | `GET /api/status` | 200 `application/json`, 고정된 최소 필드만 | N/A |
| 알 수 없는 API | `GET /api/nope` | 404 JSON, 안정 code·사용자 메시지·correlation ID | 내부 경로·스택 미포함 |
| 잘못된 메서드 | `POST /api/status` | 405 JSON, 같은 오류 계약 | Spring 기본 오류 본문 미노출 |
| 백엔드 비가용 | 백엔드 중지 후 `GET /api/status` | 502 또는 504 JSON(연결 불가·응답 지연을 그대로 반영), 같은 오류 계약 | Nginx 기본 HTML·upstream 주소·버전 미노출 |
| 알 수 없는 정적 경로 | `GET /nope` | 404 | index.html 대체 없음(라우터가 없다) |
| 응답 헤더 | 모든 응답 | CSP·`X-Content-Type-Options`·`Referrer-Policy`·`X-Frame-Options`가 붙고 `Server` 버전이 없다 | N/A |

</frozen-after-approval>

## Code Map

- `frontend/dist/` -- Nginx가 제공할 산출물. `vite.config.ts`에 `base` 미설정이라 asset URL이 `/assets/...` 루트 절대경로다. 반드시 origin 루트에 mount한다.
- `frontend/index.html:16-19` -- favicon이 인라인 `data:` URI다. `/favicon.ico` 라우트를 만들면 "요청 0건" 계약이 깨진다.
- `frontend/index.html:24-65` -- `noscript` 폴백이 **인라인 `style` 속성**을 쓴다. CSP에 `style-src-attr`가 없으면 JS 없는 폴백의 스타일이 죽는다.
- `frontend/e2e/entry-screen.spec.ts:239-274` -- 외부 요청 0건·`xhr`/`fetch` 0건 단언. `:252-254` 주석이 `origin.startsWith` 포트 접두 충돌을 경고한다.
- `frontend/e2e/entry-screen.spec.ts:276-307` -- 동일 출처 4xx/5xx 0건. Nginx가 추가 요청(robots·autoindex 리다이렉트)을 만들면 깨진다.
- `frontend/playwright.config.ts:4-6,47-53` -- `BASE_URL`·포트 상수와 `preview` webServer. 외부 origin 주입 지점이 여기다. `playwright.config.test.ts`는 `webServer`·`baseURL`을 단언하지 않아 안전하다.
- `.github/workflows/ci.yml:48-58` -- 단일 `ci-required` job. `:82-101` gitleaks, `:114-146` classify, `:148-208` 프런트엔드 10단계, `:210-225` 진단 업로드.
- `.github/scripts/test-ci-scripts.sh:583-700` -- ci.yml 텍스트 계약. `runs-on` 정확히 1회(`:598`), 액션 SHA 각 1회(`:609-616`), `docs-only` gate 정확히 10회(`:618-621`)를 단언하므로 job 분리 시 **반드시 함께 재설계**한다.
- `.github/scripts/classify-changes.sh:112-122` -- `<dir>/<file>` 경로는 자동으로 product-change다. `backend/`·`deploy/` 추가에 분류 규칙 변경은 필요 없다.
- `.gitignore:26-32` -- `build/`·`.gradle/`은 이미 제외. `gradle-wrapper.jar`는 제외되지 않아 정상 커밋된다.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 항목 14(CSP·보안 응답 헤더)를 이 스토리가 소유한다. 판정을 여기에 기록한다.
- `DEVELOPMENT.md:66-160`, `CLAUDE.md` -- 프런트엔드·CI 절 옆에 백엔드·통합 실행 절차를 추가한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/gradlew`, `backend/gradle/wrapper/*`, `backend/settings.gradle.kts`, `backend/build.gradle.kts` -- Gradle wrapper와 Java 25 toolchain(자동 프로비저닝 끔), Spring Boot 4.1.1 `web`·`test` starter만 선언 -- AC-1의 재현 가능한 빌드 진입점
- [x] `backend/src/main/java/com/bokrilog/BokriLogApplication.java` -- `@SpringBootApplication` 진입점. 모듈 패키지는 만들지 않는다 -- Story 1.3이 `identity`를 첫 모듈로 도입한다
- [x] `backend/src/main/java/com/bokrilog/StatusController.java` -- `GET /api/status`만, 고정된 최소 필드 -- AC-1
- [x] `backend/src/main/java/com/bokrilog/ApiErrorHandler.java` -- 404·405·500을 공통 오류 계약 JSON으로 변환하고 Spring 기본 오류 본문(whitelabel, `trace`, `path`)을 차단 -- AC-3
- [x] `backend/src/main/resources/application.yml` -- 포트, `server.error` 노출 끄기, 최소 로그 설정. 자격정보 모양 리터럴 금지 -- AC-3
- [x] `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- I/O 표의 상태·알 수 없는 경로·잘못된 메서드와 누출 금지를 시험 -- AC-1·AC-3
- [x] `backend/Dockerfile` -- `eclipse-temurin:25-jre-alpine-3.24` 위에 미리 빌드한 jar만 올리는 런타임 전용 이미지 -- CI가 재빌드 없이 산출물을 소비한다
- [x] `deploy/nginx/bokrilog.conf` -- 루트 정적 제공, `/api` 프록시(정적 처리보다 먼저 매칭), 오류 JSON, 보안 헤더, `server_tokens off` -- AC-2·AC-3
- [x] `deploy/compose/compose.yaml` -- `nginx:1.30.4-alpine`과 백엔드 두 서비스. `frontend/dist`와 Nginx 설정을 읽기 전용 마운트 -- AC-2
- [x] `deploy/compose/verify-same-origin.sh` -- compose 기동 → I/O 표 전 행 확인 → 백엔드 중지 후 502 또는 504 확인 → 로그 누출 검사 → 정리. 로컬·CI 동일 명령 -- AC-4
- [x] `frontend/playwright.config.ts` -- 외부 `BASE_URL`이 주어지면 자체 `preview` webServer를 띄우지 않게 한다. 기본 동작은 그대로 -- AC-4의 브라우저 검증을 Nginx origin에서 재사용
- [x] `frontend/playwright.config.test.ts` -- 위 분기의 두 경우를 단언 -- 회귀 경로가 조용히 사라지지 않게 한다
- [x] `.github/workflows/ci.yml` -- `changes` → `frontend-quality`·`backend-quality` → `same-origin-integration` → `ci-required` 집계로 전환. 이름 `ci-required` 유지, 통합 입력 artifact retention 1일, `if: always()` 집계에서 `failure`·`cancelled`·예상 밖 `skipped`를 실패로 판정 -- CI-PLAN §1·§8
- [x] `.github/scripts/test-ci-scripts.sh` -- 단일 job을 전제한 단언을 새 job 구조 계약으로 재설계 -- 계약 시험이 스스로 실패하지 않게 한다
- [x] `.gitignore` -- compose 로컬 오버라이드와 `.env` 제외 추가 -- 로컬 설정 커밋 방지
- [x] `DEVELOPMENT.md`, `CLAUDE.md` -- JDK 25 준비, 백엔드·compose 명령, 새 CI job 구조를 반영 -- AC-4의 "문서화된 절차"
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 항목 14의 판정을 기록. CI-PLAN §16의 "실제 검사가 통과한 뒤에만" 조건 중 GitHub 실행분이 남아 있으므로 **부분 해소**로 남기고, 이 스토리의 PR이 green이 된 뒤 해소로 올린다. 채택하지 않은 헤더(`Permissions-Policy`, COOP·CORP, HSTS, `index.html` 대 해시 자산의 `Cache-Control`)와 그 소유자도 함께 기록한다 -- 원장 갱신 규칙

**Acceptance Criteria:**
- Given JDK 25만 준비된 깨끗한 체크아웃, when `backend/gradlew --project-dir backend build`를 실행하면, then 데이터베이스·자격정보 없이 시험이 통과하고 실행 가능한 jar가 생성된다
- Given `frontend/dist`와 백엔드 jar가 이미 빌드된 상태, when `deploy/compose/verify-same-origin.sh`를 실행하면, then I/O 표의 모든 행이 재빌드 없이 검증되고 종료 코드가 0이다
- Given compose가 실행 중, when Story 1.1의 Playwright 진입 화면 시험을 Nginx origin에 대해 실행하면, then 13개 시험이 본문 수정 없이 통과한다(`frontend/e2e/entry-screen.spec.ts`의 실제 개수이자 CI-PLAN §3이 적은 수다)
- Given 어떤 오류 응답이나 Nginx·백엔드 로그, when 내부 경로·스택·환경 변수 이름을 검색하면, then 0건이다
- Given 변경된 워크플로, when `.github/scripts/test-ci-scripts.sh`를 실행하면, then 새 job 구조 계약이 통과한다

## Spec Change Log

- **2026-09-06 · 사람 재협상(frozen 블록 수정)** — 리뷰가 `error_page 502 503 504 = @upstream_unavailable`이 504·503을 502로 축약하는 결함을 지적했고, 수정 뒤 실측하자 백엔드 중지 직후에는 커널 neighbour 항목 유효 여부에 따라 502(EHOSTUNREACH)와 504(connect timeout)가 모두 나타났다. 매트릭스의 "502"는 이전 구현이 상태를 강제해 가린 값이었다. 사용자가 "502 또는 504"로 계약을 재협상해 해당 행을 수정했다. 회피한 상태: 실제 상태를 감추는 고정 502. KEEP: `error_page`에 `=`를 쓰지 않아 원 상태를 보존하는 방식, 검증 스크립트의 "둘 중 하나 + 본문 정확 일치" 단정, `compose pause`로 얻는 결정적 504 시험.

## Design Notes

**CSP는 Nginx 응답 헤더가 유일한 정의다**(deferred 항목 14). `index.html`이 인라인 `style` 속성을 쓰므로 `style-src-attr`를 빼면 noscript 폴백이 깨진다. 시작점:

```
default-src 'none'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline';
img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
```

**응답 본문 두 가지가 계약의 전부다.** 시험이 리터럴로 고정할 수 있게 모양을 여기서 확정한다.

```json
{ "status": "UP" }
{ "code": "NOT_FOUND", "message": "요청한 경로를 찾을 수 없습니다.", "correlationId": "..." }
```

상태 응답에 시각·버전·호스트명·빌드 정보를 넣지 않는다. `code`는 `NOT_FOUND`·`METHOD_NOT_ALLOWED`·`UPSTREAM_UNAVAILABLE`·`INTERNAL_ERROR` 네 개로 시작한다.

**오류 계약은 한 벌이다.** 같은 JSON 모양을 Spring(404·405·500)과 Nginx(502·504·정적 404)가 공유한다. Nginx는 `error_page`로 정적 JSON을 돌려주고 correlation ID는 `$request_id`를 쓴다.

**포트.** Playwright의 `origin.startsWith` 비교가 포트 접두에 취약하다(`4183` 대 `41830`). Nginx 호스트 포트는 `4183`으로 시작하지 않는 값을 쓴다.

**Modulith 없음.** 상태 엔드포인트를 `com.bokrilog` 루트 패키지에 둔다. Spring Modulith는 루트 패키지를 모듈로 보지 않으므로 Story 1.3이 `identity`를 첫 모듈로 넣을 때 경계 검증이 깨끗하게 시작된다.

## Verification

**Commands:**
- `brew install openjdk@25` 뒤 `~/Library/Java/JavaVirtualMachines`에 심볼릭 링크 -- expected: JDK 25 준비(최초 1회, `DEVELOPMENT.md` `## 백엔드`의 절차)
- `backend/gradlew --project-dir backend build` -- expected: 컴파일·시험 통과, `backend/build/libs/*.jar` 생성
- `npm --prefix frontend run build` -- expected: `frontend/dist/` 생성
- `deploy/compose/verify-same-origin.sh` -- expected: 모든 단언 통과, exit 0
- `npm --prefix frontend run test` / `run test:e2e` -- expected: Story 1.1 회귀 통과
- `npm --prefix frontend run typecheck` / `run lint` / `run lint:css` / `run format:check` -- expected: 각 0건
- `.github/scripts/test-ci-scripts.sh` -- expected: 새 계약 통과
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

## Suggested Review Order

**진입점 — 동일 출처 경계의 핵심 결정**

- `/api`만 프록시한다는 불변식을 경로 구분자로 강제. `/apifoo`는 정적 404
  [`bokrilog.conf:111`](../../deploy/nginx/bokrilog.conf#L111)

**Nginx — 오류 계약과 헤더**

- `error_page`에 `=`가 없어 원 상태(502·504·413)가 보존되고 본문만 JSON으로 바뀐다
  [`bokrilog.conf:151`](../../deploy/nginx/bokrilog.conf#L151)
- 413·400·414·431도 같은 계약. `client_max_body_size`가 만든 경로를 닫는다
  [`bokrilog.conf:162`](../../deploy/nginx/bokrilog.conf#L162)
- CSP·보안 헤더는 이 한 `add_header` 그룹이 유일한 정의. 상속 함정 때문에 한 곳
  [`bokrilog.conf:71`](../../deploy/nginx/bokrilog.conf#L71)
- `$request_id`를 access log에 남겨 응답 헤더·백엔드 로그와 한 ID로 잇는다
  [`bokrilog.conf:23`](../../deploy/nginx/bokrilog.conf#L23)
- 정적 루트. 라우터가 없으므로 SPA fallback을 두지 않는다
  [`bokrilog.conf:122`](../../deploy/nginx/bokrilog.conf#L122)

**Spring — 오류 계약 한 벌**

- Spring 기본 오류 본문(`timestamp`·`path`·`trace`)을 전부 차단하는 진입점
  [`ApiErrorHandler.java:37`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L37)
- 상태→code 매핑. 4xx는 `BAD_REQUEST`, 404·405만 전용 code
  [`ApiErrorHandler.java:123`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L123)
- 분류 안 된 예외는 500. 메시지·스택을 응답과 로그 모두에서 뺀다
  [`ApiErrorHandler.java:104`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L104)
- 프레임워크가 준 `headers`를 보존해 405의 `Allow`가 살아남는다
  [`ApiErrorHandler.java:83`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L83)
- 진입점의 `X-Request-Id`를 sanitize해 correlation ID로 재사용
  [`ApiErrorHandler.java:136`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L136)
- `BasicErrorController`를 대체해 컨테이너 수준 오류도 같은 계약
  [`ApiErrorHandler.java:168`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L168)
- 유일한 API. 고정 필드 `{"status":"UP"}`만
  [`StatusController.java:24`](../../backend/src/main/java/com/bokrilog/StatusController.java#L24)

**통합 검증 스크립트 — 로컬·CI 동일 명령**

- `--keep-stack`: 스택을 한 번만 세우고 Story 1.1 회귀가 같은 스택을 쓴다
  [`verify-same-origin.sh:79`](../../deploy/compose/verify-same-origin.sh#L79)
- 종료 코드로 정리 여부 결정. 실패·중단은 옵션과 무관하게 정리
  [`verify-same-origin.sh:108`](../../deploy/compose/verify-same-origin.sh#L108)
- CSP 전문 동등 비교. 지시어 하나만 바뀌어도 실패
  [`verify-same-origin.sh:268`](../../deploy/compose/verify-same-origin.sh#L268)
- `pause`로 결정적 504. 상태를 502로 뭉개지 않음을 여기서 증명
  [`verify-same-origin.sh:460`](../../deploy/compose/verify-same-origin.sh#L460)
- `stop` 뒤에는 커널 상태에 따라 502 또는 504. 본문은 글자까지 고정
  [`verify-same-origin.sh:494`](../../deploy/compose/verify-same-origin.sh#L494)
- 스택을 남길 때 백엔드를 다시 띄워 건강한 상태로 넘긴다
  [`verify-same-origin.sh:574`](../../deploy/compose/verify-same-origin.sh#L574)

**CI — 4-job 구조와 fail-closed 집계**

- 두 품질 job 산출물을 재빌드 없이 소비하는 통합 job. 예산 15분의 근거
  [`ci.yml:318`](../../.github/workflows/ci.yml#L318)
- 검증 스크립트 호출 한 줄. `--keep-stack`이 없으면 회귀가 죽은 origin을 본다
  [`ci.yml:369`](../../.github/workflows/ci.yml#L369)
- origin 주입. 없으면 Playwright가 자기 preview를 검증하며 green
  [`ci.yml:392`](../../.github/workflows/ci.yml#L392)
- `ci-required`는 이름만 유지하고 집계만 한다. `always()`로 반드시 판정을 남김
  [`ci.yml:410`](../../.github/workflows/ci.yml#L410)
- `changes` 실패는 단일 원인으로 보고. 나머지는 `판정 불가`
  [`aggregate-results.sh:72`](../../.github/scripts/aggregate-results.sh#L72)
- job별 단계 존재를 고정. Vitest 단계를 지우면 여기서 걸린다
  [`test-ci-scripts.sh:888`](../../.github/scripts/test-ci-scripts.sh#L888)
- 집계 스크립트 13개 시나리오. `GITHUB_STEP_SUMMARY`를 sink로 격리
  [`test-ci-scripts.sh:663`](../../.github/scripts/test-ci-scripts.sh#L663)

**브라우저 검증 — Story 1.1 회귀의 재사용**

- 주입된 origin이면 `webServer` 키 자체를 두지 않는다
  [`playwright.config.ts:83`](../../frontend/playwright.config.ts#L83)
- CSP 위반은 Playwright가 실패시키지 않으므로 이벤트를 직접 듣는다
  [`same-origin-csp.spec.ts:47`](../../frontend/e2e/same-origin-csp.spec.ts#L47)
- 주입 없는 기본 실행에서는 건너뛴다. 기존 13개 시험은 무수정
  [`same-origin-csp.spec.ts:28`](../../frontend/e2e/same-origin-csp.spec.ts#L28)

**빌드·런타임 주변**

- Java 25 toolchain. 자동 프로비저닝은 `gradle.properties`가 끈다
  [`build.gradle.kts:24`](../../backend/build.gradle.kts#L24)
- 프로젝트 정책으로 저장소에 고정
  [`gradle.properties:4`](../../backend/gradle.properties#L4)
- graceful shutdown 5초 ≤ compose `stop_grace_period` 10초
  [`application.yml:35`](../../backend/src/main/resources/application.yml#L35)
- 런타임 전용 이미지, 비-root. 빌드는 이미지 밖
  [`Dockerfile:20`](../../backend/Dockerfile#L20)
- 바깥에 열리는 포트는 Nginx 하나. 백엔드는 `expose`만
  [`compose.yaml:63`](../../deploy/compose/compose.yaml#L63)
- 500 경로를 실제 HTTP로 통과시키는 시험 전용 엔드포인트
  [`StatusApiTest.java:179`](../../backend/src/test/java/com/bokrilog/StatusApiTest.java#L179)
- 컨테이너 ERROR dispatch가 상태와 code를 유지하는지 확인
  [`StatusApiTest.java:223`](../../backend/src/test/java/com/bokrilog/StatusApiTest.java#L223)

**문서·원장**

- 항목 14 부분 해소. 남은 조건(GitHub green)과 미채택 헤더의 소유를 기록
  [`deferred-work-resolutions.md:84`](deferred-work-resolutions.md#L84)
- CI 게이트 서술을 현재 상태로. 복원된 승인 제약도 이 파일
  [`epic-1-context.md:45`](epic-1-context.md#L45)
- 백엔드 준비(JDK 25 심볼릭 링크·Windows)와 동일 출처 실행 절차
  [`DEVELOPMENT.md:136`](../../DEVELOPMENT.md#L136)
