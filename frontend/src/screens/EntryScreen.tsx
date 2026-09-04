import type { ReactElement } from 'react'

import { entryContent, MAIN_LANDMARK_ID } from '../content/entry'
import styles from './EntryScreen.module.css'

/**
 * 로그인 없이 열리는 진입 화면.
 *
 * 이용 범위(AC-1), 인증 경계(AC-2), 비추천 고지(UX-DR23)를 semantic 마크업으로 렌더한다.
 * 외부 요청·브라우저 저장소 쓰기·라우터·소셜 버튼은 이 스토리 범위 밖이다.
 */
export function EntryScreen(): ReactElement {
  return (
    <div className={styles.page}>
      <a className={styles.skipLink} href={`#${MAIN_LANDMARK_ID}`}>
        {entryContent.skipLink}
      </a>

      <header className={styles.header}>
        <p className={styles.brand}>{entryContent.brand}</p>
      </header>

      <main className={styles.main} id={MAIN_LANDMARK_ID} tabIndex={-1}>
        <div className={styles.intro}>
          <h1 className={styles.title}>{entryContent.pageTitle}</h1>
          <p className={styles.disclaimer}>{entryContent.disclaimer}</p>
          <p className={styles.lead}>{entryContent.lead}</p>
        </div>

        <div className={styles.sections}>
          {entryContent.sections.map((section) => (
            <section
              key={section.id}
              aria-labelledby={section.id}
              className={
                section.tone === 'notice'
                  ? [styles.section, styles.sectionNotice].join(' ')
                  : styles.section
              }
            >
              <h2 className={styles.sectionHeading} id={section.id}>
                {section.heading}
              </h2>
              {/* 전역 reset이 list-style을 지우면 일부 보조기술이 목록 의미를 잃는다. */}
              <ul className={styles.itemList} role="list">
                {/* 카피 문자열은 한 섹션 안에서 중복될 수 있으므로 key로 쓰지 않는다. */}
                {section.items.map((item, index) => (
                  <li className={styles.item} key={`${section.id}-${String(index)}`}>
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      </main>
    </div>
  )
}
