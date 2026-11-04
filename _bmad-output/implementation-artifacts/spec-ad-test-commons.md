---
title: '광고 시험 공용 helper와 engagement 바깥 의존 허용 목록'
type: 'chore'
created: '2026-11-04'
status: 'done'
baseline_commit: '1d6d0b1f254b7c50693c217c9592304f58eb21ef'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 광고 통합 시험 넷(AdConsent·AdEligibility·AdTransition·ClientTelemetry)이 같은 helper를 각자 들고 있다. 사본이 많은 것은 `grant`(3벌), 자격 행 INSERT(3벌), 동의 결정 INSERT(2벌, 인라인 2곳), `newTransition`·`assertNotFound`·`claim`·`providerBackOn`(각 2벌)이다. 그중 `admitManual`은 이미 서로 다르게 바뀌었다(Eligibility만 접수 시각을 당긴다). 경계 쪽도 비어 있다. `engagement/package-info.java`는 바깥 의존을 sync·operations·identity로 적었지만 그것을 강제하는 시험이 없다(Epic 4 회고 A-2, AI-8 `epic-4-retro-item-47`의 ③, deferred 167).

**Approach:** 루트 시험 패키지에 `abstract class AdTestBase extends OperatorTestBase`를 둔다. 광고 helper를 그리로 옮기고, 광고 시험 넷이 그것을 상속한다. 세 시험이 쓰는 `concurrently`는 `OperatorTestBase`로 올린다. `ModuleBoundaryTest`에는 engagement의 바깥 의존을 네 패키지로 제한하는 ArchUnit 규칙 하나를 더한다. 시험의 단정과 운영 코드는 바꾸지 않는다.

## Boundaries & Constraints

**Always:**
- 운영 소스(`backend/src/main`)는 바꾸지 않는다. 각 시험 메서드가 무엇을 요청하고 무엇을 단정하는지도 바꾸지 않는다. 시험 수는 늘기만 한다.
- `AdTestBase`에 `@DynamicPropertySource`·`@MockitoBean`·`@TestPropertySource`·새 설정 클래스를 두지 않는다. Spring 시험 컨텍스트가 하나 더 생기지 않아야 한다(`AuthFlowTestBase:105-121` 경고).
- `admitManual`의 공통부는 접수 → `Accepted` → `SUCCEEDED`다. 접수 시각을 1분 당기는 일은 Eligibility만 필요하므로 base에 따로 둔다(`backdateAdmission(job)`). Eligibility는 둘을 이어 부르고, 지금 그것을 부르는 모든 자리의 동작은 같다.
- 시험 값은 리터럴 토큰 모양을 피한다. 멱등 키는 `key(prefix)`, digest는 `sha256(무작위)`, transition은 `SecureRandom`에 `AdProviderTestHook.TRANSITION_BYTES`로 만든다.
- 경계 규칙은 `calculation` 규칙(`ModuleBoundaryTest:183`)과 같은 관용을 쓴다. 대상은 `com.bokrilog..`에 있는 클래스이고, 그중 `engagement..`·`sync..`·`operations..`·`identity..`가 아닌 것(루트 패키지 포함)에 engagement가 의존하면 실패한다. 규칙이 아무것도 보지 않고 통과하는 일이 없게, engagement의 운영 클래스가 비어 있지 않음을 함께 단정한다(`:69-73` 선례).
- 새 주석은 한국어로 쓴다.

**Ask First:**
- 사본을 합치면 어느 시험의 동작(SQL 열·순서·시각·단정)이 달라질 때. 특히 `AdConsentTest.insertDecision`의 digest 리터럴을 `sha256(무작위)`로 바꾸는 일이 그 시험의 단정에 닿을 때.

**Never:**
- `AccountErasureTest`·`RestoreApplyTest`(`PlanSyncTestBase` 계열)의 인라인 engagement 행, `ErasureAuditLockTest`의 `LOCK_WAITERS`, `TelemetryAllowlistTest`의 `scrape`는 합치지 않는다. 광고 시험 넷의 base 밖이다.
- 한 클래스에만 있는 helper(`insertConsumed`·`insertTodayThree`·`clearEligible`·`judge`·미터 읽기)는 옮기지 않는다.
- `PlanSyncTestBase.countOf`로 행 수 세기를 통일하는 일, 그리고 engagement 공개 API를 타입 단위로 제한하는 일은 하지 않는다. 후자는 Modulith `verify()`가 internal 미노출을 이미 막는다.
- 프로덕션 의존(`build.gradle.kts`)과 `@ApplicationModule` 애노테이션을 더하지 않는다. Modulith는 시험 classpath에만 있다.

</frozen-after-approval>

## Code Map

