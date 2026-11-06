import { useEffect, useRef, useState, type ReactElement } from 'react'

import { SignOutButton } from '../components/SignOutButton'
import {
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionStepScope,
  accountDeletionTimeText,
  backupDeadlineText,
} from '../content/accountDeletion'
import { authContent, noticePath } from '../content/auth'
import { entryContent } from '../content/entry'
import {
  fetchDeletionState,
  retryDeletion,
  type AccountDeletionProgress,
  type AccountDeletionState,
} from '../session/accountDeletion'
import { replaceLocation } from '../session/navigation'
import { useSignOut } from '../session/useSignOut'
import progressStyles from './DeletionProgressScreen.module.css'
import styles from './SettingsScreen.module.css'

const copy = accountDeletionContent.progress

/** 본문 랜드마크의 id. 건너뛰기 링크의 목적지이기도 하다. */
const MAIN_ID = 'deletion-progress-main'

type ProgressView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'loaded'; readonly state: AccountDeletionState }

/** 다시 시도(Story 5.2)의 화면 상태. 결과 문장은 화면의 단일 live region이 말한다. */
type RetryView =
  | { readonly kind: 'idle' }
  | { readonly kind: 'pending' }
  | { readonly kind: 'said'; readonly message: string }

interface DeletionProgressScreenProps {
  /** 로그아웃·다시 시도에 실을 세션 토큰. 삭제 진행 세션이 쓸 수 있는 상태 변경은 이 둘이다. */
  readonly csrfToken: string | null
}

/** 진행 상태의 표시 문장. */
function stateText(progress: AccountDeletionProgress): string {
  switch (progress.status) {
    case 'RETRY_NEEDED':
      return copy.stateRetryNeeded
    case 'OPERATIONAL_DATA_ERASED':
      return copy.stateErased
    case 'IN_PROGRESS':
      return copy.state
  }
}

/**
 * 삭제 진행 화면(Story 5.1·5.2).
 *
 * 세션 확인이 삭제 진행(`deletionInProgress`)을 말하면 <b>어느 경로에서든</b> 화면이 이것만 그린다 — 새 경로가 아니라 각 인증 화면이
 * 자기 자리에 그린다(경로·복귀 목적지 다섯 곳 계약을 건드리지 않는다). 다른 화면의 데이터 조회는 세션 게이트가 `ready`가 아니므로
 * 시작되지 않는다.
 *
 * 보여 주는 것은 요청 시각·적용 정책 버전·상태(삭제 진행 중 또는 재시도 필요)·백업 사본 파기 기한과 로그아웃·다시 확인 둘이다(다시 확인은
 * 사용자가 누를 때만 읽는다 — 자동 재시도·주기 폴링이 없다). 삭제 실행이 한 단계에서 멈췄으면(Story 5.2) 지우지 못한 범위를 닫힌 맵의
 * 문장으로 말하고 `삭제 다시 시도`를 둔다 — 누를 때만 요청하고, 응답을 받지 못하면 같은 키로 다시 보낸다. 로그아웃·다시 시도의 결과는 이
 * 화면의 live region이 말한다. 금융 값·계정 식별자·건수는 없다. 정책·요청을 읽지 못해도 삭제 요청은 이미 접수돼 있다는 사실을 말한다.
 * 세션이 끝났으면(401 — 삭제가 끝나면 세션도 지워진다) 만료 표지와 함께 진입 화면으로 간다. 화면당 live region은 하나이고 `복리 계획
 * 비교`·비추천 고지를 제목 바로 아래에 둔다.
 */
