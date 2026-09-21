import { useEffect, useRef, type ReactElement } from 'react'

import { ProviderMark } from '../components/ProviderMark'
import {
  authContent,
  authorizeHref,
  SIGN_IN_PROVIDERS,
  type AuthNoticeKind,
  type SignInProvider,
} from '../content/auth'
import { entryContent, MAIN_LANDMARK_ID } from '../content/entry'
import styles from './EntryScreen.module.css'

/**
 * 제공자별 버튼 규칙.
 *
 * 닫힌 맵이다. 2항 삼항식(`provider === 'google' ? … : …`)으로 두면 else가 곧 "나머지 제공자
 * 전부"가 되어, `SIGN_IN_PROVIDERS`에 카카오를 더하는 순간 카카오 버튼이 **네이버의 색과 N
 * 로고로** 렌더되고 타입 검사도 시험도 그것을 막지 못한다. `Record<SignInProvider, …>`는 제공자가
 * 늘 때 누락된 항목을 타입 검사가 잡는다 — `content/auth.ts`의 `signIn.labels`·`NOTICES`가 이미
 * 쓰는 형태다.
 *
 * 값 타입에 `undefined`가 붙는 것은 CSS 모듈의 타입 때문이다. `noUncheckedIndexedAccess`가
 * 켜져 있어 클래스 이름 조회가 `string | undefined`이고, `className`이 그 값을 그대로 받는다.
 */
const PROVIDER_BUTTON_CLASS: Readonly<Record<SignInProvider, string | undefined>> = {
  google: styles.googleButton,
  naver: styles.naverButton,
  kakao: styles.kakaoButton,
}

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
 * Story 1.4가 그 위에 Google 로그인 진입을, Story 1.6이 네이버를, Story 1.7이 카카오를 더했다.
 * 제공자 목록의 순서는 `SIGN_IN_PROVIDERS` 하나가 나른다 — 화면이 순서를 다시 적으면 두 곳이
 * 갈라진다.
 *
 * 로그인은 `<a href>` 최상위 내비게이션이다. 서버가 302로 제공자에 보내는 흐름이라 외부 스크립트도
 * iframe도 form POST도 필요 없고, 그래서 Nginx의 CSP(`default-src 'none'`, `connect-src 'self'`,
 * `form-action 'none'`)를 한 글자도 넓히지 않는다.
 *
 * 이 화면은 어떤 요청도 보내지 않는다. 세션 확인은 로그인 상태 경로에서만 한다.
 *
 * 표지를 달고 열리면 포커스를 페이지 제목으로 보낸다. 이 화면이 표지와 함께 나타나는 것은 언제나
 * 다른 화면에서의 전환(로그아웃, 세션 만료, 실패한 인증 왕복) 뒤이고, 그때 포커스가 문서 처음에
 * 남아 있으면 키보드·보조기술 사용자는 무엇이 바뀌었는지 알 수 없다. 표지가 없는 첫 방문에서는
 * 포커스를 옮기지 않는다 — 옮길 이유가 없고, 그러면 건너뛰기 링크가 첫 `Tab`이라는 계약도 그대로다.
 */
export function EntryScreen({ notice = null }: EntryScreenProps = {}): ReactElement {
  const title = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (notice !== null) {
      title.current?.focus()
    }
  }, [notice])

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
          {/*
            `tabIndex={-1}`이다. 프로그래밍 방식 포커스만 받고 `Tab` 순서에는 들어가지 않는다 —
            들어가면 읽기만 하는 제목이 조작 하나로 세어진다.
          */}
          <h1 className={styles.title} ref={title} tabIndex={-1}>
            {entryContent.pageTitle}
          </h1>
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

        {/*
          로그인 수단에 접근을 잃은 경우의 복구 안내. 인증이 실패한 표지에서만 나타난다.

          같은 창에서 열고 `rel="noreferrer"`로 참조 유출을 막는다. 진입점의
          `Referrer-Policy: no-referrer`가 이미 같은 일을 하지만, 이 링크들이 외부로 나가는 유일한
          자리이므로 마크업에도 적어 둔다. 새 창을 열지 않는 것은 EXPERIENCE.md의 계약이다.

          계정의 존재 여부를 드러내지 않는다. 조건문("접근할 수 없다면")이고 어떤 문장도 이
          브라우저에 계정이 있었는지 말하지 않는다. 이메일 확인이나 문의로 인증을 우회하는 경로를
          만들지 않으며 그 사실을 카피가 직접 말한다.
        */}
        {notice === 'failed' ? (
          <section aria-labelledby={authContent.recovery.id} className={styles.recovery}>
            <h2 className={styles.sectionHeading} id={authContent.recovery.id}>
              {authContent.recovery.heading}
            </h2>
            <p>{authContent.recovery.body}</p>
            {/*
              세 제공자를 언제나 함께 보여 준다. 이 화면은 사용자가 어느 제공자로 가입했는지 알 수
              없고 알아서도 안 되므로("당신이 쓴 제공자"를 화면에 도달시키는 경로가 곧 계정 존재
              신호다) 하나만 고를 수 없다. 순서는 버튼 목록과 같다.
            */}
            {authContent.recovery.links.map((link) => (
              <a
                className={styles.recoveryLink}
                href={link.href}
                key={link.provider}
                rel="noreferrer"
              >
                {link.label}
              </a>
            ))}
            <p className={styles.signInBoundary}>{authContent.recovery.caveat}</p>
          </section>
        ) : null}

        <section aria-labelledby={authContent.signIn.id} className={styles.signIn}>
          <h2 className={styles.sectionHeading} id={authContent.signIn.id}>
            {authContent.signIn.heading}
          </h2>

          {/*
            제공자 목록은 전체 폭 세로 스택이고 순서는 Google → 네이버 → 카카오로 고정이다
            (DESIGN.md). 추천 표지도, 마지막 사용 제공자 강조도, 로고만 있는 버튼도 없다.
            순서는 `SIGN_IN_PROVIDERS` 하나가 나른다.

            목록 전용 래퍼를 둔다. `.signIn`의 gap은 제목·버튼·안내문 전부에 걸리므로 버튼 사이
            간격(`--component-social-sign-in-gap`)이 안내문까지 밀어낸다. 그 값의 소유자는
            "제공자 목록의 바깥 간격"이지 섹션 전체가 아니다.

            지금 경로가 서버 허용 목록에 있으면 그 값을 `next`로 실어 보낸다. 로그인 상태 경로에
            미인증으로 들어와 이 화면으로 되돌려진 사용자가 로그인 뒤 원래 보려던 화면으로
            돌아가는 경로가 그 한 파라미터다. 진입 화면(`/`)에서는 붙지 않는다.
          */}
          <div className={styles.providerList}>
            {SIGN_IN_PROVIDERS.map((provider) => (
              <a
                className={PROVIDER_BUTTON_CLASS[provider]}
                href={authorizeHref(provider, window.location.pathname)}
                key={provider}
              >
                <ProviderMark provider={provider} />
                <span>{authContent.signIn.labels[provider]}</span>
              </a>
            ))}
          </div>

          {/* DESIGN.md가 버튼 아래에 두라고 정한 안내. */}
          <p className={styles.signInBoundary}>{authContent.signIn.boundary}</p>
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
