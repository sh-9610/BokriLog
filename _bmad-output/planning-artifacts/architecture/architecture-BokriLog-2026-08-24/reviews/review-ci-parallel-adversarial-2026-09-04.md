---
name: 'CI Parallel Runtime Final Review'
type: review
date: '2026-09-04'
source: '../CI-PLAN.md'
source_sha256: '3FA2A2C68B82E30C34CA437DF5490EC06E81F781083B3AB5A07176B4A301FC09'
lenses:
  - adversarial
  - edge-case-hunter
verdict: 'PASS WITH CHANGES'
---

# CI 병렬 실행 계획 최종 검증

## 판정

**PASS WITH CHANGES**

Backend 도입 즉시 `frontend-quality`와 `backend-quality`를 각각 단일 job으로 병렬 실행하고, 관련 변경에만 `same-origin-integration`을 이어 실행하며, 마지막 `ci-required`가 판정하는 기본 구조는 진행 가능하다. 서로 다른 PR도 독립된 concurrency group으로 병렬 실행할 수 있어 개발 대기열을 불필요하게 직렬화하지 않는다.

다만 현재 문서에는 시간 예산 충돌, PR commit identity 혼용, `not_applicable` 표현 방식, PR이 classifier 자체를 바꾸는 경우의 신뢰 경계가 남아 있다. 아래 항목을 후속 `spec-ci-foundation.md`에서 닫기 전에는 workflow 구현 완료로 판정하면 안 된다.

검토 대상은 위 SHA-256의 `CI-PLAN.md`이며, 이 검토는 계획 문서를 수정하지 않았다.

## Adversarial findings

### A1

- `lens`: `adversarial`
- `location`: §4 CI-D2, §8 Story 1.2 이후 job 구조, §10 속도와 재현성 정책
- `trigger_condition`: PR 전체 timeout은 8분인데 backend job 10분 뒤 integration 5분이 허용된다.
- `guard_snippet`: frontend-only, backend-only, cross-boundary PR별 wall-clock 목표와 job/step timeout을 별도로 정의한다.
- `potential_consequence`: 정상적인 cross-boundary PR도 계획상 시간 제한을 만족할 수 없거나 중간에 종료된다.

### A2

- `lens`: `adversarial`
- `location`: §4 CI-D5, §8 artifact handoff
- `trigger_condition`: 문서는 PR merge commit SHA를 checkout하면서 최신 head SHA와 실행 SHA를 직접 비교한다.
- `guard_snippet`: `head_sha`, `base_sha`, synthetic `merge_sha`를 별도 필드로 기록하고 각 역할에 맞게 검증한다.
- `potential_consequence`: 정상 실행이 항상 stale로 판정되거나 오래된 base와의 merge 결과가 승인된다.

### A3

- `lens`: `adversarial`
- `location`: §4 CI-D5, §5 변경 분류
- `trigger_condition`: GitHub job result에는 문서가 사용하는 `not_applicable` 상태가 기본 제공되지 않는다.
- `guard_snippet`: classifier가 승인 사유 enum을 출력하고 job은 성공 evidence를 남기거나 aggregator가 `skipped`와 사유를 함께 검증한다.
- `potential_consequence`: 의도한 생략이 실패하거나 실제 누락이 승인된 생략으로 오인된다.

### A4

- `lens`: `adversarial`
- `location`: §5 변경 분류, §11 보안과 공급망 정책
- `trigger_condition`: PR이 repository 안의 classifier 코드도 수정하면 변경된 로직이 자기 검사를 축소할 수 있다.
- `guard_snippet`: classifier는 base/default branch의 승인된 정의를 사용하고 classifier 변경 감지는 그 실행보다 앞선 고정 단계에서 처리한다.
- `potential_consequence`: 악의적이거나 잘못된 classifier 변경이 필요한 보안·제품 검사를 우회한다.

### A5

- `lens`: `adversarial`
- `location`: §5 현재 frontend-only 단계, §6 현재 단계, §14 BMAD 검증
- `trigger_condition`: 현재는 classifier가 없는데 문서-only와 BMAD-only 변경의 선택 실행 정책도 함께 선언한다.
- `guard_snippet`: 초기 단일 job은 모든 PR에서 frontend와 필요한 BMAD/문서 검사를 실행한다고 명시하거나 최소 classifier를 도입한다.
- `potential_consequence`: 구현자가 문서 표를 따라 검사를 생략하거나 반대로 모든 문서 PR에 browser 비용을 지불한다.

### A6

- `lens`: `adversarial`
- `location`: §6 실행 순서, §11 committed secret 정책, §16 첫 CI 완료 조건
- `trigger_condition`: 모든 PR 필수인 committed-secret 검사가 초기 단일 job 순서와 완료 조건에 없다.
- `guard_snippet`: 첫 `ci-required` 실행 순서와 완료 조건에 고정된 secret scan 단계를 추가한다.
- `potential_consequence`: 정책상 차단 대상인 committed secret이 초기 CI를 통과한다.

