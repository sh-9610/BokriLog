import { describe, expect, it } from 'vitest'

import { isInstant } from './instant'

/*
 * 서버 형식의 시각 판정 하나.
 *
 * 화면이 받는 모든 시각이 이 판정을 지난다. 느슨한 판정(`Date.parse`가 읽기만 하면 통과)은 서버가 보내지
 * 않는 모양 — 오프셋, 날짜만 — 을 통과시키므로 표로 고정한다.
 */

describe('isInstant', () => {
  it.each([
    ['초 단위 Z', '2026-09-25T00:00:00Z'],
    ['밀리초 소수부', '2026-10-05T01:02:03.456Z'],
    ['소수부 9자리(나노초)', '2026-10-05T01:02:03.123456789Z'],
  ])('통과: %s', (_name, value) => {
    expect(isInstant(value)).toBe(true)
  })

  it.each([
    ['오프셋 형식', '2026-09-25T09:00:00+09:00'],
    ['날짜만', '2026-09-25'],
    ['소수부 10자리', '2026-10-05T01:02:03.1234567890Z'],
    ['소문자 z', '2026-09-25T00:00:00z'],
    ['초 없음', '2026-09-25T00:00Z'],
    ['자연어', 'yesterday'],
    ['빈 문자열', ''],
  ])('거절: %s', (_name, value) => {
    expect(isInstant(value)).toBe(false)
  })

  it.each([
    ['숫자', 1_727_222_400_000],
    ['Date 객체', new Date('2026-09-25T00:00:00Z')],
    ['null', null],
    ['undefined', undefined],
  ])('거절: 문자열이 아닌 %s', (_name, value) => {
    expect(isInstant(value)).toBe(false)
  })
})
