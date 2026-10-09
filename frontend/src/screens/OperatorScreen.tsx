import { useCallback, useLayoutEffect, useRef, useState, type ReactElement } from 'react'

import type { ActiveRestriction, AuditEntry } from '../session/operatorApi'

import { OperatorExportDialog } from '../components/OperatorExportDialog'
import { OperatorRestrictionDialog } from '../components/OperatorRestrictionDialog'
import { ProviderMark } from '../components/ProviderMark'
import { ReauthPanel } from '../components/ReauthPanel'
// 제공자 버튼 규칙은 재인증 패널의 것 하나다(`PlanEndControl`과 같은 관용).
import reauthStyles from '../components/ReauthPanel.module.css'
import { SignOutButton } from '../components/SignOutButton'
import {
  authContent,
  DASHBOARD_PATH,
  noticePath,
  reauthNoticeKindOf,
  SIGN_IN_PROVIDERS,
  type SignInProvider,
} from '../content/auth'
import { formatVerifiedAt } from '../content/connection'
import { entryContent } from '../content/entry'
import {
  CALCULATION_POLICY_TARGET,
  featureLabel,
  GLOBAL_TARGET,
  ledgerCodeLabel,
  operatorContent,
  operatorRefusalMessage,
  originLabel,
  OUTCOME_FILTERS,
  outcomeLabel,
  REASON_MAX_LENGTH,
  RESTRICTION_COMMANDS,
  RESTRICTION_FEATURES,
  RESTRICTION_SCOPES,
  scopeLabel,
  signalReasonLabel,
  type RestrictionFeature,
  type RestrictionScope,
} from '../content/operator'
import { replaceLocation } from '../session/navigation'
import {
  defaultAuditFilter,
  useOperationLookup,
  useOperatorAudit,
  type AuditFilter,
} from '../session/useOperatorAudit'
import { useOperatorExport } from '../session/useOperatorExport'
import { useOperatorRestriction } from '../session/useOperatorRestriction'
import { useReauthentication } from '../session/useReauthentication'
import { liveRegionMessage, useSessionGate, type SessionGate } from '../session/useSessionState'
import { useSignOut } from '../session/useSignOut'
import { EntryScreen } from './EntryScreen'
import styles from './OperatorScreen.module.css'

const copy = operatorContent

/** 제공자별 버튼 규칙. 닫힌 맵인 근거는 재인증 패널과 같다. */
const PROVIDER_BUTTON_CLASS: Readonly<Record<SignInProvider, string | undefined>> = {
  google: reauthStyles.googleButton,
  naver: reauthStyles.naverButton,
  kakao: reauthStyles.kakaoButton,
}

/** 감사 행 하나. 색만으로 결과를 가르지 않는다 — 결과·code를 글자로 말한다. */
function AuditRow({ entry }: { readonly entry: AuditEntry }): ReactElement {
  const columns = copy.audit.columns
  return (
    <li className={styles.entry}>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.recordedAt}</span>
        <span className={styles.value}>{formatVerifiedAt(entry.recordedAt)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.outcome}</span>
        <span className={`${styles.value ?? ''} ${styles.outcome ?? ''}`}>
          {outcomeLabel(entry.outcome)}
          {entry.code === null ? '' : ` · ${ledgerCodeLabel(entry.code)}`}
        </span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.command}</span>
        <span className={styles.value}>{entry.command}</span>
        <span className={styles.term}>{columns.scope}</span>
        <span className={styles.value}>{entry.scope}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.target}</span>
        <span className={styles.value}>{entry.target}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.reason}</span>
        <span className={styles.value}>{entry.reason}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.actor}</span>
        <span className={styles.value}>{entry.actorDigest.slice(0, 12)}</span>
        <span className={styles.term}>{columns.operation}</span>
        <span className={styles.value}>{entry.operationId}</span>
      </div>
    </li>
  )
}

/**
 * 활성 제한 하나. 비식별된 대상은 `삭제됨`으로만 보인다 — 색이 아니라 글자로 말한다. 신호가 만들었거나 신호가 연결된
 * 제한(Story 3.3)은 `수동 검증 필요` 상태를 글자로 보이고, 자동 정지의 사유는 신호 종류의 문장이다.
 */
