/**
 * 예정 종료 시각 `T`에 자연 만료된 계획의 완료 결과와 종료 후 현재가치 카피(Story 2.14).
 *
 * 완료 결과는 `T` 이후 처음 확인한 정상 현재가치(첫 관측값) 하나로 정해진다. 그 값은 `T` 시각의 평가액이 아니며,
 * 실제 측정 시각과 `T`부터의 지연을 함께 보인다. 지연이 24시간을 넘거나 종료와 관측 사이에 외부 자금흐름이 있으면
 * 완료 비교는 계산 불가다 — 0이나 0%로 대신하지 않는다. 한 번 고정한 첫 관측값과 확정한 비교는 이후 동기화로 바뀌지
 * 않고, 그 뒤의 값은 따로 `종료 후 현재가치` 영역에 보인다.
 *
 * 값을 판정하지 않는다. 완료를 목표의 성패나 투자성과로 말하지 않고, 사실(언제 무엇을 관측했는가), 비교 상태와 그
 * 이유, 다음 행동만 말한다. "실시간"·"정확" 같은 보장, 수익 축하·손실 공포, 스트릭·배지를 쓰지 않는다. 화면의 자리를
 * 위치로 가리키지 않고 이름으로 가리킨다(WCAG 1.3.3). 서버가 보낸 코드는 아래 닫힌 맵으로만 카피에 잇고, 모르는 코드는
 * 글자 그대로 보이지 않고 이 파일의 안전한 카피로 접는다(`copyFor`).
 */

import { NEXT_ACTION_FALLBACK } from './planResult'

/** 계획 상태 `COMPLETED`의 이름. 패널의 표지와 상태 리전 문장이 같은 말을 쓴다. */
const COMPLETED_LABEL = '완료'

/** 완료 결과 영역의 이름. 다른 영역이 이 영역을 이름으로 가리킨다. */
const RESULT_HEADING = '완료 결과'

/** 종료 후 현재가치 영역의 이름. */
const POST_END_HEADING = '종료 후 현재가치'

/** 완료 비교 네 상태의 이름(`PENDING`·`CONFIRMED`·`UNAVAILABLE`·`HELD`). */
const COMPARISONS: Readonly<Record<string, string>> = {
  PENDING: '확인 중',
  CONFIRMED: '확정',
  UNAVAILABLE: '계산 불가',
  HELD: '확정 보류',
}

/** 모르는 비교 상태의 이름. */
const UNKNOWN_COMPARISON = '확인할 수 없음'

