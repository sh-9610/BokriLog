/**
 * 활성 계획의 사용자 시점 증분 동기화 카피(Story 2.11).
 *
 * 동기화는 세 트리거에서만 접수된다 — 대시보드 진입, 5분 이상 숨겨 둔 탭으로 돌아옴, `동기화` 버튼. 상시
 * 폴링도 전 사용자 배치도 없고, 끝나지 않은 동기화를 자동으로 다시 시도하지 않는다. 진행 중에는 마지막 정상
 * 묶음이 그대로 보이고, 끝나면 대시보드 응답 하나로 한 번에 바뀐다(EXPERIENCE 원자적 갱신 계약).
 *
 * 값을 판정하지 않는다. 사실(무엇을 확인했는가), 원인, 다음 행동만 말한다. "실시간"·"정확" 같은 보장,
 * 수익 축하·손실 공포, 투자 성공·실패로 읽힐 말, 스트릭·배지를 쓰지 않는다. 서버가 보낸 코드는 아래 닫힌
 * 맵으로만 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고 이 파일의 안전한 카피로 접는다(`copyFor`).
 */

/** 동기화 접수. 본문은 트리거 하나이며 금융값·식별자를 싣지 않는다. */
export const DASHBOARD_SYNC_PATH = '/api/dashboard/sync'

/** 상태 리전 문장이 공유하는 꼬리. 동기화가 공식 값을 바꾸지 않은 모든 결과가 같은 말로 끝난다. */
const KEPT = '기존 공식 결과는 그대로 유지됩니다.'

/** 장기 미동기화 상태의 이름. 설명 블록과 상태 리전 문장이 같은 말을 쓴다. */
const LONG_GAP_LABEL = '장기 미동기화·선택 필요'

