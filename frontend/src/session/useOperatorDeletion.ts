import { useCallback, useEffect, useState } from 'react'

import { REQUEUE_DELETION_COMMAND } from '../content/operator'
import {
  completedDeletionRequeueOf,
  deletionRequeueBodyOf,
  fetchRetryNeededDeletions,
  preparedDeletionRequeueOf,
  type CompletedDeletionRequeue,
  type DeletionRequeueInput,
  type PreparedDeletionRequeue,
  type ReadResult,
  type RetryNeededDeletion,
  type RetryNeededDeletionList,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'
import { useSequencedRead } from './useSequencedRead'

/**
 * 재시도 필요로 멈춘 계정 삭제 실행의 목록과 재대기열의 준비 → 확인 → 확정·취소(Epic 5 회고 AI-1). 상태 기계는 공통 훅
 * (`useOperatorCommand`)이다.
 *
 * 준비는 아무것도 바꾸지 않는다 — 서버가 그 실행이 아직 재시도 필요인지 보고 허가를 발급한 뒤 실패 단계의 미리보기와 확인 문구를
 * 준다. 멱등 키는 한 입력(삭제 요청 ID·사유)마다 한 번이다. 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 목록이 서버의 지금과
 * 달라졌을 수 있는 결말(완료·재시도 필요가 아님·대상 없음·이 환경에서 불가·권한 회수)에 다시 읽는다. 목록의 404는 command 권한이
 * 없다는 답이다. 서버가 이 환경에서 삭제를 실행하지 않는다고 답하면(`available: false`) 목록과 동작을 그리지 않는다. 값은 메모리에만
 * 있다.
 */

export type DeletionPhase = OperatorCommandPhase

export type DeletionListStatus = 'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface DeletionList {
  readonly status: DeletionListStatus
  /**
   * 이 환경이 삭제를 실행하는가. 성공한 읽기가 정한다 — 그 전에는 `null`(모른다)이고 화면은 설명도 불가 안내도 그리지 않는다. 그
   * 뒤의 실패는 마지막으로 읽은 값을 그대로 둔다.
   */
  readonly available: boolean | null
  /** 서버가 목록을 상한에서 잘랐다 — 오래된 순 앞쪽만 보인다. */
  readonly truncated: boolean
  readonly items: readonly RetryNeededDeletion[]
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

/** 목록을 다시 읽어야 하는 거절. 준비·확정 순간 그 실행의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'DELETION_NOT_RETRYABLE',
  'DELETION_REQUEUE_UNAVAILABLE',
  'TARGET_INVALID',
])

const REQUEUE: OperatorCommandSpec<
  DeletionRequeueInput,
  PreparedDeletionRequeue,
  CompletedDeletionRequeue
> = {
  commandOf: () => REQUEUE_DELETION_COMMAND,
  bodyOf: deletionRequeueBodyOf,
  keyOf: (input) => `${input.requestId}\n${input.reason}`,
  preparedOf: (body, input) => preparedDeletionRequeueOf(body, input.requestId),
  completedOf: completedDeletionRequeueOf,
  prepareListCodes: LIST_CHANGING_CODES,
  confirmListCodes: LIST_CHANGING_CODES,
}

export interface OperatorDeletionAccess extends OperatorCommandAccess<
  DeletionRequeueInput,
  PreparedDeletionRequeue,
  CompletedDeletionRequeue
> {
  readonly list: DeletionList
  readonly reloadList: () => void
}

const CHECKING: DeletionList = {
  status: 'checking',
  available: null,
  truncated: false,
  items: [],
  everLoaded: false,
  loading: true,
}

export function useOperatorDeletion(
  ready: boolean,
  csrfToken: string | null,
): OperatorDeletionAccess {
  const [list, setList] = useState<DeletionList>(CHECKING)
  const reads = useSequencedRead()

  const readList = useCallback((): void => {
    const ticket = reads.begin()
    void fetchRetryNeededDeletions(ticket.signal)
      // 전송층이 거부로 끝나도 읽는 중에 갇히지 않는다 — 실패로 접는다.
      .catch((): ReadResult<RetryNeededDeletionList> => ({ status: 'failed', code: null }))
      .then((result) => {
        if (!ticket.settle()) {
          return
        }
        setList((current) => {
          switch (result.status) {
            case 'loaded':
              return {
                status: 'loaded',
                available: result.value.available,
                truncated: result.value.truncated,
                items: result.value.items,
                everLoaded: true,
                loading: false,
              }
            case 'notFound':
              // command 권한이 없거나 회수됐다. 보던 행도 남기지 않는다.
              return { ...CHECKING, status: 'notFound', loading: false }
            case 'unauthenticated':
              return { ...CHECKING, status: 'unauthenticated', loading: false }
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

  /** 사용자가 누른 다시 조회와 결말 뒤의 다시 읽기. 도는 동안 목록 자리가 그 사실을 말한다. */
  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  const command = useOperatorCommand(REQUEUE, csrfToken, { onListChanged: reloadList })
  return { ...command, list, reloadList }
}
