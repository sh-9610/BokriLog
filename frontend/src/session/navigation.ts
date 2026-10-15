/*
 * 최상위 내비게이션.
 *
 * 이 저장소에는 클라이언트 라우팅이 없다. 화면 전환은 전부 문서 요청이고, 그 요청을 브라우저에
 * 시키는 유일한 자리가 이 모듈이다. 화면들이 각자 `window.location`을 만지면 "전환은 여기뿐"이라는
 * 사실이 여러 곳에 흩어지고, 시험이 그 전환을 관측할 자리도 사라진다 — jsdom은 `location`의 전환을
 * 수행하지도, 관측하게 하지도 않으므로 시험은 이 모듈을 `vi.mock`으로 대체한다.
 *
 * 두 함수의 차이는 히스토리 항목을 남기는가 하나다. 그 선택이 뒤로 가기의 의미를 정한다.
 */

/**
 * 지금 항목을 덮어쓰고 이동한다.
 *
 * 서버가 이 경로를 목적지로 고르지 않았거나(`useSessionGate`) 세션이 만료된 경우다. 되돌아갈 곳으로
 * 지금 경로를 남기면 뒤로 가기가 같은 전환을 반복한다.
 */
export function replaceLocation(path: string): void {
  window.location.replace(path)
}

/**
 * 히스토리 항목을 남기고 이동한다.
 *
 * 로그아웃이 쓴다. 사용자가 방금 보던 화면은 실제로 방문한 곳이므로 뒤로 가기로 되돌아갈 수
 * 있어야 하고, 그때 그 문서가 캐시에서 복원되지 않고 재요청되는 것이(진입점의
 * `Cache-Control: no-store`) 이 스토리가 지켜야 하는 계약이다. 덮어쓰면 그 경로가 히스토리에서
 * 사라져 그 계약을 사용자도 시험도 밟을 수 없다.
 */
export function navigateTo(path: string): void {
  window.location.assign(path)
}

/**
 * 문서를 다시 요청하지 않고 지금 히스토리 항목의 주소만 바꾼다(Story 4.3, `history.replaceState`).
 *
 * 전환이 아니다 — 화면도 문서도 그대로이고 주소 표시줄의 값만 바뀐다. 중립 전환 화면이 문서 경계를 넘어 받은 transition 참조를
 * 메모리로 옮긴 뒤 쿼리를 지우는 데만 쓴다. 그래야 광고 공급자 script가 load되기 전에 주소에서 그 값이 사라지고, 새로고침·뒤로
 * 가기로 같은 화면에 다시 들어오면 참조가 없어 곧바로 결과로 간다.
 */
export function replaceAddress(path: string): void {
  window.history.replaceState(window.history.state, '', path)
}
