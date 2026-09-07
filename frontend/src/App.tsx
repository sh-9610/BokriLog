import type { ReactElement } from 'react'

import { DASHBOARD_PATH, noticeKindOf, ONBOARDING_PATH } from './content/auth'
import { EntryScreen } from './screens/EntryScreen'
import { OnboardingScreen } from './screens/OnboardingScreen'
import { SignedInScreen } from './screens/SignedInScreen'

/**
 * 애플리케이션 루트.
 *
 * 경로를 닫힌 맵으로 화면에 대응한다. 라우터 의존을 추가하지 않는 이유는 이 저장소가 여는 경로가
 * 세 개뿐이고, 그 세 개가 Nginx의 허용 목록(`deploy/nginx/bokrilog.conf`)과 같은 집합이기 때문이다.
 * 목록에 없는 경로는 애초에 문서를 받지 못하므로 여기까지 오지 않는다.
 *
 * 전환은 전부 서버가 만드는 최상위 내비게이션이다. 클라이언트 라우팅이 없어 `history` API도,
 * 링크 가로채기도 없다. 그래서 이 함수는 마운트 시점의 `location`만 읽는다.
 *
 * 세션 확인은 로그인 상태 경로의 화면이 각자 한다. 진입 화면은 어떤 요청도 보내지 않으며 그
 * 사실을 E2E가 고정한다.
 */
export function App(): ReactElement {
  const { pathname, search } = window.location

  if (pathname === ONBOARDING_PATH) {
    return <OnboardingScreen />
  }
  if (pathname === DASHBOARD_PATH) {
    return <SignedInScreen />
  }
  return <EntryScreen notice={noticeKindOf(search)} />
}
