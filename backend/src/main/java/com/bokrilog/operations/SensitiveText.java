package com.bokrilog.operations;

import com.bokrilog.operations.internal.InquiryRules;

/**
 * 민감값 규칙의 로그 가림(Story 3.7 AC-5). 문의 입력(Story 3.6)이 거절에 쓰는 규칙 한 벌({@code InquiryRules})로 로그
 * 본문의 SECRET·EMAIL·WALLET 모양 조각을 {@code [REDACTED:<규칙>]}로 바꾼다.
 *
 * <p>순수 함수이고 Spring에 기대지 않는다 — Logback encoder가 컨텍스트가 뜨기 전부터 부른다. 패턴 탐지는 가리기만 하고 강한
 * 신호로 올리지 않는다(오탐이 전역 정지로 번지면 안 된다). 신호로 올리는 것은 프로세스가 실제로 가진 비밀값의 등장뿐이다(루트
 * {@code LogSecretGuard}).
 */
public final class SensitiveText {

    private SensitiveText() {}

    /** SECRET·EMAIL·WALLET 모양 조각을 가린 값. 정규화(NFKC·서식 문자 제거)한 값을 돌려준다. */
    public static String redact(String text) {
        return InquiryRules.redact(text);
    }
}
