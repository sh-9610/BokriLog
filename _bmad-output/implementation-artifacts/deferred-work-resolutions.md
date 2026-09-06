---
title: 'Deferred Work 해소 원장'
status: living
created: '2026-09-02'
updated: '2026-09-06'
applies_to: '_bmad-output/implementation-artifacts/deferred-work.md'
decision_baseline: 'ERD-1.0.0'
---

# Deferred Work 해소 원장

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 스킬 정의(`.claude/skills/bmad-build/step-04-review.md`)가 기존 항목의 수정을 금지하므로, 해소 여부는 그 파일을 고치지 않고 이 원장에 기록한다.

항목 순서는 `deferred-work.md`의 등장 순서와 같다. 판정 기준은 `ERD-1.0.0`, PRD `2026-08-23` 판, `epics.md`(`implementationReadinessStatus: ready-for-sprint-planning`)다.

## 상태 요약

| 상태 | 건수 |
|---|---|
| 해소 | 10 |
| 부분 해소 | 3 |
| 미해소 | 9 |

미해소·부분 해소 12건 중 개발 스프린트를 막는 항목은 없다. 부분 해소 3건 가운데 4·7은 외부 승인 게이트(G-5, 출시 전 사용자 과업)에 묶여 있고, Story 1.2가 소유하던 14(CSP·보안 응답 헤더)는 구현과 로컬 검증을 마쳤으나 CI-PLAN §16이 요구하는 GitHub 실행 green이 남아 **부분 해소**다. 미해소 9건(13·15–22)은 각각 소유 스토리, 조건부 의존 갱신 PR 또는 별도 작업이 정해져 있거나, 소유 스토리를 정하는 것 자체가 그 항목의 내용이다. Story 1.2 완료 전 조건이던 12(린터·포매터)는 2026-09-05에 해소됐다. 항목 19~22는 Story 1.2가 2026-09-06에 새로 append한 것이다. 표는 항목별 판정을 다시 세어 2026-09-06에 맞췄다.

## 항목별 판정

### 1. 장기 미접속 시 Transaction Log 보존기간 밖 자금흐름 탐지·격리

**해소.** `ERD-1.0.0` §2.2가 Transaction Log의 공식 조회 가능 범위를 최대 2년으로 고정하고, 필요한 구간이 그 범위를 벗어나거나 필수 페이지를 증명하지 못하면 기존 계획 catch-up을 `복구 불가`로 종료하며 마지막 정상 결과를 보존하고 새 계획 선택을 안내한다. `epics.md`의 `G-1 부분 확정`이 30일 초과 장기 미동기화의 사용자 선택 분기를 함께 확정했다.

### 2. Product Brief 부록의 Forge 정본 상대경로 링크

**해소(2026-09-02).** `briefs/brief-BokriLog-2026-08-16/addendum.md`의 `forged-idea.md` 코드 표기를 실제 정본 위치를 가리키는 상대경로 링크 `../../../forge/read-only-crypto-goal-tracker/forged-idea.md`로 교체했다.

### 3. Day 60 고정 IP 전환 실패 시 신규 연결 중단과 Day 90 종료 조건

**해소.** `BYBIT-IP-1.0`(`ERD-1.0.0` §10)이 신규 연결의 regional reserved public IP binding 요구, 기존 미제한 연결의 Day 60 사전 공지와 Day 90 sync·재연결 정지, 키 교체 시 provider account 재사용을 확정했다. 구현 지점은 `epics.md` Story 2.1의 연결 admission이며 PRD §12.1에 해소 참조를 남겼다.

### 4. 사용자 요청에 따른 삭제 범위와 완료 시한

**부분 해소.** 삭제 **범위**는 확정됐다. `epics.md`의 `데이터 도입 시 삭제 계약·테스트 기준`이 모듈별 deletion port와 소유권·시험 기준을 고정하고, Epic 5 Story 5.1~5.4가 전체 계정 삭제·부분 실패 추적·tombstone·재활성화 차단을 담당한다. `ERD-1.0.0` §9는 개발·CI 기본값을 법정 분리 보존 `없음`으로 두고 모든 사용자 데이터를 삭제한다.

