package com.bokrilog.source;

/**
 * 이 모듈이 아는 금융 source 제공자.
 *
 * <p>닫힌 집합이다. 문자열로 두면 스키마의 CHECK와 애플리케이션이 아는 값이 조용히 갈리고,
 * 그때 거부가 "모르는 제공자"가 아니라 SQL 오류로 나타난다.
 *
 * <p>MVP에는 값이 하나뿐이다. 둘 이상의 금융 source를 한 portfolio에 붙이는 것은 별도 승인
 * 전까지 하지 않으므로(epic-2-context) 값이 늘어나는 것은 그것을 승인하는 스토리의 일이다.
 */
public enum SourceProvider {

    /** Bybit UTA. 저장되는 문자열은 이 이름 그대로다. */
    BYBIT
}
