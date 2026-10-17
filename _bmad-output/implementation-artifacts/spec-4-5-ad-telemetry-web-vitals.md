---
title: 'Story 4.5 — 광고 이벤트와 Web Vitals를 동일 출처로 계측하고 성능 목표를 위협하면 광고만 끄기'
type: 'feature'
created: '2026-10-17'
status: 'done'
baseline_commit: 'cbf4b30706b49ec9ff945451e770e6f56715390d'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-ad-fail-open.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 4.4가 뗀 광고 이벤트 계측(156)·Web Vitals와 성능 kill switch(157, 122 이월)·서버 소비 결말 카운터(회고 B-2)의 주인이 이 스토리다(`epics.md` Story 4.5). 브라우저 → 서버 계측 경로가 없어 광고 실패율과 p75를 모르고, 광고가 NFR-14(p75 LCP 2.5초·INP 200ms·CLS 0.1)를 위협해도 광고만 자동으로 끌 수 없다.

**Approach:**
- 동일 출처 beacon 하나 `POST /api/engagement/telemetry`(인증·CSRF·`Origin`)로 Web Vitals와 광고 이벤트를 한 묶음씩 보낸다.
- 브라우저 측정은 `web-vitals` 6.2.3(정확 버전, 런타임 의존 승인)이고, 광고 이벤트는 4.4 adapter가 사유와 준비를 밖으로 내준다.
- 서버는 닫힌 어휘의 카운터로 세고, 광고를 띄운 문서의 Vitals만 SHOW 소비 1건당 한 번 비식별 표본으로 저장한다. Worker가 그 표본의 p75를 판정해 넘으면 `PERFORMANCE` 출처의 전역 `ADVERTISING` 제한을 만든다.

## Boundaries & Constraints

**Always:**
- **beacon 계약.** 본문 `{"events":[…]}`, 1~16건, 8 KiB 이하. 하나라도 어휘 밖·모양 밖이면 400이고 아무것도 기록하지 않는다. 성공은 204, 계정 쓰기 거부는 404(기록 없음).
  - `{"kind":"VITAL","metric":"LCP|INP|CLS","route":"/dashboard|/settings|/plan-conditions|/ad-transition","value":<0 이상 유한수, LCP·INP ms>,"ad":<bool>}`
  - `{"kind":"AD","event":"REQUESTED|READY|CLOSED|FAILED|RESULT","reason":"TIMEOUT|NO_FILL|BLOCKED|UNSUPPORTED|ERROR|WINDOW_EXPIRED"}` — `reason`은 `FAILED`에만 있고 필수다.
- **브라우저.** 위 네 화면만 측정한다(진입·온보딩·운영자 화면 없음 — 진입 화면 요청 0건 유지). 화면 세션의 CSRF 토큰이 있을 때만 보낸다. `pagehide`·`visibilitychange`(hidden)와 중립 화면의 떠남 직전에 `fetch` `keepalive`로 한 번 보낸다. 실패·거절은 무시하고 재시도·저장소 쓰기·화면 영향이 없다.
- **adapter.** `showInterstitial(adUnitPath, onFailure(reason), onReady)`. 4.4 상태 기계(종단 하나·취소·늦은 callback 무시·파기)는 바뀌지 않는다. 화면은 SHOW에서 `REQUESTED`, 준비에서 `READY`, `광고 닫기`에서 `CLOSED`, 실패·창 경과에서 `FAILED`, 결과로 떠날 때 `RESULT`를 한 번씩 기록한다.
- **서버 카운터**(`TelemetryAllowlist` 행 먼저, 커밋 뒤 센다): `bokrilog.web.vitals{metric,route,rating=GOOD|POOR}`(POOR = NFR-14 임계 초과), `bokrilog.ad.client.event{event,reason}`(`FAILED` 밖은 `NONE`), `bokrilog.ad.transition{outcome}`(SHOW·`AdTransitionApi.SkipReason` 전부·`NOT_FOUND`).
- **표본.** `route=/ad-transition`·`ad=true`인 VITAL은 그 사용자의 attempt 중 SHOW로 소비됐고 10분 안이며 그 metric이 아직 보고되지 않은 행이 있을 때만 metric마다 하나씩 저장하고, 그 행에 그 metric의 보고를 표시한다(사용자 잠금 → `lockWriteAccess` 한 트랜잭션 — 한 문서의 Vitals는 여러 묶음으로 나뉘어 온다). 브라우저는 한 문서에서 metric마다 첫 보고 하나만 보낸다. 표본 표에는 사용자·attempt·transition 열이 없다.
- **판정**(Worker·`all`만, 5분 주기). 지난 24시간(DB 시각) metric별 표본이 40개 이상이고 p75가 임계를 넘으면 `operations`의 새 API가 별도 트랜잭션에서 원장 잠금 → 열린 전역 `ADVERTISING` 제한이 없을 때만 INSERT(`origin=PERFORMANCE`) → 감사 SYSTEM 행(`command=performance-stop`, 정책 `AD-PERF-1.0`, metric·표본 수·p75)을 쓴다. 발동 뒤 그 시각까지의 표본을 지운다. 24시간 지난 표본은 매 판정이 지운다. 창 표본 수는 gauge로 낸다.
- 발동한 제한은 3.4 `lift-restriction`으로만 닫히고 `PERFORMANCE`는 수동 검증 필요다. 다른 기능은 제한하지 않는다.
- 경보 규칙: `WebVitalsAboveTarget`(warning, 24시간 POOR 비율 > 0.25이고 표본 ≥ 40)·`WebVitalsUnmeasured`(info, absent)를 `env="production"`으로 더하고 promtool 시험을 단다.

