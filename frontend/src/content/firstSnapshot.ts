export const FIRST_SNAPSHOT_PATH = '/api/snapshots/first'

export const firstSnapshotContent = {
  heading: '첫 자산 확인',
  start: '첫 자산 확인',
  retry: '다시 확인',
  checking: '현재가치를 확인하는 중입니다.',
  noValue: '첫 공식 현재가치가 아직 없습니다.',
  queued: '첫 자산 확인을 기다리고 있습니다.',
  running: 'Bybit 현재가치를 확인하고 있습니다.',
  succeeded: '첫 공식 현재가치를 확인했습니다.',
  blocked: '계정 또는 연결 상태가 바뀌어 확인을 중단했습니다. 설정을 확인해 주세요.',
  failed: '현재가치를 확인하지 못했습니다.',
  unavailable: '확인 상태를 불러오지 못했습니다. 다시 확인해 주세요.',
  unknownFailure: '연결과 Bybit 응답을 확인한 뒤 직접 다시 요청해 주세요.',
  connectionSettings: 'Bybit 연결 설정',
  refresh: '상태 다시 확인',
  unconnected: 'Bybit 연결을 먼저 완료해 주세요.',
  cannotPlan: '현재가치가 0 이하이므로 이 값으로 계획을 만들 수 없습니다.',
  measured: 'Bybit 측정 시각',
  official: '공식 현재가치',
  axes: [
    // 상태명은 EXPERIENCE의 소스 용어다(최신·동기화 중·최신 아님 / 확인됨·검토 필요·사용 불가 /
    // 공식·추정·계산 불가). 활성 계획이 생기면 결과 패널의 세 축이 이 자리를 대신한다.
    {
      name: '신선도',
      state: '최신 아님',
      cause: '외부 자금흐름 확인 시각이 아직 없습니다.',
      impact: '현재가치',
      action: '계획 조건을 확인한 뒤 초기 입력을 수집해 주세요.',
    },
    {
      name: '입력 신뢰도',
      state: '확인됨',
      cause: 'Bybit 지갑 응답을 검증했습니다.',
      impact: '현재가치',
      action: '측정 시각을 확인해 주세요.',
    },
    {
      name: '계산 성격',
      state: '계산 불가',
      cause: '활성 계획이 없어 계획 지표를 계산하지 않았습니다.',
      impact: '계획 지표',
      action: '계획 조건을 확인한 뒤 최초 계획을 계산·발행해 주세요.',
    },
  ],
} as const

export const failureActions: Record<string, string> = {
  AUTHENTICATION_REJECTED: 'Bybit API 키를 확인해 주세요.',
  INSUFFICIENT_PERMISSION: 'Bybit 읽기 권한을 확인해 주세요.',
  WRITE_PERMISSION: '읽기 전용 키를 새로 발급해 주세요.',
  IP_NOT_BOUND: '허용 IP 설정을 확인해 주세요.',
  SUB_ACCOUNT: '메인 UID의 키를 사용해 주세요.',
  TIMEOUT: '잠시 후 직접 다시 요청해 주세요.',
  RATE_LIMITED: '호출 제한이 풀린 뒤 직접 다시 요청해 주세요.',
  PROVIDER_UNAVAILABLE: 'Bybit 상태를 확인한 뒤 다시 요청해 주세요.',
  RESPONSE_FORMAT: '응답을 확인할 수 없습니다. 잠시 후 다시 요청해 주세요.',
  WALLET_UNAVAILABLE: 'Bybit 지갑 조회 권한과 상태를 확인해 주세요.',
}