export function DeletionProgressScreen({ csrfToken }: DeletionProgressScreenProps): ReactElement {
  const signOut = useSignOut(csrfToken)
  const [view, setView] = useState<ProgressView>({ kind: 'checking' })
  /** 사용자가 누른 다시 확인의 횟수. 값이 바뀌면 아래 effect가 한 번 더 읽는다 — 자동 재시도가 아니다. */
  const [attempt, setAttempt] = useState(0)
  const [retry, setRetry] = useState<RetryView>({ kind: 'idle' })
  /** 다시 시도의 멱등 키. 응답을 받지 못한 요청만 같은 키로 다시 보낸다. 메모리에만 있다. */
  const retryKey = useRef<string | null>(null)
  /** 화면이 붙어 있는가. 떠난 뒤 도착한 다시 시도 응답은 상태도 이동도 바꾸지 않는다(조회 effect의 `current`와 같은 가드). */
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  // 요청 시작은 passive effect다. 자동 재시도하지 않는다.
  useEffect(() => {
    let current = true
    void fetchDeletionState().then((result) => {
      if (!current) return
      if (result.outcome === 'expired') {
        replaceLocation(noticePath('expired'))
        return
      }
      const fresh = result.outcome === 'loaded' ? result.state.request : null
      if (fresh !== null) {
        // 새 상태를 읽었다 — 앞선 무응답 다시 시도의 키는 버린다. 다음 누름은 새 요청이다.
        retryKey.current = null
      }
      /*
       * 다시 시도 결과 문장은 새 상태와 맞을 때만 남긴다 — 다시 읽은 상태가 여전히 재시도 필요이거나 읽지 못했으면 그 문장을 내리고
       * status는 새 상태를 말한다(live region은 그대로 하나다).
       */
      if (fresh === null || fresh.progress.status === 'RETRY_NEEDED') {
        setRetry((previous) => (previous.kind === 'said' ? { kind: 'idle' } : previous))
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

  const request = view.kind === 'loaded' ? view.state.request : null
  const progress = request?.progress ?? null
  const retryNeeded = progress?.status === 'RETRY_NEEDED'
  const busy = view.kind === 'checking' || signOut.pending || retry.kind === 'pending'
  // 모르는 단계 code는 한 문장으로 접히므로 같은 문장은 한 번만 말한다(순서는 서버 순서).
  const failedScopes =
    progress === null ? [] : [...new Set(progress.failedSteps.map(accountDeletionStepScope))]

  const recheck = (): void => {
    // 확인 중으로 되돌리는 것은 이 조작의 일이다(effect 본문에서 상태를 바꾸지 않는다).
    setView({ kind: 'checking' })
    setAttempt((value) => value + 1)
  }

  const requestRetry = (): void => {
    if (busy) return
    retryKey.current ??= crypto.randomUUID()
    const key = retryKey.current
    setRetry({ kind: 'pending' })
    void retryDeletion(csrfToken, key).then((result) => {
      if (!mounted.current) return
      if (result.outcome === 'expired') {
        replaceLocation(noticePath('expired'))
        return
      }
      if (result.outcome === 'unanswered') {
        // 결과를 단정하지 않는다. 다음 누름은 같은 키로 간다.
        setRetry({ kind: 'said', message: copy.retryUnanswered })
        return
      }
      retryKey.current = null
      if (result.outcome === 'accepted') {
        setRetry({ kind: 'said', message: copy.retryAccepted })
        recheck()
        return
      }
      // 거절도 상태가 바뀌었다는 뜻일 수 있다(예: 이미 다시 도는 중) — 접수와 같이 한 번 다시 읽는다.
      setRetry({ kind: 'said', message: accountDeletionRefusalMessage(result.code) })
      recheck()
    })
  }

  const status = signOut.pending
    ? authContent.signOut.pending
    : signOut.failed
      ? copy.signOutFailed
      : retry.kind === 'pending'
        ? copy.retryingErasure
        : retry.kind === 'said'
          ? retry.message
          : view.kind === 'checking'
            ? copy.checking
            : view.kind === 'unavailable'
              ? copy.unavailable
              : null

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

        {/* 화면당 polite live region 하나. 확인·실패·로그아웃·다시 시도 진행을 여기서 말한다. */}
        <div aria-busy={busy} aria-live="polite" className={styles.notice} role="status">
          {status === null ? null : <p className={styles.noticeText}>{status}</p>}
        </div>

        <section aria-labelledby="deletion-progress-heading" className={styles.section}>
          <h2 className={styles.sectionHeading} id="deletion-progress-heading">
            {copy.title}
          </h2>
          <p>{copy.body}</p>
          {request === null || progress === null || view.kind !== 'loaded' ? null : (
            <dl className={styles.connectionMeta}>
              <div className={styles.connectionMetaRow}>
                <dt className={styles.connectionTerm}>{copy.stateLabel}</dt>
                <dd className={styles.connectionValue}>{stateText(progress)}</dd>
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
          {progress === null || !retryNeeded ? null : (
            <div className={progressStyles.retryBlock}>
              <p className={progressStyles.retryNotice}>{copy.retryNeededBody}</p>
              <h3 className={progressStyles.scopeHeading} id={copy.failedScopeHeadingId}>
                {copy.failedScopeHeading}
              </h3>
              <ul aria-labelledby={copy.failedScopeHeadingId} className={progressStyles.scopeList}>
                {failedScopes.map((scope) => (
                  <li key={scope}>{scope}</li>
                ))}
              </ul>
              <button
                aria-disabled={busy}
                className={styles.retryButton}
                onClick={requestRetry}
                type="button"
              >
                {copy.retryErasure}
              </button>
            </div>
          )}
          <p className={styles.boundary}>{copy.signOutHint}</p>
          <button
            aria-disabled={busy}
            className={styles.retryButton}
            onClick={() => {
              if (busy) return
              // 앞선 다시 시도 결과 문장은 새 확인 앞에서 내린다.
              setRetry({ kind: 'idle' })
              recheck()
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
