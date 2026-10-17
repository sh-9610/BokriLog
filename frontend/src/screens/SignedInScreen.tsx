import { useState, type ReactElement } from 'react'

import { DashboardStatusText } from '../components/DashboardStatusText'
import { FirstSnapshotPanel } from '../components/FirstSnapshotPanel'
import { NextPlanBaselinePanel } from '../components/NextPlanBaselinePanel'
import { PlanEndDialog } from '../components/PlanEndDialog'
import { PlanHistoryPanel } from '../components/PlanHistoryPanel'
import { PlanResultPanel } from '../components/PlanResultPanel'
import { ReauthPanel } from '../components/ReauthPanel'
import { ServiceNotice } from '../components/ServiceNotice'
import { SignOutButton } from '../components/SignOutButton'
import { authContent, DASHBOARD_PATH, reauthNoticeKindOf, SETTINGS_PATH } from '../content/auth'
import { entryContent } from '../content/entry'
import { useDashboardAdjustments } from '../session/useAdjustmentHistory'
import { useDashboardEvidence } from '../session/useCalculationEvidence'
import {
  bundleOf,
  planPresenceOf,
  statusBusy,
  terminalOf,
  useDashboard,
} from '../session/useDashboard'
import { useDashboardSync } from '../session/useDashboardSync'
import { usePlanEnd } from '../session/usePlanEnd'
import { useReauthentication } from '../session/useReauthentication'
import { useServiceNotices } from '../session/useServiceNotices'
import { liveRegionMessage, useSessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { useTelemetry } from '../telemetry/useTelemetry'
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
 * 나가는 길이 이 화면에 있다. 로그아웃 버튼은 머리말에 있고 건너뛰기 링크 다음 순서다 — 반복되는
 * 머리말을 지나치려는 사용자가 먼저 만나는 것은 건너뛰기 링크여야 한다. 진행 중임을 말하는 것은
 * 버튼이 아니라 아래 live region이다(화면당 하나). 버튼의 이름은 언제나 동작으로 남는다.
 *
 * 공식 대시보드(Story 2.8)가 이 화면의 본문이다. 대시보드 조회 모델 응답 하나에서 `계획 없음`·
 * `결과 준비 중`·공식 묶음(현재가치 → 세 신뢰 축 → 지표 → 다음 행동)을 그리고, 읽기 실패는
 * `마지막 정상 결과`·`재시도 가능`으로 구분한다. 상태 변화는 이 화면의 단일 live region이 한 문장으로
 * 알린다. 계산 근거(Story 2.9)는 같은 패널 안에서 펼치고, 그 읽기 상태도 같은 live region이 대시보드
 * 문장 뒤에 이어 말한다. 조정 이력(Story 2.10)도 같은 패널 안에서 펼치고, 그 읽기 상태는 근거 문장 뒤에
 * 이어 말한다. 동기화(Story 2.11)는 신선도 축 바로 뒤에서 조작하고 — 대시보드 진입과 오래 숨겨 둔 탭으로
 * 돌아오는 순간에도 한 번씩 요청한다 — 그 진행과 결과도 같은 live region이 대시보드 문장 자리에서 한 번씩 말한다.
 * 계획 중도 종료(Story 2.13)는 패널 끝의 `계획 종료` 영역과 장기 미동기화 선택지의 `새 계획 시작`에서 시작하고, 확인
 * 대화상자는 본문을 감싼 `inert` 래퍼의 형제로 연다. 종료되면 같은 패널이 기록 모드가 되고, 그 사실은 같은 live
 * region이 한 번 말한다.
 */
export function SignedInScreen(): ReactElement {
  const { csrfToken, gate } = useSessionGate('dashboard')
  // 이 문서의 Web Vitals(Story 4.5). 세션 토큰이 있을 때만 숨김·떠남에서 보낸다.
  useTelemetry(DASHBOARD_PATH, csrfToken)
  const signOut = useSignOut(csrfToken)
  const reauthentication = useReauthentication(csrfToken)
  const [selected, setSelected] = useState<string | null>(null)
  const [preparationMessage, setPreparationMessage] = useState('')
  const dashboard = useDashboard(gate === 'ready', selected)
  const preparationScope =
    gate === 'ready' && selected === null && terminalOf(bundleOf(dashboard.view)) !== null
      ? (bundleOf(dashboard.view)?.result.planId ?? null)
      : null
  const [messageScope, setMessageScope] = useState(preparationScope)
  if (messageScope !== preparationScope) {
    setMessageScope(preparationScope)
    setPreparationMessage('')
  }

  // The calculation evidence of the bundle the dashboard shows (Story 2.9), in its own state.
  const evidence = useDashboardEvidence(dashboard)
  // The adjustment history of the same bundle (Story 2.10), in its own state.
  const adjustments = useDashboardAdjustments(dashboard)
  // The user-time sync of the shown plan (Story 2.11): entry, tab return and the button, in its own state.
  const sync = useDashboardSync(dashboard, selected === null ? csrfToken : null)
  // The explicit early end of the shown plan (Story 2.13): reauthentication, the confirmation dialog, the end.
  const planEnd = usePlanEnd(dashboard, csrfToken)
  // Whether the first-snapshot panel leads the dashboard, and how (`planPresenceOf` gives the reasons).
  const presence = planPresenceOf(dashboard.view)
  // 서비스 공지(Story 3.5): 대시보드 응답과 따로 읽고 따로 그린다 — 마지막 정상값과 섞이지 않는다.
  const notices = useServiceNotices(gate === 'ready')

  if (gate === 'unauthenticated') {
    /*
     * 냉시작 미인증이다. 이 자리에서 표지 없이 인증 화면을 그린다(Story 1.4의 동작).
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
  /*
   * 계획 종료 확인 대화상자가 열려 있는가(Story 2.13). 열려 있는 동안 배경을 비활성화한다 — `inert`가 없으면
   * 스크린리더의 가상 커서가 뒤 화면을 읽고 포인터·키보드가 그 조작에 닿는다. 대화상자는 이 래퍼의 형제다(설정
   * 화면과 같은 관용): 안에 두면 그 속성이 대화상자까지 덮는다.
   */
  const dialog = ready ? planEnd.dialog : null

  return (
    <>
      <div className={styles.page} inert={dialog !== null}>
        {/* 건너뛰기 링크는 진입 화면과 같은 관용이다. 반복되는 머리말을 지나칠 첫 조작이다. */}
        <a className={styles.skipLink} href={`#${MAIN_ID}`}>
          {entryContent.skipLink}
        </a>

        <header className={styles.header}>
          <p className={styles.brand}>{entryContent.brand}</p>
          {/*
          세션이 확인된 뒤에만 둔다. 확인 전에는 이 화면이 로그인 상태를 주장하지 않으므로
          로그아웃할 대상도 아직 없다.
        */}
          {ready ? (
            <div className={styles.headerActions}>
              {/*
              설정 화면으로 가는 길. 이 링크가 없으면 그 화면은 주소를 직접 쳐야만 닿고,
              사용자가 자기 로그인 수단을 볼 자리가 사실상 없다. 전역 내비게이션은 아직 없으므로
              (Epic 1의 범위가 아니다) 머리말의 링크 하나가 그 자리다.

              `<a href>`다. 화면 전환은 전부 문서 요청이고 이 저장소에는 클라이언트 라우팅이 없다.
            */}
              <a className={styles.settingsLink} href={SETTINGS_PATH}>
                {authContent.settings.link}
              </a>
              <SignOutButton onSignOut={signOut.requestSignOut} pending={signOut.pending} />
            </div>
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

          로그아웃 진행도 여기서 말한다. 버튼의 문구를 바꾸는 대신 이 영역을 쓰는 이유는 두
          가지다 — 버튼 안의 문구 변화는 live region이 아니어서 보조기술에 전달되지 않고, 그
          문구가 버튼의 접근 가능한 이름이 되면 컨트롤의 이름이 동작 아닌 상태가 된다.
        */}
          <div
            /*
            표시된 세션은 갱신 중이 아니다. 게이트가 `reauth-required`인 상태는 확인이
            끝나고 다음 행동을 기다리는 자리이므로 `aria-busy`를 세우면 보조기술에 "지금
            바뀌는 중"이라고 잘못 말한다. 확인 중·이동 중·요청 중만 참이다.
          */
            aria-busy={statusBusy({
              ready,
              reauthRequired,
              requestPending: signOut.pending || reauthentication.pending,
              dashboard,
            })}
            // The dashboard, the evidence and the history speak in separate parts; a change to one is read alone.
            aria-atomic="false"
            aria-live="polite"
            className={styles.notice}
            role="status"
          >
            {ready && !signOut.pending && !reauthentication.pending ? (
              <>
                {preparationMessage === '' ? null : <span>{preparationMessage}</span>}
                <DashboardStatusText
                  adjustments={adjustments}
                  className={styles.noticeText}
                  dashboard={dashboard}
                  evidence={evidence}
                  planEnd={planEnd}
                  sync={sync}
                />
              </>
            ) : (
              <p className={styles.noticeText}>
                {liveRegionMessage(gate, signOut.pending, reauthentication.pending)}
              </p>
            )}
          </div>

          {/*
          서비스 공지(Story 3.5). 상단에, 마지막 정상값을 보이는 결과 패널 밖에 둔다. 정적 영역(landmark region)이라 위
          live region을 늘리지 않고, 공지가 바뀌어도 대시보드 문장에 섞어 말하지 않는다.
        */}
          {ready ? <ServiceNotice view={notices} /> : null}

          {reauthRequired ? (
            <ReauthPanel
              notice={reauthNoticeKindOf(window.location.search)}
              onReauthenticate={reauthentication.requestReauthentication}
              onSignOut={signOut.requestSignOut}
              pending={reauthentication.pending}
              signingOut={signOut.pending}
            />
          ) : null}

          {ready && selected === null && presence !== null ? (
            <FirstSnapshotPanel csrfToken={csrfToken} plan={presence} ready={ready} />
          ) : null}
          {ready && dashboard.view.kind !== 'denied' ? (
            <PlanHistoryPanel selected={selected} onSelect={setSelected} />
          ) : null}
          {ready && selected === null && terminalOf(bundleOf(dashboard.view)) !== null ? (
            <NextPlanBaselinePanel
              key={preparationScope}
              csrfToken={csrfToken}
              announce={setPreparationMessage}
            />
          ) : null}
          {ready ? (
            <PlanResultPanel
              adjustments={adjustments}
              dashboard={dashboard}
              evidence={evidence}
              planEnd={planEnd}
              reauthentication={reauthentication}
              sync={sync}
            />
          ) : null}
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

      {/*
        계획 종료 확인 대화상자(Story 2.13). 준비 요청이 확인 문구로 답한 뒤에만 열린다. 확정이 도는 동안에는
        호출점으로 포커스를 돌려주지 않는다 — 끝나면 화면이 패널 제목(종료) 또는 종료 영역의 표지(거절)로 옮긴다.
      */}
      {dialog === null ? null : (
        <PlanEndDialog
          dialog={dialog}
          onCancel={planEnd.cancel}
          onConfirm={planEnd.confirm}
          restoreFocus={!dialog.confirming}
        />
      )}
    </>
  )
}
