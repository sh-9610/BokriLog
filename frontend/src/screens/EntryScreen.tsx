import type { ReactElement } from 'react'

import { authContent, authorizeHref, type AuthNoticeKind } from '../content/auth'
import { entryContent, MAIN_LANDMARK_ID } from '../content/entry'
import styles from './EntryScreen.module.css'

interface EntryScreenProps {
  /**
   * 보여 줄 안내의 종류. 서버가 URL에 실어 보낸 닫힌 표지이거나, 로그인 상태 경로에서 세션 확인이
   * 미인증으로 온 경우다. 값이 없으면 안내를 띄우지 않는다.
   */
  readonly notice?: AuthNoticeKind | null
}

/**
 * 로그인 없이 열리는 인증 화면.
 *
 * 이용 범위(Story 1.1 AC-1), 인증 경계(AC-2), 비추천 고지(UX-DR23)를 semantic 마크업으로 렌더하고,
 * Story 1.4가 그 위에 Google 로그인 진입을 더했다.
 *
 * 로그인은 `<a href>` 최상위 내비게이션이다. 서버가 302로 제공자에 보내는 흐름이라 외부 스크립트도
 * iframe도 form POST도 필요 없고, 그래서 Nginx의 CSP(`default-src 'none'`, `connect-src 'self'`,
 * `form-action 'none'`)를 한 글자도 넓히지 않는다.
 *
 * 이 화면은 어떤 요청도 보내지 않는다. 세션 확인은 로그인 상태 경로에서만 한다.
 */
export function EntryScreen({ notice = null }: EntryScreenProps = {}): ReactElement {
  return (
    <div className={styles.page}>
      <a className={styles.skipLink} href={`#${MAIN_LANDMARK_ID}`}>
        {entryContent.skipLink}
      </a>

      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
      </header>

      <main className={styles.main} id={MAIN_LANDMARK_ID} tabIndex={-1}>
        <div className={styles.intro}>
          <h1 className={styles.title}>{entryContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{entryContent.disclaimer}</p>
          <p className={styles.lead}>{entryContent.lead}</p>
        </div>

        {/*
          화면당 polite live region 하나(EXPERIENCE.md). 값이 없을 때도 자리를 비워 두어야
          나중에 채워지는 문장이 보조기술에 전달된다.
        */}
        <div aria-live="polite" className={styles.notice} role="status">
          {notice === null ? null : (
            <p className={styles.noticeText}>{authContent.notices[notice]}</p>
          )}
        </div>

        <section aria-labelledby={authContent.signIn.id} className={styles.signIn}>
          <h2 className={styles.sectionHeading} id={authContent.signIn.id}>
            {authContent.signIn.heading}
          </h2>

          {/*
            제공자 목록은 전체 폭 세로 스택이고 순서는 Google → 네이버 → 카카오로 고정이다
            (DESIGN.md). 지금 실제 버튼은 Google 하나이며 추천 표지도, 마지막 사용 제공자 강조도 없다.
          */}
          {/*
            지금 경로가 서버 허용 목록에 있으면 그 값을 `next`로 실어 보낸다. 로그인 상태 경로에
            미인증으로 들어와 이 화면으로 되돌려진 사용자가 로그인 뒤 원래 보려던 화면으로
            돌아가는 경로가 그 한 파라미터다. 진입 화면(`/`)에서는 붙지 않는다.
          */}
          <a className={styles.googleButton} href={authorizeHref(window.location.pathname)}>
            <GoogleMark />
            <span>{authContent.signIn.googleLabel}</span>
          </a>

          {/* DESIGN.md가 버튼 아래에 두라고 정한 안내. */}
          <p className={styles.signInBoundary}>{authContent.signIn.boundary}</p>
          <p className={styles.signInBoundary}>{authContent.signIn.pending}</p>
        </section>

        <div className={styles.sections}>
          {entryContent.sections.map((section) => (
            <section
              key={section.id}
              aria-labelledby={section.id}
              className={
                section.tone === 'notice'
                  ? [styles.section, styles.sectionNotice].join(' ')
                  : styles.section
              }
            >
              <h2 className={styles.sectionHeading} id={section.id}>
                {section.heading}
              </h2>
              {/* 전역 reset이 list-style을 지우면 일부 보조기술이 목록 의미를 잃는다. */}
              <ul className={styles.itemList} role="list">
                {/* 카피 문자열은 한 섹션 안에서 중복될 수 있으므로 key로 쓰지 않는다. */}
                {section.items.map((item, index) => (
                  <li className={styles.item} key={`${section.id}-${String(index)}`}>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      </main>
    </div>
  )
}

/**
 * Google 공식 "G" 마크.
 *
 * 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을 하나 만들고, 외부 CDN이면 브라우저
 * 경계를 통째로 넘는다. 색은 Google 가이드가 소유하는 값이며 `tokens.css`가 그 값을 기록한다.
 *
 * `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트("Google로 계속하기")가 제공하므로 로고를
 * 다시 읽어 주면 이름이 두 번 들린다. 로고만 있는 버튼은 DESIGN.md가 금지한다.
 */
function GoogleMark(): ReactElement {
  return (
    <svg
      aria-hidden="true"
      className={styles.googleMark}
      focusable="false"
      viewBox="0 0 48 48"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
        fill="var(--component-social-sign-in-google-logo-red)"
      />
      <path
        d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
        fill="var(--component-social-sign-in-google-logo-blue)"
      />
      <path
        d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
        fill="var(--component-social-sign-in-google-logo-yellow)"
      />
      <path
        d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
        fill="var(--component-social-sign-in-google-logo-green)"
      />
    </svg>
  )
}