경로 접두사는 `backend/src/test/java/com/bokrilog/`이다.

- `OperatorTestBase.java` -- `Signed`:53, `signedIn`:65, `key`:109, `headers`:176. `concurrently`와 `interface Call`이 올라갈 자리다.
- `PlanSyncTestBase.java` -- `sync`, `awaitSync`:46, `sha256`:186, `publishedPlan`:200. `AuthFlowTestBase.java` -- `jdbc()`:1562, `sendJson`:742.
- `AdEligibilityTest.java`:
  - `admitManual`:593(접수 시각 −1분)과 그것을 감싸는 `manualSync`:585
  - `grant(Fixture)`:603, `insertConsent`:649(sha256), `insertEligibleRow`:639(인라인 transition 640-645), `assertNotFound`:679
  - `concurrently`:704-723, `providerBackOn`:86-89, `SecureRandom`:59, 필드 `consents`:66·`deletion`:72
- `AdTransitionTest.java`:
  - `admitManual`:379(당기지 않음), `grant(UUID)`:387, `claim`:393, `insertEligible`:399, `newTransition`:407, `assertNotFound`:432
  - 인라인 동의 INSERT 184-187·243-247, `providerBackOn`:60-63
- `ClientTelemetryTest.java` -- `claim`:477, `grant(UUID)`:496, `insertEligible(UUID)`:511(`decided_at` 기본값), `newTransition`:525. `insertConsumed`:503은 남긴다.
- `AdConsentTest.java` -- `insertDecision`:482(digest `"a"/"b".repeat(64)`, 쓰는 곳 :125·:427), `concurrently`:494-513, `IDEMPOTENCY_CONFLICT` 참조.
- `SupportInquiryTest.java` -- `concurrently`:961-979, `interface Call`:960. `OperatorTestBase`로 올린 것을 쓴다.
- `engagement/internal/AdProviderTestHook.java` -- `TRANSITION_BYTES`:23, `providerEnabled`. 운영 시험 문이며 바꾸지 않는다.
- `ModuleBoundaryTest.java` -- calculation 규칙:183-206(관용), 모듈 존재 단정:69-73, engagement로 들어오는 의존:500-516. engagement가 실제로 바깥에 의존하는 것은 `identity.AccountAccessApi`, `sync.SyncJobApi`, `operations.{OperationalRestrictionApi,RestrictionFeature,RestrictionSubject,AdvertisingStopApi}`뿐이다(운영 소스 import 기준). 그래서 새 규칙은 지금 green이다.
- 열린 회고 액션:
  - `epic-4-retro-item-47`: ③을 닫아 액션 전체가 끝난다(sprint-status 전환은 done PR 몫).
  - `epic-3-retro-item-39`(시험 표지 생성 도우미·gitleaks 사전 검사): 광고 시험의 값 생성만 base로 모으고, 이 액션은 건드리지 않고 지나간다.
  - `epic-2-retro-item-24`(erase 완전성 시험): `AccountErasureTest`를 건드리지 않으므로 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `OperatorTestBase.java` -- `concurrently(Call, Call)`과 `interface Call`을 protected로 올린다. `AdConsentTest`·`AdEligibilityTest`·`SupportInquiryTest`의 사본을 지운다 -- 세 벌을 하나로.
- [x] `AdTestBase.java`(신규, `abstract class`, package-private) -- 다음을 둔다.
  - 필드: `consents`·`deletion`·`context`. `@AfterEach providerBackOn`.
  - helper: `grant(UUID)`, `admitManual(UUID, String)`, `backdateAdmission(UUID)`, `claim(Signed, String)`, `insertConsent(UUID, String policyVersion, String choice)`, `insertEligible(UUID)`, `insertEligible(UUID, String decidedAtSql)`.
  - static: `newTransition()`, `assertNotFound(…)`.
  - 단일 정의다.
- [x] `AdConsentTest`·`AdEligibilityTest`·`AdTransitionTest`·`ClientTelemetryTest` -- `extends AdTestBase`로 바꾸고, 사본·인라인 INSERT·중복 필드를 base 호출로 바꾼다 -- 동작 불변.
- [x] `ModuleBoundaryTest.java` -- `engagement는_sync_operations_identity에만_의존한다` 규칙과 비어 있지 않음 단정을 더한다 -- package-info의 산문을 기계 판정으로.
- [x] `AdTestBaseTest.java`(신규, Spring 없음) -- `src/test/java`를 훑어 옮긴 helper 이름(`admitManual`·`grant`·`newTransition`·`assertNotFound`·`insertEligible`·`insertConsent`·`claim`·`concurrently`)의 메서드 정의가 정해진 base 밖에 없음을 단정한다 -- 재발 방지(`ControllerSupportTest`의 관용).
- [x] `CLAUDE.md` -- "시험·CI" 묶음에 한 줄을 더한다. 광고 통합 시험은 `AdTestBase`를 상속하고 helper 사본을 만들지 않는다는 것, engagement 바깥 의존은 `ModuleBoundaryTest`가 강제한다는 것 -- 규칙의 자리.

