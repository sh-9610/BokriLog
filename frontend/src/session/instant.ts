/** 서버가 쓰는 형식의 시각(`Instant#toString`): UTC, `Z`, 초 단위와 선택적 소수부(최대 9자리). */
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/

/**
 * 값이 서버 형식의 시각인가. 대시보드·계산 근거·조정 이력·계획 조건·종료 기록이 함께 쓰는 엄격판
 * 하나다 — `Date.parse`만 보는 느슨한 판정은 `'yesterday'`는 거절해도 오프셋 형식이나 날짜만 있는 값을
 * 통과시켜, 서버가 보내지 않는 모양이 화면에 닿는다.
 */
export function isInstant(value: unknown): value is string {
  return typeof value === 'string' && INSTANT.test(value) && Number.isFinite(Date.parse(value))
}
