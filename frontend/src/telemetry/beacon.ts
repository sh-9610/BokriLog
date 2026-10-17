import { CSRF_HEADER } from '../content/auth'

/*
 * 동일 출처 계측 beacon(Story 4.5) — Web Vitals와 광고 이벤트를 한 묶음씩 `POST /api/engagement/telemetry`로 보낸다.
 *
 * 어휘는 닫혀 있다. 서버(`ClientTelemetryController`)가 같은 어휘로 본문을 검사하고 하나라도 어휘 밖이면 묶음 전체를 400으로
 * 거절한다. 이벤트에는 사용자·결과·transition·금융값이 없다 — metric·화면 경로·값·광고 시도 여부, 또는 광고 이벤트와 실패 사유뿐이다.
 *
 * 보내는 방법은 `fetch`의 `keepalive` 하나다. `sendBeacon`은 CSRF 헤더를 실을 수 없어 쓰지 않는다. 화면 세션의 CSRF 토큰이 있을
 * 때만 보내고(진입 화면은 토큰이 없어 요청 0건), 응답을 읽지 않으며, 실패·거절은 무시한다 — 재시도·브라우저 저장소 쓰기·화면
 * 영향이 없다. 큐는 메모리에만 있고 문서와 함께 사라진다.
 */

/** beacon 경로. 백엔드 `ClientTelemetryController.TELEMETRY_PATH`와 같은 값이다. */
export const TELEMETRY_PATH = '/api/engagement/telemetry'

/** 묶음 하나의 최대 이벤트 수. 서버 `ClientTelemetryApi.MAX_EVENTS`와 같다. */
export const MAX_BATCH_EVENTS = 16

/** 묶음 본문의 최대 바이트 수. 서버 `ClientTelemetryApi.MAX_BODY_BYTES`와 같다. */
export const MAX_BATCH_BYTES = 8 * 1024

/** 큐의 상한. 문서 하나가 만드는 이벤트(Vitals 셋·광고 이벤트 다섯)보다 넉넉하다. 넘으면 새 이벤트를 버린다. */
const MAX_QUEUED = 64

/*
 * 닫힌 어휘. 서버 `ClientTelemetryApi`의 enum과 같은 집합이고, 둘의 일치는 공유 표 `telemetryVocabulary.fixture.json`을 프런트·
 * 백엔드 시험이 함께 읽어 강제한다.
 */
export const VITAL_METRICS = ['LCP', 'INP', 'CLS'] as const
export type VitalMetric = (typeof VITAL_METRICS)[number]

/** 측정하는 화면 넷. 진입·온보딩·운영자 화면은 측정하지 않는다. */
export const TELEMETRY_ROUTES = [
  '/dashboard',
  '/settings',
  '/plan-conditions',
  '/ad-transition',
] as const
export type TelemetryRoute = (typeof TELEMETRY_ROUTES)[number]

export const AD_EVENTS = ['REQUESTED', 'READY', 'CLOSED', 'FAILED', 'RESULT'] as const
export type AdEvent = (typeof AD_EVENTS)[number]

export const AD_FAILURE_REASONS = [
  'TIMEOUT',
  'NO_FILL',
  'BLOCKED',
  'UNSUPPORTED',
  'ERROR',
  'WINDOW_EXPIRED',
] as const
export type AdFailureReason = (typeof AD_FAILURE_REASONS)[number]

/** adapter가 감지하는 실패의 사유. 소비 창 경과(`WINDOW_EXPIRED`)는 화면이 정한다. */
export type AdapterFailureReason = Exclude<AdFailureReason, 'WINDOW_EXPIRED'>

export type TelemetryEvent =
  | {
      readonly kind: 'VITAL'
      readonly metric: VitalMetric
      readonly route: TelemetryRoute
      readonly value: number
      readonly ad: boolean
    }
  | { readonly kind: 'AD'; readonly event: Exclude<AdEvent, 'FAILED'> }
  | { readonly kind: 'AD'; readonly event: 'FAILED'; readonly reason: AdFailureReason }

/** 큐의 이벤트. Vitals의 `ad`는 보낼 때 정한다. */
type QueuedEvent =
  | Exclude<TelemetryEvent, { readonly kind: 'VITAL' }>
  | Omit<Extract<TelemetryEvent, { readonly kind: 'VITAL' }>, 'ad'>

let queue: QueuedEvent[] = []
let csrfToken: string | null = null
/**
 * 이 문서가 광고를 시도했다(`REQUESTED`). 보낼 때 이 값이 참이면 이 문서의 모든 Vitals가 광고를 띄운 문서의 것(`ad=true`)이다 —
 * SHOW 응답 전 키 입력으로 LCP가 먼저 확정돼 큐에 들어갔어도 그렇다.
 */
let adRequested = false
/** 이 문서에서 이미 기록한 광고 이벤트. 이벤트마다 한 번이다. */
const recordedAdEvents = new Set<AdEvent>()
/** 이 문서에서 이미 기록한 metric. 문서당 metric마다 첫 보고 하나만 보낸다(서버 표본도 SHOW당 metric마다 하나다). */
const recordedVitals = new Set<VitalMetric>()

function enqueue(event: QueuedEvent): void {
  if (queue.length >= MAX_QUEUED) return
  queue.push(event)
}

