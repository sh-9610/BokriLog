import { useEffect, useRef, useState, type ReactElement } from 'react'

import { SignOutButton } from '../components/SignOutButton'
import {
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionStepScope,
  accountDeletionTimeText,
  backupDeadlineText,
  backupDestructionText,
  completionStateText,
} from '../content/accountDeletion'
import { authContent, noticePath } from '../content/auth'
import { entryContent } from '../content/entry'
import {
  fetchDeletionState,
  retryDeletion,
  type AccountDeletionCompletion,
  type AccountDeletionProgress,
  type AccountDeletionState,
  type LegalRetentionItem,
} from '../session/accountDeletion'
import { replaceLocation } from '../session/navigation'
import { useSignOut } from '../session/useSignOut'
import progressStyles from './DeletionProgressScreen.module.css'
import styles from './SettingsScreen.module.css'

const copy = accountDeletionContent.progress

const completionCopy = accountDeletionContent.completion

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
  /**
   * 세션 확인이 삭제 완료(`deletionCompleted`)를 말했는가(Story 5.4). 완료 조회를 아직 읽지 못했거나(확인 중·읽지 못함) 다시 확인하는
   * 동안에도 제목·문장·안내를 완료 쪽으로 고른다 — 삭제가 끝난 세션에 "진행 중"을 말하지 않는다.
   */
  readonly completed?: boolean
}

/** 진행 상태의 표시 문장. */
function stateText(progress: AccountDeletionProgress): string {
  switch (progress.status) {
    case 'RETRY_NEEDED':
      return copy.stateRetryNeeded
    case 'OPERATIONAL_DATA_ERASED':
      return copy.stateErased
    case 'DELETION_COMPLETED':
      return copy.stateCompleted
    case 'IN_PROGRESS':
      return copy.state
  }
}

/** 메타 목록의 한 줄. 진행·완료 화면이 같은 모양을 쓴다. */
function MetaRow({ term, value }: { readonly term: string; readonly value: string }): ReactElement {
  return (
    <div className={styles.connectionMetaRow}>
      <dt className={styles.connectionTerm}>{term}</dt>
      <dd className={styles.connectionValue}>{value}</dd>
    </div>
  )
}

/** 법정 분리 보존의 표시. 승인된 항목이 없으면 그 사실 한 줄이고, 있으면 항목마다 필드·근거·목적·기간이다. */
function legalRetentionText(items: readonly LegalRetentionItem[]): string {
  if (items.length === 0) {
    return completionCopy.legalNone
  }
  const labels = accountDeletionContent
  return items
    .map(
      (item) =>
        `${labels.legalFieldLabel} ${item.field} · ${labels.legalBasisLabel} ${item.basis} · ${labels.legalPurposeLabel} ${item.purpose} · ${labels.legalPeriodLabel} ${item.period}`,
    )
    .join(' / ')
}

/**
 * 삭제가 끝난 뒤의 완료 안내(Story 5.4). 모든 값이 서버 값이다 — 운영 데이터 삭제 시각, 백업 사본 파기 기한·상태(초과를 숨기지 않는다),
 * 전체 삭제 완료 여부, 법정 분리 보존. 화면이 계산하거나 지어내는 값이 없다.
 */