export const planCompletionContent = {
  /** 패널 제목 곁의 표지. */
  marker: COMPLETED_LABEL,
  statusLabel: '계획 상태',
  /** 완료 모드의 소개. 패널 소개 자리에 둔다. */
  intro:
    '예정 종료 시각에 끝난 계획의 결과입니다. 완료 결과는 종료 뒤 처음 확인한 정상 현재가치로 정해지며, 그 뒤의 값은 종료 후 현재가치 영역에 따로 보입니다.',
  /** 완료 결과 영역. */
  result: {
    id: 'plan-completion-result',
    headingId: 'plan-completion-result-heading',
    heading: RESULT_HEADING,
    comparisonLabel: '완료 비교',
    comparisons: COMPARISONS,
    unknownComparison: UNKNOWN_COMPARISON,
    reasonLabel: '이유',
    /** `UNAVAILABLE`·`HELD`의 이유. */
    reasons: {
      OBSERVATION_DELAY_EXCEEDED:
        '종료 후 첫 관측이 예정 종료 시각부터 24시간을 넘겨 이루어져 완료 비교를 계산하지 않습니다.',
      POST_END_CASHFLOW:
        '예정 종료 시각과 첫 관측 사이에 외부 입출금이 확인되어 완료 비교를 계산하지 않습니다.',
      HISTORY_OUT_OF_RANGE:
        '다시 읽어야 할 구간이 Bybit에서 조회할 수 있는 최근 2년보다 이르러 완료 비교를 계산하지 않습니다.',
      DATA_GAP:
        '종료 시점에 마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 있어 데이터 공백으로 완료 비교를 계산하지 않습니다.',
      INPUT_REVIEW_REQUIRED:
        '입출금 기록에 검토가 필요한 입력이 있어 완료 비교의 확정을 보류했습니다.',
    } as Record<string, string>,
    unknownReason: '완료 비교의 이유를 확인하지 못했습니다.',
    /** 비교 상태별 다음 행동. 모르는 상태는 `unknownNext`로 접는다. */
    next: {
      PENDING:
        '동기화가 종료 후 첫 정상 현재가치를 확인하고 입출금 기록을 확인하면 완료 비교가 정해집니다. 동기화 버튼은 종료 후 현재가치 영역에 있습니다.',
      HELD: '다시 동기화하면 처음 고정한 첫 관측값을 그대로 다시 써서 입출금 기록을 확인합니다. 더 늦은 관측값으로 바꾸지 않습니다.',
      CONFIRMED: '완료 비교가 확정되었습니다. 이후 동기화로 바뀌지 않습니다.',
      UNAVAILABLE: '완료 비교는 계산하지 않으며, 이후 동기화로 바뀌지 않습니다.',
    } as Record<string, string>,
    unknownNext: '다시 불러와 완료 결과를 확인해 주세요.',
    /**
     * `HELD`인데 서버가 다시 동기화해도 풀리지 않는 원인이라고 판정했을 때(`retryable: false`). 다시 확인을 약속하지
     * 않는다.
     */
    heldFinal:
      '이 원인은 다시 동기화해도 해소되지 않아 완료 비교는 확정 보류로 남습니다. 이후 동기화는 종료 후 현재가치만 확인하며, 새 계획은 새 계획 준비 안내를 따릅니다.',
    scheduledEndAt: '예정 종료 시각 (T)',
    observedMeasuredAt: '종료 후 첫 관측 시각',
    observedMeasuredAtHelp: 'Bybit가 실제로 측정한 시각입니다.',
    delay: '예정 종료부터 첫 관측까지',
    /** 관측이 있을 때만 보인다. */
    delayHelp:
      '24시간을 넘으면 완료 비교를 계산하지 않습니다. 24시간 이하여도 예정 종료 시각과 첫 관측 사이에 외부 입출금이 있으면 계산하지 않습니다. 이 사이의 평가액은 추정하지 않습니다.',
    observedValue: '종료 후 첫 관측값',
    /** 관측값은 `T` 시각의 평가액이 아니다(AC-2). */
    observedValueHelp:
      '예정 종료 시각의 평가액이 아니라, 종료 뒤 처음 확인한 정상 현재가치입니다. 실제 측정 시각과 지연을 함께 보입니다.',
    /** 아직 관측이 없을 때 세 관측 칸이 보이는 말. */
    notObserved: '아직 관측하지 않았습니다',
    completionTarget: '완료 목표 (예정 종료 시각의 조정 최종 목표)',
    completionTargetHelp: '확인된 입출금을 예정 종료 시각까지 반영한 목표 금액입니다.',
    /** 완료 계산이 아직 없을 때. 비교 상태가 왜 없는지는 비교 줄이 말한다. */
    noCompletionTarget: '완료 계산 전',
    postEndCashflows: '종료와 첫 관측 사이의 외부 입출금',
    postEndCashflowCount: (count: number) => `${String(count)}건`,
    /** 완료 결과는 고정이다. */
    fixed:
      '완료 결과는 처음 고정한 첫 관측값으로 정해지며 이후 동기화로 바뀌지 않습니다. 이 계획의 계획 조건·지표·조정 이력·계산 근거는 읽기 전용 기록입니다.',
    /** 중도 종료와의 구분. 목표에 대한 판정을 하지 않는다. */
    distinct:
      '예정 종료 시각의 자연 만료는 중도 종료와 구분되는 기록이며, 목표에 대한 판정을 담지 않습니다.',
  },
  /**
   * 완료 계획의 신선도 원인(`PLAN_COMPLETED`)을 아직 진행 버전이 보일 때(비교가 확인 중이거나 보류) 말하는 문장. 완료
   * 결과가 확정되면 표시된 지표가 완료 계산 버전으로 바뀔 수 있다.
   */
  freshnessProgressCause:
    '예정 종료 시각이 지나 계획이 끝났습니다. 표시된 지표는 종료 전 마지막 공식 버전이며, 완료 결과가 정해지면 완료 계산 버전으로 바뀔 수 있습니다.',
  /** 계산 근거와 조정 이력 두 토글을 묶는 영역. */
  evidenceHeading: '계산 근거와 조정 이력',
  /** 종료 후 현재가치 영역. */
  postEnd: {
    id: 'plan-post-end',
    headingId: 'plan-post-end-heading',
    heading: POST_END_HEADING,
    intro: `${RESULT_HEADING}와 별개로 종료 뒤 동기화로 확인한 현재가치입니다. ${RESULT_HEADING}와 완료 비교에는 쓰이지 않습니다.`,
    value: '현재가치',
    measuredAt: '측정 시각',
    /** 첫 관측 뒤에 새로 확인한 값이 아직 없을 때. 가장 최근 값은 첫 관측값이다. */
    sameAsObservation: `첫 관측 뒤에 새로 확인한 현재가치는 아직 없습니다. 가장 최근 값은 ${RESULT_HEADING}의 종료 후 첫 관측값입니다.`,
    /** 아직 첫 관측도 없을 때. */
    /** 서버가 값을 보냈지만 이 화면이 확인할 수 없었을 때. 값이 없다고 말하지 않는다. */
    unreadable:
      '종료 후 현재가치를 불러오지 못했습니다. 다시 불러오기로 다시 확인할 수 있으며, 완료 결과는 바뀌지 않습니다.',
    notObserved:
      '아직 종료 후 현재가치를 확인하지 않았습니다. 동기화하면 종료 뒤 처음 확인한 정상 현재가치가 완료 결과에 고정됩니다.',
  },
  /** 완료 모드의 다음 행동 자리. 2.14에서는 설명만 있고 실행 버튼이 없다. */
  nextPlan: {
    heading: '새 계획 준비',
    /** `PREPARE_NEXT_PLAN`. 이후 기능을 약속하지 않고 지금 사실만 말한다. */
    text: '이 계획은 예정 종료 시각에 끝났습니다. 이 기록에서는 새 계획을 만들지 않습니다. 새 계획은 종료 뒤 새로 확인한 현재가치와 시각에서 따로 시작합니다.',
    /** `SYNC_FOR_OBSERVATION`. */
    observe:
      '종료 후 현재가치 영역의 동기화 버튼으로 종료 뒤 첫 정상 현재가치를 확인할 수 있습니다. 지금 요청할 수 없으면 그 버튼 곁에 이유가 보입니다. 이 기록에서는 새 계획을 만들지 않습니다.',
  },
  /**
   * 화면의 단일 상태 리전이 대시보드 문장 자리에서 읽는 문장. 완료 결과가 화면에 올 때와 비교 상태가 바뀔 때 한 번씩,
   * 동기화가 끝날 때 한 번 말한다.
   */
  status: {
    /** @param scheduled 서울 시각으로 쓴 예정 종료 시각. @param comparison 완료 비교 상태의 이름. */
    shown: (scheduled: string, comparison: string) =>
      `예정 종료 시각 ${scheduled}에 끝난 계획의 완료 결과를 표시합니다. 완료 비교는 ${comparison}입니다.`,
    /** 동기화가 끝나 완료 결과가 보이거나 바뀌었을 때. */
    synced: (comparison: string) =>
      `동기화를 마쳐 완료 결과를 표시합니다. 완료 비교는 ${comparison}입니다.`,
    /** 완료 비교가 정해진 뒤의 동기화가 끝났을 때. 완료 결과는 그대로다. */
    postEndSynced: `동기화를 마쳐 ${POST_END_HEADING}를 표시합니다. ${RESULT_HEADING}는 바뀌지 않습니다.`,
    /** 완료 비교가 정해진 뒤의 동기화가 끝났지만 새 값을 확인할 수 없었을 때. */
    postEndUnreadable: `동기화를 마쳤지만 ${POST_END_HEADING}를 불러오지 못했습니다. ${RESULT_HEADING}는 바뀌지 않습니다.`,
    /** 완료 비교가 정해진 뒤의 동기화가 끝났고 첫 관측 뒤의 새 값은 아직 없을 때. */
    postEndNone: `동기화를 마쳤습니다. 첫 관측 뒤의 새 ${POST_END_HEADING}는 아직 없으며 ${RESULT_HEADING}는 바뀌지 않습니다.`,
  },
} as const

