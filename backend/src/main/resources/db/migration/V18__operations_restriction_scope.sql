-- Story 3.2: 운영 제한을 `기능 × scope × target`으로 넓힌다.
--
-- **expand만 한다(AD-12).** V4의 `command` 열은 지우지 않고 NULL을 허용할 뿐이다 — 옛 API가 새 스키마 위에서 돌아도
-- (rollback 호환 창) 옛 행을 읽을 수 있고, 열을 지우는 contract migration은 따로 묻는다(SPEC Ask First). 새 API는
-- `command`를 읽지도 쓰지도 않는다.
--
-- **rollback 호환의 한계.** 3.2 이전 API는 `command`만 읽는다. 그래서 새 스키마 위로 옛 API로 되돌리면 3.2가 만든
-- 제한(`command`가 NULL인 행 — scope 제한과 전역 제한 모두)을 보지 못하고 그 기능을 막지 않는다. 되채운 옛 행만 계속
-- 막는다. 아직 운영 배포 전이라 이 창은 실재하지 않으며, 배포·rollback 창의 절차(제한이 있는 동안 옛 API로 되돌리지
-- 않는 규칙 포함)는 Story 3.8이 다룬다.
--
-- 기능은 NFR-17의 넷이다 — 신규 연결(`NEW_CONNECTION`)·동기화(`SYNC`)·계산 표시(`CALCULATION_DISPLAY`)·광고
-- (`ADVERTISING`). scope는 `GLOBAL`·`PLAN`·`PORTFOLIO`·`CALCULATION_POLICY_VERSION`·`PROVIDER_CONNECTION`이고 `GLOBAL`의
-- target은 고정값 `ALL`이다. 기능별로 받는 scope의 조합은 서버(`RestrictionFeature.scopes`)가 검증하며 여기서는 두 축의
-- 닫힌 집합과 `GLOBAL`·`ALL`의 짝만 고정한다.
--
-- **사용자 귀속.** 계획·portfolio·연결 target은 사용자에게 귀속된 원식별자다. `target_user_id`가 그 소유자이고, 계정
-- 삭제의 deletion port(`OperationsDeletionApi.eraseRestrictionTargets`)가 target을 keyed digest로 바꾸고 이 열을 NULL로
-- 만들며 `target_erased_at`을 남긴다. identity를 외래키로 가리키지 않는다(V17과 같은 이유 — 행이 계정보다 오래 산다).
-- 감사 원장에는 같은 target이 처음부터 digest로만 남는다.
--
-- `operation_id`는 그 제한을 만든 운영 command다(원장의 operation). 이 파일 이전의 행에는 없다.

ALTER TABLE operations_restriction ALTER COLUMN command DROP NOT NULL;

ALTER TABLE operations_restriction ADD COLUMN feature text;
ALTER TABLE operations_restriction ADD COLUMN scope text;
ALTER TABLE operations_restriction ADD COLUMN target text;
ALTER TABLE operations_restriction ADD COLUMN target_user_id uuid;
ALTER TABLE operations_restriction ADD COLUMN operation_id text;
ALTER TABLE operations_restriction ADD COLUMN target_erased_at timestamptz;

-- 기존 command 행의 되채우기. 대응표(SPEC Boundaries "검사 지점 대응")대로 기능 전체를 막는 전역 제한이 된다 — 신규 연결
-- command는 `NEW_CONNECTION`, 그 밖의 command(첫 스냅샷·초기 수집·계획 조건·발행·증분 동기화·종료·해제와 모르는 이름)는
-- 모두 동기화 파이프라인의 것이므로 `SYNC`다. 모르는 이름을 버리지 않고 `SYNC`로 접는 것이 fail-closed다.
UPDATE operations_restriction
SET feature = CASE WHEN command = 'bybit-connection-create' THEN 'NEW_CONNECTION' ELSE 'SYNC' END,
    scope = 'GLOBAL',
    target = 'ALL'
WHERE feature IS NULL;

ALTER TABLE operations_restriction ALTER COLUMN feature SET NOT NULL;
ALTER TABLE operations_restriction ALTER COLUMN scope SET NOT NULL;
ALTER TABLE operations_restriction ALTER COLUMN target SET NOT NULL;

ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_feature_check
    CHECK (feature IN ('NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY', 'ADVERTISING'));
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_scope_check
    CHECK (scope IN ('GLOBAL', 'PLAN', 'PORTFOLIO', 'CALCULATION_POLICY_VERSION', 'PROVIDER_CONNECTION'));
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_target_length_check
    CHECK (octet_length(target) BETWEEN 1 AND 128);
-- 전역 제한의 target은 `ALL` 하나다. 다른 scope는 `ALL`을 target으로 쓸 수 없다.
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_global_target_check
    CHECK ((scope = 'GLOBAL') = (target = 'ALL'));
-- 사용자 귀속은 계획·portfolio·연결 scope에만 있다.
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_target_user_check
    CHECK (target_user_id IS NULL OR scope IN ('PLAN', 'PORTFOLIO', 'PROVIDER_CONNECTION'));
-- 비식별된 행은 귀속이 없다.
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_erased_check
    CHECK (target_erased_at IS NULL OR target_user_id IS NULL);
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_operation_id_length_check
    CHECK (operation_id IS NULL OR octet_length(operation_id) BETWEEN 1 AND 64);

-- 판독 술어다(`OperationalRestrictionRepository.effective`). 기능과 scope로 좁히고 target을 같음으로 비교한다.
CREATE INDEX operations_restriction_scope_idx ON operations_restriction (feature, scope, target);

-- 같은 기능·scope·target의 활성 제한은 하나다. 생성 command는 원장 잠금 안에서 먼저 보고 친절한 거절을 하지만,
-- 권위는 이 색인이다. 운영 command가 만든 행(`operation_id`가 있는 행)만 센다 — 되채운 옛 command 행은 서로 다른
-- command가 같은 `(SYNC, GLOBAL, ALL)`로 접혀 겹칠 수 있고, 그것을 지우거나 끝내면 옛 제한의 의미를 바꾼다.
CREATE UNIQUE INDEX operations_restriction_active_key
    ON operations_restriction (feature, scope, target)
    WHERE effective_until IS NULL AND operation_id IS NOT NULL;

-- 삭제 port의 비식별.
CREATE INDEX operations_restriction_target_user_idx ON operations_restriction (target_user_id);
