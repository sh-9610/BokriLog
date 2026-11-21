import { useLayoutEffect } from 'react'

import { DASHBOARD_PATH } from '../content/auth'
import { replaceLocation } from '../session/navigation'

/** 이전 광고 주소는 대시보드로 복귀시킨다. */
export function AdTransitionScreen(): null {
  useLayoutEffect(() => {
    replaceLocation(DASHBOARD_PATH)
  }, [])
  return null
}
