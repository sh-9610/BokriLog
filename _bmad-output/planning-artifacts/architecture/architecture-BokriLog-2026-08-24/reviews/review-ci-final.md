# CI 계획 최종 재검증 — 2026-09-04

- **검토 대상:** `CI-PLAN.md`
- **입력:** rubric, adversarial, current-reality, structure, prose 검토
- **최종 판정:** **PASS**

## Reviewer gate 해소

| 초기 지적 | 최종 반영 |
| --- | --- |
| Release evidence와 실제 실행 artifact의 결합 부족 | canonical ARM64 OCI subject, 동일 image migration digest, subject-bound attestation, canonical evidence digest와 workload signature를 고정했다. |
| 동시·stale production 배포 위험 | environment durable lease/fencing, non-cancellation, predecessor CAS, descendant 전진 배포와 deployment ID 멱등성을 고정했다. |
| Required check가 skip/cancel을 숨길 수 있음 | 고정 `ci-required`, event/change-class 기대 목록, success-only truth table과 제한된 `not_applicable`을 정의했다. |
| CI가 실제 rollback predecessor를 결정함 | CI는 tested predecessor만 기록하고 CD가 실제 active container와 append-only record에서 predecessor를 확정하도록 분리했다. |
| Migration timeout·부분 실패·중복 실행 모호성 | DB-authoritative lock/history, exact digest와 from/to, unknown 결과 재확인, nontransactional recovery와 startup fail-closed를 추가했다. |
| Registry promotion·보존·권한 모호성 | digest-preserving copy, 대상 재검증, tag 입력 금지, write/deploy principal 분리와 rollback retention을 고정했다. |
| Playwright retry가 flaky를 green으로 만들 수 있음 | required retry 0과 `retain-on-failure`, 별도 진단 재실행 및 release rerun 감사 규칙을 적용했다. |
| Production 완료 증거 부족 | append-only deployment record와 `APPROVED`부터 `VERIFIED`까지의 상태, versioned observation policy를 정의했다. |
| lint/style/format 및 WebKit 도입 시점 모호성 | Story 1.2 전 required 승격과 WebKit 10회 clean-run/p95 기준, main release 전 필수를 정했다. |
| 보안 검사 판정 미정 | committed secret·Critical 차단, 만료되는 High waiver와 Story별 도구 선택 시점을 정했다. |

## Structure 검토

- 목적, 현재 기준선, 결정, 실행 경로, 확장 시점, 보안, CD 인계, 완료 조건과 재검토 순서가 독립 구현에 필요한 흐름을 이룬다.
- 현재 실행 항목과 미래 Story gate를 표로 분리했고 BMAD/구현 문서의 소유권을 명시했다.
- 가장 긴 Release/CD 절은 release identity → 검증 → promotion → predecessor → migration → production state 순서로 읽히며 상위 AD-12 계약과 충돌하지 않는다.

## Prose 검토

- GitHub Actions와 Jenkins 비교가 현재 팀·운영 부담·OCI 호환성의 이유로 직접 연결된다.
- “필수”, “advisory”, “not_applicable”, “release 완료”의 판정 기준이 수치 또는 상태로 정의됐다.
- 아직 선택할 수 없는 registry, signing provider와 action SHA만 Story 3.8로 미뤘고 보안 불변식 자체는 미루지 않았다.

## 잔여 위험

- Windows E2E 종료 문제는 첫 hosted Ubuntu workflow 실행에서 확인해야 한다.
- GitHub private repository의 ruleset, environment reviewer, native attestation 제공 범위는 실제 plan에 따라 달라진다. 강제 수단이 없으면 Production CD는 fail-closed한다.
- 이 검토는 계획 문서의 준비도를 승인한다. Workflow, branch protection과 OCI 설정이 구현됐다는 의미는 아니다.
