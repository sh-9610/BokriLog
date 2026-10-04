/**
 * 활성 계획의 명시적 중도 종료와 읽기 전용 기록 보존 카피(Story 2.13).
 *
 * 계획 종료는 되돌릴 수 없는 민감 조작이다. 최근 본인 확인(Story 1.8) → `plan-end` 일회성 허가(Story 1.9) →
 * 확인 대화상자 → 확정의 순서로만 일어나고, 확정이 성립하면 계획은 `중도 종료` 기록이 된다. 기록은 종료 직전의
 * 마지막 공식 묶음을 그대로 읽기 전용으로 보인다 — 종료 시각의 평가액을 추정하지 않고, 마지막 정상 측정 시각을
 * 종료 시각으로 바꿔 쓰지 않는다.
 *
 * 값을 판정하지 않는다. 중도 종료를 목표의 성패나 자연 만료의 완료로 말하지 않고, 사실(언제 무엇을 왜 끝냈는가),
 * 보존 범위, 다음 행동만 말한다. "실시간"·"정확" 같은 보장, 수익 축하·손실 공포, 스트릭·배지를 쓰지 않는다.
 * 화면의 자리를 "위의"·"아래의"처럼 위치로 가리키지 않고 이름으로 가리킨다(WCAG 1.3.3). 서버가 보낸 코드는 아래
 * 닫힌 맵으로만 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고 이 파일의 안전한 카피로 접는다(`copyFor`).
 */

/** 준비. 본문은 사유와 지금 보이는 묶음의 참조 세 값이며 금융값을 싣지 않는다. */
export const PLAN_END_PATH = '/api/dashboard/plan-end'

/** 확정. 준비 본문에 서버가 준 확인 문구를 더하고 멱등 키 헤더를 싣는다. */
export const PLAN_END_CONFIRM_PATH = `${PLAN_END_PATH}/confirm`

/** 취소. 본문이 없고 언제나 204다. */
export const PLAN_END_CANCEL_PATH = `${PLAN_END_PATH}/cancel`

/** 종료 사유. `USER_REQUEST`는 사용자 중도 종료, `LONG_GAP_NEW_PLAN`은 장기 미동기화 뒤 새 계획 선택이다. */
export type PlanEndReason = 'USER_REQUEST' | 'LONG_GAP_NEW_PLAN'

/** 거절 뒤 공유하는 꼬리. 어느 거절이든 계획과 공식 결과는 그대로다. */
const KEPT = '계획과 공식 결과는 바뀌지 않았습니다.'

/** 계획 중도 종료 버튼의 이름. 영역의 버튼과 안내 문장이 같은 말을 쓴다. */
const END_ACTION = '계획 중도 종료'

/** 장기 미동기화 선택지의 버튼 이름(`planSync.ts`의 `longGap.newPlanTitle`과 같은 말). */
const NEW_PLAN_ACTION = '새 계획 시작'

/** 기록의 표지. 계획 상태 `ENDED_EARLY`의 이름이다. */
const ENDED_EARLY_LABEL = '중도 종료'

/** 거절 표지 하나: 이름과 설명. */
interface Refusal {
  readonly label: string
  readonly text: string
}

/** 종료 영역이 보이는 거절 표지. 키는 `usePlanEnd`의 표지 종류다. */
const REFUSALS = {
  stale: {
    label: '표시된 공식 결과가 바뀌었습니다',
    text: `그사이 공식 결과가 다른 버전으로 바뀌어 종료하지 않았습니다. 새 결과를 불러온 뒤 다시 요청할 수 있습니다. ${KEPT}`,
  },
  notFound: {
    label: '종료할 활성 계획을 찾지 못했습니다',
    text: '이미 종료됐거나 활성 계획이 아니어서 종료하지 않았습니다. 지금 상태를 다시 불러옵니다.',
  },
  unavailable: {
    label: '지금은 계획을 중도 종료할 수 없습니다',
    text: `서버가 지금은 계획 종료를 받지 않았습니다. ${KEPT}`,
  },
  longGap: {
    label: '장기 미동기화 상태입니다',
    text: `마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 이 계획은 ${END_ACTION} 대신 장기 미동기화 선택지의 ${NEW_PLAN_ACTION}으로 끝냅니다. ${KEPT}`,
  },
  notLongGap: {
    label: '장기 미동기화 상태가 아닙니다',
    text: `지금은 ${NEW_PLAN_ACTION}이 아니라 ${END_ACTION}로 끝낼 수 있습니다. ${KEPT}`,
  },
  notConfirmed: {
    label: '계획을 종료하지 않았습니다',
    text: `확인 시간이 지났거나 확인한 내용이 지금과 맞지 않아 이 확인으로는 종료하지 않았습니다. ${KEPT} 지금 상태를 다시 불러옵니다.`,
  },
  denied: {
    label: '계획 종료를 처리하지 못했습니다',
    text: `이 계정에서 지금은 계획 종료를 받지 않았습니다. ${KEPT}`,
  },
  conflict: {
    label: '계획 종료를 처리하지 못했습니다',
    text: `같은 요청 키가 다른 요청에 쓰여 처리하지 않았습니다. ${KEPT} 다시 요청해 주세요.`,
  },
  // 요청이 서버에 닿았는지, 무엇이 막았는지 단정하지 않는다. 준비는 아무것도 바꾸지 않는다.
  error: {
    label: '계획 종료 요청을 처리하지 못했습니다',
    text: `${KEPT} 잠시 뒤 다시 요청할 수 있습니다.`,
  },
} as const satisfies Readonly<Record<string, Refusal>>

