# CI 병렬 실행 계획 문장·일관성 검토

- 검토 대상: `CI-PLAN.md`
- 검토 방식: BMad `prose` lens, Microsoft Writing Style Guide, 사람 독자 기준
- 문서 규모: 8,738단어
- 판정: **PASS WITH CHANGES**

이 문서는 BokriLog 구현자와 운영자가 현재 단일 job에서 향후 frontend/backend 병렬 CI와 Release Gate까지 일관되게 구현하도록 돕기 위해 존재한다.

현재 권장안의 핵심 방향에는 문제가 없다. 다만 현재 단계와 Backend 도입 이후 단계의 시간 제한, 변경 분류, WebKit 적용 시점에 서로 다르게 읽히는 문장이 있어 구현 SPEC을 작성하기 전에 정리해야 한다.

## 상위 발견

1. PR 전체 timeout 8분과 향후 backend job timeout 10분이 동시에 적용되는 것처럼 적혀 있어 실행 계약이 성립하지 않는다.
2. 현재 frontend-only 단계는 모든 검사를 실행한다고 하면서 문서-only 변경은 제품 job을 `not_applicable`로 둔다고도 적혀 있다.
3. Backend 도입 즉시 두 job을 병렬 실행한다는 표현이 경로 기반 skip 정책과 결합되면 모든 PR에서 양쪽 job을 실행하는지 불명확하다.
4. 초기 CI는 Chromium만 사용하지만 `main` PR과 Release Gate에는 WebKit을 즉시 요구하므로, Release Gate 활성화 시점이 명확하지 않다.

## Editorial findings

