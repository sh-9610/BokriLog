import { useRef, useState, type ReactElement, type RefObject } from 'react'

import type { InquiryReceipt, SupportInquiries, UserInquiry } from '../session/useSupportInquiries'

import {
  INQUIRY_CATEGORIES,
  INQUIRY_CONTACTS,
  INQUIRY_DESCRIPTION_MAX_LENGTH,
  INQUIRY_EMAIL_MAX_LENGTH,
  INQUIRY_TITLE_MAX_LENGTH,
  inquiryCategoryLabel,
  inquiryContactLabel,
  inquiryFieldMessage,
  inquiryProblems,
  inquiryStatusLabel,
  inquiryTargetText,
  inquiryTimeText,
  sensitiveRuleMessage,
  supportContent,
  type InquiryField,
  type InquiryProblems,
} from '../content/support'
import styles from './SupportInquiryPanel.module.css'

const copy = supportContent

/** 필드의 DOM id. 라벨·도움말·오류 문장이 이 id로 묶인다. */
const FIELD_IDS: Readonly<Record<InquiryField, string>> = {
  category: 'support-inquiry-category',
  title: 'support-inquiry-title',
  description: 'support-inquiry-description',
  contact: 'support-inquiry-contact',
  replyEmail: 'support-inquiry-email',
}

/** 필드 순서. 첫 문제 칸으로 초점을 옮길 때 이 순서를 본다. */
const FIELD_ORDER: readonly InquiryField[] = [
  'category',
  'title',
  'description',
  'contact',
  'replyEmail',
]

interface SupportInquiryPanelProps {
  readonly support: SupportInquiries
  /** 화면의 다른 조작이 도는 중이다. 보내기를 막는다. */
  readonly blocked: boolean
  /** 이 영역의 조작이 화면의 live region을 소유하게 한다. */
  readonly onAction: () => void
}

function Receipt({ receipt }: { readonly receipt: InquiryReceipt }): ReactElement {
  return (
    <div aria-labelledby={copy.receipt.id} className={styles.receipt} role="group">
      <h3 className={styles.subheading} id={copy.receipt.id}>
        {copy.receipt.heading}
      </h3>
      <dl className={styles.facts}>
        <div>
          <dt>{copy.receipt.reference}</dt>
          <dd className={styles.reference}>{receipt.reference}</dd>
        </div>
        <div>
          <dt>{copy.receipt.receivedAt}</dt>
          <dd>{inquiryTimeText(receipt.receivedAt)}</dd>
        </div>
        <div>
          <dt>{copy.receipt.targetAt}</dt>
          <dd>{inquiryTargetText(receipt.targetAt)}</dd>
        </div>
      </dl>
    </div>
  )
}

function InquiryRow({ inquiry }: { readonly inquiry: UserInquiry }): ReactElement {
  const columns = copy.columns
  return (
    <li className={styles.item}>
      <p className={styles.itemTitle}>{inquiry.title}</p>
      {/* 내가 쓴 본문. 접힌 채로 두고 사용자가 펼친다(키보드로도 열린다). */}
      <details className={styles.details}>
        <summary className={styles.summary}>{copy.descriptionToggle}</summary>
        <p className={styles.answer}>{inquiry.description}</p>
      </details>
      <dl className={styles.facts}>
        <div>
          <dt>{columns.status}</dt>
          {/* 상태는 글자가 말한다. 색만으로 가르지 않는다. */}
          <dd>{inquiryStatusLabel(inquiry.status)}</dd>
        </div>
        <div>
          <dt>{columns.reference}</dt>
          <dd className={styles.reference}>{inquiry.reference}</dd>
        </div>
        <div>
          <dt>{columns.category}</dt>
          <dd>{inquiryCategoryLabel(inquiry.category)}</dd>
        </div>
        <div>
          <dt>{columns.receivedAt}</dt>
          <dd>{inquiryTimeText(inquiry.receivedAt)}</dd>
        </div>
        <div>
          <dt>{columns.targetAt}</dt>
          <dd>{inquiryTargetText(inquiry.targetAt)}</dd>
        </div>
        {inquiry.answer === null ? null : (
          <>
            <div>
              <dt>{columns.answer}</dt>
              <dd className={styles.answer}>{inquiry.answer}</dd>
            </div>
            {inquiry.answeredAt === null ? null : (
              <div>
                <dt>{columns.answeredAt}</dt>
                <dd>{inquiryTimeText(inquiry.answeredAt)}</dd>
              </div>
            )}
          </>
        )}
      </dl>
    </li>
  )
}

