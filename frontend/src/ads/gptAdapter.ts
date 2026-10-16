/*
 * `AD-GPT-1.0` adapter — Google Ad Manager의 Google Publisher Tag(GPT) web interstitial(Story 4.3).
 *
 * 이 저장소에서 `googletag`를 아는 유일한 모듈이다. 중립 전환 화면(`screens/AdTransitionScreen`)이 서버의 transition 소비가
 * `SHOW`일 때만 부른다 — 다른 화면·경로는 이 모듈을 부르지 않으며 GPT script도 그때만 load된다(CSP도 `/ad-transition`에만 그
 * origin을 연다, `deploy/nginx/bokrilog.conf`).
 *
 * web interstitial은 이 adapter가 직접 띄우지 않는다. slot을 정의해 두면 GPT가 사용자가 누른 동일 사이트 anchor(이 화면의
 * `결과 보기`) 이동 앞에 광고를 띄운다. `data-google-interstitial="false"`인 anchor는 trigger가 아니다.
 *
 * 비개인화 문맥형만 요청한다(`nonPersonalizedAds`). targeting은 하나도 걸지 않는다 — slot·targeting·URL·제목 어디에도 금융·계획·
 * 신뢰·사용자/결과 식별이 없다.
 *
 * 실패(slot이 `null`, script load 오류, 정의 중 예외)는 `onFailure` 한 번이다 — 화면은 광고 없이 결과로 간다. 재시도·다른 공급자
 * fallback이 없다. load 시간초과·미채움·render·접근성 실패 감지와 늦은 callback 처리는 Story 4.4 몫이다.
 */

/** GPT library 주소. 공급자 문서의 값 그대로다. */
export const GPT_SCRIPT_SRC = 'https://securepubads.g.doubleclick.net/tag/js/gpt.js'

/*
 * 이 adapter가 쓰는 `googletag` 표면만의 타입. 런타임 의존을 더하지 않으려고 `@types/google-publisher-tag` 대신 쓰는 부분만
 * 적는다.
 */
interface PubAdsService {
  setPrivacySettings(settings: { readonly nonPersonalizedAds: boolean }): unknown
}

interface Slot {
  addService(service: PubAdsService): Slot
}

interface GoogleTag {
  readonly cmd: { push(callback: () => void): unknown }
  readonly enums: { readonly OutOfPageFormat: { readonly INTERSTITIAL: number } }
  defineOutOfPageSlot(adUnitPath: string, format: number): Slot | null
  pubads(): PubAdsService
  enableServices(): void
  display(slot: Slot): void
}

interface GoogleTagWindow {
  googletag?: GoogleTag | { cmd: (() => void)[] }
}

/**
 * 그 ad unit의 web interstitial을 준비한다. GPT script를 script 요소로 load하고, load된 GPT가 command queue를 처리할 때
 * 비개인화 설정 → out-of-page interstitial slot 정의 → 서비스 활성 → display 순서로 부른다.
 *
 * @param adUnitPath 서버 소비 응답의 ad unit 경로
 * @param onFailure 광고를 준비하지 못했다(slot 없음·script 오류·예외). 한 번만 불린다
 */
export function showInterstitial(adUnitPath: string, onFailure: () => void): void {
  let failed = false
  const fail = (): void => {
    if (failed) return
    failed = true
    onFailure()
  }
  try {
    const host = window as unknown as GoogleTagWindow
    // GPT가 load되기 전에는 배열이 command queue다. load된 GPT가 같은 이름의 객체로 바꾸고 쌓인 command를 처리한다.
    host.googletag ??= { cmd: [] }
    host.googletag.cmd.push(() => {
      try {
        const googletag = host.googletag as GoogleTag
        const pubads = googletag.pubads()
        pubads.setPrivacySettings({ nonPersonalizedAds: true })
        const slot = googletag.defineOutOfPageSlot(
          adUnitPath,
          googletag.enums.OutOfPageFormat.INTERSTITIAL,
        )
        // 이 페이지·기기에서 interstitial을 지원하지 않으면 GPT가 `null`을 준다.
        if (slot === null) {
          fail()
          return
        }
        slot.addService(pubads)
        googletag.enableServices()
        googletag.display(slot)
      } catch {
        fail()
      }
    })
    const script = document.createElement('script')
    script.src = GPT_SCRIPT_SRC
    script.async = true
    script.addEventListener('error', fail, { once: true })
    document.head.appendChild(script)
  } catch {
    fail()
  }
}
