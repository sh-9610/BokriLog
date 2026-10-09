package com.bokrilog.operations.internal;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 서버가 만드는 불투명 operation ID(Story 3.1). 운영 command와 강한 신호(Story 3.3)가 같은 모양을 쓴다 — 원장의 한
 * operation이 무엇이 냈든 같은 열에 같은 형식으로 묶인다.
 */
final class OperationIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OperationIds() {}

    /** {@code op_} 뒤 18바이트 난수의 URL-safe base64. */
    static String next() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "op_" + ENCODER.encodeToString(bytes);
    }
}