**Ask First:** 임계·창(24시간)·최소 표본(40)·주기를 바꾸는 것, 측정 화면을 넓히는 것, 사용자 귀속 계측 행을 만드는 것, kill switch가 광고 밖 기능을 건드리는 것.

**Never:** 외부 분석·RUM·공급자 보고, `sendBeacon`(CSRF 헤더를 못 싣는다), 브라우저 저장소, 재시도, kill switch의 자동 해제, `OPS-SIGNAL-1.0` 판정표 변경, CSP·화면 경로 변경, 사용자·결과·transition·금융값을 label·본문·로그에 싣는 것, 이 계측만으로 유광고 출시 준비 완료를 적는 것(G-4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 묶음 | 인증·CSRF, 어휘 안 3건 | 204, 카운터 증가 | N/A |
| 어휘 밖 | `metric:"FID"` 또는 17건 | 400, 기록 0 | 화면 영향 없음 |
| 미인증·CSRF 없음 | 진입 화면·토큰 없음 | 보내지 않음 / 401·403 | N/A |
| 광고 표본 | SHOW 소비 5분 뒤 LCP·CLS·INP | 표본 3건, 보고 시각 기록 | N/A |
| 반복 표본 | 같은 attempt로 두 번째 묶음 | 카운터만 증가, 표본 없음 | N/A |
| SHOW 없음 | skip 소비·10분 지남·남의 계정 | 표본 없음 | N/A |
| 임계 초과 | 24시간 LCP 표본 40, p75 3초 | 전역 ADVERTISING 제한 1건, 표본 삭제 | 이미 열린 전역 제한이면 INSERT 없음 |
| 표본 부족 | 39개 | 발동 없음, gauge 39 | N/A |
| 전송 실패 | 네트워크 오류 | 무시, 이동·초점 그대로 | 재시도 없음 |

</frozen-after-approval>

## Code Map

이 스토리의 모든 경로와 명령은 worktree `/Users/hwan/Desktop/SeongHwan/Side_Project/BokriLog-4-5`(브랜치 `feature/4-5-ad-telemetry-web-vitals`) 기준이다. 사용자 체크아웃(`.../BokriLog`)의 파일은 고치지 않는다. Node 명령 앞에는 `eval "$(fnm env)" && fnm use 24 >/dev/null &&`를 붙인다(비대화형 셸은 Node 25를 잡는다). 백엔드 시험은 Docker Desktop이 떠 있어야 한다. 백엔드 경로의 `engagement/`·`operations/`·루트 파일은 `backend/src/main/java/com/bokrilog/` 아래다.

- `backend/src/main/resources/db/migration/V29__engagement_ad_vitals.sql`(새) — `engagement_ad_attempt`에 `transition_shown boolean NOT NULL DEFAULT false`·`vitals_reported_at timestamptz`(CHECK: shown → consumed, reported → shown), 표본 표 `engagement_ad_vital_sample(id bigserial, recorded_at timestamptz DEFAULT statement_timestamp(), metric, value)`, `operations_restriction_origin_check`에 `PERFORMANCE`(V19:100).
- `engagement/internal/AdTransitionService.java:46-87` — `consume` 때 SHOW면 `transition_shown`. 커밋 뒤 결말 카운터(`AdMetrics` 관용 `afterCommit`).
- `engagement/internal/AdAttemptRepository.java:120-140` — 조건부 UPDATE 관용. 보고 가능한 attempt 잠금·보고 시각 문장을 더한다.
- `engagement/AdTelemetry.java`·`internal/AdMetrics.java` — 닫힌 어휘·0 선등록 관용. 새 이름·tag 값을 여기 둔다.
- `engagement/`에 공개 API `ClientTelemetryApi`(묶음 기록), `internal`에 서비스·표본 repository·판정기(`SupportPendingMetrics.java:15-60`의 역할 조건·가상 스레드·`statement_timeout` 관용).
- `TelemetryAllowlist.java:113-116` — 광고 행 옆에 세 행과 gauge 행.
- `AdTransitionController.java` — 컨트롤러 모양 선례. 새 루트 `ClientTelemetryController`가 `/api/engagement/telemetry`를 연다(`AuthSecurityConfig.java:285,1333` 접두사가 인증·CSRF·`Origin`을 이미 건다).
- `operations/RestrictionOrigin.java` — `PERFORMANCE`. `OperationalRestrictionApi.java:49` `requiresVerification`을 `origin != OPERATOR`로.
- `operations/internal/OperationalSignalService.java:118-170` — 원장 잠금 → `activeRow` → `insert(..., origin)` → `appendSystem` 순서의 선례. 새 공개 API `AdvertisingStopApi`와 구현이 같은 순서를 쓴다.
- `ModuleBoundaryTest.java:369-432` — 광고 API 접근 규칙 선례. `ClientTelemetryApi`·`AdvertisingStopApi` 규칙을 더한다.
- `deploy/observability/alerts.yml`·`alerts.test.yml` — `MonthlyCostUnmeasured` info 선례.
- `frontend/src/ads/gptAdapter.ts:94-199` — `fail`(138)·`slotOnload`(180)·script `error`(191)·`null` slot(185)이 사유의 원천.
- `frontend/src/screens/AdTransitionScreen.tsx:88-220` — `leave`(88)·창 경과(112)·SHOW(218)·`광고 닫기`(226)·`결과 보기`(233).
- `frontend/src/session/adTransition.ts:31-61` — 동일 출처 POST 관용. 새 `telemetry/` 모듈은 keepalive·응답 무시판이다.
- `frontend/src/test/setup.ts:690` — `sendBeacon` 거부(유지). fetch 감시가 beacon 요청을 본다.
- `frontend/src/content/operator.ts:350` `originLabel` — `PERFORMANCE` 문구.

## Tasks & Acceptance

**Execution:**
- [x] `V29__engagement_ad_vitals.sql` — 위 Code Map대로.
- [x] `operations/RestrictionOrigin.java`·`OperationalRestrictionApi.java`·새 `AdvertisingStopApi.java`·`internal/AdvertisingStopService.java` — kill switch의 제한·감사 행.
- [x] `engagement/` 새 `ClientTelemetryApi`·`internal/ClientTelemetryService`·`AdVitalSampleRepository`·`AdPerformanceJudge`, `AdTelemetry`·`AdMetrics`·`AdAttemptRepository`·`AdTransitionService` 갱신 — beacon 검증·카운터·표본·판정.
- [x] `ClientTelemetryController.java`(새)·`TelemetryAllowlist.java` — 경로와 허용 표.
- [x] 백엔드 시험 — beacon 계약·어휘 밖 400·404, 표본 조건(SHOW·10분·1회), 판정(40·p75·삭제·기존 제한), 결말 카운터, 허용 표, 경계 규칙, 삭제 뒤 잔여 없음.
- [x] `frontend/package.json`·lockfile — `web-vitals` `6.2.3` 정확 버전.
- [x] `frontend/src/telemetry/`(새) — 묶음 큐·flush·Web Vitals 등록(문서당 한 번)·경로 대응. 네 화면이 훅 하나를 부른다.
- [x] `gptAdapter.ts`·`AdTransitionScreen.tsx` — 사유·준비 콜백과 광고 이벤트 기록.
- [x] 프런트엔드 시험 — 어휘·flush 시점·토큰 없음 무전송·실패 무영향·adapter 사유 matrix·4.4 회귀, 진입 화면 요청 0건 e2e 유지.
- [x] `content/operator.ts`, `alerts.yml`·`alerts.test.yml`, `CLAUDE.md`·`epic-4-context.md` — 문구·경보·계약 한 줄.

**Acceptance Criteria:**
- Given 운영자 화면, when kill switch 제한이 열려 있을 때, then 출처는 성능 자동 정지·수동 검증 필요로 보이고 `lift-restriction`으로만 닫힌다.
- Given 기본 구성의 주입 origin e2e, when 4.3·4.4 시험을 다시 돌릴 때, then 결과와 진입 화면 요청 0건이 그대로다.

## Spec Change Log

- 2026-10-17 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) — intent_gap 1, bad_spec 0, patch 21, defer 0, 나머지 reject.
  - **intent_gap 1건(frozen 정정):** 행 단위 보고 표지라 결과 보기 flush에 LCP만 실리면 그 SHOW의 CLS·INP 표본이 영구히 빠졌다(web-vitals는 CLS·INP를 hidden에서만 보고한다). 사용자 상시 위임(설계 질문은 추천안으로 진행)에 따라 `## Boundaries & Constraints`의 표본 문장을 metric 단위 표지와 "문서당 metric 첫 보고 하나"로 정정했다. 코드 전체를 되돌리지 않고 그 부분만 patch 루프로 다시 구현했다.
  - **patch:** metric 단위 보고 표지(V29 수정 — 미적용 마이그레이션), 열린 전역 `ADVERTISING` 제한이 있으면 판정이 표본을 지움(해제 뒤 묵은 표본 재발동·발동 뒤 늦은 표본 차단), 판독·판정 실패 로그 WARN, 클라이언트의 metric 첫 보고 하나, 광고 문서의 `ad` 표시를 flush 시점에 결정, bfcache 복원의 `RESULT`, Web Vitals 카운터 `ad` tag, `WebVitalsUnmeasured`를 24시간 증가 0으로, 경보·주석 문구, adapter 사유 타입 묶기, 프런트·백 어휘 공유 fixture 대조 시험, setup의 CSRF 상수, 중복 색인 제거, epic-4-context·CLAUDE.md 브라우저 경계, 시험 보강(동시 묶음·10분 경계·나뉜 묶음·해제 뒤 재발동 없음·역할 시험·화면별 route·운영자/온보딩 0건·주입 origin beacon 204).
  - **reject:** beacon 속도 제한(카운터만 부풀고 kill switch 표본은 SHOW 1건에 묶여 하루 3회 이하 — Design Notes에 적음), 값 상한(frozen 계약이 0 이상 유한수, 표본은 SHOW에 묶임), 성공 로그 수준(루트 컨트롤러 관용과 같다), 부분 색인 증가(사용자당 하루 3행 이하), kill switch 발동 카운터(운영자 화면·감사 원장이 보인다).
  - **KEEP:** beacon 계약·닫힌 어휘·400 전체 거절, 표본 표의 비식별, 판정의 원장 잠금 → 기존 전역 제한 확인 → INSERT → 감사 SYSTEM 행, `PERFORMANCE`의 수동 검증 필요, 4.4 adapter 상태 기계.

