/*
 * 읽기 전용 Bybit 연결 화면 한국어 카피 정본(Story 2.1).
 *
 * 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다.
 * 근거: epics.md Story 2.1, DESIGN.md components/secure-input-form, EXPERIENCE.md Voice and Tone,
 *      UX-DR15(연결 조작은 설정 화면 안이다), UX-DR23.
 *
 * 규칙:
 *   - 판정 대신 사실·원인·다음 행동을 짧은 완전문으로 쓴다.
 *   - "실시간", "정확", "안전 보장", 수익 축하·손실 공포·스트릭 표현을 쓰지 않는다.
 *   - 금융값을 넣지 않는다. 이 화면은 연결 사실만 보여 주며 총자산도 수익률도 그리지 않는다.
 *   - API Key·Secret을 화면에 되비추지 않는다. 성공 뒤에는 마스킹 값조차 보여 주지 않는다.
 */

/**
 * 연결 표면의 경로. **이 계층에서 그 경로를 정의하는 상수는 이것 하나다.**
 *
 * 세 표면이 한 접두사를 나눠 쓰고 메서드와 경로가 그것을 가른다 —
 * `GET /api/connections/bybit`은 연결 상태 조회, `POST /api/connections/bybit`은 폼을 열 준비,
 * `POST /api/connections/bybit/connect`는 검증과 저장, `POST /api/connections/bybit/cancel`은 그
 * 준비를 되돌리는 취소다.
 *
 * 접두사가 `/api/auth/`가 아니다. 이 경로는 **인증으로 막히는 첫 경로**이며 미인증 요청은 401로
 * 끝난다(`AuthSecurityConfig`). 상태 변경이 세션 귀속 CSRF 토큰과 동일 출처 `Origin`을 요구하는
 * 것은 `/api/auth/` 아래와 같다.
 */
export const BYBIT_CONNECTION_PATH = '/api/connections/bybit'

/** 검증과 저장. **여기서만 자격정보가 나간다.** 본문으로만 보내며 쿼리에 싣지 않는다. */
export const BYBIT_CONNECT_PATH = `${BYBIT_CONNECTION_PATH}/connect`

/** 준비를 되돌린다. 화면의 "바뀐 것은 없습니다"가 참이려면 이 요청이 있어야 한다. */
export const BYBIT_CANCEL_PATH = `${BYBIT_CONNECTION_PATH}/cancel`

/**
 * 서버가 돌려주는 거절 code → 화면 문장.
 *
 * **닫힌 맵이다.** 서버가 같은 뜻의 문장을 이미 보내지만 화면은 자기 카피를 쓴다 — 이 저장소의
 * 규율이 "화면 카피는 `content/`가 소유한다"이고, 서버 문자열을 그대로 그리면 그 규율이 이
 * 화면에서만 뒤집힌다. 대신 모르는 code는 아래 `unknown` 하나로 접는다.
 *
 * 각 문장이 **다음 행동 하나**를 말한다. 무엇이 어긋났는지 감추지 않는 것은 여기서 갈리는 것이
 * 사용자 자신이 Bybit 콘솔에서 고칠 수 있는 사실이기 때문이다 — 계정의 존재나 남의 계정을
 * 드러내지 않는다.
 */
