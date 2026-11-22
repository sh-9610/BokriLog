/*
 * 대시보드용 GPT web interstitial 슬롯을 준비한다. Google이 실제 앵커의 클릭을 처리한다.
 * 수동 제한 광고를 요청하고 광고 단위 경로만 전달한다. 소재와 실제 제한 광고 게재는 출시 검증에서 확인한다.
 */

import type { AdapterFailureReason } from '../telemetry/beacon'

/** adapter가 감지하는 실패의 사유(Story 4.5). 계측 어휘에서 화면이 정하는 소비 창 경과(`WINDOW_EXPIRED`)를 뺀 것이다. */
export type AdFailure = AdapterFailureReason

/** 정리 한 단계를 시도한다. 던져도 다음 단계로 간다. */
function attempt(step: () => void): void {
  try {
    step()
  } catch {
    // 정리하지 못한 단계는 문서 unload가 거둔다. 결론은 그대로다.
  }
}

/** GPT library 주소. 공급자 문서의 값 그대로다. */
export const GPT_SCRIPT_SRC = 'https://pagead2.googlesyndication.com/tag/js/gpt.js'

/** 부른 뒤 준비(`slotOnload`)를 기다리는 시한. 넘으면 광고 없이 결과로 간다(Story 4.4). */
export const AD_READY_TIMEOUT_MS = 3000

/*
 * 이 adapter가 쓰는 `googletag` 표면만의 타입. 런타임 의존을 더하지 않으려고 `@types/google-publisher-tag` 대신 쓰는 부분만
 * 적는다.
 */
interface Slot {
  addService(service: PubAdsService): Slot
  setConfig(settings: {
    readonly interstitial: { readonly triggers: Record<string, false> }
  }): unknown
}

interface SlotEvent {
  readonly slot: Slot
}

interface SlotRenderEndedEvent extends SlotEvent {
  readonly isEmpty: boolean
}

interface SlotEventMap {
  readonly slotOnload: SlotEvent
  readonly slotRenderEnded: SlotRenderEndedEvent
}

interface PubAdsService {
  setPrivacySettings(settings: { readonly limitedAds: true }): unknown
  addEventListener<K extends keyof SlotEventMap>(
    type: K,
    listener: (event: SlotEventMap[K]) => void,
  ): unknown
  removeEventListener<K extends keyof SlotEventMap>(
    type: K,
    listener: (event: SlotEventMap[K]) => void,
  ): unknown
}

interface GoogleTag {
  readonly cmd: { push(callback: () => void): unknown }
  readonly enums: { readonly OutOfPageFormat: { readonly INTERSTITIAL: number } }
  defineOutOfPageSlot(adUnitPath: string, format: number): Slot | null
  pubads(): PubAdsService
  enableServices(): void
  display(slot: Slot): void
  destroySlots(slots: Slot[]): unknown
}

interface GoogleTagWindow {
  googletag?: GoogleTag | { cmd: (() => void)[] }
}

/**
 * 지정한 광고 단위의 전면 광고를 준비하고 슬롯 상태를 대시보드에 전달한다.
 *
 * @param adUnitPath 서버 설정 조회의 광고 단위 경로
 * @param onFailure 광고를 쓸 수 없다(시간초과·미채움·차단·slot 없음·예외)와 그 사유. 한 번만 불리고, 취소 뒤에는 불리지 않는다
 * @param onReady 광고가 준비됐다(시한 안의 `slotOnload`). 한 번만 불리고, 종단 뒤에는 불리지 않는다
 * @returns 취소. 리스너·시한을 정리하고 slot을 파기한다. 여러 번 불러도 된다
 */
