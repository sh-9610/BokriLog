import { useEffect, useState, type ReactElement } from 'react'

import { ProviderMark } from '../components/ProviderMark'
import { ReauthPanel } from '../components/ReauthPanel'
import { SignOutButton } from '../components/SignOutButton'
import {
  authContent,
  linkNoticeKindOf,
  reauthNoticeKindOf,
  SIGN_IN_PROVIDERS,
  type SignInProvider,
} from '../content/auth'
import { entryContent } from '../content/entry'
import { fetchLinkedProviders } from '../session/linkProvider'
import { useLinking } from '../session/useLinking'
import { useReauthentication } from '../session/useReauthentication'
import { liveRegionMessage, useSessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { EntryScreen } from './EntryScreen'
import styles from './SettingsScreen.module.css'

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'settings-main'

/**
 * 제공자별 버튼 규칙. 닫힌 맵인 근거는 진입 화면·재인증 패널과 같다.
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

/**
 * 연결 목록 조회의 상태.
 *
 * `unavailable`이 따로 있는 것이 요점이다. 실패를 빈 목록으로 접으면 이미 연결된 수단에도 연결
 * 버튼이 그려지고, 그 클릭은 서버가 거절해 엉뚱한 안내로 끝난다 — 사용자는 무엇이 잘못됐는지
 * 알 수 없고 고칠 수도 없다.
 */
type LinkStatus = 'checking' | 'loaded' | 'unavailable'

/**
 * 로그인 수단 설정 화면.
 *
 * 이 화면이 보여 주는 것은 계정의 사실 하나다 — 세 제공자 각각이 이 계정에 연결됐는가. 서버가
 * 돌려주는 값이 제공자 이름뿐이므로(`GET /api/auth/link`) `issuer`·`subject`·이메일·연결 시각은
 * 화면에 도달하지 않는다.
 *
 * 연결은 세 단계가 순서대로 성립해야 한다 — 기존 수단으로 본인 확인 → 연결 작업용 허가 발급 →
 * 대상 제공자 왕복. 앞 두 단계는 서버가 판정하고 이 화면은 두 조작을 제공할 뿐이다. 본인 확인이
 * 신선하지 않으면 연결 시작이 거부되고 표지 하나가 "본인 확인을 마친 뒤 다시 연결해 주세요"를
 * 말한다 — <b>자동으로 이어 하지 않는 것이 계약이다</b>(AC-6). 사용자가 아래 본인 확인 버튼을
 * 직접 누르고, 확인을 마친 뒤 이 화면에서 다시 연결을 누른다.
 *
 * 이미 연결된 수단에는 연결 조작을 주지 않는다. 해제는 이 스토리의 범위가 아니므로(Story 1.11)
 * 그 수단에 남는 것은 "연결됨" 상태 하나이며, 마지막 수단 보호와 확인 대화상자는 그 스토리가
 * 자기 작업과 함께 만든다.
 *
 * 서버 세션이 유일한 권위다. 확인 전에는 어떤 상태도 주장하지 않고, 미인증은 인증 화면으로
 * 되돌린다. 재인증을 요구받은 세션은 이동하지 않고 이 자리에서 회복 패널을 연다 — 두 인증 화면과
 * 같은 배치이며 같은 공유 컴포넌트다.
 */
export function SettingsScreen(): ReactElement {
  const { csrfToken, gate } = useSessionGate('settings')
  const signOut = useSignOut(csrfToken)
  const reauthentication = useReauthentication(csrfToken)
  const linking = useLinking(csrfToken)

  const [linked, setLinked] = useState<readonly SignInProvider[]>([])
  const [linkStatus, setLinkStatus] = useState<LinkStatus>('checking')
  /**
   * 다시 확인하기.
   *
   * 값이 바뀌면 아래 effect가 다시 돈다. 자동 재시도가 아니라 사용자의 조작 하나가 그 값을
   * 옮기며, 그것이 "실패를 반복 요청으로 덮지 않는다"는 이 저장소의 규율이다.
   */
  const [attempt, setAttempt] = useState(0)

  const ready = gate === 'ready'

  /*
   * 연결 목록은 게이트가 `ready`일 때만 확인한다.
   *
   * 미인증·확인 중·표시된 세션에서 이 요청을 보내면 서버가 403으로 답하는 상태 조회가 하나
   * 늘어날 뿐이고, 그 응답으로 화면이 할 일도 달라지지 않는다. 자동 재시도하지 않는 것은 세션
   * 확인과 같은 규율이다.
   */
  useEffect(() => {
    if (!ready) {
      return
    }
    let current = true
    void fetchLinkedProviders().then((result) => {
      if (!current) {
        return
      }
      if (result.status === 'loaded') {
        setLinked(result.providers)
      }
      setLinkStatus(result.status)
    })
    return () => {
      current = false
    }
  }, [attempt, ready])

  if (gate === 'unauthenticated') {
    /*
     * 냉시작 미인증이다. 이 자리에서 표지 없이 인증 화면을 그린다(두 인증 화면과 같은 동작).
     *
     * 만료는 여기 오지 않는다 — 게이트가 `redirecting`으로 답하고 `/?auth=expired`로 보내므로
     * 만료 문장의 출처는 주소의 표지 하나다.
     */
    return <EntryScreen notice="signedOut" />
  }

  /*
   * 재인증을 요구받은 세션이다. 이동하지 않고 이 자리에서 회복 경로를 연다 — 인증 화면으로
   * 보내면 그 화면의 로그인 버튼을 서버가 "이미 인증된 세션"으로 거절해 무한 왕복이 된다.
   */
  const reauthRequired = gate === 'reauth-required'
  const notice = linkNoticeKindOf(window.location.search)
  const busy = linking.pending || reauthentication.pending || signOut.pending

  return (
    <div className={styles.page}>
      {/* 건너뛰기 링크는 두 인증 화면과 같은 관용이다. 반복되는 머리말을 지나칠 첫 조작이다. */}
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
          화면당 polite live region 하나. 두 인증 화면과 같은 배치이며 진행을 말하는 자리도 여기다.

          표시된 세션은 갱신 중이 아니다. 게이트가 `reauth-required`인 상태는 확인이 끝나고 다음
          행동을 기다리는 자리이므로 `aria-busy`를 세우면 보조기술에 "지금 바뀌는 중"이라고
          잘못 말한다.
        */}
        <div
          aria-busy={(!ready && !reauthRequired) || busy}
          aria-live="polite"
          className={styles.notice}
          role="status"
        >
          {ready && !busy ? null : (
            <p className={styles.noticeText}>
              {liveRegionMessage(gate, signOut.pending, reauthentication.pending, linking.pending)}
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
          <>
            <section aria-labelledby={authContent.settings.id} className={styles.section}>
              <h2 className={styles.sectionHeading} id={authContent.settings.id}>
                {authContent.settings.heading}
              </h2>
              <p>{authContent.settings.body}</p>

              {/*
                표지. 서버가 콜백 뒤에 붙이거나(취소·실패) 클라이언트가 시작 실패에 붙인다.
                두 값 모두 무엇이 어긋났는지 말하지 않으며, 그 침묵이 다른 계정의 존재를 감춘다.
              */}
              {notice === null ? null : (
                <p className={styles.linkNotice}>{authContent.notices[notice]}</p>
              )}

              {linkStatus === 'checking' ? (
                <p className={styles.checking}>{authContent.settings.status}</p>
              ) : linkStatus === 'unavailable' ? (
                /*
                 * 확인하지 못했다. 목록을 그리지 않는 것이 요점이다 — 빈 목록을 그리면 이미
                 * 연결된 수단에도 연결 버튼이 붙고 그 클릭이 엉뚱한 안내로 끝난다.
                 */
                <>
                  <p className={styles.linkNotice}>{authContent.settings.unavailable}</p>
                  <button
                    className={styles.retryButton}
                    onClick={() => {
                      // 확인 중으로 되돌리는 것은 이 조작의 일이다. effect 본문에서 하면 렌더가
                      // 한 번 더 돌고 `react-hooks/set-state-in-effect`가 그것을 막는다.
                      setLinkStatus('checking')
                      setAttempt((value) => value + 1)
                    }}
                    type="button"
                  >
                    {authContent.settings.retry}
                  </button>
                </>
              ) : (
                <ul className={styles.providerList} role="list">
                  {SIGN_IN_PROVIDERS.map((provider) => (
                    <li className={styles.providerRow} key={provider}>
                      <span className={styles.providerName}>
                        {authContent.settings.providerNames[provider]}
                      </span>
                      {linked.includes(provider) ? (
                        <span className={styles.linkedState}>{authContent.settings.linked}</span>
                      ) : (
                        <button
                          /*
                           * `aria-disabled`이지 `disabled`가 아니다. 그 속성은 요소를 탭 순서에서
                           * 떨어뜨리므로 방금 `Enter`로 이 버튼을 실행한 키보드·스크린리더
                           * 사용자가 그 순간 포커스를 잃는다. 실제 중복 제출 차단은 화면이 소유한
                           * 가드가 한다(`useLinking`).
                           */
                          aria-disabled={busy}
                          className={PROVIDER_BUTTON_CLASS[provider]}
                          onClick={() => {
                            if (busy) {
                              return
                            }
                            linking.requestLink(provider)
                          }}
                          type="button"
                        >
                          <ProviderMark provider={provider} />
                          <span>{authContent.settings.linkLabels[provider]}</span>
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}

              <p className={styles.boundary}>{authContent.settings.boundary}</p>
            </section>

            <section aria-labelledby={authContent.settings.reauth.id} className={styles.section}>
              <h2 className={styles.sectionHeading} id={authContent.settings.reauth.id}>
                {authContent.settings.reauth.heading}
              </h2>
              <p>{authContent.settings.reauth.body}</p>

              <div className={styles.providerActions}>
                {SIGN_IN_PROVIDERS.map((provider) => (
                  <button
                    aria-disabled={busy}
                    className={PROVIDER_BUTTON_CLASS[provider]}
                    key={provider}
                    onClick={() => {
                      if (busy) {
                        return
                      }
                      reauthentication.requestReauthentication(provider)
                    }}
                    type="button"
                  >
                    <ProviderMark provider={provider} />
                    <span>{authContent.settings.reauth.labels[provider]}</span>
                  </button>
                ))}
              </div>
            </section>
          </>
        ) : null}
      </main>
    </div>
  )
}
