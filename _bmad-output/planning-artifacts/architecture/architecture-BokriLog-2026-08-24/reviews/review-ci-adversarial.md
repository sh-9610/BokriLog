# CI 계획 적대적 검토 — BokriLog MVP

- **검토 대상:** `CI-PLAN.md`
- **상속 계약:** `ARCHITECTURE-SPINE.md` AD-12·AD-14·AD-15, `PORTFOLIO-SOLUTION-DESIGN.md` §8·§10, `epics.md` Story 3.8
- **렌즈:** 문구를 준수해 독립 구현한 CI, Release Gate, CD가 서로 다른 선택을 해도 안전 계약이 보존되는가
- **판정:** **FAIL — Critical 2건과 High 7건을 정본에 반영한 뒤 `final`로 유지해야 한다.**

현재 계획은 빠른 PR CI와 OCI 운영 VM의 분리, digest 배포, expand-only migration, 운영 승인이라는 방향은 적절하다. 그러나 `동일 artifact`, `required 성공`, `이전 버전`, `한 번의 배포`, `운영 검증 완료`를 구현자가 동일하게 판정할 수 있는 기계 계약이 부족하다. 아래 반례는 모두 현재 문구를 위반하지 않으면서도 상속한 배포 불변식을 깨뜨릴 수 있다.

## Critical

### C-1 — Release evidence가 실제 실행 단위와 암호학적으로 결합되지 않는다

**문서상 여지**

`image_digest`, `sbom_digest`, `signature_result`, `migration_version`, `test_evidence` 필드는 나열되어 있지만 evidence manifest 자체의 schema, digest, 서명 주체, 저장 위치와 각 필드의 검증 가능한 참조 형식이 없다. `signature_result: success`라는 문자열도 허용된다. `migration_version`은 문자열일 뿐 실제 migration bytes와 image에 결합되지 않는다. `image_digest`도 OCI index digest인지 ARM64 child manifest digest인지 명확하지 않다.

**서로 독립적인 준수 구현의 반례**

1. Release Gate A는 multi-platform OCI index의 digest를 기록하고 그 index에 서명한다.
2. CD B는 registry copy 뒤 ARM64 child digest를 pull하고, evidence의 `signature_result=success`만 확인한다.
3. Migration C는 배포 서버의 최신 checkout에 있는 SQL을 실행하고 `migration_version` 문자열만 비교한다.
4. 세 단위는 모두 “digest 배포”, “서명 확인”, “migration version 확인”, “재빌드 금지”를 주장할 수 있지만 시험한 image, 실행한 image와 적용한 migration은 하나의 불변 release가 아니다.

이 경우 정상 image에 잘못된 schema가 적용되거나, 서명되지 않은 실행 manifest가 통과하거나, 수정된 evidence JSON이 CD 입력이 될 수 있다.

**필수 수정**

- Release subject를 `registry/repository@sha256:<digest>`의 **canonical OCI subject**로 고정하고, `linux/arm64` 단일 manifest인지 OCI index인지 한 가지를 결정한다. index를 쓰면 선택될 ARM64 child digest도 evidence에 명시하고 둘의 포함 관계를 검증한다.
- API와 Worker는 역할 인자만 다르고 동일한 canonical subject를 실행한다고 정의한다.
- migration bundle을 image 내부의 불변 경로 또는 별도 `migration_artifact_digest`로 고정하고 `migration_version`만으로 대체하지 않는다.
- SBOM, provenance, test summary와 migration bundle이 정확한 image subject를 참조하는 attestation이어야 한다.
- evidence manifest에 versioned JSON schema를 두고 canonical serialization digest와 승인된 CI identity의 서명을 요구한다. CD는 문자열 결과가 아니라 서명·subject·issuer·workflow identity를 재검증한다.
- CD가 검증한 evidence digest와 실제 pull·실행한 image digest를 배포 감사 기록에 함께 남긴다.

### C-2 — Release와 production 배포의 전역 직렬화·stale 방지가 없다

**문서상 여지**

동시성 규칙은 같은 PR의 오래된 CI 취소에만 적용된다. `main`의 여러 push, 수동 재실행, Production CD, 단일 migration 역할, Worker drain과 Nginx cutover 사이의 배타성은 정의되지 않았다.

**서로 독립적인 준수 구현의 반례**

