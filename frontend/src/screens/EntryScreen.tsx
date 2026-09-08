import { useEffect, useRef, type ReactElement } from 'react'

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
}

/** 제공자별 로고. 같은 이유로 닫힌 맵이다. */
const PROVIDER_MARK: Readonly<Record<SignInProvider, () => ReactElement>> = {
  google: GoogleMark,
  naver: NaverMark,
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
 * Story 1.4가 그 위에 Google 로그인 진입을, Story 1.6이 네이버를 더했다. 제공자 목록의 순서는
 * `SIGN_IN_PROVIDERS` 하나가 나른다 — 화면이 순서를 다시 적으면 두 곳이 갈라진다.
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
              두 제공자를 언제나 함께 보여 준다. 이 화면은 사용자가 어느 제공자로 가입했는지 알 수
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
            (DESIGN.md). 지금 실제 버튼은 앞의 둘이며 추천 표지도, 마지막 사용 제공자 강조도,
            로고만 있는 버튼도 없다. 순서는 `SIGN_IN_PROVIDERS` 하나가 나른다.

            목록 전용 래퍼를 둔다. `.signIn`의 gap은 제목·버튼·안내문 전부에 걸리므로 버튼 사이
            간격(`--component-social-sign-in-gap`)이 안내문까지 밀어낸다. 그 값의 소유자는
            "제공자 목록의 바깥 간격"이지 섹션 전체가 아니다.

            지금 경로가 서버 허용 목록에 있으면 그 값을 `next`로 실어 보낸다. 로그인 상태 경로에
            미인증으로 들어와 이 화면으로 되돌려진 사용자가 로그인 뒤 원래 보려던 화면으로
            돌아가는 경로가 그 한 파라미터다. 진입 화면(`/`)에서는 붙지 않는다.
          */}
          <div className={styles.providerList}>
            {SIGN_IN_PROVIDERS.map((provider) => {
              const Mark = PROVIDER_MARK[provider]
              return (
                <a
                  className={PROVIDER_BUTTON_CLASS[provider]}
                  href={authorizeHref(provider, window.location.pathname)}
                  key={provider}
                >
                  <Mark />
                  <span>{authContent.signIn.labels[provider]}</span>
                </a>
              )
            })}
          </div>

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

/**
 * 네이버 공식 "N" 로고.
 *
 * `GoogleMark`와 같은 방식의 인라인 SVG다. 외부 파일이면 브라우저가 매 로드마다 요청을 하나 만들고,
 * 외부 CDN·외부 이미지는 브라우저 경계를 통째로 넘는다. 색은 네이버 가이드가 소유하는 값이며
 * `tokens.css`가 그 값을 기록한다.
 *
 * **경로는 공식 애셋에서 추출한 값이다.** 손으로 그린 근사가 아니다. 출처는 공식 버튼 가이드
 * (`developers.naver.com/docs/login/bi/bi.md`)가 링크하는 한글 버튼 애셋
 * `https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.ai`(PDF 1.5, 2026-09-09
 * 내려받음, 애셋 내 버튼 파일 날짜 2025-12-04)이며, 그 안의 N 마크는 곡선 없는 10점 직선 경로다.
 * 아래 좌표는 그 애셋의 16×16 인스턴스를 SVG 좌표계로 변환한 것이다(y축 뒤집음).
 *
 * **bbox가 정확한 정사각형이라 24px 박스에서 잉크가 박스를 가득 채운다.** 경로의 bounding box는
 * x 0~16 · y 0~16이고 애셋의 16×16·18×18·20×20 인스턴스가 모두 같은 성질을 갖는다. 이것이
 * 손으로 그린 이전 경로와의 실질적 차이다 — 그 경로는 `viewBox="0 0 20 20"` 안에서 잉크가
 * 10×9만 차지해 24px 박스에서 실제 잉크 높이가 10.8px이었고, 그러면 가이드의 "텍스트는 로고
 * 높이보다 작은 크기"가 렌더 결과에서 깨진다(레이블 20px). 게다가 가이드가 금지하는 첫 항목이
 * 로고 변형이다.
 *
 * 로고를 변형·조합하지 않는다. 완성형(로고 + 레이블)의 공표된 하한은 16px이고 실제 크기는
 * `--component-social-sign-in-naver-logo-size`(24px)다 — "텍스트는 로고 높이보다 작은 크기"라는
 * 가이드 요구와 레이블의 WCAG 큰 텍스트 문턱이 함께 그 값을 정한다(근거는 tokens.css의 그 토큰
 * 주석). 잉크 높이를 실제로 재는 단정은 `e2e/auth-entry.spec.ts`가 갖는다.
 *
 * `aria-hidden`이다. 접근 가능한 이름은 옆의 텍스트("네이버로 계속하기")가 제공하므로 로고를 다시
 * 읽어 주면 이름이 두 번 들린다. 로고만 있는 버튼은 DESIGN.md가 금지한다.
 */
function NaverMark(): ReactElement {
  return (
    <svg
      aria-hidden="true"
      className={styles.naverMark}
      focusable="false"
      viewBox="0 0 16 16"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        d="M10.848 8.565 L4.916 0 L0 0 L0 16 L5.151 16 L5.151 7.438 L11.083 16 L16 16 L16 0 L10.848 0 Z"
        fill="var(--component-social-sign-in-naver-logo)"
      />
    </svg>
  )
}
