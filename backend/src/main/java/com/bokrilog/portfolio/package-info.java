/**
 * 기준통화 묶음과 활성 source 구간의 권위.
 *
 * <p>membership과 기본 portfolio를 소유한다(AD-15). <b>계획은 connection이 아니라 portfolio에
 * 귀속한다</b>는 것이 이 모듈이 있는 이유이며, MVP는 사용자당 자동 생성 {@code USD} portfolio 하나에
 * 활성 {@code BYBIT} membership 하나뿐이다. 다중 source·기준통화 변경·transfer matching은 별도
 * 승인 전까지 구현하지 않는다.
 *
 * <p>다른 모듈은 이 패키지의 공개 application API로만 협력하고 {@code internal} 하위 패키지나 소유
 * 테이블에 직접 닿지 않는다. {@code ModuleBoundaryTest}가 이를 강제한다.
 *
 * <p>여기에는 HTTP 표면이 없다({@code identity}·{@code source}와 같은 배치).
 *
 * <p>불변 snapshot·current pointer·발행 순번은 아직 없다. Story 2.2 이후가 자기 계약과 함께
 * 들인다 — 이 스토리가 만드는 것은 그 값들이 올라설 귀속 하나다.
 */
package com.bokrilog.portfolio;
