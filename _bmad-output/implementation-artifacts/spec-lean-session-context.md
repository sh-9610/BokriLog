---
title: 개발 세션 시작 입력 축소
type: chore
created: 2026-11-21
status: done
baseline_commit: 58fce0c5f3c09caaf07f2cb59ebff7e6a75595d3
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="사용자 소유 의도">

## Intent

개발자가 작업을 시작할 때 공통 규칙, 해당 영역의 계약, 현재 작업 상태를 적은 입력으로 확인할 수 있게 한다. 기본 문서에서 과거 경위와 영역별 계약을 분리하고 읽기 전용 명령으로 상태 확인을 모은다.

## Boundaries & Constraints

- 계약 원문과 정본 우선순위, SPEC 승인, 리뷰, 테스트, CI 판정, 브랜치·커밋 정책을 보존한다.
- 상태는 현재 체크아웃에서 다시 조회한다. 저장소·GitHub 상태를 변경하지 않는다.
- 주간 한도 효과는 실제 사용 뒤 평가한다. 이번 범위는 문서와 상태 조회이며 테스트 출력·one-shot 정책은 별도다.

## I/O & Edge-Case Matrix

| 상황 | 기대 동작 |
|---|---|
| 인증과 계정 삭제에 걸친 변경 | 안내에서 두 계약을 찾고 둘 다 SPEC context에 포함한다. |
| 세션 시작 | 브랜치·작업 트리·최근 커밋, develop push CI 최근 3건, 진행·리뷰 스토리와 SPEC 상태, 열린 액션 ID·설명을 출력한다. |
| CI 비정상 또는 진행 중 | 실행별 상태와 판정 원장 기록 유무를 구분한다. |
| GitHub 조회 오류 또는 SPEC 누락·모호함 | 확인할 수 없는 항목을 명시하고 확인 가능한 로컬 결과를 제공한다. |
| 진행 스토리 없음 | 정상 빈 결과로 표시하고 open·in-progress 액션은 계속 출력한다. |

</frozen-after-approval>

## Code Map

- CLAUDE.md의 현재 진행 지점, 스토리가 고정한 계약을 기준으로 영역별 읽기 경로를 만든다. AGENTS.md의 위임과 기존 문서 참조가 계속 유효해야 한다.
- 개발 절차의 명령은 DEVELOPMENT.md가 소유한다. scripts/에 상태 조회 진입점을 두고 각 원장은 원래 위치에서 읽는다.
- sprint-status.yaml의 development_status와 action_items, spec 파일의 frontmatter, ci-run-verdicts.md가 입력이다. 멀티라인 액션 설명 전체를 보존한다.
- epic-1-retro-item-8(과정)은 미룸 판정 절차를 보존하며 이번 변경으로 닫지 않는다. epic-3-retro-item-31(과정)은 셸 규약 검사의 기존 범위를 보존한다. epic-3-retro-item-38(과정)의 SPEC 크기 선택 기록 정책은 그대로 둔다.

## Verification

- 기준 리비전 58fce0c의 CLAUDE.md 127,838바이트와 변경 뒤 크기를 비교한다. 이동한 계약의 원문 대조와 링크 해석을 확인한다.
- 인증·금융 계산·계정 삭제·배포 및 다중 영역 예시에서 안내 → 계약 파일 → SPEC context 전달을 점검한다.
- 실제 로컬 상태와 직접 조회한 결과의 항목별 일치 여부를 확인한다. 임시 입력으로 진행 스토리, SPEC 누락, CI 비정상·조회 실패를 재현한다.
- 사용자 체크아웃과 자격정보를 건드리지 않고 변경 목록과 git diff --check를 검증한다.

## 검증 결과

- 공통 지침 127,838 → 14,634바이트. 영역별 계약 원문과 작성 시점에 고정한 기존 SPEC 링크 20개를 대조했다.
- 실제 체크아웃의 Git 상태·커밋, 진행 스토리 0건, 열린 액션 17건의 설명, origin develop CI 3건을 직접 조회 결과와 대조했다.
- 상태 조회 회귀 시험 5개가 통과했다. 임시 입력으로 SPEC 누락·모호함, CI 시도 번호 구분 및 GitHub 조회 실패를 확인했다.

## Spec Change Log

- 독립 리뷰 뒤 조회 대상을 origin으로 고정하고 원장 부분 오류, 미추적 한글 경로, 오프라인 확인 절차를 보강했다. 계약 연결과 회귀 시험을 다시 확인했다.

## Suggested Review Order

1. [공통 지침과 계약 읽기 경로](../../CLAUDE.md#스토리가-고정한-계약)
2. [상태 조회 명령](../../DEVELOPMENT.md#세션-상태-조회)
3. [조회 구현](../../scripts/session-status.py) · [회귀 시험](../../scripts/test_session_status.py)
