# CI 계획 Current Reality 검토 — 2026-09-04

- **검토 대상:** `CI-PLAN.md`
- **검토 범위:** 현재 저장소와 GitHub/Playwright 공식 문서 기준으로 실행 가능성과 전제 확인
- **판정:** **PASS WITH RECORDED CONSTRAINTS**

## 저장소 현실

- 구현은 `frontend/` React/Vite/TypeScript와 Vitest·Playwright Chromium까지이며 `backend/`, `deploy/`, `.github/workflows/`는 없다.
- Node 24와 npm lockfile이 현재 재현 기준이다. 따라서 첫 CI를 frontend 단일 job으로 시작하고 미래 gate는 기능 Story와 함께 활성화하는 계획이 현재 상태와 일치한다.
- 로컬 `npm ci`, typecheck, build와 Vitest는 성공했다. Chromium E2E assertion 13개도 성공했지만 Windows에서 프로세스가 종료되지 않아 첫 hosted Ubuntu 실행에서 timeout 없는 종료를 확인한다는 위험 기록이 타당하다.

## 공식 동작 대조

- GitHub는 required workflow에 path filter를 걸어 workflow 자체가 생략되면 check가 pending으로 남을 수 있고, 조건부로 skip된 job은 success처럼 취급될 수 있다고 설명한다. 항상 실행되는 `ci-required`가 기대 job 결과를 직접 판정하는 계획과 일치한다.
- GitHub concurrency는 동시 실행 제한과 in-progress 취소를 제공하지만 pending 실행 처리와 순서는 설정에 영향을 받는다. PR stale-run 취소와 production non-cancellation을 분리하고, 실제 배포에는 외부 상태와 함께 확인되는 durable lock·CAS를 추가한 결정이 타당하다.
- GitHub는 third-party action을 full-length commit SHA로 고정하는 방식을 immutable reference로 권고한다. 계획의 action pinning과 일치한다.
- Playwright는 CI에서 `npm ci`, browser/OS dependency 설치와 worker 1을 권고하며 browser binary cache는 일반적으로 권장하지 않는다. 계획의 npm 다운로드 cache 우선, browser cache 측정 후 결정, 안정성 우선 정책과 일치한다.
- Playwright의 `retain-on-failure`는 최초 실패 trace를 보존한다. Required gate를 retry 0으로 두고 진단 재실행을 분리하면 flaky pass가 merge를 허용하는 문제를 피할 수 있다.
- GitHub artifact attestation은 subject digest 결합을 지원하지만 private repository에서는 공식 문서상 Enterprise Cloud 제약이 있다. 그러므로 계획은 특정 무료 기능을 가정하지 않고 Story 3.8에서 signing/attestation 구현을 선택하되 canonical subject와 서명 검증 계약을 유지한다.
- GitHub environment required reviewer 또한 private repository의 일부 비-Enterprise plan에서 제한될 수 있다. Production 승인을 단일 GitHub 기능에 의존하지 않고, 강제 가능한 승인 체계가 없으면 CD를 fail-closed하는 계획이 필요하다.

## 남은 구현 시 확인사항

- Hosted Ubuntu image와 action commit SHA는 workflow 구현 시점의 지원 버전으로 고정한다.
- Repository plan에서 ruleset, environment approval와 attestation 사용 가능 범위를 확인한다.
- 첫 10회 실행으로 p95와 WebKit 승격 기준을 측정한다.
- OCI registry, workload identity/signing과 durable deployment ledger 구현은 Story 3.8 SPEC에서 위 상위 계약에 맞춰 선택한다.

## 결론

현재 CI 계획은 존재하지 않는 backend/CD를 선행 구현하지 않으면서도 이후 구현자가 지켜야 할 release/CD 불변식을 충분히 고정한다. 공식 플랫폼 제약은 도구 선택의 Deferred로 남겼고, 보안·무결성 요구는 Deferred하지 않았다.
