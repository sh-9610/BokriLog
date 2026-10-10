/**
 * 사용자 문의(Story 3.6)의 카피·경로·입력 규칙.
 *
 * 설정 화면의 `문의` 영역(`components/SupportInquiryPanel`)과 시험이 같은 문자열을 참조한다. 운영자 카피 모듈
 * (`content/operator`)에 기대지 않는다(그 모듈은 운영자 chunk 전용이다).
 *
 * 답변 시각은 "답변 목표"라고만 말한다 — 약속·보장 표현과 24시간 대응 약속을 쓰지 않는다. 금지 표현 목록은
 * `content/copyRules`가 소유하고 시험이 이 모듈의 문자열을 그것으로 훑는다.
 *
 * 입력 규칙(민감값·길이)은 서버(`InquiryRules`)가 권위이고, 여기의 같은 규칙은 보내기 전에 미리 안내하는 용도다.
 */

import { noticeTimeText } from './serviceNotice'

/** 문의 접수·내 문의 목록. 백엔드 `SupportInquiryController.INQUIRIES_PATH`와 같은 값이다. */
export const SUPPORT_INQUIRIES_PATH = '/api/support/inquiries'

/** 문의 범주. 서버 `SupportInquiryApi.Category`와 같은 닫힌 집합이다. */
export const INQUIRY_CATEGORIES = [
  'ACCOUNT',
  'CONNECTION',
  'SYNC_PLAN',
  'SERVICE_NOTICE',
  'OTHER',
] as const

export type InquiryCategory = (typeof INQUIRY_CATEGORIES)[number]

/** 답변 받을 방법. 서버 `SupportInquiryApi.Contact`와 같다. */
export const INQUIRY_CONTACTS = ['IN_APP', 'EMAIL'] as const

export type InquiryContact = (typeof INQUIRY_CONTACTS)[number]

/** 문의 상태. 서버 `SupportInquiryApi.Status`와 같다. */
export const INQUIRY_STATUSES = ['RECEIVED', 'IN_PROGRESS', 'ANSWERED', 'RESOLVED'] as const

export type InquiryStatus = (typeof INQUIRY_STATUSES)[number]

/** 답변 목표의 상태. 서버 `SupportInquiryApi.TargetState`와 같다. */
export type InquiryTargetState = 'SET' | 'CALENDAR_PENDING'

/** 입력 상한. 서버 `SupportInquiryApi`의 값과 같다. */
export const INQUIRY_TITLE_MAX_LENGTH = 80
export const INQUIRY_DESCRIPTION_MAX_LENGTH = 2000
export const INQUIRY_EMAIL_MAX_LENGTH = 254

/** 입력 필드. 서버가 거절의 `field`로 같은 이름을 돌려준다. */
export type InquiryField = 'category' | 'title' | 'description' | 'contact' | 'replyEmail'

/** 민감값 규칙. 서버 `SupportInquiryApi.SensitiveRule`과 같다. */
export type SensitiveRule = 'SECRET' | 'WALLET' | 'FINANCIAL' | 'EMAIL'

const CATEGORY_LABELS: Readonly<Record<InquiryCategory, string>> = {
  ACCOUNT: '계정·로그인',
  CONNECTION: '거래소 연결',
  SYNC_PLAN: '동기화·계획',
  SERVICE_NOTICE: '서비스 공지·장애',
  OTHER: '기타',
}

const STATUS_LABELS: Readonly<Record<InquiryStatus, string>> = {
  RECEIVED: '접수됨',
  IN_PROGRESS: '처리 중',
  ANSWERED: '답변 완료',
  RESOLVED: '해결됨',
}

const CONTACT_LABELS: Readonly<Record<InquiryContact, string>> = {
  IN_APP: '이 화면에서 확인',
  EMAIL: '이 화면에서 확인하고 이메일로도 회신 받기',
}

/** 민감값 규칙 → 안내 문장. 서버 거절 문장과 같은 뜻이다. 맞은 조각을 되풀이하지 않는다. */
const RULE_MESSAGES: Readonly<Record<SensitiveRule, string>> = {
  SECRET: 'API 키·비밀키·토큰처럼 보이는 값이 있습니다. 그 값을 빼 주세요.',
  WALLET: '지갑 주소처럼 보이는 값이 있습니다. 그 값을 빼 주세요.',
  FINANCIAL: '금액·계좌번호처럼 보이는 숫자가 있습니다. 금액과 6자리 이상 숫자를 빼 주세요.',
  EMAIL:
    '제목·내용에 이메일 주소가 있습니다. 이메일 답변이 필요하면 답변 방법에서 고른 뒤 주소 칸에 적어 주세요.',
}

