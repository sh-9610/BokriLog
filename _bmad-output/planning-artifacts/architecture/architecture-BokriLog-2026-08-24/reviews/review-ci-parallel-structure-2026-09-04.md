# CI 병렬 실행 계획 구조 검토

- 검토 일자: 2026-09-04
- 검토 대상: `CI-PLAN.md`, `PORTFOLIO-SOLUTION-DESIGN.md`
- 렌즈: BMad Editorial Structure
- 독자: BokriLog 구현자, 리뷰어, 운영자
- 스타일 기준: Microsoft Writing Style Guide
- 구조 모델: Strategic/Context (Pyramid)
- 단어 수: `CI-PLAN.md` 8,738단어, `PORTFOLIO-SOLUTION-DESIGN.md` 7,171단어
- 판정: **PASS WITH CHANGES**

## 목적과 독자 판단

이 문서는 BokriLog 구현자와 운영자가 현재의 단일 frontend CI에서 Backend 도입 후 frontend/backend 단일 job 병렬 구조로 안전하게 전환하고, 검증된 산출물을 CD에 인계하는 기준을 찾고 실행하도록 돕는다.

## 구조 검토 결과

| Pass | Original Text | Revised Text | Changes |
| --- | --- | --- | --- |
| structure | `CI-PLAN.md` §4 CI-D2: “PR 필수 CI … 전체 timeout 8분”; §6: “frontend job 8분”; §8: “backend job 10분 … same-origin job 5분”; §10·§17: “PR 전체 timeout 8분” | **QUESTION/MERGE:** `PR 피드백 SLO`, `현재 frontend-only hard timeout`, `Backend 도입 후 job별 hard timeout`을 하나의 시간 정책 표로 구분한다. “PR 전체 timeout 8분”이 모든 미래 PR의 hard limit인지 현재 frontend-only 기준인지 명시한다. | Backend 경로는 품질 job 뒤에 same-origin job이 이어지므로 현재 표현만으로는 8분 전체 제한과 10분·5분 job 제한을 동시에 해석할 수 없다. 병렬화 결정 자체는 일관되지만 시간 계약은 수정이 필요하다. 중복 설명을 합치면 약 40~70단어 절감할 수 있다. |
| structure | `CI-PLAN.md` §1 `최종 권장 실행 프로필` 323단어와 §8 `Story 1.2 이후 job 구조` 445단어가 같은 병렬 flowchart와 핵심 결정을 반복함 | **CONDENSE:** §1에는 현재/미래 실행 프로필과 최대 동시 runner 2개 결론을 유지한다. §8에서는 동일 flowchart와 “Backend 즉시 병렬화” 설명을 제거하고 artifact 전달, 변경별 integration 조건, backend 내부 검사 등 구현 상세만 남긴다. | 최종 권장안은 문서 첫머리에서 쉽게 찾을 수 있다. 반복 diagram을 제거해도 이해도는 유지되며 약 70~110단어를 줄일 수 있다. |
| structure | `CI-PLAN.md` §16 `장기 CI-CD 인계 완료`에 “Backend 도입 즉시 frontend/backend 단일 job이 … 병렬 실행”이 포함됨 | **MOVE:** 이 항목을 `첫 CI 구현 완료`의 Backend 전환 완료 조건으로 옮기거나 §1·§8의 결정으로만 남긴다. | Backend job 병렬화는 CI-CD 인계 완료 조건이 아니다. 현재 위치는 단계 경계를 흐리고 동일 결정을 세 번째로 반복한다. 약 20~30단어 절감할 수 있다. |
| structure | `CI-PLAN.md` §12 `Release Gate와 CD 인계 계약` 869단어와 `PORTFOLIO-SOLUTION-DESIGN.md` §8 `CI-CD 인계와 배포 직렬화` 502단어 | **PRESERVE:** CI 문서에는 release evidence schema, Release Gate 판정, registry promotion과 CD 시작 조건을 유지한다. Solution Design에는 durable lease, migration, cutover, observation과 deployment record를 유지한다. 현재의 상호 링크도 보존한다. | 이전보다 문서 경계가 명확하다. 일부 identity 문장은 두 독자의 진입 문맥을 제공하는 유용한 보강이며, 현재 수준에서는 제거할 중복이 아니다. 절감 없음. |
| structure | `CI-PLAN.md` §4 CI-D1 `실행 기반` 490단어 | **CONDENSE:** 본문에는 GitHub-hosted Actions 채택, production VM runner 금지와 재검토 조건만 남긴다. GitHub Actions/Jenkins 비교표는 결정 근거 부록이나 별도 ADR로 이동한다. | 최종 runner 구조보다 도구 선택의 재설명이 길다. 이미 결정된 비교를 축약하면 핵심 실행 정책으로 더 빨리 이동할 수 있고 약 150~220단어를 줄일 수 있다. |
| structure | `CI-PLAN.md` §5 `이벤트와 브랜치 정책` 820단어가 현재 frontend-only 동작과 Backend 이후 classifier 동작을 한 절에 연속 배치함 | **CONDENSE/GROUP:** `현재 frontend-only`와 `Backend 도입 후` 두 소제목으로 나누고, 공통 trigger·concurrency 규칙은 한 번만 제시한다. | 내용은 필요하지만 전환 시점별 규칙을 찾기 어렵다. 재배치 중심이며 약 20~40단어만 줄어든다. |
| structure | `CI-PLAN.md` §1 `최종 권장 실행 프로필` | **PRESERVE:** 목적과 범위 바로 뒤의 현재 위치, 현재 단일 `ci-required`, Backend 도입 즉시 두 단일 job 병렬화, 최대 동시 runner 2개, PR별 concurrency 설명을 유지한다. | 가장 중요한 결론이 전면 배치됐고 8분 기준이 병렬화 시작 조건이 아니라고 명시됐다. 사용자 질문에 대한 직접 답으로 기능한다. 절감 없음. |

## 요약

최종 실행 프로필의 위치와 병렬화 결정은 적절하며, “Backend 도입 즉시 frontend/backend 단일 job을 병렬 실행한다”는 권장안도 본문 전반에서 일관된다. 다만 `PR 전체 timeout 8분`과 Backend·same-origin의 개별 timeout 표현은 하나의 실행 계약으로 동시에 해석하기 어려워 구현 전에 정리해야 한다.

총 7개 권고 중 수정 권고는 5개, 보존 권고는 2개다. 모두 적용하면 `CI-PLAN.md`에서 약 300~470단어(3.4~5.4%)를 줄일 수 있다. 별도 길이 목표는 없다. Diagram과 전면 요약은 유지하므로 예상되는 이해도 손실은 없으며, 중복 diagram 하나와 잘못 배치된 완료 조건만 제거한다.
