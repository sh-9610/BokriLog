/**
 * 복리 계획 지표의 순수 결정 함수(Story 2.7, {@code CALC-1.0}·{@code CALENDAR-SEOUL-1.0}).
 *
 * <p><b>부작용이 없다.</b> 데이터베이스·HTTP·Spring·시스템 시계·Bybit와 다른 모듈의 타입에 닿지
 * 않고, provider 중립 값 객체만 받는다. 기준시각은 호출자가 명시적으로 넘기며 이 모듈이 "지금"을
 * 읽지 않는다. {@code ModuleBoundaryTest}가 이를 바이트코드 수준에서 강제한다.
 *
 * <p>공개 표면은 {@link com.bokrilog.calculation.PlanCalculator} 하나와 그 값 객체다. 달력 경계와
 * 공식은 {@code internal}에 있고 다른 모듈은 그 패키지에 닿지 못한다.
 *
 * <p>모든 산술은 {@code MathContext(34, HALF_EVEN)}이고 거듭제곱은 버전 고정
 * {@code ch.obermuhlner:big-math}의 {@code pow}다. 결과는 canonical decimal string(최대 34
 * 유효숫자·scale 24)이며 같은 입력·정책·구현 버전은 처리 순서와 관계없이 같은 결과와 hash를 낸다.
 * 표시 반올림은 이 모듈의 일이 아니다.
 */
package com.bokrilog.calculation;
