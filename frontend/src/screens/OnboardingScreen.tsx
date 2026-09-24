import type { ReactElement } from 'react'

import { ReauthPanel } from '../components/ReauthPanel'
import { SignOutButton } from '../components/SignOutButton'
import { authContent, reauthNoticeKindOf } from '../content/auth'
import { entryContent } from '../content/entry'
import { useReauthentication } from '../session/useReauthentication'
import { liveRegionMessage, useSessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { EntryScreen } from './EntryScreen'
import styles from './OnboardingScreen.module.css'

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'onboarding-main'

/**
 * 신규 가입 직후의 온보딩 화면.
 *
 * 서버 세션이 유일한 권위다. 확인 전에는 어떤 상태도 주장하지 않고, 미인증·오류·취소는 모두
 * 인증 화면으로 되돌린다. 세션이 확인되기 전에는 사용자 데이터를 한 글자도 표시하지 않는다.
 *
 * 서버가 고른 목적지가 이 화면이 아니면 그리지 않는다. 이미 온보딩을 지난 계정이 이 경로를 직접
 * 열면 서버는 `dashboard`를 답하고, 그때 화면은 사용자 데이터를 그리는 대신 그 목적지로 문서를
 * 다시 요청한다(`useSessionGate`).
 *
 * 나가는 길이 이 화면에도 있다. 온보딩에 로그아웃이 없으면 방금 가입한 사용자가 이 화면에서
 * 나갈 방법이 없다. 진행 중임을 말하는 것은 버튼이 아니라 아래 live region이다(화면당 하나).
 * 버튼의 이름은 언제나 동작으로 남는다.
 *
 * 이 화면에는 아직 계좌·계획·금융 데이터가 없다. Bybit 연결과 복리 계획은 Epic 2의 범위다.
 */
export function OnboardingScreen(): ReactElement {
  const { csrfToken, gate } = useSessionGate('onboarding')
  const signOut = useSignOut(csrfToken)
  const reauthentication = useReauthentication(csrfToken)

  if (gate === 'unauthenticated') {
    /*
     * 세션 확인 없이 로그인 상태를 주장하지 않는다. 냉시작 미인증이 이 경로를 지난다.
     *
     * 만료는 여기 오지 않는다 — 게이트가 `redirecting`으로 답하고 `/?auth=expired`로 보내므로
     * 만료 문장의 출처는 주소의 표지 하나다. 두 결과를 함께 내면 제자리 렌더와 이동이 경합한다.
     */
    return <EntryScreen notice="signedOut" />
  }

  const ready = gate === 'ready'
  /*
   * 재인증을 요구받은 세션이다. 이동하지 않고 이 자리에서 회복 경로를 연다 —
   * 표시된 세션은 이미 자기 목적지에 있고, 인증 화면으로 보내면 그 화면의 로그인 버튼을
   * 서버가 "이미 인증된 세션"으로 거절해 무한 왕복이 된다.
   */
  const reauthRequired = gate === 'reauth-required'

  return (
    <div className={styles.page}>
      {/* 건너뛰기 링크는 진입 화면과 같은 관용이다. 반복되는 머리말을 지나칠 첫 조작이다. */}
      <a className={styles.skipLink} href={`#${MAIN_ID}`}>
        {entryContent.skipLink}
      </a>

      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
        {/* 세션이 확인된 뒤에만 둔다. 확인 전에는 로그아웃할 대상이 아직 없다. */}
        {ready ? (
          <SignOutButton onSignOut={signOut.requestSignOut} pending={signOut.pending} />
        ) : null}
      </header>

      <main className={styles.main} id={MAIN_ID} tabIndex={-1}>
        <div className={styles.intro}>
          {/* UX-DR23: 제목과 바로 아래 고지를 인접 배치한다. */}
          <h1 className={styles.title}>{authContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{authContent.disclaimer}</p>
        </div>

        {/*
          화면당 polite live region 하나. 갱신 중임을 aria-busy로 알린다.

          로그아웃 진행도 여기서 말한다. 버튼 안의 문구 변화는 live region이 아니어서 보조기술에
          전달되지 않고, 그 문구가 버튼의 접근 가능한 이름이 되면 컨트롤의 이름이 동작 아닌
          상태가 된다.
        */}
        <div
          /*
            표시된 세션은 갱신 중이 아니다. 게이트가 `reauth-required`인 상태는 확인이
            끝나고 다음 행동을 기다리는 자리이므로 `aria-busy`를 세우면 보조기술에 "지금
            바뀌는 중"이라고 잘못 말한다. 확인 중·이동 중·요청 중만 참이다.
          */
          aria-busy={(!ready && !reauthRequired) || signOut.pending || reauthentication.pending}
          aria-live="polite"
          className={styles.notice}
          role="status"
        >
          {ready && !signOut.pending ? null : (
            <p className={styles.noticeText}>
              {liveRegionMessage(gate, signOut.pending, reauthentication.pending)}
            </p>
          )}
        </div>

        {reauthRequired ? (
          <ReauthPanel
            notice={reauthNoticeKindOf(window.location.search)}
            onReauthenticate={reauthentication.requestReauthentication}
            onSignOut={signOut.requestSignOut}
            pending={reauthentication.pending}
            signingOut={signOut.pending}
          />
        ) : null}

        {ready ? (
          <section aria-labelledby={authContent.onboarding.id} className={styles.section}>
            <h2 className={styles.sectionHeading} id={authContent.onboarding.id}>
              {authContent.onboarding.heading}
            </h2>
            <ul className={styles.itemList} role="list">
              {authContent.onboarding.items.map((item, index) => (
                <li className={styles.item} key={`${authContent.onboarding.id}-${String(index)}`}>
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </main>
    </div>
  )
}
