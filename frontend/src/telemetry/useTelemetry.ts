import { useLayoutEffect } from 'react'
import { onCLS, onINP, onLCP, type Metric } from 'web-vitals'

import {
  beginDocument,
  listenForDocumentHide,
  recordVital,
  setTelemetryToken,
  type TelemetryRoute,
} from './beacon'

/*
 * 대시보드·설정·계획 조건 화면이 부르는 계측 훅(Story 4.5).
 *
 * Web Vitals(LCP·INP·CLS)는 `web-vitals`가 잰다 — INP·CLS의 세션 창과 상호작용 묶음을 직접 구현하지 않는다. 이 저장소는 화면
 * 이동이 모두 문서 이동이므로 문서 하나가 화면 하나다. 그래서 등록은 문서당 한 번이고(StrictMode의 이중 effect도 한 번), 경로는
 * 그 화면의 것이다. 보내는 시점은 문서가 숨거나 내려갈 때다(`listenForDocumentHide`). 중립 화면은 떠나기 직전에도 따로 보낸다.
 */

/** 이 문서의 계측을 시작한다. 두 번째 호출부터는 아무것도 하지 않는다. */
function start(route: TelemetryRoute): void {
  if (!beginDocument()) return
  const report = (metric: Metric): void => {
    if (metric.name === 'LCP' || metric.name === 'INP' || metric.name === 'CLS') {
      recordVital(metric.name, route, metric.value)
    }
  }
  try {
    onLCP(report)
    onINP(report)
    onCLS(report)
  } catch {
    // 측정을 지원하지 않는 환경이어도 화면은 그대로다.
  }
  listenForDocumentHide()
}

/**
 * 그 화면의 계측. 세션의 CSRF 토큰을 넘긴다 — 토큰이 있을 때만 보낸다.
 *
 * 둘 다 layout effect다: 리스너 등록과, flush가 읽는 latest 토큰은 커밋과 함께 성립해야 한다(`DEVELOPMENT.md`의 구조와 규칙).
 */
export function useTelemetry(route: TelemetryRoute, csrfToken: string | null): void {
  useLayoutEffect(() => {
    start(route)
  }, [route])
  useLayoutEffect(() => {
    setTelemetryToken(csrfToken)
  }, [csrfToken])
}