export const planSyncContent = {
  /**
   * 동기화 버튼. 이름은 언제나 동작이다 — 진행 중임은 버튼 옆 문구와 화면의 상태 리전이 말한다(버튼 안
   * 문구를 바꾸면 컨트롤의 이름이 동작이 아니라 상태가 된다).
   */
  action: '동기화',
  running: '동기화 중',
  runningHelp:
    '현재가치와 입출금 기록을 다시 확인하고 있습니다. 끝날 때까지 표시된 공식 결과는 바뀌지 않습니다.',
  /** 최근 사건 안내(실패 저하 계약). 동기화 버튼 곁에 늘 둔다. */
  recentEvents:
    '최근 입출금은 거래소 기록 지연으로 아직 보이지 않을 수 있습니다. 동기화가 끝난 뒤에도 보이지 않으면 잠시 뒤 다시 동기화해 주세요.',
  /** 수동 동기화를 다시 받을 때까지 남은 시간. 서버가 준 상대 초에서 이 기기가 센다. */
  cooldown: (seconds: number) => `${String(seconds)}초 뒤 다시 동기화할 수 있습니다.`,
  unavailableLabel: '지금은 동기화할 수 없습니다',
  /** 대시보드 `sync.reason`(사용 불가 사유). */
  reasons: {
    RESTRICTED: '운영 제한으로 지금은 동기화를 받지 않습니다.',
    CONNECTION_UNAVAILABLE:
      'Bybit 연결을 확인할 수 없어 동기화할 수 없습니다. 설정 화면에서 연결 상태를 확인해 주세요.',
    PLAN_PERIOD_ENDED: '계획 종료 시각이 지나 이 계획은 더 동기화하지 않습니다.',
    // Story 2.12: 같은 기준 계산의 검증 작업이 검토 필요로 끝나 결과를 보류하고 있다. 동기화해도 바뀌지 않으므로
    // 받지 않는다. 사용자가 풀 수 없는 원인이라 다시 요청하라고 권하지 않는다.
    REVIEW_HELD:
      '같은 기준 계산에서 입력 검토가 필요한 상태로 끝난 작업이 결과를 보류하고 있어 동기화를 받지 않습니다. 다시 요청해도 바뀌지 않으며, 원인과 확인 대상 구간은 신뢰 상태의 입력 신뢰도에 있습니다.',
  },
  /** 사유를 모르거나 동기화 블록을 읽을 수 없을 때. 금융 결과는 그대로 보이고 동기화만 막는다. */
  unknownReason: '동기화할 수 있는지 확인하지 못했습니다. 다시 불러오기로 다시 확인할 수 있습니다.',
  /**
   * 버튼이 진행 중인 작업 때문에 막혔을 때의 이유(`aria-describedby`). 다른 탭·자동 트리거가 시작한 작업을
   * 이 화면이 따라가는 중이거나, 이 화면이 더 따라가지 않는 작업이 아직 진행 중일 때다. 더 따라가지 않는 작업은
   * 그 목적으로 부른다(Story 2.12: 같은 작업 표에 정밀 검증·기존 계획 계속도 있다).
   */
  inProgressElsewhere:
    '다른 곳에서 시작한 동기화가 진행 중입니다. 끝날 때까지 새로 요청할 수 없고, 끝나면 이 화면이 결과를 표시합니다.',
  inProgressUnfollowed: {
    SYNC: '동기화가 아직 진행 중이라 새로 요청할 수 없습니다. 다시 불러오기로 진행 상태를 확인할 수 있습니다.',
    VERIFY:
      '정밀 검증이 아직 진행 중이라 동기화를 새로 요청할 수 없습니다. 다시 불러오기로 진행 상태를 확인할 수 있습니다.',
    CATCH_UP:
      '기존 계획 계속이 아직 진행 중이라 동기화를 새로 요청할 수 없습니다. 다시 불러오기로 진행 상태를 확인할 수 있습니다.',
  },
  /** 정밀 검증·기존 계획 계속이 진행 중일 때(Story 2.12). 목적과 무관하게 진행 작업은 하나다. */
  inProgressOther:
    '정밀 검증이나 기존 계획 계속이 진행 중이라 동기화를 새로 요청할 수 없습니다. 끝나면 이 화면이 결과를 표시합니다.',
  /**
   * 동기화 오류 요약. 사용자가 누른 동기화가 공식 결과를 바꾸지 못하고 끝나면 초점이 여기로 온다. 마지막
   * 작업의 상태·원인·단계·시각을 서버가 판정한 코드 그대로 보인다.
   */
  summary: {
    statuses: {
      FAILED: '동기화하지 못했습니다',
      NEEDS_REVIEW: '동기화 입력 검토 필요',
      BLOCKED: '동기화를 마치지 못했습니다',
    },
    unknownStatus: '동기화 상태를 확인하지 못했습니다',
    kept: KEPT,
    causeLabel: '원인',
    stageLabel: '멈춘 단계',
    triggerLabel: '요청',
    finishedLabel: '종료 시각',
    /** 끝나지 못한 이유. 상태별 닫힌 집합이며, 모르는 코드는 `unknownCode`로 접는다. */
    codes: {
      PROVIDER_UNAVAILABLE:
        'Bybit 응답을 받지 못했습니다(시간 초과·요청 한도·일시 장애). 잠시 뒤 다시 동기화할 수 있습니다.',
      SOURCE_REJECTED:
        'Bybit가 조회를 거절했습니다. 연결의 API 키 권한과 상태를 확인한 뒤 다시 동기화해 주세요.',
      CONVERSION_REQUIRED:
        '사건 시점의 USD 가격을 확인하지 못해 환산을 보류했습니다. 사건 분이 아직 닫히지 않았다면 잠시 뒤 다시 동기화할 수 있습니다.',
      CALCULATION_RANGE_EXCEEDED:
        '계산 값이 허용된 정밀도 범위를 벗어나 공식 결과를 바꾸지 않았습니다.',
      CALCULATION_INPUT_INVALID: '계산 입력의 형식을 확인할 수 없어 공식 결과를 바꾸지 않았습니다.',
      CALCULATION_POLICY_UNVERIFIED: '계산 정책의 고정 예제를 확인하지 못해 계산을 멈췄습니다.',
      SYNC_INTERRUPTED:
        '처리가 중간에 멈춰 아무것도 발행하지 않았습니다. 다시 동기화할 수 있습니다.',
      SYNC_FAILED: '동기화를 끝내지 못했습니다. 다시 동기화할 수 있습니다.',
      PROVIDER_TIME_UNTRUSTED:
        'Bybit 기준 시각을 확인하지 못해 동기화하지 않았습니다. 잠시 뒤 다시 동기화할 수 있습니다.',
      SOURCE_REQUIRES_REVIEW: 'Bybit 응답의 형식을 확인할 수 없어 입력을 검토해야 합니다.',
      CLASSIFICATION_REQUIRES_REVIEW:
        '외부 자금흐름으로 판정할 수 없는 사건이 있어 입력을 검토해야 합니다.',
      CONVERSION_REQUIRES_REVIEW:
        '사건 시점 USD 환산을 확인할 수 없는 사건이 있어 입력을 검토해야 합니다.',
      NOT_PERMITTED: '계정·연결 상태나 운영 제한이 동기화를 허용하지 않았습니다.',
      PLAN_CHANGED: '동기화하는 사이 활성 계획이 바뀌어 발행하지 않았습니다.',
      MEMBERSHIP_CHANGED: '포트폴리오의 연결 구성이 바뀌어 발행하지 않았습니다.',
      POINTER_CONFLICT: '공식 결과가 그사이 다른 버전으로 바뀌어 발행하지 않았습니다.',
      INPUTS_CHANGED: '동기화 입력이 그사이 바뀌어 발행하지 않았습니다. 다시 동기화할 수 있습니다.',
      LONG_GAP: '마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 동기화하지 않았습니다.',
      PLAN_PERIOD_ENDED: '계획 종료 시각이 지나 동기화하지 않았습니다.',
      // Story 2.14: 예정 종료 직후, Bybit 기준 시각이나 현재가치 측정 시각이 아직 예정 종료 시각에 이르지 않았다.
      PROVIDER_END_NOT_REACHED:
        'Bybit 기준 시각이 아직 예정 종료 시각에 이르지 않아 종료 후 현재가치를 확인하지 않았습니다. 잠시 후 다시 동기화할 수 있습니다.',
      // Story 2.12: 공식 사건의 소실은 이제 검토 필요다. 조회 가능 범위 밖의 구간은 다시 구성하지 않는다.
      SOURCE_EVENT_MISSING:
        '이미 공식 계산에 쓴 입출금 기록이 다시 읽은 구간에서 보이지 않아 입력을 검토해야 합니다.',
      HISTORY_OUT_OF_RANGE:
        '다시 읽어야 할 구간이 Bybit에서 조회할 수 있는 최근 2년보다 이르러 다시 구성할 수 없습니다.',
    },
    unknownCode: '동기화를 끝내지 못한 원인을 확인하지 못했습니다.',
    /** 작업이 멈춘 단계. */
    stages: {
      TIME: '기준 시각 확인',
      COLLECTION: '입출금 기록 수집',
      FINAL_SWEEP: '발행 직전 최근 기록 재확인',
      CLASSIFICATION: '외부 자금흐름 판정',
      CONVERSION: '사건 시점 USD 환산',
      CALCULATION: '계획 계산',
      PUBLICATION: '공식 결과 발행',
      // Story 2.14: 예정 종료 뒤 첫 정상 현재가치를 확인하는 단계.
      OBSERVATION: '종료 후 첫 현재가치 관측',
    },
    unknownStage: '확인할 수 없는 단계',
    /** 작업을 접수한 트리거. */
    triggers: {
      ENTRY: '대시보드 진입',
      VISIBILITY: '탭으로 돌아옴',
      MANUAL: '사용자 요청',
      SYSTEM: '서비스 요청',
    },
    unknownTrigger: '확인할 수 없는 요청',
  },
  /** 접수 자체가 성립하지 않았거나 결과를 끝까지 따라가지 못했을 때의 요약(이 화면이 판정한 것). */
  local: {
    // 마지막 접수가 이 화면의 요청이라고 말하지 않는다 — 다른 탭이나 자동 트리거의 것일 수 있다.
    cooldown: {
      label: '아직 다시 동기화할 수 없습니다',
      text: `최근 동기화 요청이 있어 잠시 뒤에 다시 요청할 수 있습니다. ${KEPT}`,
    },
    // 서버의 사유 줄이 보이면 이 요약은 그리지 않는다(같은 이름을 두 번 보이지 않는다).
    unavailable: {
      label: '지금은 동기화할 수 없습니다',
      text: `서버가 지금은 동기화를 받지 않았습니다. ${KEPT}`,
    },
    // 요청이 서버에 닿았는지 단정하지 않는다. 무엇이 일어났든 기존 정상값은 그대로다.
    error: {
      label: '동기화 요청을 처리하지 못했습니다',
      text: `${KEPT} 잠시 뒤 다시 동기화할 수 있습니다.`,
    },
    stalled: {
      label: '동기화 결과를 아직 확인하지 못했습니다',
      text: `이 화면이 진행 상태를 더 확인하지 않습니다. 다시 불러오기로 결과를 확인해 주세요. ${KEPT}`,
    },
  },
  /**
   * 장기 미동기화(마지막 확인 뒤 30일 초과). 동기화 버튼 대신 이 설명과 두 버튼을 둔다 — `기존 계획 계속`(Story
   * 2.12)과 `새 계획 시작`(Story 2.13). 두 방법은 수집하는 데이터 범위가 다르다. `새 계획 시작`은 지금 계획을
   * 장기 미동기화 후 새 계획 선택으로 중도 종료하는 흐름(본인 확인 → 확인 대화상자 → 확정)을 연다. 새 계획을
   * 만드는 조작은 아니다.
   */
  longGap: {
    label: LONG_GAP_LABEL,
    text: '마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 동기화를 시작하지 않습니다. 이어 가는 방법은 두 가지이며, 둘은 수집하는 데이터 범위가 다릅니다.',
    continueTitle: '기존 계획 계속',
    continueText:
      '마지막 확인 시각 앞 7일부터 지금까지의 입출금 기록을 모두 수집해 따라잡은 뒤, 같은 계획 조건으로 비교를 이어 갑니다.',
    /** 수집 범위. 시작은 서버가 준 `catchUpFrom`이고 끝은 요청이 처리되는 시점이다. */
    continueRangeLabel: '수집 범위',
    continueRangeUntil: '지금까지',
    continueCost:
      '공백 기간을 7일씩 나눠 모두 읽고 발행 직전 최근 7일을 한 번 더 확인하므로 몇 분 이상 걸릴 수 있습니다. 끝날 때까지 표시된 결과는 읽기 전용으로 유지됩니다.',
    /** `기존 계획 계속` 버튼. 이름은 언제나 동작이다. */
    continueAction: '기존 계획 계속',
    newPlanTitle: '새 계획 시작',
    newPlanText:
      '공백 기간의 기록은 수집하지 않습니다. 지금 계획은 중도 종료되어 완료 비교가 데이터 공백으로 계산 불가가 되고, 새 계획은 새 현재가치와 시각에서 따로 시작합니다.',
    /** `새 계획 시작` 버튼(Story 2.13). 이름은 언제나 동작이다. */
    newPlanAction: '새 계획 시작',
    /** 서버가 지금 이 선택을 받지 않을 때(`planEnd`가 장기 미동기화 선택이 아님). 사유는 종료 영역의 맵으로 잇는다. */
    newPlanUnavailableLabel: '지금은 새 계획 시작을 요청할 수 없습니다',
    readOnly: '마지막 정상 결과는 읽기 전용으로 유지됩니다.',
  },
  /**
   * 화면의 단일 상태 리전이 대시보드 문장 자리에서 읽는 동기화 문장. 한 동기화의 시작과 끝에 한 번씩만
   * 바뀐다 — 진행 중 폴링은 이 문장도, "다시 불러오는 중"도 만들지 않는다.
   */
  status: {
    syncing: '동기화 중입니다. 끝날 때까지 표시된 공식 결과는 바뀌지 않습니다.',
    synced: (basis: string) =>
      `동기화를 마쳐 새 공식 결과를 표시합니다. 계산 기준 시각은 ${basis}입니다.`,
    syncedWithReliability: (reliability: string, basis: string) =>
      `동기화를 마쳐 새 공식 결과를 표시합니다. 입력 신뢰도는 ${reliability}이며 계산 기준 시각은 ${basis}입니다.`,
    failed: `동기화하지 못했습니다. ${KEPT}`,
    review: `동기화한 입력에 검토가 필요해 공식 결과를 바꾸지 않았습니다. ${KEPT}`,
    blocked: `동기화를 마치지 못해 공식 결과를 바꾸지 않았습니다. ${KEPT}`,
    cooldown: `최근 동기화 요청이 있어 아직 다시 요청할 수 없습니다. ${KEPT}`,
    longGap: `${LONG_GAP_LABEL}: 마지막 확인 뒤 30일이 넘게 지나 동기화하지 않았습니다. 마지막 정상 결과는 읽기 전용으로 유지됩니다.`,
    unavailable: `지금은 동기화를 받지 않습니다. ${KEPT}`,
    error: `동기화 요청을 처리하지 못했습니다. ${KEPT}`,
    stalled: '동기화 결과를 아직 확인하지 못했습니다. 다시 불러오기로 확인할 수 있습니다.',
  },
} as const

