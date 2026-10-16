import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactElement } from 'react'

import { showInterstitial } from '../ads/gptAdapter'
import {
  AD_TRANSITION_PARAMETER,
  AD_TRANSITION_PATH,
  adTransitionContent,
  TRANSITION_PATTERN,
} from '../content/adTransition'
import { authContent, DASHBOARD_PATH } from '../content/auth'
import { claimAdTransition } from '../session/adTransition'
import { replaceAddress, replaceLocation } from '../session/navigation'
import { SESSION_REQUEST_TIMEOUT_MS, useSessionGate } from '../session/useSessionState'
import styles from './AdTransitionScreen.module.css'

/** 마운트 시점 주소의 transition 참조. 없거나 모양이 아니면 `null`이다. 렌더 단계에서 한 번만 읽는다. */
function transitionInAddress(): string | null {
  const value = new URLSearchParams(window.location.search).get(AD_TRANSITION_PARAMETER)
  return value !== null && TRANSITION_PATTERN.test(value) ? value : null
}

/**
 * 금융정보 없는 중립 전환 화면(Story 4.3, `neutral-ad-transition`).
 *
 * 대시보드가 수동 동기화 성공 뒤 서버 판정이 자격일 때만 이 문서로 보낸다. 화면이 아는 것은 주소의 불투명 transition 참조
 * 하나이고, 마운트 직후 그것을 메모리로 옮기고 주소에서 지운다(GPT가 load되기 전이다). 참조가 없거나 모양이 아니면 — 새로고침·
 * 뒤로 가기 재진입이 이것이다 — 세션 확인도 하지 않고 요청 0건으로 결과로 간다.
 */
export function AdTransitionScreen(): ReactElement | null {
  // 렌더 단계에서 주소를 한 번 읽는다 — StrictMode의 이중 effect가 이미 지운 주소를 다시 읽지 않게.
  const [transition] = useState(transitionInAddress)

  /*
   * 주소에서 참조를 지운다(그 뒤에야 공급자 script가 load될 수 있다). 참조가 없으면 떠난다. layout effect다 — passive effect면
   * 화면이 이미 보이는데 주소에 참조가 남아 있는 틈이 생기고, 아래 화면의 소비 요청(passive)보다 반드시 앞선다.
   */
  useLayoutEffect(() => {
    replaceAddress(AD_TRANSITION_PATH)
    if (transition === null) replaceLocation(DASHBOARD_PATH)
  }, [transition])

  return transition === null ? null : <NeutralAdTransition transition={transition} />
}

/**
 * 참조를 가진 중립 화면.
 *
 * 세션이 확인되면 서버에 참조를 소비시키고, `SHOW`일 때만 GPT adapter를 부른다. 광고는 `결과 보기` anchor 클릭이 띄운다.
 * `광고 닫기`, 그리고 `SHOW`가 아닌 모든 결말(건너뜀·401·403·404·오류·시간 초과·adapter 실패)은 광고 없이 `/dashboard`로
 * 간다(덮어쓰기 — 이 화면은 히스토리에 남지 않는다).
 *
 * DOM에 금융값·계획값·신뢰 상태·사용자/결과 식별이 없다. `광고` 표지와 네이티브 `광고 닫기`가 첫 조작이자 최초 포커스이고, 대시보드
 * 카드의 입체 언어(테두리 상자·그림자)를 쓰지 않는다. `결과 보기` 외의 anchor는 interstitial trigger가 아니어야 한다
 * (`data-google-interstitial="false"`) — 지금 이 화면에는 그런 anchor가 없다.
 */