1. `main` commit A와 B의 Release Gate가 동시에 실행되고 B가 먼저 완료된다.
2. 운영자는 B를 승인해 migration과 cutover를 시작한다.
3. 늦게 완료된 A의 CD도 승인되어 B가 만든 schema 위에 A의 migration을 실행하고 Worker/API를 A로 되돌린다.
4. 또는 두 CD가 각각 “별도 단일 migration job 하나”를 실행하고 서로의 Worker drain·upstream 파일을 덮는다.

각 실행 내부 순서는 AD-12와 일치하지만, 실행 간 경합으로 이전 schema, 중복 Worker, 잘못된 upstream 또는 부분 migration이 생긴다.

**필수 수정**

- Production environment마다 **하나의 durable deployment lease/lock**만 허용하고 migration 시작 전에 획득하여 관찰 종료 또는 rollback 완료까지 유지한다.
- Release build는 병렬화할 수 있어도 production promotion/CD는 직렬화한다. 실행 중 production job은 새 commit 때문에 자동 취소하지 않는다.
- migration 직전과 cutover 직전에 `target_commit`, `target_evidence_digest`, 현재 운영 deployment record와 기대 predecessor를 CAS 방식으로 다시 확인한다.
- 기본 정책을 “현재 운영 release의 descendant이자 승인된 target만 전진 배포”로 고정한다. 의도적 downgrade/rollback은 별도 명령과 사유·승인을 요구한다.
- 수동 재실행과 GitHub run attempt가 같은 migration/cutover를 중복 수행하지 않도록 deployment ID 기반 멱등성을 둔다.

## High

### H-1 — Required gate의 성공 진리표가 없어 `skipped`가 실패를 숨길 수 있다

계획은 “failure·cancel을 성공으로 숨기지 않는다”고만 적고, 어떤 event/변경 분류에서 어떤 job이 필수인지, `success/failure/cancelled/skipped`를 어떻게 집계하는지 정의하지 않는다. 독립 구현자는 `always()` aggregator에서 `needs.*.result != 'failure'`만 검사해 `cancelled` 또는 예상치 못한 `skipped`를 성공으로 만들 수 있다. path classifier가 workflow·CI script 변경을 문서 변경으로 분류해 모든 비싼 검사를 건너뛰어도 final gate가 green이 될 수 있다.

**필수 수정:** event와 변경 class별 required-job matrix, 허용된 skip 사유 enum, aggregator의 명시적 truth table을 정한다. 필수 job은 오직 `success`만 허용하고, `skipped`는 사전에 승인된 비필수 job에만 허용한다. workflow, build script, lockfile, Dockerfile, migration, deploy script, classifier 자체 변경은 관련 전체 gate를 강제한다. Branch protection은 개별 가변 job이 아니라 이름이 고정된 단일 aggregator를 참조하되, aggregator가 기대 job과 evidence의 존재까지 검증해야 한다.

### H-2 — `previous_digest`의 권위 원본과 rollback 가능성 판정 시점이 없다

`previous_digest`를 “직전 성공한 Release Gate”, “직전 tag”, “현재 active container” 중 어디서 가져오는지 정하지 않았다. 실제 운영이 수동 rollback됐거나 release를 건너뛴 경우 세 값은 다르다. Release Gate 시점의 old binary+new schema 시험도 배포 직전의 실제 production schema와 predecessor를 보장하지 않는다.

**필수 수정:** `previous_digest`는 배포 시작 시 서명된 production deployment record와 실제 active container에서 교차 확인한 digest여야 한다. Release Gate가 예상 predecessor를 기록하되 CD가 실제 predecessor와 일치하지 않으면 새 compatibility 검증 없이는 중단한다. 이전 image와 evidence의 registry 보존 기간은 rollback window보다 길어야 하고 garbage collection에서 보호한다. rollback window의 시작·종료 조건과 contract migration 승인 주체도 고정한다.

### H-3 — Migration 실행·재시도·부분 실패 계약이 불충분하다

“단일 역할로 expand migration을 한 번 적용”은 동시 실행 방지, transactional 여부, DDL 부분 성공, timeout 후 결과 불명, 재실행 idempotency와 schema history 검증을 결정하지 않는다. 서로 다른 migration runner가 DB advisory lock, CI concurrency 또는 파일 lock을 각각 택하면 단일성을 공유하지 못한다.