const REJECTIONS: Readonly<Record<string, string>> = {
  /**
   * 그 Bybit 계정이 이미 다른 BokriLog 계정에 연결돼 있다.
   *
   * 상대 계정을 말하지 않는다. 원인만 알리고 소유자를 드러내지 않는 것이 서버와 같은 계약이다.
   */
  CONNECTION_ACCOUNT_TAKEN:
    '이 Bybit 계정은 이미 다른 BokriLog 계정에 연결돼 있습니다. 다른 Bybit 계정의 키로 다시 시도해 주세요.',
  CONNECTION_INVALID_FORMAT:
    'API Key 또는 Secret의 형식이 올바르지 않습니다. 값을 다시 확인해 주세요.',
  CONNECTION_AUTHENTICATION_REJECTED:
    'Bybit이 이 API Key를 받아들이지 않았습니다. 키가 유효한지 확인하고 다시 시도해 주세요.',
  CONNECTION_WRITE_PERMISSION:
    '이 API Key에 쓰기 권한이 있습니다. Bybit에서 읽기 전용 키를 새로 발급해 주세요.',
  CONNECTION_INSUFFICIENT_PERMISSION:
    '이 API Key에 조회 권한이 부족합니다. Bybit에서 조회 권한을 포함한 읽기 전용 키를 발급해 주세요.',
  CONNECTION_SUB_ACCOUNT:
    '서브 계정 키로는 연결할 수 없습니다. 메인 계정에서 읽기 전용 키를 발급해 주세요.',
  CONNECTION_IP_NOT_BOUND:
    '이 API Key에 BokriLog의 접속 IP가 등록돼 있지 않습니다. Bybit에서 IP를 등록한 뒤 다시 시도해 주세요.',
  /** 대행 업그레이드를 하지 않는다는 사실을 문장이 직접 말한다. */
  CONNECTION_UNSUPPORTED_ACCOUNT_MODE:
    '이 Bybit 계정은 통합 거래 계정(UTA) 2.0이 아닙니다. Bybit에서 직접 전환한 뒤 다시 시도해 주세요.',
  /** 실패를 `0 USDT`나 정상 연결로 표시하지 않는다. 조회가 성립하지 않았다는 사실을 말한다. */
  CONNECTION_WALLET_UNAVAILABLE:
    'Bybit 지갑 조회를 끝내지 못했습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  /** 자동 재시도하지 않는다. "직접"이라는 낱말이 그 계약을 사용자에게 말한다. */
  CONNECTION_TIMEOUT:
    'Bybit 응답이 제때 오지 않았습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  CONNECTION_RATE_LIMITED:
    'Bybit이 요청 빈도를 제한했습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  CONNECTION_PROVIDER_UNAVAILABLE:
    'Bybit에 연결하지 못했습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  CONNECTION_RESPONSE_FORMAT:
    'Bybit 응답을 해석하지 못했습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  CONNECTION_UNAVAILABLE:
    '지금은 연결 기능을 사용할 수 없습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.',
  /**
   * 단일 거부.
   *
   * 서버가 재인증 없음·오래된 재인증·의심 세션·삭제 상태·운영 제한·이미 있는 활성 연결을 하나로
   * 접은 값이다. 그래서 이 문장도 이유를 특정하지 않고 사용자가 할 수 있는 일 하나를 가리킨다.
   */
  FORBIDDEN: '연결을 시작하지 못했습니다. 아래에서 본인 확인을 마친 뒤 다시 시도해 주세요.',
}

/**
 * 서버가 이 표면에서 낼 수 있는 안정 code 전부.
 *
 * 백엔드 `BybitConnectionController.CODES`의 값 집합과 **같아야 한다.** 두 계층이 한 계약을 나눠
 * 갖는 자리이며(`CSRF_HEADER`와 같은 종류의 중복) 각 층의 시험이 자기 쪽을 이 목록과 대조한다 —
 * 갈라지면 화면이 모르는 code를 받아 일반 문장으로 접고, 사용자는 고칠 수 있는 원인을 듣지 못한다.
 *
 * `UNAUTHENTICATED`는 여기 없다. 그 code는 이 표면만의 것이 아니라 공통 오류 계약의 것이고,
 * 화면은 그것을 문장이 아니라 **이동**으로 답한다(`useBybitConnection`).
 */
export const CONNECTION_REJECTION_CODES: readonly string[] = [
  'CONNECTION_ACCOUNT_TAKEN',
  'CONNECTION_AUTHENTICATION_REJECTED',
  'CONNECTION_INSUFFICIENT_PERMISSION',
  'CONNECTION_INVALID_FORMAT',
  'CONNECTION_IP_NOT_BOUND',
  'CONNECTION_PROVIDER_UNAVAILABLE',
  'CONNECTION_RATE_LIMITED',
  'CONNECTION_RESPONSE_FORMAT',
  'CONNECTION_SUB_ACCOUNT',
  'CONNECTION_TIMEOUT',
  'CONNECTION_UNAVAILABLE',
  'CONNECTION_UNSUPPORTED_ACCOUNT_MODE',
  'CONNECTION_WALLET_UNAVAILABLE',
  'CONNECTION_WRITE_PERMISSION',
  'FORBIDDEN',
]