### A7

- `lens`: `adversarial`
- `location`: §5 PR → main, §7 main browser 정책, §12 main PR 역할
- `trigger_condition`: main PR의 `release validation` 구성, 의존관계와 runner/time budget이 정의되지 않았다.
- `guard_snippet`: main PR validation의 정확한 suite, `needs`, 최대 동시 runner, timeout과 금지된 side effect를 명시한다.
- `potential_consequence`: main PR에서 제품 시험이 중복되거나 예상보다 많은 runner와 시간이 소비된다.

### A8

- `lens`: `adversarial`
- `location`: §12 develop 실패 차단, §13 main 보호
- `trigger_condition`: 실패한 develop이 main promotion을 막는다는 선언을 강제할 ref·ancestry·status 검증이 없다.
- `guard_snippet`: release/hotfix 정책별 허용 ancestry와 최신 develop 통합 check를 main PR aggregator에서 검증한다.
- `potential_consequence`: 실패한 develop 상태를 우회한 main release candidate가 생성된다.

### A9

- `lens`: `adversarial`
- `location`: §8 run-scoped artifact handoff
- `trigger_condition`: producer 시험 성공 뒤 artifact upload 실패를 producer 실패로 강제하는 계약이 없다.
- `guard_snippet`: artifact upload와 digest metadata 생성을 producer success 조건에 포함하고 누락 시 job을 실패시킨다.
- `potential_consequence`: 품질 job은 성공하지만 integration 입력이 없어 뒤늦고 모호한 실패가 발생한다.

### A10

- `lens`: `adversarial`
- `location`: §6 timeout과 진단 artifact
- `trigger_condition`: GitHub job-level timeout은 후속 진단 upload step까지 종료할 수 있다.
- `guard_snippet`: 시험 명령에 더 짧은 내부 deadline을 두고 job timeout 전에 자식 종료와 upload 여유를 확보한다.
- `potential_consequence`: 가장 필요한 hang 상황에서 trace와 screenshot을 남기지 못한다.

### A11

- `lens`: `adversarial`
- `location`: §8 PR artifact, §10 보존 정책
- `trigger_condition`: 성공한 integration 입력 artifact의 retention과 정리 시점이 정의되지 않았다.
- `guard_snippet`: PR artifact는 현재 workflow 완료까지만 필요한 최소 retention을 적용하고 superseded run artifact 정리 정책을 둔다.
- `potential_consequence`: 병렬화를 위해 생성한 artifact가 저장공간과 Actions 비용을 불필요하게 누적한다.

### A12

- `lens`: `adversarial`
- `location`: §12 candidate와 evidence 보존
- `trigger_condition`: rollback predecessor는 보존하지만 승인 대기 중인 candidate evidence의 만료 정책은 없다.
- `guard_snippet`: candidate 승인 유효기간까지 image, signature와 attestations를 함께 보존하고 만료 후 promotion을 금지한다.
- `potential_consequence`: 운영 승인 시점에 검증 대상 일부가 삭제되거나 서로 다른 candidate가 선택된다.

### A13

- `lens`: `adversarial`
- `location`: §4 CI-D5
- `trigger_condition`: 1분 aggregator가 최신 PR head를 조회할 API 권한과 API 실패 판정을 정의하지 않는다.
- `guard_snippet`: 최소 `pull-requests: read` 필요성을 검증하고 API timeout·rate-limit·오류는 fail-closed 처리한다.
- `potential_consequence`: aggregator가 권한 오류로 반복 실패하거나 stale 검증을 조용히 건너뛴다.

### A14

- `lens`: `adversarial`
- `location`: §10 비용 측정, §17 재검토 조건
- `trigger_condition`: billed runner-minute은 측정하지만 월 예산과 조치 임계값이 없다.
- `guard_snippet`: private repository 월간 minute 예산, 경고 임계값과 초과 시 조치 순서를 foundation SPEC에 고정한다.
- `potential_consequence`: 병렬 PR 증가가 quota 고갈 뒤에야 발견되어 CI queue가 갑자기 멈춘다.

## Edge-case-hunter findings

