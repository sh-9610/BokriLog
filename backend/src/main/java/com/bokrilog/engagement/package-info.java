/**
 * 대시보드 광고 설정, 브라우저 계측, 기존 광고 기록의 계정 삭제를 소유한다.
 *
 * <p>{@link com.bokrilog.engagement.DashboardAdApi}는 전역 운영 제한과 공급자 활성 설정을 판독한다. 광고를 준비하는 것은
 * 대시보드이고 동기화와 결과 조회는 이 모듈의 판정을 기다리지 않는다.
 *
 * <p>{@link com.bokrilog.engagement.ClientTelemetryApi}는 같은 출처에서 온 브라우저 이벤트를 닫힌 어휘의 카운터로 센다.
 * 기존 SHOW와 관련된 Vitals 표본 계약은 그 기록의 호환성을 위해 남는다. 활성화된 전역 운영 중단은 그 출처와 관계없이 적용된다.
 *
 * <p>계정 삭제는 {@link com.bokrilog.engagement.EngagementDeletionApi}로 기존 동의·광고 시도 행을 지운다.
 * 이 패키지의 내부 표에는 다른 모듈이 직접 접근하지 않는다.
 */
package com.bokrilog.engagement;
