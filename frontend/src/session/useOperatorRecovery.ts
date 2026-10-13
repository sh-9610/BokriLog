import { LIFT_RESTRICTION_COMMAND } from '../content/operator'
import {
  completedRecoveryOf,
  preparedRecoveryOf,
  recoveryBodyOf,
  type CompletedRecovery,
  type PreparedRecovery,
  type RecoveryInput,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'

/**
 * 제한 복구의 준비 → 확인 → 확정·취소(Story 3.4). 상태 기계는 공통 훅(`useOperatorCommand`)이다.
 *
 * 준비는 아무것도 열지 않는다 — 서버가 제한이 아직 열려 있는지 보고 허가를 발급한 뒤 다시 열릴 단계·남는 제한·신호 계보의
 * 미리보기와 확인 문구를 준다. 멱등 키는 한 입력(제한·원인·검증·증거·배포 버전·조건)마다 한 번이다. 확정의 결과에는 서버의
 * 커밋 뒤 smoke 결말(`PASSED`·`REVERTED`, 재전송에서 원장에 결말이 아직 없으면 `PENDING`)이 실리고, `PENDING`이면 operation
 * 조회로 이어 가도록 그 operation을 남긴다. 목록이 서버의 지금과 달라졌을 수 있는 결말(완료·닫힌 제한·새 신호·권한 회수)에는
 * `onListChanged`를 부른다. 값은 메모리에만 있다.
 */

export type RecoveryPhase = OperatorCommandPhase

/** 목록을 다시 읽어야 하는 거절. 확정·준비 순간 제한의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'RESTRICTION_NOT_ACTIVE',
  'TARGET_INVALID',
  'NEW_SIGNAL_SINCE_PREPARE',
])

const RECOVERY: OperatorCommandSpec<RecoveryInput, PreparedRecovery, CompletedRecovery> = {
  commandOf: () => LIFT_RESTRICTION_COMMAND,
  bodyOf: recoveryBodyOf,
  keyOf: (input) =>
    [
      input.restrictionId,
      input.reason,
      input.verification,
      input.evidenceRef,
      input.deployVersion,
      input.conditions.join(','),
    ].join('\n'),
  preparedOf: (body, input) => preparedRecoveryOf(body, input.restrictionId),
  completedOf: completedRecoveryOf,
  prepareListCodes: LIST_CHANGING_CODES,
  confirmListCodes: LIST_CHANGING_CODES,
  pendingOperationOf: (completed) =>
    completed.smoke.status === 'PENDING' ? completed.operationId : null,
}

export type OperatorRecoveryAccess = OperatorCommandAccess<
  RecoveryInput,
  PreparedRecovery,
  CompletedRecovery
>

export function useOperatorRecovery(
  csrfToken: string | null,
  onListChanged: () => void,
): OperatorRecoveryAccess {
  return useOperatorCommand(RECOVERY, csrfToken, { onListChanged })
}