/** 종료 영역의 거절 표지 종류. */
export type PlanEndRefusal = keyof typeof REFUSALS

export const planEndContent = {
  /** 대시보드 패널 끝, 계획 조건 다음의 `계획 종료` 영역(READY). */
  area: {
    id: 'plan-end',
    headingId: 'plan-end-heading',
    heading: '계획 종료',
    /** `AVAILABLE`: 결과와 보존 범위. 이 설명 곁에 보조 버튼 하나를 둔다. */
    text: '계획을 중도 종료하면 되돌릴 수 없습니다. 이 계획은 더 동기화하지 않으며, 마지막 공식 결과와 계획 입력·조정 이력·계산 근거는 읽기 전용 기록으로 남습니다.',
    /** 버튼. 이름은 언제나 동작이다 — 진행 중임은 곁의 문구와 화면의 상태 리전이 말한다. */
    action: END_ACTION,
    /**
     * `NEW_PLAN_CHOICE`: 장기 미동기화. 이 영역에는 버튼을 두지 않고 선택지를 이름으로 가리킨다 — 장기
     * 미동기화에서는 공백을 기록하는 사유 하나만 받는다.
     */
    newPlanChoice: `마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 이 계획은 장기 미동기화 선택지의 ${NEW_PLAN_ACTION} 버튼으로 끝냅니다. 그때 공백 기간은 수집하지 않습니다.`,
    /** `UNAVAILABLE`: 사유만 보인다. */
    unavailableLabel: '지금은 계획을 중도 종료할 수 없습니다',
    reasons: {
      RESTRICTED: '운영 제한으로 지금은 계획 종료를 받지 않습니다.',
      PLAN_PERIOD_ENDED: '예정 종료 시각이 지나 이 계획은 중도 종료할 수 없습니다.',
      // 공식 결과의 측정·확인 시각이 서버 시각보다 앞서 있다(서버 사이 시계 차이, 최대 1분 안팎).
      RESULT_TOO_RECENT:
        '방금 확인한 결과의 측정 시각이 서버 시각보다 늦어 잠시 뒤에 다시 요청할 수 있습니다.',
      // 공식 결과의 기록이 서로 맞지 않는다. 아무것도 끝내지 않았다.
      RESULT_UNAVAILABLE:
        '표시된 공식 결과의 기록을 확인하지 못해 지금은 계획 종료를 받지 않습니다.',
    } as Record<string, string>,
    /** 사유를 모르거나 종료 블록을 읽을 수 없을 때. 금융 결과는 그대로 보이고 종료만 막는다. */
    unknownReason:
      '계획을 중도 종료할 수 있는지 확인하지 못했습니다. 다시 불러오기로 다시 확인할 수 있습니다.',
    /** 진행 중인 작업 때문에 버튼이 막혔을 때의 이유(`aria-describedby`). 두 종료 버튼이 같은 게이트를 쓴다. */
    busy: '동기화·정밀 검증·기존 계획 계속이 진행 중이라 지금은 계획 종료를 요청할 수 없습니다. 작업이 끝난 뒤 다시 요청할 수 있습니다.',
    /** 준비 요청이 도는 동안 버튼 곁의 문구. 버튼이 막힌 이유이기도 하다. */
    preparing: '계획 종료를 준비하는 중',
    /** 확인 대화상자가 열려 있는 동안 두 종료 버튼이 막힌 이유. */
    dialogOpen: '계획 종료 확인 대화상자가 열려 있어 새로 요청할 수 없습니다.',
    /** 본인 확인 화면으로 이동하는 동안 두 종료 버튼이 막힌 이유. */
    leaving: '본인 확인 화면으로 이동하고 있어 지금은 계획 종료를 요청할 수 없습니다.',
    /** 버튼 id. 대화상자가 호출점을 잃었을 때(다시 마운트된 뒤) 초점을 돌려줄 자리다. */
    actionId: 'plan-end-action',
  },
  /** 장기 미동기화 선택지의 `새 계획 시작` 버튼 id. 대화상자가 그 사유로 열렸을 때 초점을 돌려줄 자리다. */
  newPlanActionId: 'plan-new-plan-action',
  /**
   * 동기화 블록은 장기 미동기화인데 서버의 종료 판정은 장기 미동기화가 아니라고 할 때(두 블록 사이의 짧은 경합),
   * `새 계획 시작` 곁의 이유.
   */
  notLongGapReason: `서버는 이 계획을 장기 미동기화로 보지 않아 ${NEW_PLAN_ACTION} 대신 계획 종료 영역의 ${END_ACTION}로 끝낼 수 있습니다.`,
  /** 거절 뒤 공유하는 꼬리. 이유를 아는 사용 불가 거절이 이 문장으로 끝난다. */
  kept: KEPT,
  /**
   * 본인 확인이 필요할 때 종료 영역에 보이는 안내와 세 제공자 버튼. 자동으로 이어 하지 않는다 — 확인을 마치고
   * 돌아온 사용자가 같은 버튼을 다시 누른다.
   */
  reauth: {
    label: '본인 확인이 필요합니다',
    text: `계획 종료는 최근 본인 확인이 필요한 조작입니다. 이 계정에 연결된 로그인 수단으로 본인 확인을 마치면 로그인 상태 화면으로 돌아오며, 그 뒤 ${END_ACTION}나 ${NEW_PLAN_ACTION}을 다시 눌러 주세요. ${KEPT}`,
  },
  refusals: REFUSALS,
  /** 종료 영역의 사용 불가 사유와 같은 닫힌 맵을 거절 사유에도 쓴다. */
  unknownRefusalReason: '지금은 계획 종료를 받지 않습니다.',
  /**
   * 확인 대화상자(DESIGN.md `confirmation-dialog`). 대상과 비가역 결과를 먼저 말하고, 보존되는 것과 이후에
   * 일어나는 일을 문단으로 나눈다. 기본 포커스는 취소다.
   */
  dialog: {
    titleId: 'plan-end-dialog-title',
    descriptionId: 'plan-end-dialog-description',
    progressId: 'plan-end-dialog-progress',
    titles: {
      USER_REQUEST: '이 계획을 중도 종료할까요?',
      LONG_GAP_NEW_PLAN: '새 계획 시작을 위해 이 계획을 종료할까요?',
    } as Record<PlanEndReason, string>,
    periodLabel: '대상 계획 기간',
    /** 비가역 결과. 무엇이 끝나는지 먼저 말한다. */
    irreversible:
      '종료하면 되돌릴 수 없습니다. 이 계획은 다시 활성 계획이 되지 않고, 계획 조건과 입력도 바꿀 수 없습니다.',
    /** 실제 종료 시각은 이 화면이 아니라 서버가 확정할 때 정한다. */
    endTime: '실제 종료 시각은 확정을 누를 때 서버가 정합니다.',
    /** 마지막 정상값은 그대로 남고 종료 시각의 평가액이 되지 않는다(AC-4). */
    lastNormal:
      '마지막 정상값과 그 측정 시각은 그대로 남으며, 종료 시각의 평가액으로 바꾸거나 새로 추정하지 않습니다.',
    /** 보존되는 것. */
    kept: '계획 입력·조정 이력·계산 근거는 읽기 전용 기록으로 남습니다.',
    /** 종료 뒤에 일어나는 일. 새 계획은 이 조작이 만들지 않는다. */
    after:
      '종료한 뒤에는 이 계획을 동기화하지 않습니다. 새 계획은 새 현재가치와 시각에서 따로 시작합니다.',
    /** 자연 만료와의 구분(AC-2). 목표에 대한 판정을 하지 않는다. */
    distinct:
      '중도 종료는 예정 종료 시각의 자연 만료와 구분되는 기록이며, 목표에 대한 판정을 담지 않습니다.',
    /** 장기 미동기화 뒤 새 계획 선택에서만. */
    longGap:
      '마지막 외부 자금흐름 확인 뒤의 공백 기간은 수집하지 않으며, 이 계획의 완료 비교는 데이터 공백으로 계산 불가가 됩니다.',
    /** 최종 확인. 영역의 버튼과 다른 이름이다 — 두 조작이 같은 이름이면 무엇을 누르는지 구분되지 않는다. */
    confirm: '중도 종료 확정',
    /** 취소. 기본 포커스가 여기 있다. */
    cancel: '취소',
    /** 확정이 도는 동안. 대화상자가 막히고 이 문장이 확정 버튼을 설명한다. */
    confirming: '중도 종료를 확정하는 중입니다.',
    /** 응답을 받지 못했다. 같은 요청(같은 멱등 키)으로 다시 확인할 수 있다. */
    uncertain:
      '응답을 받지 못해 계획이 종료됐는지 알 수 없습니다. 중도 종료 확정을 다시 누르면 같은 요청으로 다시 확인하고, 이미 종료됐다면 같은 결과를 돌려받습니다. 취소하면 지금 상태를 다시 불러옵니다.',
  },
  /** 중도 종료된 계획의 기록(ENDED). 같은 패널의 기록 모드다. */
  record: {
    id: 'plan-end-record',
    headingId: 'plan-end-record-heading',
    heading: '종료 기록',
    marker: ENDED_EARLY_LABEL,
    intro:
      '중도 종료한 계획의 마지막 공식 결과입니다. 값은 종료 전 마지막 정상 묶음 그대로이며 읽기 전용 기록입니다.',
    statusLabel: '계획 상태',
    reasonLabel: '종료 사유',
    reasons: {
      USER_REQUEST: '사용자 중도 종료',
      LONG_GAP_NEW_PLAN: '장기 미동기화 후 새 계획 선택',
    } as Record<string, string>,
    unknownReason: '확인할 수 없는 사유',
    endedAt: '실제 종료 시각',
    endedAtHelp: '확정할 때 서버가 정한 시각입니다.',
    scheduledEndAt: '예정 종료 시각 (T)',
    lastNormalValue: '마지막 정상 현재가치',
    lastNormalMeasuredAt: '마지막 정상 측정 시각',
    delay: '측정부터 종료까지',
    delayHelp:
      '마지막 정상 측정 시각부터 실제 종료 시각까지의 간격입니다. 이 사이의 평가액은 추정하지 않습니다.',
    completion: '완료 비교',
    completions: {
      NOT_APPLICABLE: '해당 없음 · 중도 종료는 완료 비교 대상이 아닙니다',
      UNAVAILABLE_DATA_GAP: '데이터 공백으로 계산 불가',
    } as Record<string, string>,
    unknownCompletion: '확인할 수 없음',
    uncollected: '미수집 구간',
    uncollectedHelp: '이 구간의 입출금 기록은 수집하지 않았습니다.',
    readOnly:
      '이 계획의 계획 조건·지표·조정 이력·계산 근거는 읽기 전용 기록입니다. 이 계획은 더 동기화하지 않습니다.',
  },
  /** 기록 모드의 다음 행동 자리. 2.13에서는 설명만 있고 실행 버튼이 없다. */
  nextPlan: {
    heading: '새 계획 준비',
  },
  /**
   * 확정했지만 정해진 횟수만큼 다시 읽어도 종료 기록이 화면에 오지 않았을 때(응답 실패, 늦은 읽기). 화면은 다시
   * 불러오기를 한 번 한다.
   */
  endedNotShown: {
    label: '종료 기록을 불러오지 못했습니다',
    text: '계획 중도 종료는 확정됐지만 종료 기록을 화면에 불러오지 못했습니다. 다시 불러오는 중이며, 계속 보이지 않으면 다시 불러오기를 눌러 주세요.',
  },
  /** 응답 없는 확정 뒤에 대화상자를 닫았을 때. 계획이 끝났는지 모르므로 다시 읽는다. */
  uncertain: {
    label: '계획 종료 여부를 확인하지 못했습니다',
    text: '확정 요청의 응답을 받지 못해 계획이 종료됐는지 확인하지 못했습니다. 종료 여부를 확인하지 못해 다시 불러옵니다.',
  },
  /**
   * 화면의 단일 상태 리전이 읽는 문장. 대시보드 문장 곁의 한 요소이며 한 조작의 시작과 끝에 한 번씩만 바뀐다.
   * 확정의 끝은 이 요소가 아니라 대시보드 문장이 말한다 — 기록이 화면에 오는 순간 한 번이다(`ended`).
   */
  status: {
    preparing: '계획 종료를 준비하는 중입니다.',
    reauthRequired: `본인 확인이 필요합니다. 계획 종료 영역의 본인 확인 버튼으로 확인한 뒤 다시 요청해 주세요. ${KEPT}`,
    cancelled: '계획 중도 종료를 취소했습니다. 바뀐 것은 없습니다.',
    /** @param endedAt 서울 시각으로 쓴 실제 종료 시각. */
    ended: (endedAt: string) =>
      `중도 종료한 계획의 기록을 표시합니다. 실제 종료 시각은 ${endedAt}입니다. 이 계획은 더 동기화하지 않습니다.`,
  },
} as const

