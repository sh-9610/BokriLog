import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { AD_READY_TIMEOUT_MS, GPT_SCRIPT_SRC, showInterstitial } from './gptAdapter'

/**
 * Story 4.3·4.4: `AD-GPT-1.0` adapter. jsdom은 외부 script를 받지 않으므로 GPT는 대역이다 — 시험이 load된 GPT처럼 command
 * queue를 처리해, adapter가 부르는 순서와 부르지 않는 것(targeting), 그리고 결론(준비·실패·취소)이 한 번인지를 본다. 시한은
 * 가짜 시계로 센다. 실제 소재·request field 검사는 출시 게이트 G-4다.
 */

const AD_UNIT_PATH = '/1234567/bokrilog_test'
const INTERSTITIAL = 5

interface Host {
  googletag?: unknown
}

type SlotEventType = 'slotOnload' | 'slotRenderEnded'
type Listener = (event: unknown) => void

interface FakeGpt {
  /** adapter가 GPT에 부른 것, 순서대로. */
  readonly calls: string[]
  /** 대역이 정의해 준 slot. */
  readonly slot: object
  /** 지금 붙어 있는 리스너 수. */
  attached(): number
  /**
   * 이벤트를 보낸다. 떼어 낸 리스너에도 보낸다 — GPT가 떼기 전에 잡아 둔 늦은 callback이 와도 adapter가 아무것도 부르지 않는지
   * 보기 위한 최악의 경우다.
   */
  fire(type: SlotEventType, event: object): void
}

function gptScripts(): HTMLScriptElement[] {
  return [...document.querySelectorAll<HTMLScriptElement>('script')].filter(
    (script) => script.src === GPT_SCRIPT_SRC,
  )
}

/** 대역이 기록한 뒤 던지게 할 GPT 메서드. */
type ThrowingMethod = 'removeEventListener' | 'destroySlots' | 'enableServices' | 'display'

interface GptOptions {
  /** `null`이면 이 기기에서 interstitial을 지원하지 않는 GPT다. */
  readonly slotKind?: 'slot' | null
  /** 부른 것을 기록한 뒤 던지는 메서드. */
  readonly throwIn?: readonly ThrowingMethod[]
}

/** load된 GPT를 흉내 낸다: 쌓인 command를 그 자리에서 처리하는 `googletag`로 바꾼다. 부른 것을 순서대로 남긴다. */
function loadGpt({ slotKind = 'slot', throwIn = [] }: GptOptions = {}): FakeGpt {
  const maybeThrow = (method: ThrowingMethod): void => {
    if (throwIn.includes(method)) throw new Error(`${method} broken`)
  }
  const calls: string[] = []
  const attached = new Map<string, Listener>()
  const everAttached: { type: SlotEventType; listener: Listener }[] = []
  const host = window as unknown as Host
  const queued = (host.googletag as { cmd: (() => void)[] }).cmd
  const pubads = {
    setPrivacySettings: (settings: unknown) => {
      calls.push(`setPrivacySettings ${JSON.stringify(settings)}`)
    },
    addEventListener: (type: SlotEventType, listener: Listener) => {
      calls.push(`addEventListener ${type}`)
      attached.set(type, listener)
      everAttached.push({ type, listener })
    },
    removeEventListener: (type: SlotEventType, listener: Listener) => {
      calls.push(`removeEventListener ${type}`)
      maybeThrow('removeEventListener')
      if (attached.get(type) === listener) attached.delete(type)
      return true
    },
  }
  const slot = {
    addService: (service: unknown) => {
      calls.push(`addService ${String(service === pubads)}`)
      return slot
    },
  }
  host.googletag = {
    cmd: {
      push: (callback: () => void) => {
        callback()
      },
    },
    enums: { OutOfPageFormat: { INTERSTITIAL } },
    pubads: () => pubads,
    defineOutOfPageSlot: (path: string, format: number) => {
      calls.push(`defineOutOfPageSlot ${path} ${String(format)}`)
      return slotKind === null ? null : slot
    },
    enableServices: () => {
      calls.push('enableServices')
      maybeThrow('enableServices')
    },
    display: (target: unknown) => {
      calls.push(`display ${String(target === slot)}`)
      maybeThrow('display')
    },
    destroySlots: (slots: unknown[]) => {
      calls.push(`destroySlots ${String(slots.length === 1 && slots[0] === slot)}`)
      maybeThrow('destroySlots')
      return true
    },
  }
  for (const command of queued) command()
  return {
    calls,
    slot,
    attached: () => attached.size,
    fire: (type, event) => {
      for (const entry of everAttached) if (entry.type === type) entry.listener(event)
    },
  }
}

/** GPT가 load되기 전 command queue에 쌓인 command 수. */
function pendingCommands(): number {
  return ((window as unknown as Host).googletag as { cmd: unknown[] }).cmd.length
}

