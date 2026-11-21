---
title: '수동 조회에서 Google 전면 광고 시도'
type: 'feature'
created: '2026-11-22'
status: 'done'
baseline_commit: 'ca4f0046872a399463d7ca7860ee39de9cae33c7'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/context/advertising.md'
  - '{project-root}/_bmad-output/implementation-artifacts/context/deployment.md'
---

<frozen-after-approval reason="사용자가 승인하는 구현 계약">

## Intent

**문제** 현재 동기화가 성공한 뒤에 광고 동의와 앱 빈도를 검사하고 결과 화면에서 추가 클릭을 요구한다.

**변경안** 대시보드의 기존 `동기화` 조작 한 번으로 Google 전면 광고를 시도하고 수동 동기화를 시작한다. 사용자가 승인한 변경에 따라 Google 태그를 대시보드에서 직접 준비한다. BokriLog 광고 동의와 앱의 일 3회·30분 제한을 제거한다.

## Boundaries & Constraints

- 광고 공급자가 활성인 현재 대시보드에서만 동기화용 광고를 준비한다. Google 광고 요청의 설정·주소·타기팅에 금융값이나 계정 식별자를 싣지 않는다. 실제 광고는 출시 검증 전 기본 비활성 상태로 둔다. 기존 금융정보 화면의 보안 헤더 중 Google 태그에 필요한 CSP 지시자만 좁게 확장한다.
- 동기화 API의 세션·CSRF·멱등성과 30초 재요청 대기, 광고 운영 중단과 공급자 비활성 설정을 보존한다. 기존 동의 기록은 계정 삭제·복원 계약에 따라 관리한다.
- 광고는 출시 검증까지 기본 비활성 상태를 유지한다. 실제 Google 광고 단위에서 제한 광고 게재 가능 여부와 1분 빈도 설정을 확인한다.
- 실제 소재 검증에서 화면이나 광고 모드의 요건을 충족할 수 없으면 운영 활성화와 대체 UX 결정을 보류한다.

## I/O & Edge-Case Matrix

| 조건 | 입력 | 기대 동작 |
| --- | --- | --- |
| 대시보드에서 광고 준비 완료 | `조회` 활성화 | 실제 링크 클릭이 Google 광고를 시도하고 기존 CSRF 보호 POST로 수동 작업을 최대 한 건 접수한다. |
| 운영 광고 중단, 미채움·차단 또는 시간 초과 | `조회` 활성화 | 광고 없이 기존 수동 동기화 흐름으로 이어진다. |
| 직접 URL 방문, 새로고침, 뒤로 가기, 연속 클릭 | 이동 또는 요청 반복 | 사용자 클릭과 결부된 수동 요청만 접수하고 같은 요청을 중복 실행하지 않는다. |
| 자동 동기화 또는 단순 결과 열기 | 대시보드 진입·복귀 | 광고를 시작하지 않는다. |

</frozen-after-approval>

## Code Map

- `frontend/src/session/useDashboardSync.ts` — 수동 POST와 작업 상태 추적을 재사용하고 완료 뒤 광고 자격 요청을 제거한다.
- `frontend/src/screens/{SettingsScreen,AdTransitionScreen}.tsx`, `frontend/src/ads/gptAdapter.ts` — 동의 UI 제거, 대시보드에서 GPT 슬롯을 준비하고 기존 완료 후 광고 이동을 중단한다.
- `backend/src/main/java/com/bokrilog/engagement/internal/{AdGate,AdEligibilityService,AdTransitionService}.java` — 사전 동의와 빈도 판정을 제거하고 광고 활성 및 운영 제한 판정을 재사용한다.
- `deploy/nginx/bokrilog.conf` — 현재 대시보드에 필요한 Google CSP 예외를 추가하고 다른 업무 경로는 그대로 둔다.
- 열린 액션 Epic 3 항목 39(과정)는 시험용 식별자에 적용하며 공용 도우미 개발은 범위 밖이다. G-4 및 미해결 항목 148·152·153·154는 게재와 기록 검토에서 확인한다.

## Tasks & Acceptance

- [x] 설정 동의 UI와 신규 동의 경로를 정리하고 기존 계정 삭제·복원 시험을 확인한다.
- [x] 광고 공급 설정을 대시보드 준비 시점에 인증된 동일 출처 조회로 제공한다. 운영 중단과 공급자 비활성은 유지한다.
- [x] 기존 동기화 버튼 자리에서 준비된 경우에만 일반 앵커를 사용해 GPT가 클릭을 감지하도록 한다. POST가 문서 이동에 의해 취소되지 않도록 하고, 준비 실패·차단 시 기존 버튼 동작을 제공한다. 다른 링크나 수동 조회 외 트리거를 제외한다.
- [x] 반복 조회, 광고 실패, 뒤로 가기, 자동 동기화, 서버 제한, CSP 시험을 갱신하고 광고 계약 변경을 기록한다.

