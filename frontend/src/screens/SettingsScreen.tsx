import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react'

import { BybitConnectionForm } from '../components/BybitConnectionForm'
import { ProviderMark } from '../components/ProviderMark'
import { ReauthPanel } from '../components/ReauthPanel'
import { SignOutButton } from '../components/SignOutButton'
import { UnlinkDialog } from '../components/UnlinkDialog'
import {
  authContent,
  linkNoticeKindOf,
  reauthNoticeKindOf,
  SIGN_IN_PROVIDERS,
  type SignInProvider,
} from '../content/auth'
import { connectionContent, formatVerifiedAt, rejectionMessage } from '../content/connection'
import { entryContent } from '../content/entry'
import { fetchLinkedProviders } from '../session/linkProvider'
import { useBybitConnection, type ConnectionNotice } from '../session/useBybitConnection'
import { useLinking } from '../session/useLinking'
import { useReauthentication } from '../session/useReauthentication'
import { liveRegionMessage, useSessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { useUnlinking } from '../session/useUnlinking'
import { EntryScreen } from './EntryScreen'
import styles from './SettingsScreen.module.css'

/**
 * 연결 조작의 결과 문장.
 *
 * 화면이 아니라 이 함수가 카피를 고른다. 세 갈래가 인라인 삼항식으로 흩어지면 새 갈래가 생길 때
 * 한쪽에만 추가되는 자리가 생기고, 그때 그 결과는 아무 문장도 없이 지나간다.
 *
 * **폼이 열려 있는 동안 거절은 여기서 나오지 않는다.** 그 문장은 폼 안의 `role="alert"` 오류
 * 요약이 이미 말하고, 두 곳에서 렌더하면 보조기술이 같은 문장을 두 번 읽는다 — assertive와
 * polite가 겹치는 자리이므로 소유자를 하나로 정한다. 폼이 닫힌 채 거절된 경우(준비 실패)는
 * 말할 자리가 이 region뿐이므로 그대로 나온다.
 */
function connectionNoticeText(notice: ConnectionNotice | null, formOpen: boolean): string | null {
  if (notice === null) {
    return null
  }
  if (notice.kind === 'succeeded') {
    return connectionContent.succeeded
  }
  if (notice.kind === 'cancelled') {
    return connectionContent.cancelled
  }
  return formOpen ? null : rejectionMessage(notice.code)
}

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'settings-main'

/**
 * 마지막 수단 설명 문장의 id.
 *
 * 제공자마다 다른 값이다. 목록에는 행이 셋 있고 id가 같으면 문서에 중복 id가 생긴다 — 마지막
 * 수단인 행은 하나뿐이라 실제로 겹치지는 않지만, 그 사실에 기대면 목록 조건이 바뀌는 날 조용히
 * 깨진다.
 */
function lastMethodDescriptionId(provider: SignInProvider): string {
  return `settings-last-method-${provider}`
}

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
 * 이미 연결된 수단에는 연결 조작 대신 해제 조작을 준다. 해제는 준비와 최종 확인 두 단계이며 그
 * 사이가 확인 대화상자다 — 대화상자를 여는 요청이 허가를 발급하고 무장할 뿐 <b>매핑·세션·목록을
 * 건드리지 않는다</b>.
 *
 * <b>마지막 남은 수단은 화면이 미리 설명한다.</b> 서버는 그 거부를 다른 거부와 구분하지 않지만
 * (구분하면 그 차이가 계정의 수단 구성을 알려 준다) 이 사실은 화면이 자기 목록에서 직접 만든다 —
 * 사용자가 이미 보고 있는 것이고, 숨기면 비활성화된 버튼에 이유가 없어 접근성이 깨진다. 권위는
 * 여전히 서버의 삭제 문장이므로 이 화면을 우회한 직접 호출도 같은 거부로 끝난다.
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
   *
   * 해제 성공도 같은 값을 옮긴다. 목록이 바뀌었으므로 다시 읽어야 하고, 그 요청을 여기 하나로
   * 모으면 "언제 목록을 다시 읽는가"의 답이 이 effect 하나로 남는다.
   */
  const [attempt, setAttempt] = useState(0)
  /**
   * 사용자가 마지막으로 시작한 흐름.
   *
   * 화면당 polite live region은 하나인데 결과를 만드는 흐름이 둘이다(해제와 연결). 우선순위를
   * "해제 표지가 없으면 연결 표지"로 두면 지난 연결 결과가 **되살아난다** — 새 해제 조작이
   * 자기 표지를 비우는 순간 그 자리로 옛 문장이 올라온다. 마지막으로 만진 흐름이 그 자리를
   * 소유하게 하면 그 되살아남이 구조적으로 불가능해진다.
   */
  const [lastFlow, setLastFlow] = useState<'auth' | 'connection'>('auth')

  const onUnlinked = useCallback((removed: SignInProvider): void => {
    /*
     * 사라진 수단을 먼저 목록에서 뺀다.
     *
     * 재조회가 돌아오기 전까지 그 행은 여전히 "연결됨"과 해제 버튼을 그린다. 그 사이에 누르면
     * 서버가 거절해 실패 표지가 나고, 사용자는 방금 해제한 것을 다시 해제하려다 실패를 본다.
     * 서버가 무엇을 지웠는지는 이 화면이 이미 알고 있으므로 기다릴 이유가 없다.
     */
    setLinked((current) => current.filter((provider) => provider !== removed))
    setAttempt((value) => value + 1)
  }, [])

  const unlinking = useUnlinking(csrfToken, onUnlinked)
  const connection = useBybitConnection(csrfToken, gate === 'ready')

  /**
   * 해제 결과 문장의 자리.
   *
   * 포커스를 여기로 옮겨야 하는 경우가 하나 있다 — 해제에 성공하면 대화상자를 연 버튼이 사라져
   * (그 제공자가 더 이상 연결돼 있지 않다) 대화상자가 되돌릴 호출점이 없다. 그때 포커스는
   * `<body>`에 남고, 키보드·보조기술 사용자는 무엇이 일어났는지 듣지 못한 채 문서 처음으로
   * 떨어진다.
   */
  const unlinkNoticeRef = useRef<HTMLParagraphElement>(null)

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

  /*
   * 해제에 성공하면 결과 문장으로 포커스를 옮긴다.
   *
   * 성공에서만 옮기는 것이 계약이고 그 반대편이 대화상자의 호출점 복귀다 — 취소·실패에서는 그
   * 버튼이 그대로 있으므로 대화상자가 돌려주고, 성공에서는 곧 사라질 버튼이라 대화상자가 손을
   * 떼고 이 effect가 받는다(`UnlinkDialog`의 `restoreFocus`). 두 자리가 조건을 나눠 갖지 않으면
   * 포커스가 잠깐 그 버튼에 얹혔다가 목록 갱신과 함께 `<body>`로 떨어진다.
   *
   * `activeElement`를 보고 정하지 않는다. 대화상자가 닫히는 시점에는 호출점 버튼이 아직 문서에
   * 있어 그 판정이 언제나 거짓이 되고, 실제 포커스 상실은 그 뒤 목록이 갱신될 때 일어난다.
   */
  const unlinkNotice = unlinking.notice
  useEffect(() => {
    if (unlinkNotice === 'unlinkSucceeded') {
      unlinkNoticeRef.current?.focus()
    }
  }, [unlinkNotice])

  /*
   * 연결에 성공하면 같은 문장으로 포커스를 옮긴다.
   *
   * 해제와 같은 이유다 — 성공하면 폼이 사라지고 그 안의 제출 버튼도 함께 사라져 포커스가
   * `<body>`로 떨어진다. 그때 키보드·보조기술 사용자는 무엇이 일어났는지 듣지 못한 채 문서
   * 처음으로 간다. 취소·실패에서는 옮기지 않는다: 취소는 폼을 연 버튼이 그대로 있고, 실패는
   * 폼이 남아 있어 그 안의 Secret 필드가 포커스를 받는다.
   */
  const connectionNotice = connection.notice
  useEffect(() => {
    if (connectionNotice?.kind === 'succeeded') {
      unlinkNoticeRef.current?.focus()
    }
  }, [connectionNotice])

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
  /**
   * 대화상자가 열려 있는가.
   *
   * 배경을 비활성화하는 판정이자 배경 조작을 막는 판정이다. `inert`가 없는 브라우저에서도 배경의
   * 연결·본인 확인·로그아웃이 눌리면 안 된다 — 그 조작들은 열려 있는 해제 무장을 버리므로, 사용자가
   * 확인을 누르는 순간 이유 없이 실패한다.
   */
  const dialogOpen = unlinking.confirming !== null
  /**
   * 연결 구역의 조작을 시작한다.
   *
   * 두 가지를 함께 한다 — 이 흐름이 live region을 소유하게 하고, 그 조작을 실제로 부른다. 호출
   * 자리마다 두 줄을 적으면 하나를 빠뜨리는 날 그 조작의 결과가 엉뚱한 흐름의 표지 아래 묻힌다.
   */
  const runConnection = (action: () => void): void => {
    setLastFlow('connection')
    action()
  }

  /** 인증 흐름(연결·해제·본인 확인·로그아웃)의 조작. {@link runConnection}의 짝이다. */
  const runAuth = (action: () => void): void => {
    setLastFlow('auth')
    action()
  }

  const busy =
    connection.pending ||
    linking.pending ||
    reauthentication.pending ||
    signOut.pending ||
    unlinking.pending ||
    dialogOpen
  /**
   * 마지막 남은 접근 수단인가.
   *
   * 목록을 확인한 뒤에만 참이다. 확인하지 못한 상태에서는 목록 자체를 그리지 않으므로
   * (`linkStatus === 'unavailable'`) 이 값이 쓰이지 않는다.
   */
  const lastMethod = linked.length <= 1
  /**
   * live region이 말할 결과 문장.
   *
   * 해제와 연결이 같은 자리를 나눠 쓴다. 두 조작은 동시에 끝날 수 없고(`busy`가 서로를 막는다)
   * 화면당 polite live region은 하나여야 한다.
   */
  const resultMessage =
    lastFlow === 'connection'
      ? connectionNoticeText(connection.notice, connection.confirmation !== null)
      : unlinking.notice === null
        ? null
        : authContent.notices[unlinking.notice]

  return (
    <>
      {/*
        대화상자가 열려 있는 동안 배경을 비활성화한다.

        `inert`가 없으면 스크린리더의 가상 커서가 뒤 화면을 그대로 읽고 포인터·키보드가 그 조작에
        닿는다 — 모달의 뜻이 성립하지 않는다. 대화상자를 이 요소의 **형제**로 둔 이유가 그것이다:
        안에 두면 그 속성이 대화상자까지 덮는다.
      */}
      <div className={styles.page} inert={dialogOpen}>
        {/* 건너뛰기 링크는 두 인증 화면과 같은 관용이다. 반복되는 머리말을 지나칠 첫 조작이다. */}
        <a className={styles.skipLink} href={`#${MAIN_ID}`}>
          {entryContent.skipLink}
        </a>

        <header className={styles.header}>
          <p className={styles.brand}>{entryContent.brand}</p>
          {/* 세션이 확인된 뒤에만 둔다. 확인 전에는 로그아웃할 대상이 아직 없다. */}
          {ready ? (
            /*
              다른 조작이 도는 동안에는 막힌다(`ReauthPanel`과 같은 배치). 로그아웃이 그 사이에
              성립하면 방금 심은 무장이 세션과 함께 사라지고, 특히 확인 대화상자가 열려 있는 동안
              눌리면 사용자가 확인을 누르는 순간 이유 없이 실패한다.
            */
            <SignOutButton
              onSignOut={() => {
                if (busy && !signOut.pending) {
                  return
                }
                runAuth(signOut.requestSignOut)
              }}
              pending={busy}
            />
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
            {ready && !busy ? (
              /*
              해제·연결 결과는 이 live region이 말한다. 섹션 안에 두면 취소·실패에서 포커스도
              움직이지 않아 보조기술 사용자에게 아무것도 알려지지 않는다 — 두 조작 모두 문서를
              떠나지 않으므로 화면 어디에도 변화 신호가 없다.

              한 자리가 둘을 맡는다. 화면당 polite live region은 하나이고, 두 조작이 동시에
              끝날 수는 없다(`busy`가 서로를 막는다).
            */
              resultMessage === null ? null : (
                <p className={styles.noticeText} ref={unlinkNoticeRef} tabIndex={-1}>
                  {resultMessage}
                </p>
              )
            ) : (
              <p className={styles.noticeText}>
                {liveRegionMessage(
                  gate,
                  signOut.pending,
                  reauthentication.pending,
                  linking.pending,
                  unlinking.pending,
                  connection.pending,
                )}
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
                          <span className={styles.linkedActions}>
                            <span className={styles.linkedState}>
                              {authContent.settings.linked}
                            </span>
                            {/*
                            마지막 남은 수단이면 비활성이고 이유를 문장으로 말한다.

                            `aria-disabled`이지 `disabled`가 아니다. 그 속성은 요소를 탭 순서에서
                            떨어뜨려 키보드 사용자가 그 버튼의 존재도 이유도 알 수 없게 만든다 —
                            여기서는 그 이유가 정확히 사용자가 알아야 하는 것이다. 실제 차단은
                            아래 `onClick`의 가드가 하고, 서버의 삭제 문장이 최종 방어다.

                            설명은 `aria-describedby`로 버튼에 붙인다. 문장이 버튼 옆에 있기만
                            하면 보조기술은 그 둘의 관계를 모른다.
                          */}
                            <button
                              aria-describedby={
                                lastMethod ? lastMethodDescriptionId(provider) : undefined
                              }
                              aria-disabled={busy || lastMethod}
                              className={styles.unlinkButton}
                              onClick={() => {
                                if (busy || lastMethod) {
                                  return
                                }
                                runAuth(() => {
                                  unlinking.requestUnlink(provider)
                                })
                              }}
                              type="button"
                            >
                              {authContent.settings.unlinkLabels[provider]}
                            </button>
                            {lastMethod ? (
                              <span
                                className={styles.lastMethod}
                                id={lastMethodDescriptionId(provider)}
                              >
                                {authContent.settings.lastMethod}
                              </span>
                            ) : null}
                          </span>
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
                              runAuth(() => {
                                linking.requestLink(provider)
                              })
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

              {/*
                읽기 전용 Bybit 연결(Story 2.1).

                새 내부 경로를 만들지 않는다. UX-DR15가 설정 화면을 이 조작의 집으로 정했고, 경로를
                더하면 등가 검사 없는 다섯 곳을 또 손으로 맞춰야 한다.

                폼은 준비가 200으로 답한 뒤에만 열린다. 여는 요청은 허가를 발급하고 무장할 뿐
                아무것도 바꾸지 않으며, 취소는 그 둘을 실제로 되돌린다.
              */}
              <section aria-labelledby={connectionContent.id} className={styles.section}>
                <h2 className={styles.sectionHeading} id={connectionContent.id}>
                  {connectionContent.heading}
                </h2>
                <p>{connectionContent.body}</p>
                <p className={styles.boundary}>{connectionContent.requirements}</p>

                {connection.view.status === 'checking' ? (
                  <p className={styles.checking}>{connectionContent.checking}</p>
                ) : connection.view.status === 'unavailable' ? (
                  /*
                   * 확인하지 못했다. 연결 여부를 주장하지 않는 것이 요점이다 — "연결 없음"으로
                   * 접으면 이미 연결된 계정에도 연결 버튼이 붙고 그 클릭이 엉뚱한 안내로 끝난다.
                   */
                  <>
                    <p className={styles.linkNotice}>{connectionContent.unavailable}</p>
                    <button
                      /*
                       * `aria-disabled`가 없으면 `busy` 가드가 조용히 무시한다 — 보조기술은 누를
                       * 수 있는 버튼이라 말하고 클릭은 아무 일도 하지 않는다. 형제 조작과 같은
                       * 표현으로 맞춘다.
                       */
                      aria-disabled={busy}
                      className={styles.retryButton}
                      onClick={() => {
                        if (busy) {
                          return
                        }
                        runConnection(connection.refresh)
                      }}
                      type="button"
                    >
                      {connectionContent.retry}
                    </button>
                  </>
                ) : connection.view.connected ? (
                  <>
                    <p className={styles.linkedState}>{connectionContent.connected}</p>
                    {/*
                      검증 시각과 적용 정책 버전. 자산도 계정 식별자도 그리지 않는다 — 이 조회는
                      공식 현재가치 스냅샷이 아니며 그 발행은 Story 2.2가 소유한다.
                    */}
                    <dl className={styles.connectionMeta}>
                      <div className={styles.connectionMetaRow}>
                        <dt className={styles.connectionTerm}>
                          {connectionContent.verifiedAtLabel}
                        </dt>
                        <dd className={styles.connectionValue}>
                          {connection.view.verifiedAt === null
                            ? ''
                            : formatVerifiedAt(connection.view.verifiedAt)}
                        </dd>
                      </div>
                      <div className={styles.connectionMetaRow}>
                        <dt className={styles.connectionTerm}>
                          {connectionContent.policyVersionLabel}
                        </dt>
                        <dd className={styles.connectionValue}>
                          {connection.view.policyVersion ?? ''}
                        </dd>
                      </div>
                    </dl>
                    <p className={styles.boundary}>{connectionContent.connectedBoundary}</p>
                  </>
                ) : connection.confirmation === null ? (
                  <>
                    <p className={styles.checking}>{connectionContent.notConnected}</p>
                    <button
                      /*
                       * `aria-disabled`이지 `disabled`가 아니다. 그 속성은 요소를 탭 순서에서
                       * 떨어뜨리므로 방금 `Enter`로 실행한 키보드·스크린리더 사용자가 그 순간
                       * 포커스를 잃는다. 실제 중복 제출 차단은 훅의 ref 가드가 한다.
                       */
                      aria-disabled={busy}
                      className={styles.retryButton}
                      onClick={() => {
                        if (busy) {
                          return
                        }
                        runConnection(connection.start)
                      }}
                      type="button"
                    >
                      {connectionContent.start}
                    </button>
                  </>
                ) : (
                  <BybitConnectionForm
                    confirmation={connection.confirmation}
                    onCancel={() => {
                      runConnection(connection.cancel)
                    }}
                    onSubmit={async (apiKey, apiSecret) => {
                      setLastFlow('connection')
                      return connection.submit(apiKey, apiSecret)
                    }}
                    /*
                     * 형제 조작과 같은 게이트다. `connection.pending`만 보면 다른 요청이 도는
                     * 동안에도 비밀을 실은 제출이 나갈 수 있고, 그것은 이 화면의 다른 어떤
                     * 조작에도 없는 예외가 된다.
                     */
                    pending={busy}
                    rejection={
                      connection.notice?.kind === 'rejected'
                        ? rejectionMessage(connection.notice.code)
                        : null
                    }
                  />
                )}
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
                        runAuth(() => {
                          reauthentication.requestReauthentication(provider)
                        })
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

      {/*
        확인 대화상자. 준비 요청이 204로 답한 뒤에만 열린다.

        위 화면의 <b>형제</b>다. 안에 두면 배경을 비활성화하는 `inert`가 대화상자까지 덮어 어떤
        조작도 닿지 않는다.

        해제에 성공하면 호출점으로 돌려주지 않는다(`restoreFocus`). 그 버튼은 곧 사라지고, 포커스는
        화면이 결과 문장으로 옮겨 받는다.
      */}
      {unlinking.confirming === null ? null : (
        <UnlinkDialog
          onCancel={unlinking.cancel}
          onConfirm={unlinking.confirm}
          pending={unlinking.pending}
          provider={unlinking.confirming}
          restoreFocus={unlinking.notice !== 'unlinkSucceeded'}
        />
      )}
    </>
  )
}