**Acceptance Criteria:**
- Given 이 변경, when 광고 시험 넷·`SupportInquiryTest`·`ModuleBoundaryTest`·`AdTestBaseTest`를 `--tests`로 돌리면, then 모두 통과하고 각 기존 시험 클래스의 시험 수가 그대로다.
- Given engagement 운영 클래스가 루트 패키지나 plan 모듈 타입을 import하는 가상의 변경, when `ModuleBoundaryTest`를 돌리면, then 새 규칙이 실패한다(구현 중 임시로 한 번 확인하고 되돌린다).
- Given 이 변경, when `git diff --stat backend/src/main`, then 변경이 없다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-04):
  - `AdEligibilityTest`에서 `admitManual`을 부르는 자리는 둘이고(`manualSync`, 경합 시험의 두 번째 접수), 둘 다 바로 `backdateAdmission`을 잇는다. 접수 시각을 당기는 동작은 예전과 같다.
  - `AdTransitionTest`의 인라인 두 스레드 경합 블록(Code Map 밖)도 같은 순서·같은 동작이라 `concurrently`로 바꿨다.
  - `AdConsentTest.insertDecision`의 digest 리터럴(`"a"/"b".repeat(64)`)을 `sha256(무작위)`로 바꿨다(Ask First 조건 확인). 이 digest를 읽는 단정은 없고, 유일 조건은 `(user_id, idempotency_key_digest)`라 충돌하지 않는다.
  - `grant`의 멱등 키 접두사는 `ad-grant` 하나다. 키 뒤에 무작위 UUID가 붙으므로 멱등 동작은 같다.
  - base의 `@AfterEach providerBackOn`이 이제 `AdConsentTest`·`ClientTelemetryTest`에서도 돈다. 공급자를 시험 컨텍스트 기본값(켜짐)으로 되돌리기만 하므로 단정은 같다.
  - `AdTestBaseTest`의 helper 목록에는 Tasks 목록에 없던 `backdateAdmission`이 들어갔다(base로 옮긴 helper이므로).
  - 인수 기준의 근거:
    - 기존 시험 클래스의 `@Test` 수가 기준 커밋과 같다. AdConsent 17·AdEligibility 12·AdTransition 9·ClientTelemetry 15·SupportInquiry 20이고, ModuleBoundary는 19→20(새 규칙 하나)이다.
    - 부정 확인으로 `engagement/internal`에 루트 `BokriLogApplication`과 `plan.PlanConditionApi`를 참조하는 임시 클래스를 넣었다. 새 규칙이 정확히 그 둘로 실패했고, 임시 클래스는 지웠다.
    - `git diff --stat backend/src/main`은 비어 있다.
    - `--rerun`으로 다시 돌린 대상 시험 120건이 모두 통과했다.
- 1차 리뷰(2026-11-04, blind·edge-case·verification-gap): patch 7 · defer 0 · reject 10.
  - patch:
    - `AdTestBaseTest`가 `src/test/java` 전체에서 `grant`·`claim`·`concurrently` 같은 흔한 이름을 금지했다. 광고와 무관한 미래 시험이 거짓 실패하므로, 훑는 범위를 그 base의 하위 시험(`extends AdTestBase`, `concurrently`는 `OperatorTestBase`·`AdTestBase` 계열)과 base 자신으로 좁혔다.
    - `providerBackOn`을 같은 이름으로 다시 정의하면 base의 `@AfterEach`가 가려지는데 목록에 없었다. 목록에 더했다.
    - 정의 정규식이 제네릭 메서드(`<T> List<T> concurrently(`)를 놓쳤고 `assert claim(`을 정의로 오인할 수 있었다. 둘 다 고치고 확인 줄을 더했다.
    - holder를 파일 이름이 아니라 `src/test/java` 기준 경로로 대조하게 했다. working dir 가정이 깨질 때의 실패 메시지도 붙였다.
    - `backdateAdmission`이 없는 job에서 조용히 지나갔다. 바뀐 행이 1인지 단정한다.
    - `ModuleBoundaryTest` 새 규칙 Javadoc에 추적 키(AI-8 ③ `epic-4-retro-item-47`)를 넣었다. `.because` 문구도 실제 의존(성능 정지 발동 `AdvertisingStopApi`)을 담게 고쳤다.
    - `CLAUDE.md` 새 줄이 시험이 막는 것보다 강하게 읽혔다. 같은 이름의 재정의만 막고, 이름을 바꾼 사본·인라인 SQL은 리뷰 몫이라고 고쳤다. `providerBackOn`도 목록에 더했다.
  - reject:
    - `backdateAdmission`을 base에 둔 것: SPEC Always가 지정했다.
    - `default`·`native` 수식어 사본: 클래스 helper에 해당하지 않는다.
    - `concurrently`의 무기한 `get()`과 출발 latch의 약한 동시성: 세 사본이 이미 그랬고, 이 변경은 옮기기만 했다.
    - `AdEligibilityTest`의 `CountDownLatch`·`Executors` import: 382-385행에서 쓴다.
    - `grant` 접두사 통일, `providerBackOn` 확대: 위에 기록했고 동작은 같다.
    - `OperatorCommandTest`·`OperatorRecoveryTest`·`ServiceNoticeTest`의 `interface Call`: `call`·`quietly`라는 다른 helper의 타입이고 `concurrently` 사본이 아니다.
    - `CLAUDE.md`의 현재 진행 지점·미룬 결정 문장: done 전환 PR이 맞춘다.
    - Tasks 목록과 helper 목록의 불일치: 위에 기록했다.
    - 인수 기준 근거가 없다는 지적: 위에 기록했다.

