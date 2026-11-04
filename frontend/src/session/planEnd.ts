import {
  PLAN_END_CANCEL_PATH,
  PLAN_END_CONFIRM_PATH,
  PLAN_END_PATH,
  type PlanEndReason,
} from '../content/planEnd'
import { postJson, readJson } from './http'
import { isInstant } from './instant'
import { DASHBOARD_REQUEST_TIMEOUT_MS, record } from './useDashboard'

/*
 * 활성 계획의 중도 종료(Story 2.13).
 *
 * 요청이 셋이고 전부 `POST`다. 먼저 `POST /api/dashboard/plan-end`(준비)가 자격을 판정하고 `plan-end` 일회성 허가를
 * 발급해 세션에 흐름을 심는다 — 그 응답 200의 확인 문구가 대화상자를 열 근거다. 사용자가 확정을 누르면
 * `POST /api/dashboard/plan-end/confirm`이 그 문구를 되돌려 보내 허가를 소비하고 계획을 끝내며, 취소하면
 * `POST /api/dashboard/plan-end/cancel`이 흐름과 허가를 되돌린다.
 *
 * **준비는 아무것도 바꾸지 않는다.** 그래서 대화상자를 열었다가 닫아도 계획·결과가 그대로다.
 *
 * 전송의 모양은 대시보드의 다른 상태 변경과 같다 — 세션 쿠키(`credentials: 'same-origin'`), 세션 귀속 CSRF 토큰
 * 헤더, 그리고 브라우저가 싣는 `Origin`. 확인 문구는 서버가 만든 정본이며 이 모듈은 그것을 해석하지 않고 그대로
 * 되돌려 보낼 뿐이다. 허가 참조는 서버가 브라우저에 보내지 않는다. 시한은 대시보드 읽기와 같은 10초다.
 *
 * 자동 재시도하지 않는다. 확정에 응답이 없으면 화면이 대화상자를 유지하고, 사용자가 다시 누를 때 **같은 멱등
 * 키**로 다시 보낸다 — 그 사이 서버가 이미 끝냈다면 같은 결과가 돌아온다.
 */

/** 준비·확정이 함께 싣는 값: 사유와 지금 보이는 묶음의 참조 세 값. */
export interface PlanEndRequest {
  readonly reason: PlanEndReason
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
}

/** 공통 오류 계약의 거절. `code`는 안정적인 machine code이고 종료 사용 불가에는 `reason`이 붙는다. */
export interface PlanEndRefusalAnswer {
  readonly kind: 'refused'
  readonly status: number
  readonly code: string | null
  readonly reason: string | null
}

/** 준비의 결과. `noAnswer`는 응답을 받지 못했거나 읽을 수 없는 경우다. */
export type PreparePlanEndAnswer =
  | { readonly kind: 'armed'; readonly confirmation: string }
  | PlanEndRefusalAnswer
  | { readonly kind: 'noAnswer' }

/** 확정의 결과. `noAnswer`는 계획이 끝났는지 알 수 없는 경우다(응답 없음·시한 초과·5xx·읽을 수 없는 200). */
export type ConfirmPlanEndAnswer =
  | { readonly kind: 'ended'; readonly endedAt: string }
  | PlanEndRefusalAnswer
  | { readonly kind: 'noAnswer' }

/** 응답 하나: 상태 코드와 JSON 본문(아니면 `null`). */
interface Answer {
  readonly status: number
  readonly payload: unknown
}

/**
 * 상태 변경 요청 하나. 시한은 세 요청 모두 대시보드 읽기와 같은 10초이며 요청과 본문 읽기를 함께 덮는다. 토큰이 없는
 * 호출은 여기 오지 않는다 — 보내도 서버가 403으로 거절하므로 결과는 같지만, 그 요청은 실패가 예정된 상태 변경 시도
 * 하나를 로그에 남긴다. 본문이 없는 요청(취소)은 `Content-Type`도 싣지 않는다.
 *
 * @returns 응답, 또는 응답을 받지 못했으면 `null`
 */
async function send(
  path: string,
  csrfToken: string,
  body: object | null,
  idempotencyKey: string | null,
): Promise<Answer | null> {
  const controller = new AbortController()
  let deadline: number | undefined
  const timedOut = new Promise<never>((_, reject) => {
    deadline = window.setTimeout(() => {
      controller.abort()
      reject(new Error('The plan-end request timed out.'))
    }, DASHBOARD_REQUEST_TIMEOUT_MS)
  })
  const exchange = async (): Promise<Answer> => {
    const response = await postJson(path, {
      csrfToken,
      body: body ?? undefined,
      idempotencyKey: idempotencyKey ?? undefined,
      signal: controller.signal,
    })
    // 오류 계약은 언제나 JSON이지만 그 사실에 기대지 않는다. JSON이 아닌 본문은 `null`이다.
    const payload: unknown = await readJson(response)
    return { status: response.status, payload }
  }
  try {
    return await Promise.race([exchange(), timedOut])
  } catch {
    // 네트워크 단절·응답 유실·시한 초과. 무엇이 일어났는지 추측하지 않는다.
    return null
  } finally {
    window.clearTimeout(deadline)
  }
}

