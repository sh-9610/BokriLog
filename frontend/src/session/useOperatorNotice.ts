import { useCallback, useEffect, useState } from 'react'

import { PUBLISH_NOTICE_COMMAND } from '../content/operator'
import {
  completedNoticeOf,
  fetchNoticeIncidents,
  noticeBodyOf,
  preparedNoticeOf,
  type CompletedNotice,
  type NoticeIncident,
  type NoticeInput,
  type PreparedNotice,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'
import { useSequencedRead } from './useSequencedRead'

/**
 * 서비스 공지 발행의 준비 → 확인 → 확정·취소와 사건 목록(Story 3.5). 상태 기계는 공통 훅(`useOperatorCommand`)이다.
 *
 * 준비는 아무것도 쓰지 않는다 — 서버가 사건·덮는 제한·다음 안내 시각을 보고 허가를 발급한 뒤, 덮는 제한에서 계산한 영향·
 * 계속 가능 기능의 미리보기와 확인 문구를 준다. 멱등 키는 한 입력(사건·상태·덮는 제한·메시지·다음 안내 시각)마다 한
 * 번이다. 사건 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 목록이 서버의 지금과 달라졌을 수 있는 결말(완료·번호 충돌·
 * 닫힌 사건·대상 오류·연락처 없음)에 다시 읽는다. 목록의 404는 이 운영자에게 command 권한이 없다는 답이다. 값은 메모리에만
 * 있다.
 */

export type NoticePhase = OperatorCommandPhase

export type NoticeListStatus = 'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface NoticeList {
  readonly status: NoticeListStatus
  readonly items: readonly NoticeIncident[]
  readonly supportContactConfigured: boolean
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

export interface OperatorNoticeAccess extends OperatorCommandAccess<
  NoticeInput,
  PreparedNotice,
  CompletedNotice
> {
  /** 마지막으로 준비한 공지 상태. 같은 거절 code의 문장을 가른다(복구 공지의 대상 거절). */
  readonly requestedStatus: string | null
  readonly list: NoticeList
  readonly reloadList: () => void
}

const CHECKING: NoticeList = {
  status: 'checking',
  items: [],
  supportContactConfigured: false,
  everLoaded: false,
  loading: true,
}

/** 사건 목록을 다시 읽어야 하는 거절. 준비·확정 순간 사건·제한의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'NOTICE_VERSION_CONFLICT',
  'INCIDENT_CLOSED',
  'TARGET_INVALID',
  'SUPPORT_CONTACT_UNCONFIGURED',
])

const NOTICE: OperatorCommandSpec<NoticeInput, PreparedNotice, CompletedNotice> = {
  commandOf: () => PUBLISH_NOTICE_COMMAND,
  bodyOf: noticeBodyOf,
  keyOf: (input) =>
    [
      input.incident,
      input.status,
      [...input.covers].sort().join(','),
      input.message,
      input.nextUpdateAt ?? '-',
    ].join('\n'),
  preparedOf: (body, input) => preparedNoticeOf(body, input.incident),
  completedOf: completedNoticeOf,
  prepareListCodes: LIST_CHANGING_CODES,
  confirmListCodes: LIST_CHANGING_CODES,
}

export function useOperatorNotice(ready: boolean, csrfToken: string | null): OperatorNoticeAccess {
  const [list, setList] = useState<NoticeList>(CHECKING)
  const reads = useSequencedRead()

  const readList = useCallback((): void => {
    const ticket = reads.begin()
    void fetchNoticeIncidents(ticket.signal).then((result) => {
      if (!ticket.settle()) {
        return
      }
      setList((current) => {
        switch (result.status) {
          case 'loaded':
            return {
              status: 'loaded',
              items: result.value.items,
              supportContactConfigured: result.value.supportContactConfigured,
              everLoaded: true,
              loading: false,
            }
          case 'notFound':
            // command 권한이 없거나 회수됐다. 보던 사건도 남기지 않는다.
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

  /** 다시 조회. 도는 동안 목록 자리가 그 사실을 말한다. */
  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  const command = useOperatorCommand(NOTICE, csrfToken, { onListChanged: readList })
  return { ...command, requestedStatus: command.input?.status ?? null, list, reloadList }
}
