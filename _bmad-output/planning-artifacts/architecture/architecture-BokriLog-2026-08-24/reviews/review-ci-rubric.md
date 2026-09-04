# CI Plan Rubric Review — BokriLog

- **검토 대상:** `CI-PLAN.md`
- **상위 권위:** `ARCHITECTURE-SPINE.md`
- **대조 자료:** `PORTFOLIO-SOLUTION-DESIGN.md`, `epics.md`, `EPIC-IMPLEMENTATION-READINESS.md`, `deferred-work.md`, 현재 Git/프런트엔드 설정
- **렌즈:** BMad Architecture Reviewer Gate의 good-spine rubric walker
- **판정:** **PASS WITH CHANGES — Critical은 없지만 High 2건을 닫기 전에는 CI/CD 인계 계약의 최종 정본으로 확정하기 이르다.**

## 한 문장 판정

이 계획은 GitHub-hosted Actions, PR/통합/Release Gate 분리, 기능과 함께 확장하는 게이트, 운영 OCI VM에서의 build 금지, digest 기반 CD 인계라는 핵심 분기점을 올바르게 고정했지만, rollback 기준 digest의 소유권을 CI와 CD 사이에서 잘못 배치했고 현재 확인된 lint/format 표류를 결정 또는 Deferred로 회수하지 못했다.

## 잘된 점

- 현재 저장소에는 `frontend/`만 있고 `.github/workflows/`, `backend/`, `deploy/`가 없다는 현실을 반영해 미래 코드를 미리 구현하지 않는다.
- GitHub-hosted runner와 OCI production VM의 신뢰 경계를 명확히 분리해 AD-14·AD-15의 운영 제약을 보존한다.
- PR CI, `develop` 통합 CI, `main` Release Gate의 목적과 권한을 분리하고, Production 배포를 CI 성공의 부수효과로 만들지 않는다.
- 타입·build·unit을 브라우저·컨테이너보다 먼저 실행하고, 중복 build 제거, stale run 취소, 필요한 브라우저만 실행하는 시간 최적화 순서가 구체적이다.
- OAuth·Bybit·광고의 실제 외부 상태를 PR merge gate에서 분리하고 stub 기반 결정성을 요구해 외부 장애가 제품 회귀 판정을 흔들지 않게 한다.
- AD-12의 Worker drain → expand migration → inactive API 검증 → Nginx cutover 계약과 동일 ARM64 digest 배포 원칙을 약화하지 않는다.
- Registry, GitHub-to-OCI 인증, action SHA처럼 실제 배포 코드와 당시 공식 지원을 봐야 하는 항목은 Story 3.8이라는 재검토 시점을 붙여 성급하게 확정하지 않는다.

## High Findings

### H1 — `previous_digest`를 Release Gate 산출물로 두면 CI/CD 권한 분리와 실제 rollback 기준이 어긋난다

**근거:** `CI-PLAN.md` §4는 Release Gate 권한을 `registry write만`으로 제한하지만, §12 evidence manifest에는 `previous_digest`를 rollback 후보의 정확한 digest로 요구한다. 같은 절은 CD가 시작될 때까지 운영자 승인과 OCI 사전검사가 남아 있다고 정의한다.

**문제:** 이전 운영 digest는 release candidate를 만든 시점이 아니라 실제 배포를 시작하는 시점의 운영 상태다. CI가 production 조회 권한 없이 이를 알 수 없고, 조회하더라도 승인 대기 중 다른 hotfix가 배포되면 값이 낡는다. 또한 `rollback_compatibility`의 `old binary`가 `main`의 직전 release인지 실제 배포 중인 digest인지 구현자마다 다르게 선택할 수 있다. 이 상태로는 두 CD 구현이 서로 다른 버전으로 rollback해도 모두 문서를 따랐다고 주장할 수 있다.

**권고 수정:** 두 필드를 분리한다.

- Release Gate는 `compatibility_base_digest`와 그 기준 schema에 대한 `old binary + new schema` 검증을 기록한다.
- CD는 배포 직전 authoritative runtime/배포 원장에서 `previous_deployed_digest`를 읽고, candidate의 호환성 증거가 그 digest를 포함하는지 확인한다.
- 포함하지 않으면 새 compatibility 검증 없이는 cutover하지 않는다.
- `previous_deployed_digest`, 실제 schema version, cutover/rollback 결과는 CI evidence가 아니라 append-only deployment audit가 소유한다.

**처리:** 문서 내 명확한 수정이 가능하다. 토론 없이 autofix 가능한 High다.

### H2 — 현재 확인된 ESLint·Stylelint/format 표류가 CI 정본에서 결정도 Deferred도 되지 않았다

**근거:** `deferred-work.md`에는 토큰 참조 규칙과 import 순서를 사람이 지키는 동안 실제 위반이 발생했다는 근거와 함께 ESLint·Stylelint 도입이 명시돼 있다. 그러나 `CI-PLAN.md`의 현재 게이트, 단계별 확장, Deferred/재검토 조건 어디에도 lint·format 게이트의 도입 시점과 blocking 정책이 없다.

