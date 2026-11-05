import { useEffect, useState, type ReactElement } from 'react'

import { SignOutButton } from '../components/SignOutButton'
import {
  accountDeletionContent,
  accountDeletionTimeText,
  backupDeadlineText,
} from '../content/accountDeletion'
import { authContent, noticePath } from '../content/auth'
import { entryContent } from '../content/entry'
import { fetchDeletionState, type AccountDeletionState } from '../session/accountDeletion'
import { replaceLocation } from '../session/navigation'
import { useSignOut } from '../session/useSignOut'
import styles from './SettingsScreen.module.css'

const copy = accountDeletionContent.progress

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'deletion-progress-main'

type ProgressView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'loaded'; readonly state: AccountDeletionState }

interface DeletionProgressScreenProps {
  /** 로그아웃에 실을 세션 토큰. 삭제 진행 세션이 쓸 수 있는 상태 변경은 로그아웃 하나다. */
  readonly csrfToken: string | null
}

/**
 * 삭제 진행 화면(Story 5.1).
 *
 * 세션 확인이 삭제 진행(`deletionInProgress`)을 말하면 <b>어느 경로에서든</b> 화면이 이것만 그린다 — 새 경로가 아니라 각 인증 화면이
 * 자기 자리에 그린다(경로·복귀 목적지 다섯 곳 계약을 건드리지 않는다). 다른 화면의 데이터 조회는 세션 게이트가 `ready`가 아니므로
 * 시작되지 않는다.
 *
 * 보여 주는 것은 요청 시각·적용 정책 버전·상태(삭제 진행 중)·백업 사본 파기 기한과 로그아웃·다시 확인 둘이다(다시 확인은 사용자가
 * 누를 때만 읽는다 — 자동 재시도가 없다). 로그아웃 실패는 이 화면의 live region이 말한다. 금융 값·계정 식별자는 없다.
 * 정책·요청을 읽지 못해도 삭제 요청은 이미 접수돼 있다는 사실을 말한다. 세션이 끝났으면(401) 만료 표지와 함께 진입 화면으로 간다.
 * 화면당 live region은 하나이고 `복리 계획 비교`·비추천 고지를 제목 바로 아래에 둔다.
 */
export function DeletionProgressScreen({ csrfToken }: DeletionProgressScreenProps): ReactElement {
  const signOut = useSignOut(csrfToken)
  const [view, setView] = useState<ProgressView>({ kind: 'checking' })
  /** 사용자가 누른 다시 확인의 횟수. 값이 바뀌면 아래 effect가 한 번 더 읽는다 — 자동 재시도가 아니다. */
  const [attempt, setAttempt] = useState(0)

  // 요청 시작은 passive effect다. 자동 재시도하지 않는다.
  useEffect(() => {
    let current = true
    void fetchDeletionState().then((result) => {
      if (!current) return
      if (result.outcome === 'expired') {
        replaceLocation(noticePath('expired'))
        return
      }
      setView(
        result.outcome === 'loaded' && result.state.request !== null
          ? { kind: 'loaded', state: result.state }
          : { kind: 'unavailable' },
      )
    })
    return () => {
      current = false
    }
  }, [attempt])

  const status = signOut.pending
    ? authContent.signOut.pending
    : signOut.failed
      ? copy.signOutFailed
      : view.kind === 'checking'
        ? copy.checking
        : view.kind === 'unavailable'
          ? copy.unavailable
          : null
  const request = view.kind === 'loaded' ? view.state.request : null

  return (
    <div className={styles.page}>
      <a className={styles.skipLink} href={`#${MAIN_ID}`}>
        {entryContent.skipLink}
      </a>

      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
        <SignOutButton onSignOut={signOut.requestSignOut} pending={signOut.pending} />
      </header>

      <main className={styles.main} id={MAIN_ID} tabIndex={-1}>
        <div className={styles.intro}>
          {/* UX-DR23: 제목과 바로 아래 고지를 인접 배치한다. */}
          <h1 className={styles.title}>{authContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{authContent.disclaimer}</p>
        </div>

        {/* 화면당 polite live region 하나. 확인·실패·로그아웃 진행을 여기서 말한다. */}
        <div
          aria-busy={view.kind === 'checking' || signOut.pending}
          aria-live="polite"
          className={styles.notice}
          role="status"
        >
          {status === null ? null : <p className={styles.noticeText}>{status}</p>}
        </div>

        <section aria-labelledby="deletion-progress-heading" className={styles.section}>
          <h2 className={styles.sectionHeading} id="deletion-progress-heading">
            {copy.title}
          </h2>
          <p>{copy.body}</p>
          {request === null || view.kind !== 'loaded' ? null : (
            <dl className={styles.connectionMeta}>
              <div className={styles.connectionMetaRow}>
                <dt className={styles.connectionTerm}>{copy.stateLabel}</dt>
                <dd className={styles.connectionValue}>{copy.state}</dd>
              </div>
              <div className={styles.connectionMetaRow}>
                <dt className={styles.connectionTerm}>{copy.requestedAtLabel}</dt>
                <dd className={styles.connectionValue}>
                  {accountDeletionTimeText(request.requestedAt)}
                </dd>
              </div>
              <div className={styles.connectionMetaRow}>
                <dt className={styles.connectionTerm}>{copy.policyVersionLabel}</dt>
                <dd className={styles.connectionValue}>{request.policyVersion}</dd>
              </div>
              <div className={styles.connectionMetaRow}>
                <dt className={styles.connectionTerm}>{copy.backupLabel}</dt>
                <dd className={styles.connectionValue}>
                  {backupDeadlineText(request.requestedAt, view.state.policy.backupDestroyDays)}
                </dd>
              </div>
            </dl>
          )}
          <p className={styles.boundary}>{copy.signOutHint}</p>
          <button
            aria-disabled={view.kind === 'checking' || signOut.pending}
            className={styles.retryButton}
            onClick={() => {
              if (view.kind === 'checking' || signOut.pending) return
              // 확인 중으로 되돌리는 것은 이 조작의 일이다(effect 본문에서 상태를 바꾸지 않는다).
              setView({ kind: 'checking' })
              setAttempt((value) => value + 1)
            }}
            type="button"
          >
            {copy.retry}
          </button>
        </section>
      </main>
    </div>
  )
}
