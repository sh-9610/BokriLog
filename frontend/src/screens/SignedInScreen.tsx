import type { ReactElement } from 'react'

import { authContent } from '../content/auth'
import { entryContent } from '../content/entry'
import { useSessionGate } from '../session/useSessionState'
import { EntryScreen } from './EntryScreen'
import styles from './SignedInScreen.module.css'

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'signed-in-main'

/**
 * 로그인 상태 화면.
 *
 * 서버 세션이 유일한 권위다. 확인 전에는 어떤 상태도 주장하지 않고, 미인증·오류·취소는 모두
 * 인증 화면으로 되돌린다. 미인증 상태로 이 경로에 직접 들어오면 문서는 200이지만 화면은 인증
 * 화면이 되고 사용자 데이터는 0건이다.
 *
 * 서버가 고른 목적지가 이 화면이 아니면 그리지 않는다. 온보딩을 지나야 하는 계정이 이 경로를
 * 직접 열면 서버는 `onboarding`을 답하고, 그때 화면은 그 목적지로 문서를 다시 요청한다
 * (`useSessionGate`).
 *
 * 지표·신뢰 상태·조정 이력은 이 스토리의 범위가 아니다. 여기서 하는 일은 "세션이 있다"를 보여
 * 주는 것뿐이며 금융값을 담지 않는다.
 */
export function SignedInScreen(): ReactElement {
  const gate = useSessionGate('dashboard')

  if (gate === 'unauthenticated') {
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
          <section aria-labelledby={authContent.dashboard.id} className={styles.section}>
            <h2 className={styles.sectionHeading} id={authContent.dashboard.id}>
              {authContent.dashboard.heading}
            </h2>
            <ul className={styles.itemList} role="list">
              {authContent.dashboard.items.map((item, index) => (
                <li className={styles.item} key={`${authContent.dashboard.id}-${String(index)}`}>
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