**문제:** 이 문서는 CI 원칙·단계의 정본을 자처한다. 이미 관측된 코드 규칙 표류를 회수하지 않으면 후속 `spec-ci-foundation.md` 작성자가 lint를 지금 넣을지, 나중에 넣을지, 영구적으로 사람 리뷰에 맡길지 서로 다르게 결정한다. 이는 Reviewer Gate가 찾는 실제 divergence point이며, 현재 게이트가 typecheck/build/test를 통과해도 디자인 토큰과 import 규칙은 계속 표류할 수 있다.

**권고 수정:** 다음 중 하나를 명시적으로 채택한다.

- 권장: lint/format 도구 도입 PR에서 baseline을 정리하고 `lint`, `stylelint`, `format:check`를 PR required gate로 함께 승격한다. 첫 CI foundation과 분리할 경우 정확한 Story/선행조건을 적는다.
- 또는 Deferred에 도입 트리거와 그때까지의 임시 human-review 책임을 기록한다.

**처리:** 현재 구현에 도구를 추가할 필요는 없고 계획 문서에 소유 시점과 판정만 추가하면 된다.

## Medium Findings

### M1 — WebKit의 현재 적용 여부가 두 절에서 다르게 읽힌다

§6은 첫 구현을 Chromium만 쓰는 단일 `frontend-quality` job으로 정의한다. 반면 §7 표는 `push → develop`, `PR → main`, Release Gate에서 WebKit을 이미 필수로 표현하고 §13도 `main` 필수 검사로 둔다. 현재 `playwright.config.ts`에는 Chromium project만 있으므로 첫 구현 SPEC이 어느 계약을 따라야 하는지 모호하다.

**권고:** WebKit을 `CI foundation 이후` 단계로 명시하고, advisory 도입 → 안정성 기준 충족 → required 승격의 객관적 조건을 둔다. 예: 최근 10회 중 infrastructure/flaky failure 0회와 p95 예산 충족. 또는 첫 foundation부터 필수라면 §6에 WebKit 설치·실행을 포함한다.

### M2 — SBOM·서명은 있으나 취약점·secret·정적 분석 정책이 결정/Deferred되지 않았다

§11은 `npm audit`를 단독 merge gate로 쓰지 않는다고 올바르게 제한하지만, 대신 어떤 공급망/애플리케이션 보안 검사를 언제 수행할지는 없다. 기존 current-reality review도 lockfile/image 갱신 시 CVE scan을 후속 검증으로 남겼다. Secret scanning, dependency diff/review, SAST, container CVE 및 license 판정은 각각 required인지 advisory인지, 외부 DB 장애 시 무엇을 할지 결정되지 않았다.

**권고:** 현재 PR에는 최소 secret/diff 정책, container가 생기는 Story 3.8에는 image CVE/license 정책을 배정한다. 도구 선택은 구현 시점으로 Deferred해도 되지만 severity 임계값, suppress 승인·만료, 외부 scanner 장애의 판정은 계획이 소유해야 한다.

### M3 — Release evidence의 필드는 있으나 evidence 자체의 불변성과 소비 규칙이 부족하다

§12는 manifest 필드를 잘 정의하지만 schema version, 직렬화 형식, 저장 위치, 보존기간, 무결성/서명, 중복·재실행 시 식별 규칙이 없다. `workflow_run_id`만으로는 artifact 복사·교체 또는 rerun 간 어느 evidence가 권위인지 막지 못한다.

**권고:** 구현 도구는 Story 3.8로 미루되 다음 불변식은 지금 고정한다. Evidence는 versioned machine-readable schema이고 candidate image digest에 cryptographically bound되어야 하며, 보호된 `main`의 commit/run attempt와 유일하게 연결되고, CD는 registry/attestation store의 검증된 evidence만 소비한다. 보존기간은 rollback·감사 기간보다 짧을 수 없다고 둔다.

### M4 — 문서/BMAD-only 변경의 시간 절약 경로가 현재 계획에서 완결되지 않았다

Top-level `paths` filter를 금지하고 안정적인 required gate 아래에서 비싼 job만 skip한다는 원칙은 적절하다. 다만 §5는 `docs/*` PR에도 동일한 PR 필수 CI를, §14는 특정 경로에서만 `bmad-contract`를 실행한다고만 하여, BMAD/docs-only 변경에서 frontend E2E도 실행하는지 명확하지 않다.

**권고:** 변경 분류 job은 항상 실행하고, 제품 runtime/lockfile/workflow가 바뀌지 않은 문서 전용 변경에서는 frontend browser job을 성공 skip할 수 있다고 결정한다. 반대로 workflow, test 분류기, 공통 build script 변경은 전체 게이트를 강제로 실행하는 fail-safe 목록에 둔다.

## Low / 관찰 사항

