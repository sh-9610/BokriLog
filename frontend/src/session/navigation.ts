/**
 * 최상위 내비게이션 하나.
 *
 * 이 저장소에는 클라이언트 라우팅이 없다. 화면 전환은 전부 문서 요청이고, 그 요청을 브라우저에
 * 시키는 유일한 자리가 이 함수다. 세 화면이 각자 `window.location`을 만지면 "전환은 하나뿐"이라는
 * 사실이 세 곳에 흩어지고, 시험이 그 전환을 관측할 자리도 사라진다.
 *
 * `replace`다. `assign`은 되돌아갈 곳으로 지금 경로를 남기는데, 서버가 이 경로를 목적지로 고르지
 * 않았다는 것이 이 호출의 이유다. 뒤로 가기가 다시 그 경로로 돌아오면 같은 전환이 반복된다.
 */
export function replaceLocation(path: string): void {
  window.location.replace(path)
}
