import {
  CSRF_HEADER,
  unlinkCancelPath,
  unlinkConfirmPath,
  unlinkPath,
  type SignInProvider,
} from '../content/auth'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 로그인 수단 해제.
 *
 * 요청이 셋이고 전부 `POST`다. 먼저 `POST /api/auth/unlink/<제공자>`가 허가를 발급받아 세션에
 * 일회성 무장을 심고(그 응답 204가 확인 대화상자를 열 근거다), 사용자가 최종 확인을 누르면
 * `POST /api/auth/unlink/<제공자>/confirm`이 그 무장을 소비하며, 취소하면
 * `POST /api/auth/unlink/<제공자>/cancel`이 그 무장과 허가를 되돌린다.
 *
 * **첫 요청은 아무것도 바꾸지 않는다.** 그래서 대화상자를 열었다가 닫아도 매핑·세션·목록이 그대로다.
 * 두 요청을 하나로 합치면 대화상자를 여는 순간 해제가 일어나므로 그 계약이 성립하지 않는다.
 *
 * 전송의 모양은 `linkProvider.ts`와 같다 — 세션 쿠키(`credentials: 'same-origin'`), 토큰 헤더,
 * 그리고 브라우저가 싣는 `Origin`. 마지막 것은 스크립트가 만들 수 없고, 그래서 여기에 `Origin`을
 * 다루는 코드가 없는 것이 정상이다.
 *
 * **이동이 없다.** 연결은 성공하면 제공자로 최상위 이동하고 실패하면 표지를 달고 같은 화면을 다시
 * 요청했지만, 해제는 왕복이 없어 문서를 떠나지 않는다. 결과는 화면의 상태 하나로 남고 주소는 그대로다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 무엇이 일어났는지 알 수 없고,
 * 재인증이 만료된 경우에는 자동으로 재인증을 시작하지도 않는다.
 */

/** 해제 준비의 결과. 화면이 대화상자를 열지 정하는 데 쓴다. */
export type UnlinkPrepareOutcome = 'armed' | 'failed'

/** 최종 확인의 결과. */
export type UnlinkConfirmOutcome = 'failed' | 'unlinked'

/**
 * 확인 대화상자를 열 준비를 요청한다. **아직 아무것도 해제하지 않는다.**
 *
 * 204만 성공이다. 그 밖(403·404·네트워크 단절·시한 초과)은 전부 `failed` 하나로 수렴하며 무엇이
 * 일어났는지 추측하지 않는다 — 마지막 수단·미연결·재인증 없음·오래된 재인증이 서버에서 이미 구분
 * 되지 않는 하나이기 때문이다.
 */
export async function prepareUnlink(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<UnlinkPrepareOutcome> {
  return (await post(unlinkPath(provider), csrfToken)) ? 'armed' : 'failed'
}

/**
 * 최종 확인을 보낸다. **여기서만 상태가 바뀐다.**
 *
 * 성공하면 서버가 매핑을 지우고, 이 사용자의 다른 세션을 모두 폐기하고, 현재 세션을 회전시킨다.
 * 회전은 `Set-Cookie` 하나로 끝나므로 이 함수가 할 일이 없다 — 브라우저가 새 쿠키를 그대로 쓴다.
 */
export async function confirmUnlink(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<UnlinkConfirmOutcome> {
  return (await post(unlinkConfirmPath(provider), csrfToken)) ? 'unlinked' : 'failed'
}

/**
 * 열어 둔 준비를 되돌린다. **최선 노력이며 결과를 돌려주지 않는다.**
 *
 * 준비가 서버 상태를 만들므로(무장과 허가) 취소에도 되돌리는 요청이 있어야 한다 — 없으면 화면의
 * "바뀐 것은 없습니다"가 거짓이 되고 아무도 참조하지 않는 허가가 5분을 산다.
 *
 * 결과를 기다리지 않는 이유는 사용자 경험이다. 취소는 즉시 닫혀야 하고, 이 요청이 실패해도
 * 사용자가 할 일은 없다 — 남은 무장은 스스로 만료되고 다음 조작이 어차피 그것을 버린다. 그래서
 * 화면은 이 약속의 결과로 아무 분기도 만들지 않는다.
 */
export async function cancelUnlink(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<void> {
  await post(unlinkCancelPath(provider), csrfToken)
}

/**
 * 상태 변경 요청 하나의 공유 전송.
 *
 * **export하는 이유는 재사용이다.** Story 2.1의 Bybit 연결 취소가 정확히 같은 모양을 필요로 하고
 * (`POST`, 세션 쿠키, 토큰 헤더, 204만 성공, 자동 재시도 없음), 복제하면 시한·자격정보 정책·
 * 실패 접기 규칙이 네 번째 사본으로 갈라진다. 정의가 이 모듈에 남는 것은 첫 소비자가 여기였기
 * 때문이며, 그 자리를 옮기는 것은 별도로 배포 가능한 딴 목표다.
 *
 * @returns 서버가 204로 답했는가. 그 밖은 전부 거짓 하나다
 */
export async function post(path: string, csrfToken: string | null): Promise<boolean> {
  if (csrfToken === null) {
    /*
     * 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절하므로 결과는 같지만, 그 요청은
     * 실패가 예정된 상태 변경 시도 하나를 로그에 남긴다(`linkProvider.ts`와 같은 근거).
     */
    return false
  }

  // 시한은 세션 확인·로그아웃·재인증 시작·연결 시작과 같은 값이다. 같은 진입점을 지나므로
  // 갈라질 이유가 없다.
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)

  try {
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { [CSRF_HEADER]: csrfToken },
      signal: controller.signal,
    })
    return response.status === 204
  } catch {
    // 네트워크 단절·응답 유실·시한 초과도 같은 결과다. 무엇이 일어났는지 추측하지 않는다.
    return false
  } finally {
    clearTimeout(deadline)
  }
}