const FIELD_MESSAGES: Readonly<Record<InquiryField, string>> = {
  category: '문의 종류를 골라 주세요.',
  title: `제목을 ${String(INQUIRY_TITLE_MAX_LENGTH)}자 이내의 한 줄로 입력해 주세요.`,
  description: `내용을 ${String(INQUIRY_DESCRIPTION_MAX_LENGTH)}자 이내로 입력해 주세요.`,
  contact: '답변 받을 방법을 골라 주세요.',
  replyEmail: `이메일 주소 하나를 ${String(INQUIRY_EMAIL_MAX_LENGTH)}자 이내로 입력해 주세요.`,
}

/** 서버 거절 code → 화면 문장. 닫힌 맵이며 모르는 code는 하나로 접는다. */
const REFUSALS: Readonly<Record<string, string>> = {
  INQUIRY_RATE_LIMITED:
    '최근 24시간 동안 문의를 5건 보냈습니다. 시간이 지난 뒤 다시 보내 주세요. 보낸 문의의 상태는 아래 목록에서 볼 수 있습니다.',
  IDEMPOTENCY_CONFLICT:
    '같은 요청이 다른 내용으로 이미 처리되었습니다. 내용을 확인한 뒤 다시 보내 주세요.',
  IDEMPOTENCY_KEY_REQUIRED: '요청을 받지 못했습니다. 화면을 새로 열어 다시 보내 주세요.',
  FORBIDDEN: '요청을 확인하지 못했습니다. 화면을 새로 연 뒤 다시 보내 주세요.',
}

export const supportContent = {
  /** 영역 제목의 id. landmark `region`의 이름이 된다. */
  id: 'support-inquiry-heading',
  heading: '문의',
  body: '서비스 이용 중 문제나 계정 관련 질문을 남겨 주세요. 접수 다음 영업일부터 세어 3번째 영업일 18:00(KST)까지 답변하는 것을 목표로 합니다. 운영 시간은 대한민국 영업일 09:00~18:00(KST)입니다.',
  /** 민감값 금지 안내. 폼 위에 영구로 둔다. */
  sensitiveNotice:
    'API 키·비밀키·토큰, 지갑 주소, 잔고·금액·계좌번호 같은 금융 정보는 적지 마세요. 그런 값이 있으면 문의를 받지 않습니다.',
  formHeading: '새 문의',
  categoryLabel: '문의 종류',
  categoryHelp: '가장 가까운 종류를 고릅니다.',
  categoryPlaceholder: '종류를 골라 주세요',
  titleLabel: '제목',
  titleHelp: `${String(INQUIRY_TITLE_MAX_LENGTH)}자 이내 한 줄`,
  descriptionLabel: '내용',
  descriptionHelp: `${String(INQUIRY_DESCRIPTION_MAX_LENGTH)}자 이내. 무엇을 하려다 어떤 화면을 보았는지 적어 주세요.`,
  contactLabel: '답변 받을 방법',
  contactHelp: '답변은 언제나 아래 문의 목록에 표시됩니다.',
  emailLabel: '이메일 주소',
  emailHelp:
    '이메일을 고르면 운영자가 이 주소로 회신할 수 있고, 답변은 이 목록에도 남습니다. 주소는 문의와 함께 보관되며 해결 후 90일이 지나면 문의와 함께 삭제됩니다.',
  submit: '문의 보내기',
  /** 보내는 중. 같은 요청을 다시 보내지 않는다. */
  sending: '문의를 보내고 있습니다.',
  /** 보내기 전 검사에서 막혔다. */
  invalid: '입력을 확인해 주세요. 표시된 칸에 안내가 있습니다.',
  /** 응답을 받지 못했다. 자동으로 다시 보내지 않는다. */
  unanswered:
    '응답을 받지 못해 접수 여부를 알 수 없습니다. 입력은 그대로 두었습니다. 다시 보내기를 누르면 같은 요청으로 처리되어 두 번 접수되지 않습니다.',
  /** 응답 없는 접수 뒤 입력이 바뀌었다. 새 문의로 보내기 전에 목록을 다시 읽었다. */
  checkFirst:
    '앞서 보낸 문의는 응답을 받지 못해 접수 여부를 알 수 없습니다. 아래 문의 목록을 다시 불러왔으니 이미 접수됐는지 확인해 주세요. 새 문의로 보내려면 문의 보내기를 다시 누르세요.',
  received: (reference: string): string => `문의를 접수했습니다. 접수 번호는 ${reference}입니다.`,
  receipt: {
    id: 'support-inquiry-receipt',
    heading: '접수 확인',
    reference: '접수 번호',
    receivedAt: '접수 시각',
    targetAt: '답변 목표',
  },
  /** 달력에 그 해가 아직 없어 답변 목표를 정하지 못했다. 접수는 되었다. */
  targetPending: '답변 목표를 아직 정하지 못했습니다',
  listHeading: '내 문의',
  /** 목록에서 내가 쓴 본문을 펼치는 조작. */
  descriptionToggle: '보낸 내용 보기',
  listEmpty: '보낸 문의가 없습니다.',
  listChecking: '문의 목록을 확인하고 있습니다.',
  listUnavailable: '문의 목록을 불러오지 못했습니다.',
  listRetry: '문의 목록 다시 불러오기',
  columns: {
    reference: '접수 번호',
    category: '종류',
    receivedAt: '접수 시각',
    targetAt: '답변 목표',
    status: '상태',
    answer: '답변',
    answeredAt: '답변 시각',
  },
  /** 공개 지원 이메일(설정값이 있을 때만). 웹 문의가 어려울 때의 대안이다. */
  fallback: '웹 문의를 쓰기 어렵다면 다음 주소로 메일을 보낼 수 있습니다:',
  unknownRefusal: '문의를 보내지 못했습니다. 입력은 그대로 두었습니다. 잠시 후 다시 보내 주세요.',
} as const