/** 위 목록에 문장이 있는가. 시험이 두 방향을 모두 보는 데 쓴다. */
export function hasRejectionCopy(code: string): boolean {
  return Object.hasOwn(REJECTIONS, code)
}

/**
 * 모르는 code의 문장.
 *
 * 서버가 code를 하나 더 만들었는데 이 맵이 따라가지 못한 경우다. 빈 문장을 그리거나 서버 문자열을
 * 그대로 내보내는 대신 사용자가 할 수 있는 일을 말한다 — 어느 쪽도 계정의 사실을 드러내지 않는다.
 */
const UNKNOWN_REJECTION = '연결을 끝내지 못했습니다. 잠시 후 아래 버튼으로 다시 시도해 주세요.'

/** 그 code의 문장. 모르는 값은 하나로 접힌다. */
export function rejectionMessage(code: string | null): string {
  if (code === null) {
    return UNKNOWN_REJECTION
  }
  return REJECTIONS[code] ?? UNKNOWN_REJECTION
}

/**
 * 검증 시각의 표시.
 *
 * 저장은 UTC instant이고 **표시는 `Asia/Seoul`**이다(epic-2-context의 시간 계약). 클라이언트가
 * 자기 시간대를 쓰면 같은 사실이 기기마다 다른 문장이 되고, 화면과 로그를 맞춰 볼 수 없다.
 *
 * 읽을 수 없는 값은 빈 문자열이다. 서버가 보낸 값이지만 추측해서 그리지 않는다.
 */
export function formatVerifiedAt(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(parsed)
}