```json
[
  {
    "location": "CI-PLAN.md:§4 CI-D2, §8, §10",
    "trigger_condition": "backend와 integration이 모두 실행되어 8분 전체 timeout을 초과한다",
    "guard_snippet": "budget = profile_timeout(change_class); assert(job_deadlines_fit(budget))",
    "potential_consequence": "정상적인 경계 변경 PR이 완료되기 전에 강제 종료된다"
  },
  {
    "location": "CI-PLAN.md:§4 CI-D5, §8",
    "trigger_condition": "PR head는 같지만 target branch가 실행 중 변경된다",
    "guard_snippet": "assert(head_sha == current_head && base_sha == current_base && merge_sha_is_current)",
    "potential_consequence": "오래된 base로 검증한 merge 결과가 최신으로 승인된다"
  },
  {
    "location": "CI-PLAN.md:§4 CI-D5, §5",
    "trigger_condition": "classifier output이 비어있거나 허용 enum 밖의 값을 반환한다",
    "guard_snippet": "invalid_or_empty(classification) => expected_jobs = full-required",
    "potential_consequence": "aggregator가 어떤 job을 기대해야 하는지 결정하지 못한다"
  },
  {
    "location": "CI-PLAN.md:§4 CI-D5, §5",
    "trigger_condition": "관련 없는 job이 GitHub의 skipped 상태로 끝난다",
    "guard_snippet": "skipped_allowed = classifier_reason in approved_not_applicable_enum",
    "potential_consequence": "승인된 생략과 예상 밖 미실행을 구분하지 못한다"
  },
  {
    "location": "CI-PLAN.md:§5, §8",
    "trigger_condition": "PR이 classifier 구현과 제품 코드를 함께 수정한다",
    "guard_snippet": "classifier_changed => evaluate_with_base_classifier_and_run(full-required)",
    "potential_consequence": "변경된 분류기가 자기 PR의 필수 검사를 축소한다"
  },
  {
    "location": "CI-PLAN.md:§6",
    "trigger_condition": "Playwright가 내부 deadline 없이 job timeout까지 멈춘다",
    "guard_snippet": "test_deadline < job_timeout - diagnostic_upload_budget",
    "potential_consequence": "job 종료와 함께 실패 진단 업로드도 중단된다"
  },
  {
    "location": "CI-PLAN.md:§8",
    "trigger_condition": "producer 시험은 성공하지만 artifact upload가 실패한다",
    "guard_snippet": "producer_success = tests_success && artifact_uploaded && digest_emitted",
    "potential_consequence": "integration이 입력 누락으로 실행되지 않거나 늦게 실패한다"
  },
  {
    "location": "CI-PLAN.md:§5, §7, §12",
    "trigger_condition": "main PR release validation이 품질 job과 동시에 실행된다",
    "guard_snippet": "define(main_pr_dag, max_parallel, timeout, validation_suite)",
    "potential_consequence": "PR 최대 runner와 시간 예산이 계획보다 커진다"
  },
  {
    "location": "CI-PLAN.md:§11, §6",
    "trigger_condition": "초기 frontend-only PR에 committed secret이 포함된다",
    "guard_snippet": "ci-required.steps.prepend(committed_secret_scan)",
    "potential_consequence": "명시된 보안 차단 정책이 실제 첫 CI에서 실행되지 않는다"
  },
  {
    "location": "CI-PLAN.md:§12",
    "trigger_condition": "candidate가 생성된 뒤 운영 승인이 장기간 지연된다",
    "guard_snippet": "approval_deadline <= candidate_retention; reject_expired_candidate()",
    "potential_consequence": "CD 시작 전에 evidence나 attestation 일부가 만료된다"
  },
  {
    "location": "CI-PLAN.md:§12, §13",
    "trigger_condition": "develop 통합 실패 후 hotfix PR이 main으로 열린다",
    "guard_snippet": "main_policy(hotfix) => explicit_exception_approval_and_post_merge_backport",
    "potential_consequence": "develop 차단 규칙과 긴급 수정 경로가 서로 교착된다"
  },
  {
    "location": "CI-PLAN.md:§4 CI-D5",
    "trigger_condition": "최신 head 조회가 rate-limit이나 일시적 API 오류로 실패한다",
    "guard_snippet": "head_lookup_error => ci-required_failure",
    "potential_consequence": "stale 확인이 생략되거나 정상 PR이 비결정적으로 승인된다"
  }
]
```

## 진행 조건

권장 구조 자체를 바꿀 필요는 없다. 구현 SPEC에서 최소한 다음 계약을 명시하면 진행 가능하다.

1. 변경 유형별 전체 wall-clock과 job/step timeout을 서로 모순 없이 정한다.
2. PR의 `head_sha`, `base_sha`, synthetic `merge_sha`를 분리해 기록하고 검증한다.
3. `not_applicable`을 GitHub의 실제 `skipped` 결과와 안전하게 매핑한다.
4. classifier self-change는 base branch의 승인된 분류기로 fail-closed 처리한다.
5. 초기 단일 job에 committed-secret 검사와 BMAD/문서 경로 정책을 반영한다.
6. main PR release validation의 DAG, 최대 runner와 시간 예산을 별도로 고정한다.
7. 내부 step deadline으로 진단 업로드 시간을 확보하고 artifact upload 실패를 producer 실패로 처리한다.
8. PR artifact와 승인 대기 candidate의 retention·만료 규칙을 정한다.
