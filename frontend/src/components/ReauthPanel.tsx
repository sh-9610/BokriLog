import { useLayoutEffect, useRef, type ReactElement } from 'react'

import {
  authContent,
  SIGN_IN_PROVIDERS,
  type ReauthNoticeKind,
  type SignInProvider,
} from '../content/auth'
import { ProviderMark } from './ProviderMark'
import styles from './ReauthPanel.module.css'
import { SignOutButton } from './SignOutButton'

/**
 * 제공자별 버튼 규칙. 닫힌 맵인 근거는 진입 화면과 같다.
 *
 * 2항 삼항식으로 두면 else가 곧 "나머지 제공자 전부"가 되어, 제공자가 늘 때 그 버튼이 남의 색과
 * 로고로 렌더되고 타입 검사도 시험도 그것을 막지 못한다.
 *
 * 값 타입에 `undefined`가 붙는 것은 CSS 모듈의 타입 때문이다(`noUncheckedIndexedAccess`).
 */
const PROVIDER_BUTTON_CLASS: Readonly<Record<SignInProvider, string | undefined>> = {
  google: styles.googleButton,
  naver: styles.naverButton,
  kakao: styles.kakaoButton,
}

/** 표지 문장의 id. 제목이 `aria-describedby`로 이것을 가리킨다. */
const NOTICE_ID = 'auth-reauth-notice'

interface ReauthPanelProps {
  /**
   * 서버나 클라이언트가 URL에 실어 보낸 **재인증** 표지. 값이 없으면 안내만 보여 준다.
   *
   * 타입이 `AuthNoticeKind`가 아니다. 그대로 받으면 재인증과 무관한 표지도 이 패널 안에서
   * 렌더되고, `/dashboard?auth=loggedOut`이 "로그아웃했습니다"를 아직 로그인해 있는 사용자에게
   * 보여 준다. 좁혀 두면 화면이 주소를 거르는 일을 타입 검사가 강제한다.
   */
  readonly notice: ReauthNoticeKind | null
  /** 재인증 시작이 도는 중인가. 상태는 화면이 소유한다(`useReauthentication`). */
  readonly pending: boolean
  /** 로그아웃이 도는 중인가. 상태는 화면이 소유한다(`useSignOut`). */
  readonly signingOut: boolean
  readonly onReauthenticate: (provider: SignInProvider) => void
  readonly onSignOut: () => void
}

/**
 * 두 인증 화면이 공유하는 재인증 패널.
 *
 * 세션이 재인증을 요구받으면 이 패널이 화면의 본문 자리를 대신한다. 새 경로도 라우터도 만들지
 * 않는 이유가 이것이다 — 표시된 세션은 이미 자기 목적지에 있고, 필요한 것은 그 자리에서 본인을
 * 확인할 수단이지 다른 주소가 아니다.
 *
 * <b>연결된 제공자를 묻지 않는다.</b> 세 버튼을 고정 순서로 그대로 보이고, 연결되지 않은 수단을
 * 고른 결과는 다른 실패와 구분되지 않는 표지 하나로 수렴한다. 어느 수단으로 가입했는지를 화면에
 * 도달시키는 경로가 곧 계정 소속 신호이기 때문이며, 진입 화면의 복구 안내가 세 제공자를 언제나
 * 함께 보여 주는 것과 같은 판단이다.
 *
 * 버튼은 `<button>`이다. 진입 화면의 로그인이 `<a href>`인 것과 다른 이유는 순서다 — 재인증은
 * 세션에 흐름을 심는 상태 변경(`POST`)을 먼저 지나야 하고, 그 뒤에야 제공자로 가는 내비게이션이
 * 일어난다. 링크로 두면 그 무장이 건너뛰어지고 인가 시작은 "이미 인증된 세션"으로 거절된다.
 *
 * 로그아웃 버튼이 여기 함께 있다. 표시된 세션이 화면에서 스스로 나갈 수 있게 되는 자리이며,
 * 그전까지는 게이트가 미인증이라 토큰이 UI에 도달하지 않아 그 길이 없었다.
 *
 * 화면당 하나뿐인 `role="status"` 영역을 이 패널은 만들지 않는다. 진행과 상태를 말하는 것은
 * 화면의 live region 하나이고, 아래 표지는 문서를 열 때 이미 있는 정적 문장이라 live region에
 * 둘 이유가 없다 — 초기 렌더의 내용은 어차피 안내되지 않는다.
 */