**미해소 잔여:** 법정 분리 보존의 대상 필드·법적 근거·목적·기간과 그에 따른 완료 시한. 게이트 G-5로 남아 production 삭제 실행과 공개 베타만 차단한다.

### 5. 공개 베타 활성화 완료율의 분모와 측정 기간

**해소.** PRD §10.1 `SM-2`가 분모를 `온보딩을 시작한 유효 테스트 사용자`로, 활성화 완료 시점을 `FR-4의 계획 생성 성공 시각`으로 확정했다. 측정 기간은 PRD §10의 판단 시점인 공개 베타 Day 60이다. 유효 사용자 정의는 `SM-1`이 소유한다.

### 6. 동기화 성공률의 측정 단위와 제외 규칙

**해소.** PRD §10.1 `SM-4`가 `NFR-12`의 성공 정의를 참조하고, `NFR-12`는 성공을 `필수 API 구간·페이지 검증과 원자 발행을 완료한 유효 동기화`로 규정한다. `epics.md`의 `검증 시점과 완료 증거` 절이 `Bybit 오류를 분모에서 임의 제외하지 않는다`를 명시해 제외 규칙 남용을 차단한다.

### 7. 이해도 80%를 판정할 문항·합격 기준·응답 대상·측정 시점

**부분 해소.** PRD §10.1 `SM-5`가 응답 대상을 `사용자 과업 평가 참가자`로, 판정 대상을 현재 시점 계획금액·계획 대비 차이·외부 자금흐름 조정·세 가지 신뢰 상태 4개 개념으로, 합격 기준을 80%로 확정했다.

**미해소 잔여:** 실제 문항과 과업 스크립트. PRD §11 출시 전 게이트의 `오류·검토 필요·계산 불가 상태에 대한 사용자 이해 과업을 수행한다`로 남아 있으며 개발 스프린트를 막지 않는다.

### 8. 주 2시간 운영 목표의 안정화 시작 조건과 측정 주수

**해소.** PRD §10.1 `SM-6`이 안정화 시점을 `최근 14일 동안 중대 사고가 없고 미해결된 치명적 결함과 높은 심각도의 결함이 모두 0건인 첫날`로 확정했다. `epics.md`의 `검증 시점과 완료 증거`가 실제 목표 달성은 운영 기간 측정으로만 판단하도록 분리했다.

### 9. 강한 신호별 자동 정지 발동 조건과 60초 측정 경계

**해소.** `OPS-SIGNAL-1.0`(`ERD-1.0.0` §6.1)이 신호별 권위 증거와 자동 제한 범위를 판정표로 고정하고, 강한 신호가 아닌 사건(단일 사용자 인증 만료, 일반 timeout·rate limit·5xx, 단일 가격 결측, 광고 오류)을 명시적으로 배제했다. §6.2가 `감지 후 60초 안에 durable하게 설정하고 자동 해제하지 않는다`로 측정 경계를 확정했다.

### 10. 계산 사고에서 `last_trusted_only` 표시를 허용할 조건

**해소.** `ERD-1.0.0` §6.1이 `같은 canonical input/policy에서 다른 result hash`를 deterministic replay 1건으로 강한 신호로 판정하고, 해당 calculation policy의 **표시와 publication을 함께 정지**하며 범위가 불명하면 sync·계산을 전역 정지한다. 따라서 잘못된 계산 버전이 만든 과거 값이 마지막 신뢰값으로 계속 노출되는 경로가 차단된다.

### 11. 프런트엔드 시험을 자동 실행하는 CI 워크플로

