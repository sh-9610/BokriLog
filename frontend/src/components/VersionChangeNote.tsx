import type { ReactElement, ReactNode } from 'react'

import type { VersionChange } from '../session/useDashboard'

import { copyFor } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { Time } from './EvidenceParts'
import { RangeList } from './VerificationRanges'
import styles from './VersionChangeNote.module.css'

const copy = planVerificationContent.change

/** The marker's root. The screen's accessibility checks name it. */
export const VERSION_CHANGE_NOTE_ID = 'plan-version-change'

const LABEL_ID = 'plan-version-change-label'

/**
 * The job and reason that made the version, and the ranges it read again and reused. A sync reads no planned
 * range again and reuses none — it collects from its own start, which the calculation evidence shows — so a version
 * a sync made has no range lines: "none" there would contradict that collection.
 */
function Details({ change }: { readonly change: VersionChange }): ReactElement {
  const verification = planVerificationContent
  const ranges = change.purpose !== 'SYNC'
  return (
    <dl className={styles.facts}>
      {change.purpose === null ? null : (
        <div>
          <dt>{copy.purposeLabel}</dt>
          <dd>{copyFor(verification.purposes, change.purpose, verification.unknownPurpose)}</dd>
        </div>
      )}
      {change.reason === null ? null : (
        <div>
          <dt>{copy.reasonLabel}</dt>
          <dd>{copyFor(verification.jobReasons, change.reason, verification.unknownJobReason)}</dd>
        </div>
      )}
      {ranges ? (
        <>
          <div>
            <dt>{copy.targetsLabel}</dt>
            <dd>
              <RangeList
                className={styles.ranges}
                empty={verification.none}
                ranges={change.targets}
              />
            </dd>
          </div>
          <div>
            <dt>{copy.reusedLabel}</dt>
            <dd>
              <RangeList
                className={styles.ranges}
                empty={verification.none}
                ranges={change.reused}
              />
            </dd>
          </div>
        </>
      ) : null}
    </dl>
  )
}

/** One named group: its first line is its name. */
function Note({
  label,
  children,
}: {
  readonly label: ReactNode
  readonly children: ReactNode
}): ReactElement {
  return (
    <div
      aria-labelledby={LABEL_ID}
      className={styles.note}
      id={VERSION_CHANGE_NOTE_ID}
      role="group"
    >
      <p className={styles.label} id={LABEL_ID}>
        {label}
      </p>
      {children}
    </div>
  )
}

/**
 * The result marker of the shown official version (Story 2.12), right under the dashboard's introduction.
 *
 * A version that differs from the previous official one by late changes says how many of each — added late,
 * value changed, removed, leaving out the kinds with none, so a value change is never called a late deposit — and
 * where the impact starts ("입출금 변경 반영: 값이 바뀐 입출금 1건, 영향 시작 …"), and where to read the change (the
 * calculation evidence's change part). A sync can make such a version too. A verification that found no change
 * says `필요 구간 검증 완료` with the ranges it read again, the evidence it reused and when. Any other version shows
 * nothing. It never says the whole history was read again, and never judges the values. A named group (its first
 * line is its name), not a live region: the screen's status region says what a verification did, once.
 */
export function VersionChangeNote({
  change,
}: {
  readonly change: VersionChange
}): ReactElement | null {
  if (change.kind === 'CORRECTED') {
    return (
      <Note
        label={
          <>
            {copy.corrected(change)}
            {change.firstAffectedAt === null ? null : (
              <>
                , {copy.impactStart} <Time value={change.firstAffectedAt} />
              </>
            )}
          </>
        }
      >
        <p className={styles.help}>{copy.correctedHelp}</p>
        <Details change={change} />
      </Note>
    )
  }
  if (change.kind === 'VERIFIED_NO_CHANGE') {
    return (
      <Note label={copy.verified}>
        <p className={styles.help}>{copy.verifiedHelp}</p>
        <Details change={change} />
        {change.verifiedAt === null ? null : (
          <p className={styles.help}>
            {copy.verifiedAtLabel}: <Time value={change.verifiedAt} />
          </p>
        )}
      </Note>
    )
  }
  return null
}