export function inquiryCategoryLabel(category: InquiryCategory): string {
  return CATEGORY_LABELS[category]
}

export function inquiryStatusLabel(status: InquiryStatus): string {
  return STATUS_LABELS[status]
}

export function inquiryContactLabel(contact: InquiryContact): string {
  return CONTACT_LABELS[contact]
}

export function sensitiveRuleMessage(rule: SensitiveRule): string {
  return RULE_MESSAGES[rule]
}

export function inquiryFieldMessage(field: InquiryField): string {
  return FIELD_MESSAGES[field]
}

/** 서버 거절 code → 문장. 모르면 일반 문장이다. */
export function inquiryRefusalMessage(code: string | null): string {
  return code === null
    ? supportContent.unknownRefusal
    : (REFUSALS[code] ?? supportContent.unknownRefusal)
}

/** 답변 목표 문장. 목표 시각이 없으면 미정 문장이다. 시각은 서울 시각에 `KST`를 붙인다. */
export function inquiryTargetText(targetAt: string | null): string {
  return targetAt === null ? supportContent.targetPending : noticeTimeText(targetAt)
}

/** 문의 시각. 서울 시각에 `KST`를 붙인다. */
export function inquiryTimeText(value: string): string {
  return noticeTimeText(value)
}

// ------------------------------------------------------------------ 입력 규칙(서버 `InquiryRules`와 같은 한 벌)

/** PEM 머리말·`Bearer ` 토큰. 대소문자를 가리지 않고 낱말 경계를 보지 않는다(서버와 같은 의미). */
const SECRET_MARKER = /-----begin|bearer\s/iu
const WORD_RUN = /[A-Za-z0-9_-]+/g
const SECRET_MIN_CHARS = 20
const UUID_TEXT = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/
const ERROR_CODE = /^[A-Z0-9_]+$/
const HEX_WALLET = /0[xX][0-9a-fA-F]{40}/
const ALNUM_TOKEN = /[A-Za-z0-9]+/g
const BASE58 = /^[1-9A-HJ-NP-Za-km-z]+$/
const BECH32 = /^[a-z]{1,10}1[02-9ac-hj-np-z]{6,}$/i
const EMAIL_LIKE = /[^\s@]+@[^\s@]+\.[^\s@]+/u
const ISO_DATE = /(?<!\p{Nd})\p{Nd}{4}-\p{Nd}{2}-\p{Nd}{2}(?!\p{Nd})/gu
const CLOCK_TIME = /(?<![\p{Nd}:])\p{Nd}{1,2}:\p{Nd}{2}(?::\p{Nd}{2})?(?![\p{Nd}:])/gu
const LONG_DIGITS = /\p{Nd}{6,}/u
const DIGIT_GROUP = /\p{Nd}+(?:[,.:\s-]\p{Nd}+)*/gu
const MONEY_LIKE = /[$₩€¥£]\s*\p{Nd}|\p{Nd}[\p{Nd},.]*\s*(?:USDT|USD|원)|(?:USDT|USD)\s*\p{Nd}/iu
const FORMAT = /\p{Cf}/gu
const REPLY_EMAIL = /^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/
// eslint-disable-next-line no-control-regex -- 제목의 제어·서식 문자와 줄·문단 구분자를 거르는 규칙이다(서버 규칙과 같다).
const CONTROL = /[\u0000-\u001f\u007f\u0085\u2028\u2029\p{Cf}]/u
// eslint-disable-next-line no-control-regex -- 본문은 줄바꿈·탭만 받는다(서버 규칙과 같다).
const CONTROL_EXCEPT_LINES = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/

/** 판정 앞의 정규화: NFKC(전각 숫자·통화 기호·＠를 보통 문자로)와 서식 문자(폭 없는 공백 등) 제거. */
export function normalizedForRules(text: string): string {
  return text.normalize('NFKC').replace(FORMAT, '')
}

