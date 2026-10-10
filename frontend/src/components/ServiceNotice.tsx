import type { ReactElement } from 'react'

import type { ServiceNoticeItem, ServiceNoticeView } from '../session/useServiceNotices'

import {
  noticeFeaturesText,
  noticeStateLabel,
  noticeTimeText,
  serviceNoticeContent,
} from '../content/serviceNotice'
import styles from './ServiceNotice.module.css'

const copy = serviceNoticeContent

/** 항목 하나의 사실 목록. 시작 시각·멈춘 기능·계속 쓸 수 있는 기능·다음 안내 또는 복구 시각·문의 순서다. */
function Facts({ item }: { readonly item: ServiceNoticeItem }): ReactElement {
  return (
    <dl className={styles.facts}>
      {item.kind === 'NOTICE' ? (
        <div>
          <dt>{copy.startedAt}</dt>
          <dd>{noticeTimeText(item.startedAt)}</dd>
        </div>
      ) : null}
      <div>
        <dt>{copy.affected}</dt>
        <dd>{noticeFeaturesText(item.affectedFeatures)}</dd>
      </div>
      <div>
        <dt>{copy.continuing}</dt>
        <dd>{noticeFeaturesText(item.continuingFeatures)}</dd>
      </div>
      {item.kind === 'NOTICE' && item.recoveredAt !== null ? (
        <div>
          <dt>{copy.recoveredAt}</dt>
          <dd>{noticeTimeText(item.recoveredAt)}</dd>
        </div>
      ) : null}
      {item.kind === 'NOTICE' && item.nextUpdateAt !== null ? (
        <div>
          <dt>{copy.nextUpdateAt}</dt>
          {/* 예정 시각이 지났는데 새 버전이 없다 — 지난 시각을 다음 안내처럼 말하지 않는다. */}
          <dd>
            {item.overdue
              ? copy.overdue(noticeTimeText(item.nextUpdateAt))
              : noticeTimeText(item.nextUpdateAt)}
          </dd>
        </div>
      ) : null}
      {/* 설정된 연락처만 진짜 경로다. 없으면 링크를 두지 않는다 — 허위·빈 지원 링크가 없다. */}
      {item.supportContact === null ? null : (
        <div>
          <dt>{copy.support}</dt>
          <dd>
            <a className={styles.link} href={`mailto:${item.supportContact}`}>
              {item.supportContact}
            </a>
          </dd>
        </div>
      )}
    </dl>
  )
}

function Item({ item }: { readonly item: ServiceNoticeItem }): ReactElement {
  const state = item.kind === 'NOTICE' ? item.status : item.kind
  return (
    <li className={styles.item}>
      {/* 상태 표지는 문장이다. 색만으로 상태를 가르지 않는다. */}
      <p className={styles.state}>
        {copy.stateLabel}: {noticeStateLabel(state)}
      </p>
      <p className={styles.message}>
        {item.kind === 'NOTICE' ? item.message : copy.restrictedBody}
      </p>
      <Facts item={item} />
    </li>
  )
}

/**
 * 서비스 공지 영역(Story 3.5 AC-4·AC-5). 대시보드와 설정 화면 상단, 마지막 정상값 블록 <b>밖</b>에 둔다.
 *
 * 정적 콘텐츠다 — landmark `region`(이름은 제목)이고 live region이 아니다. 화면의 단일 status를 늘리지 않는다. 확인 중이거나
 * 공지가 없으면 아무것도 그리지 않는다. 확인하지 못했고 믿을 수 있는 마지막 응답도 없으면 "서비스 상태를 확인하지
 * 못했습니다" 한 문장뿐이다 — 기능 허용을 주장하지 않는다. 재시도 버튼이 없다.
 */
export function ServiceNotice({ view }: { readonly view: ServiceNoticeView }): ReactElement | null {
  if (view.kind === 'checking') return null
  if (view.kind === 'ready' && view.notices.items.length === 0) return null
  return (
    <section aria-labelledby={copy.id} className={styles.region}>
      <h2 className={styles.heading} id={copy.id}>
        {copy.heading}
      </h2>
      {view.kind === 'unavailable' ? (
        <p className={styles.message}>{copy.unavailable}</p>
      ) : (
        <ul className={styles.list}>
          {view.notices.items.map((item, index) => (
            <Item
              item={item}
              key={
                item.kind === 'NOTICE'
                  ? `${item.incidentId}-${String(item.version)}`
                  : `restricted-${String(index)}`
              }
            />
          ))}
        </ul>
      )}
    </section>
  )
}