**해소(2026-09-04).** `.github/workflows/ci.yml`의 단일 `ci-required` job이 `develop`·`main`의 PR과 push에서 install, typecheck, build, Vitest, Chromium E2E를 실행하고 `CI`를 설정한다(CI-PLAN §6, `spec-ci-foundation.md`). CI-PLAN §16이 요구한 "검사가 실제로 성공한 뒤" 조건은 GitHub-hosted Ubuntu 실행 6회 green(PR #2·#4, `develop` push 3회, `workflow_dispatch` 1회)으로 충족했다. Ruleset `protect-integration-branches`가 `develop`·`main`에서 `ci-required` 통과를 병합 조건으로 강제한다(CI-PLAN §13).

### 12. 린터·포매터(ESLint·stylelint) 도입

**해소(2026-09-05).** `spec-frontend-lint-policy.md`(PR #6, `develop` 병합 커밋 e0452a5)가 CI-PLAN §6·§8이 정한 품질 정책 PR이다. `frontend/stylelint.config.js`의 `declaration-strict-value`가 `DESIGN.md` 네 범주(색·타이포그래피·모서리·간격)에서 리터럴을 금지하고 `tokens.css` 참조를 강제하며, `frontend/eslint.config.ts`의 perfectionist가 import 순서를 고정한다. `frontend/lint-policy.test.ts`의 fixture 49개가 규칙이 실제로 위반을 잡는지 고정하고, `ci.yml`의 `ci-required`가 타입 검사 뒤·빌드 앞에 ESLint·stylelint·Prettier 단계를 필수로 실행한다. CI-PLAN §16의 "검사가 실제로 성공한 뒤" 조건은 PR #6과 병합 후 `develop` push 실행 green으로, "실패가 병합을 차단한다"는 리터럴 색을 넣은 일회용 PR #7이 stylelint 단계에서 실패해 BLOCKED가 된 것으로 확인했다. 정적 접근성 lint(jsx-a11y)는 ESLint 10 peer 미지원으로 항목 17에 분리했다.

### 13. Playwright 시험 대상에 WebKit 추가 여부

**미해소.** CI-PLAN §7이 WebKit을 비필수 job으로 시작하고 §17이 10회 측정에서 안정성과 p95 예산을 충족할 때 `develop` required로 승격하도록 정했다. `main` PR의 WebKit 필수 검사는 Story 3.8의 `release-validation` 범위다(§5, §13). 결정 시점은 Story 3.8 전이며 지금은 막는 항목이 없다.

### 14. CSP를 비롯한 보안 응답 헤더

**미해소.** Story 1.2의 Nginx 구성이 소유한다. `epics.md` Story 1.2와 `ARCHITECTURE-SPINE.md`의 동일 출처 경계가 정의 지점이다. Story 1.2 착수 시 SPEC에서 결정한다.

**부분 해소(2026-09-06).** `spec-1-2-same-origin-shell.md` `## Design Notes`가 CSP를 결정하고 `deploy/nginx/bokrilog.conf`가 구현했다. `ARCHITECTURE-SPINE.md` AD-4(동일 출처 셸)의 경계 위에서 Nginx 응답 헤더가 CSP의 **유일한** 정의다. `<meta>` CSP를 두지 않는다. 문서 본문에 정책을 두면 문서를 바꿀 수 있는 경로가 곧 정책을 바꿀 수 있는 경로가 되기 때문이다.

적용한 헤더는 네 가지이며 모두 `always`가 붙어 4xx·5xx 응답에도 실린다. `server_tokens off`가 `Server` 헤더에서 버전을 지운다.

```text
Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self';
  style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self';
  base-uri 'none'; form-action 'none'; frame-ancestors 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
X-Frame-Options: DENY
```

두 지시어에 근거가 필요하다. `style-src-attr 'unsafe-inline'`은 `frontend/index.html`의 noscript 폴백이 인라인 `style` 속성을 쓰기 때문이다. JS가 꺼진 브라우저에서는 `tokens.css`가 로드되지 않아 폴백 리터럴이 속성에 있어야 하고, 이 지시어를 빼면 가입 전 고지 화면이 스타일 없이 나온다(항목 18이 같은 폴백을 다룬다). `img-src 'self' data:`는 파비콘이 인라인 `data:` URI이기 때문이다. 외부 파일로 바꾸면 매 로드마다 `/favicon.ico` 요청이 생겨 Story 1.1의 "문서·번들 외 요청 0건" 계약이 깨진다.

검증 근거: `deploy/compose/verify-same-origin.sh`가 `GET /`·`/api/status`·`/api/nope`·`/nope`·백엔드 정지 후 502 다섯 응답 각각에서 네 헤더와 `Server` 버전 부재를 단정하며 2026-09-06 로컬 실행에서 64개 단정이 모두 통과했다. 헤더가 실제로 화면을 깨뜨리지 않는다는 것은 Story 1.1의 Playwright 13개(axe WCAG 검사 포함)를 Nginx origin에 대고 본문 수정 없이 돌려 확인했다.

**미해소 잔여:** CI-PLAN §16의 "검사가 실제로 성공한 뒤에만 완료로 본다" 조건 중 GitHub-hosted 실행분. 지금까지의 근거는 2026-09-06 로컬 실행뿐이고 이 구성으로 CI가 돌아간 적은 없다. Story 1.2의 PR에서 `same-origin-integration` job이 green이 되면 그 실행을 근거로 **해소** 판정을 새로 추가한다(덮어쓰지 않는다). 그 전에 해소로 적으면 원장이 검증되지 않은 상태를 검증된 상태로 보고하게 되고, 이 원장의 존재 이유가 사라진다.

**이번에 채택하지 않은 헤더와 그 소유.** 항목 14를 닫으면서 기록되지 않은 새 공백이 열리지 않도록 남긴다. 아래 네 가지는 "빠뜨린 것"이 아니라 이 스토리 범위에서 근거를 댈 수 없어 **의도적으로 넣지 않은 것**이다.

| 헤더 | 지금 넣지 않은 이유 | 결정 지점 |
|---|---|---|
| `Permissions-Policy` | 현재 화면이 geolocation·camera·microphone·payment 어느 강력 기능도 쓰지 않는다. 지금 값을 정하면 근거 없는 목록이 된다. 실제로 차단할 기능은 광고 SDK와 배포 프로필이 정해질 때 결정된다 | Story 3.8(배포 프로필). 광고 도입 시 Epic 4가 재검토 |
| `Cross-Origin-Opener-Policy`·`Cross-Origin-Resource-Policy` | `frame-ancestors 'none'`과 `default-src 'none'`이 지금의 위협(임베딩·외부 로드)을 이미 막는다. COOP `same-origin`은 제공자 왕복·팝업 흐름과 상호작용하므로 그 흐름이 없는 지금 정하면 나중에 다시 뒤집어야 한다 | Story 1.4(Google 가입·로그인) |
| HSTS(`Strict-Transport-Security`) | 개발·CI 프로필은 HTTP 전용이다. HTTP 응답의 HSTS는 브라우저가 무시하고, 로컬에 켜 두면 `127.0.0.1`을 HTTPS로 강제해 개발을 막는다. TLS 종단이 있는 곳의 헤더다 | Story 3.8(TLS 종단·배포 프로필) |
| `Cache-Control`(`index.html` 대 해시 자산 `/assets`) | Vite가 자산 파일명에 내용 해시를 넣으므로 `/assets`는 장기 immutable, `index.html`은 `no-cache`가 맞다. 지금은 Nginx 정적 제공 기본값(`Last-Modified`·`ETag` 조건부 요청)에 맡겼다. 이 구분은 CDN·프록시 캐시와 함께 정해야 의미가 있다 | Story 3.8(배포 프로필) |

네 항목 모두 Story 1.1의 "문서·번들 외 요청 0건" 계약과 현재 I/O 표의 어떤 행에도 영향을 주지 않는다. 위 결정 지점의 SPEC이 이를 다루지 않고 지나가면 그 시점에 `deferred-work.md` 항목으로 다시 올린다.

### 15. 진입 화면 `다음 단계` 카피를 소셜 로그인 버튼으로 교체

**미해소.** `epics.md` Story 1.4(Google 가입·로그인)가 소유한다. `frontend/src/content/entry.ts`의 "로그인 수단 선택은 아직 준비 중입니다."는 로그인 수단이 생기는 즉시 거짓이 되므로 Story 1.4 SPEC의 필수 변경으로 넣는다.

### 16. `bmad-contract` 검사 구현

**미해소.** CI-PLAN §14가 `_bmad/**`·`.agents/skills/**`·`.claude/skills/**`·`.codex/**`·`scripts/check-bmad-env.sh`·`uv.toml` 변경에만 도는 별도 검사로 정했다. 러너에 `uv`와 관리 Python 다운로드가 필요해 frontend 필수 경로와 분리했다(`spec-ci-foundation.md`). 그때까지 이 경로만 바뀐 PR은 fail-closed 분류로 전체 frontend 검사를 돌고, BMAD 계약 자체는 어떤 게이트도 검증하지 않는다. 개발 스프린트를 막지 않는다.

### 17. `eslint-plugin-jsx-a11y` 도입

**미해소.** 2026-09-05 기준 최신판(6.10.2)의 peerDependencies가 ESLint 9까지만 선언해 항목 12의 정책 PR에서 제외했다. CI-PLAN §11이 정한 별도 의존 갱신 PR로 추가한다. 렌더 결과의 접근성은 E2E axe 검사가 담당하므로 개발 스프린트를 막지 않는다.

### 18. noscript 인라인 스타일 폴백 값의 표류 시험

**미해소.** `frontend/index.html`의 noscript 인라인 스타일은 JS 없이 `tokens.css`가 로드되지 않아 `var(--…, 폴백)` 폴백 리터럴이 필요하고, stylelint 대상(`src/**/*.css`) 밖이다. 폴백 값을 `tokens.css`와 비교하는 시험이 없어 토큰 변경을 따라가지 못해도 CI가 알지 못한다. `DEVELOPMENT.md` 예외 목록에 근거를 기록했고 시험은 별도 작업이다. 개발 스프린트를 막지 않는다.

### 19. 통합 입력 artifact 이름의 `run_attempt`와 `Re-run failed jobs`

**미해소(2026-09-06).** Story 1.2가 append했다. CI-PLAN §8이 artifact 이름에 run attempt를 넣도록 요구한 결과, `same-origin-integration`만 실패한 실행에서 `Re-run failed jobs`가 동작하지 않는다. 이미 성공한 producer job은 다시 돌지 않아 새 attempt 이름의 artifact가 없기 때문이다. 계획 문서의 요구와 복구 경로가 충돌하므로 조용한 이름 변경이 아니라 CI-PLAN §8 자체를 다시 읽는 결정이 필요하다. 그때까지의 우회는 `Re-run all jobs`이며 `DEVELOPMENT.md` `## CI`에 적었다.

### 20. `same-origin-integration`의 중복 설치

**미해소(2026-09-06).** Story 1.2가 append했다. 통합 job이 `frontend-quality`와 같은 `npm ci`·Chromium 설치를 되풀이한다. CI-PLAN §10의 시간 예산 조정 순서가 "중복 build·설치 제거"를 첫 번째로 두므로, 예산이 문제가 되는 시점의 첫 후보다. 지금은 job timeout을 16분으로 잡아 감당하고 있다.

### 21. 백엔드 품질 게이트(포매터·정적 분석·아키텍처 규칙)

**미해소(2026-09-06).** Story 1.2가 append했다. 프런트엔드 네 게이트(typecheck·ESLint·stylelint·Prettier)에 대응하는 백엔드 게이트가 `gradle build` 하나뿐이다. 소유 스토리가 없다. Java 코드가 적은 지금이 도입 비용이 가장 낮고, 미루면 전체 재포맷 커밋이 생긴다. 항목 12(프런트엔드 린터)가 같은 문제를 프런트엔드에서 먼저 겪었다.

### 22. production 컨테이너 권한 강화

**미해소(2026-09-06).** Story 1.2가 append했다. `deploy/compose/compose.yaml`의 nginx 서비스에 `user`·`read_only`·`cap_drop`·`security_opt`·자원 제한이 없다. compose 파일 주석은 Story 3.8이 소유한다고 적었지만 `epics.md` Story 3.8의 AC-1~AC-5는 digest·서명·SBOM·메모리 한도·blue/green 순서만 다루고 컨테이너 권한은 다루지 않는다. 소유를 실제로 붙이는 것이 이 항목의 내용이다. 현재 구성은 개발·CI 전용이므로 개발 스프린트를 막지 않는다.

## 갱신 규칙

- `deferred-work.md`에 새 항목이 append되면 이 원장에 같은 순서로 판정을 추가한다.
- 판정을 바꿀 때는 기존 문단을 덮어쓰지 않고 날짜와 근거를 붙여 새 판정을 추가한다.
- 해소 근거는 반드시 권위 문서(PRD, `ARCHITECTURE-SPINE.md`, `EPIC-IMPLEMENTATION-READINESS.md`, `epics.md`)의 절 번호로 지목한다.