- Given 광고가 활성인 환경에서, when 동의 기록이 없는 이용자가 30분 이내 또는 하루 네 번째 이후에도 수동 조회를 하면, then 앱의 광고 동의나 일일·간격 제한 때문에 광고 시도가 차단되지 않는다.
- Given 운영자가 광고를 중단했을 때, when 이용자가 조회하면, then 광고 요청 없이 기존 수동 동기화가 가능하다.

## Spec Change Log

- 2026-11-22 사용자 결정으로 추가 안내 화면을 제거하고 기존 대시보드에서 광고를 준비하도록 변경했다.
- 리뷰 후 효과 정리와 광고 준비 상태를 묶고, 광고 복귀의 일시적 조회 실패 재시도와 준비 이벤트를 보강했다. 제한 광고용 스크립트와 설정 API의 배포 연결을 회귀시험에 추가했다.

## Design Notes

- 승인된 대시보드 경계 변경은 동결된 기존 계획 산출물의 AD-18에 대한 후속 결정이다. 현재 브라우저 경계는 `context/frontend.md`, 현행 광고 동작과 이전 구현 이력은 `context/advertising.md`에서 확인한다.
- 광고 준비 실패와 운영 제한은 기존 동기화 버튼으로 이어진다. 광고 슬롯이 준비됐을 때만 링크 클릭을 Google에 전달하고, 수동 POST는 광고 표시 결과를 기다리지 않는다.
- 광고 링크가 문서 이동을 일으키면 복귀 화면이 결과와 작업 상태를 다시 읽는다. 이동 직전 문서에서 끝난 별도 POST 응답의 오류 문구와 초점은 복귀 화면에 전달하지 않는다.
- 자동 성능 중단용 DB 표본과 새 광고 경로의 귀속 연결, 실제 GPT 제한 광고의 노출·빈도·저장소 사용은 G-4 운영 검증으로 남긴다. 광고 공급 설정의 기본값은 꺼짐이다. 열린 탭의 기존 슬롯은 광고 설정을 최대 120초 동안 사용한다.

## Verification

- 프런트엔드 시험 및 빌드, 백엔드 Gradle 빌드, Playwright, 동일 출처 검증을 실행한다.
- 실제 Ad Manager에서 광고 단위 빈도와 소재 게재를 확인하고 대시보드의 CSP 및 네트워크 요청을 점검한다. 실계정 공급 검증이 불가능하면 미검증으로 기록한다.
- 프런트엔드 Vitest 1,578건, 빌드·타입·ESLint·Stylelint, Chromium 동일 출처 73건과 리뷰 반영 후 광고 클릭 2건이 통과했다.
- 전체 백엔드 Gradle 빌드와 시험 1,753건을 통과했고, 미사용 빈도 제한 코드 제거 후 광고 관련 시험 9건과 JAR 생성을 다시 확인했다.
- 운영 Compose preflight 시험 138건과 공급자 설정을 활성화한 격리 동일 출처 검사 1,128건이 통과했다. 운영 Ad Manager 계정의 광고 소재, 저장소 동작과 노출 빈도는 미검증이다.

## Suggested Review Order

- 기존 동기화 조작에서 Google 클릭과 수동 접수를 연결한다.
  [SyncControl.tsx:650](../../frontend/src/components/SyncControl.tsx#L650)

- 실제 광고를 준비할 조건과 슬롯의 유효 기간을 관리한다.
  [useDashboardAd.ts:13](../../frontend/src/ads/useDashboardAd.ts#L13)

- 인증된 대시보드에서 유효한 광고 단위 설정을 조회한다.
  [DashboardAdController.java:32](../../backend/src/main/java/com/bokrilog/DashboardAdController.java#L32)

- 문서 이동 후 늦게 접수된 동기화 작업을 찾는다.
  [useDashboardSync.ts:778](../../frontend/src/session/useDashboardSync.ts#L778)

- Google 광고 CSP 예외를 대시보드에 적용한다.
  [bokrilog.conf:93](../../deploy/nginx/bokrilog.conf#L93)

- 광고 트리거·접수 지연·복귀를 Chromium에서 검증한다.
  [dashboard-ad.spec.ts:67](../../frontend/e2e/dashboard-ad.spec.ts#L67)
