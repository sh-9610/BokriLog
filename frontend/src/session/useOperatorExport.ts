import { useCallback } from 'react'

import { AUDIT_EXPORT_COMMAND, AUDIT_LEDGER_SCOPE } from '../content/operator'
import {
  completedExportOf,
  EXPORT_CONFIRM_TIMEOUT_MS,
  preparedExportOf,
  type CompletedExport,
  type PreparedExport,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'

/**
 * 감사 원장 내보내기의 준비 → 확인 → 확정·취소(Story 3.1). 상태 기계는 공통 훅(`useOperatorCommand`)이고 여기에는 이
 * command의 설정만 있다 — 입력은 기간·사유이고 멱등 키는 한 입력(기간·사유)마다 한 번이다. 확정은 최대 10,000행을 읽으므로
 * 시한이 길고, 완료되면 감사 조회를 다시 읽는다(`onCompleted`).
 */

export type ExportPhase = OperatorCommandPhase

export interface ExportInput {
  readonly target: string
  readonly reason: string
}

const EXPORT: OperatorCommandSpec<ExportInput, PreparedExport, CompletedExport> = {
  commandOf: () => AUDIT_EXPORT_COMMAND,
  bodyOf: (input) => ({ scope: AUDIT_LEDGER_SCOPE, target: input.target, reason: input.reason }),
  keyOf: (input) => `${input.target}\n${input.reason}`,
  preparedOf: (body) => preparedExportOf(body),
  completedOf: completedExportOf,
  confirmTimeoutMs: EXPORT_CONFIRM_TIMEOUT_MS,
}

export interface OperatorExportAccess extends Omit<
  OperatorCommandAccess<ExportInput, PreparedExport, CompletedExport>,
  'prepare'
> {
  readonly prepare: (target: string, reason: string) => void
}

export function useOperatorExport(
  csrfToken: string | null,
  onCompleted: () => void,
): OperatorExportAccess {
  const command = useOperatorCommand(EXPORT, csrfToken, { onCompleted })
  const { prepare: prepareInput } = command
  const prepare = useCallback(
    (target: string, reason: string): void => {
      prepareInput({ target, reason })
    },
    [prepareInput],
  )
  return { ...command, prepare }
}