export function ReauthPanel({
  notice,
  onReauthenticate,
  onSignOut,
  pending,
  signingOut,
}: ReauthPanelProps): ReactElement {
  const heading = useRef<HTMLHeadingElement>(null)

  /**
   * 어느 조작이든 도는 중인가.
   *
   * 두 조작이 서로를 막는다. 재인증을 시작한 직후 로그아웃이 성립하면 세션이 사라진 뒤 인가
   * 시작이 열리고, 그 흐름은 무장을 잃었으므로 재인증이 아니라 **새 로그인**이 된다 — 사용자가
   * 누른 것과 일어나는 일이 달라지는 자리다. 반대 순서도 같다: 로그아웃이 도는 동안 무장을
   * 심으면 곧 사라질 세션에 흐름을 남긴다.
   *
   * 이 교차 차단은 패널의 일이다. 두 조작의 상태를 함께 아는 것이 이 컴포넌트뿐이고, 각
   * 훅은 자기 중복 제출만 막는다.
   */
  const busy = pending || signingOut

  useLayoutEffect(() => {
    /*
     * 표지와 함께 열리면 포커스를 이 제목으로 보낸다.
     *
     * 표지가 있다는 것은 방금 재인증이 끝나지 않았다는 뜻이고, 그 이동 뒤에 포커스가 문서
     * 처음에 남아 있으면 키보드·보조기술 사용자는 무엇이 바뀌었는지 알 수 없다. 진입 화면이
     * 표지와 함께 열릴 때 쓰는 것과 같은 관용이다.
     *
     * 표지가 없으면 옮기지 않는다. 그때 이 패널은 화면을 처음 여는 것과 같고, 건너뛰기 링크가
     * 첫 `Tab`이라는 계약이 그대로 유지된다.
     *
     * layout effect다. 패널을 그린 커밋 안에서 포커스가 제목에 닿으므로 한 프레임도 `<body>`에
     * 머물지 않는다. passive effect는 그리기 뒤 별도 작업으로 돌아, 부하가 걸리면 새 패널을 이미 본
     * 코드보다 늦게 왔다(진입 화면과 같은 이유).
     */
    if (notice !== null) {
      heading.current?.focus()
    }
  }, [notice])

  return (
    <section aria-labelledby={authContent.reauth.id} className={styles.panel}>
      {/*
        `tabIndex={-1}`이다. 프로그래밍 방식 포커스만 받고 `Tab` 순서에는 들어가지 않는다 —
        들어가면 읽기만 하는 제목이 조작 하나로 세어진다.
      */}
      {/*
        표지가 있으면 제목이 그 문장을 자기 설명으로 가리킨다.

        포커스가 이 제목으로 오는데 제목 문구는 언제나 "본인 확인이 필요합니다"라, 설명이 없으면
        `?auth=reauthFailed`로 돌아온 스크린리더 사용자는 **왜** 돌아왔는지 듣지 못한다. 두 번째
        live region을 만들지 않는 이유는 화면당 하나라는 계약이고, 이 문장은 문서를 열 때 이미
        있는 정적 값이라 live region이 안내할 대상도 아니다.
      */}
      <h2
        aria-describedby={notice === null ? undefined : NOTICE_ID}
        className={styles.heading}
        id={authContent.reauth.id}
        ref={heading}
        tabIndex={-1}
      >
        {authContent.reauth.heading}
      </h2>
      <p>{authContent.reauth.body}</p>

      {notice === null ? null : (
        <p className={styles.notice} id={NOTICE_ID}>
          {authContent.notices[notice]}
        </p>
      )}

      {/*
        제공자 목록은 전체 폭 세로 스택이고 순서는 Google → 네이버 → 카카오로 고정이다
        (DESIGN.md). 추천 표지도, 마지막 사용 제공자 강조도, 로고만 있는 버튼도 없다. 순서는
        `SIGN_IN_PROVIDERS` 하나가 나른다.
      */}
      <div className={styles.providerList}>
        {SIGN_IN_PROVIDERS.map((provider) => (
          <button
            /*
             * `aria-disabled`이지 `disabled`가 아니다. 그 속성은 요소를 탭 순서에서 떨어뜨리므로
             * 방금 `Enter`로 이 버튼을 실행한 키보드·스크린리더 사용자가 그 순간 포커스를 잃는다.
             * 실제 중복 제출 차단은 화면이 소유한 가드가 한다(`useReauthentication`).
             */
            aria-disabled={busy}
            className={PROVIDER_BUTTON_CLASS[provider]}
            key={provider}
            onClick={() => {
              if (busy) {
                return
              }
              onReauthenticate(provider)
            }}
            type="button"
          >
            <ProviderMark provider={provider} />
            <span>{authContent.signIn.labels[provider]}</span>
          </button>
        ))}
      </div>

      <p className={styles.boundary}>{authContent.reauth.boundary}</p>

      {/* 표시된 세션이 스스로 나갈 수 있는 유일한 길이다. */}
      <SignOutButton
        onSignOut={() => {
          if (busy) {
            return
          }
          onSignOut()
        }}
        pending={busy}
      />
    </section>
  )
}
