---
title: 'Deferred Work 해소 원장'
status: living
created: '2026-09-02'
updated: '2026-09-05'
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
| 부분 해소 | 2 |
| 미해소 | 6 |

미해소·부분 해소 8건 중 개발 스프린트를 막는 항목은 없다. 부분 해소 2건은 외부 승인 게이트(G-5, 출시 전 사용자 과업)에 묶여 있고, 미해소 6건(13–18)은 각각 소유 스토리, 조건부 의존 갱신 PR 또는 별도 작업이 정해져 있다. Story 1.2 완료 전 조건이던 12(린터·포매터)는 2026-09-05에 해소됐다. 표는 항목별 판정을 다시 세어 2026-09-05에 맞췼다.

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

### 15. 진입 화면 `다음 단계` 카피를 소셜 로그인 버튼으로 교체

**미해소.** `epics.md` Story 1.4(Google 가입·로그인)가 소유한다. `frontend/src/content/entry.ts`의 "로그인 수단 선택은 아직 준비 중입니다."는 로그인 수단이 생기는 즉시 거짓이 되므로 Story 1.4 SPEC의 필수 변경으로 넣는다.

### 16. `bmad-contract` 검사 구현

**미해소.** CI-PLAN §14가 `_bmad/**`·`.agents/skills/**`·`.claude/skills/**`·`.codex/**`·`scripts/check-bmad-env.sh`·`uv.toml` 변경에만 도는 별도 검사로 정했다. 러너에 `uv`와 관리 Python 다운로드가 필요해 frontend 필수 경로와 분리했다(`spec-ci-foundation.md`). 그때까지 이 경로만 바뀐 PR은 fail-closed 분류로 전체 frontend 검사를 돌고, BMAD 계약 자체는 어떤 게이트도 검증하지 않는다. 개발 스프린트를 막지 않는다.

### 17. `eslint-plugin-jsx-a11y` 도입

**미해소.** 2026-09-05 기준 최신판(6.10.2)의 peerDependencies가 ESLint 9까지만 선언해 항목 12의 정책 PR에서 제외했다. CI-PLAN §11이 정한 별도 의존 갱신 PR로 추가한다. 렌더 결과의 접근성은 E2E axe 검사가 담당하므로 개발 스프린트를 막지 않는다.

### 18. noscript 인라인 스타일 폴백 값의 표류 시험

**미해소.** `frontend/index.html`의 noscript 인라인 스타일은 JS 없이 `tokens.css`가 로드되지 않아 `var(--…, 폴백)` 폴백 리터럴이 필요하고, stylelint 대상(`src/**/*.css`) 밖이다. 폴백 값을 `tokens.css`와 비교하는 시험이 없어 토큰 변경을 따라가지 못해도 CI가 알지 못한다. `DEVELOPMENT.md` 예외 목록에 근거를 기록했고 시험은 별도 작업이다. 개발 스프린트를 막지 않는다.

## 갱신 규칙

- `deferred-work.md`에 새 항목이 append되면 이 원장에 같은 순서로 판정을 추가한다.
- 판정을 바꿀 때는 기존 문단을 덮어쓰지 않고 날짜와 근거를 붙여 새 판정을 추가한다.
- 해소 근거는 반드시 권위 문서(PRD, `ARCHITECTURE-SPINE.md`, `EPIC-IMPLEMENTATION-READINESS.md`, `epics.md`)의 절 번호로 지목한다.