const DISPLAY_SEQUENCE = [
  'setPrivacySettings {"nonPersonalizedAds":true}',
  `defineOutOfPageSlot ${AD_UNIT_PATH} ${String(INTERSTITIAL)}`,
  'addService true',
  'addEventListener slotOnload',
  'addEventListener slotRenderEnded',
  'enableServices',
  'display true',
]

const RELEASE_SEQUENCE = [
  'removeEventListener slotOnload',
  'removeEventListener slotRenderEnded',
  'destroySlots true',
]

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
})

afterEach(() => {
  vi.useRealTimers()
  for (const script of gptScripts()) script.remove()
  delete (window as unknown as Host).googletag
})

/** 실패 뒤에도 GPT script는 하나이고 display는 많아야 한 번이며, 요청·저장소는 0건이다(재시도 없음). */
function expectNoRetry(gpt: FakeGpt | null): void {
  expect(gptScripts()).toHaveLength(1)
  if (gpt !== null) {
    for (const name of ['defineOutOfPageSlot', 'display']) {
      expect(gpt.calls.filter((call) => call.startsWith(name)).length).toBeLessThanOrEqual(1)
    }
  }
  expect(getObservedRequests()).toEqual([])
  expect(getObservedStorageEntries()).toEqual([])
}

describe('GPT web interstitial adapter: 준비', () => {
  it('준비 시한은 3초다(바꾸려면 SPEC Ask First)', () => {
    expect(AD_READY_TIMEOUT_MS).toBe(3000)
  })

  it('GPT를 script 요소로 load하고, 비개인화 설정 → interstitial slot → 리스너 → 서비스 → display 순서로 부르며 targeting이 없다', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)

    const scripts = gptScripts()
    expect(scripts).toHaveLength(1)
    expect(scripts[0]?.async).toBe(true)
    // 그 자리에서는 아무것도 부르지 않는다 — GPT가 load된 뒤 command queue가 처리한다.
    expect(pendingCommands()).toBe(1)
    const gpt = loadGpt()
    expect(gpt.calls).toEqual(DISPLAY_SEQUENCE)
    expect(onFailure).not.toHaveBeenCalled()
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  // 준비는 준비 시한만 해제한다 — adapter가 준비 시한 뒤 스스로 실패하지 않는다는 사실만 고정한다. 준비된 광고의 수명 상한은
  // adapter 계약 밖이고 화면이 소비 창(`AD_CLAIM_WINDOW_SECONDS`)에 취소로 건다(`AdTransitionScreen`, Epic 4 회고 AI-1).
  it('3초 안에 우리 slot이 준비되면 준비 시한이 지나도 실패하지 않는다 — 수명 상한은 adapter 밖(화면)', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()

    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS - 1)
    gpt.fire('slotOnload', { slot: gpt.slot })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS * 2)
    // 미채움이 아닌 render 종료도 아무것도 바꾸지 않는다.
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: false })

    expect(onFailure).not.toHaveBeenCalled()
    expect(gpt.calls).toEqual(DISPLAY_SEQUENCE)
    expect(gpt.attached()).toBe(2)
  })

  it('준비 뒤에도 미채움이면 한 번 실패하고 slot을 파기한다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()
    gpt.fire('slotOnload', { slot: gpt.slot })

    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expect(gpt.attached()).toBe(0)
    expectNoRetry(gpt)
  })
})