/**
 * 측정부터 종료까지의 간격. 일·시간·분으로 내리며 0인 단위는 말하지 않는다. 1분 미만이면 그렇게 말한다 —
 * 화면은 서버가 계산한 밀리초를 그대로 쓰고 자기 시계와 비교하지 않는다.
 */
export function formatDelay(millis: number): string {
  const minutes = Math.floor(Math.max(0, millis) / 60_000)
  if (minutes < 1) return '1분 미만'
  const days = Math.floor(minutes / (24 * 60))
  const hours = Math.floor((minutes % (24 * 60)) / 60)
  const rest = minutes % 60
  return [
    days > 0 ? `${String(days)}일` : null,
    hours > 0 ? `${String(hours)}시간` : null,
    rest > 0 ? `${String(rest)}분` : null,
  ]
    .filter((part) => part !== null)
    .join(' ')
}

/** 화면에 나올 수 있는 종료 카피 전부. 시험이 금지어를 한 번에 검사한다. */
export const planEndCopyStrings: readonly string[] = [
  planEndContent.area.heading,
  planEndContent.area.text,
  planEndContent.area.action,
  planEndContent.area.newPlanChoice,
  planEndContent.area.unavailableLabel,
  ...Object.values(planEndContent.area.reasons),
  planEndContent.area.unknownReason,
  planEndContent.area.busy,
  planEndContent.area.preparing,
  planEndContent.area.dialogOpen,
  planEndContent.area.leaving,
  planEndContent.notLongGapReason,
  planEndContent.kept,
  planEndContent.endedNotShown.label,
  planEndContent.endedNotShown.text,
  planEndContent.uncertain.label,
  planEndContent.uncertain.text,
  planEndContent.reauth.label,
  planEndContent.reauth.text,
  ...Object.values(planEndContent.refusals).flatMap((refusal) => [refusal.label, refusal.text]),
  planEndContent.unknownRefusalReason,
  ...Object.values(planEndContent.dialog.titles),
  planEndContent.dialog.periodLabel,
  planEndContent.dialog.irreversible,
  planEndContent.dialog.endTime,
  planEndContent.dialog.lastNormal,
  planEndContent.dialog.kept,
  planEndContent.dialog.after,
  planEndContent.dialog.distinct,
  planEndContent.dialog.longGap,
  planEndContent.dialog.confirm,
  planEndContent.dialog.cancel,
  planEndContent.dialog.confirming,
  planEndContent.dialog.uncertain,
  planEndContent.record.heading,
  planEndContent.record.marker,
  planEndContent.record.intro,
  planEndContent.record.statusLabel,
  planEndContent.record.reasonLabel,
  ...Object.values(planEndContent.record.reasons),
  planEndContent.record.unknownReason,
  planEndContent.record.endedAt,
  planEndContent.record.endedAtHelp,
  planEndContent.record.scheduledEndAt,
  planEndContent.record.lastNormalValue,
  planEndContent.record.lastNormalMeasuredAt,
  planEndContent.record.delay,
  planEndContent.record.delayHelp,
  planEndContent.record.completion,
  ...Object.values(planEndContent.record.completions),
  planEndContent.record.unknownCompletion,
  planEndContent.record.uncollected,
  planEndContent.record.uncollectedHelp,
  planEndContent.record.readOnly,
  planEndContent.nextPlan.heading,
  planEndContent.status.preparing,
  planEndContent.status.reauthRequired,
  planEndContent.status.cancelled,
  planEndContent.status.ended('2026년 10월 3일 오전 10:00'),
]
