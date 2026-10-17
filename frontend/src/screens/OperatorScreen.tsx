import { useCallback, useLayoutEffect, useRef, useState, type ReactElement } from 'react'

import type {
  ActiveRestriction,
  AuditEntry,
  NoticeIncident,
  NoticeVersionEntry,
  OperatorInquiry,
} from '../session/operatorApi'

import { OperatorExportDialog } from '../components/OperatorExportDialog'
import { OperatorInquiryDialog } from '../components/OperatorInquiryDialog'
import { OperatorNoticeDialog } from '../components/OperatorNoticeDialog'
import { OperatorRecoveryDialog } from '../components/OperatorRecoveryDialog'
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
import { containsForbiddenCopy } from '../content/copyRules'
import { entryContent } from '../content/entry'
import {
  businessMinutesText,
  CALCULATION_POLICY_TARGET,
  DEPLOY_VERSION_MAX_LENGTH,
  featureLabel,
  featuresText,
  FOLLOW_UP_NOTICE_STATUSES,
  formatOperatorTime,
  GLOBAL_TARGET,
  incidentOptionLabel,
  INQUIRY_ANSWER_MAX_LENGTH,
  INQUIRY_FILTER_STATUSES,
  inquiryCategoryText,
  inquiryNextStatuses,
  inquiryStatusText,
  inquiryTargetMetText,
  ledgerCodeLabel,
  NEW_INCIDENT,
  NOTICE_MESSAGE_MAX_LENGTH,
  noticeStatusLabel,
  noticeTime,
  operatorContent,
  operatorRefusalMessage,
  originLabel,
  OUTCOME_FILTERS,
  outcomeLabel,
  performanceReasonLabel,
  PUBLISH_NOTICE_COMMAND,
  REASON_MAX_LENGTH,
  recoverButtonLabel,
  RECOVERY_CONDITION_LABELS,
  RECOVERY_CONDITIONS,
  RESTRICTION_COMMANDS,
  RESTRICTION_FEATURES,
  RESTRICTION_SCOPES,
  scopeLabel,
  signalReasonLabel,
  smokeStatusLabel,
  stageLabel,
  UPDATE_INQUIRY_COMMAND,
  type FollowUpNoticeStatus,
  type InquiryNextStatus,
  type RecoveryCondition,
  type RestrictionFeature,
  type RestrictionScope,
} from '../content/operator'
import { kstInputToIso } from '../content/serviceNotice'
import { replaceLocation } from '../session/navigation'
import {
  defaultAuditFilter,
  useOperationLookup,
  useOperatorAudit,
  type AuditFilter,
} from '../session/useOperatorAudit'
import { useOperatorExport } from '../session/useOperatorExport'
import { useOperatorInquiry } from '../session/useOperatorInquiry'
import { useOperatorNotice } from '../session/useOperatorNotice'
import { useOperatorRecovery } from '../session/useOperatorRecovery'
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
        <span className={styles.value}>{formatOperatorTime(entry.recordedAt)}</span>
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

/** 제한의 화면 대상: 비식별이면 `삭제됨`, 전역이면 `전역`, 그 밖은 원 target이다. */
function restrictionTargetLabel(restriction: ActiveRestriction): string {
  if (restriction.targetErased) return copy.restriction.active.erased
  if (restriction.scope === 'GLOBAL') return scopeLabel('GLOBAL')
  return restriction.target ?? copy.restriction.active.erased
}

/**
 * 활성 제한 하나. 비식별된 대상은 `삭제됨`으로만 보인다 — 색이 아니라 글자로 말한다. 신호가 만들었거나 신호가 연결된
 * 제한(Story 3.3)은 `수동 검증 필요` 상태를 글자로 보이고, 자동 정지의 사유는 신호 종류의 문장이다. 행마다 그 제한
 * 하나를 여는 `복구` 동작이 있다(Story 3.4).
 */
function RestrictionRow({
  busy,
  onRecover,
  restriction,
}: {
  readonly restriction: ActiveRestriction
  readonly busy: boolean
  readonly onRecover: (restriction: ActiveRestriction) => void
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
        <span className={styles.value}>{restrictionTargetLabel(restriction)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.effectiveFrom}</span>
        <span className={styles.value}>{formatOperatorTime(restriction.effectiveFrom)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.reason}</span>
        <span className={styles.value}>
          {restriction.reinstated
            ? copy.restriction.active.reinstated
            : restriction.reason === null
              ? copy.restriction.active.noReason
              : restriction.origin === 'SIGNAL'
                ? signalReasonLabel(restriction.reason)
                : restriction.origin === 'PERFORMANCE'
                  ? performanceReasonLabel(restriction.reason)
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
      <button
        aria-disabled={busy}
        // 행마다 같은 글자라 이름에 기능·범위·대상을 붙여 가른다. 보이는 글자로 시작한다(label-in-name).
        aria-label={recoverButtonLabel(
          restriction.feature,
          restriction.scope,
          restrictionTargetLabel(restriction),
        )}
        className={styles.button}
        onClick={() => {
          if (!busy) onRecover(restriction)
        }}
        type="button"
      >
        {copy.restriction.active.recover}
      </button>
    </li>
  )
}

/**
 * 공지 사건 하나와 그 버전 이력(Story 3.5, 읽기 전용). 상태는 글자로 말한다 — 색만으로 가르지 않는다. 버전은 오래된
 * 순이고 고치거나 지우는 조작이 없다.
 */
function IncidentRow({ incident }: { readonly incident: NoticeIncident }): ReactElement {
  const history = copy.notice.history
  const columns = history.columns
  return (
    <li className={styles.entry}>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.incident}</span>
        <span className={styles.value}>{incident.incidentId}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.startedAt}</span>
        <span className={styles.value}>{noticeTime(incident.startedAt)}</span>
        <span className={styles.term}>{columns.state}</span>
        <span className={styles.value}>{incident.closed ? history.closed : history.open}</span>
      </div>
      <ol className={styles.list}>
        {incident.versions.map((version) => (
          <li className={styles.entry} key={version.version}>
            <div className={styles.entryRow}>
              <span className={styles.term}>{columns.version}</span>
              <span className={styles.value}>
                v{String(version.version)} {noticeStatusLabel(version.status)}
              </span>
              <span className={styles.term}>{columns.createdAt}</span>
              <span className={styles.value}>{noticeTime(version.createdAt)}</span>
            </div>
            <div className={styles.entryRow}>
              <span className={styles.term}>{columns.affected}</span>
              <span className={styles.value}>{featuresText(version.affectedFeatures)}</span>
              <span className={styles.term}>{columns.continuing}</span>
              <span className={styles.value}>{featuresText(version.continuingFeatures)}</span>
            </div>
            <div className={styles.entryRow}>
              <span className={styles.term}>{columns.message}</span>
              <span className={styles.value}>{version.message}</span>
            </div>
            <div className={styles.entryRow}>
              {version.recoveredAt === null ? (
                <>
                  <span className={styles.term}>{columns.nextUpdateAt}</span>
                  <span className={styles.value}>
                    {version.nextUpdateAt === null ? '' : noticeTime(version.nextUpdateAt)}
                  </span>
                </>
              ) : (
                <>
                  <span className={styles.term}>{columns.recoveredAt}</span>
                  <span className={styles.value}>{noticeTime(version.recoveredAt)}</span>
                </>
              )}
              <span className={styles.term}>{columns.covers}</span>
              <span className={styles.value}>{String(version.covers.length)}</span>
            </div>
          </li>
        ))}
      </ol>
    </li>
  )
}

