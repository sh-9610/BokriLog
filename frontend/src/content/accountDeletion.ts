/**
 * 계정 삭제 요청(Story 5.1)의 카피·경로·정책 code.
 *
 * 설정 화면의 `계정 삭제` 영역(`components/AccountDeletionPanel`)·확인 대화상자(`components/AccountDeletionDialog`)·삭제 진행 화면
 * (`screens/DeletionProgressScreen`)과 시험이 같은 문자열을 참조한다. 금지 표현 목록은 `content/copyRules`가 소유한다.
 *
 * 정책의 원본은 서버 상수(`AccountDeletionPolicy`, `ACCOUNT-DELETION-1.1`)이고, 사용자가 읽는 고지 문장(삭제 대상 문장·남기는 것·백업·
 * 법정 보존 없음)의 원본은 공유 표 `accountDeletionPolicy.fixture.json`의 `notice` 하나다 — 화면은 그 표를 읽고, 서버 고지 hash는 그
 * 정규 직렬화의 digest를 덮는다. 화면은 서버가 준 삭제 대상 code를 그 닫힌 맵으로 카피에 잇고 모르는 code는 그리지 않는다. 두 벌의 code
 * 목록·버전·기한·거절 code도 같은 표를 프런트·백엔드 시험이 함께 읽어 묶는다. 표의 `noticeDigestByVersion`이 버전마다 고지 digest를
 * 하나씩 남기고 백엔드 시험이 "지금 버전의 행 = 서버 `NOTICE_DIGEST` = 표 `notice`에서 다시 계산한 값"과 "행의 digest가 서로 모두
 * 다름"을 단정한다 — 문장을 바꾸면 새 버전 행을 더하고 버전과 그 상수를 올려야 한다(기존 행을 고쳐 쓰면 리뷰에 이력 수정으로 보인다).
 *
 * 법정 분리 보존은 외부 승인(G-5) 전이라 비어 있다. 이 화면의 "보존하지 않습니다" 문장은 개발·CI 기본값이며, production에서는
 * 서버가 삭제 요청을 열지 않는다(`available:false`).
 */

// e2e(Playwright, Node ESM)도 이 모듈을 읽으므로 JSON import 속성을 붙인다 — Vite·Vitest는 그대로 받는다.
import accountDeletionPolicy from './accountDeletionPolicy.fixture.json' with { type: 'json' }
import { formatVerifiedAt } from './connection'

/**
 * 고지 문장의 단일 원본(공유 표의 `notice`). 서버 고지 hash(`AccountDeletionPolicy.NOTICE_DIGEST`)가 이 문장들의 정규 직렬화를 덮고, 백엔드
 * 시험이 표에서 다시 계산해 상수·버전별 이력(`noticeDigestByVersion`)과 대조한다(위 머리 주석).
 */
const NOTICE = accountDeletionPolicy.notice

/** 백업 사본 파기 일 수(공유 표 `backupDestroyDays` — 서버 `AccountDeletionPolicy.BACKUP_DESTROY_DAYS`와 같은 값). */
const BACKUP_DAYS = String(accountDeletionPolicy.backupDestroyDays)

/** 정책·요청 상태 조회와 준비. 백엔드 `AccountDeletionController.DELETION_PATH`와 같은 값이다. */
export const ACCOUNT_DELETION_PATH = '/api/account/deletion'
export const ACCOUNT_DELETION_CONFIRM_PATH = `${ACCOUNT_DELETION_PATH}/confirm`
export const ACCOUNT_DELETION_CANCEL_PATH = `${ACCOUNT_DELETION_PATH}/cancel`
/** 재시도 필요로 멈춘 삭제 실행의 다시 시도(Story 5.2). 백엔드 `AccountDeletionController.RETRY_PATH`와 같은 값이다. */
export const ACCOUNT_DELETION_RETRY_PATH = `${ACCOUNT_DELETION_PATH}/retry`

