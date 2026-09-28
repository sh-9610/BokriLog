import { describe, expect, it } from 'vitest'

import {
  formatAmount,
  formatPercent,
  formatSeoulInstant,
  formatSeoulTime,
  percentFromRatio,
} from './decimal'

describe('DISPLAY-1.0 formatting', () => {
  it.each([
    ['1234.56', '1,234.56'],
    ['-12.345', '-12.35'],
    ['12.344999999999999999', '12.34'],
    ['0.005', '0.01'],
    ['-0.004', '0.00'],
    ['-0.001', '0.00'],
    ['0', '0.00'],
    ['999999999999999999999999.995', '1,000,000,000,000,000,000,000,000.00'],
    ['1234.5600', '1,234.56'],
  ])('rounds the amount %s HALF_UP to %s', (value, shown) => {
    expect(formatAmount(value)).toBe(shown)
  })

  it.each([
    ['0.0648', '6.48%'],
    ['0.083582089552238805970149', '8.36%'],
    ['0.146315789473684210526316', '14.63%'],
    ['0.755287009063444108761329', '75.53%'],
    ['-0.2', '-20.00%'],
    ['-0.00004', '0.00%'],
    ['12.5', '1,250.00%'],
    ['0', '0.00%'],
    ['0.00005', '0.01%'],
  ])('shows the ratio %s as %s without clipping', (value, shown) => {
    expect(formatPercent(value)).toBe(shown)
  })

  it('leaves a value it cannot read untouched instead of inventing one', () => {
    expect(formatAmount('1E3')).toBe('1E3')
    expect(formatPercent('abc')).toBe('abc')
  })

  it('shows an entered target rate exactly', () => {
    expect(percentFromRatio('0.1')).toBe('10')
    expect(percentFromRatio('0.0123')).toBe('1.23')
    expect(percentFromRatio('10')).toBe('1000')
  })

  it('shows instants in Asia/Seoul', () => {
    const seoul = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date('2026-09-27T15:00:00Z'))
    expect(formatSeoulTime('2026-09-27T15:00:00Z', 'x')).toBe(seoul)
    expect(formatSeoulTime('not a time', 'fallback')).toBe('fallback')
  })

  it('shows an instant to the millisecond in Asia/Seoul where two times must be told apart', () => {
    const parts = new Intl.DateTimeFormat('ko-KR', {
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
    const at = new Date('2026-09-28T16:41:23.456Z')
    expect(formatSeoulInstant('2026-09-28T16:41:23.456Z', 'x')).toBe(parts.format(at))
    // The parts themselves, whatever the ICU punctuation: the Seoul day and hour, the second and the ms.
    const shown = Object.fromEntries(parts.formatToParts(at).map((part) => [part.type, part.value]))
    expect(shown).toMatchObject({
      day: '29',
      hour: '01',
      minute: '41',
      second: '23',
      fractionalSecond: '456',
    })
    // A second apart: the same minute on the dashboard, two different times here.
    expect(formatSeoulInstant('2026-09-27T00:59:59Z', 'x')).not.toBe(
      formatSeoulInstant('2026-09-27T01:00:00Z', 'x'),
    )
    expect(formatSeoulInstant('not a time', 'fallback')).toBe('fallback')
  })
})