describe('GPT web interstitial adapter: 실패', () => {
  it('3초 안에 준비 신호가 없으면 한 번 실패하고 리스너를 떼고 slot을 파기한다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()

    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS - 1)
    expect(onFailure).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expect(gpt.attached()).toBe(0)

    // 늦은 callback: 준비·미채움·시한 어느 것도 아무것도 부르지 않는다.
    gpt.fire('slotOnload', { slot: gpt.slot })
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS * 2)
    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expectNoRetry(gpt)
  })

  it('GPT가 3초 안에 load되지 않으면 실패하고, 늦게 load된 GPT의 command는 아무것도 부르지 않는다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)

    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    expect(onFailure).toHaveBeenCalledTimes(1)

    const gpt = loadGpt()
    expect(gpt.calls).toEqual([])
    expect(onFailure).toHaveBeenCalledTimes(1)
    expectNoRetry(gpt)
  })

  it('준비 전 미채움은 한 번 실패하고 slot을 파기한다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()

    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    gpt.fire('slotOnload', { slot: gpt.slot })

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expectNoRetry(gpt)
  })

  it('다른 slot의 준비·미채움은 무시한다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()
    const other = {}

    gpt.fire('slotRenderEnded', { slot: other, isEmpty: true })
    expect(onFailure).not.toHaveBeenCalled()
    // 남의 slot이 준비돼도 우리 시한은 그대로 센다.
    gpt.fire('slotOnload', { slot: other })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expect(gpt.attached()).toBe(0)
    expectNoRetry(gpt)
  })

  it('slot이 null이면 한 번 실패를 알리고 서비스를 켜지 않으며, 시한도 다시 실패하지 않는다', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt({ slotKind: null })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).not.toContain('enableServices')
    expect(gpt.calls).not.toContain('display true')
    // 파기할 slot이 없다.
    expect(gpt.calls.some((call) => call.startsWith('destroySlots'))).toBe(false)
    expectNoRetry(gpt)
  })

  it('script load 오류(차단)는 한 번 실패를 알리고, 그 뒤의 시한·늦은 GPT는 아무것도 부르지 않는다', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)
    gptScripts()[0]?.dispatchEvent(new Event('error'))
    gptScripts()[0]?.dispatchEvent(new Event('error'))
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    expect(onFailure).toHaveBeenCalledTimes(1)
    const gpt = loadGpt()
    expect(gpt.calls).toEqual([])
    expectNoRetry(gpt)
  })

  it('정의 중 예외도 한 번 실패를 알린다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const host = window as unknown as Host
    const queued = (host.googletag as { cmd: (() => void)[] }).cmd
    host.googletag = {
      cmd: {
        push: (callback: () => void) => {
          callback()
        },
      },
      enums: { OutOfPageFormat: { INTERSTITIAL } },
      pubads: () => {
        throw new Error('broken')
      },
    }

    for (const command of queued) command()
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expectNoRetry(null)
  })

  it.each(['enableServices', 'display'] as const)(
    'slot 정의 뒤 %s에서 예외가 나면 한 번 실패하고 리스너 둘을 떼고 slot을 파기한다',
    (method) => {
      const onFailure = vi.fn()
      showInterstitial(AD_UNIT_PATH, onFailure)

      const gpt = loadGpt({ throwIn: [method] })
      vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

      expect(onFailure).toHaveBeenCalledTimes(1)
      const thrownAt = DISPLAY_SEQUENCE.findIndex((call) => call.startsWith(method))
      expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE.slice(0, thrownAt + 1), ...RELEASE_SEQUENCE])
      expect(gpt.attached()).toBe(0)
      expectNoRetry(gpt)
    },
  )
})

describe('GPT web interstitial adapter: 정리 단계의 예외', () => {
  it('리스너 제거가 던져도 slot은 파기하고, 시한초과의 실패는 한 번이다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt({ throwIn: ['removeEventListener'] })

    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expectNoRetry(gpt)
  })

  it('slot 파기가 던져도 미채움의 실패는 한 번이다', () => {
    const onFailure = vi.fn()
    showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt({ throwIn: ['destroySlots'] })

    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expectNoRetry(gpt)
  })

  it('정리 단계가 모두 던져도 취소는 던지지 않고 실패를 알리지 않는다', () => {
    const onFailure = vi.fn()
    const cancel = showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt({ throwIn: ['removeEventListener', 'destroySlots'] })

    expect(() => {
      cancel()
    }).not.toThrow()
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })

    expect(onFailure).not.toHaveBeenCalled()
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
  })
})

describe('GPT web interstitial adapter: 취소', () => {
  it('display 뒤 취소하면 리스너를 떼고 slot을 파기하며, 그 뒤 어떤 callback도 실패를 알리지 않는다', () => {
    const onFailure = vi.fn()
    const cancel = showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()

    cancel()
    cancel()

    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expect(gpt.attached()).toBe(0)
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })
    gpt.fire('slotOnload', { slot: gpt.slot })
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS * 2)
    gptScripts()[0]?.dispatchEvent(new Event('error'))
    expect(onFailure).not.toHaveBeenCalled()
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expectNoRetry(gpt)
  })

  it('준비 뒤 취소해도 실패를 알리지 않고 slot을 파기한다', () => {
    const onFailure = vi.fn()
    const cancel = showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()
    gpt.fire('slotOnload', { slot: gpt.slot })

    cancel()
    gpt.fire('slotRenderEnded', { slot: gpt.slot, isEmpty: true })

    expect(onFailure).not.toHaveBeenCalled()
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
    expect(gpt.attached()).toBe(0)
    expectNoRetry(gpt)
  })

  it('GPT load 전에 취소하면 늦게 처리되는 command는 아무것도 부르지 않는다', () => {
    const onFailure = vi.fn()
    const cancel = showInterstitial(AD_UNIT_PATH, onFailure)

    cancel()
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)
    const gpt = loadGpt()

    expect(gpt.calls).toEqual([])
    expect(onFailure).not.toHaveBeenCalled()
    expectNoRetry(gpt)
  })

  it('실패 뒤 취소는 아무것도 다시 부르지 않는다', () => {
    const onFailure = vi.fn()
    const cancel = showInterstitial(AD_UNIT_PATH, onFailure)
    const gpt = loadGpt()
    vi.advanceTimersByTime(AD_READY_TIMEOUT_MS)

    cancel()

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gpt.calls).toEqual([...DISPLAY_SEQUENCE, ...RELEASE_SEQUENCE])
  })
})