/** 공통 오류 계약의 거절. 문장은 가져오지 않는다 — 카피는 `content/`가 소유한다. */
function refusalOf(answer: Answer): PlanEndRefusalAnswer {
  const row = record(answer.payload)
  return {
    kind: 'refused',
    status: answer.status,
    code: typeof row?.code === 'string' ? row.code : null,
    reason: typeof row?.reason === 'string' ? row.reason : null,
  }
}

/**
 * 확인 대화상자를 열 준비를 요청한다. **아직 아무것도 끝내지 않는다.**
 *
 * 200은 보낸 사유와 참조를 그대로 되돌려 주고 확인 문구 하나를 더해야 성립한다. 그 밖의 200은 무엇을 준비했는지
 * 알 수 없으므로 응답이 없던 것과 같다.
 */
export async function preparePlanEnd(
  request: PlanEndRequest,
  csrfToken: string | null,
): Promise<PreparePlanEndAnswer> {
  if (csrfToken === null) return { kind: 'noAnswer' }
  const answer = await send(PLAN_END_PATH, csrfToken, request, null)
  if (answer === null) return { kind: 'noAnswer' }
  if (answer.status !== 200) return refusalOf(answer)
  const row = record(answer.payload)
  if (
    row?.reason !== request.reason ||
    row.planId !== request.planId ||
    row.pointerVersion !== request.pointerVersion ||
    row.calculationId !== request.calculationId ||
    typeof row.confirmation !== 'string' ||
    row.confirmation === ''
  ) {
    return { kind: 'noAnswer' }
  }
  return { kind: 'armed', confirmation: row.confirmation }
}

/**
 * 확정을 보낸다. **여기서만 계획이 끝난다.**
 *
 * 성공하면 서버가 허가를 소비하고 계획을 `중도 종료`로 바꾸고 세션을 회전시킨다. 회전은 `Set-Cookie` 하나로
 * 끝나므로 이 함수가 할 일이 없다. 같은 키의 재전송은 저장된 같은 결과를 돌려준다.
 *
 * 5xx는 계획이 끝났는지 알 수 없는 응답으로 본다 — 서버가 되돌렸을 수도, 커밋 뒤에 멈췄을 수도 있다. 그때 같은
 * 키로 다시 보내면 어느 쪽이든 서버가 가른다. 200은 보낸 계획과 사유를 그대로 되돌려 주고 종료 시각을 실어야
 * 성립한다(준비와 같은 대조). 그 밖의 200도 무엇이 끝났는지 알 수 없는 응답이다.
 */
export async function confirmPlanEnd(
  request: PlanEndRequest,
  confirmation: string,
  idempotencyKey: string,
  csrfToken: string | null,
): Promise<ConfirmPlanEndAnswer> {
  if (csrfToken === null) return { kind: 'noAnswer' }
  const answer = await send(
    PLAN_END_CONFIRM_PATH,
    csrfToken,
    { ...request, confirmation },
    idempotencyKey,
  )
  if (answer === null || answer.status >= 500) return { kind: 'noAnswer' }
  if (answer.status !== 200) return refusalOf(answer)
  const row = record(answer.payload)
  if (
    row?.outcome !== 'ENDED' ||
    row.planId !== request.planId ||
    row.reason !== request.reason ||
    !isInstant(row.endedAt)
  ) {
    return { kind: 'noAnswer' }
  }
  return { kind: 'ended', endedAt: row.endedAt }
}

/**
 * 열어 둔 준비를 되돌린다. **최선 노력이며 결과를 돌려주지 않는다** — 남은 흐름은 스스로 만료되고 다음 준비가
 * 버린다. 본문 없는 `POST`이고 서버는 언제나 204로 답한다. 시한은 준비·확정과 같은 10초다 — 다음 준비가 이것을
 * 기다리므로 시한이 갈라지면 그 기다림의 상한도 갈라진다.
 */
export async function cancelPlanEnd(csrfToken: string | null): Promise<void> {
  if (csrfToken === null) return
  await send(PLAN_END_CANCEL_PATH, csrfToken, null, null)
}