/** 완료 비교 상태의 이름. 모르는 상태는 안전한 이름으로 접는다. */
export function comparisonName(code: string): string {
  return Object.hasOwn(COMPARISONS, code)
    ? (COMPARISONS[code] ?? UNKNOWN_COMPARISON)
    : UNKNOWN_COMPARISON
}

/**
 * 완료 모드의 다음 행동 코드 → 문장. `SYNC_FOR_OBSERVATION`(첫 관측이 없거나 비교가 아직 정해지지 않았다)과
 * `PREPARE_NEXT_PLAN`(비교가 정해졌다) 둘이고, 모르는 코드는 공통 안전 문장으로 접는다.
 */
export function completionNextActionCopy(code: string): string {
  if (code === 'SYNC_FOR_OBSERVATION') return planCompletionContent.nextPlan.observe
  if (code === 'PREPARE_NEXT_PLAN') return planCompletionContent.nextPlan.text
  return NEXT_ACTION_FALLBACK
}

/** 화면에 나올 수 있는 완료 카피 전부. 시험이 금지어를 한 번에 검사한다. */
export const planCompletionCopyStrings: readonly string[] = [
  planCompletionContent.marker,
  planCompletionContent.statusLabel,
  planCompletionContent.intro,
  planCompletionContent.result.heading,
  planCompletionContent.result.comparisonLabel,
  ...Object.values(planCompletionContent.result.comparisons),
  planCompletionContent.result.unknownComparison,
  planCompletionContent.result.reasonLabel,
  ...Object.values(planCompletionContent.result.reasons),
  planCompletionContent.result.unknownReason,
  ...Object.values(planCompletionContent.result.next),
  planCompletionContent.result.unknownNext,
  planCompletionContent.result.heldFinal,
  planCompletionContent.freshnessProgressCause,
  planCompletionContent.postEnd.unreadable,
  planCompletionContent.status.postEndUnreadable,
  planCompletionContent.status.postEndNone,
  planCompletionContent.result.scheduledEndAt,
  planCompletionContent.result.observedMeasuredAt,
  planCompletionContent.result.observedMeasuredAtHelp,
  planCompletionContent.result.delay,
  planCompletionContent.result.delayHelp,
  planCompletionContent.result.observedValue,
  planCompletionContent.result.observedValueHelp,
  planCompletionContent.result.notObserved,
  planCompletionContent.result.completionTarget,
  planCompletionContent.result.completionTargetHelp,
  planCompletionContent.result.noCompletionTarget,
  planCompletionContent.result.postEndCashflows,
  planCompletionContent.result.postEndCashflowCount(2),
  planCompletionContent.result.fixed,
  planCompletionContent.result.distinct,
  planCompletionContent.evidenceHeading,
  planCompletionContent.postEnd.heading,
  planCompletionContent.postEnd.intro,
  planCompletionContent.postEnd.value,
  planCompletionContent.postEnd.measuredAt,
  planCompletionContent.postEnd.sameAsObservation,
  planCompletionContent.postEnd.notObserved,
  planCompletionContent.nextPlan.heading,
  planCompletionContent.nextPlan.text,
  planCompletionContent.nextPlan.observe,
  planCompletionContent.status.shown('2026년 10월 25일 오후 1:00', '확인 중'),
  planCompletionContent.status.synced('확정'),
  planCompletionContent.status.postEndSynced,
]
