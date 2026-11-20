---
title: 'CI 시험 fork 분할: backend-quality의 Gradle 시험을 JVM 셋으로'
type: 'chore'
created: '2026-11-03'
status: 'done'
review_loop_iteration: 0
scope_choice: 'K' # 2026-11-03 CHECKPOINT 1 — 토큰 초과(약 11,750자)를 [K] 유지로 승인(epic-3-retro-item-38 규약)
baseline_commit: '66d4938012398f0c15086e4c24dcdc9fd445ecbb'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-ci-backend-assemble-job.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** jar 생산자 분리(PR #129) 뒤 제품 PR의 CI 임계 경로는 `backend-quality` 하나다(PR #129 실측 14분 19초, 그중 Gradle 시험 ≈14분). 시험 1,5xx건이 JVM 하나·PostgreSQL Testcontainers 하나에서 순차로 돌아 클래스 시간의 합(상위 8개 파이프라인 클래스 12.6분 + 나머지 128개 7.7분 ≈ 20분, 로컬)이 그대로 wall-clock이다.

**Approach:** `build.gradle.kts`의 `Test` 설정에 `maxParallelForks = 3`을 더해 시험 클래스를 JVM 셋에 나눈다. JVM마다 `PostgresTestcontainersConfig`의 static 컨테이너가 하나씩 뜨고 포트는 전부 임의(`RANDOM_PORT`·루프백 0)라 fork 사이에 공유 자원이 없다. 이 SPEC은 `spec-backend-test-speed.md` Never와 `spec-ci-critical-path.md`·`spec-ci-backend-assemble-job.md`가 막아 둔 "백엔드 시험 병렬화" 경계를 **JVM 단위 fork 분할에 한해** 다시 연다 — JVM 안 병렬(JUnit 병렬)과 컨테이너 공유는 여전히 금지다. 단축은 PR CI 실측으로 확인한다.

## Boundaries & Constraints

**Always:**
- 시험 코드·단정·DB 초기화 전략(`AuthFlowTestBase.resetStore`)·스텁·시험 수와 이름은 그대로다. fork 분할은 빌드 설정 한 곳(`build.gradle.kts`)의 변경이다.
- JVM(fork)마다 컨테이너 하나 — `PostgresTestcontainersConfig`의 static 계약 그대로. fork 사이에 DB·포트·파일을 공유하는 시험이 있으면 안 되고, 구현 중 발견되면 중단하고 보고한다.
- `ci.yml`·`.github/scripts/*`·`test-ci-scripts.sh`는 바꾸지 않는다(명령은 `build` 그대로, timeout 20분 그대로). 바꾸게 되면 계약 시험을 같은 커밋에서 갱신한다.
- `gradle.properties`의 `org.gradle.parallel=false`는 그대로다(프로젝트 병렬이지 시험 fork가 아니고, CI-PLAN §10의 캐시·재현 정책에 묶여 있다).
- 근거 주석이 fork 수의 출처(러너 4 vCPU·16 GB, Gradle의 class 단위 round-robin 분배, 클래스 시간 수치)와 "JVM당 컨테이너 하나"를 적는다.
- 완료 조건: 로컬 `clean build` 통과(Windows 로컬의 `BybitAdapterTest` `Bad address: listen`은 Hyper-V 포트 예약 환경 문제로 알려져 있다 — 그것만이면 CI가 본다), PR CI 아홉 job green, `backend-quality` 실측이 PR #129의 14분 19초보다 뚜렷이 짧다.
- `CLAUDE.md`·`DEVELOPMENT.md`의 시험 시간 서술과 `deferred-work-resolutions.md`의 163 판정을 같은 PR에서 맞춘다(done 전환 PR이 없는 chore라 이 PR이 판정 자리다).

**Ask First:**
- fork 수를 3이 아닌 값(2·4)으로 바꾸는 것 — CI 실측(분배·시간·간헐 실패)을 본 뒤 결정한다.
- 분배가 나빠(round-robin이 무거운 클래스를 한 fork에 몰아) 실측이 10분을 넘으면 suite 배치(별도 `Test` task)로 가는 것 — 이 SPEC 밖의 결정이다.
- test worker heap(`maxHeapSize`)·`forkEvery` 설정, `backend-quality` timeout 20분 변경(CI-PLAN 새 버전 몫).
- fork 환경에서만 나는 시험 실패를 시험 수정으로 닫는 것 — 원인을 보고하고 결정을 받는다.

**Never:**
- JVM 안 병렬(JUnit `junit.jupiter.execution.parallel.*`), Testcontainers 재사용(`reuse`)·컨테이너 공유, 시험 삭제·`@Disabled`·단정 완화·대기 상한 늘리기.
- `ci.yml` 구조·job·timeout 변경, 리허설의 PR 경로 분리(deferred 164), 분류기 규칙 변경.
- 운영 코드·`application.yml`·`DevBybitStub` 시나리오 변경.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| CI 기본 | `ubuntu-24.04` 4 vCPU, `build`에 worker 플래그 없음 | fork 3, PostgreSQL 컨테이너 3, ryuk 3, 시험 수·결과 동일 | 컨테이너 시작 실패는 그 fork의 시험 실패로 드러나고 job이 빨갛다 |
| 로컬 `--max-workers=1` | `spec-backend-test-speed.md`의 측정 명령 | Gradle이 `maxParallelForks`를 max-workers로 상한해 fork 1로 접힌다 | N/A |
| 콜드 러너 | `postgres:18.6-alpine` 이미지 없음, 세 JVM이 동시에 pull | docker 데몬이 같은 이미지의 pull을 묶어 한 번만 내려받는다 | pull 실패는 시험 실패 |
| 한 fork의 실패 | 그 fork의 클래스 하나 실패 | `build/reports/tests`·`build/test-results`는 fork와 무관하게 병합된다. 실패 진단 artifact 단계 그대로 | N/A |

</frozen-after-approval>

## Code Map

- `backend/build.gradle.kts:160-234` -- `tasks.withType<Test>().configureEach` — 여기에 `maxParallelForks`(`:190`)와 근거 주석(`:162-189`), fork 분배·시험 수를 CI 로그에 남기는 `addTestListener`(`:195-233`, 판정 없이 출력만). 다른 설정은 그대로.
- `backend/gradle.properties:10` -- `org.gradle.parallel=false`(변경 전 `:8`) — 값은 손대지 않는다. 주석이 "병렬화는 켜지 않는다"고 적지만 그것은 프로젝트 병렬이므로 시험 fork는 별개라는 한 줄(`:8-9`)을 더한다.
- `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:37` -- static `CONTAINER` — JVM당 컨테이너 하나의 근거. `AuthFlowTestBase.resetStore`의 NOWAIT 전체 잠금은 그 컨테이너 안이라 fork 사이에 경합하지 않는다.
- fork 공유 자원 조사(읽기 전용, 결과 없음): `@SpringBootTest`는 `RANDOM_PORT` 2·`NONE` 8이고 `DEFINED_PORT` 없음, 루프백 서버 셋은 모두 포트 0(`OidcStubConfiguration.java:188`·`BybitStubConfiguration.java:221`·`source/internal/BybitAdapterTest.java:79`), 파일은 `RestoreRunnersTest`의 `@TempDir` 하나, `System.setProperty`·고정 포트 바인딩 없음, `DevOidcStubProfileTest.java:175`의 `18080`은 문자열 정규화 단정일 뿐이다.
- `.github/workflows/ci.yml:290-376` -- `backend-quality` — 변경 없음. 명령 `build`(`:352`), timeout 20분(`:311`), 진단 artifact 단계(`:366-375`, 실패·취소 때만 `build/reports/tests`·`build/test-results`)가 fork와 무관하다.
- `CLAUDE.md:97`(Docker 전제 문장 — JVM당 컨테이너, fork 수 `min(3, --max-workers)`) · `:130`(시험 시간 문장) · `:154`(진행 지점 문단의 "deferred 163·164로 남았다") · `:222` 표 끝(이 chore 행, `develop`의 AI-8 ① 행 뒤) · `:283-285`(시험·CI 계약 — JVM당 컨테이너 한 줄) · `:291`(미룬 결정의 163·168·169 판정).
- `DEVELOPMENT.md:220`(Docker 전제 문단 — fork 수·컨테이너 수·로컬 정식 명령) · `:277`(파일 표의 `PostgresTestcontainersConfig` 행) · `:752`(`backend-quality` 행) · `:760`(마지막 문장 "남은 병목 … deferred 163") · `:767`(시험 시간 문단) 뒤 `:769`(fork 분할 문단 — 근거·실측).
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md:18-23`(상태 표) · `:25`(2026-11-04 CI 실측 판정 문단) · `:33`(이 chore의 2026-11-03 갱신 문단) · `:1349`(163 절, 부분 해소 블록 `:1359`, 해소 블록 `:1366`) · `:1409`·`:1417`(리뷰 defer 168·169 절).
- 걸린 열린 회고 액션: `epic-3-retro-item-38`(SPEC [K] 선택·patch 건수 기록 규약, in-progress) — 이 SPEC이 그 규약을 따르되 닫지는 않는다. `epic-3-retro-item-37`(CI 임계 경로)은 done이고 이 chore는 그 둘째 후속이다. 그 외 이 변경이 고치는 파일에 걸린 액션 없음.

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts` -- `Test`에 `maxParallelForks = 3`과 근거 주석 -- 임계 경로의 시험 wall-clock을 셋으로 나눈다
- [x] `backend/gradle.properties` -- `org.gradle.parallel` 주석에 "시험 fork는 `build.gradle.kts`가 따로 정한다" 한 줄 -- 두 설정의 뜻이 갈리지 않게
- [x] 로컬 측정 -- `clean build` 통과·총시간, `build/test-results`의 `timestamp`+`time`으로 fork 타임라인(겹침·fork별 합)을 재구성해 `## Verification`에 기록 -- 분할이 실제로 일어났다는 증거
- [x] `CLAUDE.md`, `DEVELOPMENT.md` -- 시험 시간 서술·job 표·시험·CI 계약 한 줄 -- 문서와 실측 일치
- [x] `deferred-work-resolutions.md` -- 이 chore의 갱신 문단과 163 판정(CI 실측 뒤 해소), 상태 표·`CLAUDE.md:291` 건수 -- done 전환 규칙(163 절의 CI 실측 칸은 PR 실측으로 채운다)
- [x] PR을 열 때 PR 번호와 CI 실측을 채운다 -- 추적 산출물 정합. 채울 자리 전부: `CLAUDE.md:130` 문장(분할 뒤 CI `backend-quality` 실측) · `:154` 문단(PR 번호, 163의 해소 여부) · `:222` 표 행(PR 번호, 상태) · `:291` 문단(163 판정) · `DEVELOPMENT.md:769` 문단(CI 실측, pull·컨테이너 시작 오버헤드) · `deferred-work-resolutions.md:25` 갱신 문단(PR 번호)과 `### 163`의 실측 bullet·역전 규칙 적용(해소/Ask First/실패, 상태 표 건수) · 이 SPEC `## Verification`의 마지막 bullet(PR CI 실측) — PR 번호는 PR #133의 `ec5982a`가, CI 실측과 163 판정은 머지 뒤 기록 PR이 채웠다

**Acceptance Criteria:**
- Given 이 변경, when PR CI의 `backend-quality`가 돌면, then 아홉 job이 green이고 시험 수가 변경 전과 같으며 그 job이 PR #129의 14분 19초보다 뚜렷이 짧다(목표 8~10분). 통과 범위: `backend-quality` ≤ 10분 → 통과(163 해소), 10분 초과 ~ 14분 19초 미만 → Ask First(fork 수 2·4 또는 suite 배치) 뒤 결정, 14분 19초 이상 → 실패.
- Given 로컬 `clean build`, when Docker가 떠 있으면, then 통과한다(알려진 Windows 예외만 허용).
- Given `build/test-results`의 XML, when `timestamp`·`time`으로 타임라인을 그리면, then 세 클래스가 같은 시각에 돌던 구간이 있다.
- Given `bash .github/scripts/test-ci-scripts.sh`, when 로컬에서 돌리면, then 통과한다(`ci.yml` 불변의 확인).

## Spec Change Log

- 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프, 2026-11-03: patch 14·defer 2·reject 3. patch — 163을 해소에서 부분 해소로(CI 실측 전, 역전 규칙 명시), "1,5xx건" 자리표시를 실측 1,610건으로, 기준 보고서와 실측의 차이·`Bad address: listen`의 출처 설명, Code Map 줄 번호 정정, `Test`에 fork별 시험 수·길이와 전체 건수를 찍는 `TestListener`(green CI 로그에서 분할을 관측하는 유일한 자리), AC 1의 통과 범위(≤10분 해소·10~14분19초 Ask First·≥14분19초 실패), PR 시점 자리 열거, DEVELOPMENT.md job 표 행 복원, 콜드 러너를 기본 경로로, fork 수 결정 입력(상위 20 클래스 표·모의 방법) 보존, 자원 산수 유도, Gradle 9.7.1 클래스 이름 `MaxNParallelTestDefinitionProcessor`(분배 규칙은 bytecode로 확인), `min(3, --max-workers)`와 로컬 정식 명령 서술, CLAUDE.md 표 행 상태 `review`. defer — 168(fork 격리 불변식의 소스 스캔 시험, SPEC Always가 시험 코드 변경을 막음)·169(CI의 fork 수 기계 판정, `ci.yml`·스크립트 불변). 리뷰 당시 166·167로 적었고 아래 2026-11-04 항목이 번호를 옮겼다. reject — CPU 경합 행렬 행(Ask First "fork 환경에서만 나는 실패는 보고"가 이미 덮음), fork JVM 죽음의 미실행 클래스(Gradle이 executor 비정상 종료로 task를 실패시킴), `PostgresTestcontainersConfig` Javadoc 교차 참조(시험 트리 불변). KEEP — fork 분할은 `build.gradle.kts` 한 곳의 설정이고 시험 코드·`ci.yml`·스크립트는 그대로라는 틀.
- PR 전 기준 갱신, 2026-11-04: 작업 중 `develop`이 PR #130(루트 컨트롤러 공통 부품)·#132(그 done 전환)로 `66d4938` → `c213b6c`가 됐고, #132가 deferred 166·167을 AI-8 ②·③ 묶음에 먼저 썼다. 이 브랜치(자체 커밋 없음)를 `c213b6c`로 fast-forward하고 변경을 3-way로 다시 얹어 문서 충돌 여섯(`CLAUDE.md` 셋·원장 둘·`deferred-work.md` 하나)을 풀었다 — `develop` 쪽 문장·절은 글자 그대로 두고 이 chore의 문장만 더했다. 리뷰 defer를 168·169로 옮기고 상태 표를 해소 32·부분 해소 23·미해소 114(합 169)로, `CLAUDE.md`의 건수·표 행·Code Map과 아래 리뷰 순서의 줄 앵커를 맞췄다. `baseline_commit`은 구현을 시작한 `66d4938` 그대로다. `develop`이 바꾼 백엔드 파일(컨트롤러·`IdempotencyKey`·`ControllerSupportTest` 등)은 이 chore의 파일과 겹치지 않고 새 시험에 고정 포트·`System.setProperty`가 없다.
- CI 실측과 AC 1 판정, 2026-11-04(머지 뒤 기록 PR): PR #133 CI의 `backend-quality`가 14분 19초 → 10분 14초로 AC 1의 Ask First 구간(10분 초과 ~ 14분 19초 미만)에 들었다. 원인은 러너 사양이었다 — 저장소가 private이라 기본 `ubuntu-24.04` 러너가 4코어가 아니고 Gradle이 fork를 CPU 수로 상한해 executor가 2개였다. 동결 블록 Always의 "러너 4 vCPU·16 GB" 전제와 Design Notes의 같은 근거는 틀렸다(동결 문장은 고치지 않고 여기에 편차로 남긴다). 2 vCPU에서 fork를 늘릴 이득이 없어 사용자가 fork 수 변경·suite 배치 없이 이 값을 수용했고 163을 해소로 판정했다. `build.gradle.kts` 주석·`CLAUDE.md`·`DEVELOPMENT.md`의 4코어 전제를 고치고 설정은 3 그대로 둔다(4코어 이상 로컬은 3, CI는 2).

## Design Notes

**163의 첫 질문 — JVM 여럿에서 안전한가.** scheduler·virtual worker는 자기 JVM의 Spring 컨텍스트가 자기 컨테이너의 DB에만 닿는다. `resetStore`의 NOWAIT 전체 잠금도 그 컨테이너 안이다. fork 사이에 남는 공유는 docker 데몬·이미지 캐시·`~/.testcontainers.properties`뿐이고 셋 다 동시 접근에 안전하다. 그래서 바꾸는 것은 빌드 설정 한 줄이고 시험은 손대지 않는다.

**분배는 round-robin이다.** Gradle 9.7.1의 `MaxNParallelTestDefinitionProcessor`(bytecode로 확인 — 처음 N개는 새 processor를 만들고 그 뒤는 `processors.get(pos)`, `pos = (pos + 1) % N`, pos 0부터)는 class 스캔 순서로 i번째(0부터) 클래스를 fork i mod N에 보내고, 빈 fork에 다음 클래스를 주지 않는다. 스캔 순서는 `listFiles()` 순서라 러너마다 고정이지만 미리 알 수 없다. 아래 기준 보고서로 무작위 순서 3,000회를 모의하면 최장 fork의 p50/p90이 2 fork 12.1/14.4분, 3 fork 9.3/11.5분(10분 초과 32%), 4 fork 7.9/9.9분(10분 초과 9%)이고, 이상적 분배(LPT)는 2:10.1·3:6.8·4:5.1분이다. 3을 고르는 이유 — 4 vCPU에서 worker JVM 셋 + PostgreSQL 셋 + Gradle 클라이언트 JVM(`ci.yml`은 `--no-daemon`이라 데몬이 아니라 1회성 클라이언트 JVM)에 코어 하나를 남겨 부하가 유발하는 간헐 실패(deferred 159)를 키우지 않으면서 목표 8~10분의 중앙값에 든다. 4는 3의 실측이 나쁜 꼬리에 들 때의 Ask First 선택지다.

**fork 수 결정의 입력(기준 보고서).** 2026-11-03 로컬 `gradlew test` 보고서(fork 분할 전, 136 클래스·시험 1,592건, Windows 11). 값은 클래스 `time`에서 로컬 환경 실패(이 호스트의 Hyper-V 포트 예약 문제)의 30초 timeout을 뺀 초다 — 환경 실패 29건 가운데 30초 timeout은 `AccountUnlinkTest` 10·`AccountLinkedProvidersTest` 6·`AccountLinkDenialTest` 5의 21건(300·180·150초 → 0.5초 하한)이고 나머지 8건(`AccountDeletionTest` 7·`AuthKakaoSignInTest` 1)은 2초 안의 빠른 실패라 빼도 합이 달라지지 않는다. 보정 뒤 합 20.3분(보정 전 30.4분). 상위 20 클래스:

| 클래스(`com.bokrilog.` 생략) | 시험 수 | 초 |
|---|---:|---:|
| `FirstSnapshotTest` | 117 | 224 |
| `PlanVerificationTest` | 38 | 129 |
| `BybitReconnectionTest` | 24 | 104 |
| `SuccessorPlanTest` | 21 | 80 |
| `PlanEndTest` | 26 | 56 |
| `PlanCompletionTest` | 17 | 56 |
| `IncrementalSyncTest` | 19 | 53 |
| `BybitConnectionRevocationTest` | 28 | 52 |
| `StrongSignalAutoStopTest` | 23 | 31 |
| `ModuleBoundaryTest` | 33 | 27 |
| `AccountErasureTest` | 9 | 26 |
| `OperatorRecoveryTest` | 20 | 24 |
| `RestrictionEnforcementTest` | 11 | 22 |
| `OperatorRestrictionTest` | 11 | 21 |
| `AdEligibilityTest` | 12 | 20 |
| `source.internal.BybitAdapterTest` | 49 | 19 |
| `ServiceNoticeTest` | 16 | 16 |
| `BybitConnectionTest` | 39 | 13 |
| `AuthUnlinkProviderTest` | 37 | 12 |
| `RestoreApplyTest` | 13 | 10 |

상위 20의 합 16.6분, 나머지 116 클래스의 합 3.7분(상위 8의 합 12.6분, 나머지 128의 합 7.7분). 모의 방법 — 136 클래스의 (이름, 초) 목록을 무작위로 셔플(seed 고정)하고 i번째 클래스를 fork i mod N에 더해 N개 fork의 합 가운데 최댓값을 그 순서의 wall-clock으로 삼는다. 3,000회 반복해 최댓값의 분포에서 p50·p90·P(>10분)을 읽고, 같은 목록을 긴 것부터 가장 덜 찬 fork에 넣는 LPT로 이상적 분배를 구한다. N = 2·3·4를 같은 목록으로 돌린다. 실측(아래 Verification)은 137 클래스·1,610건·합 22.1분으로 기준과 다른데, 기준 보고서가 환경 실패 21건의 30초 timeout을 뺀 값이고 `ErasureAuditLockTest`(PR #127)가 아직 없던 별도 실행이기 때문이다.

**오버헤드.** fork마다 컨테이너 시작(≈5초)·ryuk 하나·Spring 컨텍스트 재생성(그 fork가 받은 클래스의 구성 수만큼, 개당 5~10초)이 더 든다. GitHub-hosted 러너는 실행마다 새 VM이라 콜드 시작은 예외가 아니라 기본 경로다 — 세 JVM이 동시에 `postgres:18.6-alpine`을 pull할 때 docker 데몬이 같은 이미지의 pull을 한 번으로 묶는다는 것은 추정이고, pull·컨테이너 시작 오버헤드는 PR 실측에서 적는다. worker JVM heap은 Gradle 기본값(512 MB)을 그대로 쓴다 — 지금 fork 하나가 모든 컨텍스트를 그 heap에 담고 통과하므로 fork마다 담는 컨텍스트가 적어지는 쪽은 더 안전하다. 메모리는 worker JVM당 heap 상한 512 MB(Gradle test worker 기본 `maxHeapSize`) + metaspace·off-heap 약 0.3 GB ≈ 0.8 GB의 추정으로 셋 ≈ 2.5 GB이고, PostgreSQL 셋과 클라이언트 JVM을 더해도 16 GB 안이다.

**측정 방법.** 전체 시간은 CI job 시각으로, 분배는 `build/test-results/test/TEST-*.xml`의 `timestamp`(시작)·`time`(길이)으로 fork 타임라인을 재구성해 본다(일회성 스크립트, 저장소에 넣지 않는다). green CI 실행에서는 그 XML이 올라가지 않으므로(`ci.yml`은 실패·취소 때만 진단 artifact) `build.gradle.kts`의 `TestListener`가 executor(fork)마다 `[test-forks] Gradle Test Executor N: 시험 …건, 길이 …초(시작 +…초)`와 루트에서 `[test-forks] 전체 …건(성공·실패·건너뜀), executor M개`를 찍는다 — 판정 없이 출력만이고, CI 로그에서 분할과 시험 수를 보는 유일한 자리다. 수치 출처 — PR #129 run 41594289163의 `backend-quality` 14분 19초, 클래스별 시간은 위 기준 보고서다.

## Verification

**Commands:**
- `bash backend/gradlew --project-dir backend clean build` -- expected: 통과(Windows 로컬의 `BybitAdapterTest` `Bad address: listen`만 허용), 총시간과 fork 타임라인을 여기 기록
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과, 실패 0(`ci.yml` 불변)
- PR CI -- expected: 아홉 job green, `backend-quality` 실측 8~10분, digest 일치

**로컬 실측(2026-11-03, Windows 11, Temurin 25, Gradle 9.7.1, Docker Desktop 29.7.2, 다른 작업 없이).**
- `bash backend/gradlew --project-dir backend clean build`: Gradle 보고 11분 44초(셸 벽시계 705초). 분할 전 같은 날 같은 호스트의 `clean build`는 19분 42초였다(`spec-ci-backend-assemble-job.md` Verification). 시험 1,610건·137 클래스(Intent의 "1,5xx건"은 작성 시점 자리표시이고 실측은 1,610건이다), 실패 1건 — `BybitAdapterTest > publicMarketReadsAreUnsignedAndUseTheClosedSpotQueries()`의 `java.net.SocketException: Bad address: listen`(허용 예외). 출처 — `spec-ci-backend-assemble-job.md` Verification: 분할 없는 baseline에서도 같은 예외가 5건 재현됐고 이 호스트의 Windows Hyper-V 제외 포트 범위 문제로 판정했다. 실행마다 건수가 다른 것(그때 5건 → 이번 1건)은 루프백 포트 0 바인딩이 받는 임의 포트가 예약 범위에 걸리는 확률 문제이지 fork 셋의 부하 문제가 아니다. 그래서 종료 코드는 1이고 통과 여부는 PR CI가 본다. 기준 보고서(136 클래스·합 20.3분)와 이 실측(137 클래스·22.1분)의 차이는 기준이 환경 실패 21건의 30초 timeout을 뺀 값이고 `ErasureAuditLockTest`가 없던 별도 실행이기 때문이다(Design Notes).
- 시험 중 `docker ps`: `postgres:18.6-alpine` 셋 + `testcontainers/ryuk:0.14.0` 셋 — fork마다 컨테이너 하나.
- fork 타임라인(`build/test-results/test/TEST-*.xml`의 `timestamp`·`time`, 일회성 스크립트는 저장소 밖): 클래스 시간 합 22.1분, 첫 시작(17:03:05)부터 마지막 종료까지 11.1분, 세 클래스가 동시에 돌던 구간 4.6분(순간 4개로 보이는 지점 하나는 같은 fork의 연속 클래스가 밀리초 단위로 겹쳐 보인 것). 겹치지 않게 묶은 세 lane(XML에 fork 번호가 없어 greedy 재구성)의 합은 10.4·7.1·4.6분, 마지막 종료는 +11.1·+7.1·+4.6분 — fork 하나가 끝까지 돈 동안 다른 둘은 +7.1·+4.6분에 끝났으니 로컬 분배는 모의의 p90 꼬리(11.5분)에 가깝다. 상위 클래스: `FirstSnapshotTest` 4.19분(+2.9 시작), `PlanVerificationTest` 1.95분(+8.9), `BybitReconnectionTest` 1.81분(+0.9), `SuccessorPlanTest` 1.80분(+2.5), `IncrementalSyncTest` 1.10분(+2.2).
- `bash .github/scripts/test-ci-scripts.sh`: 380개 중 380개 통과, 0개 실패(`ci.yml` 불변). 리뷰 patch 적용 뒤 다시 돌려도 380/380.
- fork 로그(`TestListener`) 출력 모양 — `bash backend/gradlew --project-dir backend test --tests com.bokrilog.StatusApiTest --tests com.bokrilog.ModuleBoundaryTest --tests com.bokrilog.sync.internal.SyncLeaseTest --rerun`(BUILD SUCCESSFUL in 23s, 세 클래스가 executor 셋에 하나씩):

  ```text
  [test-forks] Gradle Test Executor 40: 시험 2건, 길이 0.7초(시작 +0.8초)
  [test-forks] Gradle Test Executor 41: 시험 33건, 길이 9.5초(시작 +0.8초)
  [test-forks] Gradle Test Executor 39: 시험 15건, 길이 17.5초(시작 +0.8초)
  [test-forks] 전체 50건(성공 50·실패 0·건너뜀 0), executor 3개
  ```

  Windows 콘솔로 받은 로그는 Gradle 클라이언트가 cp949로 써서 한글이 깨져 보이고(`iconv -f cp949`로 복원), ubuntu 러너의 UTF-8 로그에서는 그대로 보인다. 전체 `clean build`는 다시 돌리지 않았다 — 변경이 로그 출력뿐이고 부분 실행이 listener의 모양·executor 수를 보였다.
- PR CI 실측(PR #133, run 41752910193, 2026-11-04): 아홉 job green, `backend-quality` 14분 19초 → 10분 14초(Gradle `build` 10분 1초), `[test-forks]` 로그 executor 2개(764건 455.4초·861건 522.2초)·전체 1,625건 성공, 전체 wall-clock은 `changes` 시작부터 `ci-required` 끝까지 14분 43초 → 12분 19초, digest 일치. 가장 늦게 끝난 job은 `deploy-rehearsal`(`frontend-quality` 뒤 01:22:26 → 01:30:15)이고 `backup-rehearsal`은 9분 44초였다. AC 1 판정은 Spec Change Log 2026-11-04 항목.
- 새 기준 로컬 `clean build`(`develop` `c213b6c` 위, 2026-11-04, Docker Desktop 기동 뒤 안정화한 다음): 10분 56초, 1,625건 전부 성공(fork 셋 570건 4.9분·525건 6.3분·530건 10.1분). 그 앞 실행 하나는 Docker Desktop을 켠 직후라 fork 하나가 Docker 환경을 찾지 못하고 다른 fork의 컨테이너가 중간에 사라져 무효로 멈췄다.

## Suggested Review Order

**fork 분할 — 설정 한 줄과 그 근거**

- 진입점: 시험 클래스를 JVM 셋에 나누는 유일한 변경. 러너 4 vCPU·round-robin 분배·모의 p50/p90이 3을 고른 근거
  [`build.gradle.kts:190`](../../backend/build.gradle.kts#L190)
- JVM당 컨테이너 하나·임의 포트라 fork 사이 공유 자원이 없다는 안전성 근거와 2·4 변경이 Ask First인 이유
  [`build.gradle.kts:163`](../../backend/build.gradle.kts#L163)
- `org.gradle.parallel=false`는 프로젝트 병렬이지 시험 fork가 아니라는 구분(값 불변)
  [`gradle.properties:8`](../../backend/gradle.properties#L8)

**관측 — green CI 로그에서 분할을 보는 자리**

- fork(executor)마다 시험 수·길이, 전체 건수·executor 수를 찍는 listener. 판정 없이 출력만, fork가 1로 접히면 executor 1개로 드러난다
  [`build.gradle.kts:203`](../../backend/build.gradle.kts#L203)
- 왜 로그가 유일한 자리인가 — 성공 실행은 test-results를 올리지 않고 `events("failed")`는 침묵한다
  [`build.gradle.kts:196`](../../backend/build.gradle.kts#L196)

**결정의 입력과 측정**

- Gradle 9.7.1 bytecode로 확인한 round-robin 규칙과 3,000회 모의 결과
  [`spec-ci-test-forks.md:93`](spec-ci-test-forks.md#L93)
- 상위 20 클래스 시간 표와 모의 방법 — 나중에 2·4·suite 배치를 다시 셈할 수 있는 입력
  [`spec-ci-test-forks.md:95`](spec-ci-test-forks.md#L95)
- 로컬 실측 19분 42초 → 11분 44초, fork 타임라인, 허용된 Windows 예외 1건
  [`spec-ci-test-forks.md:133`](spec-ci-test-forks.md#L133)

**계약·문서**

- 새 시험·CI 계약 — fork 사이 DB·포트·파일 공유 금지, JVM 안 병렬·`reuse` 금지
  [`CLAUDE.md:285`](https://github.com/sh-9610/BokriLog/blob/f4e24a6d7749d32f66dce558e7ad3af5828ee9db/CLAUDE.md#L285)
- `min(3, --max-workers)`와 2코어 기기·Docker Desktop의 컨테이너 셋
  [`CLAUDE.md:97`](https://github.com/sh-9610/BokriLog/blob/f4e24a6d7749d32f66dce558e7ad3af5828ee9db/CLAUDE.md#L97)
- 시험 시간 서술 — 분할 전·후 로컬 실측, CI 실측은 PR이 채운다
  [`CLAUDE.md:130`](https://github.com/sh-9610/BokriLog/blob/f4e24a6d7749d32f66dce558e7ad3af5828ee9db/CLAUDE.md#L130)
- 로컬 정식 명령은 `clean build`(fork 3)이고 `--max-workers=1`은 fork 1이라 wall-clock 비교 불가
  [`DEVELOPMENT.md:220`](../../DEVELOPMENT.md#L220)
- 분할 근거·오버헤드(콜드 러너가 기본 경로)·실측 문단
  [`DEVELOPMENT.md:769`](../../DEVELOPMENT.md#L769)
- job 표의 `backend-quality` 행(원래 목록 복원, fork 문장 별도)
  [`DEVELOPMENT.md:752`](../../DEVELOPMENT.md#L752)

**미룬 결정 원장**

- 163을 부분 해소로 — CI 실측 범위(≤10분 해소·10~14분19초 Ask First·≥14분19초 실패)에 따른 역전 규칙
  [`deferred-work-resolutions.md:1359`](deferred-work-resolutions.md#L1359)
- 이 chore의 갱신 문단 — append 168·169 미해소, 164·165·159 그대로
  [`deferred-work-resolutions.md:33`](deferred-work-resolutions.md#L33)
- 리뷰가 남긴 defer 둘 — fork 격리 불변식의 소스 스캔 시험, CI의 fork 수 기계 판정
  [`deferred-work.md:515`](deferred-work.md#L515)
- patch 14·defer 2·reject 3의 기록과 PR 전 `develop`(`c213b6c`) 위로 다시 얹은 기록
  [`spec-ci-test-forks.md:85`](spec-ci-test-forks.md#L85)