/** 이 화면의 설명이 묘사하는 정책 버전(공유 표). 서버 `AccountDeletionPolicy.VERSION`과 같아야 확정이 저장된다. */
export const ACCOUNT_DELETION_POLICY_VERSION: string = accountDeletionPolicy.version

/**
 * 삭제 대상 code → 화면 문장. 서버 `AccountDeletionPolicy.TARGETS`와 같은 닫힌 집합이고, 문장은 공유 표의 `notice.targets`에서 읽는다
 * (고지 hash가 덮는 문장 — 여기서 고치지 않는다). `OPERATIONS_RECORDS`는 운영 기록 자체는 남고 그 안의 나를 가리키는 정보만 알아볼 수
 * 없는 값으로 바뀌므로 "삭제"라고 말하지 않는다.
 */
const TARGET_SENTENCES = NOTICE.targets

export type AccountDeletionTarget = keyof typeof TARGET_SENTENCES

/** 화면이 아는 삭제 대상 code. 서버 순서와 같다(공유 표가 고정한다). */
export const ACCOUNT_DELETION_TARGETS = Object.keys(
  TARGET_SENTENCES,
) as readonly AccountDeletionTarget[]

/** 그 code의 문장. 모르는 code면 `null`이다 — 화면은 그 항목을 그리지 않는다. */
export function accountDeletionTargetSentence(code: string): string | null {
  return Object.hasOwn(TARGET_SENTENCES, code)
    ? TARGET_SENTENCES[code as AccountDeletionTarget]
    : null
}