function CompletionDetails({
  completion,
  legalRetention,
}: {
  readonly completion: AccountDeletionCompletion
  readonly legalRetention: readonly LegalRetentionItem[]
}): ReactElement {
  return (
    <>
      <p>{completionCopy.body}</p>
      <dl className={styles.connectionMeta}>
        <MetaRow
          term={copy.stateLabel}
          value={completionStateText(completion.deletionCompleted, completion.backupDestruction)}
        />
        <MetaRow term={copy.requestIdLabel} value={completion.requestId} />
        <MetaRow
          term={completionCopy.operationalErasedAtLabel}
          value={accountDeletionTimeText(completion.operationalErasedAt)}
        />
        <MetaRow
          term={completionCopy.backupDeadlineLabel}
          value={
            completion.backupDeadline === null
              ? completionCopy.backupUnknown
              : accountDeletionTimeText(completion.backupDeadline)
          }
        />
        <MetaRow
          term={completionCopy.backupStateLabel}
          value={backupDestructionText(completion.backupDestruction)}
        />
        {completion.backupDestroyedAt === null ? null : (
          <MetaRow
            term={completionCopy.backupDestroyedAtLabel}
            value={accountDeletionTimeText(completion.backupDestroyedAt)}
          />
        )}
        <MetaRow
          term={completionCopy.completionLabel}
          value={
            completion.deletionCompleted
              ? completionCopy.completionDone
              : completionCopy.completionPending
          }
        />
        {completion.deletionCompletedAt === null ? null : (
          <MetaRow
            term={completionCopy.completedAtLabel}
            value={accountDeletionTimeText(completion.deletionCompletedAt)}
          />
        )}
        <MetaRow term={completionCopy.legalLabel} value={legalRetentionText(legalRetention)} />
      </dl>
      {completion.backupDestruction === 'OVERDUE' ? (
        <p className={progressStyles.retryNotice}>{completionCopy.overdueNotice}</p>
      ) : null}
      {completion.deletionCompleted ? null : <p>{completionCopy.limitNotice}</p>}
    </>
  )
}

/** 지원 경로(deferred 170). 서버에 설정된 연락처가 있을 때만 그린다 — 빈·지어낸 링크를 만들지 않는다. */
function SupportContact({ contact }: { readonly contact: string }): ReactElement {
  return (
    <div className={progressStyles.supportBlock}>
      <h3 className={progressStyles.scopeHeading} id={copy.supportHeadingId}>
        {copy.supportHeading}
      </h3>
      <p id={copy.supportBodyId}>{copy.supportBody}</p>
      <a
        aria-describedby={copy.supportBodyId}
        className={progressStyles.supportLink}
        href={`mailto:${contact}`}
      >
        {contact}
      </a>
    </div>
  )
}

/**
 * 삭제 진행 화면(Story 5.1·5.2).
 *
 * 세션 확인이 삭제 진행(`deletionInProgress`)을 말하면 <b>어느 경로에서든</b> 화면이 이것만 그린다 — 새 경로가 아니라 각 인증 화면이
 * 자기 자리에 그린다(경로·복귀 목적지 다섯 곳 계약을 건드리지 않는다). 다른 화면의 데이터 조회는 세션 게이트가 `ready`가 아니므로
 * 시작되지 않는다.
 *
 * 보여 주는 것은 요청 시각·요청 ID(삭제 요청의 불투명 참조 — 계정 식별자가 아니고 문의할 때 알려 줄 값이다)·적용 정책 버전·상태(삭제 진행 중 또는 재시도 필요)·백업 사본 파기 기한과 로그아웃·다시 확인 둘이다(다시 확인은
 * 사용자가 누를 때만 읽는다 — 자동 재시도·주기 폴링이 없다). 삭제 실행이 한 단계에서 멈췄으면(Story 5.2) 지우지 못한 범위를 닫힌 맵의
 * 문장으로 말하고 `삭제 다시 시도`를 둔다 — 누를 때만 요청하고, 응답을 받지 못하면 같은 키로 다시 보낸다. 로그아웃·다시 시도의 결과는 이
 * 화면의 live region이 말한다. 금융 값·계정 식별자·건수는 없다. 정책·요청을 읽지 못해도 삭제 요청은 이미 접수돼 있다는 사실을 말한다.
 * 세션이 끝났으면(401) 만료 표지와 함께 진입 화면으로 간다. 화면당 live region은 하나이고 `복리 계획 비교`·비추천 고지를 제목 바로
 * 아래에 둔다.
 *
 * 삭제가 끝나면(Story 5.4) 요청한 세션은 계정 정보 없는 완료 확인 세션으로 남고, 같은 조회가 완료 조회(`completion`)를 돌려준다 — 이
 * 화면은 그 자리에서 완료 화면이 된다(요청 ID·운영 삭제 시각·백업 사본 파기 기한과 상태·전체 삭제 완료·법정 분리 보존). 기한 초과를 숨기지 않고
 * 다시 시도 버튼은 없다(그 세션이 할 수 있는 것은 확인과 로그아웃뿐이다). 세션 확인이 완료를 말하면(`completed`) 완료 조회를 읽기
 * 전·읽지 못함·다시 확인 중에도 완료 쪽 제목·문장을 고르고, 진행 중 다시 시도가 401이면 만료로 보내기 전에 한 번 다시 읽어 완료면 그 자리에서
 * 완료 화면을 그린다. 서버에 지원 연락처가 설정돼 있으면 진행·완료 화면 모두에 지원
 * 경로를 둔다(deferred 170).
 */
