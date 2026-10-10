/**
 * 화면 카피 금지 표현의 단일 목록(Story 3.5 리뷰).
 *
 * 실시간·정확 보장, 손실 공포, 수익 축하, 스트릭·배지 표현을 화면에 쓰지 않는다(CLAUDE.md `화면 카피`). 운영자가 쓰는 공지
 * 한 줄도 사용자 화면에 그대로 나가므로 같은 목록을 지난다 — 운영자 폼이 이 목록으로 미리 막고, 서버
 * (`OperatorEnvelopeService.FORBIDDEN_COPY_WORDS`)가 같은 목록으로 다시 막는다. 두 목록은 백엔드 시험
 * (`ServiceNoticeTest`)이 이 파일을 읽어 대조한다 — 하나를 바꾸면 다른 하나도 바꾼다.
 */
export const FORBIDDEN_COPY_WORDS = [
  '실시간',
  '정확',
  '보장',
  '축하',
  '손실',
  '수익을 내',
  '스트릭',
  '배지',
] as const

/** 금지 표현이 하나라도 있는가. */
export function containsForbiddenCopy(text: string): boolean {
  return FORBIDDEN_COPY_WORDS.some((word) => text.includes(word))
}