/**
 * 문의 하나(Story 3.6, 최소화한 목록). 본문·답변이 없고 회신 이메일은 가린 값이다. 상태·달성 여부는 글자로 말한다 — 색만으로
 * 가르지 않는다. `상세 보기`가 본문을 연다.
 */
function InquiryRow({
  disabled,
  inquiry,
  onSelect,
}: {
  readonly inquiry: OperatorInquiry
  readonly disabled: boolean
  readonly onSelect: () => void
}): ReactElement {
  const columns = copy.inquiry.columns
  return (
    <li className={styles.entry}>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.reference}</span>
        <span className={styles.value}>{inquiry.reference}</span>
        <span className={styles.term}>{columns.status}</span>
        <span className={`${styles.value ?? ''} ${styles.outcome ?? ''}`}>
          {inquiryStatusText(inquiry.status)}
        </span>
        <span className={styles.term}>{columns.category}</span>
        <span className={styles.value}>{inquiryCategoryText(inquiry.category)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.title}</span>
        <span className={styles.value}>{inquiry.title}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.receivedAt}</span>
        <span className={styles.value}>{noticeTime(inquiry.receivedAt)}</span>
        <span className={styles.term}>{columns.targetAt}</span>
        <span className={styles.value}>
          {inquiry.targetAt === null ? copy.inquiry.targetPending : noticeTime(inquiry.targetAt)}
        </span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.firstAnsweredAt}</span>
        <span className={styles.value}>
          {inquiry.firstAnsweredAt === null ? '-' : noticeTime(inquiry.firstAnsweredAt)}
        </span>
        <span className={styles.term}>{columns.targetMet}</span>
        <span className={styles.value}>{inquiryTargetMetText(inquiry.targetMet)}</span>
      </div>
      <div className={styles.entryRow}>
        <span className={styles.term}>{columns.contact}</span>
        <span className={styles.value}>
          {copy.inquiry.contactLabels[inquiry.contact] ?? inquiry.contact}
        </span>
        {inquiry.replyEmail === null ? null : (
          <>
            <span className={styles.term}>{columns.replyEmail}</span>
            <span className={styles.value}>{inquiry.replyEmail}</span>
          </>
        )}
      </div>
      <button
        aria-disabled={disabled}
        className={styles.button}
        onClick={() => {
          if (!disabled) onSelect()
        }}
        type="button"
      >
        {copy.inquiry.select(inquiry.reference)}
      </button>
    </li>
  )
}

/** 오늘(KST)의 `YYYY-MM-DD`. 집계 기간의 기본값이다. */
function kstToday(offsetDays = 0): string {
  const shifted = new Date(Date.now() + offsetDays * 24 * 60 * 60 * 1000)
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(shifted)
}

/**
 * 사건의 최신 버전이 덮는 제한 가운데 지금 열린 것(Epic 3 회고 AI-1). 공지는 제한 하나가 아니라 그 되돌림 계보를 덮으므로
 * 서버가 계보에서 지금 열린 제한(`liveCovers`)을 계산해 준다 — 해제 smoke가 같은 범위를 새 제한으로 되돌렸으면(닫힌 중간
 * 행을 지나도) 그 새 제한이다. 서버가 그 값을 싣지 않았으면 덮는 제한으로 접는다. 사건 목록은 활성 제한 목록보다 먼저 읽혔을
 * 수 있으므로(복구는 활성 목록만 다시 읽는다) 지금 활성 목록에 있는 것만 센다 — 그 사이 복구된 제한을 아직 효력 중으로 말하지
 * 않는다.
 */
function liveCoversOf(
  version: NoticeVersionEntry | undefined,
  items: readonly ActiveRestriction[],
): readonly string[] {
  if (version === undefined) {
    return []
  }
  return (version.liveCovers ?? version.covers).filter((id) =>
    items.some((item) => item.restrictionId === id),
  )
}

