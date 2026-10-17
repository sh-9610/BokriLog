/*
 * `AD-GPT-1.0` adapter — Google Ad Manager의 Google Publisher Tag(GPT) web interstitial(Story 4.3·4.4·4.5).
 *
 * 이 저장소에서 `googletag`를 아는 유일한 모듈이다. 중립 전환 화면(`screens/AdTransitionScreen`)이 서버의 transition 소비가
 * `SHOW`일 때만 부른다 — 다른 화면·경로는 이 모듈을 부르지 않으며 GPT script도 그때만 load된다(CSP도 `/ad-transition`에만 그
 * origin을 연다, `deploy/nginx/bokrilog.conf`).
 *
 * web interstitial은 이 adapter가 직접 띄우지 않는다. slot을 정의해 두면 GPT가 사용자가 누른 동일 사이트 anchor(이 화면의
 * `결과 보기`) 이동 앞에 광고를 띄운다. `data-google-interstitial="false"`인 anchor는 trigger가 아니다.
 *
 * 비개인화 문맥형만 요청한다(`nonPersonalizedAds`). 공급자에 넘기는 것은 ad unit 경로와 그 설정뿐이고 targeting·URL 조작·
 * `console` 출력이 없다 — slot·targeting·URL·제목 어디에도 금융·계획·신뢰·사용자/결과 식별이 없다.
 *
 * 상태 기계(Story 4.4) — 실패와 취소가 종단이고 둘 중 먼저 온 하나만 성립한다. 준비는 종단이 아니라 시한만 해제한다.
 * - 준비: 우리 slot의 `slotOnload`가 부른 뒤 `AD_READY_TIMEOUT_MS` 안에 온다. 시한을 해제하고 `onReady`를 한 번 부르며, 광고는
 *   `결과 보기` 클릭이 띄운다. 준비 뒤에도 미채움은 실패다.
 * - 실패: 준비 시간초과(`TIMEOUT`), 우리 slot의 미채움(`slotRenderEnded`의 `isEmpty` — 준비 전후 모두, `NO_FILL`), script
 *   `error`(CSP·차단기, `BLOCKED`), slot `null`(이 기기에서 interstitial 미지원, `UNSUPPORTED`), command 안 예외(`ERROR`).
 *   `onFailure(사유)` 한 번이고 화면은 광고 없이 결과로 간다. 사유는 화면이 계측 beacon에 싣는 닫힌 값이다(Story 4.5).
 * - 취소: 돌려준 함수. 화면이 떠날 때(닫기·실패·언마운트·bfcache 복원) 부른다. `onFailure`를 부르지 않는다.
 * 실패·취소는 시한을 해제하고 리스너를 떼고 slot이 있으면 `destroySlots`로 파기한다 — 단계마다 따로 시도해 한 단계가 던져도 나머지는
 * 그대로 한다. 이동과 문서 unload 사이에 GPT가 광고를 띄우는 틈을 닫는다. 종단 뒤의 이벤트·시한·command는 아무것도 부르지
 * 않는다(늦은 callback). 다른 slot의 이벤트는 무시한다.
 * 재시도·다른 공급자 fallback이 없고 adapter는 서버에 아무것도 보내지 않는다 — 사유와 준비는 화면이 받아 계측 beacon에
 * 싣는다(Story 4.5). 소재 iframe 안의 키보드 트랩·레이블은 교차 출처라 여기서 볼 수 없다 — 실제 소재로 하는 출시 게이트 G-4
 * 몫이고, iframe 밖 네이티브 `광고 닫기`가 그 사이의 탈출구다.
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
export const GPT_SCRIPT_SRC = 'https://securepubads.g.doubleclick.net/tag/js/gpt.js'

/** 부른 뒤 준비(`slotOnload`)를 기다리는 시한. 넘으면 광고 없이 결과로 간다(Story 4.4). */
export const AD_READY_TIMEOUT_MS = 3000

/*
 * 이 adapter가 쓰는 `googletag` 표면만의 타입. 런타임 의존을 더하지 않으려고 `@types/google-publisher-tag` 대신 쓰는 부분만
 * 적는다.
 */
interface Slot {
  addService(service: PubAdsService): Slot
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
  setPrivacySettings(settings: { readonly nonPersonalizedAds: boolean }): unknown
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
 * 그 ad unit의 web interstitial을 준비한다. GPT script를 script 요소로 load하고, load된 GPT가 command queue를 처리할 때
 * 비개인화 설정 → out-of-page interstitial slot 정의 → 준비·미채움 리스너 → 서비스 활성 → display 순서로 부른다.
 *
 * @param adUnitPath 서버 소비 응답의 ad unit 경로
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
        service.setPrivacySettings({ nonPersonalizedAds: true })
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
    const element = document.createElement('script')
    element.src = GPT_SCRIPT_SRC
    element.async = true
    element.addEventListener('error', onScriptError, { once: true })
    script = element
    document.head.appendChild(element)
  } catch {
    fail('ERROR')
  }

  return cancel
}