export const accountDeletionContent = {
  /** 영역 제목의 id. landmark `region`의 이름이 된다. */
  id: 'account-deletion-heading',
  heading: '계정 삭제',
  body: '계정을 삭제하면 BokriLog에 저장된 내 데이터를 지웁니다. 삭제는 되돌릴 수 없습니다.',
  checking: '계정 삭제 정책을 확인하고 있습니다.',
  unavailable:
    '계정 삭제 정책을 확인하지 못했습니다. 정책을 확인하기 전에는 삭제를 요청할 수 없습니다.',
  retry: '계정 삭제 정책 다시 확인',
  /** 즉시 일어나는 것. */
  immediateHeading: '요청하는 즉시',
  immediate:
    '요청하는 즉시 이 계정의 다른 기기 로그인이 모두 끝나고, 거래소 연결·동기화·계획·문의·광고 같은 모든 조작이 막힙니다. 이 기기에서는 삭제 진행 상태 확인과 로그아웃만 할 수 있고, 로그아웃한 뒤에는 다시 로그인할 수 없습니다.',
  targetsHeading: '삭제하는 운영 데이터',
  targetsHeadingId: 'account-deletion-targets-heading',
  /** 삭제하지 않고 남기는 것. 대화상자도 같은 목록을 말한다. */
  keptHeading: '삭제하지 않고 남기는 것',
  keptHeadingId: 'account-deletion-kept-heading',
  /** 공유 표의 `notice.kept`(고지 hash가 덮는다). */
  kept: NOTICE.kept,
  backupHeading: '백업 사본',
  /** 백업 파기 기한. 일 수는 서버 정책에서 오고 문장 틀은 공유 표의 `notice.backup`(고지 hash가 덮는다)이다. */
  backup: (days: number): string => NOTICE.backup.replace('{days}', String(days)),
  legalHeading: '법정 분리 보존',
  /** 승인된 법정 분리 보존 항목이 없다(개발·CI 기본값). 공유 표의 `notice.legalNone`(고지 hash가 덮는다). */
  legalNone: NOTICE.legalNone,
  /** 승인된 법정 분리 보존 항목 하나의 표시 이름(G-5 승인 뒤에만 서버가 싣는다). */
  legalFieldLabel: '보존 항목',
  legalBasisLabel: '근거',
  legalPurposeLabel: '목적',
  legalPeriodLabel: '기간',
  /** 연결 해제와의 차이. */
  differenceHeading: 'Bybit 연결 해제와 다른 점',
  difference:
    'Bybit 연결 해제는 저장된 자격정보만 지우고 계획·조정 이력·계산 근거를 읽기 전용으로 남깁니다. 계정 삭제는 그 기록까지 모두 지우고 계정을 다시 쓸 수 없게 합니다.',
  policyVersionLabel: '적용 정책 버전',
  /** production — 법정 보존 정책 승인 전이라 실행을 열지 않는다. */
  closed: '지금은 계정 삭제를 요청할 수 없습니다. 삭제가 필요하면 위 문의로 알려 주세요.',
  /** 정책 버전이 이 화면의 설명과 다르다. */
  outdated:
    '계정 삭제 정책이 바뀌었습니다. 화면을 새로 열어 바뀐 정책을 확인한 뒤 다시 요청해 주세요.',
  start: '계정 삭제 요청',
  startId: 'account-deletion-start',
  /** 화면의 단일 live region이 말할 진행·결과 문장. */
  preparing: '계정 삭제 요청을 준비하고 있습니다.',
  cancelled: '계정 삭제 요청을 취소했습니다. 바뀐 것은 없습니다.',
  /** 응답을 받지 못한 확정 뒤에 취소했다. 결과를 단정하지 않는다. */
  unknown: '계정 삭제 요청 결과를 확인하지 못했습니다. 화면을 새로 열어 상태를 확인해 주세요.',
  dialog: {
    titleId: 'account-deletion-dialog-title',
    descriptionId: 'account-deletion-dialog-description',
    progressId: 'account-deletion-dialog-progress',
    uncertainId: 'account-deletion-dialog-uncertain',
    inputId: 'account-deletion-dialog-input',
    noticeId: 'account-deletion-dialog-notice',
    title: '계정을 삭제할까요?',
    /** 비가역 결과를 먼저 말한다. */
    effect:
      '요청하면 되돌릴 수 없습니다. 다른 기기의 로그인이 바로 끝나고, 이 기기에서는 삭제 진행 상태만 볼 수 있습니다.',
    removed:
      '계정·로그인 수단·Bybit 자격정보와 연결, 계획·계산·스냅샷·자금흐름 기록, 문의, 광고 기록을 모두 삭제합니다.',
    /** 남기는 것의 머리 문장. 목록은 `accountDeletionContent.kept`다. */
    keptIntro: '다음은 삭제하지 않고 남깁니다.',
    phraseLabel: '확인을 위해 다음 문구를 그대로 입력해 주세요',
    cancel: '취소',
    confirm: '계정 삭제',
    confirming: '계정 삭제 요청을 처리하고 있습니다.',
    uncertain:
      '응답을 받지 못했습니다. 같은 요청으로 다시 시도하거나 취소한 뒤 화면을 새로 열어 확인해 주세요.',
  },
  progress: {
    /** 삭제 진행 화면의 제목. */
    title: '계정 삭제가 진행 중입니다',
    body: '계정 삭제 요청을 받았습니다. 이 계정의 다른 기기 로그인은 끝났고 모든 조작이 막혀 있습니다.',
    requestedAtLabel: '요청 시각',
    /** 삭제 요청 ID(불투명 참조 — 계정 식별자가 아니다). 문의할 때 알려 줄 값이다. */
    requestIdLabel: '요청 ID',
    policyVersionLabel: '적용 정책 버전',
    stateLabel: '상태',
    state: '삭제 진행 중',
    /** 요청 + 일 수의 날짜. 내부 목표일이고 약속한 기한이 아니다 — `backupTargetNote`가 함께 말한다. */
    backupLabel: '백업 사본 파기 목표일',
    /** 목표일 표시 옆의 설명. 약속은 고지 문장(운영 데이터 삭제를 마친 다음 날부터 최대 일 수)이다. */
    backupTargetNote: `백업 사본 파기 목표일은 내부 목표이며 약속한 기한이 아닙니다. 늦어져도 운영 데이터 삭제를 마친 다음 날부터 최대 ${BACKUP_DAYS}일 안에 파기합니다.`,
    signOutHint: '로그아웃하면 이 계정으로 다시 로그인할 수 없습니다.',
    checking: '계정 삭제 진행 상태를 확인하고 있습니다.',
    unavailable: '삭제 진행 상태를 지금 불러오지 못했습니다. 삭제 요청은 이미 접수돼 있습니다.',
    /** 사용자가 누르는 다시 확인. 자동 재시도는 없다. */
    retry: '삭제 진행 상태 다시 확인',
    signOutFailed: '로그아웃하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    /** 삭제 실행(Story 5.2)이 한 단계에서 멈췄다. 자동으로 다시 하지 않는다. */
    stateRetryNeeded: '재시도 필요',
    retryNeededBody:
      '일부 데이터를 삭제하지 못해 삭제가 멈췄습니다. 다시 시도하면 멈춘 범위와 아직 남은 범위만 다시 처리합니다. 끝난 범위는 남은 데이터가 새로 발견된 경우에만 다시 처리합니다. 계정 삭제는 계속 진행 중이며, 이 화면의 다시 시도와 로그아웃 말고 다른 조작은 막혀 있습니다.',
    failedScopeHeading: '삭제하지 못한 범위',
    failedScopeHeadingId: 'deletion-progress-failed-heading',
    /** 사용자가 누르는 다시 시도. 누를 때만 요청한다. */
    retryErasure: '삭제 다시 시도',
    retryingErasure: '삭제를 다시 시도하도록 요청하고 있습니다.',
    retryAccepted: '다시 시도를 요청했습니다. 잠시 뒤 진행 상태를 다시 확인해 주세요.',
    retryUnanswered:
      '다시 시도 요청의 응답을 받지 못했습니다. 같은 요청으로 다시 누르거나 진행 상태를 다시 확인해 주세요.',
    /** 모든 운영 데이터를 지웠다. 백업 사본 파기와는 별개다. */
    stateErased: '운영 데이터 삭제 완료(백업 사본은 목표일 안에 따로 파기)',
    /** 운영 삭제·백업 파기·법정 분리 보존 처리가 모두 끝났다(Story 5.4). */
    stateCompleted: '전체 삭제 완료',
    /** 지원 경로(deferred 170). 서버에 설정된 연락처가 있을 때만 그린다. */
    supportHeading: '문의',
    supportHeadingId: 'deletion-progress-support-heading',
    supportBody:
      '계정 삭제 진행이나 완료에 대해 문의가 필요하면 아래 연락처로 알려 주세요. 문의할 때 이 요청 ID를 알려 주세요.',
    supportBodyId: 'deletion-progress-support-body',
    supportLabel: '문의 연락처',
  },
  /**
   * 삭제가 끝난 뒤의 완료 안내(Story 5.4). 요청한 세션은 계정 정보 없이 완료 확인용으로만 남아 있고, 모든 값은 서버가 준 사실이다. 운영
   * 데이터 삭제·백업 사본 파기·법정 분리 보존을 서로 다른 상태로 말하고, 기한 초과를 숨기지 않는다.
   */
  completion: {
    /** 운영 데이터는 지웠고 백업 사본 파기를 기다린다. */
    title: '계정의 운영 데이터를 모두 지웠습니다',
    /** 백업 사본 파기·법정 분리 보존 처리까지 모두 끝났다. */
    titleCompleted: '계정 삭제가 모두 끝났습니다',
    body: '이 계정과 연결된 운영 데이터를 모두 지웠습니다. 이 화면은 삭제 결과 확인과 로그아웃만 할 수 있고, 삭제한 계정은 되살릴 수 없습니다.',
    operationalErasedAtLabel: '운영 데이터 삭제 시각',
    /** 서버의 요청 + 일 수(내부 목표일). 약속한 기한이 아니다 — 진행 화면의 `backupTargetNote`를 함께 그린다. */
    backupDeadlineLabel: '백업 사본 파기 목표일',
    backupStateLabel: '백업 사본 파기',
    backupPending: '목표일 안에서 파기를 기다리는 중',
    backupOverdue: '목표일이 지났지만 아직 파기를 확인하지 못함',
    backupDestroyed: '파기 완료',
    /** 서버가 백업 사본 상태를 읽지 못했다. 완료로 말하지 않는다. */
    backupUnknown: '확인하지 못함',
    backupDestroyedAtLabel: '백업 사본 파기 완료 시각',
    /**
     * 목표일 초과는 숨기지 않는다. 다음 백업 실행의 파기가 다시 시도한다(앞당겨 지우지 않는다). 복원 약속은 고지 문장과 같은 범위다 — 삭제
     * 요청 기록이 서비스 밖 보관소에 남은 뒤에만 되살리지 않는다.
     */
    overdueNotice:
      '백업 사본 파기가 내부 목표일을 넘겼습니다. 완료로 표시하지 않으며, 다음 백업 실행에서 파기를 다시 시도합니다. 삭제 요청 기록이 서비스 밖 보관소에 남은 뒤에는 백업으로 복원하더라도 삭제한 계정을 되살리지 않습니다.',
    completionLabel: '전체 삭제',
    completionDone: '완료',
    completionPending: '백업 사본 파기를 확인한 뒤 완료',
    completedAtLabel: '전체 삭제 완료 시각',
    legalLabel: '법정 분리 보존',
    /** 승인된 법정 분리 보존 항목이 없다(개발·CI 기본값). */
    legalNone: '따로 보존하는 항목 없음',
    signOutHint:
      '확인을 마쳤으면 로그아웃해 주세요. 로그아웃하거나, 30분 동안 쓰지 않거나, 이 기기에서 처음 로그인한 때부터 12시간이 지나면 이 화면을 다시 열 수 없습니다.',
    /** 화면 수명(세션 수명)이 백업 사본 파기(최대 30일)보다 짧다는 한계. 영수증은 없다. */
    limitNotice: `백업 사본 파기에는 운영 데이터 삭제를 마친 다음 날부터 최대 ${BACKUP_DAYS}일이 걸려 이 화면이 닫히기 전에 전체 삭제 완료를 보지 못할 수 있습니다. 그 뒤의 결과는 문의 연락처로 확인해 주세요. 삭제 확인서는 따로 드리지 않습니다.`,
    /** 상태 줄 — 백업 사본 파기 상태에 따라 고른다(초과를 "기한 안"으로 말하지 않는다). 기한 안(`PENDING`)은 진행 화면의 `stateErased`다. */
    stateErasedOverdue: '운영 데이터 삭제 완료(백업 사본 파기는 목표일을 넘김)',
    stateErasedDestroyed: '운영 데이터 삭제 완료(백업 사본 파기 완료, 전체 삭제는 아직)',
    stateErasedUnknown: '운영 데이터 삭제 완료(백업 사본 파기 상태 확인하지 못함)',
    retry: '삭제 결과 다시 확인',
    checking: '계정 삭제 결과를 확인하고 있습니다.',
    unavailable: '삭제 결과를 지금 불러오지 못했습니다. 운영 데이터 삭제는 이미 끝났습니다.',
  },
} as const