/**
 * 설정 화면의 `문의` 영역(Story 3.6 AC-4).
 *
 * 폼은 영구 라벨·도움말과 민감값 금지 안내를 둔다. 보내기 전에 서버와 같은 규칙으로 미리 안내하고(문제 칸에 문장을 붙이고
 * 첫 문제 칸으로 초점을 옮긴다), 서버가 거절하면 그 칸에 같은 방식으로 붙인다. <b>입력은 실패에서 지우지 않는다</b> —
 * 네트워크 끊김·5xx·거절 모두 그대로 두고, 자동으로 다시 보내지 않는다. 사용자가 다시 누르면 같은 입력은 같은 멱등 키다.
 * 성공하면 접수 번호·시각·답변 목표를 확인 블록으로 보이고 화면의 status가 한 번 말한다(이 영역은 live region을 더하지
 * 않는다).
 *
 * 공개 지원 이메일은 설정값이 있을 때만 `mailto` 링크로 보인다 — 허위·빈 지원 경로가 없다.
 */
export function SupportInquiryPanel({
  blocked,
  onAction,
  support,
}: SupportInquiryPanelProps): ReactElement {
  const [category, setCategory] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [contact, setContact] = useState('IN_APP')
  const [replyEmail, setReplyEmail] = useState('')
  const [problems, setProblems] = useState<InquiryProblems>({})
  const categoryRef = useRef<HTMLSelectElement>(null)
  const titleRef = useRef<HTMLInputElement>(null)
  const descriptionRef = useRef<HTMLTextAreaElement>(null)
  const contactRef = useRef<HTMLSelectElement>(null)
  const emailRef = useRef<HTMLInputElement>(null)
  const refs: Readonly<Record<InquiryField, RefObject<HTMLElement | null>>> = {
    category: categoryRef,
    title: titleRef,
    description: descriptionRef,
    contact: contactRef,
    replyEmail: emailRef,
  }

  const locked = blocked || support.pending

  /** 첫 문제 칸으로 초점을 옮긴다 — 사용자가 누른 보내기의 결과다. */
  const focusFirst = (found: InquiryProblems): void => {
    const first = FIELD_ORDER.find((field) => found[field] !== undefined)
    if (first !== undefined) refs[first].current?.focus()
  }

  const send = async (): Promise<void> => {
    if (locked) return
    onAction()
    const draft = { category, title, description, contact, replyEmail }
    const found = inquiryProblems(draft)
    setProblems(found)
    if (Object.keys(found).length > 0) {
      support.reportInvalid()
      focusFirst(found)
      return
    }
    const result = await support.submit(draft)
    if (result.kind === 'received') {
      // 접수됐다. 다음 문의를 위해 글을 비운다(종류·답변 방법은 남긴다).
      setTitle('')
      setDescription('')
      setReplyEmail('')
      setProblems({})
      return
    }
    // 서버가 칸을 짚었으면 그 칸에 붙인다. 입력은 그대로다.
    if (result.kind === 'refused' && result.field !== null) {
      const field = result.field
      const server: InquiryProblems = {
        [field]: {
          message:
            result.rule === null ? inquiryFieldMessage(field) : sensitiveRuleMessage(result.rule),
          rule: result.rule,
        },
      }
      setProblems(server)
      focusFirst(server)
    }
  }

  const describedBy = (field: InquiryField, help: string): string =>
    problems[field] === undefined ? help : `${help} ${FIELD_IDS[field]}-error`

  const errorOf = (field: InquiryField): ReactElement | null => {
    const problem = problems[field]
    return problem === undefined ? null : (
      <p className={styles.fieldError} id={`${FIELD_IDS[field]}-error`}>
        {problem.message}
      </p>
    )
  }

  const list = support.list

  return (
    <section aria-labelledby={copy.id} className={styles.section}>
      <h2 className={styles.heading} id={copy.id}>
        {copy.heading}
      </h2>
      <p>{copy.body}</p>
      <p className={styles.sensitive}>{copy.sensitiveNotice}</p>

      <form
        aria-labelledby="support-inquiry-form-heading"
        className={styles.form}
        noValidate
        onSubmit={(event) => {
          // 폼은 fetch로만 보낸다. `form-action 'none'` CSP를 유지한다.
          event.preventDefault()
          void send()
        }}
      >
        <h3 className={styles.subheading} id="support-inquiry-form-heading">
          {copy.formHeading}
        </h3>

        <div className={styles.field}>
          <label className={styles.label} htmlFor={FIELD_IDS.category}>
            {copy.categoryLabel}
          </label>
          <select
            aria-describedby={describedBy('category', 'support-inquiry-category-help')}
            aria-invalid={problems.category !== undefined}
            className={styles.input}
            id={FIELD_IDS.category}
            onChange={(event) => {
              setCategory(event.target.value)
            }}
            ref={categoryRef}
            value={category}
          >
            <option value="">{copy.categoryPlaceholder}</option>
            {INQUIRY_CATEGORIES.map((value) => (
              <option key={value} value={value}>
                {inquiryCategoryLabel(value)}
              </option>
            ))}
          </select>
          <p className={styles.help} id="support-inquiry-category-help">
            {copy.categoryHelp}
          </p>
          {errorOf('category')}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor={FIELD_IDS.title}>
            {copy.titleLabel}
          </label>
          <input
            aria-describedby={describedBy('title', 'support-inquiry-title-help')}
            aria-invalid={problems.title !== undefined}
            autoComplete="off"
            className={styles.input}
            id={FIELD_IDS.title}
            maxLength={INQUIRY_TITLE_MAX_LENGTH}
            onChange={(event) => {
              setTitle(event.target.value)
            }}
            ref={titleRef}
            type="text"
            value={title}
          />
          <p className={styles.help} id="support-inquiry-title-help">
            {copy.titleHelp}
          </p>
          {errorOf('title')}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor={FIELD_IDS.description}>
            {copy.descriptionLabel}
          </label>
          <textarea
            aria-describedby={describedBy('description', 'support-inquiry-description-help')}
            aria-invalid={problems.description !== undefined}
            autoComplete="off"
            className={styles.textarea}
            id={FIELD_IDS.description}
            maxLength={INQUIRY_DESCRIPTION_MAX_LENGTH}
            onChange={(event) => {
              setDescription(event.target.value)
            }}
            ref={descriptionRef}
            rows={6}
            value={description}
          />
          <p className={styles.help} id="support-inquiry-description-help">
            {copy.descriptionHelp}
          </p>
          {errorOf('description')}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor={FIELD_IDS.contact}>
            {copy.contactLabel}
          </label>
          <select
            aria-describedby={describedBy('contact', 'support-inquiry-contact-help')}
            aria-invalid={problems.contact !== undefined}
            className={styles.input}
            id={FIELD_IDS.contact}
            onChange={(event) => {
              setContact(event.target.value)
            }}
            ref={contactRef}
            value={contact}
          >
            {INQUIRY_CONTACTS.map((value) => (
              <option key={value} value={value}>
                {inquiryContactLabel(value)}
              </option>
            ))}
          </select>
          <p className={styles.help} id="support-inquiry-contact-help">
            {copy.contactHelp}
          </p>
          {errorOf('contact')}
        </div>

        {contact === 'EMAIL' ? (
          <div className={styles.field}>
            <label className={styles.label} htmlFor={FIELD_IDS.replyEmail}>
              {copy.emailLabel}
            </label>
            <input
              aria-describedby={describedBy('replyEmail', 'support-inquiry-email-help')}
              aria-invalid={problems.replyEmail !== undefined}
              autoComplete="email"
              className={styles.input}
              id={FIELD_IDS.replyEmail}
              maxLength={INQUIRY_EMAIL_MAX_LENGTH}
              onChange={(event) => {
                setReplyEmail(event.target.value)
              }}
              ref={emailRef}
              type="email"
              value={replyEmail}
            />
            <p className={styles.help} id="support-inquiry-email-help">
              {copy.emailHelp}
            </p>
            {errorOf('replyEmail')}
          </div>
        ) : null}

        <div className={styles.actions}>
          {/*
            `aria-disabled`이지 `disabled`가 아니다 — 방금 `Enter`로 보낸 키보드·스크린리더 사용자가 포커스를 잃지 않는다.
            실제 중복 제출 차단은 훅의 가드가 한다.
          */}
          <button aria-disabled={locked} className={styles.submit} type="submit">
            {copy.submit}
          </button>
        </div>
      </form>

      {support.receipt === null ? null : <Receipt receipt={support.receipt} />}

      <h3 className={styles.subheading} id="support-inquiry-list-heading">
        {copy.listHeading}
      </h3>
      {list.kind === 'checking' ? (
        <p className={styles.muted}>{copy.listChecking}</p>
      ) : list.kind === 'unavailable' ? (
        <>
          <p className={styles.muted}>{copy.listUnavailable}</p>
          <button
            aria-disabled={support.listLoading}
            className={styles.secondary}
            onClick={() => {
              if (!support.listLoading) support.reload()
            }}
            type="button"
          >
            {copy.listRetry}
          </button>
        </>
      ) : (
        <>
          {list.refreshFailed ? (
            <>
              <p className={styles.muted}>{copy.listUnavailable}</p>
              <button
                aria-disabled={support.listLoading}
                className={styles.secondary}
                onClick={() => {
                  if (!support.listLoading) support.reload()
                }}
                type="button"
              >
                {copy.listRetry}
              </button>
            </>
          ) : null}
          {list.items.length === 0 ? (
            <p className={styles.muted}>{copy.listEmpty}</p>
          ) : (
            <ol aria-labelledby="support-inquiry-list-heading" className={styles.list}>
              {list.items.map((inquiry) => (
                <InquiryRow inquiry={inquiry} key={inquiry.reference} />
              ))}
            </ol>
          )}
          {/* 설정된 연락처만 진짜 경로다. 없으면 링크를 두지 않는다. */}
          {list.supportContact === null ? null : (
            <p className={styles.fallback}>
              {copy.fallback}{' '}
              <a className={styles.link} href={`mailto:${list.supportContact}`}>
                {list.supportContact}
              </a>
            </p>
          )}
        </>
      )}
    </section>
  )
}
