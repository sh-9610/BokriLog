import { Component, lazy, Suspense, type ReactElement, type ReactNode } from 'react'

import { AD_TRANSITION_PATH } from './content/adTransition'
import {
  authContent,
  DASHBOARD_PATH,
  noticeKindOf,
  ONBOARDING_PATH,
  OPERATOR_PATH,
  SETTINGS_PATH,
} from './content/auth'
import { planConditionContent } from './content/planConditions'
import { AdTransitionScreen } from './screens/AdTransitionScreen'
import { EntryScreen } from './screens/EntryScreen'
import { OnboardingScreen } from './screens/OnboardingScreen'
import { PlanConditionScreen } from './screens/PlanConditionScreen'
import { SettingsScreen } from './screens/SettingsScreen'
import { SignedInScreen } from './screens/SignedInScreen'

/**
 * 보호된 운영자 화면(Story 3.1). 일반 번들의 초기 화면에 운영자 제어를 섞지 않으려고 `React.lazy`로 따로 받는다 —
 * 그 chunk는 `/operator`에 들어올 때만 요청되고, 진입 화면을 비롯한 다른 경로는 그 코드를 받지 않는다(e2e가 고정한다).
 * 라우터·런타임 의존을 더하지 않는다(`React.lazy`는 React 내장이다).
 */
const OperatorScreen = lazy(async () => {
  const module = await import('./screens/OperatorScreen')
  return { default: module.OperatorScreen }
})

/**
 * 따로 받는 화면 코드의 오류 경계. chunk를 받지 못하면(배포 교체·네트워크 단절) `Suspense`가 오류를 던지고, 경계가 없으면
 * 앱 전체가 빈 화면이 된다. 그 자리에 다시 불러오기 안내 하나를 그린다 — 자동 재시도하지 않는다.
 */
class ScreenLoadBoundary extends Component<
  { readonly children: ReactNode },
  { readonly failed: boolean }
> {
  override state = { failed: false }

  static getDerivedStateFromError(): { readonly failed: boolean } {
    return { failed: true }
  }

  override render(): ReactNode {
    if (this.state.failed) {
      return <p role="status">{authContent.session.screenLoadFailed}</p>
    }
    return this.props.children
  }
}

/**
 * 애플리케이션 루트.
 *
 * 경로를 닫힌 맵으로 화면에 대응한다. 라우터 의존을 추가하지 않는 이유는 이 저장소가 여는 경로가
 * 적고 Nginx의 허용 목록(`deploy/nginx/bokrilog.conf`)과 같은 집합이기 때문이다.
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
  if (pathname === SETTINGS_PATH) {
    return <SettingsScreen />
  }
  if (pathname === planConditionContent.path) {
    return <PlanConditionScreen />
  }
  if (pathname === AD_TRANSITION_PATH) {
    // 중립 전환 화면(Story 4.3). `/plan-conditions`처럼 화면 경로이지만 로그인 복귀 목적지가 아니다.
    return <AdTransitionScreen />
  }
  if (pathname === OPERATOR_PATH) {
    return (
      /*
       * chunk를 받는 동안 아무 상태도 주장하지 않는다. 세션 확인 문장은 화면이 받은 뒤 자기 live region에서 말한다.
       */
      <ScreenLoadBoundary>
        <Suspense fallback={<p role="status">{authContent.session.checking}</p>}>
          <OperatorScreen />
        </Suspense>
      </ScreenLoadBoundary>
    )
  }
  return <EntryScreen notice={noticeKindOf(search)} />
}