/** `datetime-local` 입력을 서울 시각(KST)으로 읽어 ISO-8601로. 브라우저 시간대를 쓰지 않는다. 읽을 수 없으면 `null`이다. */
function nextUpdateIso(value: string): string | null {
  return kstInputToIso(value)
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
  /** 화면의 단일 status가 말할 마지막 조작. 네 명령의 끝난 상태가 함께 남아도 사용자가 마지막에 누른 쪽을 말한다. */
  const [lastCommand, setLastCommand] = useState<
    'export' | 'inquiry' | 'notice' | 'recovery' | 'restriction'
  >('export')
  // 제한 복구(Story 3.4): 활성 목록에서 고른 제한 하나와 그 증거 입력. 값은 메모리에만 있다.
  const recovering = useOperatorRecovery(csrfToken, restricting.reloadList)
  const [chosenRecovery, setRecoveryTarget] = useState<ActiveRestriction | null>(null)
  /*
   * 고른 제한은 지금 목록에 있을 때만 복구 영역을 연다. 복구가 끝나 목록을 다시 읽으면 그 행이 빠지고 폼도 사라진다 —
   * 닫힌 제한의 폼이 남아 다시 준비되지 않는다. 남은 입력은 다음에 행을 고를 때 비운다.
   */
  const recoveryTarget =
    chosenRecovery !== null &&
    restricting.list.items.some((item) => item.restrictionId === chosenRecovery.restrictionId)
      ? chosenRecovery
      : null
  const [conditions, setConditions] = useState<readonly RecoveryCondition[]>([])
  const [cause, setCause] = useState('')
  const [verification, setVerification] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [deployVersion, setDeployVersion] = useState('')
  /*
   * 서비스 공지(Story 3.5): 사건 목록은 command 권한이 제한 목록으로 확인된 뒤에만 읽는다. 입력은 메모리에만 있다. 덮는
   * 제한은 활성 제한 목록에서 고른다 — 목록에서 빠진(닫힌) 제한은 고른 값에서도 빠진다.
   */
  const noticing = useOperatorNotice(ready && restricting.list.everLoaded, csrfToken)
  const [noticeIncident, setNoticeIncident] = useState(NEW_INCIDENT)
  const [noticeStatus, setNoticeStatus] = useState<FollowUpNoticeStatus>('SCOPE_CHANGED')
  const [chosenCovers, setNoticeCovers] = useState<readonly string[]>([])
  /** 운영자가 덮는 제한을 직접 고쳤는가. 고치기 전에는 기존 사건의 기본값이 목록과 함께 따라간다. */
  const [coversEdited, setCoversEdited] = useState(false)
  const [noticeMessage, setNoticeMessage] = useState('')
  const [noticeNext, setNoticeNext] = useState('')
  const openIncidents = noticing.list.items.filter((incident) => !incident.closed)
  // 고른 사건이 목록에서 사라졌거나 닫혔으면 새 사건으로 접는다 — 닫힌 사건에 다음 버전을 준비하지 않는다.
  const incidentChoice = openIncidents.some((incident) => incident.incidentId === noticeIncident)
    ? noticeIncident
    : NEW_INCIDENT
  /*
   * 덮는 제한. 기존 사건을 골랐고 운영자가 아직 고치지 않았으면 그 최신 버전이 덮던 제한의 되돌림 계보 가운데 지금 활성인
   * 것이 기본값이다 — 활성 목록이 사건을 고른 뒤에 다시 읽혀도 그 목록으로 다시 계산한다. 고친 뒤에는 고른 값이고, 목록에서
   * 빠진(닫힌) 제한은 고른 값에서도 빠진다.
   */
  const noticeCovers =
    incidentChoice !== NEW_INCIDENT && !coversEdited
      ? liveCoversOf(
          openIncidents.find((incident) => incident.incidentId === incidentChoice)?.versions.at(-1),
          restricting.list.items,
        )
      : chosenCovers.filter((id) =>
          restricting.list.items.some((item) => item.restrictionId === id),
        )
  const effectiveNoticeStatus = incidentChoice === NEW_INCIDENT ? 'INVESTIGATING' : noticeStatus
  const noticeRecovering = effectiveNoticeStatus === 'RECOVERED'
  /*
   * 복구 공지는 덮는 제한을 고르지 않는다 — 서버가 직전 버전의 것을 잇고, 그 제한의 되돌림 계보가 모두 닫혀 있어야 받는다.
   * 그 계보 가운데 지금 열린(효력 중인) 제한의 수를 미리 말한다(서버의 `liveCovers`).
   */
  const recoveringStillOpen = noticeRecovering
    ? liveCoversOf(
        openIncidents.find((incident) => incident.incidentId === incidentChoice)?.versions.at(-1),
        restricting.list.items,
      ).length
    : 0
  // 운영자 한 줄도 사용자 화면에 나간다. 화면 카피 금지 표현이 있으면 미리 막는다(서버도 같은 목록으로 거절한다).
  const noticeMessageForbidden = containsForbiddenCopy(noticeMessage)
  /*
   * 문의 처리(Story 3.6): 문의 목록은 command 권한이 제한 목록으로 확인된 뒤에만 읽는다. 고른 문의 하나의 상세에서만 본문이
   * 보이고, 그 상세 아래에서 상태·답변을 준비한다. 입력은 메모리에만 있다.
   */
  const inquiring = useOperatorInquiry(ready && restricting.list.everLoaded, csrfToken)
  const [chosenInquiryStatus, setInquiryStatus] = useState<InquiryNextStatus | null>(null)
  const [inquiryLookup, setInquiryLookup] = useState('')
  const [inquiryAnswer, setInquiryAnswer] = useState('')
  const [inquiryReason, setInquiryReason] = useState('')
  const [metricsFrom, setMetricsFrom] = useState(() => kstToday(-30))
  const [metricsTo, setMetricsTo] = useState(() => kstToday())
  const selectedInquiry = inquiring.detail.status === 'idle' ? null : inquiring.detail.reference
  /*
   * 처리 폼의 상태 선택은 지금 상태에서 갈 수 있는 다음 전이만이다. 고른 값이 그 안에 없으면(다른 문의를 골랐거나 상태가
   * 바뀌었다) 첫 전이로 접는다. 해결된 문의는 고를 것이 없다.
   */
  const nextInquiryStatuses =
    inquiring.detail.status === 'loaded'
      ? inquiryNextStatuses(inquiring.detail.value.summary.status)
      : []
  const inquiryStatus: InquiryNextStatus | null =
    chosenInquiryStatus !== null && nextInquiryStatuses.includes(chosenInquiryStatus)
      ? chosenInquiryStatus
      : (nextInquiryStatuses[0] ?? null)

  const resultRef = useRef<HTMLHeadingElement>(null)
  const noticeResultRef = useRef<HTMLHeadingElement>(null)
  const restrictionResultRef = useRef<HTMLHeadingElement>(null)
  const recoveryHeadingRef = useRef<HTMLHeadingElement>(null)
  const recoveryResultRef = useRef<HTMLHeadingElement>(null)
  const inquiryDetailRef = useRef<HTMLHeadingElement>(null)
  const inquiryResultRef = useRef<HTMLHeadingElement>(null)

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
  // 행의 `복구`를 누른 결과는 복구 영역이다 — 그 제목으로 초점을 옮긴다.
  const recoveryTargetId = recoveryTarget?.restrictionId ?? null
  useLayoutEffect(() => {
    if (recoveryTargetId !== null) {
      recoveryHeadingRef.current?.focus()
    }
  }, [recoveryTargetId])
  const recovered = recovering.phase === 'completed'
  useLayoutEffect(() => {
    if (recovered) {
      recoveryResultRef.current?.focus()
    }
  }, [recovered])
  const published = noticing.phase === 'completed'
  useLayoutEffect(() => {
    if (published) {
      noticeResultRef.current?.focus()
    }
  }, [published])
  // 행의 `상세 보기`를 누른 결과는 문의 상세다 — 그 제목으로 초점을 옮긴다.
  useLayoutEffect(() => {
    if (selectedInquiry !== null) {
      inquiryDetailRef.current?.focus()
    }
  }, [selectedInquiry])
  const inquiryRecorded = inquiring.phase === 'completed'
  useLayoutEffect(() => {
    if (inquiryRecorded) {
      inquiryResultRef.current?.focus()
    }
  }, [inquiryRecorded])

  // 서버가 세션이 끝났다고 답했다. 만료의 결과는 이동 하나다(`useSessionGate`와 같은 규칙).
  const sessionEnded =
    audit.view.status === 'unauthenticated' ||
    exporting.sessionEnded ||
    restricting.sessionEnded ||
    recovering.sessionEnded ||
    noticing.sessionEnded ||
    inquiring.sessionEnded ||
    restricting.list.status === 'unauthenticated' ||
    noticing.list.status === 'unauthenticated' ||
    inquiring.list.status === 'unauthenticated' ||
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
  const recoveryDialogOpen =
    recovering.prepared !== null &&
    (recovering.phase === 'armed' || recovering.phase === 'confirming')
  const noticeDialogOpen =
    noticing.prepared !== null && (noticing.phase === 'armed' || noticing.phase === 'confirming')
  const inquiryDialogOpen =
    inquiring.prepared !== null && (inquiring.phase === 'armed' || inquiring.phase === 'confirming')
  const dialogOpen =
    exportDialogOpen ||
    restrictionDialogOpen ||
    recoveryDialogOpen ||
    noticeDialogOpen ||
    inquiryDialogOpen
  const busy =
    exporting.busy ||
    restricting.busy ||
    recovering.busy ||
    noticing.busy ||
    inquiring.busy ||
    reauthentication.pending ||
    signOut.pending ||
    dialogOpen
  const canRestrict = restrictionTarget.trim() !== '' && restrictionReason.trim() !== ''
  const canRecover =
    recoveryTarget !== null &&
    RECOVERY_CONDITIONS.every((condition) => conditions.includes(condition)) &&
    cause.trim() !== '' &&
    verification.trim() !== '' &&
    evidenceRef.trim() !== '' &&
    deployVersion.trim() !== ''
  const canNotice =
    noticing.list.supportContactConfigured &&
    noticeMessage.trim() !== '' &&
    !noticeMessageForbidden &&
    (noticeRecovering
      ? recoveringStillOpen === 0
      : noticeCovers.length > 0 && nextUpdateIso(noticeNext) !== null)

  const canInquire =
    inquiring.detail.status === 'loaded' &&
    inquiryStatus !== null &&
    inquiryReason.trim() !== '' &&
    (inquiryStatus !== 'ANSWERED' || inquiryAnswer.trim() !== '')

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
  // 공지 영역(Story 3.5)은 제한 영역이 보인 뒤, 사건 목록을 한 번이라도 읽었거나 그 읽기만 실패했을 때 그린다.
  const noticeVisible =
    restrictionVisible &&
    (noticing.list.everLoaded || (listLoaded && noticing.list.status === 'failed'))
  // 문의 영역(Story 3.6)도 제한 영역이 보인 뒤, 문의 목록을 한 번이라도 읽었거나 그 읽기만 실패했을 때 그린다.
  const inquiryVisible =
    restrictionVisible &&
    (inquiring.list.everLoaded || (listLoaded && inquiring.list.status === 'failed'))
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
      : lastCommand === 'recovery'
        ? recovering.uncertainOperationId
        : lastCommand === 'notice'
          ? noticing.uncertainOperationId
          : lastCommand === 'inquiry'
            ? inquiring.uncertainOperationId
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
            aria-busy={
              audit.view.loading ||
              exporting.busy ||
              restricting.busy ||
              recovering.busy ||
              noticing.busy ||
              inquiring.busy
            }
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
                lastCommand === 'restriction'
                  ? restrictionSentence(restricting)
                  : lastCommand === 'recovery'
                    ? recoverySentence(recovering)
                    : lastCommand === 'notice'
                      ? noticeSentence(noticing)
                      : lastCommand === 'inquiry'
                        ? inquirySentence(inquiring)
                        : exportSentence(exporting),
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
                            {formatOperatorTime(restricting.completed.effectiveFrom)}
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
                        <RestrictionRow
                          busy={busy}
                          key={restriction.restrictionId}
                          onRecover={(chosen) => {
                            /*
                             * 다른 행을 고르면 입력과 복구 상태 기계를 비운다 — 앞 제한의 증거·거절·결과·멱등 키가
                             * 다음 제한에 실리지 않는다. 고른 행이 사라졌던 경우도 다른 행이다.
                             */
                            if (recoveryTarget?.restrictionId !== chosen.restrictionId) {
                              setConditions([])
                              setCause('')
                              setVerification('')
                              setEvidenceRef('')
                              setDeployVersion('')
                              recovering.reset()
                            }
                            setRecoveryTarget(chosen)
                          }}
                          restriction={restriction}
                        />
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

                  {recoveryTarget === null ? null : (
                    <div aria-labelledby={copy.recovery.id} className={styles.result} role="group">
                      <h3
                        className={styles.sectionHeading}
                        id={copy.recovery.id}
                        ref={recoveryHeadingRef}
                        tabIndex={-1}
                      >
                        {copy.recovery.heading}
                      </h3>
                      <p>{copy.recovery.body}</p>
                      <p>
                        {copy.recovery.selected}: {featureLabel(recoveryTarget.feature)} ·{' '}
                        {scopeLabel(recoveryTarget.scope)} ·{' '}
                        {restrictionTargetLabel(recoveryTarget)} ·{' '}
                        {originLabel(recoveryTarget.origin)}
                      </p>
                      {recoveryTarget.requiresVerification ? (
                        <p className={styles.help}>{copy.recovery.verificationNote}</p>
                      ) : null}
                      <form
                        className={styles.form}
                        onSubmit={(event) => {
                          // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
                          event.preventDefault()
                        }}
                      >
                        <fieldset className={styles.fieldset}>
                          <legend className={styles.label}>{copy.recovery.conditionsLegend}</legend>
                          {RECOVERY_CONDITIONS.map((condition) => (
                            <label className={styles.checkboxLabel} key={condition}>
                              <input
                                checked={conditions.includes(condition)}
                                className={styles.checkbox}
                                onChange={(event) => {
                                  const checked = event.target.checked
                                  setConditions((current) =>
                                    checked
                                      ? RECOVERY_CONDITIONS.filter(
                                          (known) => known === condition || current.includes(known),
                                        )
                                      : current.filter((known) => known !== condition),
                                  )
                                }}
                                type="checkbox"
                              />
                              <span>{RECOVERY_CONDITION_LABELS[condition]}</span>
                            </label>
                          ))}
                        </fieldset>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-recovery-reason">
                            {copy.recovery.reasonLabel}
                          </label>
                          <input
                            aria-describedby="operator-recovery-text-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-recovery-reason"
                            maxLength={REASON_MAX_LENGTH}
                            onChange={(event) => {
                              setCause(event.target.value)
                            }}
                            type="text"
                            value={cause}
                          />
                        </div>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-recovery-verification">
                            {copy.recovery.verificationLabel}
                          </label>
                          <input
                            aria-describedby="operator-recovery-text-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-recovery-verification"
                            maxLength={REASON_MAX_LENGTH}
                            onChange={(event) => {
                              setVerification(event.target.value)
                            }}
                            type="text"
                            value={verification}
                          />
                        </div>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-recovery-evidence">
                            {copy.recovery.evidenceLabel}
                          </label>
                          <input
                            aria-describedby="operator-recovery-text-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-recovery-evidence"
                            maxLength={REASON_MAX_LENGTH}
                            onChange={(event) => {
                              setEvidenceRef(event.target.value)
                            }}
                            type="text"
                            value={evidenceRef}
                          />
                          <p className={styles.help} id="operator-recovery-text-help">
                            {copy.recovery.textHelp}
                          </p>
                        </div>
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-recovery-version">
                            {copy.recovery.deployVersionLabel}
                          </label>
                          <input
                            aria-describedby="operator-recovery-version-help"
                            autoComplete="off"
                            className={styles.input}
                            id="operator-recovery-version"
                            maxLength={DEPLOY_VERSION_MAX_LENGTH}
                            onChange={(event) => {
                              setDeployVersion(event.target.value)
                            }}
                            spellCheck={false}
                            type="text"
                            value={deployVersion}
                          />
                          <p className={styles.help} id="operator-recovery-version-help">
                            {copy.recovery.deployVersionHelp}
                          </p>
                        </div>
                      </form>
                      <button
                        aria-disabled={busy || !canRecover}
                        className={styles.dangerButton}
                        id={copy.recovery.startId}
                        onClick={() => {
                          if (busy || !canRecover) {
                            return
                          }
                          setLastCommand('recovery')
                          recovering.prepare({
                            restrictionId: recoveryTarget.restrictionId,
                            reason: cause.trim(),
                            verification: verification.trim(),
                            evidenceRef: evidenceRef.trim(),
                            deployVersion: deployVersion.trim(),
                            conditions,
                          })
                        }}
                        type="button"
                      >
                        {copy.recovery.start}
                      </button>
                      <button
                        aria-disabled={busy}
                        className={styles.button}
                        onClick={() => {
                          if (!busy) setRecoveryTarget(null)
                        }}
                        type="button"
                      >
                        {copy.recovery.close}
                      </button>

                      {recovering.reauthRequired && lastCommand === 'recovery' ? (
                        <div className={styles.result}>
                          <p className={styles.label}>{copy.recovery.reauthLabel}</p>
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
                    </div>
                  )}

                  {recovering.completed === null ? null : (
                    <div
                      aria-labelledby={copy.recovery.result.id}
                      className={styles.result}
                      role="group"
                    >
                      <h3
                        className={styles.label}
                        id={copy.recovery.result.id}
                        ref={recoveryResultRef}
                        tabIndex={-1}
                      >
                        {copy.recovery.result.heading}
                      </h3>
                      <p>
                        {copy.recovery.result.smoke}:{' '}
                        {smokeStatusLabel(recovering.completed.smoke.status)}
                      </p>
                      <p>
                        {copy.recovery.result.reopenedStages}:{' '}
                        {stagesText(recovering.completed.smoke.reopenedStages)}
                      </p>
                      <p>
                        {copy.recovery.result.stillBlockedStages}:{' '}
                        {stagesText(recovering.completed.smoke.stillBlockedStages)}
                      </p>
                      <p>
                        {copy.recovery.result.operation}: {recovering.completed.operationId}
                      </p>
                      <p>
                        {copy.recovery.result.restriction}: {recovering.completed.restrictionId}
                      </p>
                      <p>
                        {copy.recovery.result.liftedAt}:{' '}
                        {formatOperatorTime(recovering.completed.liftedAt)}
                      </p>
                    </div>
                  )}
                </section>
              ) : null}

              {noticeVisible ? (
                <section aria-labelledby={copy.notice.id} className={styles.section}>
                  <h2 className={styles.sectionHeading} id={copy.notice.id}>
                    {copy.notice.heading}
                  </h2>
                  <p>{copy.notice.body}</p>
                  {noticing.list.everLoaded && !noticing.list.supportContactConfigured ? (
                    <p className={styles.help}>{copy.notice.contactMissing}</p>
                  ) : null}
                  {noticing.list.everLoaded ? (
                    <>
                      <form
                        className={styles.form}
                        onSubmit={(event) => {
                          // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
                          event.preventDefault()
                        }}
                      >
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-notice-incident">
                            {copy.notice.incidentLabel}
                          </label>
                          <select
                            className={styles.input}
                            id="operator-notice-incident"
                            onChange={(event) => {
                              const next = event.target.value
                              setNoticeIncident(next)
                              // 사건을 바꾸면 고친 값을 버리고 그 사건의 기본값(`noticeCovers`)으로 돌아간다.
                              setNoticeCovers([])
                              setCoversEdited(false)
                            }}
                            value={incidentChoice}
                          >
                            <option value={NEW_INCIDENT}>{copy.notice.newIncident}</option>
                            {openIncidents.map((incident) => {
                              const latest = incident.versions.at(-1)
                              return (
                                <option key={incident.incidentId} value={incident.incidentId}>
                                  {incidentOptionLabel(
                                    incident.startedAt,
                                    latest?.version ?? 0,
                                    latest?.status ?? '',
                                    incident.incidentId,
                                  )}
                                </option>
                              )
                            })}
                          </select>
                        </div>
                        {incidentChoice === NEW_INCIDENT ? null : (
                          <div className={styles.field}>
                            <label className={styles.label} htmlFor="operator-notice-status">
                              {copy.notice.statusLabel}
                            </label>
                            <select
                              className={styles.input}
                              id="operator-notice-status"
                              onChange={(event) => {
                                setNoticeStatus(event.target.value as FollowUpNoticeStatus)
                              }}
                              value={noticeStatus}
                            >
                              {FOLLOW_UP_NOTICE_STATUSES.map((status) => (
                                <option key={status} value={status}>
                                  {noticeStatusLabel(status)}
                                </option>
                              ))}
                            </select>
                          </div>
                        )}
                        {noticeRecovering ? (
                          <div className={styles.wideField}>
                            <p className={styles.help}>{copy.notice.recoveredCovers}</p>
                            {recoveringStillOpen === 0 ? null : (
                              <p className={styles.empty}>
                                {copy.notice.recoveredStillOpen(recoveringStillOpen)}
                              </p>
                            )}
                          </div>
                        ) : (
                          <fieldset
                            aria-describedby="operator-notice-covers-help"
                            className={styles.fieldset}
                          >
                            <legend className={styles.label}>{copy.notice.coversLegend}</legend>
                            {restricting.list.items.length === 0 ? (
                              <p className={styles.empty}>{copy.notice.noActive}</p>
                            ) : (
                              restricting.list.items.map((item) => (
                                <label className={styles.checkboxLabel} key={item.restrictionId}>
                                  <input
                                    checked={noticeCovers.includes(item.restrictionId)}
                                    className={styles.checkbox}
                                    onChange={(event) => {
                                      const checked = event.target.checked
                                      // 지금 보이는 값(기본값일 수 있다)에서 고친다.
                                      setNoticeCovers(
                                        checked
                                          ? [...noticeCovers, item.restrictionId]
                                          : noticeCovers.filter((id) => id !== item.restrictionId),
                                      )
                                      setCoversEdited(true)
                                    }}
                                    type="checkbox"
                                  />
                                  <span>
                                    {copy.notice.coverLabel(
                                      featureLabel(item.feature),
                                      scopeLabel(item.scope),
                                      item.scope === 'GLOBAL' ? '' : restrictionTargetLabel(item),
                                    )}
                                  </span>
                                </label>
                              ))
                            )}
                            <p className={styles.help} id="operator-notice-covers-help">
                              {copy.notice.coversHelp}
                            </p>
                          </fieldset>
                        )}
                        <div className={styles.wideField}>
                          <label className={styles.label} htmlFor="operator-notice-message">
                            {copy.notice.messageLabel}
                          </label>
                          <input
                            aria-describedby="operator-notice-message-help"
                            aria-invalid={noticeMessageForbidden}
                            autoComplete="off"
                            className={styles.input}
                            id="operator-notice-message"
                            maxLength={NOTICE_MESSAGE_MAX_LENGTH}
                            onChange={(event) => {
                              setNoticeMessage(event.target.value)
                            }}
                            type="text"
                            value={noticeMessage}
                          />
                          <p className={styles.help} id="operator-notice-message-help">
                            {noticeMessageForbidden
                              ? copy.notice.messageForbidden
                              : copy.notice.messageHelp}
                          </p>
                        </div>
                        {noticeRecovering ? null : (
                          <div className={styles.wideField}>
                            <label className={styles.label} htmlFor="operator-notice-next">
                              {copy.notice.nextUpdateLabel}
                            </label>
                            <input
                              aria-describedby="operator-notice-next-help"
                              className={styles.input}
                              id="operator-notice-next"
                              onChange={(event) => {
                                setNoticeNext(event.target.value)
                              }}
                              type="datetime-local"
                              value={noticeNext}
                            />
                            <p className={styles.help} id="operator-notice-next-help">
                              {copy.notice.nextUpdateHelp}
                            </p>
                          </div>
                        )}
                      </form>
                      <button
                        aria-disabled={busy || !canNotice}
                        className={styles.dangerButton}
                        id={copy.notice.startId}
                        onClick={() => {
                          if (busy || !canNotice) {
                            return
                          }
                          setLastCommand('notice')
                          noticing.prepare({
                            incident: incidentChoice,
                            status: effectiveNoticeStatus,
                            // 복구 공지는 비워 보낸다 — 서버가 직전 버전이 덮던 제한을 잇는다.
                            covers: noticeRecovering ? [] : noticeCovers,
                            message: noticeMessage.trim(),
                            nextUpdateAt: noticeRecovering ? null : nextUpdateIso(noticeNext),
                          })
                        }}
                        type="button"
                      >
                        {copy.notice.start}
                      </button>

                      {noticing.reauthRequired && lastCommand === 'notice' ? (
                        <div className={styles.result}>
                          <p className={styles.label}>{copy.notice.reauthLabel}</p>
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

                      {noticing.completed === null ? null : (
                        <div
                          aria-labelledby={copy.notice.result.id}
                          className={styles.result}
                          role="group"
                        >
                          <h3
                            className={styles.label}
                            id={copy.notice.result.id}
                            ref={noticeResultRef}
                            tabIndex={-1}
                          >
                            {copy.notice.result.heading}
                          </h3>
                          <p>
                            {copy.notice.result.version}: v{String(noticing.completed.version)}{' '}
                            {noticeStatusLabel(noticing.completed.status)}
                          </p>
                          <p>
                            {copy.notice.result.incident}: {noticing.completed.incidentId}
                          </p>
                          <p>
                            {copy.notice.result.operation}: {noticing.completed.operationId}
                          </p>
                          <p>
                            {copy.notice.result.publishedAt}:{' '}
                            {noticeTime(noticing.completed.publishedAt)}
                          </p>
                        </div>
                      )}
                    </>
                  ) : null}

                  <h3 className={styles.label} id={copy.notice.history.id}>
                    {copy.notice.history.heading}
                  </h3>
                  {noticing.list.status === 'failed' ? (
                    <p className={styles.empty}>{copy.notice.history.failed}</p>
                  ) : null}
                  {noticing.list.items.length === 0 ? (
                    noticing.list.status === 'loaded' ? (
                      <p className={styles.empty}>{copy.notice.history.empty}</p>
                    ) : null
                  ) : (
                    <ol aria-labelledby={copy.notice.history.id} className={styles.list}>
                      {noticing.list.items.map((incident) => (
                        <IncidentRow incident={incident} key={incident.incidentId} />
                      ))}
                    </ol>
                  )}
                  <button
                    aria-disabled={noticing.list.loading}
                    className={styles.button}
                    onClick={() => {
                      if (!noticing.list.loading) noticing.reloadList()
                    }}
                    type="button"
                  >
                    {copy.notice.history.reload}
                  </button>
                </section>
              ) : null}

              {inquiryVisible ? (
                <section aria-labelledby={copy.inquiry.id} className={styles.section}>
                  <h2 className={styles.sectionHeading} id={copy.inquiry.id}>
                    {copy.inquiry.heading}
                  </h2>
                  <p>{copy.inquiry.body}</p>

                  <form
                    className={styles.form}
                    onSubmit={(event) => {
                      // reference로 바로 연다 — 목록 상한 밖의 문의도 열 수 있다.
                      event.preventDefault()
                      const reference = inquiryLookup.trim().toUpperCase()
                      if (reference === '' || busy) return
                      if (reference !== selectedInquiry) {
                        setInquiryAnswer('')
                        setInquiryReason('')
                        setInquiryStatus(null)
                      }
                      inquiring.select(reference)
                    }}
                  >
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="operator-inquiry-filter">
                        {copy.inquiry.filterLabel}
                      </label>
                      <select
                        className={styles.input}
                        id="operator-inquiry-filter"
                        onChange={(event) => {
                          inquiring.filterList(
                            event.target.value === '' ? null : event.target.value,
                          )
                        }}
                        value={inquiring.listFilter ?? ''}
                      >
                        <option value="">{copy.inquiry.filterAll}</option>
                        {INQUIRY_FILTER_STATUSES.map((status) => (
                          <option key={status} value={status}>
                            {inquiryStatusText(status)}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className={styles.field}>
                      <label className={styles.label} htmlFor="operator-inquiry-lookup">
                        {copy.inquiry.lookupLabel}
                      </label>
                      <input
                        autoComplete="off"
                        className={styles.input}
                        id="operator-inquiry-lookup"
                        onChange={(event) => {
                          setInquiryLookup(event.target.value)
                        }}
                        spellCheck={false}
                        type="text"
                        value={inquiryLookup}
                      />
                    </div>
                    <button className={styles.button} type="submit">
                      {copy.inquiry.lookupButton}
                    </button>
                  </form>

                  <h3 className={styles.label} id="operator-inquiry-list">
                    {copy.inquiry.listHeading}
                  </h3>
                  {inquiring.list.status === 'failed' ? (
                    <p className={styles.empty}>{copy.inquiry.failed}</p>
                  ) : null}
                  {inquiring.list.items.length === 0 ? (
                    inquiring.list.status === 'loaded' ? (
                      <p className={styles.empty}>{copy.inquiry.empty}</p>
                    ) : null
                  ) : (
                    <ol aria-labelledby="operator-inquiry-list" className={styles.list}>
                      {inquiring.list.items.map((inquiry) => (
                        <InquiryRow
                          disabled={busy}
                          inquiry={inquiry}
                          key={inquiry.reference}
                          onSelect={() => {
                            // 다른 문의를 고르면 그 문의의 입력(상태 선택 포함)으로 새로 시작한다.
                            if (inquiry.reference !== selectedInquiry) {
                              setInquiryAnswer('')
                              setInquiryReason('')
                              setInquiryStatus(null)
                            }
                            inquiring.select(inquiry.reference)
                          }}
                        />
                      ))}
                    </ol>
                  )}
                  <button
                    aria-disabled={inquiring.list.loading}
                    className={styles.button}
                    onClick={() => {
                      if (!inquiring.list.loading) inquiring.reloadList()
                    }}
                    type="button"
                  >
                    {copy.inquiry.reload}
                  </button>

                  {selectedInquiry === null ? null : (
                    <div
                      aria-labelledby={copy.inquiry.detail.id}
                      className={styles.result}
                      role="group"
                    >
                      <h3
                        className={styles.label}
                        id={copy.inquiry.detail.id}
                        ref={inquiryDetailRef}
                        tabIndex={-1}
                      >
                        {copy.inquiry.detail.heading} {selectedInquiry}
                      </h3>
                      {inquiring.detail.status === 'loading' ? (
                        <p className={styles.empty}>{copy.inquiry.detail.loading}</p>
                      ) : inquiring.detail.status === 'notFound' ? (
                        <p className={styles.empty}>{copy.inquiry.detail.notFound}</p>
                      ) : inquiring.detail.status === 'failed' ? (
                        <p className={styles.empty}>{copy.inquiry.detail.failed}</p>
                      ) : inquiring.detail.status === 'loaded' ? (
                        <>
                          <p className={styles.term}>{copy.inquiry.columns.status}</p>
                          <p className={styles.value}>
                            {inquiryStatusText(inquiring.detail.value.summary.status)}
                          </p>
                          <p className={styles.term}>{copy.inquiry.columns.title}</p>
                          <p className={styles.value}>{inquiring.detail.value.summary.title}</p>
                          <p className={styles.term}>{copy.inquiry.detail.description}</p>
                          <p className={`${styles.value ?? ''} ${styles.preserved ?? ''}`}>
                            {inquiring.detail.value.description}
                          </p>
                          {inquiring.detail.value.replyEmail === null ? null : (
                            <>
                              {/* 원문은 상세에만 있다 — 운영자가 자기 메일로 회신하는 용도다. */}
                              <p className={styles.term}>{copy.inquiry.detail.replyEmail}</p>
                              <p className={styles.value}>{inquiring.detail.value.replyEmail}</p>
                            </>
                          )}
                          {inquiring.detail.value.answer === null ? null : (
                            <>
                              <p className={styles.term}>{copy.inquiry.detail.answer}</p>
                              <p className={`${styles.value ?? ''} ${styles.preserved ?? ''}`}>
                                {inquiring.detail.value.answer}
                              </p>
                            </>
                          )}

                          {inquiryStatus === null ? (
                            <p className={styles.empty}>{copy.inquiry.noNextStatus}</p>
                          ) : (
                            <>
                              <form
                                className={styles.form}
                                onSubmit={(event) => {
                                  // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
                                  event.preventDefault()
                                }}
                              >
                                <div className={styles.field}>
                                  <label className={styles.label} htmlFor="operator-inquiry-status">
                                    {copy.inquiry.statusLabel}
                                  </label>
                                  <select
                                    className={styles.input}
                                    id="operator-inquiry-status"
                                    onChange={(event) => {
                                      setInquiryStatus(event.target.value as InquiryNextStatus)
                                    }}
                                    value={inquiryStatus}
                                  >
                                    {nextInquiryStatuses.map((status) => (
                                      <option key={status} value={status}>
                                        {inquiryStatusText(status)}
                                      </option>
                                    ))}
                                  </select>
                                </div>
                                {inquiryStatus === 'ANSWERED' ? (
                                  <div className={styles.wideField}>
                                    <label
                                      className={styles.label}
                                      htmlFor="operator-inquiry-answer"
                                    >
                                      {copy.inquiry.answerLabel}
                                    </label>
                                    <textarea
                                      aria-describedby="operator-inquiry-answer-help"
                                      className={styles.textarea}
                                      id="operator-inquiry-answer"
                                      maxLength={INQUIRY_ANSWER_MAX_LENGTH}
                                      onChange={(event) => {
                                        setInquiryAnswer(event.target.value)
                                      }}
                                      rows={5}
                                      value={inquiryAnswer}
                                    />
                                    <p className={styles.help} id="operator-inquiry-answer-help">
                                      {copy.inquiry.answerHelp}
                                    </p>
                                  </div>
                                ) : null}
                                <div className={styles.wideField}>
                                  <label className={styles.label} htmlFor="operator-inquiry-reason">
                                    {copy.inquiry.reasonLabel}
                                  </label>
                                  <input
                                    aria-describedby="operator-inquiry-reason-help"
                                    autoComplete="off"
                                    className={styles.input}
                                    id="operator-inquiry-reason"
                                    maxLength={REASON_MAX_LENGTH}
                                    onChange={(event) => {
                                      setInquiryReason(event.target.value)
                                    }}
                                    type="text"
                                    value={inquiryReason}
                                  />
                                  <p className={styles.help} id="operator-inquiry-reason-help">
                                    {copy.inquiry.reasonHelp}
                                  </p>
                                </div>
                              </form>
                              <button
                                aria-disabled={busy || !canInquire}
                                className={styles.dangerButton}
                                id={copy.inquiry.startId}
                                onClick={() => {
                                  if (busy || !canInquire) {
                                    return
                                  }
                                  setLastCommand('inquiry')
                                  inquiring.prepare({
                                    reference: selectedInquiry,
                                    status: inquiryStatus,
                                    reason: inquiryReason.trim(),
                                    answer:
                                      inquiryStatus === 'ANSWERED' ? inquiryAnswer.trim() : null,
                                  })
                                }}
                                type="button"
                              >
                                {copy.inquiry.start}
                              </button>
                            </>
                          )}
                        </>
                      ) : null}
                    </div>
                  )}

                  {inquiring.reauthRequired && lastCommand === 'inquiry' ? (
                    <div className={styles.result}>
                      <p className={styles.label}>{copy.inquiry.reauthLabel}</p>
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

                  {inquiring.completed === null ? null : (
                    <div
                      aria-labelledby={copy.inquiry.result.id}
                      className={styles.result}
                      role="group"
                    >
                      <h3
                        className={styles.label}
                        id={copy.inquiry.result.id}
                        ref={inquiryResultRef}
                        tabIndex={-1}
                      >
                        {copy.inquiry.result.heading}
                      </h3>
                      <p>
                        {copy.inquiry.result.status}:{' '}
                        {inquiryStatusText(inquiring.completed.status)}
                      </p>
                      <p>
                        {copy.inquiry.result.operation}: {inquiring.completed.operationId}
                      </p>
                      <p>
                        {copy.inquiry.result.transitionedAt}:{' '}
                        {noticeTime(inquiring.completed.transitionedAt)}
                      </p>
                    </div>
                  )}

                  <div
                    aria-labelledby={copy.inquiry.metrics.id}
                    className={styles.result}
                    role="group"
                  >
                    <h3 className={styles.label} id={copy.inquiry.metrics.id}>
                      {copy.inquiry.metrics.heading}
                    </h3>
                    <p className={styles.help}>{copy.inquiry.metrics.body}</p>
                    <form
                      className={styles.form}
                      onSubmit={(event) => {
                        event.preventDefault()
                        inquiring.loadMetrics(metricsFrom, metricsTo)
                      }}
                    >
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="operator-inquiry-metrics-from">
                          {copy.inquiry.metrics.fromLabel}
                        </label>
                        <input
                          className={styles.input}
                          id="operator-inquiry-metrics-from"
                          onChange={(event) => {
                            setMetricsFrom(event.target.value)
                          }}
                          type="date"
                          value={metricsFrom}
                        />
                      </div>
                      <div className={styles.field}>
                        <label className={styles.label} htmlFor="operator-inquiry-metrics-to">
                          {copy.inquiry.metrics.toLabel}
                        </label>
                        <input
                          className={styles.input}
                          id="operator-inquiry-metrics-to"
                          onChange={(event) => {
                            setMetricsTo(event.target.value)
                          }}
                          type="date"
                          value={metricsTo}
                        />
                      </div>
                      <button className={styles.button} type="submit">
                        {copy.inquiry.metrics.run}
                      </button>
                    </form>
                    {inquiring.metrics.status === 'loading' ? (
                      <p className={styles.empty}>{copy.inquiry.metrics.loading}</p>
                    ) : inquiring.metrics.status === 'failed' ? (
                      <p className={styles.empty}>{copy.inquiry.metrics.failed}</p>
                    ) : inquiring.metrics.status === 'loaded' ? (
                      <>
                        {inquiring.metrics.value.purgeHorizon === null ? null : (
                          <p className={styles.help}>
                            {copy.inquiry.metrics.purgeHorizon(
                              noticeTime(inquiring.metrics.value.purgeHorizon),
                            )}
                          </p>
                        )}
                        <dl className={styles.metrics}>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.due}</dt>
                            <dd className={styles.value}>{String(inquiring.metrics.value.due)}</dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.met}</dt>
                            <dd className={styles.value}>
                              {String(inquiring.metrics.value.metTarget)}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.rate}</dt>
                            <dd className={styles.value}>
                              {inquiring.metrics.value.ratePercent === null
                                ? copy.inquiry.metrics.none
                                : `${inquiring.metrics.value.ratePercent.toFixed(1)}%`}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.pending}</dt>
                            <dd className={styles.value}>
                              {String(inquiring.metrics.value.calendarPending)}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.measured}</dt>
                            <dd className={styles.value}>
                              {String(inquiring.metrics.value.measured)}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.median}</dt>
                            <dd className={styles.value}>
                              {businessMinutesText(inquiring.metrics.value.medianBusinessMinutes)}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.total}</dt>
                            <dd className={styles.value}>
                              {businessMinutesText(inquiring.metrics.value.totalBusinessMinutes)}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.calendar}</dt>
                            <dd className={styles.value}>
                              {inquiring.metrics.value.calendarVersion}
                            </dd>
                          </div>
                          <div>
                            <dt className={styles.term}>{copy.inquiry.metrics.asOf}</dt>
                            <dd className={styles.value}>
                              {noticeTime(inquiring.metrics.value.asOf)}
                            </dd>
                          </div>
                        </dl>
                      </>
                    ) : null}
                  </div>
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
      {noticeDialogOpen ? (
        <OperatorNoticeDialog
          code={noticing.code}
          confirming={noticing.phase === 'confirming'}
          onCancel={noticing.cancel}
          onConfirm={noticing.confirm}
          prepared={noticing.prepared}
          uncertain={noticing.uncertain}
        />
      ) : null}
      {inquiryDialogOpen ? (
        <OperatorInquiryDialog
          answer={inquiring.answer}
          code={inquiring.code}
          confirming={inquiring.phase === 'confirming'}
          onCancel={inquiring.cancel}
          onConfirm={inquiring.confirm}
          prepared={inquiring.prepared}
          uncertain={inquiring.uncertain}
        />
      ) : null}
      {recoveryDialogOpen ? (
        <OperatorRecoveryDialog
          code={recovering.code}
          confirming={recovering.phase === 'confirming'}
          onCancel={recovering.cancel}
          onConfirm={recovering.confirm}
          prepared={recovering.prepared}
          targetLabel={
            recoveryTarget === null
              ? recovering.prepared.target
              : restrictionTargetLabel(recoveryTarget)
          }
          uncertain={recovering.uncertain}
        />
      ) : null}
    </>
  )
}

