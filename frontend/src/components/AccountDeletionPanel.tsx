import type { ReactElement } from 'react'

import type { AccountDeletionControl } from '../session/useAccountDeletion'

import {
  ACCOUNT_DELETION_POLICY_VERSION,
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionTargetSentence,
} from '../content/accountDeletion'
import styles from './AccountDeletionPanel.module.css'

const copy = accountDeletionContent

interface AccountDeletionPanelProps {
  readonly deletion: AccountDeletionControl
  /** 화면의 다른 조작이 도는 중이다. 이 영역의 조작을 막는다. */
  readonly blocked: boolean
  /** 이 영역의 조작이 화면의 live region을 소유하게 한다. */
  readonly onAction: () => void
}

/**
 * 설정 화면의 `계정 삭제` 영역(Story 5.1). 화면의 마지막 영역이다.
 *
 * 서버가 준 삭제 정책을 서로 구분해 보여 준다 — 요청 즉시 일어나는 차단, 삭제하는 운영 데이터(대상 code를 닫힌 맵으로 문장에 잇는다.
 * 모르는 code는 그리지 않고 실행도 열지 않는다), 백업 사본의 파기 기한, 법정 분리 보존(지금 없음), Bybit 연결 해제와의 차이. 그 아래가
 * `계정 삭제 요청` 버튼이다 — 누르면 준비 요청이 허가를 무장한 뒤 확인 대화상자가 열린다(준비는 아무것도 바꾸지 않는다).
 *
 * 실행을 열지 않는 경우가 둘이다 — production(정책 승인 전, `available:false`)과 서버 정책이 이 화면의 설명과 다를 때(정책 버전이
 * 다르거나 이 화면이 문장을 갖지 못한 대상 code가 있다). 법정 분리
 * 보존 항목이 있으면(G-5 승인 뒤) 항목마다 필드·근거·목적·기간을 보인다. 삭제하지 않고 남기는 것도 따로 보인다. 이 영역은 live region을 더하지 않는다 — 결과는 화면의 region 하나가 말한다.
 */
export function AccountDeletionPanel({
  blocked,
  deletion,
  onAction,
}: AccountDeletionPanelProps): ReactElement {
  const view = deletion.view
  const locked = blocked || deletion.pending
  // 대상 code마다의 문장. 모르는 code는 `null`이다.
  const targetSentences =
    view.kind === 'loaded'
      ? view.state.policy.targets.map((code) => accountDeletionTargetSentence(code))
      : []
  /*
   * 이 화면의 설명이 서버 정책과 맞지 않는다 — 정책 버전이 다르거나, 이 화면이 문장을 갖지 못한 대상 code가 있다. 어느 쪽이든 사용자가
   * 삭제될 것을 다 읽을 수 없으므로 실행을 열지 않는다(정책 버전 불일치와 같은 처리).
   */
  const outdated =
    view.kind === 'loaded' &&
    (view.state.policy.version !== ACCOUNT_DELETION_POLICY_VERSION ||
      targetSentences.some((sentence) => sentence === null))

  return (
    <section aria-labelledby={copy.id} className={styles.section}>
      <h2 className={styles.heading} id={copy.id}>
        {copy.heading}
      </h2>
      <p>{copy.body}</p>

      {view.kind === 'checking' ? (
        <p className={styles.muted}>{copy.checking}</p>
      ) : view.kind === 'unavailable' ? (
        <>
          <p className={styles.muted}>{copy.unavailable}</p>
          <button
            aria-disabled={blocked}
            className={styles.secondary}
            onClick={() => {
              if (!blocked) deletion.reload()
            }}
            type="button"
          >
            {copy.retry}
          </button>
        </>
      ) : (
        <>
          <div className={styles.block}>
            <h3 className={styles.subheading}>{copy.immediateHeading}</h3>
            <p className={styles.emphasis}>{copy.immediate}</p>
          </div>

          <div className={styles.block}>
            <h3 className={styles.subheading} id={copy.targetsHeadingId}>
              {copy.targetsHeading}
            </h3>
            <ul aria-labelledby={copy.targetsHeadingId} className={styles.list}>
              {targetSentences.map((sentence, index) =>
                sentence === null ? null : <li key={`${String(index)}-${sentence}`}>{sentence}</li>,
              )}
            </ul>
          </div>

          <div className={styles.block}>
            <h3 className={styles.subheading}>{copy.backupHeading}</h3>
            <p>{copy.backup(view.state.policy.backupDestroyDays)}</p>
          </div>

          <div className={styles.block}>
            <h3 className={styles.subheading}>{copy.legalHeading}</h3>
            {view.state.policy.legalRetention.length === 0 ? (
              <p>{copy.legalNone}</p>
            ) : (
              // 승인된 법정 분리 보존 항목(G-5 뒤). 항목마다 필드·근거·목적·기간을 그대로 보인다.
              <ul className={styles.list}>
                {view.state.policy.legalRetention.map((item, index) => (
                  // 같은 필드 이름의 항목이 둘이어도 겹치지 않는 key다.
                  <li key={`${String(index)}-${item.field}`}>
                    {copy.legalFieldLabel}: {item.field} · {copy.legalBasisLabel}: {item.basis} ·{' '}
                    {copy.legalPurposeLabel}: {item.purpose} · {copy.legalPeriodLabel}:{' '}
                    {item.period}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className={styles.block}>
            <h3 className={styles.subheading} id={copy.keptHeadingId}>
              {copy.keptHeading}
            </h3>
            <ul aria-labelledby={copy.keptHeadingId} className={styles.list}>
              {copy.kept.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>

          <div className={styles.block}>
            <h3 className={styles.subheading}>{copy.differenceHeading}</h3>
            <p>{copy.difference}</p>
          </div>

          <p className={styles.muted}>
            {copy.policyVersionLabel}: {view.state.policy.version}
          </p>

          {!view.state.available ? (
            <p className={styles.emphasis}>{copy.closed}</p>
          ) : view.state.request !== null ? (
            <p className={styles.emphasis}>
              {accountDeletionRefusalMessage('ACCOUNT_DELETION_IN_PROGRESS')}
            </p>
          ) : outdated ? (
            <p className={styles.emphasis}>{copy.outdated}</p>
          ) : (
            <button
              /*
               * `aria-disabled`이지 `disabled`가 아니다 — 방금 `Enter`로 누른 키보드·스크린리더 사용자가 포커스를 잃지 않는다. 실제
               * 중복 제출 차단은 훅의 가드가 한다.
               */
              aria-disabled={locked}
              className={styles.destructive}
              id={copy.startId}
              onClick={() => {
                if (locked) return
                onAction()
                deletion.start()
              }}
              type="button"
            >
              {copy.start}
            </button>
          )}
        </>
      )}
    </section>
  )
}