**필수 수정:** DB가 권위인 migration lock과 schema history를 사용하고, exact migration artifact digest·expected-from·expected-to를 검증한다. timeout/연결 끊김은 성공으로 추정하지 않으며 schema history와 실제 schema를 재확인한 뒤에만 재시도한다. 비트랜잭션 DDL은 별도 복구 절차와 실패 주입 fixture를 요구한다. startup validation은 expected schema 범위를 벗어나면 fail-closed여야 한다.

### H-4 — Registry promotion·보존·권한 경계가 없어 검증 뒤 artifact가 달라질 수 있다

Registry 선택을 Story 3.8로 미룰 수는 있지만, promotion의 불변식까지 미뤘다. 구현 A는 build registry에서 production registry로 tag pull/push하고, 구현 B는 server가 tag를 resolve한 뒤 digest를 기록할 수 있다. registry가 manifest를 재작성하거나 SBOM/referrer를 복사하지 않거나 GC가 이전 digest를 지워도 현재 문구상 차단 근거가 약하다.

**필수 수정:** build→candidate→production promotion은 서버 재빌드 없이 digest-preserving copy로 한정하고 대상 registry에서 canonical subject, signature, SBOM/provenance를 다시 검증한다. mutable tag는 탐색용일 뿐 권위가 아니며 CD 입력으로 금지한다. registry write principal과 production deploy principal을 분리하고 overwrite/delete 권한, immutability와 rollback artifact retention을 정한다.

### H-5 — Branch protection이 선택 사항이라 release 신뢰 근거가 무너질 수 있다

문서는 private plan이 보호 기능을 제공하지 않으면 “운영 규칙”으로 대신하도록 허용한다. 동시에 CD 조건은 “성공한 보호 `main` commit”을 요구한다. 구현자는 보호되지 않은 `main` push에서도 Release Gate와 registry write를 실행할 수 있고, 그 결과를 승인만 받아 배포할 수 있다. 이는 검증 가능한 제어가 아니다.

**필수 수정:** 보호된 source와 required gate 강제를 production 출시의 선행조건으로 둔다. 현재 plan이 이를 제공하지 않으면 production CD를 fail-closed로 비활성화하거나, 동일한 효과를 제공하는 GitHub ruleset/별도 immutable release ref와 검증 가능한 승인 정책을 마련해야 한다. 단순 수동 규칙은 production artifact의 신뢰 근거로 인정하지 않는다.

### H-6 — Retry가 flaky failure를 실제로 성공 처리한다

현재 Playwright CI retry 1회는 “trace 수집과 일시적 판별에만 사용”한다고 하지만 Playwright 기본 판정은 첫 실패 후 재시도 성공을 workflow 성공으로 처리한다. 따라서 문구의 의도와 실제 일반 구현이 다르다. 수동 workflow rerun이나 failed-job rerun도 어떤 실패에 허용되는지 없다.

**필수 수정:** required gate는 flaky test 수가 0일 때만 성공하도록 report를 파싱하거나 required 경로의 retry를 0으로 둔다. 재시도는 별도 진단 job 또는 artifact 수집에만 사용한다. 수동 rerun은 infra failure reason만 허용하고 original attempt, rerun actor, reason과 두 결과를 evidence에 남긴다. 같은 SHA의 재실행이 이전 실패 기록을 지우지 않도록 release policy를 정한다.

### H-7 — 실제 production 완료 증거와 관찰 성공 기준이 없다

상속 문서는 단계·수행자·digest·검증·전환·rollback 감사와 “실제 운영 대상 smoke 전에는 운영 배포 미검증” 상태를 요구한다. CI 계획의 evidence는 배포 전 자료만 담고, CD 후 deployment evidence의 schema·권위 저장소·상태 전이가 없다. “관찰 기간”의 길이, 통과 signal, telemetry 지연 시 행동도 정의되지 않아 Nginx reload 직후 성공 처리하는 CD와 30분 관찰하는 CD가 모두 준수 구현이 된다.

**필수 수정:** release evidence와 별도의 append-only production deployment record를 정의한다. 최소 필드는 deployment ID, environment/host, operator/approval, target·previous·evidence digest, migration 결과, inactive readiness/smoke/telemetry 증거, cutover 시각, Worker singleton 증거, post-cutover 관찰 창과 결과, rollback 여부·사유다. 상태는 최소 `APPROVED → MIGRATED → INACTIVE_VERIFIED → CUTOVER → OBSERVING → VERIFIED`이며 실패·rollback 상태를 분리한다. 관찰 시간과 오류율/health/telemetry/queue 기준을 versioned policy로 고정하고 `VERIFIED` 전에는 production 출시 완료로 표시하지 않는다. 배포 단계마다 operations restriction을 재검사하고 제한 상태에서는 fail-closed해야 한다.

