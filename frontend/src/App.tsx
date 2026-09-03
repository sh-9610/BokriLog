import type { ReactElement } from 'react'

import { EntryScreen } from './screens/EntryScreen'

/**
 * 애플리케이션 루트.
 * Story 1.1은 라우터 없이 진입 화면 하나만 마운트한다.
 * 전역 탐색과 라우팅은 Story 1.2 이후 범위다.
 */
export function App(): ReactElement {
  return <EntryScreen />
}