/** 단계 code 목록의 화면 문장. 비었으면 `없음`이다. */
function stagesText(stages: readonly string[]): string {
  return stages.length === 0 ? copy.recovery.result.none : stages.map(stageLabel).join(', ')
}

/** live region의 문장. 조작의 진행이 조회 상태보다 앞선다. */
function statusText(
  ready: boolean,
  gate: SessionGate,
  signingOut: boolean,
  reauthenticating: boolean,
  sentence: string | null,
  lookingUp: boolean,
  audit: ReturnType<typeof useOperatorAudit>['view'],
  auditVisible: boolean,
  list: ReturnType<typeof useOperatorRestriction>['list'],
  loadFailed: boolean,
): string {
  if (!ready || signingOut || reauthenticating) {
    return liveRegionMessage(gate, signingOut, reauthenticating)
  }
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
function exportSentence(exporting: ReturnType<typeof useOperatorExport>): string | null {
  switch (exporting.phase) {
    case 'preparing':
      return copy.status.preparing
    case 'armed':
      return exporting.uncertain ? copy.export.uncertain : copy.status.prepared
    case 'confirming':
      return copy.status.confirming
    case 'completed':
      return copy.status.completed(exporting.completed?.rowCount ?? 0)
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
  restricting: ReturnType<typeof useOperatorRestriction>,
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
      return done === null
        ? copy.status.restrictionConfirming
        : copy.status.restrictionCompleted(featureLabel(done.feature), scopeLabel(done.scope))
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

/** 서비스 공지 발행의 진행·결과 문장(Story 3.5). 말할 것이 없으면 `null`. */
function noticeSentence(noticing: ReturnType<typeof useOperatorNotice>): string | null {
  switch (noticing.phase) {
    case 'preparing':
      return copy.status.noticePreparing
    case 'armed':
      return noticing.uncertain ? copy.notice.uncertain : copy.status.noticePrepared
    case 'confirming':
      return copy.status.noticeConfirming
    case 'completed': {
      const done = noticing.completed
      return done === null
        ? copy.status.noticeConfirming
        : copy.status.noticeCompleted(done.version, noticeStatusLabel(done.status))
    }
    case 'cancelled':
      return noticing.uncertainOperationId === null
        ? copy.status.noticeCancelled
        : copy.notice.uncertainClosed
    case 'refused':
      return operatorRefusalMessage(
        noticing.code,
        PUBLISH_NOTICE_COMMAND,
        noticing.requestedStatus ?? undefined,
      )
    case 'idle':
      return null
  }
}

/** 문의 처리의 진행·결과 문장(Story 3.6). 말할 것이 없으면 `null`. */
function inquirySentence(inquiring: ReturnType<typeof useOperatorInquiry>): string | null {
  switch (inquiring.phase) {
    case 'preparing':
      return copy.status.inquiryPreparing
    case 'armed':
      return inquiring.uncertain ? copy.inquiry.uncertain : copy.status.inquiryPrepared
    case 'confirming':
      return copy.status.inquiryConfirming
    case 'completed': {
      const done = inquiring.completed
      return done === null
        ? copy.status.inquiryConfirming
        : copy.status.inquiryCompleted(inquiryStatusText(done.status))
    }
    case 'cancelled':
      return inquiring.uncertainOperationId === null
        ? copy.status.inquiryCancelled
        : copy.inquiry.uncertainClosed
    case 'refused':
      return operatorRefusalMessage(inquiring.code, UPDATE_INQUIRY_COMMAND)
    case 'idle':
      return null
  }
}

/** 제한 복구의 진행·결과 문장(Story 3.4). 말할 것이 없으면 `null`. */
function recoverySentence(recovering: ReturnType<typeof useOperatorRecovery>): string | null {
  switch (recovering.phase) {
    case 'preparing':
      return copy.status.recoveryPreparing
    case 'armed':
      return recovering.uncertain ? copy.recovery.uncertain : copy.status.recoveryPrepared
    case 'confirming':
      return copy.status.recoveryConfirming
    case 'completed': {
      const done = recovering.completed
      if (done === null) return copy.status.recoveryConfirming
      const feature = featureLabel(done.feature)
      const scope = scopeLabel(done.scope)
      if (done.smoke.status === 'PASSED') return copy.status.recoveryCompleted(feature, scope)
      if (done.smoke.status === 'REVERTED') return copy.status.recoveryReverted(feature, scope)
      // 결말이 원장에 아직 없다(PENDING) — 통과로도 되돌림으로도 말하지 않는다.
      return copy.status.recoveryPending(feature, scope)
    }
    case 'cancelled':
      return recovering.uncertainOperationId === null
        ? copy.status.recoveryCancelled
        : copy.recovery.uncertainClosed
    case 'refused':
      return operatorRefusalMessage(recovering.code)
    case 'idle':
      return null
  }
}
