import type { ReactElement } from 'react'

import { authContent } from '../content/auth'
import { entryContent } from '../content/entry'
import { useSessionGate } from '../session/useSessionState'
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
 * 이 화면에는 아직 계좌·계획·금융 데이터가 없다. Bybit 연결과 복리 계획은 Epic 2의 범위다.
 */
export function OnboardingScreen(): ReactElement {
  const gate = useSessionGate('onboarding')

  if (gate === 'unauthenticated') {
    // 세션 확인 없이 로그인 상태를 주장하지 않는다. 뒤로 가기 복원도 이 경로를 지난다.
    return <EntryScreen notice="signedOut" />
  }

  const ready = gate === 'ready'

  return (
    <div className={styles.page}>
      {/* 건너뛰기 링크는 진입 화면과 같은 관용이다. 반복되는 머리말을 지나칠 첫 조작이다. */}
      <a className={styles.skipLink} href={`#${MAIN_ID}`}>
        {entryContent.skipLink}
      </a>

      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
      </header>

      <main className={styles.main} id={MAIN_ID} tabIndex={-1}>
        <div className={styles.intro}>
          {/* UX-DR23: 제목과 바로 아래 고지를 인접 배치한다. */}
          <h1 className={styles.title}>{authContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{authContent.disclaimer}</p>
        </div>

        {/* 화면당 polite live region 하나. 갱신 중임을 aria-busy로 알린다. */}
        <div aria-busy={!ready} aria-live="polite" className={styles.notice} role="status">
          {ready ? null : (
            <p className={styles.noticeText}>
              {gate === 'redirecting'
                ? authContent.session.redirecting
                : authContent.session.checking}
            </p>
          )}
        </div>

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