function RestrictionRow({
  restriction,
}: {
  readonly restriction: ActiveRestriction
}): ReactElement {
  const active = copy.restriction.active
  const columns = active.columns
  return (
    <li className={styles.entry}>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.origin}</span>
        <span className={styles.value}>{originLabel(restriction.origin)}</span>
        <span className={styles.term}>{columns.state}</span>
        <span className={styles.value}>
          {restriction.requiresVerification
            ? active.states.verificationRequired
            : active.states.active}
        </span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.feature}</span>
        <span className={styles.value}>{featureLabel(restriction.feature)}</span>
        <span className={styles.term}>{columns.scope}</span>
        <span className={styles.value}>{scopeLabel(restriction.scope)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.target}</span>
        <span className={styles.value}>
          {restriction.targetErased
            ? copy.restriction.active.erased
            : restriction.scope === 'GLOBAL'
              ? scopeLabel('GLOBAL')
              : restriction.target}
        </span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.effectiveFrom}</span>
        <span className={styles.value}>{formatVerifiedAt(restriction.effectiveFrom)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.reason}</span>
        <span className={styles.value}>
          {restriction.reason === null
            ? copy.restriction.active.noReason
            : restriction.origin === 'SIGNAL'
              ? signalReasonLabel(restriction.reason)
              : restriction.reason}
        </span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.actor}</span>
        <span className={styles.value}>
          {restriction.actorDigest === null
            ? copy.restriction.active.noReason
            : restriction.actorDigest.slice(0, 12)}
        </span>
      </div>
    </li>
  )
}

/** 범위가 정하는 대상의 기본값. 전역은 고정값, 계산 정책 버전은 이 빌드가 아는 버전이다. */
function defaultTarget(scope: RestrictionScope): string {
  if (scope === 'GLOBAL') return GLOBAL_TARGET
  if (scope === 'CALCULATION_POLICY_VERSION') return CALCULATION_POLICY_TARGET
  return ''
}