export const connectionContent = {
  id: 'connection-bybit',
  heading: 'Bybit 연결',
  /**
   * 무엇을 하는 조작인지, 그리고 무엇을 하지 않는지.
   *
   * 읽기 전용이라는 사실을 먼저 말한다. 사용자가 거래소 키를 넘기는 자리이므로 그 경계가 조작
   * 앞에 있어야 한다.
   */
  body: '읽기 전용 Bybit API 키로 자산을 조회합니다. BokriLog는 주문·출금·이체를 하지 않으며 쓰기 권한이 있는 키는 연결되지 않습니다.',
  /** 연결 전에 지나야 하는 것. 본인 확인 자리는 같은 화면 아래에 있다. */
  requirements:
    '연결에는 최근 본인 확인이 필요하고, 메인 계정의 통합 거래 계정(UTA) 2.0 읽기 전용 키여야 하며, 그 키에 BokriLog의 접속 IP가 등록돼 있어야 합니다.',
  /** 상태를 확인하는 동안. 확인 전에는 연결 여부를 주장하지 않는다. */
  checking: 'Bybit 연결 상태를 확인하고 있습니다.',
  /**
   * 상태를 확인하지 못했다.
   *
   * 빈 상태로 접지 않는다. 접으면 이미 연결된 계정에도 연결 버튼이 그려지고 그 클릭은 서버가
   * 거절해 엉뚱한 안내로 끝난다(`SettingsScreen`의 연결 목록과 같은 근거).
   */
  unavailable: 'Bybit 연결 상태를 확인하지 못했습니다. 아래 버튼으로 다시 확인해 주세요.',
  retry: 'Bybit 연결 상태 다시 확인',
  /** 연결되지 않은 상태. */
  notConnected: '연결된 Bybit 계정이 없습니다.',
  /** 연결된 상태. 계정 식별자도 자산도 말하지 않는다. */
  connected: '연결됨',
  verifiedAtLabel: '검증 시각',
  policyVersionLabel: '적용 정책 버전',
  /**
   * 연결된 뒤의 경계.
   *
   * 이 조회를 공식 현재가치 스냅샷으로 발행하지 않는다는 사실을 화면이 직접 말한다 — 그러지
   * 않으면 사용자는 "연결됐으니 지금 자산이 보인다"고 읽는다.
   */
  connectedBoundary:
    '연결은 조회 권한만 확인한 것입니다. 자산 현재가치와 복리 계획 비교는 이후 단계에서 제공합니다.',
  /** 폼을 여는 조작. 이 요청은 아무것도 바꾸지 않는다. */
  start: 'Bybit 연결 시작',
  /** 폼이 열려 있는 동안 화면당 하나뿐인 live region이 말할 문장. */
  pending: 'Bybit 연결을 처리하고 있습니다.',
  form: {
    id: 'connection-bybit-form',
    heading: 'Bybit API 키 입력',
    /** 확인 문구 앞의 라벨. 문구 자체는 서버가 만들고 화면은 그대로 렌더한다. */
    confirmationLabel: '확인 문구',
    /**
     * 확인 문구를 왜 보여 주는가.
     *
     * 사용자가 실제로 본 문장이 허가의 귀속 축 하나가 된다. 그 사실을 말하지 않으면 화면에 뜻
     * 모를 코드가 하나 늘어난 것으로만 보인다.
     */
    confirmationHelp:
      '이 문구는 이번 연결 요청에만 유효합니다. 아래 버튼을 누르면 같은 문구로 확인이 전송됩니다.',
    apiKeyLabel: 'API Key',
    apiKeyHelp: 'Bybit에서 발급한 읽기 전용 API Key를 붙여 넣으세요.',
    apiSecretLabel: 'API Secret',
    /** 붙여넣기를 막지 않는다는 사실을 말한다. 막는 폼이 흔하므로 사용자가 시도조차 않을 수 있다. */
    apiSecretHelp:
      'API Secret을 붙여 넣으세요. 저장 뒤에는 화면에 다시 표시되지 않으며 일부만 가려 보여 주지도 않습니다.',
    submit: 'Bybit 연결',
    cancel: '취소',
    /** 오류 요약의 제목. 요약이 있을 때만 렌더된다. */
    errorSummaryHeading: '연결하지 못했습니다',
    /** 필드 오류. 서버에 보내기 전에 화면이 먼저 거른다. */
    apiKeyRequired: 'API Key를 입력해 주세요.',
    apiSecretRequired: 'API Secret을 입력해 주세요.',
    /**
     * 실패 뒤 Secret 필드에 붙는 문장.
     *
     * Secret만 지우는 것이 `secure-input-form`의 계약이다. 지워진 이유를 말하지 않으면 사용자는
     * 값이 사라진 것을 오류로 읽는다.
     */
    apiSecretCleared: '보안을 위해 Secret만 지웠습니다. 다시 붙여 넣어 주세요.',
  },
  /** 취소했다. 아무것도 바뀌지 않았다는 사실을 함께 말한다. */
  cancelled: 'Bybit 연결을 취소했습니다. 바뀐 것은 없습니다.',
  /** 연결이 끝났다. 자산도 계정 식별자도 말하지 않는다. */
  succeeded: 'Bybit 연결을 마쳤습니다. 아래에서 검증 시각과 적용 정책 버전을 확인할 수 있습니다.',
} as const

/**
 * 연결 구역에서 **언제나** 렌더되는 카피.
 *
 * 폼 안의 문구는 여기 없다. 그 문장들은 폼이 열렸을 때만 나타나므로 한 배열에 섞으면 폼을 열지
 * 않은 화면의 문구 단정이 늘 실패한다(`unlinkDialogCopyStrings`와 같은 근거).
 */
export const connectionCopyStrings: readonly string[] = [
  connectionContent.heading,
  connectionContent.body,
  connectionContent.requirements,
]

/** 폼이 열렸을 때 렌더되어야 하는 카피 전부. */
export const connectionFormCopyStrings: readonly string[] = [
  connectionContent.form.heading,
  connectionContent.form.confirmationLabel,
  connectionContent.form.confirmationHelp,
  connectionContent.form.apiKeyLabel,
  connectionContent.form.apiKeyHelp,
  connectionContent.form.apiSecretLabel,
  connectionContent.form.apiSecretHelp,
  connectionContent.form.submit,
  connectionContent.form.cancel,
]
