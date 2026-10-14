import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { useSequencedRead } from './useSequencedRead'

/** 목록 읽기의 순번·중단 도우미. 새 읽기가 앞의 것을 중단하고, 늦은 응답은 버리며, `stop`이 남은 읽기를 모두 끝낸다. */
describe('useSequencedRead', () => {
  it('새 읽기는 앞의 읽기를 중단하고 앞의 늦은 응답을 버린다', () => {
    const { result } = renderHook(() => useSequencedRead())
    const first = result.current.begin()
    const second = result.current.begin()

    expect(first.signal.aborted).toBe(true)
    expect(second.signal.aborted).toBe(false)
    expect(first.settle()).toBe(false)
    expect(second.settle()).toBe(true)
  })

  it('stop은 도는 읽기를 중단하고 이미 시작한 읽기의 응답을 모두 버린다', () => {
    const { result } = renderHook(() => useSequencedRead())
    const pending = result.current.begin()
    result.current.stop()

    expect(pending.signal.aborted).toBe(true)
    expect(pending.settle()).toBe(false)
    // stop 뒤의 새 읽기는 다시 마지막 것이다.
    const fresh = result.current.begin()
    expect(fresh.settle()).toBe(true)
  })

  it('끝난 읽기를 내려 놓은 뒤의 새 읽기는 그것을 중단하지 않는다', () => {
    const { result } = renderHook(() => useSequencedRead())
    const done = result.current.begin()
    expect(done.settle()).toBe(true)
    result.current.begin()
    expect(done.signal.aborted).toBe(false)
  })

  it('다시 그려도 같은 도우미다', () => {
    const { result, rerender } = renderHook(() => useSequencedRead())
    const before = result.current
    rerender()
    expect(result.current).toBe(before)
  })
})
