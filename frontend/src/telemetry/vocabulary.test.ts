import { describe, expect, it } from 'vitest'

import { AD_TRANSITION_PATH } from '../content/adTransition'
import { DASHBOARD_PATH, SETTINGS_PATH } from '../content/auth'
import { planConditionContent } from '../content/planConditions'
import {
  AD_EVENTS,
  AD_FAILURE_REASONS,
  MAX_BATCH_BYTES,
  MAX_BATCH_EVENTS,
  TELEMETRY_PATH,
  TELEMETRY_ROUTES,
  VITAL_METRICS,
} from './beacon'
import vocabulary from './telemetryVocabulary.fixture.json'

/**
 * Story 4.5: 계측 어휘의 두 벌 대조. 서버(`ClientTelemetryApi`·`ClientTelemetryController`)는 어휘 밖이 하나라도 있으면 묶음 전체를
 * 400으로 거절한다. 공유 표 하나를 이 시험과 백엔드 `ClientTelemetryVocabularyTest`가 함께 읽어 두 벌이 갈라지지 않게 묶는다.
 */
describe('계측 어휘', () => {
  it('경로·묶음 상한·metric·화면·광고 이벤트·실패 사유가 공유 표와 같다', () => {
    expect(TELEMETRY_PATH).toBe(vocabulary.path)
    expect(MAX_BATCH_EVENTS).toBe(vocabulary.maxEvents)
    expect(MAX_BATCH_BYTES).toBe(vocabulary.maxBodyBytes)
    expect([...VITAL_METRICS]).toEqual(vocabulary.metrics)
    expect([...TELEMETRY_ROUTES]).toEqual(vocabulary.routes)
    expect([...AD_EVENTS]).toEqual(vocabulary.adEvents)
    expect([...AD_FAILURE_REASONS]).toEqual(vocabulary.failureReasons)
  })

  it('측정 화면은 네 화면 경로 그대로다(진입·온보딩·운영자 없음)', () => {
    expect([...TELEMETRY_ROUTES]).toEqual([
      DASHBOARD_PATH,
      SETTINGS_PATH,
      planConditionContent.path,
      AD_TRANSITION_PATH,
    ])
  })
})