export function DeletionProgressScreen({
  csrfToken,
  completed = false,
}: DeletionProgressScreenProps): ReactElement {
  const signOut = useSignOut(csrfToken)
  const [view, setView] = useState<ProgressView>({ kind: 'checking' })
  /**
   * 마지막으로 읽은 완료 조회. 다시 확인하는 동안(확인 중·읽지 못함) 제목이 진행·운영 완료 쪽으로 되돌아가지 않게 한다 — 완료는 단조라
   * 앞서 읽은 값보다 뒤로 가지 않는다.
   */
  const [knownCompletion, setKnownCompletion] = useState<AccountDeletionCompletion | null>(null)
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
      const finished = result.outcome === 'loaded' ? result.state.completion : null
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
      if (finished !== null) {
        setKnownCompletion(finished)
      }
      setView(
        result.outcome === 'loaded' && (fresh !== null || finished !== null)
          ? { kind: 'loaded', state: result.state }
          : { kind: 'unavailable' },
      )
    })
    return () => {
      current = false
    }
  }, [attempt])

  const request = view.kind === 'loaded' ? view.state.request : null
  const completion = view.kind === 'loaded' ? view.state.completion : null
  /** 완료 쪽 문장을 고르는가 — 세션 확인의 완료 표지, 지금 읽은 완료 조회, 앞서 읽은 완료 조회 가운데 하나라도 있으면이다. */
  const showsCompletion = completed || completion !== null || knownCompletion !== null
  const latestCompletion = completion ?? knownCompletion
  const supportContact = view.kind === 'loaded' ? view.state.supportContact : null
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
        /*
         * 다시 시도가 401이면 세션이 끝났거나, 그 사이 삭제가 끝나 이 세션이 완료 확인 세션이 됐다(완료 세션의 다시 시도는 미인증과 같다).
         * 만료로 보내기 전에 한 번 다시 읽어 완료 조회가 오면 그 자리에서 완료 화면을 그린다 — 이 조작의 결과 확인이지 자동 재시도가 아니다.
         */
        retryKey.current = null
        void fetchDeletionState().then((state) => {
          if (!mounted.current) return
          if (state.outcome === 'loaded' && state.state.completion !== null) {
            setKnownCompletion(state.state.completion)
            setRetry({ kind: 'idle' })
            setView({ kind: 'loaded', state: state.state })
            return
          }
          replaceLocation(noticePath('expired'))
        })
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
            ? showsCompletion
              ? completionCopy.checking
              : copy.checking
            : view.kind === 'unavailable'
              ? showsCompletion
                ? completionCopy.unavailable
                : copy.unavailable
              : null

  const heading = !showsCompletion
    ? copy.title
    : latestCompletion?.deletionCompleted === true
      ? completionCopy.titleCompleted
      : completionCopy.title

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
            {heading}
          </h2>
          {completion !== null ? null : <p>{showsCompletion ? completionCopy.body : copy.body}</p>}
          {completion === null || view.kind !== 'loaded' ? null : (
            <CompletionDetails
              completion={completion}
              legalRetention={view.state.policy.legalRetention}
            />
          )}
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
                <dt className={styles.connectionTerm}>{copy.requestIdLabel}</dt>
                <dd className={styles.connectionValue}>{request.requestId}</dd>
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
          {supportContact === null ? null : <SupportContact contact={supportContact} />}
          <p className={styles.boundary}>
            {showsCompletion ? completionCopy.signOutHint : copy.signOutHint}
          </p>
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
            {showsCompletion ? completionCopy.retry : copy.retry}
          </button>
        </section>
      </main>
    </div>
  )
}