## Design Notes

- **p75와 임계.** "p75 ≤ 임계"는 "임계 초과 표본 비율 ≤ 25%"와 같다. 그래서 Prometheus는 GOOD/POOR 카운터만으로 목표 충족을 정확히 판정하고, kill switch는 DB 표본으로 `percentile_disc(0.75)`를 직접 본다.
- **beacon 남용.** beacon에는 속도 제한이 없다. 인증된 사용자가 카운터(경보)를 부풀릴 수는 있지만 kill switch 표본은 SHOW 소비 1건·metric 하나에 묶인다.
- **표본 남용.** 표본을 SHOW 소비 1건당 1회로 묶어 사용자당 하루 3회(4.2 cap) 이하다. 40개 중 25% 초과를 만들려면 여러 계정이 필요하다. 광고를 끄는 쪽으로만 작동하므로 남용의 최악은 수익 정지다.
- **결과 도달.** 대시보드는 출처를 모른다(`no-referrer`, 주소 표지 없음). 그래서 `RESULT`는 중립 화면이 결과로 떠나는 순간이다.
- **의존.** INP·CLS의 세션 창·상호작용 묶음은 직접 구현하면 틀리기 쉽다. `web-vitals`는 의존이 없고 번들에 들어가 CSP·외부 요청이 늘지 않는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` — expected: 통과.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test` — expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh`·`deploy/observability` promtool 검사(`check-alert-rules.sh`) — expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` — expected: 0 종료.

