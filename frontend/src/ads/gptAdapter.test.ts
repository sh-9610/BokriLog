import { afterEach, describe, expect, it, vi } from 'vitest'

import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { GPT_SCRIPT_SRC, showInterstitial } from './gptAdapter'

/**
 * Story 4.3: `AD-GPT-1.0` adapter. jsdom은 외부 script를 받지 않으므로 GPT는 대역이다 — 시험이 load된 GPT처럼 command queue를
 * 처리해, adapter가 부르는 순서와 부르지 않는 것(targeting)을 본다. 실제 소재·request field 검사는 출시 게이트 G-4다.
 */

const AD_UNIT_PATH = '/1234567/bokrilog_test'
const INTERSTITIAL = 5

interface Host {
  googletag?: unknown
}

function gptScripts(): HTMLScriptElement[] {
  return [...document.querySelectorAll<HTMLScriptElement>('script')].filter(
    (script) => script.src === GPT_SCRIPT_SRC,
  )
}

/** load된 GPT를 흉내 낸다: 쌓인 command를 그 자리에서 처리하는 `googletag`로 바꾼다. 부른 것을 순서대로 남긴다. */
function loadGpt(slot: 'slot' | null): string[] {
  const calls: string[] = []
  const host = window as unknown as Host
  const queued = (host.googletag as { cmd: (() => void)[] }).cmd
  const pubads = {
    setPrivacySettings: (settings: unknown) => {
      calls.push(`setPrivacySettings ${JSON.stringify(settings)}`)
    },
  }
  const definedSlot = {
    addService: (service: unknown) => {
      calls.push(`addService ${String(service === pubads)}`)
      return definedSlot
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
      return slot === null ? null : definedSlot
    },
    enableServices: () => {
      calls.push('enableServices')
    },
    display: (target: unknown) => {
      calls.push(`display ${String(target === definedSlot)}`)
    },
  }
  for (const command of queued) command()
  return calls
}

afterEach(() => {
  for (const script of gptScripts()) script.remove()
  delete (window as unknown as Host).googletag
})

describe('GPT web interstitial adapter', () => {
  it('GPT를 script 요소로 load하고, 비개인화 설정 → interstitial slot → 서비스 → display 순서로 부르며 targeting이 없다', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)

    const scripts = gptScripts()
    expect(scripts).toHaveLength(1)
    expect(scripts[0]?.async).toBe(true)
    // 그 자리에서는 아무것도 부르지 않는다 — GPT가 load된 뒤 command queue가 처리한다.
    const calls = loadGpt('slot')
    expect(calls).toEqual([
      'setPrivacySettings {"nonPersonalizedAds":true}',
      `defineOutOfPageSlot ${AD_UNIT_PATH} ${String(INTERSTITIAL)}`,
      'addService true',
      'enableServices',
      'display true',
    ])
    expect(onFailure).not.toHaveBeenCalled()
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('slot이 null이면 한 번 실패를 알리고 서비스를 켜지 않는다', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)
    const calls = loadGpt(null)

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(calls).not.toContain('enableServices')
  })

  it('script load 오류는 한 번 실패를 알린다(재시도 없음)', () => {
    const onFailure = vi.fn()

    showInterstitial(AD_UNIT_PATH, onFailure)
    gptScripts()[0]?.dispatchEvent(new Event('error'))
    gptScripts()[0]?.dispatchEvent(new Event('error'))

    expect(onFailure).toHaveBeenCalledTimes(1)
    expect(gptScripts()).toHaveLength(1)
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

    expect(onFailure).toHaveBeenCalledTimes(1)
  })
})