/** 추출 결과를 파일로 내려받는다. 네트워크 요청도 브라우저 저장소도 쓰지 않는다 — 메모리의 묶음을 바로 넘긴다. */
function download(operationId: string, rows: readonly AuditEntry[]): void {
  if (typeof URL.createObjectURL !== 'function') {
    return
  }
  const blob = new Blob([JSON.stringify(rows, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `bokrilog-audit-${operationId}.json`
  // 문서에 붙여야 브라우저가 내려받기를 시작한다. 주소는 내려받기가 시작된 다음 틱에 거둔다.
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => {
    URL.revokeObjectURL(url)
  }, 0)
}

/**
 * 보호된 운영자 화면(Story 3.1). `React.lazy`로 따로 받는 chunk다 — 일반 번들의 초기 화면·탐색·DOM에 운영자 제어가
 * 섞이지 않는다.
 *
 * 서버가 유일한 권위다. 세션 확인 뒤 감사 조회가 404이면(운영자가 아니거나 권한이 없다) 없는 화면과 같은 문장 하나만
 * 그리고 어떤 운영자 제어도 DOM에 두지 않는다 — 권한 유무를 말하지 않는다. 화면당 polite live region은 하나다.
 *
 * 감사 내보내기는 준비 → 확인 대화상자(확인 문구 재입력, 취소가 기본 포커스) → 확정이다. 서버가 최근 본인 확인을
 * 요구하면 이 자리에 세 로그인 수단을 보이고, 확인을 마친 사용자가 다시 준비한다 — 자동으로 이어 하지 않는다. 응답을
 * 받지 못한 확정은 실행 여부를 단정하지 않고 operation 조회로 안내한다.
 */
export function OperatorScreen(): ReactElement {
  const { csrfToken, gate } = useSessionGate('operator')
  const signOut = useSignOut(csrfToken)
  const reauthentication = useReauthentication(csrfToken)
  const ready = gate === 'ready'

  const [initial] = useState<AuditFilter>(() => defaultAuditFilter())
  const [filter, setFilter] = useState<AuditFilter>(initial)
  const [reason, setReason] = useState('')
  const [operationInput, setOperationInput] = useState('')
  /** 사용자가 조회 칸을 고쳤는가. 고친 뒤에는 불확실한 operation으로 다시 채우지 않는다. */
  const [operationEdited, setOperationEdited] = useState(false)
  const audit = useOperatorAudit(ready, initial)
  const lookup = useOperationLookup()

  const { search } = audit
  const appliedQuery = audit.view.query
  const refreshAudit = useCallback((): void => {
    search({ from: appliedQuery.from, to: appliedQuery.to, outcome: appliedQuery.outcome })
  }, [appliedQuery.from, appliedQuery.outcome, appliedQuery.to, search])
  const exporting = useOperatorExport(csrfToken, refreshAudit)
  // 제한 목록은 운영자임이 감사 조회로 확인된 뒤에만 읽는다. 목록의 404는 제한 권한이 없다는 답이다.
  // 제한 목록은 감사 조회와 따로 읽는다(Story 3.2) — 두 영역의 권한(`audit`·`command`)이 다르다.
  const restricting = useOperatorRestriction(ready, csrfToken)
  const [feature, setFeature] = useState<RestrictionFeature>('SYNC')
  const [scope, setScope] = useState<RestrictionScope>('GLOBAL')
  const [restrictionTarget, setRestrictionTarget] = useState(GLOBAL_TARGET)
  const [restrictionReason, setRestrictionReason] = useState('')
  /** 화면의 단일 status가 말할 마지막 조작. 두 명령의 끝난 상태가 함께 남아도 사용자가 마지막에 누른 쪽을 말한다. */
  const [lastCommand, setLastCommand] = useState<'export' | 'restriction'>('export')

  const resultRef = useRef<HTMLHeadingElement>(null)
  const restrictionResultRef = useRef<HTMLHeadingElement>(null)

  // 완료의 결과는 사용자가 누른 조작의 결과다 — 그 제목으로 초점을 옮긴다(커밋과 함께, layout effect).
  const completed = exporting.phase === 'completed'
  useLayoutEffect(() => {
    if (completed) {
      resultRef.current?.focus()
    }
  }, [completed])
  const restricted = restricting.phase === 'completed'
  useLayoutEffect(() => {
    if (restricted) {
      restrictionResultRef.current?.focus()
    }
  }, [restricted])

  // 서버가 세션이 끝났다고 답했다. 만료의 결과는 이동 하나다(`useSessionGate`와 같은 규칙).
  const sessionEnded =
    audit.view.status === 'unauthenticated' ||
    exporting.sessionEnded ||
    restricting.sessionEnded ||
    restricting.list.status === 'unauthenticated' ||
    lookup.lookup.status === 'unauthenticated'
  useLayoutEffect(() => {
    if (sessionEnded) {
      replaceLocation(noticePath('expired'))
    }
  }, [sessionEnded])

  if (gate === 'unauthenticated') {
    return <EntryScreen notice="signedOut" />
  }

  if (audit.view.status === 'notFound' && restricting.list.status === 'notFound') {
    // 운영자가 아니거나 권한이 없다. 없는 화면과 같은 문장 하나뿐이다 — 운영자 제어를 DOM에 두지 않는다.
    return (
      <div className={styles.page}>
        <main className={styles.main} id={copy.mainId} tabIndex={-1}>
          <div className={styles.intro}>
            <h1 className={styles.title}>{copy.notFound.heading}</h1>
            <p>{copy.notFound.body}</p>
            <a className={styles.link} href={DASHBOARD_PATH}>
              {copy.notFound.link}
            </a>
          </div>
        </main>
      </div>
    )
  }

  const reauthRequired = gate === 'reauth-required'
  const exportDialogOpen =
    exporting.prepared !== null && (exporting.phase === 'armed' || exporting.phase === 'confirming')
  const restrictionDialogOpen =
    restricting.prepared !== null &&
    (restricting.phase === 'armed' || restricting.phase === 'confirming')
  const dialogOpen = exportDialogOpen || restrictionDialogOpen
  const busy =
    exporting.busy || restricting.busy || reauthentication.pending || signOut.pending || dialogOpen
  const canRestrict = restrictionTarget.trim() !== '' && restrictionReason.trim() !== ''
  // 운영자 제어는 운영자임이 한 번 확인된 뒤에만 그린다. 첫 조회의 실패는 fail-closed다.
  /*
   * 두 영역은 각자의 조회로 판정한다(Story 3.2). 감사 영역(감사 원장·내보내기)은 감사 조회를 한 번이라도 읽은 뒤에만,
   * 제한 영역은 제한 목록을 한 번이라도 읽은 뒤에만 그린다 — 404는 그 권한이 없다는 답이다(fail-closed). 운영자임이
   * 감사 쪽에서 확인된 뒤 목록 조회만 실패했다면 제한 영역은 실패 문장과 다시 조회만 보인다.
   */
  const auditVisible = ready && audit.view.everLoaded
  const listLoaded = restricting.list.everLoaded
  const restrictionVisible =
    ready && (listLoaded || (auditVisible && restricting.list.status === 'failed'))
  const operatorVisible = auditVisible || restrictionVisible
  const settled = audit.view.status !== 'checking' && restricting.list.status !== 'checking'
  const loadFailed =
    ready &&
    !operatorVisible &&
    settled &&
    (audit.view.status === 'failed' || restricting.list.status === 'failed')
  const target = `${filter.from}~${filter.to}`
  // 응답을 받지 못한 확정의 operation을 조회 칸에 미리 채운다. 사용자가 칸을 고친 뒤에는 그 값이 이긴다.
  const uncertainOperationId =
    lastCommand === 'restriction'
      ? restricting.uncertainOperationId
      : exporting.uncertainOperationId
  const operationValue =
    !operationEdited && uncertainOperationId !== null ? uncertainOperationId : operationInput
  const canPrepare = filter.from !== '' && filter.to !== '' && reason.trim() !== ''

  return (
    <>
      {/* 대화상자가 열려 있는 동안 배경을 비활성화한다. 대화상자는 이 요소의 형제다. */}
      <div className={styles.page} inert={dialogOpen}>
        <a className={styles.skipLink} href={`#${copy.mainId}`}>
          {entryContent.skipLink}
        </a>

        <header className={styles.header}>
          {/* 운영자 판정 전에는 운영자 표면임을 말하지 않는다. */}
          <p className={styles.brand}>{operatorVisible ? copy.brand : entryContent.brand}</p>
          {ready ? (
            <SignOutButton
              onSignOut={() => {
                if (busy && !signOut.pending) {
                  return
                }
                signOut.requestSignOut()
              }}
              pending={busy}
            />
          ) : null}
        </header>

        <main className={styles.main} id={copy.mainId} tabIndex={-1}>
          <div className={styles.intro}>
            {/* UX-DR23: 제목과 바로 아래 고지를 인접 배치한다. 제목은 일반 사용자 화면과 구분된다. */}
            <h1 className={styles.title}>
              {operatorVisible ? copy.heading : authContent.pageTitle}
            </h1>
            <p className={styles.disclaimer}>{authContent.disclaimer}</p>
            {operatorVisible ? <p className={styles.surfaceNote}>{copy.surfaceNote}</p> : null}
          </div>

          {/* 화면당 polite live region 하나. 진행·결과·거절을 여기서 말한다. */}
          <div
            aria-busy={audit.view.loading || exporting.busy || restricting.busy}
            aria-live="polite"
            className={styles.notice}
            role="status"
          >
            <p className={styles.noticeText}>
              {statusText(
                ready,
                gate,
                signOut.pending,
                reauthentication.pending,
                lastCommand === 'restriction' ? restricting : exporting,
                lastCommand,
                lookup.lookup.status === 'loading',
                audit.view,
                auditVisible,
                restricting.list,
                loadFailed,
              )}
            </p>
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

          {operatorVisible ? (
            <>
              {auditVisible ? (
                <>
                  <section aria-labelledby={copy.audit.id} className={styles.section}>
                    <h2 className={styles.sectionHeading} id={copy.audit.id}>
                      {copy.audit.heading}
                    </h2>
                    <p>{copy.audit.body}</p>
                    <form
                      className={styles.form}
                      onSubmit={(event) => {
                        // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
                        event.preventDefault()
                        if (!audit.view.loading) {
                          search(filter)
                        }
                      }}
                    >
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="operator-filter-from">
                          {copy.audit.fromLabel}
                        </label>
                        <input
                          className={styles.input}
                          id="operator-filter-from"
                          onChange={(event) => {
                            setFilter({ ...filter, from: event.target.value })
                          }}
                          type="date"
                          value={filter.from}
                        />
                      </div>
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="operator-filter-to">
                          {copy.audit.toLabel}
                        </label>
                        <input
                          className={styles.input}
                          id="operator-filter-to"
                          onChange={(event) => {
                            setFilter({ ...filter, to: event.target.value })
                          }}
                          type="date"
                          value={filter.to}
                        />
                      </div>
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="operator-filter-outcome">
                          {copy.audit.outcomeLabel}
                        </label>
                        <select
                          className={styles.input}
                          id="operator-filter-outcome"
                          onChange={(event) => {
                            setFilter({ ...filter, outcome: event.target.value })
                          }}
                          value={filter.outcome}
                        >
                          <option value="">{copy.audit.outcomeAll}</option>
                          {OUTCOME_FILTERS.map((outcome) => (
                            <option key={outcome} value={outcome}>
                              {outcomeLabel(outcome)}
                            </option>
                          ))}
                        </select>
                      </div>
                      <button
                        aria-disabled={audit.view.loading}
                        className={styles.button}
                        type="submit"
                      >
                        {copy.audit.search}
                      </button>
                    </form>

                    {audit.view.items.length === 0 && audit.view.status === 'loaded' ? (
                      <p className={styles.empty}>{copy.audit.empty}</p>
                    ) : (
                      <ol className={styles.list}>
                        {audit.view.items.map((entry) => (
                          <AuditRow entry={entry} key={entry.sequence} />
                        ))}
                      </ol>
                    )}
                    {audit.view.nextCursor === null ? null : (
                      <button
                        aria-disabled={audit.view.loading}
                        className={styles.button}
                        onClick={audit.loadMore}
                        type="button"
                      >
                        {copy.audit.more}
                      </button>
                    )}
                  </section>

                  <section aria-labelledby={copy.export.id} className={styles.section}>
                    <h2 className={styles.sectionHeading} id={copy.export.id}>
                      {copy.export.heading}
                    </h2>
                    <p>{copy.export.body}</p>
                    <p>
                      {copy.export.periodLabel}: {target}
                    </p>
                    <div className={styles.wideField}>
                      <label className={styles.label} htmlFor="operator-export-reason">
                        {copy.export.reasonLabel}
                      </label>
                      <input
                        aria-describedby="operator-export-reason-help"
                        autoComplete="off"
                        className={styles.input}
                        id="operator-export-reason"
                        maxLength={REASON_MAX_LENGTH}
                        onChange={(event) => {
                          setReason(event.target.value)
                        }}
                        type="text"
                        value={reason}
                      />
                      <p className={styles.help} id="operator-export-reason-help">
                        {copy.export.reasonHelp}
                      </p>
                    </div>
                    <button
                      aria-disabled={busy || !canPrepare}
                      className={styles.dangerButton}
                      id={copy.export.startId}
                      onClick={() => {
                        if (busy || !canPrepare) {
                          return
                        }
                        setLastCommand('export')
                        exporting.prepare(target, reason.trim())
                      }}
                      type="button"
                    >
                      {copy.export.start}
                    </button>

                    {exporting.reauthRequired && lastCommand === 'export' ? (
                      <div className={styles.result}>
                        <p className={styles.label}>{copy.export.reauthLabel}</p>
                        <div className={reauthStyles.providerList}>
                          {SIGN_IN_PROVIDERS.map((provider) => (
                            <button
                              aria-disabled={busy}
                              className={PROVIDER_BUTTON_CLASS[provider]}
                              key={provider}
                              onClick={() => {
                                if (busy) return
                                reauthentication.requestReauthentication(provider)
                              }}
                              type="button"
                            >
                              <ProviderMark provider={provider} />
                              <span>{authContent.settings.reauth.labels[provider]}</span>
                            </button>
                          ))}
                        </div>
                        <p className={styles.help}>{authContent.reauth.boundary}</p>
                      </div>
                    ) : null}

                    {exporting.completed === null ? null : (
                      <div className={styles.result}>
                        <h3 className={styles.label} ref={resultRef} tabIndex={-1}>
                          {copy.export.result.heading}
                        </h3>
                        <p>
                          {copy.export.result.operation}: {exporting.completed.operationId}
                        </p>
                        <p>
                          {copy.export.result.rows}: {exporting.completed.rowCount}
                        </p>
                        {exporting.completed.truncated ? (
                          <p>{copy.export.result.truncated}</p>
                        ) : null}
                        <button
                          className={styles.button}
                          onClick={() => {
                            const done = exporting.completed
                            if (done !== null) download(done.operationId, done.rows)
                          }}
                          type="button"
                        >
                          {copy.export.result.download}
                        </button>
                      </div>
                    )}
                  </section>
                </>
              ) : null}

              {restrictionVisible ? (
                <section aria-labelledby={copy.restriction.id} className={styles.section}>
                  <h2 className={styles.sectionHeading} id={copy.restriction.id}>
                    {copy.restriction.heading}
                  </h2>
                  <p>{copy.restriction.body}</p>
                  {listLoaded ? (
                    <>
                      <form
                        className={styles.form}
                        onSubmit={(event) => {
                          // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
                          event.preventDefault()
                        }}
                      >
                        <div className={styles.field}>
                          <label className={styles.label} htmlFor="operator-restriction-feature">
                            {copy.restriction.featureLabel}
                          </label>
                          <select
                            className={styles.input}
                            id="operator-restriction-feature"
                            onChange={(event) => {
                              const next = event.target.value as RestrictionFeature
                              setFeature(next)
                              // 기능이 받지 않는 범위는 전역으로 접는다. 서버가 같은 조합을 다시 검증한다.
                              if (!RESTRICTION_SCOPES[next].includes(scope)) {
                                setScope('GLOBAL')
                                setRestrictionTarget(GLOBAL_TARGET)
                              }
                            }}
                            value={feature}
                          >
                            {RESTRICTION_FEATURES.map((option) => (
                              <option key={option} value={option}>
                                {featureLabel(option)}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div className={styles.field}>
                          <label className={styles.label} htmlFor="operator-restriction-scope">
                            {copy.restriction.scopeLabel}
                          </label>
                          <select
                            className={styles.input}
                            id="operator-restriction-scope"
                            onChange={(event) => {
                              const next = event.target.value as RestrictionScope
                              setScope(next)
                              setRestrictionTarget(defaultTarget(next))
                            }}
                            value={scope}
                          >
                            {RESTRICTION_SCOPES[feature].map((option) => (
                              <option key={option} value={option}>
                                {scopeLabel(option)}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-restriction-target">
                            {copy.restriction.targetLabel}
                          </label>
                          <input
                            aria-describedby="operator-restriction-target-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-restriction-target"
                            onChange={(event) => {
                              setRestrictionTarget(event.target.value)
                            }}
                            readOnly={scope === 'GLOBAL'}
                            spellCheck={false}
                            type="text"
                            value={
                              scope === 'GLOBAL' ? copy.restriction.globalTarget : restrictionTarget
                            }
                          />
                          <p className={styles.help} id="operator-restriction-target-help">
                            {copy.restriction.targetHelp}
                          </p>
                        </div>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-restriction-reason">
                            {copy.restriction.reasonLabel}
                          </label>
                          <input
                            aria-describedby="operator-restriction-reason-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-restriction-reason"
                            maxLength={REASON_MAX_LENGTH}
                            onChange={(event) => {
                              setRestrictionReason(event.target.value)
                            }}
                            type="text"
                            value={restrictionReason}
                          />
                          <p className={styles.help} id="operator-restriction-reason-help">
                            {copy.restriction.reasonHelp}
                          </p>
                        </div>
                      </form>
                      <button
                        aria-disabled={busy || !canRestrict}
                        className={styles.dangerButton}
                        id={copy.restriction.startId}
                        onClick={() => {
                          if (busy || !canRestrict) {
                            return
                          }
                          setLastCommand('restriction')
                          restricting.prepare({
                            command: RESTRICTION_COMMANDS[feature],
                            scope,
                            target: scope === 'GLOBAL' ? GLOBAL_TARGET : restrictionTarget.trim(),
                            reason: restrictionReason.trim(),
                          })
                        }}
                        type="button"
                      >
                        {copy.restriction.start}
                      </button>

                      {restricting.reauthRequired && lastCommand === 'restriction' ? (
                        <div className={styles.result}>
                          <p className={styles.label}>{copy.restriction.reauthLabel}</p>
                          <div className={reauthStyles.providerList}>
                            {SIGN_IN_PROVIDERS.map((provider) => (
                              <button
                                aria-disabled={busy}
                                className={PROVIDER_BUTTON_CLASS[provider]}
                                key={provider}
                                onClick={() => {
                                  if (busy) return
                                  reauthentication.requestReauthentication(provider)
                                }}
                                type="button"
                              >
                                <ProviderMark provider={provider} />
                                <span>{authContent.settings.reauth.labels[provider]}</span>
                              </button>
                            ))}
                          </div>
                          <p className={styles.help}>{authContent.reauth.boundary}</p>
                        </div>
                      ) : null}

                      {restricting.completed === null ? null : (
                        <div className={styles.result}>
                          <h3 className={styles.label} ref={restrictionResultRef} tabIndex={-1}>
                            {copy.restriction.result.heading}
                          </h3>
                          <p>
                            {copy.restriction.result.operation}: {restricting.completed.operationId}
                          </p>
                          <p>
                            {copy.restriction.result.restriction}:{' '}
                            {restricting.completed.restrictionId}
                          </p>
                          <p>
                            {copy.restriction.result.effectiveFrom}:{' '}
                            {formatVerifiedAt(restricting.completed.effectiveFrom)}
                          </p>
                        </div>
                      )}
                    </>
                  ) : null}

                  <h3 className={styles.label} id={copy.restriction.active.id}>
                    {copy.restriction.active.heading}
                  </h3>
                  <p>{copy.restriction.active.body}</p>
                  {restricting.list.status === 'failed' ? (
                    <p className={styles.empty}>{copy.restriction.active.failed}</p>
                  ) : null}
                  {/* 실패와 "없음"을 함께 말하지 않는다 — 비었다는 것은 목록을 읽었을 때만 참이다. */}
                  {restricting.list.items.length === 0 ? (
                    restricting.list.status === 'loaded' ? (
                      <p className={styles.empty}>{copy.restriction.active.empty}</p>
                    ) : null
                  ) : (
                    <ol aria-labelledby={copy.restriction.active.id} className={styles.list}>
                      {restricting.list.items.map((restriction) => (
                        <RestrictionRow key={restriction.restrictionId} restriction={restriction} />
                      ))}
                    </ol>
                  )}
                  <button
                    aria-disabled={restricting.list.loading}
                    className={styles.button}
                    onClick={() => {
                      if (!restricting.list.loading) restricting.reloadList()
                    }}
                    type="button"
                  >
                    {copy.restriction.active.reload}
                  </button>
                </section>
              ) : null}

              <section aria-labelledby={copy.operation.id} className={styles.section}>
                <h2 className={styles.sectionHeading} id={copy.operation.id}>
                  {copy.operation.heading}
                </h2>
                <p>{copy.operation.body}</p>
                <form
                  className={styles.form}
                  onSubmit={(event) => {
                    event.preventDefault()
                    lookup.find(operationValue)
                  }}
                >
                  <div className={styles.wideField}>
                    <label className={styles.label} htmlFor="operator-operation-id">
                      {copy.operation.label}
                    </label>
                    <input
                      autoComplete="off"
                      className={styles.input}
                      id="operator-operation-id"
                      onChange={(event) => {
                        setOperationEdited(true)
                        setOperationInput(event.target.value)
                      }}
                      spellCheck={false}
                      type="text"
                      value={operationValue}
                    />
                  </div>
                  <button className={styles.button} type="submit">
                    {copy.operation.search}
                  </button>
                </form>
                {lookup.lookup.status === 'loaded' ? (
                  <ol className={styles.list}>
                    {lookup.lookup.items.map((entry) => (
                      <AuditRow entry={entry} key={entry.sequence} />
                    ))}
                  </ol>
                ) : lookup.lookup.status === 'notFound' ? (
                  <p className={styles.empty}>{copy.operation.notFound}</p>
                ) : lookup.lookup.status === 'failed' ? (
                  <p className={styles.empty}>{copy.operation.failed}</p>
                ) : null}
              </section>
            </>
          ) : null}
        </main>
      </div>

      {exportDialogOpen ? (
        <OperatorExportDialog
          code={exporting.code}
          confirming={exporting.phase === 'confirming'}
          onCancel={exporting.cancel}
          onConfirm={exporting.confirm}
          prepared={exporting.prepared}
          uncertain={exporting.uncertain}
        />
      ) : null}
      {restrictionDialogOpen ? (
        <OperatorRestrictionDialog
          code={restricting.code}
          confirming={restricting.phase === 'confirming'}
          onCancel={restricting.cancel}
          onConfirm={restricting.confirm}
          prepared={restricting.prepared}
          uncertain={restricting.uncertain}
        />
      ) : null}
    </>
  )
}

/** live region의 문장. 조작의 진행이 조회 상태보다 앞선다. */
function statusText(
  ready: boolean,
  gate: SessionGate,
  signingOut: boolean,
  reauthenticating: boolean,
  command: ReturnType<typeof useOperatorExport> | ReturnType<typeof useOperatorRestriction>,
  kind: 'export' | 'restriction',
  lookingUp: boolean,
  audit: ReturnType<typeof useOperatorAudit>['view'],
  auditVisible: boolean,
  list: ReturnType<typeof useOperatorRestriction>['list'],
  loadFailed: boolean,
): string {
  if (!ready || signingOut || reauthenticating) {
    return liveRegionMessage(gate, signingOut, reauthenticating)
  }
  const sentence = kind === 'export' ? exportSentence(command) : restrictionSentence(command)
  if (sentence !== null) {
    return sentence
  }
  if (lookingUp) {
    return copy.status.operationLoading
  }
  if (loadFailed) {
    // 운영자임이 확인되지 않았다. 감사 원장을 말하지 않는 일반 문장이다.
    return copy.loadFailed
  }
  if (!auditVisible) {
    // 감사 권한이 없거나 아직 모른다(Story 3.2). 제한 목록의 상태를 말하되, 운영자임이 확인되기 전에는 일반 문장이다.
    if (!list.everLoaded) return copy.status.checking
    if (list.loading) return copy.status.restrictionsLoading
    return list.status === 'failed'
      ? copy.restriction.active.failed
      : copy.status.restrictionsLoaded(list.items.length)
  }
  if (audit.loading || audit.status === 'checking') {
    return copy.status.loading
  }
  if (audit.status === 'failed') {
    return copy.status.failed
  }
  return copy.status.loaded(audit.items.length)
}

/** 감사 내보내기의 진행·결과 문장. 말할 것이 없으면 `null`. */
function exportSentence(
  exporting: ReturnType<typeof useOperatorExport> | ReturnType<typeof useOperatorRestriction>,
): string | null {
  switch (exporting.phase) {
    case 'preparing':
      return copy.status.preparing
    case 'armed':
      return exporting.uncertain ? copy.export.uncertain : copy.status.prepared
    case 'confirming':
      return copy.status.confirming
    case 'completed': {
      const done = exporting.completed
      return copy.status.completed(done !== null && 'rowCount' in done ? done.rowCount : 0)
    }
    case 'cancelled':
      return exporting.uncertainOperationId === null
        ? copy.status.cancelled
        : copy.export.uncertainClosed
    case 'refused':
      return operatorRefusalMessage(exporting.code, 'audit-export')
    case 'idle':
      return null
  }
}

/** 영향 범위 제한의 진행·결과 문장(Story 3.2). 말할 것이 없으면 `null`. */
function restrictionSentence(
  restricting: ReturnType<typeof useOperatorExport> | ReturnType<typeof useOperatorRestriction>,
): string | null {
  switch (restricting.phase) {
    case 'preparing':
      return copy.status.restrictionPreparing
    case 'armed':
      return restricting.uncertain ? copy.restriction.uncertain : copy.status.restrictionPrepared
    case 'confirming':
      return copy.status.restrictionConfirming
    case 'completed': {
      const done = restricting.completed
      return done !== null && 'feature' in done
        ? copy.status.restrictionCompleted(featureLabel(done.feature), scopeLabel(done.scope))
        : copy.status.restrictionConfirming
    }
    case 'cancelled':
      return restricting.uncertainOperationId === null
        ? copy.status.restrictionCancelled
        : copy.restriction.uncertainClosed
    case 'refused':
      return operatorRefusalMessage(restricting.code)
    case 'idle':
      return null
  }
}
