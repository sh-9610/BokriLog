import { useCallback, useEffect, useState } from 'react'

import type { RestrictionCommand } from '../content/operator'

import {
  completedRestrictionOf,
  fetchRestrictions,
  preparedRestrictionOf,
  type ActiveRestriction,
  type CompletedRestriction,
  type PreparedRestriction,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'
import { useSequencedRead } from './useSequencedRead'

/**
 * 영향 범위 제한의 준비 → 확인 → 확정·취소와 활성 제한 목록(Story 3.2).
 *
 * 상태 기계는 공통 훅(`useOperatorCommand`)이다. 준비는 아무것도 막지 않는다 — 서버가 대상을 확인하고 허가를 발급한 뒤
 * 영향 미리보기와 확인 문구를 준다. 멱등 키는 한 입력(명령·범위·대상·사유)마다 한 번이다. 목록은 운영자임이 확인된 뒤
 * (`ready`) 한 번 읽고, 확정이 끝나거나 목록이 달라졌다는 거절(같은 제한·대상 없음·권한 회수)에 다시 읽는다. 목록의 404는
 * 이 운영자에게 제한 권한이 없다는 답이다 — 화면은 제한 영역을 그리지 않는다(fail-closed). 값은 메모리에만 있다.
 */

export type RestrictionPhase = OperatorCommandPhase

export type RestrictionListStatus =
  'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface RestrictionList {
  readonly status: RestrictionListStatus
  readonly items: readonly ActiveRestriction[]
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

export interface RestrictionInput {
  readonly command: RestrictionCommand
  readonly scope: string
  readonly target: string
  readonly reason: string
}

const RESTRICTION: OperatorCommandSpec<
  RestrictionInput,
  PreparedRestriction,
  CompletedRestriction
> = {
  commandOf: (input) => input.command,
  bodyOf: (input) => ({ scope: input.scope, target: input.target, reason: input.reason }),
  keyOf: (input) => `${input.command}\n${input.scope}\n${input.target}\n${input.reason}`,
  preparedOf: (body, input) => preparedRestrictionOf(body, input.command),
  completedOf: completedRestrictionOf,
  // 준비가 이미 효력 중인 같은 제한을 알려 왔다. 목록이 그것을 보이도록 다시 읽는다.
  prepareListCodes: new Set(['ALREADY_RESTRICTED']),
  // 확정 순간 같은 제한이 생겼거나 대상이 사라졌다. 목록이 서버의 지금을 보이게 한다.
  confirmListCodes: new Set(['ALREADY_RESTRICTED', 'TARGET_INVALID']),
}

export interface OperatorRestrictionAccess extends OperatorCommandAccess<
  RestrictionInput,
  PreparedRestriction,
  CompletedRestriction
> {
  readonly list: RestrictionList
  readonly reloadList: () => void
}

const CHECKING: RestrictionList = {
  status: 'checking',
  items: [],
  everLoaded: false,
  loading: true,
}

export function useOperatorRestriction(
  ready: boolean,
  csrfToken: string | null,
): OperatorRestrictionAccess {
  const [list, setList] = useState<RestrictionList>(CHECKING)
  const reads = useSequencedRead()

  const readList = useCallback((): void => {
    const ticket = reads.begin()
    void fetchRestrictions(ticket.signal).then((result) => {
      if (!ticket.settle()) {
        return
      }
      setList((current) => {
        switch (result.status) {
          case 'loaded':
            return { status: 'loaded', items: result.value, everLoaded: true, loading: false }
          case 'notFound':
            // 제한 권한이 없거나 회수됐다. 보던 행도 남기지 않는다.
            return { status: 'notFound', items: [], everLoaded: false, loading: false }
          case 'unauthenticated':
            return { status: 'unauthenticated', items: [], everLoaded: false, loading: false }
          case 'failed':
            // 마지막으로 읽은 목록을 버리지 않는다. 실패는 목록 자리에서만 말한다.
            return { ...current, status: 'failed', loading: false }
        }
      })
    })
  }, [reads])

  // 첫 읽기는 게이트가 열린 순간 한 번이다. 요청 시작은 커밋이 보여 주는 결과가 아니므로 passive effect다.
  useEffect(() => {
    if (!ready) {
      return
    }
    readList()
    return () => {
      reads.stop()
    }
  }, [ready, readList, reads])

  /** 사용자가 누른 다시 조회. 도는 동안 목록 자리가 그 사실을 말한다. */
  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  const command = useOperatorCommand(RESTRICTION, csrfToken, { onListChanged: reloadList })
  return { ...command, list, reloadList }
}