/** 토큰 모양: 영문자와 숫자가 함께 있는 20자 이상 낱말. 정규 UUID와 대문자·밑줄만의 오류 code는 뺀다. */
function tokenLike(text: string): boolean {
  for (const match of text.matchAll(WORD_RUN)) {
    const word = match[0]
    if (word.length < SECRET_MIN_CHARS || UUID_TEXT.test(word) || ERROR_CODE.test(word)) continue
    if (/[A-Za-z]/.test(word) && /[0-9]/.test(word)) return true
  }
  return false
}

function walletLike(text: string): boolean {
  if (HEX_WALLET.test(text)) return true
  for (const match of text.matchAll(ALNUM_TOKEN)) {
    const token = match[0]
    if (token.length >= 26 && token.length <= 62 && (BASE58.test(token) || BECH32.test(token))) {
      return true
    }
  }
  return false
}

/** 금융값 모양. ISO 날짜와 시각은 먼저 지운다(날짜·시각은 금융값이 아니다). */
function financialLike(normalized: string): boolean {
  const text = normalized.replace(ISO_DATE, '#').replace(CLOCK_TIME, '#')
  if (LONG_DIGITS.test(text) || MONEY_LIKE.test(text)) return true
  for (const match of text.matchAll(DIGIT_GROUP)) {
    const digits = (match[0].match(/\p{Nd}/gu) ?? []).length
    if (digits >= 6) return true
  }
  return false
}

/** 민감값 모양이 있으면 그 규칙. 서버와 같은 정규화·같은 순서로 판정한다. */
export function sensitiveRuleOf(raw: string): SensitiveRule | null {
  const text = normalizedForRules(raw)
  if (SECRET_MARKER.test(text)) return 'SECRET'
  if (EMAIL_LIKE.test(text)) return 'EMAIL'
  if (walletLike(text)) return 'WALLET'
  if (tokenLike(text)) return 'SECRET'
  if (financialLike(text)) return 'FINANCIAL'
  return null
}

/** 보내기 전 입력 검사의 결과. 문제가 없으면 빈 객체다. */
export type InquiryProblems = Partial<
  Record<InquiryField, { readonly message: string; readonly rule: SensitiveRule | null }>
>

/** 보내기 전 검사. 서버 규칙과 같은 길이·모양·민감값 규칙이다. */
export function inquiryProblems(input: {
  readonly category: string
  readonly title: string
  readonly description: string
  readonly contact: string
  readonly replyEmail: string
}): InquiryProblems {
  const problems: Record<string, { message: string; rule: SensitiveRule | null }> = {}
  if (!INQUIRY_CATEGORIES.some((category) => category === input.category)) {
    problems.category = { message: FIELD_MESSAGES.category, rule: null }
  }
  const title = input.title.trim()
  if (title === '' || title.length > INQUIRY_TITLE_MAX_LENGTH || CONTROL.test(title)) {
    problems.title = { message: FIELD_MESSAGES.title, rule: null }
  } else {
    const rule = sensitiveRuleOf(title)
    if (rule !== null) problems.title = { message: RULE_MESSAGES[rule], rule }
  }
  const description = input.description.trim()
  if (
    description === '' ||
    description.length > INQUIRY_DESCRIPTION_MAX_LENGTH ||
    CONTROL_EXCEPT_LINES.test(description)
  ) {
    problems.description = { message: FIELD_MESSAGES.description, rule: null }
  } else {
    const rule = sensitiveRuleOf(description)
    if (rule !== null) problems.description = { message: RULE_MESSAGES[rule], rule }
  }
  if (!INQUIRY_CONTACTS.some((contact) => contact === input.contact)) {
    problems.contact = { message: FIELD_MESSAGES.contact, rule: null }
  } else if (input.contact === 'EMAIL') {
    const email = input.replyEmail.trim()
    if (email.length > INQUIRY_EMAIL_MAX_LENGTH || !REPLY_EMAIL.test(email)) {
      problems.replyEmail = { message: FIELD_MESSAGES.replyEmail, rule: null }
    }
  }
  return problems
}

/** 시험과 화면이 함께 쓰는 금지 표현 검사 대상. */
export const supportCopyStrings: readonly string[] = [
  supportContent.heading,
  supportContent.body,
  supportContent.sensitiveNotice,
  supportContent.contactHelp,
  supportContent.emailHelp,
  supportContent.unanswered,
  supportContent.checkFirst,
  supportContent.targetPending,
  supportContent.fallback,
  supportContent.received('Q-ABCDEFGH23'),
  ...Object.values(CATEGORY_LABELS),
  ...Object.values(STATUS_LABELS),
  ...Object.values(CONTACT_LABELS),
  ...Object.values(RULE_MESSAGES),
  ...Object.values(FIELD_MESSAGES),
  ...Object.values(REFUSALS),
]
