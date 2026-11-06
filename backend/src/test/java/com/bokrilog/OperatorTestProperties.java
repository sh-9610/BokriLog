package com.bokrilog;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 시험 컨텍스트의 운영자 설정(Story 3.1).
 *
 * <p>감사 digest 키는 API 기동의 필수값이라 servlet 시험 컨텍스트마다 있어야 한다. 키는 <b>시험 JVM이 실행 시점에</b>
 * 만든다 — 32바이트의 base64는 자격정보 모양 리터럴이고 gitleaks가 잡는다(값이 가짜여도). allowlist는 비워 둔다: 기본
 * 상태가 "운영자 없음"이어야 그 상태의 404가 시험되고, 운영자가 필요한 시험은 {@code OperatorAccess.reinterpret} 훅으로
 * 자기 운영자를 세운다.
 */
final class OperatorTestProperties {

    /** 시험 컨텍스트의 지원 연락처. */
    static final String SUPPORT_CONTACT = "support@bokrilog.test";

    /** 이 JVM의 digest 키. 컨텍스트가 몇 개 뜨든 같은 값이라 컨텍스트 캐시 키가 갈리지 않는다. */
    static final String AUDIT_DIGEST_KEY = newKey();

    /** 이 JVM의 tombstone digest 키(Story 3.10). 감사 digest 키와 다른 값이다(같으면 기동 거부). */
    static final String TOMBSTONE_DIGEST_KEY = newKey();

    private OperatorTestProperties() {}

    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bokrilog.operations.audit-digest-key", () -> AUDIT_DIGEST_KEY);
        registry.add("bokrilog.operations.operators", () -> "");
        // 서비스 공지(Story 3.5)의 지원 연락처. 예약 최상위 도메인 `.test`의 개발용 주소다. 비어 있는 경우는
        // `SupportContact.reinterpret` 훅으로 시험한다.
        registry.add("bokrilog.operations.support-contact", () -> SUPPORT_CONTACT);
        // 삭제 tombstone(Story 3.10)의 digest 키. servlet 시험 컨텍스트에서도 `AccountErasure`·복원 적용을 바로 부를 수 있게 준다.
        registry.add("bokrilog.tombstone.digest-key", () -> TOMBSTONE_DIGEST_KEY);
        // 계정 삭제 Worker(Story 5.2)의 주기 dispatch는 끈다 — 키가 있으므로 켜 두면 삭제 요청 시험(5.1)의 계정을 Worker가 지워
        // 그 시험의 단정이 경합한다. Worker의 시험은 `AccountErasureWorker.runOnce`로 한 번씩 몬다.
        registry.add("bokrilog.account-deletion.worker.scheduled", () -> "false");
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