## Medium

### M-1 — Privileged workflow의 신뢰 경계가 구현자 선택에 남아 있다

Production secret을 environment에 둔다는 규칙만으로는 충분하지 않다. `workflow_run`이 untrusted PR artifact를 받아 배포하거나 `workflow_dispatch`가 임의 ref/evidence URL을 입력받는 구현도 가능하다. CD는 default branch의 승인된 workflow 정의로만 실행하고, 입력은 보호 `main`의 성공 Release Gate가 생성한 서명 evidence digest로 한정해야 한다. PR head checkout, 외부 URL artifact와 임의 SHA 입력은 금지할 필요가 있다.

### M-2 — Cache poisoning과 release clean-build 경계가 없다

PR cache가 release에서 복원될 수 있고 향후 build cache가 실행 파일을 포함하면 untrusted PR이 release 결과에 영향을 줄 수 있다. Package-manager 다운로드 cache 외의 cache는 content hash와 trust domain을 분리하고, Release Gate는 PR이 쓴 cache를 실행 가능한 입력으로 소비하지 않는다고 명시해야 한다.

### M-3 — `workflow_dispatch: 선택한 전체 검사`가 모호하다

누가 어느 ref에서 어떤 gate를 선택할 수 있는지 정하지 않았다. 수동 실행은 진단과 release/promotion을 분리하고, 진단 실행은 registry write나 deployment approval 대상이 될 수 없게 해야 한다. Release 수동 재생성은 보호 `main` SHA와 동일 evidence schema를 만족해야 한다.

### M-4 — Test evidence의 완전성과 fail-fast 관계가 없다

fail-fast 때문에 뒤쪽 suite가 실행되지 않은 run에서 단순 “workflow success”만 보지 않으면 문제없지만, 현재 `test_evidence`는 각 suite의 상태·수·report digest를 요구하지 않는다. Release manifest는 필수 suite별 `passed/failed/not_run`, report digest와 정책 버전을 기록하고 하나라도 `not_run`이면 release를 거부해야 한다.

### M-5 — Launch gate는 목록일 뿐 배포 차단 입력으로 모델링되지 않았다

OAuth, Bybit/IP, 법률, 광고, 백업 복원 등은 green CI와 구분했지만 어떤 기능 flag와 어떤 production release가 어떤 승인 evidence를 요구하는지 기계적으로 연결되지 않았다. 공개 베타 전에 versioned launch-readiness manifest를 별도로 두고 필수 외부 승인, 만료일, approver와 실제 훈련 결과를 참조하도록 해야 한다. 없거나 만료된 항목은 해당 기능 또는 공개 베타 활성화를 차단해야 한다.

## 교차 구현 수렴을 위한 최소 계약

아래 다섯 계약이 정본에 추가되면 핵심 반례가 닫힌다.

1. **Release identity contract:** canonical OCI subject + ARM64 실행 digest + migration artifact digest + attestation/evidence digest와 서명 검증 규칙.
2. **Required gate contract:** event/change class별 필수 job matrix, skip enum, aggregator truth table와 branch-protection 대상 이름.
3. **Deployment concurrency contract:** durable environment lock, predecessor CAS, stale release 거부, rerun idempotency와 production job non-cancellation.
4. **Rollback contract:** 실제 active deployment record가 권위인 `previous_digest`, compatibility 재검증, artifact retention, rollback-window 종료 조건.
5. **Production verification contract:** append-only deployment record, 단계별 evidence, versioned observation policy와 `VERIFIED` 전 출시 완료 금지.

## 최종 판정

현재 계획은 **방향성은 채택 가능하지만 실행 정본으로는 미완성**이다. 특히 C-1과 C-2는 독립 구현 간 불일치가 데이터베이스와 운영 트래픽에 직접 영향을 주므로 Story 3.8 구현 SPEC로 단순 위임해서는 안 된다. 상위 CI 계획이 불변식을 먼저 고정하고, registry·인증 제품 선택과 action SHA 같은 교체 가능한 세부만 구현 SPEC에 남겨야 한다.