/**
 * 삭제 단계 code → 실패 범위 문장(Story 5.2). 백엔드 `DeletionStep`과 같은 닫힌 집합·순서이고 공유 표의 `erasureSteps`가 대조한다.
 * 금융 상세·건수·원인 원문은 없다 — 무엇을 지우지 못했는가만 말한다.
 */
const STEP_SCOPES = {
  CREDENTIALS: '로그인 수단 연결과 Bybit 자격정보',
  PORTFOLIO: '현재가치 스냅샷과 구성 내역',
  SYNC: '수집·동기화·정밀 검증 작업 기록과 계산 결과',
  PLAN: '복리 계획과 그 조건·종료·완료 기록',
  SOURCE: 'Bybit 연결과 원 사건·원천 기록',
  OPERATIONS: '문의 내역과 운영 기록 속 나를 알아볼 수 있는 정보',
  ENGAGEMENT: '광고 동의와 광고 시도·빈도 기록',
  IDENTITY: '계정·로그인 세션과 삭제 요청 기록',
} as const

export type AccountDeletionStep = keyof typeof STEP_SCOPES

/** 화면이 아는 삭제 단계 code(실행 순서). */
export const ACCOUNT_DELETION_STEPS = Object.keys(STEP_SCOPES) as readonly AccountDeletionStep[]

