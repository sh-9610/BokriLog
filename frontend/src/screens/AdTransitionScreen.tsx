import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type MouseEvent,
  type ReactElement,
} from 'react'

import { showInterstitial } from '../ads/gptAdapter'
import {
  AD_CLAIM_WINDOW_SECONDS,
  AD_TRANSITION_PARAMETER,
  AD_TRANSITION_PATH,
  adTransitionContent,
  TRANSITION_PATTERN,
} from '../content/adTransition'
import { authContent, DASHBOARD_PATH } from '../content/auth'
import { claimAdTransition } from '../session/adTransition'
import { replaceAddress, replaceLocation } from '../session/navigation'
import { SESSION_REQUEST_TIMEOUT_MS, useSessionGate } from '../session/useSessionState'
import { flushTelemetry, recordAdEvent } from '../telemetry/beacon'
import { useTelemetry } from '../telemetry/useTelemetry'
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
   * 참조가 없으면 주소를 정리하고 떠난다. 참조가 있으면 주소 정리는 중립 화면의 첫 layout effect가 한다 — 자식의 layout effect가
   * 부모의 것보다 먼저 돌므로, 여기서 지우면 중립 화면이 마운트 커밋에서 떠나는 경우(늦게 연 문서의 수명 상한)에 이동이 주소
   * 정리보다 앞선다.
   */
  useLayoutEffect(() => {
    if (transition !== null) return
    replaceAddress(AD_TRANSITION_PATH)
    replaceLocation(DASHBOARD_PATH)
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
 * 준비된 광고의 수명은 서버 소비 창(`AD_CLAIM_WINDOW_SECONDS`, 결정 뒤 120초)에 묶는다 — 이 문서가 시작된 뒤 그 시간까지
 * `결과 보기`를 누르지 않았으면 광고를 취소하고 결과로 간다(Epic 4 회고 AI-1).
 *
 * 광고 이벤트(Story 4.5)는 SHOW 뒤에만 이벤트마다 한 번 기록한다 — adapter 호출 `REQUESTED`, 준비 `READY`, `광고 닫기` `CLOSED`,
 * adapter 실패·소비 창 경과 `FAILED`(사유), 결과로 떠남 `RESULT`. 떠나기 직전(이동·`결과 보기` 기본 이동 앞)에 계측 묶음을 한 번
 * 보낸다. 문서의 Web Vitals는 `useTelemetry`가 이 화면 경로로 잰다 — SHOW 뒤에 보고된 값은 광고를 띄운 문서의 것으로 표시된다.
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
  useTelemetry(AD_TRANSITION_PATH, csrfToken)
  const closeButton = useRef<HTMLButtonElement>(null)
  const claimed = useRef(false)
  const leaving = useRef(false)
  /** SHOW를 받아 adapter를 불렀다(`REQUESTED`). 광고 이벤트는 그 뒤에만 기록한다. */
  const requested = useRef(false)
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
    if (requested.current) recordAdEvent('RESULT')
    // 떠나기 직전의 계측 묶음. 실패해도 이동은 그대로다.
    flushTelemetry()
    replaceLocation(DASHBOARD_PATH)
  }, [])

  /** `광고 닫기`. 광고를 시도한 뒤면 닫기를 기록하고 떠난다. */
  const close = useCallback((): void => {
    if (!leaving.current && requested.current) recordAdEvent('CLOSED')
    leave()
  }, [leave])

  /** 소비 창이 지나 광고를 취소하고 떠난다. 광고를 시도한 뒤면 창 경과의 실패를 기록한다. */
  const expire = useCallback((): void => {
    if (!leaving.current && requested.current) recordAdEvent('FAILED', 'WINDOW_EXPIRED')
    leave()
  }, [leave])

  /**
   * `결과 보기`를 눌렀다 — anchor의 기본 이동(과, 준비된 광고면 GPT가 그 클릭으로 띄우는 interstitial)이 결과로 간다. 이 화면은
   * 더 이동하지 않는다: 그 뒤의 늦은 실패(준비 시한 등)·닫기가 `replaceLocation`으로 두 번째 이동을 만들지 않게 떠나는 표시만
   * 세운다. 광고를 취소하지 않는다(준비된 광고는 이 클릭이 띄운다). 기본 동작을 막지 않는다.
   *
   * 보조키(Ctrl·Cmd·Shift·Alt) 클릭과 주 버튼이 아닌 클릭은 떠남이 아니다 — 새 탭·창으로 열어도 이 탭은 남으므로 수명 상한과 닫기가
   * 계속 동작해야 한다. 아무것도 하지 않는다.
   *
   * 클릭 시점에 이미 소비 창이 지났으면 기본 이동을 막고 `leave`로 간다(광고 취소 → 이동 한 번). 백그라운드 탭 스로틀링·절전으로
   * 수명 상한 타이머가 늦게 도는 경우에 대한 방어다. GPT의 클릭 처리와의 순서는 실제 소재로 보는 G-4 몫이고, 이 검사는 타이머
   * 지연에 대한 방어다.
   */
  const followResult = useCallback(
    (event: MouseEvent<HTMLAnchorElement>): void => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
        return
      }
      if (performance.now() >= AD_CLAIM_WINDOW_SECONDS * 1000) {
        event.preventDefault()
        expire()
        return
      }
      if (leaving.current) return
      leaving.current = true
      if (requested.current) recordAdEvent('RESULT')
      // 기본 이동 앞에서 계측 묶음을 보낸다. 실패해도 이동은 그대로다.
      flushTelemetry()
    },
    [expire],
  )

  /*
   * 주소에서 참조를 지운다(그 뒤에야 공급자 script가 load될 수 있다). 이 컴포넌트의 첫 layout effect다 — 아래의 수명 상한·게이트
   * 이동보다, 그리고 소비 요청(passive)보다 반드시 앞선다. passive effect면 화면이 이미 보이는데 주소에 참조가 남아 있는 틈이 생긴다.
   */
  useLayoutEffect(() => {
    replaceAddress(AD_TRANSITION_PATH)
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
      // 광고를 시도했고 아직 떠나지 않은 문서(결과 보기 없이 숨겨졌다 돌아온 문서)면 결과 도달을 남기고 보낸다(Story 4.5).
      const unrecorded = requested.current && !leaving.current
      leaving.current = true
      cancelAd.current?.()
      if (unrecorded) {
        recordAdEvent('RESULT')
        flushTelemetry()
      }
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
   * 준비된 광고의 수명 상한(Epic 4 회고 AI-1). 광고를 띄울 수 있는 기간은 서버 소비 창(결정 뒤 120초)이고, 화면은 그 시작을 이
   * 문서가 시작된 시각으로 근사한다 — 대시보드는 자격 응답을 받은 자리에서 문서를 이동하므로 문서 시작은 결정 뒤 응답 전송 시간만큼
   * 늦을 뿐이다. 기준은 `performance.now()`(문서 시작부터 흐른 단조 시간)이고 벽시계를 쓰지 않는다. 상한까지 `결과 보기`를 누르지
   * 않았으면 `leave` 한 경로로 떠난다 — 광고 취소(slot 파기)가 먼저이고 이동은 한 번이며, 늦게 온 SHOW는 adapter를 부르지 않는다.
   * 이미 눌렀으면 `leave`가 아무것도 하지 않는다 — 클릭이 띄운 광고는 창 안에서 시작된 것이므로 표시 중에 파기하지 않는다. 남은
   * 시간이 없으면(늦게 연 문서) 마운트 커밋에서 바로 떠나므로 소비 요청도 나가지 않는다. 타이머·이동이므로 layout effect다.
   */
  useLayoutEffect(() => {
    const remaining = AD_CLAIM_WINDOW_SECONDS * 1000 - performance.now()
    if (remaining <= 0) {
      expire()
      return
    }
    const limit = window.setTimeout(expire, remaining)
    return () => {
      window.clearTimeout(limit)
    }
  }, [expire])

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
      requested.current = true
      recordAdEvent('REQUESTED')
      cancelAd.current = showInterstitial(
        claim.adUnitPath,
        (reason) => {
          recordAdEvent('FAILED', reason)
          leave()
        },
        () => {
          recordAdEvent('READY')
        },
      )
    })()
  }, [transition, csrfToken, leave])

  return (
    <div className={styles.page}>
      <div className={styles.adBar}>
        <p className={styles.adLabel}>{adTransitionContent.adLabel}</p>
        <button className={styles.close} onClick={close} ref={closeButton} type="button">
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