## Suggested Review Order

**beacon 계약 (진입점)**

- 동일 출처 경로 하나, 8 KiB·16건·필드 정확 일치가 아니면 묶음 전체 400
  [`ClientTelemetryController.java:52`](../../backend/src/main/java/com/bokrilog/ClientTelemetryController.java#L52)

- 사용자 잠금 → 쓰기 접근 → 10분 안 SHOW 행에서 아직 안 보고된 metric만 표본
  [`ClientTelemetryService.java:54`](../../backend/src/main/java/com/bokrilog/engagement/internal/ClientTelemetryService.java#L54)

- metric 단위 보고 표지와 표본 표(사용자 열 없음), `PERFORMANCE` 출처
  [`V29__engagement_ad_vitals.sql:27`](../../backend/src/main/resources/db/migration/V29__engagement_ad_vitals.sql#L27)

- SHOW 소비 때만 `transition_shown`을 같은 문장에서 쓴다
  [`AdAttemptRepository.java:144`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdAttemptRepository.java#L144)

**성능 kill switch**

- 전역 광고 제한이 이미 효력 중이면 판정 없이 표본을 비운다(해제 뒤 재발동 차단)
  [`AdPerformanceJudge.java:112`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdPerformanceJudge.java#L112)

- 발동·기존 제한 어느 쪽이든 창을 읽은 시각까지 표본 삭제
  [`AdPerformanceJudge.java:128`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdPerformanceJudge.java#L128)

- metric별 표본 수와 `percentile_disc(0.75)` 한 문장
  [`AdVitalSampleRepository.java:80`](../../backend/src/main/java/com/bokrilog/engagement/internal/AdVitalSampleRepository.java#L80)

- 원장 잠금 → 열린 전역 제한 확인 → INSERT → 감사 SYSTEM 행, 신호 경로와 같은 순서
  [`AdvertisingStopService.java:75`](../../backend/src/main/java/com/bokrilog/operations/internal/AdvertisingStopService.java#L75)

- `PERFORMANCE`도 수동 검증 필요, 해제는 `lift-restriction` 하나
  [`OperationalRestrictionApi.java:53`](../../backend/src/main/java/com/bokrilog/operations/OperationalRestrictionApi.java#L53)

**브라우저 계측**

- keepalive `fetch`만, 토큰 없으면 무전송, 실패는 버리고 재시도 없음
  [`beacon.ts:156`](../../frontend/src/telemetry/beacon.ts#L156)

- 문서당 metric 첫 보고 하나, `ad`는 보낼 때 `REQUESTED` 여부로 정한다
  [`beacon.ts:100`](../../frontend/src/telemetry/beacon.ts#L100)

- web-vitals 등록은 문서당 한 번, 네 화면이 이 훅만 부른다
  [`useTelemetry.ts:43`](../../frontend/src/telemetry/useTelemetry.ts#L43)

**광고 이벤트**

- adapter가 실패 사유와 준비를 내준다 — 4.4 종단 상태 기계는 그대로
  [`gptAdapter.ts:107`](../../frontend/src/ads/gptAdapter.ts#L107)

- 떠나는 곳마다 `RESULT` 기록 뒤 이동 전에 flush
  [`AdTransitionScreen.tsx:101`](../../frontend/src/screens/AdTransitionScreen.tsx#L101)

- bfcache 복원도 결과로 떠나는 경로라 `RESULT`를 남긴다
  [`AdTransitionScreen.tsx:168`](../../frontend/src/screens/AdTransitionScreen.tsx#L168)

**계측 어휘·경보**

- 새 이름 셋과 `ad` tag — 허용 표가 같은 상수를 쓴다
  [`AdTelemetry.java:97`](../../backend/src/main/java/com/bokrilog/engagement/AdTelemetry.java#L97)

- POOR 비율 25% 초과와 24시간 증가 0의 미측정 경보
  [`alerts.yml:251`](../../deploy/observability/alerts.yml#L251)

- 운영자 화면의 성능 자동 정지 사유 문장
  [`operator.ts:343`](../../frontend/src/content/operator.ts#L343)

**시험**

- 나뉜 묶음·동시 묶음·10분 경계·해제 뒤 재발동 없음
  [`ClientTelemetryTest.java`](../../backend/src/test/java/com/bokrilog/ClientTelemetryTest.java)

- 프런트·백이 함께 읽는 어휘 fixture
  [`telemetryVocabulary.fixture.json`](../../frontend/src/telemetry/telemetryVocabulary.fixture.json)

- 화면별 route와 온보딩·운영자 0건
  [`screens.test.tsx`](../../frontend/src/telemetry/screens.test.tsx)

- 주입 origin에서 beacon 204
  [`auth-entry.spec.ts:1217`](../../frontend/e2e/auth-entry.spec.ts#L1217)