/** 모르는 단계 code의 문장. 범위를 지어내지 않는다. */
const UNKNOWN_STEP_SCOPE = '일부 삭제 단계'

/** 그 단계 code의 실패 범위 문장. 모르는 code는 하나로 접힌다. */
export function accountDeletionStepScope(code: string): string {
  return Object.hasOwn(STEP_SCOPES, code)
    ? STEP_SCOPES[code as AccountDeletionStep]
    : UNKNOWN_STEP_SCOPE
}

/** 거절 code → 화면 문장. 백엔드 `AccountDeletionController.REFUSALS`와 같은 code 집합이다(공유 표가 대조한다). */
const REFUSALS: Readonly<Record<string, string>> = {
  FORBIDDEN: '계정 삭제를 요청하지 못했습니다. 세션 상태를 확인한 뒤 다시 시도해 주세요.',
  REAUTHENTICATION_REQUIRED:
    '계정을 삭제하려면 먼저 본인 확인이 필요합니다. 위에서 본인 확인을 마친 뒤 다시 시도해 주세요.',
  ACCOUNT_DELETION_NOT_CONFIRMED:
    '확인된 삭제 요청이 아니어서 계정을 삭제하지 않았습니다. 처음부터 다시 시도해 주세요.',
  CONFIRMATION_MISMATCH: '입력한 문구가 확인 문구와 다릅니다. 다시 입력해 주세요.',
  ACCOUNT_DELETION_POLICY_CHANGED: accountDeletionContent.outdated,
  ACCOUNT_DELETION_IN_PROGRESS: '이미 계정 삭제가 요청돼 진행 중입니다.',
  /** 삭제가 이미 끝났다 — 완료 확인 세션의 확정 재전송(Story 5.4). 아무것도 새로 만들지 않았다. */
  ACCOUNT_DELETION_COMPLETED: '계정 삭제가 이미 끝났습니다. 새로 요청할 것이 없습니다.',
  ACCOUNT_DELETION_NOT_RETRYABLE:
    '지금은 다시 시도할 삭제 단계가 없습니다. 진행 상태를 다시 확인해 주세요.',
  ACCOUNT_DELETION_UNAVAILABLE: accountDeletionContent.closed,
  IDEMPOTENCY_KEY_REQUIRED: '요청을 처리하지 못했습니다. 화면을 새로 열어 다시 시도해 주세요.',
  /** 같은 키가 다른 본문으로 이미 커밋된 요청에 쓰였다 — 그 계정의 삭제는 이미 진행 중이다. */
  IDEMPOTENCY_CONFLICT:
    '같은 요청 키가 다른 삭제 요청에 이미 쓰였습니다. 화면을 새로 열어 상태를 확인해 주세요.',
}