/** 화면 세션의 CSRF 토큰. 없으면(`null`) 보내지 않고 큐에 둔다. */
export function setTelemetryToken(token: string | null): void {
  csrfToken = token
}

/**
 * Web Vitals 하나. 문서당 metric마다 첫 보고 하나만 쌓는다(같은 metric의 재보고는 무시). 0 이상 유한수가 아니면 버린다(서버가 묶음
 * 전체를 거절하지 않게).
 */
export function recordVital(metric: VitalMetric, route: TelemetryRoute, value: number): void {
  if (!Number.isFinite(value) || value < 0 || recordedVitals.has(metric)) return
  recordedVitals.add(metric)
  enqueue({ kind: 'VITAL', metric, route, value })
}

/**
 * 광고 이벤트 하나. 이벤트마다 문서당 한 번이다(두 번째부터는 무시한다). `REQUESTED`가 기록된 문서의 Vitals는 보낼 때 광고를 띄운
 * 문서의 것으로 표시된다.
 */
export function recordAdEvent(
  ...[event, reason]: [Exclude<AdEvent, 'FAILED'>] | ['FAILED', AdFailureReason]
): void {
  if (recordedAdEvents.has(event)) return
  recordedAdEvents.add(event)
  if (event === 'REQUESTED') adRequested = true
  enqueue(event === 'FAILED' ? { kind: 'AD', event, reason } : { kind: 'AD', event })
}

/** 크기 상한 안의 묶음들로 나눈다 — 건수 16·본문 8 KiB. 하나로도 넘치는 이벤트는 없다(필드가 짧은 닫힌 값뿐이다). */
function batchesOf(events: readonly TelemetryEvent[]): TelemetryEvent[][] {
  const batches: TelemetryEvent[][] = []
  let current: TelemetryEvent[] = []
  for (const event of events) {
    const candidate = [...current, event]
    if (
      current.length > 0 &&
      (candidate.length > MAX_BATCH_EVENTS || bodyBytes(candidate) > MAX_BATCH_BYTES)
    ) {
      batches.push(current)
      current = [event]
    } else {
      current = candidate
    }
  }
  if (current.length > 0) batches.push(current)
  return batches
}

/** 보낼 이벤트. Vitals의 `ad`는 보내는 지금 이 문서가 광고를 시도했는가다. */
function wireOf(event: QueuedEvent): TelemetryEvent {
  return event.kind === 'VITAL' ? { ...event, ad: adRequested } : event
}

function bodyOf(events: readonly TelemetryEvent[]): string {
  return JSON.stringify({ events })
}

function bodyBytes(events: readonly TelemetryEvent[]): number {
  return new TextEncoder().encode(bodyOf(events)).length
}

/**
 * 쌓인 이벤트를 보낸다. 토큰이 없거나 큐가 비었으면 아무것도 하지 않는다(토큰이 없으면 큐를 그대로 둔다). 보낸 이벤트는 결과와
 * 무관하게 큐에서 뺀다 — 재시도하지 않는다. 문서가 내려가는 중에도 요청이 끝나도록 `keepalive`다.
 */
export function flushTelemetry(): void {
  const token = csrfToken
  if (token === null || queue.length === 0) return
  const pending = queue.map(wireOf)
  queue = []
  for (const batch of batchesOf(pending)) {
    try {
      void fetch(TELEMETRY_PATH, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        keepalive: true,
        headers: { 'Content-Type': 'application/json', [CSRF_HEADER]: token },
        body: bodyOf(batch),
      }).catch(() => {
        // 계측 실패는 화면과 무관하다. 재시도하지 않는다.
      })
    } catch {
      // `fetch` 자체가 던져도(keepalive 한도 등) 화면은 그대로다.
    }
  }
}

/** 이 문서의 계측이 시작됐다(`beginDocument`). */
let documentStarted = false
/** 숨김·떠남 리스너를 뗀다. 시험의 문서 초기화만 부른다. */
let stopListening: (() => void) | null = null

/** 이 문서의 계측을 시작해도 되는가. 문서당 처음 한 번만 참이다(StrictMode의 이중 effect도 한 번). */
export function beginDocument(): boolean {
  if (documentStarted) return false
  documentStarted = true
  return true
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'hidden') flushTelemetry()
}

/**
 * 문서가 숨거나(`visibilitychange`의 hidden) 내려갈 때(`pagehide`) 보낸다. `web-vitals`는 같은 hidden 전환에서 window capture
 * 단계로 마지막 값을 보고하므로, 문서에 붙인 이 리스너(target 단계)는 그 뒤에 돌아 그 값까지 한 묶음으로 보낸다.
 */
export function listenForDocumentHide(): void {
  if (stopListening !== null) return
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', flushTelemetry)
  stopListening = () => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('pagehide', flushTelemetry)
  }
}

/** 시험 전용: 문서 하나의 상태(큐·토큰·광고 이벤트·시작·리스너)를 처음으로 되돌린다. */
export function resetTelemetryForTests(): void {
  queue = []
  csrfToken = null
  adRequested = false
  recordedAdEvents.clear()
  recordedVitals.clear()
  documentStarted = false
  stopListening?.()
  stopListening = null
}