- `첫 10회 p95`는 표본이 작아 사실상 최대값에 민감하다. 초기 10회는 median/max와 개별 단계 시간을 같이 기록하고, p95 운영 판정은 표본이 더 쌓인 뒤 사용하는 편이 명확하다.
- `ARCHITECTURE-SPINE.md` Stack의 React verified baseline은 19.2.7이지만 현재 `frontend/package-lock.json`은 19.2.8이다. CI 계획은 lockfile을 권위 원본으로 둬 직접 충돌하지 않지만, 상위 spine의 관측 기준은 다음 아키텍처 갱신 때 최신 현실로 맞춰야 한다.
- CI 실패 알림, 책임자, 장시간 GitHub Actions 장애 때의 merge 예외 절차가 없다. 1인 프로젝트에서는 당장 High는 아니지만 협업자 추가 또는 공개 베타 전에 운영 규칙으로 결정하거나 Deferred에 재검토 조건을 두는 편이 좋다.

## Inherited Requirements Reconciliation

| 상위 계약 | CI 계획 반영 | 판정 |
| --- | --- | --- |
| AD-2 Java 25/Spring과 금융 `double`/`float` 금지 | Story 1.2/Epic 2 게이트에 반영 | PASS |
| AD-3 Modulith 경계 | backend quality와 release evidence에 반영 | PASS |
| AD-4 same-origin `/api`, 브라우저 금융·인증 영속 금지 | same-origin integration 및 E2E에 반영 | PASS |
| AD-9 결정적 BigDecimal 계산 | 승인 예제·결정성·경계값 방향에 반영 | PASS |
| AD-10·11 인증/credential/redaction | stub, secret-free PR, redaction에 반영 | PASS |
| AD-12 expand/rollback/worker drain | CD 인계 순서는 반영됐으나 이전 digest 소유권 오류 | **PARTIAL — H1** |
| AD-14 테스트 위치와 telemetry redaction | hosted CI와 운영 smoke를 분리 | PASS |
| AD-15 ARM64/OCI resource 경계 | release ARM64와 CD 사전 gate에 반영 | PASS |
| AD-16·20 fencing/원자 발행/사용자 격리 | Epic 2와 DB integration에 반영 | PASS |
| Story 3.8 서명·SBOM·migration·rollback 증거 | 필드와 단계 존재 | PASS WITH M2/M3 |
| 현재 deferred lint·WebKit | WebKit은 방향 있으나 phase 모호, lint는 누락 | **FAIL — H2/M1** |

## Dimension Coverage

| CI/CD 차원 | 상태 |
| --- | --- |
| CI 플랫폼·runner 신뢰 경계 | 결정됨 |
| 브랜치·이벤트·취소 | 결정됨 |
| 현재 프런트엔드 gate | 결정됨 |
| 기능별 test 계층 확장 | 결정됨 |
| 외부 provider와 secret 경계 | 결정됨 |
| 브라우저 범위 | 방향 결정, 활성화 조건 보완 필요 |
| 시간 예산·최적화 순서 | 결정됨 |
| branch protection | 결정됨, plan 제약 시 fallback 있음 |
| ARM64 build와 CI/CD 역할 분리 | 결정됨 |
| rollback 기준 상태 소유권 | **수정 필요** |
| release evidence 무결성·보존 | 부분 결정, 보완 필요 |
| SAST/secret/CVE/license | **침묵 — 결정 또는 Deferred 필요** |
| Registry·OCI 인증·action SHA | Story 3.8로 안전하게 Deferred |
| 실제 provider/법률/운영 훈련 | CI 바깥 launch gate로 결정됨 |
| 실패 알림·merge 예외·ownership | 낮은 위험의 미결 차원 |

## 최종 Rubric

| 기준 | 결과 |
| --- | --- |
| 실제 divergence point를 고정하는가 | **부분 통과** — 핵심 플랫폼/경로는 고정, lint 표류 누락 |
| 규칙이 stated divergence를 기계적으로 막을 수 있는가 | **부분 통과** — rollback 기준 digest 소유권 수정 필요 |
| Deferred가 안전한 기본값과 재검토 조건을 갖는가 | **부분 통과** — registry/auth는 좋으나 보안 스캔·lint는 침묵 |
| Brownfield 현실을 ratify하는가 | **통과**, WebKit phase와 React baseline 관찰사항 있음 |
| 상위 아키텍처와 모순이 없는가 | **부분 통과** — AD-12의 실제 rollback 상태와 H1 충돌 |
| PRD/Epic의 CI 관련 capability를 덮는가 | **대체로 통과** |
| 운영·환경 envelope를 덮는가 | **통과** |
| 구현을 성급하게 시작하지 않았는가 | **통과** — workflow/package/OCI 변경 없음 |

## Gate 권고

**H1과 H2를 문서에서 수정한 뒤 승인한다.** M1과 M3도 같은 문서 수정에서 닫는 편이 후속 `spec-ci-foundation.md`와 Story 3.8 구현의 해석 차이를 크게 줄인다. M2와 M4는 최소한 Deferred 항목과 재검토 시점까지 명시해야 한다. 이 수정은 모두 계획 수준의 계약 정리이며 GitHub Actions, package script, branch protection 또는 OCI 설정 구현을 요구하지 않는다.
