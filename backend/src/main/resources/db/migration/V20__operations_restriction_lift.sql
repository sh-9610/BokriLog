-- Story 3.4: 검증 증거를 확인한 뒤 제한을 수동으로 복구(해제)한다.
--
-- **해제는 행을 지우지 않고 효력 구간을 닫는다.** V4가 만든 `effective_until`을 해제 command(`lift-restriction`)가
-- 조건부 UPDATE 한 문장(`OperationalRestrictionRepository.lift`)으로 채우고, 그 operation ID를 이 파일이 더하는
-- `lifted_by_operation_id`에 남긴다. 같은 커밋에 감사 원장의 `COMPLETED` 행이 쓰이므로 "누가 어떤 증거로 풀었는가"는
-- 원장이, "언제부터 막지 않는가"는 이 행이 말한다. `effective_until`을 쓰는 운영 SQL은 그 한 문장뿐이고 시험
-- (`OperatorRecoveryTest`)이 운영 소스를 훑어 강제한다 — 시간 경과·신호 부재·재시작으로 제한이 닫히는 경로가 없다.
--
-- **열린 행 색인.** 신호의 연결 판정(`activeRow`)과 생성 command의 중복 판정(`isActive`)이 `effective_until IS NULL`인
-- 행을 기능·scope·target으로 찾는다. 해제된 행이 쌓여도 그 판독이 닫힌 행을 훑지 않게 한다.
--
-- expand만 한다(AD-12). 열은 NULL을 허용하고 기본값이 없다 — 옛 API가 새 스키마 위에서 돌아도(rollback 호환 창) 이
-- 열을 모르고 그대로 동작한다. V1~V19는 고치지 않는다.

ALTER TABLE operations_restriction ADD COLUMN lifted_by_operation_id text NULL;

ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_lifted_by_length_check
    CHECK (lifted_by_operation_id IS NULL OR octet_length(lifted_by_operation_id) BETWEEN 1 AND 64);
-- 해제한 operation이 있는 행은 닫혀 있다. 닫힌 옛 행(해제 command 이전)은 operation이 없을 수 있다.
ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_lifted_closed_check
    CHECK (lifted_by_operation_id IS NULL OR effective_until IS NOT NULL);

CREATE INDEX operations_restriction_open_idx ON operations_restriction (feature, scope, target)
    WHERE effective_until IS NULL;

-- **smoke 되돌림의 계보.** 해제 커밋 뒤 smoke가 어긋나면 같은 기능·scope·target의 새 제한을 더한다
-- (`OperationalRestrictionRepository.reinstate`). 그 행은 `reinstated_from`으로 원 제한을 가리키고 원 제한의 출처를 잇는다
-- — 신호가 만든 제한을 되돌린 행도 자동 정지(`SIGNAL`)다. 활성 목록의 연결 신호 수와 다음 해제의 신호 계보 재검사는 이
-- 사슬을 따라 원 제한에 연결된 신호까지 센다. 신호 행은 고치지 않는다.
ALTER TABLE operations_restriction ADD COLUMN reinstated_from uuid NULL REFERENCES operations_restriction (restriction_id);

ALTER TABLE operations_restriction ADD CONSTRAINT operations_restriction_reinstated_self_check
    CHECK (reinstated_from IS NULL OR reinstated_from <> restriction_id);