/** 화면이 문장을 가진 거절 code. 공유 표의 `refusalCodes`와 같은 집합이다. */
export const ACCOUNT_DELETION_REFUSAL_CODES: readonly string[] = Object.keys(REFUSALS)

const UNKNOWN_REFUSAL =
  '계정 삭제를 요청하지 못했습니다. 바뀐 것은 없습니다. 잠시 후 다시 시도해 주세요.'

/** 그 거절 code의 문장. 모르는 값은 하나로 접힌다. */
export function accountDeletionRefusalMessage(code: string | null): string {
  if (code === null) {
    return UNKNOWN_REFUSAL
  }
  return REFUSALS[code] ?? UNKNOWN_REFUSAL
}

/**
 * 완료 화면의 상태 줄(Story 5.4). 전체 삭제 완료가 아니면 백업 사본 파기 상태에 따라 고른다 — 기한 초과를 "기한 안"으로 말하지 않고, 상태를
 * 읽지 못했으면 완료로 말하지 않는다.
 */
export function completionStateText(
  deletionCompleted: boolean,
  status: 'PENDING' | 'OVERDUE' | 'DESTROYED' | null,
): string {
  const copy = accountDeletionContent.completion
  if (deletionCompleted) {
    return accountDeletionContent.progress.stateCompleted
  }
  switch (status) {
    case 'PENDING':
      return accountDeletionContent.progress.stateErased
    case 'OVERDUE':
      return copy.stateErasedOverdue
    case 'DESTROYED':
      return copy.stateErasedDestroyed
    case null:
      return copy.stateErasedUnknown
  }
}