| Pass | Original Text | Revised Text | Changes |
| --- | --- | --- | --- |
| prose | §4 CI-D2·§8·§17 — “PR 필수 CI … 전체 timeout 8분”, “시작 timeout은 backend job 10분”, “PR p95 5분 또는 전체 timeout 8분” | “현재 frontend-only PR은 job timeout 8분을 적용한다. Backend 도입 후 PR은 `frontend-quality` 8분, `backend-quality` 10분, `same-origin-integration` 5분의 job별 timeout을 적용하고, workflow 전체 timeout과 p95 목표는 첫 10회 측정 후 별도로 확정한다.” | 현재와 미래의 timeout을 구분한다. 지금 문장대로면 10분 backend job이 8분 전체 제한 안에서 완료돼야 하는 모순이 생긴다. |
| prose | §5 — “현재 frontend-only 단계는 전 검사를 실행” / 변경 분류 표 — “문서만 … 제품 job은 `not_applicable`” | “현재 frontend-only 단계에서는 문서-only 변경도 단일 `ci-required` job을 시작하되, job 내부 분류로 문서 검증만 실행할지 모든 frontend 검사를 실행할지 foundation SPEC에서 하나로 고정한다. Backend 도입 후에는 별도 classifier job이 변경 분류를 소유한다.” | 현재 단계에서 문서-only PR이 전체 검사를 실행하는지 생략하는지 상반된 지침을 제거한다. 별도 classifier **job**을 만들지 않는 것과 분류 자체를 하지 않는 것도 구분한다. |
| prose | §1·§8·§16 — “Backend가 생기는 Story 1.2부터는 … 서로 즉시 병렬 실행한다.” | “Backend가 도입된 뒤 frontend와 backend 검사가 모두 필요한 PR에서는 두 단일 job을 즉시 병렬 실행한다. 한쪽만 관련된 변경은 반대쪽 job을 승인된 `not_applicable`로 처리한다.” | 병렬 실행 기본값과 경로 기반 skip 정책을 한 문장으로 연결한다. 기존 표현은 모든 Backend-era PR이 항상 runner 2개를 쓰는 것으로 읽힐 수 있다. |
| prose | §1 — “PR 한 건의 품질 검사에 필요한 최대 동시 hosted runner는 2개다.” | “WebKit을 별도 job으로 분리하기 전의 기본 PR 경로에서는 동시에 최대 GitHub-hosted runner 2개를 사용한다.” | WebKit 별도 job이나 향후 matrix가 추가될 때도 항상 2개라는 오해를 막고 적용 범위를 명시한다. |
| prose | §1 — “다른 PR은 별도 concurrency group에서 독립적으로 실행할 수 있다.” | “다른 PR은 별도 concurrency group을 사용하므로 서로 취소하지 않으며, 계정의 동시 실행 한도 안에서 병렬로 실행할 수 있다.” | concurrency group이 runner 용량이나 queue 대기를 보장한다는 인상을 제거한다. |
| prose | §7 — “초기 CI에서는 Chromium만 required로 둔다.” / 표 — “PR → `main`: WebKit 필수”, “Release Gate: WebKit 필수” | “초기 CI에서는 Chromium만 필수로 둔다. WebKit 필수 검사가 준비되기 전에는 production Release Gate를 활성화하지 않는다. 또는 첫 Release Gate 구현 범위에 WebKit 설치와 검증을 포함한다.” | 두 선택지 중 하나를 구현 SPEC에서 확정하도록 한다. 현재 문서는 첫 CI 완료 조건과 `main` 필수 조건 사이의 전환 시점을 설명하지 않는다. |
| prose | §4 CI-D5 — “Aggregator는 성공 직전에 실행 SHA가 여전히 해당 PR의 최신 head인지 확인하며 read-only 권한과 1분 timeout만 가진다.” | “Aggregator는 `pull-requests: read`를 포함한 최소 읽기 권한으로 PR의 현재 head SHA를 조회하고, 현재 workflow SHA와 비교한다. 조회 실패나 불일치는 실패로 판정하며 timeout은 1분이다.” | `read-only`만으로는 필요한 GitHub 권한과 실패 동작을 구현자가 판단하기 어렵다. 실제 조회 방식을 foundation SPEC에서 달리 정한다면 이 문장도 함께 갱신해야 한다. |
| prose | §8 — “`same-origin-integration`은 관련 변경일 때만 두 품질 job의 현재 실행 산출물을 받아 실행” / “Frontend-only와 backend-only 변경은 반대편을 … `not_applicable`” | “Same-origin 검사가 필요한 변경에서는 frontend와 backend job을 모두 실행해 현재 run의 입력 artifact를 생성한다. 검사가 필요하지 않은 단일 영역 변경에서는 반대편 job과 same-origin job을 `not_applicable`로 처리한다.” | 조건부 integration이 어떤 상황에서 양쪽 job을 강제로 실행하는지 한곳에서 분명히 한다. |
| prose | §12 — “`develop` 통합 CI가 실패하면 main promotion과 Release Gate를 막고 즉시 revert 또는 fix owner를 지정한다.” | “Release Gate는 승격 대상 commit이 통과해야 하는 `develop` 통합 상태와 그 증거를 foundation SPEC에 정의된 방식으로 확인한다. 실패하면 Release Gate를 중단하고, 담당자와 revert 또는 수정 결정을 추적 가능한 기록에 남긴다.” | 서로 다른 workflow 사이에서 무엇을 확인해 차단하는지와 `즉시`, `owner 지정`의 기록 위치를 명확히 한다. |
| prose | §3 — “Story 1.1은 review이고 Story 1.2 이후 제품·운영 기능은 backlog다.” | “Story 1.1은 검토 중이며, Story 1.2 이후의 제품·운영 기능은 백로그 상태다.” | 상태 설명을 자연스러운 한국어로 바꾼다. |
| prose | §3 — “첫 hosted Ubuntu 실행이 제한 시간 안에 정상 종료되는 것을 branch protection보다 먼저 확인한다.” | “브랜치 보호 규칙을 적용하기 전에 GitHub-hosted Ubuntu에서 프로세스가 제한 시간 안에 정상 종료되는지 확인한다.” | 확인 대상과 선후 관계를 직접적으로 표현한다. |
| prose | §6 — “CI에서 explicit build와 함께 쓰면 build가 중복된다.” | “CI에서 명시적인 build 단계를 함께 실행하면 동일한 build가 중복된다.” | 불필요한 영문 혼용을 줄이고 중복 대상을 명확히 한다. |
| prose | §6 — “첫 CI foundation은 존재하는 명령만 자동화한다.” | “초기 CI는 현재 존재하는 명령만 자동화한다.” | 문서에서 이미 사용하는 ‘초기 CI’와 용어를 통일한다. |
| prose | §9 — “부하 시험은 stub 또는 BokriLog 소유 endpoint만 대상으로 한다.” | “부하 테스트는 stub 또는 BokriLog가 소유한 endpoint만 대상으로 실행한다.” | 어색한 소유 표현을 바로잡는다. |
| prose | §11 — “`npm audit` 같은 시간가변 외부 판정” | “`npm audit`처럼 결과가 시간에 따라 바뀌는 외부 검사” | 생소한 조어를 평이한 표현으로 바꾼다. |
| prose | §13 — “최신 base와의 통합 여부를 확인한 merge 결과 검증” | “최신 대상 브랜치와 통합한 merge 결과 검증” | `base`가 가리키는 브랜치를 명시한다. |
| prose | §14 — “green lint만으로 승인하지 않고” | “lint 통과만으로 승인하지 않고” | 색상 은유를 직접적인 상태 표현으로 바꾼다. |
| prose | §16 — “실제 성공 뒤에만 deferred CI 항목을 해소로 기록한다.” | “검사가 실제로 성공한 뒤에만 해당 deferred CI 항목을 해결된 것으로 기록한다.” | 어색한 조사와 생략된 대상을 보완한다. |

## 스타일·보존 메모

기술 식별자와 CI/CD 계약 용어는 유지하되, 한국어 설명을 중심으로 한 단정적이고 계약 지향적인 문체를 보존하는 것이 적절하다.

## 최종 판단

Frontend와 Backend를 각각 단일 job으로 유지하고, 둘 다 필요한 PR에서 즉시 병렬 실행한다는 최종 권장안은 타당하다. 위 상위 네 항목을 구현 SPEC 전에 정리하면 runner 수, CI 대기시간, 조건부 실행에 대한 해석 차이 없이 진행할 수 있다.
