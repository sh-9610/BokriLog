---
title: 'Deferred Work 해소 원장'
status: living
created: '2026-09-02'
updated: '2026-10-04'
applies_to: '_bmad-output/implementation-artifacts/deferred-work.md'
decision_baseline: 'ERD-1.0.0'
---

# Deferred Work 해소 원장

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 스킬 정의(`.claude/skills/bmad-build/step-04-review.md`)가 기존 항목의 수정을 금지하므로, 해소 여부는 그 파일을 고치지 않고 이 원장에 기록한다.

항목 순서는 `deferred-work.md`의 등장 순서와 같다. 판정 기준은 `ERD-1.0.0`, PRD `2026-08-23` 판, `epics.md`(`implementationReadinessStatus: ready-for-sprint-planning`)다.

## 상태 요약

| 상태 | 건수 |
|---|---|
| 해소 | 16 |
| 부분 해소 | 4 |
| 미해소 | 31 |
| 미판정 | 51 |

**2026-10-04 갱신(Story 2.14 done 전환).** Story 2.14(PR #60)가 `deferred-work.md`에 항목 **101~102**를 append했다. 판정은 아직 없다. 그래서 표를 **102건** 기준으로 맞추고 미판정을 49→51로 늘렸다. 나머지 수치는 그대로다.

**2026-10-04 갱신(Story 2.13 done 전환).** Story 2.13(PR #57)이 `deferred-work.md`에 항목 **98~100**을 append했다. 판정은 아직 없다. 그래서 표를 **100건** 기준으로 맞추고 미판정을 46→49로 늘렸다. 나머지 수치는 그대로다.

**2026-10-04 갱신(frontend-effect-timing 라운드).** 표를 `deferred-work.md`의 **97건** 전체로 맞췄다. 판정을 가진 것은 51건이다 — 항목 1~50과 이 라운드가 새로 판정한 83이다. 항목 **50이 해소**로 넘어갔고 **83이 해소**로 새로 들어왔다. 둘은 같은 원인(부하 아래 늦게 도는 passive effect)이었다. 그래서 해소 14→16, 미해소 32→31이다. 나머지 46건(51~82와 84~97)은 아직 판정이 없어 `미판정`으로 따로 센다. 이 라운드의 범위가 아니어서 판정하지 않았다.

**2026-09-09 갱신(Story 1.7 라운드).** 표를 **50건** 기준으로 맞췄다. 항목 **42가 해소**로 넘어갔고(세 루프 모두 완주 수 가드를 갖췄고 `verify-same-origin.sh` 526/526 exit 0으로 확인했다) 항목 **41이 부분 해소**가 됐다(판단하는 층은 생겼으나 그 등식이 44px 바닥 아래에서만 성립한다는 취약성은 그대로다). 이 라운드가 append한 여섯(45~50)은 전부 미해소다. 그래서 해소 13→14, 부분 해소 3→4, 미해소 28→32다.

**2026-09-09 갱신(Story 1.6 라운드).** 표를 **44건** 기준으로 맞췄다. 해소 13과 부분 해소 3은 그대로이고 미해소가 21에서 28로 늘었다 — 이 라운드가 `deferred-work.md`에 append한 일곱 항목(38~44) 전부가 미해소이며 아래 절이 그 판정을 갖는다. 그리고 **주인 없던 두 항목 23·27을 Story 1.6이 실제로 보았고 재차 미뤘다**(각 절의 2026-09-09 판정). 해소로 넘어간 항목은 이 라운드에 없다.

아래 문단들은 2026-09-08 라운드가 적은 것이며 그때의 37건 기준을 그대로 남긴다. 낡은 것은 수치가 아니라 시점이므로 덮어쓰지 않는다.

표는 항목별 판정을 다시 세어 2026-09-08에 **37건** 기준으로 맞췄다. 해소 13은 기존 10건(1·2·3·5·6·8·9·10·11·12)에 14·15·24가 더해진 결과이고, 부분 해소 3은 4·7·28, 미해소 21은 13·16·17·18·19·20·21·22와 23·25·26·27·29·30·31·32·33·34, 그리고 이 라운드가 append한 35·36·37이다.

**이 라운드가 세 항목을 append하고 같은 라운드에서 판정했다.** 리뷰가 낸 `defer` 셋(35 판정 기록 단계의 절차 공백, 36 인용 해석 검사, 37 소유자·확인일 표)을 `deferred-work.md`에 더했다. 판정 없이 넘기면 이 라운드가 고친 바로 그 드리프트를 스스로 다시 시작하는 것이므로 세 절을 함께 넣었다. 그래서 항목 수가 34에서 37로 늘었다.

**판정이 두 스프린트만큼 밀렸던 사실을 먼저 적는다.** Story 1.3(PR #12)·Story 1.4(PR #14)·lint-policy(PR #16)·Story 1.5(PR #17) 네 라운드가 append한 12건(항목 23~34)이 이번 라운드 전까지 아무 판정도 갖지 못했다. `deferred-work.md`에 항목을 **추가**하는 단계는 `bmad-build` step-04가 강제하지만 그 항목의 판정을 이 원장에 쓰는 단계는 어느 스킬에도 없어서, 원장은 사람이 따로 챙길 때만 갱신됐고 네 라운드 연속으로 챙기지 못했다. 그 결과 "검사가 실제로 성공한 뒤에만 해소로 적는다"는 이 원장의 규율이 원장 자신 안에서 먼저 깨졌다. `## 갱신 규칙`의 마지막 줄이 그 재발을 막는 최소 수단이다.

**소유 스토리가 지나갔는데 처리되지 않은 항목이 둘이다.** 23(`identity` 행 잠금 대기 상한)은 Story 1.4를, 27(`identity`가 `spring_session`에 직접 SQL을 던지는 결합)은 Story 1.5를 결정 지점으로 지목했지만 두 스토리 모두 `done`이 된 지금 **현재 주인이 없다**. 그 사이 27은 악화됐고(`principal_name == user_id` 규약이 세 곳에 독립 재구현되고 세션 삭제 경로가 둘로 늘었다) 23은 원문이 예고한 조건이 성립했다(OIDC 콜백이 열려 그 잠금 대기가 사용자 요청의 대기가 됐다).

**개발을 막는 항목은 여전히 없다.** 부분 해소 3건 중 4·7은 외부 승인 게이트(G-5, 출시 전 사용자 과업)에 묶여 있고 28은 Epic 2가 두 화면을 실제로 갈라 놓을 때 다시 판단하는 항목이다.

**미해소 21건 중 열셋은 주인이 없다.** 소유가 정해진 것은 여섯이다 — 13(Story 3.8 전), 16(CI-PLAN §14의 별도 검사), 17(CI-PLAN §11의 조건부 의존 갱신 PR), 18(별도 작업), 19·20(CI-PLAN §8·§10을 다시 읽는 결정). 22와 32는 소유를 붙이는 것 자체가 항목의 내용이다. 나머지 열셋 — 21·23·25·26·27·29·30·31·33·34·35·36·37 — 은 어느 스토리도 갖고 있지 않다. 그중 23·27은 소유 스토리가 지나가 주인을 잃은 경우이고(위 문단), 21·29와 30·31, 28·34, 36·37은 각각 한 쌍이라 함께 결정해야 싸다. Story 1.6이 같은 콜백·세션 개설 경로를 다시 지나므로 그 SPEC이 23·27을 함께 볼 첫 기회를 갖는다. 이 열거가 37개 절을 다 읽지 않고 주인 없는 항목을 보는 유일한 자리이며, 항목별 소유자·마지막 확인일 열을 가진 표를 둘지는 항목 37이 소유한다.

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

**해소(2026-09-08).** 위 **부분 해소** 문단이 남긴 잔여 조건은 GitHub-hosted 실행분 하나였고 그것이 충족됐다. Story 1.2의 PR #9 실행(`actions/runs/34035780145`, 2026-09-06 13:20~13:24 UTC)에서 `changes`·`frontend-quality`·`backend-quality`·`same-origin-integration`·`ci-required` 다섯 job이 모두 success다. 그중 `same-origin-integration`의 `동일 출처 검증` 단계가 `verify-same-origin.sh`로 네 헤더와 `Server` 버전 부재를 Nginx origin에서 단정하고, 이어지는 `Story 1.1 회귀 (Nginx origin)` 단계가 Playwright를 같은 origin에 대고 통과했다. `develop`에 들어간 머지 커밋은 `d9a1fc2`다. 이후 이 CSP를 바꾸는 변경은 이 항목이 아니라 새 항목으로 다룬다.

### 15. 진입 화면 `다음 단계` 카피를 소셜 로그인 버튼으로 교체

**미해소.** `epics.md` Story 1.4(Google 가입·로그인)가 소유한다. `frontend/src/content/entry.ts`의 "로그인 수단 선택은 아직 준비 중입니다."는 로그인 수단이 생기는 즉시 거짓이 되므로 Story 1.4 SPEC의 필수 변경으로 넣는다.

**해소(2026-09-08).** 소유 스토리가 그 카피를 실제 버튼으로 교체했다. `git diff 2f300f6^1 2f300f6 -- frontend/src/content/entry.ts`가 내는 변경은 한 줄뿐이다 — 현재 `:59`가 된 그 줄이 "로그인 수단 선택은 아직 준비 중입니다…"에서 "Google로 계속하면 본인 확인을 거쳐 계정이 만들어지고, 처음 로그인하면 온보딩 화면으로 이동합니다"로 바뀌었다. 그 파일에서 "준비 중"을 찾으면 지금 0건이다. PR #14 머지 커밋 `2f300f6`. 버튼이 실제로 왕복하는지는 `dev-oidc` 스텁 위에서 `frontend/e2e/auth-entry.spec.ts`와 `deploy/compose/verify-same-origin.sh`가 확인하며 그 PR의 `ci-required`가 green이었다.

**이 항목의 논리는 Story 1.6·1.7에서 다시 성립한다.** 항목이 붙잡은 것은 "로그인 수단이 생기는 즉시 거짓이 되는 문장"이었고, 같은 모양의 문장이 지금 `frontend/src/content/auth.ts:177`에 있다(`네이버·카카오 로그인은 아직 준비 중입니다.`). 그 파일 `:176` 주석이 "네이버·카카오는 Story 1.6·1.7이 더한다"고 스스로 적었고 `frontend/src/screens/EntryScreen.test.tsx:143`이 그 문장을 단정한다. 위 해소는 Google 축에 한정되며 네이버·카카오 축은 이 항목을 재사용하지 않고 그 두 스토리가 각자 SPEC의 필수 변경으로 다룬다.

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

### 23. `identity` 행 잠금 대기 상한과 HTTP 요청 타임아웃

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12, 머지 커밋 `89b8948`). 구조는 그대로다 — `IdentityAccountRepository.java:35-43`의 `SELECT state … FOR UPDATE`에 `NOWAIT`도 `lock_timeout`도 없고, `AccountLifecycleService.java:44-77`의 `createAccount`·`linkSocialIdentity`에 붙은 `@Transactional`에도 timeout 속성이 없다. `backend/src/main/resources/application.yml`에서 `lock_timeout`·`statement_timeout`과 Tomcat 요청 타임아웃을 찾으면 0건이다.

**원문이 예고한 조건은 이미 성립했다.** 항목은 "지금은 identity에 HTTP 표면이 없어 사용자가 그 대기를 볼 경로가 없지만, Story 1.4가 OIDC 콜백을 열면 그 대기가 곧 사용자 요청의 대기가 된다"고 적었다. 그 콜백은 열렸다 — `AuthFlowHandlers.java:339-342`의 `onSuccess`가 `AuthSessionService`를 부르고 `AuthSessionService.java:104-105`가 같은 요청 스레드에서 `createAccount`·`linkSocialIdentity`에 동기로 도달한다. 즉 이 항목은 예고 상태가 아니라 성립한 상태로 미해소다.

**미해소 잔여:** 상한 값(몇 초로 끊을지), 끊었을 때의 거부 계약, 그리고 그것을 소유할 스토리 전부. `spec-1-4-google-sign-in.md`·`spec-1-5-logout-session-expiry.md`에서 잠금 상한·요청 타임아웃을 찾으면 0건이다.

**주인이 없는 이유는 소유를 이름이 아니라 조건으로 적었기 때문이다.** 원문은 소유를 "HTTP 요청 타임아웃이 생기는 스토리와 함께 값을 결정한다"로 적었고 스토리 이름을 대지 않았다. Story 1.4는 그 조건의 **방아쇠**로만 등장한다("Story 1.4가 OIDC 콜백을 열면 그 대기가 곧 사용자 요청의 대기가 된다"). 그 방아쇠는 당겨졌는데 조건에 해당하는 스토리가 지정된 적이 없으므로, `done` 전환 시점의 확인이 걸릴 이름이 처음부터 없었다. Story 1.6이 같은 콜백 경로에 두 번째 제공자를 얹으므로 그 SPEC이 이 결정을 볼 첫 기회이며, `## 갱신 규칙`의 (가) 부류가 이 모양을 다시 놓치지 않기 위한 장치다.

**재차 미룸(2026-09-09) — Story 1.6이 보았고 미뤘다.** 위 문단이 예고한 "첫 기회"가 실제로 왔다. Story 1.6은 같은 콜백·세션 개설 경로에 두 번째 제공자를 얹으며 이 항목을 명시적으로 검토했고, `spec-1-6-naver-sign-in.md`의 `## Boundaries & Constraints`(Never: "미룬 항목 두 건을 이 스토리에서 해소하는 것")와 `## Design Notes` 마지막 절이 그 판정과 근거를 소유한다. 근거는 둘이다 — (1) 상한은 JDBC·트랜잭션 정책의 변경이고 이 스토리가 만든 변경(등록 저장소를 맵으로, `provider` 값을 흐름에서, 스텁 인스턴스를 둘로)과 폭발 반경이 겹치지 않는다, (2) 값의 근거가 이 스토리에는 없다 — 상한을 몇 초로 끊을지 정하려면 요청 타임아웃 정책이 함께 있어야 하고 그 정책은 아직 어느 스토리도 갖고 있지 않다.

구조는 2026-09-08 판정 시점과 같다. Story 1.6은 `identity`를 한 줄도 바꾸지 않았고(`git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/`가 0), `IdentityAccountRepository`의 `FOR UPDATE`에 `NOWAIT`·`lock_timeout`이 없는 것도, `application.yml`에 `lock_timeout`·`statement_timeout`·Tomcat 요청 타임아웃이 없는 것도 그대로다. 달라진 것은 그 대기에 도달하는 **사용자 경로가 하나 늘었다**는 사실뿐이다 — 이제 두 제공자의 콜백이 같은 `createAccount`·`linkSocialIdentity`에 동기로 닿는다.

**미해소 잔여:** 2026-09-08 판정과 같다(상한 값, 거부 계약, 소유 스토리). 주인 없음도 그대로다. Story 1.7(카카오)이 세 번째 제공자로 같은 경로를 또 지나므로 그 SPEC이 다음 기회이며, 제공자를 하나 더 더하는 것만으로는 값의 근거가 생기지 않는다는 사실이 이 라운드가 확인한 것이다.

### 24. `(issuer, subject)` 조회 API와 계정 생성·매핑 연결의 트랜잭션 경계

**해소(2026-09-08).** Story 1.3이 append했고(PR #12) 지목한 Story 1.4가 두 절반을 모두 처리했다. 조회 API는 `AccountLifecycleApi`에 `findUserIdBySocialIdentity`로 들어왔고 구현은 `AccountLifecycleService.java:122`다. 트랜잭션 경계는 `identity` 밖에서 잡혔다 — `AuthSessionService.java:68-90`의 `establish()`가 `TransactionTemplate` 하나로 한 시도를 감싸고, `:92-127`의 `attempt()`가 그 트랜잭션 안에서 조회·계정 생성·매핑 연결·세션 판정을 차례로 수행한다. 매핑 연결이 거부되면 `:117`이 `setRollbackOnly()`를 불러 방금 만든 계정을 되돌리고, 세션 판정이 거부되면 `:124`가 같은 일을 한다. 항목이 우려한 "두 호출이 각각 자기 트랜잭션을 열어 매핑 없는 ACTIVE 계정이 영구히 남는다"가 그 두 롤백으로 닫힌다. `AuthAccountMappingTest.java:78-148`이 같은 `(issuer, subject)`로 동시에 처음 로그인하는 경합에서 계정과 매핑이 각각 하나이고 두 세션의 `principal_name`이 그 하나를 가리키는 것까지 단정한다. 검사 근거는 PR #14 머지 커밋 `2f300f6`과 그 PR의 `ci-required` green이다.

### 25. `PostgresTestcontainersConfig`의 공유 컨테이너 수명과 Spring 컨텍스트 캐시

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12). 구조는 그대로다 — `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:37-43`이 컨테이너를 `static` 필드로 들고 그 인스턴스를 `@Bean @ServiceConnection`으로 등록하므로, Spring이 컨텍스트를 닫을 때 그 컨테이너의 `stop()`을 부를 수 있는 경로가 남아 있다.

원문의 "지금은 컨텍스트가 넷뿐이라 축출이 일어나지 않아 드러나지 않는다"는 전제는 시험이 늘어난 뒤에도 성립한다. 시험 클래스는 18개가 됐지만 **구별되는 Spring 컨텍스트는 지금도 정확히 넷이다** — (1) `AuthFlowTestBase`(`RANDOM_PORT`, 하위 클래스 8개가 `@DynamicPropertySource`를 기반 클래스 한 곳에 두어 공유), (2) `StatusApiTest`(`RANDOM_PORT` + 중첩 `TestOnlyEndpointConfiguration`), (3) `identity`의 `AccountAccessDecisionTest`·`AccountDeletionTest`·`AccountLinkDenialTest`(`webEnvironment = NONE`, 셋이 공유), (4) `AccountConcurrencyTest`(`@TestPropertySource`로 hikari 풀 크기를 바꿔 별도). 저장소 전체에 `@DirtiesContext`가 0건이고 Spring 기본 캐시 상한(32)에 여유가 크다. 그 넷이 유지되는 것은 우연이 아니라 `AuthFlowTestBase.java:38-41`이 컨텍스트 재사용을 의도적으로 설계한 결과이며, 반대로 하위 클래스 하나에 `@TestPropertySource`나 별도 configuration이 붙는 순간 수가 늘어난다. 즉 이 항목은 아직 드러나지 않은 잠복 결함이고, 드러나지 않았다는 것이 해소의 근거는 아니므로 판정은 미해소다.

**미해소 잔여:** 컨테이너 수명을 JVM에 묶는 방식의 결정(수동 시작 + shutdown hook 또는 컨테이너 재사용 설정). 소유 스토리는 정해지지 않았다.

### 26. `verify-same-origin.sh` 마이그레이션 호출의 이식 가능한 시간 상한

**미해소(2026-09-08).** Story 1.3이 append했다(PR #12). `deploy/compose/verify-same-origin.sh:218-222`의 `compose --profile migrate build migrate`와 `compose --profile migrate run --rm migrate`에 여전히 시간 상한이 없다. 스크립트의 다른 대기는 그대로 묶여 있다 — `:239`의 `up --detach --build --wait --wait-timeout 180`, 그리고 개별 요청의 `--max-time`. Story 1.5가 이 파일을 크게 고쳤지만(로그아웃·CSRF·캐시 왕복 추가) 이 갭은 손대지 않았다.

**미해소 잔여:** bash 3.2에서 도는 이식 가능한 구현(백그라운드 실행 + 감시 루프)과 그 상한 값. 소유 스토리는 정해지지 않았다.

### 27. `identity`가 `spring_session`에 직접 SQL을 던지는 결합

**미해소(2026-09-08) — 악화.** Story 1.4가 append했다(PR #14). 결정 지점으로 지목한 Story 1.5는 이 결합을 **명시적 범위 밖으로 두고** `done`이 됐다 — `SessionGuard.java:23-26`의 javadoc이 "`identity`의 `deleteSessionsOf`는 삭제 경로 전용으로 그대로 남고, 이 스토리는 그 결합을 넓히지 않는다"고 적었다. `IdentityAccountRepository.java:195-198`의 `DELETE FROM spring_session WHERE principal_name = :principalName`은 그대로다. 결합을 넓히지 않은 것은 사실이지만 그 주변이 셋 늘었다.

- `principal_name == user_id` 규약이 세 곳에 독립 재구현됐다 — `AuthFlowHandlers.java:389-397`(세션에 쓰는 쪽), `IdentityAccountRepository.java:196-197`(raw SQL로 읽는 쪽), `SessionGuard.java:209-211`(Spring Session API로 읽는 쪽). 공유 상수도 공유 변환 함수도 없다. `SessionGuard.java:206-207`의 주석이 "두 곳이 갈라지면 사용자 단위 조작이 조용히 0건이 되므로 변환을 한 곳에 둔다"고 스스로 경고하면서 그 변환을 세 번째로 다시 적었다.
- 세션 제거 경로가 둘이 됐다 — 계정 삭제는 raw SQL(`IdentityAccountRepository.java:195-198`), 세션 폐기는 Spring Session API(`SessionGuard.java:237-259`). 규약이 갈리면 두 경로가 각각 0행이 된다.
- 경계 검사는 이 결합을 보지 못한다. `ModuleBoundaryTest`는 패키지 의존만 본다. 다만 지금의 실제 안전망은 원문이 적은 것보다 낫다 — `AuthDeletionPathTest`와 `SessionGuardTest`가 둘 다 진짜 로그인으로 세션을 만든 뒤 폐기 결과를 단정하므로, `AuthFlowHandlers`가 principal에 싣는 값이 바뀌면 두 시험이 **함께** 깨진다. 조용한 실패가 성립하는 경우는 시험 없이 네 번째 읽는 쪽이 추가될 때이며, 규약이 공유 상수가 아닌 한 그 추가를 막는 장치는 없다.

**미해소 잔여:** 세 선택지(세션 폐기를 이벤트·포트로 뒤집기 / `principal_name` 규약을 공유 상수로 승격 / 세션 소유를 통째로 루트 패키지로 이전) 중 무엇을 택할지. **결정 지점으로 지목된 Story 1.5가 이미 `done`이므로 현재 주인이 없다.** Story 1.6이 같은 세션 개설 경로에 두 번째 제공자를 얹고 Epic 5가 삭제 화면에서 이 DELETE를 사용자 경로로 끌어오므로, 늦어도 그 두 지점에서는 결정이 필요하다.

**재차 미룸(2026-09-09) — Story 1.6이 보았고 미뤘다.** 위 문단이 지목한 두 지점 중 앞의 것이 왔다. Story 1.6은 같은 세션 개설 경로에 두 번째 제공자를 얹으며 이 항목을 명시적으로 검토했고, `spec-1-6-naver-sign-in.md`의 `## Boundaries & Constraints`(Never: "`identity`의 `spring_session` 결합 확대", "미룬 항목 두 건을 이 스토리에서 해소하는 것")와 `## Design Notes` 마지막 절이 그 판정과 근거를 소유한다. 근거는 세 선택지 모두가 `identity` **내부 변경**을 요구하는 반면 이 스토리는 `identity`를 한 줄도 바꾸지 않았다는 것이다 — 여기서 손대면 "제공자를 늘린다"는 단일 목표에 관계없는 모듈 리팩터가 같은 diff에 섞인다.

결합은 넓어지지도 악화되지도 않았다. Story 1.6은 세션 개설 경로에 제공자 축을 하나 더했을 뿐이고, `principal_name == user_id` 규약을 읽는 **네 번째 자리를 만들지 않았다**(2026-09-08 판정이 세 곳으로 센 그 상태 그대로다). `IdentityAccountRepository`의 `DELETE FROM spring_session WHERE principal_name = :principalName`도, `SessionGuard`의 Spring Session API 경로도 무변경이다. 새 마이그레이션도 새 의존도 없다.

**미해소 잔여:** 2026-09-08 판정과 같다(세 선택지 중 하나를 택하는 결정). 주인 없음도 그대로다. 남은 지목 지점은 Epic 5의 삭제 화면이고, Story 1.7(카카오)이 같은 경로를 한 번 더 지난다 — 제공자를 더하는 것이 이 결정의 재료를 만들지 않는다는 사실은 이 라운드가 확인했다.

### 28. `OnboardingScreen`·`SignedInScreen`을 세션 게이트 셸 하나로 합칠지

**부분 해소(2026-09-08).** Story 1.4가 append했다(PR #14). 버튼 축의 중복은 Story 1.5가 실제로 만들지 않았다 — `frontend/src/components/SignOutButton.tsx`(52줄)와 `SignOutButton.test.tsx`(239줄)를 두 화면이 함께 쓴다. 이 라운드에 새로 생길 수 있었던 중복 한 축이 생기지 않았으므로 그만큼은 해소다.

**부분 해소는 실체 축에만 걸린다 — 결정은 손대지 않았다.** 이 항목의 내용은 "합칠지 정한다"는 결정이고 그 결정은 여전히 없다. 부분 해소로 판정하는 근거는 항목 4·7의 선례와 같은 모양이다(실체의 일부가 내려앉고 잔여가 남으면 부분 해소). 결정 축만 기준으로 세면 이 항목은 미해소이고 상태 요약은 부분 해소 2·미해소 22가 된다. 결정형 항목에서 부분 해소를 언제 허용하는지는 아직 규칙에 없으며, 이 절은 두 읽기가 가능하다는 사실을 드러내 두는 쪽을 택했다.

**미해소 잔여:** 화면 축의 중복은 그대로다. 실측하면 `OnboardingScreen.tsx` 103줄, `SignedInScreen.tsx` 108줄 가운데 80줄이 동일하고(`git diff --no-index` 기준 28 추가·23 삭제), `.module.css`는 양쪽 189줄 중 최상단 주석 한 줄만 다르다(그 CSS는 항목 34가 따로 다룬다). 시험은 오히려 발산했다 — `OnboardingScreen.test.tsx` 422줄 대 `SignedInScreen.test.tsx` 362줄, `git diff --no-index` 출력 618줄(195 추가·255 삭제). 즉 통합을 미룰 근거(Epic 2가 대시보드에 지표를 넣으면 두 화면이 실제로 갈라진다)와 통합을 앞당길 근거(같은 수정을 두 번 하는 비용)가 함께 커졌고 결정은 아직 없다.

### 29. 백엔드 import 정렬 관용과 그것을 강제할 게이트

**미해소(2026-09-08).** Story 1.4가 append했다(PR #14). `backend/build.gradle.kts`에 spotless·checkstyle·pmd·archunit 플러그인이 없고(`:111`의 ArchUnit 언급은 Modulith의 전이 의존을 운영 classpath에서 빼는 주석이다), 컴파일 게이트는 `:131`의 `-Xlint:all -Werror` 하나다. 항목 21(백엔드 품질 게이트)과 한 쌍이며 둘 다 소유 스토리가 없다.

**원문 주장의 일부는 실측과 다르다.** 이번 라운드에 백엔드 java 파일 전체의 비-static import 순서를 기계로 확인한 결과 알파벳 순서를 벗어난 파일은 `AuthSecurityConfig.java` 하나뿐이었다 — `:19-20`이 `…security.core.Authentication`을 `…security.config…`보다 앞에 두고, `:28-29`가 `…oauth2.core.oidc.OidcScopes`를 `…oauth2.core.endpoint…`보다 앞에 둔다. 반면 원문이 두 번째 근거로 든 `DevOidcStubTest.java:12-13`은 `org.jspecify`가 `org.junit`보다 앞이므로 알파벳순에 **맞고**, "정렬 기준이 파일마다 다르다"는 그 예시는 성립하지 않는다. Story 1.5가 더한 파일 5개(`SessionGuard.java`·`AuthLogoutTest.java`·`AuthSessionExpiryTest.java`·`AuthSessionStateJsonTest.java`·`SessionGuardTest.java`)도 같은 검사를 통과한다. 원인 진단(게이트가 없어 사람 리뷰에만 의존한다)은 그대로 유효하다.

**미해소 잔여:** 관용(알파벳 단일 블록인지 그룹 분리인지)과 그것을 강제할 도구(Spotless `importOrder` 또는 Checkstyle `CustomImportOrder`) 결정. 항목 21과 함께 정한다.

### 30. `lint:css` 범위 단정을 집합 동등 비교로 바꿀지

**미해소(2026-09-08) — 악화.** lint-policy 라운드가 append했다(PR #16, 머지 커밋 `8f4fcaf`). `frontend/lint-policy.test.ts:679-685`가 여전히 `expect.arrayContaining`으로 `src/styles/global.css`·`src/styles/tokens.css`·`src/screens/EntryScreen.module.css` 세 개만 확인한다. 그런데 `frontend/package.json:16`의 `lint:css` glob(`src/**/*.css`)이 실제로 고르는 파일은 원문이 적은 다섯 개가 아니라 **여섯 개**다 — Story 1.5가 `src/components/SignOutButton.module.css`를 더해 아무 단정도 받지 않는 CSS가 둘에서 셋(`OnboardingScreen.module.css`·`SignedInScreen.module.css`·`SignOutButton.module.css`)으로 늘었다. 새 CSS 모듈이 glob에서 빠져도 이 시험은 통과하므로, `spec-frontend-lint-policy.md`가 적은 목적("검사 대상이 조용히 줄어드는 것을 막는다")이 지켜지지 않는다.

원문이 함께 처리해야 한다고 적은 정합 쪽은 드리프트가 없다 — `spec-frontend-lint-policy.md:69`가 못박은 시험 49개와 현재 `lint-policy.test.ts`의 시험 수가 일치한다.

**미해소 잔여:** 디스크에서 `src/**/*.css`를 독립 수집해 집합으로 비교하는 시험으로 바꾸는 일. 소유 스토리는 정해지지 않았다.

### 31. `lint-policy.test.ts`의 경로 조립 가정과 범위 단정의 부정 방향 공백

**미해소(2026-09-08).** lint-policy 라운드가 append했다(PR #16). 그 PR(`1ddeebf`)은 출력측 경로만 POSIX로 정규화했고 입력측 결합은 그대로다 — `` `${cwd}/${filePath}` `` 결합이 `frontend/lint-policy.test.ts:592`(`prettier.resolveConfig`)·`:615`(`prettier.getFileInfo`)·`:702`(`eslint.isPathIgnored`) 세 곳에 남아 있고, 같은 형태의 변형이 `:616`(`.prettierignore`)·`:660`(`package.json`) 둘 더 있다. Windows에서 `C:\…\frontend/src/…` 혼합 구분자가 되며, 지금 통과하는 것은 ESLint·Prettier의 내부 정규화 덕이다.

부정 방향 단정 셋은 전부 없다. `:658-708`의 `명령 층의 선택 범위` describe에는 시험이 둘(`lint:css` glob이 전역 CSS·토큰·CSS Module을 고른다, `eslint .`이 앱·E2E·설정 파일을 무시하지 않는다)뿐이어서 (a) `lint:css`가 `src/` 밖이나 CSS 아닌 파일을 고르지 않는다 (b) `eslint .`이 `globalIgnores`를 실제로 무시한다 (c) 두 명령의 `--max-warnings 0`이 남아 있다를 아무것도 단정하지 않는다. glob 추출 정규식 `/"([^"]+)"/`도 `:664`에 그대로다.

**미해소 잔여:** 위 다섯 결합의 경로 조립 방식과 부정 방향 단정 셋. 항목 30과 같은 파일을 고치므로 함께 처리하는 편이 싸다.

### 32. Windows 전용 결함을 CI가 볼 수 있게 할지

**미해소(2026-09-08).** lint-policy 라운드가 append했다(PR #16). `.github/workflows/ci.yml`의 다섯 job(`:59` `changes`·`:164` `frontend-quality`·`:261` `backend-quality`·`:332` `same-origin-integration`·`:424` `ci-required`)이 모두 `ubuntu-24.04`에서 돈다(`runs-on`은 `:63`·`:168`·`:265`·`:336`·`:431`). matrix leg를 더하면 `.github/scripts/test-ci-scripts.sh`의 고정 단정 다섯 곳을 함께 고쳐야 한다 — `:825-827`(job 목록과 순서), `:835-836`(`runs-on: ubuntu-24.04` 다섯 줄), `:838-841`(job별 `runs-on` 확인 루프), `:1121-1124`·`:1125-1126`(artifact 이름의 생산·소비 대칭).

**근거로 인용할 CI-PLAN 절이 없다.** `CI-PLAN.md`에는 OS matrix를 다루는 절이 존재하지 않는다(§1~§18 어디에도 없다). Windows·macOS runner를 언급하는 곳은 두 군데인데 §7(브라우저 실행 정책)의 `:253`("Firefox나 Windows·macOS runner는 지원 브라우저 정책, 실제 결함 또는 사용자 비율이 필요성을 보일 때 추가한다")과 §17(재검토 조건)의 WebKit 승격 항목(`:472`, "Firefox 또는 OS matrix를 검토한다")이며 둘 다 Playwright 브라우저 매트릭스 맥락이다. 이 항목이 필요로 하는 "어느 job을 어느 OS에서 도는가"의 근거로는 쓸 수 없으므로 없는 절 번호를 지목하지 않고 절이 없다는 사실을 그대로 적는다.

**미해소 잔여:** 결정 자체(전체 matrix인지 프런트엔드 단위 시험만 Windows에서 한 번 더 도는 축소안인지)와 그 비용을 소유할 스토리. 결정 지점이 배정되지 않았고 배정하는 것 자체가 이 항목의 내용이다.

### 33. 계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶을지

**미해소(2026-09-08) — 규모 확대.** Story 1.5가 append했다(PR #17, 머지 커밋 `5b6ae01`). 실측이 원문의 수치보다 크다.

- 자산 `Cache-Control`(`public, max-age=31536000, immutable`)은 원문대로 세 곳이다 — `deploy/nginx/bokrilog.conf:76`, `deploy/compose/verify-same-origin.sh:458`, `frontend/e2e/auth-entry.spec.ts:585`.
- `X-CSRF-TOKEN`은 세 곳이 아니라 **일곱 곳**이다. 정의는 둘(`AuthSecurityConfig.java:102`의 `CSRF_HEADER_NAME`, `frontend/src/content/auth.ts:43`의 `CSRF_HEADER`)이고, `verify-same-origin.sh`가 공유 변수 없이 리터럴을 다섯 번 다시 적는다(`:963`·`:1014`·`:1027`·`:1042`·`:1060`).
- 오류 `code` 문자열은 백엔드가 여섯 종을 정의하고(`ApiErrorHandler.java:47`·`:49`·`:51`·`:63`·`:66`·`:68`) nginx가 그중 넷을 리터럴로 다시 적는다(`bokrilog.conf:284`·`:290`·`:296`·`:302`). `ApiErrorHandler.java:43-45`가 그 중복을 주석으로 경고한다.
- 원문이 언급하지 않은 동종 중복이 둘 더 있다 — `/api/auth` 경로 문자열과 세션 쿠키 이름 `BOKRILOG_SESSION`이다. 계수는 측정 범위에 따라 달라지므로 범위를 함께 적는다: `backend/src`·`frontend/src`·`frontend/e2e`·`deploy`·`.github` 기준으로 `/api/auth`가 19개 파일에 90회대, `BOKRILOG_SESSION`이 20회대이며 저장소 전체(문서 포함)로 넓히면 둘 다 더 늘어난다. 정확한 수치가 필요하면 이 항목이 요구하는 계수 검사가 그것을 소유해야 한다 — 지금은 사람이 세는 값이라 이 문단 자체가 드리프트 대상이다.
- 유휴 30분은 단일 정의다. `application.yml:111`(`timeout: 30m`)뿐이고 코드가 그 숫자를 다시 적지 않는다(`ofMinutes(30)`·`1800` 모두 0건).
- **절대 12시간은 두 곳이다.** `application.yml:162`(`absolute-lifetime: 12h`)와 `SessionGuard.java:59`의 `DEFAULT_ABSOLUTE_LIFETIME = Duration.ofHours(12)`. 후자는 기본값이면서 동시에 상한이다 — `:83-87`이 구성값이 없거나 12시간을 넘으면 이 상수로 되돌린다. 그래서 `SessionGuard.java:55` 주석이 "값의 정의는 `application.yml`의 `bokrilog.session.absolute-lifetime` 하나다"라고 적었지만 실제로는 그 파일이 상한을 함께 소유하고, yml을 12시간보다 길게 바꾸면 조용히 무시된다. 이 축도 항목의 범위에 든다.
- 반면 `AuthFlowHandlers.java:116`의 `FLOW_SESSION_TIMEOUT_SECONDS = 300`은 인증 전 흐름 세션의 별개 값이며 위 두 수명의 중복이 아니다(정의가 그 한 곳뿐이다).

Docker 없는 정적 리터럴 비교 시험은 이 저장소에 전례가 없다. `frontend/lint-policy.test.ts`는 린터를 실제로 실행해 판정하는 다른 성격이고, 오류 본문의 두 계층 비교(`verify-same-origin.sh:622`·`:628`)도 런타임 응답을 맞대는 방식이다.

**미해소 잔여:** 결정 자체(저장소 파일을 읽어 동등성을 단정하는 Docker 없는 시험인지, 계약 값을 생성 산출물로 만드는 것인지)와 그 범위(위 다섯 축 중 어디까지 묶을지). 소유 스토리는 정해지지 않았다.

### 34. 두 인증 화면의 공통 레이아웃 CSS가 또 갈라져 복사됐다

**미해소(2026-09-08).** Story 1.5가 append했다(PR #17). `frontend/src/screens/OnboardingScreen.module.css`와 `SignedInScreen.module.css`는 각각 189줄이고 `diff`가 내는 차이는 최상단 주석 한 줄(`온보딩 화면 레이아웃` 대 `로그인 상태 화면 레이아웃`)뿐이다. 이 라운드가 더한 `.header` 규칙은 양쪽 `:65-75`에 바이트 동일하게 들어 있다(원문은 이를 "5속성"으로 적었으나 실제 선언은 아홉 개다 — 규모만 다르고 복제 사실은 같다). 로그아웃 버튼 자체는 `SignOutButton.tsx`로 공유했지만 그 버튼을 앉힐 헤더 레이아웃은 컴포넌트가 소유하지 않아 두 벌이 됐다.

**미해소 잔여:** 항목 28의 셸 추출 결정. 이 항목은 그 결정의 독립 근거이며 스스로 닫히지 않는다 — 28이 통합을 택하면 함께 닫히고, 미루기를 택하면 다음 라운드가 같은 복제를 한 번 더 늘린다.

### 35. 판정을 원장에 쓰는 단계를 스킬·표준 흐름에 넣을지

**미해소(2026-09-08).** 이 라운드가 append했다. 12건 누락을 고치면서 그 누락을 만든 구조는 그대로라는 사실이 드러났고, 처방으로 넣은 것은 위 `## 갱신 규칙`의 줄들뿐이다. `bmad-build` step-04는 `deferred-work.md`에 항목을 추가하도록 강제하지만 판정을 이 원장에 쓰는 단계는 어느 스킬에도 없다. 즉 강제 수단 없는 규칙이며, 이번 라운드가 진단한 실패의 원인은 문서가 아니라 절차에 있다.

**미해소 잔여:** `CLAUDE.md`가 스킬 파일 손수정을 금지하고 동작 변경을 `_bmad/custom/`·`bmad-customize`에 맡겼으므로, 그 경로로 present 단계에 판정 확인을 배선할지 아니면 `CLAUDE.md`의 표준 흐름 5·7에 문장으로만 둘지를 정한다. 소유 스토리는 없다.

### 36. 원장 인용의 해석 가능성을 확인하는 Docker 없는 검사

**미해소(2026-09-08).** 이 라운드가 append했다. 원장은 판정마다 검증 가능한 근거를 지목하는 것을 규율로 삼는데 그 근거를 보는 검사가 없다. 이번 라운드가 그 공백을 실측했다 — 첫 구현이 항목 15에서 Story 1.4가 건드리지 않은 줄을 "바뀌었다"고 적었고, SPEC이 정한 검증 명령 다섯 개를 모두 통과했다. 잡아낸 것은 리뷰 서브에이전트가 인용 32건을 훑은 결과이며 저장소 쪽 장치는 없다.

**미해소 잔여:** 기계로 닫히는 세 축(인용 경로의 존재와 줄 범위, 커밋·run id의 해석, 원장 절 집합과 `deferred-work.md` 항목의 개수·순서 동등)을 `.github/scripts/`의 셸 검사로 둘지. 문서-only 경로도 `changes` job을 지나므로 붙일 자리는 있다. 항목 33의 계약 리터럴 계수 검사와 같은 성격이라 함께 설계하는 편이 싸다. 소유 스토리는 없다.

### 37. 항목별 소유자·마지막 확인일 표를 원장에 둘지

**미해소(2026-09-08).** 이 라운드가 append했다. 판정이 두 스프린트 밀린 직접 원인은 주인 없는 항목이 보이지 않는 것이었는데, 이 라운드는 그 가시성을 `## 상태 요약`의 산문 한 문단으로만 고쳤다. 그 문단은 손으로 유지되는 열거라 다음 라운드에 다시 낡고, frontmatter에는 `updated:` 하나뿐이어서 항목별 `last_checked`도 다음 확인 시점도 없다.

**미해소 잔여:** 번호·제목·판정·결정 지점·마지막 확인일 열을 가진 표를 둘지, 그 표와 절이 어긋날 새 드리프트 축을 감수할지의 교환. 항목 36의 검사가 그 정합을 기계로 잡아 줄 수 있으므로 함께 정한다. 소유 스토리는 없다.

### 38. 계층·언어를 넘나드는 제공자 이름 목록의 복제와 그 동등성 검사

**미해소(2026-09-09).** Story 1.6이 append했다. 같은 제공자 집합이 열 곳 넘게 나뉘어 있다 — `AuthSecurityConfig.SUPPORTED_REGISTRATION_IDS`, `DevOidcStubConfig.PROVIDERS`, `OidcStubConfiguration`의 인라인 목록 둘, `DevOidcStubProfileTest.PROVIDERS`, `StatusApiTest`의 인라인 배열, `verify-same-origin.sh`의 heredoc 셋과 `case` 분기와 하드코딩된 기대값 `'2'` 셋, `frontend/src/content/auth.ts`의 `SIGN_IN_PROVIDERS`다. Java 안의 다섯 곳은 상수 하나로 접을 수 있지만 bash·TypeScript 쪽은 언어가 달라 접히지 않으므로 처방은 통합이 아니라 계층 간 동등성 검사다.

**항목 33과 같은 성격이다.** 33이 "계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶는 문제"이고 이것은 그 리터럴 집합에 제공자 이름을 더한 것이다. 함께 설계하는 편이 싸다. 이 라운드가 그 검사의 값을 실측했다는 사실이 새 재료다 — 이 스토리가 실제로 겪은 결함이 "목록에서 빠진 leg이 붉게 나오지 않고 사라진다"는 형태였다.

**미해소 잔여:** 검사의 형태와 범위(항목 33과 같은 결정). Story 1.7(카카오)이 이 전부를 손으로 고쳐야 하므로 그 SPEC이 다음 기회다. 소유 스토리는 정해지지 않았다.

### 39. 두 스텁의 서명 키·issuer 격리를 지나는 부정 경로 단정

**미해소(2026-09-09).** Story 1.6이 append했다. `DevOidcStubConfig`·`OidcStubConfiguration`·`verify-same-origin.sh`의 주석이 모두 "한쪽 스텁이 서명한 ID Token은 다른 쪽의 JWKS로 검증되지 않는다"를 계약으로 적지만, 그 부정 경로를 실제로 지나는 시험은 한 층도 없다. 지금 단정은 "두 issuer 문자열이 다르다"는 비교로 대체돼 있다(`AuthNaverSignInTest`).

**현재 시험 층에서는 그 케이스를 만들 수조차 없다.** `AuthFlowTestBase.issueCode(Authorization, TokenPlan)`가 흐름의 제공자로만 code를 발급하도록 구조로 막아 두었고, 이 리뷰 라운드가 `callback`·`callbackWithError`에서도 같은 결합을 세웠다(제공자를 인자로 다시 받지 않는다). 그 결합은 시험이 계약을 우회하는 것을 막는 장치이므로 되돌리지 않는다 — 확인하려면 `DevOidcStub`을 직접 불러 다른 스텁의 서명 토큰을 다른 등록의 콜백에 제시하는 **낮은 층** 단정 하나가 필요하다.

**미해소 잔여:** 그 낮은 층 단정을 둘지, 그리고 둘 자리(스텁 단위 시험인 `DevOidcStubTest`인지 인증 흐름 시험인지). 이 라운드가 더한 issuer 겹침 기동 거부(`ProviderClientRegistrations.rejectSharedIssuer`)는 **구성 오류**를 막는 것이고 이 항목은 **서명 격리**를 확인하는 것이라 서로 대체되지 않는다. 소유 스토리는 정해지지 않았다.

### 40. 한 제공자만 구성된 스택을 HTTP·화면 층에서 지나는 검사

**미해소(2026-09-09).** Story 1.6이 append했다. 제공자별 fail-closed 판정을 보는 시험 둘(`AuthNaverSignInTest`·`DevOidcStubProfileTest`)은 `ProviderClientRegistrations.from()`을 직접 불러 맵 내용만 본다. `StatusApiTest`는 **어느 제공자도 구성되지 않아** `oauth2Login` DSL 자체가 배선되지 않은 다른 상태를 보고, compose·Playwright는 `dev-oidc`의 `@Primary` 등록이 언제나 두 제공자를 함께 만들므로 부분 구성 스택이 존재하지 않는다. 그래서 "필터는 배선됐고 그 등록만 없다"는 상태가 어느 층에도 없다.

그 상태에서 사용자가 보는 것이 문제다 — 화면은 제공자 버튼을 조건 없이 그리고(`SIGN_IN_PROVIDERS`), 세션 확인 응답에는 어떤 제공자가 구성됐는지 알 방법이 없고(필드 셋뿐), 인증 시작은 최상위 내비게이션이라 사용자는 `{"code":"INTERNAL_ERROR",…}` 본문을 브라우저에서 그대로 본다. 지금은 개발·CI가 두 제공자를 모두 스텁으로 구성하고 production 배포가 없어(Story 3.8) 도달하지 않지만, 실제 제공자 등록이 하나씩 들어오는 시점에 그 창이 열린다.

**미해소 잔여:** 처방이 자명하지 않은 것이 이 항목의 내용이다. 구성 여부를 세션 확인 응답에 싣는 것은 Story 1.4가 "소비자 없는 필드를 만들지 않는다"로 거부한 방향이고, 화면이 그것을 알면 "어떤 제공자가 살아 있는가"가 응답에 실린다. 결정은 실제 제공자 등록을 들이는 스토리(Epic 1의 **실연동 미검증** 게이트)와 production 배포(Story 3.8)가 함께 볼 지점이다. 소유 스토리는 정해지지 않았다.

### 41. 두 제공자 버튼의 콘텐츠 크기 차이가 "동등한 시각 면적"으로 읽히는지 보는 층

**미해소(2026-09-09).** Story 1.6이 append했다. 대비 하한을 만족시키려 네이버 레이블을 20px·700, 로고를 24px로 둔 결과 Google 버튼(14px·600, 로고 18px)과 콘텐츠 크기가 갈렸다. 유일한 guard는 `auth-entry.spec.ts`의 두 버튼 폭·높이 동일 단정이고, 그 등식은 두 버튼의 콘텐츠 높이가 **둘 다** `--component-social-sign-in-min-target-size`(44px) 아래에 머무는 동안만 성립한다. 어느 한쪽이 그 값을 넘으면 "시각 면적" 회귀가 아니라 무관해 보이는 픽셀 불일치로 나타난다.

네이버 가이드의 "타사 버튼과 함께 쓸 때 크기를 줄이지 말라"는 만족한다. 보이지 않는 것은 반대 방향이다 — 한 제공자가 강조되어 보이는 것을 `DESIGN.md`가 금지하는데 그것을 판단하는 층이 없다. 이 리뷰 라운드가 로고를 공식 애셋 경로로 바꿔 잉크가 24px 박스를 가득 채우게 되었으므로 두 버튼의 로고 잉크 크기 차이는 오히려 더 뚜렷해졌고, 그 사실은 이 항목을 닫지 않는다.

**부분 해소(2026-09-09, Story 1.7).** 세 번째 버튼이 들어오면서 판단하는 층이 생겼다 — `e2e/auth-entry.spec.ts`가 세 버튼의 바깥 크기 동일, 공표 색·모서리, 그리고 목록 자식 수와 버튼 안쪽 구성(배지 형제·강조 자식·구분선이 모두 걸린다)을 이름으로 단정한다. 첫 시도는 `data-recommended` 속성을 읽어 **언제나 통과했고** 리뷰가 그것을 잡아 마크업 모양을 재는 형태로 바꿨다.

**미해소 잔여:** 이 원장이 처음 지적한 취약성 자체는 남았다 — 바깥 크기 등식은 세 버튼의 콘텐츠가 **모두** `--component-social-sign-in-min-target-size` 아래에 머무는 동안만 성립하고, 어느 하나가 그 값을 넘으면 "한 제공자가 강조되어 보인다"가 아니라 무관해 보이는 픽셀 불일치로 나타난다. 콘텐츠 높이나 잉크 면적을 직접 재는 축은 여전히 없다. 소유 스토리는 정해지지 않았다.

### 42. `verify-same-origin.sh`의 나머지 두 제공자 루프에 완주 수 가드가 없다

**해소(2026-09-09, Story 1.7).** 세 루프가 모두 완주 수 가드를 갖췄고, 가드가 세는 기대값은 스크립트 안의 제공자 목록 하나에서 파생된다(하드코딩된 `3` 셋이 사라졌다). `deploy/compose/verify-same-origin.sh`를 실제로 돌려 526개 단정 전부 통과·exit 0으로 확인했다. 아래는 Story 1.6이 이 항목을 열 때 적은 근거다 — 이 라운드가 왕복 루프에만 `왕복 루프가 제공자 수만큼 돌았다`를 넣었다. 콜백 탐침(`CALLBACK_PROVIDERS`)과 업스트림 오류(`UPSTREAM_ERROR_PROVIDERS`) 루프는 `docker compose exec`를 부르지 않아 이번 stdin 결함에는 걸리지 않았지만, 한 목록에서 이름이 빠지면 그 제공자를 조용히 건너뛴다는 형태는 같다. "없는 단정은 붉게 나오지 않는다"가 이 스크립트에서 두 번 더 성립할 수 있다.

**남은 교환:** 루프 완주 수를 세는 관용을 함수 하나로 묶을지. 항목 38·43과 같은 파일의 같은 부류(스크립트가 조용히 절반만 도는 형태)이므로 함께 보는 편이 싸다. 이 리뷰 라운드는 대신 `round_trip()` 진입에서 결과 전역을 비우는 겹을 하나 더했다 — 그것은 leg이 사라졌을 때 **뒤의 단정이 앞 leg의 값을 물려받는 것**을 막는 것이고, 이 항목의 "leg이 사라진 것 자체를 세는 자리"와는 다른 축이다. 소유 스토리는 정해지지 않았다.

### 43. `compose()` 한 곳에 stdin 차단을 두어 `docker compose exec`의 stdin 삼킴을 원천 차단할지

**미해소(2026-09-09).** Story 1.6이 append했다. 이 라운드는 원인을 `psql_value`·`psql_exec` 두 함수에서 닫았다. 그것이 실제 결함 경로를 없애지만, 앞으로 누가 루프 안에서 다른 `compose exec`를 부르면 같은 결함이 다시 난다. `compose()` 한 곳에 `</dev/null`을 두면 클래스가 원천 차단되는데 그 함수는 `up`·`run --rm migrate`·`logs`도 태우므로 그 호출들이 stdin을 쓰지 않는다는 확인이 함께 필요하다.

**미해소 잔여:** 그 확인과 변경. 검증이 통과하는 상태에서 넓히는 변경이라 이 스토리에서 하지 않았고, 리뷰 라운드에서도 같은 판정을 유지한다 — 이 라운드의 patch 열한 건은 모두 결함이나 단정 공백을 닫는 것이고 이것은 예방적 확장이다. 소유 스토리는 정해지지 않았다.

### 44. `application.yml`이 제공자 엔드포인트 재정의 네 이름을 선언하지 않는다

**미해소(2026-09-09).** Story 1.6이 append했다. 이 저장소의 규약은 "이름은 `application.yml`이 정하고 값은 환경이 준다"인데 `issuer-uri`·`authorization-uri`·`token-uri`·`jwk-set-uri` 네 이름은 어느 제공자에도 선언돼 있지 않다. 시험(`OidcStubConfiguration`)이 `DynamicPropertyRegistry`로 실제로 그 이름을 주므로 이름은 존재하고 Boot의 relaxed binding으로 동작하지만, 개발·CI가 무엇을 덮는지가 코드에서만 발견된다.

이 스토리의 SPEC Execution 항목은 네이버에 그 넷을 더하라고 적었으나 Google이 선언하지 않은 상태에서 한쪽만 더하면 비대칭이 생긴다. 그래서 두 제공자를 함께 정하는 것으로 미뤘고, 그 판정은 리뷰 라운드에서도 그대로다 — 카카오가 들어오면 세 제공자 × 네 이름이 되므로 한 번에 정하는 편이 싸다.

**미해소 잔여:** 네 이름을 세 제공자 모두에 선언할지(그러면 `application.yml`이 열두 줄 늘고 값은 전부 비어 있다), 아니면 규약의 예외로 두고 그 사실을 주석으로 남길지. Story 1.7이 다음 기회다. 소유 스토리는 정해지지 않았다.

## 갱신 규칙

- `deferred-work.md`에 새 항목이 append되면 이 원장에 같은 순서로 판정을 추가한다.
- 판정을 바꿀 때는 기존 문단을 덮어쓰지 않고 날짜와 근거를 붙여 새 판정을 추가한다.
- 근거는 판정의 종류에 맞게 지목한다. **계획 결정**이 닫는 항목은 권위 문서(PRD, `ARCHITECTURE-SPINE.md`, `EPIC-IMPLEMENTATION-READINESS.md`, `epics.md`)의 절 번호로, **구현·검사**가 닫는 항목은 `파일:줄번호`·PR 번호·머지 커밋·CI job 결과로 지목한다. 어느 쪽이든 읽는 사람이 열어서 확인할 수 있어야 한다. (2026-09-08 개정: 이 줄은 원래 권위 문서 절 번호만 허용했으나 항목 11·12가 이미 `ci.yml`·SPEC·PR 번호를 근거로 썼고 14·15·24도 그렇다. 낡은 것은 판정이 아니라 규칙 문장이었다.)
- 인용한 근거가 그 사실을 실제로 뒷받침하는지 열어서 확인한 뒤에 적는다. 줄이 존재한다는 것만으로는 부족하다 — 항목 15의 첫 해소 판정이 Story 1.4가 건드리지 않은 줄을 "바뀌었다"고 적었고 그것은 개수·형태 검사 전부를 통과했다.
- 스토리가 `done`으로 넘어갈 때, 그 스토리를 결정 지점으로 지목한 항목의 판정을 함께 확인한다. 판정이 없으면 그 스토리의 present 단계에서 추가하고, 결정하지 못한 채 지나갔으면 미해소로 적으면서 주인이 없어진 사실을 남긴다. 항목 23·27이 이 확인 없이 소유 스토리가 지나가 버린 사례이며, 두 스프린트가 지난 뒤 이 원장을 다시 맞출 때야 드러났다.
- 위 확인은 소유가 스토리 이름으로 적힌 항목만 잡는다. 그래서 두 부류를 스프린트 경계에서 따로 재확인한다 — (가) 소유를 조건으로 적은 항목(항목 23의 "HTTP 요청 타임아웃이 생기는 스토리"처럼 이름이 없어 `done` 방아쇠가 걸리지 않는다), (나) 소유 스토리가 지나가 주인이 없어진 항목. 현재 (가)·(나)에 드는 것은 23·27이며, 주인 없는 항목 전체 목록은 `## 상태 요약`이 소유한다.
- `defer` 판정으로 `deferred-work.md`에 새 항목을 append한 라운드는 **그 라운드 안에서** 새 항목의 판정도 함께 적는다. 다음 라운드로 넘기면 이 원장이 고치려는 드리프트를 스스로 시작하는 것이다. 항목 35·36·37이 그 규칙을 적용한 첫 사례다.

### 45. 제공자별 왕복 시험이 제공자 비종속 축을 통째로 복제한다

**미해소(2026-09-09).** Story 1.7이 append했다. `AuthKakaoSignInTest`가 인가 시작 불변식·신규·기존·취소·제공자 오류·토큰 장애·이미 인증된 세션을 `AuthNaverSignInTest`에서 그대로 되풀이해 셋째 사본이 됐다. 이 축들은 제공자로 파라미터화된 코드(`AuthFlowHandlers`·`AuthSessionService`)를 지나므로, 같은 SPEC이 `AuthTokenValidationTest`의 검증 실패 18축을 "제공자 비종속이라 복제하지 않는다"로 거절한 것과 성질이 같다.

**미해소 잔여:** 파라미터화한 공통 시험 하나와 제공자 고유 구성만 보는 작은 시험으로 가를지, 아니면 제공자마다 읽기 쉬운 사본을 유지하는 값을 인정할지. 넷째 제공자는 없으므로 압력은 당장 오지 않는다 — 판단은 제공자를 늘리는 스토리가 아니라 시험 구조를 소유하는 라운드가 해야 한다. 소유 스토리는 정해지지 않았다.

### 46. `credentials()` fixture와 그 근거 주석이 시험 세 파일에 복제됐다

**미해소(2026-09-09).** Story 1.7이 append했다. `AuthNaverSignInTest`·`DevOidcStubProfileTest`·`AuthKakaoSignInTest`가 같은 본문과 같은 "자격정보 모양 리터럴을 시험 fixture에도 두지 않는다" 주석을 각자 든다.

**미해소 잔여:** `AuthFlowTestBase`로 올릴지 작은 시험 헬퍼를 새로 둘지. 전자는 이 스토리가 무변경으로 둔 파일이라 손대지 않았다. 항목 45와 같은 뿌리(시험 구조)이므로 함께 보는 편이 싸다. 소유 스토리는 정해지지 않았다.

### 47. 스텁 기동 guard가 제공자마다 손으로 쓴 OR 사슬이라 하나를 빠뜨리면 열린 채 통과한다

**미해소(2026-09-09).** Story 1.7이 append했다. `DevOidcStubConfig`의 guard는 `hasCredentials(google) || hasCredentials(naver) || hasCredentials(kakao)`이고 `PROVIDERS`와도 `AuthProperties`의 성분과도 컴파일 시점 연결이 없다. 이것이 스텁과 실제 제공자가 함께 뜨는 것을 막는 두 층 중 하나이므로, 넷째 성분을 record에 더하고 이 줄을 잊으면 실제 자격정보가 스텁 옆에서 살아 있게 된다.

**미해소 잔여:** `AuthProperties`에 `providers()` 접근자를 두어 `from()`과 이 guard가 함께 순회하면 그 실패 모양이 사라진다. 다만 그 변경은 `from()`의 구조를 바꾸므로 검증이 통과한 상태에서 넓히지 않았다. 항목 48과 같은 처방을 공유한다. 소유 스토리는 정해지지 않았다.

### 48. 같은 계층 안에도 제공자 목록 사본이 넷이고 `SUPPORTED_REGISTRATION_IDS` 등가 단정이 없다

**미해소(2026-09-09).** Story 1.7이 append했다. `DevOidcStubProfileTest.PROVIDERS`·`OidcStubConfiguration.PROVIDERS`·`AuthProviderIndependenceTest.PROVIDERS`·`StatusApiTest`의 인라인 배열이 모두 같은 패키지의 `DevOidcStubConfig.PROVIDERS`를 참조할 수 있다. 특히 `SUPPORTED_REGISTRATION_IDS`(404와 500을 가르는 집합)와 `from()`이 채우는 맵의 키 집합이 어긋나면 아는 제공자가 조용히 404가 되는데, 그 둘을 묶는 단정이 없다.

**미해소 잔여:** 이것은 항목 33·38이 다루는 **계층 간** 등가성이 아니라 계층 **안** 중복이라 처방이 다르고 훨씬 싸다(`containsExactlyInAnyOrderElementsOf` 한 줄과 상수 참조 넷). 33·38이 열려 있는 동안 이 값싼 절반만 먼저 닫을지가 판단할 지점이다. 소유 스토리는 정해지지 않았다.

### 49. 카카오 심볼이 래스터 추출인데 그 추출을 재현할 산출물이 저장소에 없다

**미해소(2026-09-09).** Story 1.7이 append했다. 카카오는 네이버와 달리 버튼 벡터 애셋을 공표하지 않고 가이드와 리소스 도구가 PNG·PSD만 낸다. 그래서 공식 래스터에서 심볼을 추출해 타원과 직선으로 복원했고 근거(RMS 0.11px·최대 0.42px, 52.9px 기준)를 SPEC의 Change Log와 코드 주석에 적었지만, 추출 스크립트도 원본 해시도 적합도 데이터도 커밋되지 않았다. 자동 검사는 종횡비와 색 둘뿐이다.

**미해소 잔여:** 가이드의 첫 금지가 "심볼의 형태·비율·색 변경"이므로 가이드가 갱신될 때 그 주장을 다시 검증할 방법이 필요하다. 추출 산출물을 저장소에 두는 길과 실연동 게이트의 항목으로 올리는 길이 있고, 전자는 저장소에 이미지 처리 도구 의존을 들이는 대가가 있다. 소유 스토리는 정해지지 않았다.

### 50. `OnboardingScreen.test.tsx`의 목적지 이동 단정이 부하 아래 간헐적으로 깨진다

**미해소(2026-09-09).** Story 1.7 라운드의 검증 중 백엔드 Gradle 빌드와 프런트엔드 Vitest를 동시에 돌렸을 때 `OnboardingScreen.test.tsx:162`의 `replaceLocation('/dashboard')`가 호출 0건으로 실패했고, 부하 없는 재실행에서는 157/157이 통과했다. 이 파일은 Story 1.7이 논리를 바꾸지 않았으므로 `develop`에 이미 있던 취약성이며 CI 러너가 붐빌 때 같은 방식으로 붉게 나올 수 있다.

**미해소 잔여:** 대기 조건을 명시적으로 바꾸는 것(`findBy*`의 타임아웃이 아니라 이동이 일어나는 사건 자체를 기다리는 형태)이 처방이지만, 재현이 부하에 의존해 고친 뒤 확인하기가 어렵다. 비결정적 실패는 재실행으로 덮이면서 신뢰를 갉아먹으므로 방치할 항목은 아니다. 소유 스토리는 정해지지 않았다.

**해소(2026-10-04, frontend-effect-timing).** 원인은 대기 조건이 아니라 이동의 시점이었다. `replaceLocation`이 `useSessionGate`의 passive effect에 있었고, 세션 응답이 만든 갱신의 passive effect는 Scheduler의 다음 작업으로 미뤄져 부하가 걸리면 `findByText(redirecting)` 직후의 동기 단정보다 늦게 돌았다. 두 이동(서버가 고른 목적지, 만료 표지)을 layout effect로 옮겨 "이동 중"을 그린 커밋에서 떠나게 했다(`frontend/src/session/useSessionState.ts:498`·`:506`). 원문의 처방대로 단정은 이동 자체를 기다린다(`frontend/src/screens/OnboardingScreen.test.tsx:416-418`, 같은 모양의 `frontend/src/screens/SignedInScreen.test.tsx:1368-1370`). 기대값은 바뀌지 않았다.

확인은 두 겹이다.

- **결정적 가드.** `frontend/src/screens/SignedInScreen.test.tsx:1849`의 첫 관측 가드가 "이동 중" 문장이 처음 보이는 순간 `replaceLocation`이 이미 불렸는지를 부하 없이 단정한다(`frontend/src/test/firstSight.ts`). 이 effect 하나를 `useEffect`로 되돌리면 그 파일 10회 중 10회 이 가드가 실패했고, 복원하면 10회 중 10회 통과했다. 이 가드는 CI `frontend-quality`가 PR마다 돌리는 Vitest에 들어 있으므로 effect가 passive로 돌아가면 부하와 무관하게 붉게 나온다.
- **로컬 부하 반복.** `frontend/scripts/stress-test.sh`(`yes` 16개)로 리뷰를 반영한 최종 트리의 전체 Vitest(871건)를 36회 돌려 모두 통과했다. 리뷰 반영 전 구현 트리(866건)도 12회·24회 모두 통과했다. 같은 스크립트·같은 부하에서 수정 전 트리(`7245371`)는 12회 중 1회 실패했다(`SignedInScreen` AC-13의 초점 단정, 같은 원인의 다른 효과). `_bmad-output/implementation-artifacts/spec-frontend-effect-timing.md` `## 측정`의 기준선은 12회 중 2회 실패였다. 가드 파일만 3회 돈 부하 반복도 통과했다.

### 83. `InitialInputsCollection.test.tsx`의 403 시험이 전체 실행 부하 아래 간헐적으로 실패한다

**해소(2026-10-04, frontend-effect-timing).** Story 2.8이 append했다(시험은 Story 2.7이 더했다). 원인은 시험이 아니라 `PlanPublicationSection`의 폴링 interval이 passive effect에서 걸리고 풀리던 것이다. 시험은 `계산·발행 중`을 본 직후 시계를 2초 당기고, 거절 문장을 본 직후 읽기 수를 센다. 부하가 걸리면 interval이 그 당김보다 늦게 걸리거나 거절을 그린 뒤에도 남아 읽기를 계속했다. `spec-frontend-effect-timing.md` `## 측정`의 기준선 1회차가 이 시험의 `reads` 7 ≠ 2였다.

interval을 layout effect로 옮겨 그 상태를 그린 커밋에서 걸리고 풀리게 했다(`frontend/src/components/PlanPublicationSection.tsx:150`). 같은 파일군의 세 폴링도 같게 옮겼다(`frontend/src/components/InitialInputsCollection.tsx:364`·`:758`·`:768`). 시험의 대기와 기대값은 바꾸지 않았고 그 의존을 주석으로 적었다(`frontend/src/components/InitialInputsCollection.test.tsx:1081-1086`).

확인은 항목 50과 같은 두 겹이다. 결정적 가드는 `frontend/src/components/InitialInputsCollection.test.tsx:1130`이다 — `계산·발행 중`이 처음 보이는 순간 interval이 이미 걸려 있는지를 가짜 `setInterval`의 수로 단정한다. 이 effect 하나를 `useEffect`로 되돌리면 그 파일 10회 중 10회 이 가드가 실패했고 복원하면 10회 중 10회 통과했으며, CI `frontend-quality`가 PR마다 돌린다. 로컬 부하 반복은 항목 50의 전체 Vitest 실행과 같고, 이 파일만 `stress-test.sh --runs 3`으로 돈 실행도 통과했다.