## Design Notes

base는 판정을 고르지 않는다. 그 시험이 무엇을 기대하는지는 각 시험 메서드에 남고, base는 준비(사용자·동의·행)와 요청 모양만 공유한다. `admitManual`을 쪼갠 것도 같은 선이다 — 쿨다운을 피하는 것은 Eligibility의 시험 사정이지 공용 의미가 아니다.

```java
UUID job = admitManual(user, key("manual"));
backdateAdmission(job); // Eligibility만: 다음 수동 접수가 쿨다운에 걸리지 않게
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.Ad*' --tests 'com.bokrilog.ClientTelemetryTest' --tests 'com.bokrilog.SupportInquiryTest' --tests 'com.bokrilog.ModuleBoundaryTest'` -- expected: 전부 통과(이 PC는 Windows 소켓 `Bad address` 간헐 실패가 있어 전체 판정은 CI `ci-required`)
- `git diff --stat backend/src/main` -- expected: 빈 출력

## Suggested Review Order

**공용 base의 경계**

- 준비·요청 모양만 공유하고 새 시험 컨텍스트를 만들지 않는 base
  [`AdTestBase.java:29`](../../backend/src/test/java/com/bokrilog/AdTestBase.java#L29)

- 접수 공통부와 Eligibility만 쓰는 접수 시각 당기기의 분리
  [`AdTestBase.java:55`](../../backend/src/test/java/com/bokrilog/AdTestBase.java#L55)
  [`AdTestBase.java:64`](../../backend/src/test/java/com/bokrilog/AdTestBase.java#L64)

- 상속한 네 시험 모두에 도는 공급자 켜짐 복원
  [`AdTestBase.java:43`](../../backend/src/test/java/com/bokrilog/AdTestBase.java#L43)

- 세 시험의 경합 helper를 한 단계 위 base로
  [`OperatorTestBase.java:264`](../../backend/src/test/java/com/bokrilog/OperatorTestBase.java#L264)

**engagement 경계 규칙**

- package-info 산문을 calculation 규칙과 같은 관용으로, 빈 대상 방지 포함
  [`ModuleBoundaryTest.java:526`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L526)

**호출자 전환(단정 불변)**

- 당기기를 이어 부르는 두 자리 중 하나
  [`AdEligibilityTest.java:572`](../../backend/src/test/java/com/bokrilog/AdEligibilityTest.java#L572)

- Code Map 밖 인라인 경합 블록도 같은 helper로
  [`AdTransitionTest.java:282`](../../backend/src/test/java/com/bokrilog/AdTransitionTest.java#L282)

- digest 리터럴이 무작위 sha256으로 바뀐 동의 행 삽입
  [`AdConsentTest.java:118`](../../backend/src/test/java/com/bokrilog/AdConsentTest.java#L118)

**재발 방지와 문서**

- 훑는 범위를 base 계열로 좁힌 helper 목록
  [`AdTestBaseTest.java:37`](../../backend/src/test/java/com/bokrilog/AdTestBaseTest.java#L37)

- 제네릭·문 머리 호출까지 가르는 정의 정규식
  [`AdTestBaseTest.java:58`](../../backend/src/test/java/com/bokrilog/AdTestBaseTest.java#L58)

- 새 광고 시험이 따를 규칙 문장
  [`CLAUDE.md:289`](../../CLAUDE.md#L289)
