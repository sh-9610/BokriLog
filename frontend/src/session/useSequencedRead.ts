import { useMemo, useRef } from 'react'

/**
 * 목록 읽기의 순번과 중단 수단 — 운영자 화면의 목록·상세·집계가 같이 쓰는 도우미다(Epic 3 회고 AI-5).
 *
 * 새 읽기는 도는 앞의 읽기를 중단하고 번호를 올린다. 응답이 왔을 때 그 읽기가 여전히 마지막 것인지(`settle`)를 물어 늦은
 * 응답을 버린다. 화면을 떠나거나 게이트가 닫히면 `stop`이 도는 읽기를 끊고 번호를 올려 남은 응답을 모두 버린다.
 */
export interface SequencedRead {
  /** 앞의 읽기를 중단하고 새 읽기를 시작한다. */
  readonly begin: () => ReadTicket
  /** 도는 읽기를 중단하고, 이미 시작한 읽기의 응답을 모두 버린다. */
  readonly stop: () => void
}

export interface ReadTicket {
  readonly signal: AbortSignal
  /** 이 읽기가 아직 마지막 것인가. 참이면 도는 읽기에서 내려 놓는다 — 응답을 반영해도 된다. */
  readonly settle: () => boolean
}

export function useSequencedRead(): SequencedRead {
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)
  return useMemo<SequencedRead>(
    () => ({
      begin: () => {
        inFlight.current?.abort()
        const controller = new AbortController()
        inFlight.current = controller
        sequence.current += 1
        const mine = sequence.current
        return {
          signal: controller.signal,
          settle: () => {
            if (mine !== sequence.current) {
              return false
            }
            inFlight.current = null
            return true
          },
        }
      },
      stop: () => {
        inFlight.current?.abort()
        sequence.current += 1
      },
    }),
    [],
  )
}
