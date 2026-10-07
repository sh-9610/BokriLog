import { useEffect, useState } from 'react'

import { nextPlanContent as copy } from '../content/nextPlan'
import { formatSeoulTime } from '../format/decimal'
import { isInstant } from '../session/instant'
import { record } from '../session/useDashboard'
import styles from './PlanConditionPanel.module.css'

interface Summary {
  readonly planId: string
  readonly status: string
  readonly startAt: string
  readonly endAt: string
}
export function PlanHistoryPanel({
  selected,
  onSelect,
}: {
  readonly selected: string | null
  readonly onSelect: (planId: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<readonly Summary[]>([])
  const [failed, setFailed] = useState(false)
  const [revision, setRevision] = useState(0)
  // The list is a read-only request; its abort discards responses after unmount or reload.
  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    void fetch('/api/dashboard/history', {
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    })
      .then(async (response) => {
        if (response.status === 401 || response.status === 403) {
          setRows([])
          onSelect(null)
          return
        }
        if (!response.ok) throw new Error('history')
        const value: unknown = await response.json()
        if (
          !Array.isArray(value) ||
          !value.every((item) => {
            const row = record(item)
            return (
              row !== null &&
              typeof row.planId === 'string' &&
              (row.status === 'ENDED_EARLY' || row.status === 'COMPLETED') &&
              isInstant(row.startAt) &&
              isInstant(row.endAt)
            )
          })
        )
          throw new Error('format')
        if (!controller.signal.aborted) {
          setRows(value as Summary[])
          setFailed(false)
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => {
      controller.abort()
    }
  }, [revision, onSelect, open])
  return (
    <section aria-labelledby="plan-history-heading" className={styles.panel}>
      <h2 id="plan-history-heading">{copy.history}</h2>
      <p>{copy.historyIntro}</p>
      {open ? null : (
        <button
          className={styles.button}
          type="button"
          onClick={() => {
            setOpen(true)
          }}
        >
          {copy.historyOpen}
        </button>
      )}
      {failed ? <p>{copy.historyFailed}</p> : null}
      {selected === null ? null : (
        <button
          className={styles.button}
          type="button"
          onClick={() => {
            onSelect(null)
          }}
        >
          {copy.current}
        </button>
      )}
      <ul>
        {rows.map((row) => (
          <li key={row.planId}>
            {row.status === 'COMPLETED' ? '완료' : '중도 종료'} /{' '}
            {formatSeoulTime(row.startAt, copy.historyFailed)} ~{' '}
            {formatSeoulTime(row.endAt, copy.historyFailed)}{' '}
            <button
              className={styles.button}
              type="button"
              aria-pressed={selected === row.planId}
              onClick={() => {
                onSelect(row.planId)
              }}
            >
              {copy.select}
            </button>
          </li>
        ))}
      </ul>
      {open ? (
        <button
          className={styles.button}
          type="button"
          onClick={() => {
            setRevision((value) => value + 1)
          }}
        >
          {copy.historyReload}
        </button>
      ) : null}
    </section>
  )
}