export function showInterstitial(
  adUnitPath: string,
  onFailure: (reason: AdFailure) => void,
  onReady: () => void,
): () => void {
  // `pending` → `ready`(준비) → `over`(실패·취소). `over`는 끝이다.
  let state: 'pending' | 'ready' | 'over' = 'pending'
  let googletag: GoogleTag | null = null
  let pubads: PubAdsService | null = null
  let slot: Slot | null = null
  let script: HTMLScriptElement | null = null

  const onSlotOnload = (event: SlotEvent): void => {
    if (state !== 'pending' || slot === null || event.slot !== slot) return
    state = 'ready'
    window.clearTimeout(deadline)
    onReady()
  }

  const onSlotRenderEnded = (event: SlotRenderEndedEvent): void => {
    if (state === 'over' || slot === null || event.slot !== slot) return
    if (event.isEmpty) fail('NO_FILL')
  }

  /**
   * 시한·리스너를 정리하고 slot을 파기한다. 단계마다 따로 시도한다 — 앞 단계가 던져도 slot 파기는 건너뛰지 않는다. 정리 중 오류는
   * 결론을 바꾸지 않는다(화면은 이미 떠나는 중이다).
   */
  const release = (): void => {
    attempt(() => {
      window.clearTimeout(deadline)
    })
    attempt(() => {
      script?.removeEventListener('error', onScriptError)
    })
    attempt(() => {
      pubads?.removeEventListener('slotOnload', onSlotOnload)
    })
    attempt(() => {
      pubads?.removeEventListener('slotRenderEnded', onSlotRenderEnded)
    })
    attempt(() => {
      if (googletag !== null && slot !== null) googletag.destroySlots([slot])
    })
  }

  function fail(reason: AdFailure): void {
    if (state === 'over') return
    state = 'over'
    release()
    onFailure(reason)
  }

  /** script `error` — CSP·차단기가 GPT를 막았다. */
  function onScriptError(): void {
    script?.remove()
    fail('BLOCKED')
  }

  const cancel = (): void => {
    if (state === 'over') return
    state = 'over'
    release()
  }

  // 부른 순간부터 준비까지를 센다. script load도 그 안에 든다.
  const deadline = window.setTimeout(() => {
    if (state === 'pending') fail('TIMEOUT')
  }, AD_READY_TIMEOUT_MS)

  try {
    const host = window as unknown as GoogleTagWindow
    // GPT가 load되기 전에는 배열이 command queue다. load된 GPT가 같은 이름의 객체로 바꾸고 쌓인 command를 처리한다.
    host.googletag ??= { cmd: [] }
    host.googletag.cmd.push(() => {
      // 결론(실패·취소) 뒤에 처리되는 command는 아무것도 부르지 않는다.
      if (state === 'over') return
      try {
        const loaded = host.googletag as GoogleTag
        googletag = loaded
        const service = loaded.pubads()
        service.setPrivacySettings({ limitedAds: true })
        const defined = loaded.defineOutOfPageSlot(
          adUnitPath,
          loaded.enums.OutOfPageFormat.INTERSTITIAL,
        )
        // 이 페이지·기기에서 interstitial을 지원하지 않으면 GPT가 `null`을 준다.
        if (defined === null) {
          fail('UNSUPPORTED')
          return
        }
        slot = defined
        defined.setConfig({
          interstitial: {
            triggers: {
              unhideWindow: false,
              navBar: false,
              inactivity: false,
              endOfArticle: false,
              continueReading: false,
              backward: false,
            },
          },
        })
        defined.addService(service)
        pubads = service
        service.addEventListener('slotOnload', onSlotOnload)
        service.addEventListener('slotRenderEnded', onSlotRenderEnded)
        loaded.enableServices()
        loaded.display(defined)
      } catch {
        fail('ERROR')
      }
    })
    const existing = document.head.querySelector<HTMLScriptElement>(
      `script[src="${GPT_SCRIPT_SRC}"]`,
    )
    const element = existing ?? document.createElement('script')
    element.addEventListener('error', onScriptError, { once: true })
    script = element
    if (existing === null) {
      element.src = GPT_SCRIPT_SRC
      element.async = true
      document.head.appendChild(element)
    }
  } catch {
    fail('ERROR')
  }

  return cancel
}
