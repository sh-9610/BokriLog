/**
 * 표시 전용 십진 형식(`DISPLAY-1.0`).
 *
 * 서버가 보낸 canonical decimal string을 **표시 직전에 한 번만** 반올림한다. 결과 문자열은 화면에만
 * 쓰고 API로 되돌려 보내거나 다음 계산의 입력으로 쓰지 않는다. `number`로 바꾸지 않는다 — 이진
 * 부동소수점은 34자리 값을 표시하기도 전에 바꿔 버린다.
 *
 * 반올림은 HALF_UP 소수 둘째 자리다(절댓값 기준, 셋째 자리가 5 이상이면 올린다). 반올림 결과가
 * 0이면 부호를 떼어 `-0.00`을 `0.00`으로 보인다.
 */

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/

/** canonical decimal string: 지수·앞의 0·끝의 0이 없고 0은 `"0"`이다(서버 `CALC-1.0`의 결과 형식). */
const CANONICAL = /^-?(0|[1-9]\d*)(\.\d*[1-9])?$/

/** 음이 아닌 plain decimal string. 표기(끝의 0 등)는 다를 수 있지만 지수·부호 `+`·구분자는 없다. */
const PLAIN_NON_NEGATIVE = /^\d+(\.\d+)?$/

/** canonical decimal string인가. 이 검사를 지난 값만 `BigInt`로 다루는 형식 함수에 넘긴다. */
export function isCanonicalDecimal(value: unknown): value is string {
  return typeof value === 'string' && CANONICAL.test(value)
}

/** 0 이상의 canonical decimal string인가. 금액 `P0`·목표율처럼 음수가 올 수 없는 값에 쓴다. */
export function isCanonicalNonNegative(value: unknown): value is string {
  return isCanonicalDecimal(value) && !value.startsWith('-')
}

/**
 * 0 이상의 plain decimal string인가. 표기만 다른 값(`"1250.00"`)도 받는다 — 형식 함수는 끝의 0을
 * 그대로 다룰 수 있으므로, 표기 차이 하나로 결과 묶음 전체를 버리지 않는다.
 */
export function isPlainNonNegative(value: unknown): value is string {
  return typeof value === 'string' && PLAIN_NON_NEGATIVE.test(value)
}

function hundredths(sign: string, whole: string, fraction: string): string {
  const padded = fraction.padEnd(3, '0')
  const minor =
    BigInt(whole) * 100n + BigInt(padded.slice(0, 2)) + ((padded[2] ?? '0') >= '5' ? 1n : 0n)
  const units = (minor / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const cents = (minor % 100n).toString().padStart(2, '0')
  return `${minor === 0n ? '' : sign}${units}.${cents}`
}

/** 금액. 예: `"-12.345"` → `"-12.35"`, `"-0.001"` → `"0.00"`, `"1234567.891"` → `"1,234,567.89"`. */
export function formatAmount(decimal: string): string {
  const parts = DECIMAL.exec(decimal)
  if (parts === null) return decimal
  return hundredths(parts[1] ?? '', parts[2] ?? '0', parts[3] ?? '')
}

/**
 * 비율을 퍼센트로. 소수점을 두 자리 옮긴 뒤 같은 규칙으로 반올림한다.
 * 예: `"0.083582089552238805970149"` → `"8.36%"`, `"-0.2"` → `"-20.00%"`, `"12.5"` → `"1,250.00%"`.
 */
export function formatPercent(ratio: string): string {
  const parts = DECIMAL.exec(ratio)
  if (parts === null) return ratio
  const fraction = (parts[3] ?? '').padEnd(2, '0')
  const whole = `${parts[2] ?? '0'}${fraction.slice(0, 2)}`.replace(/^0+(?=\d)/, '')
  return `${hundredths(parts[1] ?? '', whole, fraction.slice(2))}%`
}

/**
 * 비율을 반올림 없이 퍼센트 숫자로. 입력한 목표율을 그대로 되보이는 자리에 쓴다.
 * 예: `"0.1"` → `"10"`, `"0.0123"` → `"1.23"`.
 */
export function percentFromRatio(value: string): string {
  const [whole = '0', fraction = ''] = value.split('.')
  const coefficient = BigInt(`${whole}${fraction}`)
  const scale = fraction.length - 2
  if (scale <= 0) return (coefficient * 10n ** BigInt(-scale)).toString()
  const digits = coefficient.toString().padStart(scale + 1, '0')
  const point = digits.length - scale
  const result = `${digits.slice(0, point)}.${digits.slice(point)}`
  return result.replace(/\.?0+$/, '') || '0'
}

/** UTC instant를 서울 시각으로. 읽을 수 없는 값은 `fallback`이다. */
export function formatSeoulTime(value: string, fallback: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return fallback
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(date)
  } catch {
    return fallback
  }
}

/** 초·밀리초까지의 서울 시각 형식. 한 번만 만든다 — 계산 근거는 시각 여러 개를 한 번에 그린다. */
let seoulInstantFormat: Intl.DateTimeFormat | null = null

function seoulInstantFormatter(): Intl.DateTimeFormat {
  seoulInstantFormat ??= new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    fractionalSecondDigits: 3,
    hourCycle: 'h23',
  })
  return seoulInstantFormat
}

/**
 * UTC instant를 서울 시각으로, 초와 밀리초까지(Story 2.9). 계산 근거의 시각 절처럼 두 시각이 같은지
 * 다른지를 화면에서 그대로 확인해야 하는 곳에만 쓴다 — 분 단위로 줄이면 서로 다른 두 시각이 같아
 * 보인다. 읽을 수 없는 값은 `fallback`이다.
 */
export function formatSeoulInstant(value: string, fallback: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return fallback
  try {
    return seoulInstantFormatter().format(date)
  } catch {
    return fallback
  }
}