/** 백업 사본 파기 상태의 문장(Story 5.4). 서버가 상태를 읽지 못했으면(`null`) 완료로 말하지 않는다. */
export function backupDestructionText(status: 'PENDING' | 'OVERDUE' | 'DESTROYED' | null): string {
  const copy = accountDeletionContent.completion
  switch (status) {
    case 'PENDING':
      return copy.backupPending
    case 'OVERDUE':
      return copy.backupOverdue
    case 'DESTROYED':
      return copy.backupDestroyed
    case null:
      return copy.backupUnknown
  }
}

/** 시각 표시. 연결 검증 시각과 같은 형식(Asia/Seoul)이다. */
export function accountDeletionTimeText(value: string): string {
  return formatVerifiedAt(value)
}

/** 요청 시각에서 백업 사본 파기 목표일(날짜 — 내부 목표, 약속한 기한이 아니다). 일 수는 서버 정책이다. 시각이 깨졌으면 빈 문자열이다. */
export function backupDeadlineText(requestedAt: string, days: number): string {
  const parsed = new Date(requestedAt)
  if (Number.isNaN(parsed.getTime())) {
    return ''
  }
  const deadline = new Date(parsed.getTime() + days * 24 * 60 * 60 * 1000)
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium' }).format(
    deadline,
  )
}

/** 설정 화면의 `계정 삭제` 영역이 정책을 읽은 뒤 언제나 렌더하는 카피. */
export const accountDeletionPanelCopyStrings: readonly string[] = [
  accountDeletionContent.heading,
  accountDeletionContent.body,
  accountDeletionContent.immediate,
  accountDeletionContent.targetsHeading,
  accountDeletionContent.legalNone,
  accountDeletionContent.difference,
  accountDeletionContent.keptHeading,
  ...accountDeletionContent.kept,
]

/** 확인 대화상자가 렌더해야 하는 카피 전부. */
export const accountDeletionDialogCopyStrings: readonly string[] = [
  accountDeletionContent.dialog.title,
  accountDeletionContent.dialog.effect,
  accountDeletionContent.dialog.removed,
  accountDeletionContent.dialog.keptIntro,
  ...accountDeletionContent.kept,
  accountDeletionContent.dialog.cancel,
  accountDeletionContent.dialog.confirm,
]

/**
 * Java `String.strip()`이 지우는 공백(`Character.isWhitespace`) — 서버 대조(`ActionGrant.normalize`: NFC 뒤 strip)와 같은 정의다.
 * 줄 바꿈 없는 공백(U+00A0·U+2007·U+202F)과 U+FEFF는 공백이 아니라 지우지 않는다. JavaScript `trim()`은 이 넷을 지워 서버와
 * 갈라지므로 쓰지 않는다.
 */
const JAVA_WHITESPACE: ReadonlySet<number> = new Set([
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x1680, 0x2000, 0x2001, 0x2002,
  0x2003, 0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x205f, 0x3000,
])

/** 확인 문구 대조용 정규화 — NFC 뒤 Java `strip()`과 같은 양끝 공백 제거. 대화상자의 대조가 이것 하나를 쓴다. */
export function normalizedPhrase(value: string): string {
  const text = value.normalize('NFC')
  let start = 0
  let end = text.length
  while (start < end && JAVA_WHITESPACE.has(text.charCodeAt(start))) start += 1
  while (end > start && JAVA_WHITESPACE.has(text.charCodeAt(end - 1))) end -= 1
  return text.slice(start, end)
}