/** 화면에 나올 수 있는 동기화 카피 전부. 시험이 금지어를 한 번에 검사한다. */
export const planSyncCopyStrings: readonly string[] = [
  planSyncContent.action,
  planSyncContent.running,
  planSyncContent.runningHelp,
  planSyncContent.recentEvents,
  planSyncContent.cooldown(30),
  planSyncContent.unavailableLabel,
  ...Object.values(planSyncContent.reasons),
  planSyncContent.unknownReason,
  planSyncContent.inProgressElsewhere,
  ...Object.values(planSyncContent.inProgressUnfollowed),
  planSyncContent.inProgressOther,
  ...Object.values(planSyncContent.summary.statuses),
  planSyncContent.summary.unknownStatus,
  planSyncContent.summary.kept,
  planSyncContent.summary.causeLabel,
  planSyncContent.summary.stageLabel,
  planSyncContent.summary.triggerLabel,
  planSyncContent.summary.finishedLabel,
  ...Object.values(planSyncContent.summary.codes),
  planSyncContent.summary.unknownCode,
  ...Object.values(planSyncContent.summary.stages),
  planSyncContent.summary.unknownStage,
  ...Object.values(planSyncContent.summary.triggers),
  planSyncContent.summary.unknownTrigger,
  ...Object.values(planSyncContent.local).flatMap((entry) => [entry.label, entry.text]),
  ...Object.values(planSyncContent.longGap),
  planSyncContent.status.syncing,
  planSyncContent.status.synced('2026-09-29 10:00'),
  planSyncContent.status.syncedWithReliability('검토 필요', '2026-09-29 10:00'),
  planSyncContent.status.failed,
  planSyncContent.status.review,
  planSyncContent.status.blocked,
  planSyncContent.status.cooldown,
  planSyncContent.status.longGap,
  planSyncContent.status.unavailable,
  planSyncContent.status.error,
  planSyncContent.status.stalled,
]