function NeutralAdTransition({ transition }: { readonly transition: string }): ReactElement {
  /*
   * `settings`로 게이트를 연다 — 계획 조건 화면(`PlanConditionScreen`)과 같은 관용이다. 이 화면은 서버가 고르는 착지 화면이
   * 아니라 밀어내기 대상이 아니므로(신규 사용자의 목적지 `onboarding`이 이 화면을 밀어내면 광고가 뜰 자리가 사라진다), 목적지와
   * 무관하게 머무는 `settings`의 규칙을 빌린다. 이 화면에 필요한 것은 세션 확인과 CSRF 토큰뿐이다.
   */
  const { csrfToken, gate } = useSessionGate('settings')
  const closeButton = useRef<HTMLButtonElement>(null)
  const claimed = useRef(false)
  const leaving = useRef(false)
  /** 시도 중인 광고의 취소(adapter가 돌려준 함수). SHOW 전에는 없다. */
  const cancelAd = useRef<(() => void) | null>(null)

  /**
   * 광고 없이 결과로 간다. 두 번째 호출부터는 아무것도 하지 않는다 — 닫기든 adapter 실패든 이동은 한 번이다. 이동보다 먼저
   * 광고를 취소한다(Story 4.4): 리스너·시한을 떼고 slot을 파기해, 이동과 문서 unload 사이에 광고가 뜨거나 늦은 callback이 오는
   * 틈을 닫는다. adapter 실패로 불렸으면 adapter는 이미 결론을 냈고 취소는 아무것도 하지 않는다.
   */
  const leave = useCallback((): void => {
    if (leaving.current) return
    leaving.current = true
    cancelAd.current?.()
    replaceLocation(DASHBOARD_PATH)
  }, [])

  /**
   * `결과 보기`를 눌렀다 — anchor의 기본 이동(과, 준비된 광고면 GPT가 그 클릭으로 띄우는 interstitial)이 결과로 간다. 이 화면은
   * 더 이동하지 않는다: 그 뒤의 늦은 실패(준비 시한 등)·닫기가 `replaceLocation`으로 두 번째 이동을 만들지 않게 떠나는 표시만
   * 세운다. 광고를 취소하지 않는다(준비된 광고는 이 클릭이 띄운다). 기본 동작을 막지 않는다.
   */
  const followResult = useCallback((): void => {
    leaving.current = true
  }, [])

  // 최초 포커스는 닫기다. 커밋과 함께 성립해야 하므로 layout effect다.
  useLayoutEffect(() => {
    closeButton.current?.focus()
  }, [])

  /*
   * bfcache로 복원된 문서(`pageshow`의 `persisted`)는 재진입이다. 살아 있는 slot이 두 번째 interstitial을 띄워 서버 cap을 우회할 수
   * 있으므로 광고를 취소하고 결과로 간다 — 떠나는 표시와 무관하다(복원 전에 이미 떠났던 문서다). 리스너이므로 layout effect다.
   */
  useLayoutEffect(() => {
    const onPageShow = (event: PageTransitionEvent): void => {
      if (!event.persisted) return
      leaving.current = true
      cancelAd.current?.()
      replaceLocation(DASHBOARD_PATH)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => {
      window.removeEventListener('pageshow', onPageShow)
    }
  }, [])

  /*
   * 언마운트되면 시도 중인 광고를 취소한다(리스너·시한·slot 정리). StrictMode의 이중 실행이 부르는 정리는 SHOW 전이라 취소가
   * 아직 없어(`null`) 아무것도 하지 않는다 — 취소는 소비 응답 뒤에야 생긴다.
   */
  useLayoutEffect(
    () => () => {
      cancelAd.current?.()
    },
    [],
  )

  /*
   * 미인증·삭제된 계정, 그리고 본인 확인이 필요한 세션은 결과 화면이 자기 규칙으로 다룬다(회복 경로가 그 화면에 있다). 이 화면은
   * 아무것도 주장하지 않고 그리로 간다. 소비 effect(passive)보다 먼저 떠나는 표시를 남기므로 소비 요청도 나가지 않는다.
   */
  useLayoutEffect(() => {
    if (gate === 'unauthenticated' || gate === 'reauth-required') leave()
  }, [gate, leave])

  /*
   * 세션 확인이 끝나지 않아 소비 요청이 시한 안에 시작되지 않으면 빈 중립 화면에 머물지 않고 결과로 간다. 시작된 소비는 자기
   * 시한이 있다(`claimAdTransition`). layout effect다 — 화면이 보이는 그 커밋부터 시한을 센다.
   */
  useLayoutEffect(() => {
    const deadline = window.setTimeout(() => {
      if (!claimed.current) leave()
    }, SESSION_REQUEST_TIMEOUT_MS)
    return () => {
      window.clearTimeout(deadline)
    }
  }, [leave])

  /*
   * 소비 요청은 세션 토큰이 생긴 뒤 한 번이다. passive effect다 — 요청 시작은 커밋이 보여 주는 것이 아니고, 답은 어차피 뒤의
   * 작업에서 온다. 떠나는 중이면(닫기·다른 실패) 답이 와도 광고를 시도하지 않는다.
   */
  useEffect(() => {
    if (csrfToken === null || claimed.current || leaving.current) return
    claimed.current = true
    void (async () => {
      const claim = await claimAdTransition(transition, csrfToken)
      if (leaving.current) return
      if (claim.kind !== 'show') {
        leave()
        return
      }
      cancelAd.current = showInterstitial(claim.adUnitPath, leave)
    })()
  }, [transition, csrfToken, leave])

  return (
    <div className={styles.page}>
      <div className={styles.adBar}>
        <p className={styles.adLabel}>{adTransitionContent.adLabel}</p>
        <button className={styles.close} onClick={leave} ref={closeButton} type="button">
          {adTransitionContent.close}
        </button>
      </div>
      <main className={styles.main}>
        <h1 className={styles.heading}>{adTransitionContent.heading}</h1>
        <p className={styles.body}>{adTransitionContent.body}</p>
        <a className={styles.viewResult} href={DASHBOARD_PATH} onClick={followResult}>
          {adTransitionContent.viewResult}
        </a>
        <div className={styles.notice}>
          <p className={styles.title}>{authContent.pageTitle}</p>
          <p className={styles.disclaimer}>{authContent.disclaimer}</p>
        </div>
      </main>
    </div>
  )
}
