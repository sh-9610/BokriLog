package com.bokrilog.identity;

import com.bokrilog.PostgresTestcontainersConfig;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * identity 모듈 시험(웹 없는 컨텍스트)의 공용 base — 재가입 차단 digest의 키를 받는 컨텍스트(Story 5.4).
 *
 * <p>재가입 차단 digest({@code ActorDigest.ofDeletedIdentity})의 HMAC 키는 감사 digest 키({@code bokrilog.operations.audit-digest-key})다.
 * 그 키가 없는 프로세스는 새 매핑(가입·연결)을 만들지 않고, 매핑이 있는 계정을 지우지 않는다(차단 기록 없는 삭제를 하지 않는다 — fail-closed).
 * 그래서 매핑을 만들거나 지우는 시험은 이 base를 상속해 키를 받는다. 키 없는 경로는 상속하지 않는 {@link DeletedIdentityKeylessTest}가 본다.
 *
 * <p>키는 <b>시험 JVM이 실행 시점에</b> 만든다 — 32바이트의 base64는 자격정보 모양 리터럴이고 gitleaks가 잡는다(값이 가짜여도). JVM 안에서 한
 * 값이라 이 base를 상속한 클래스의 컨텍스트 캐시 키는 갈리지 않는다(속성·대역을 더한 클래스만 따로 뜬다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfig.class)
abstract class IdentityKeyedTestBase {

    /** 이 JVM의 감사 digest 키. */
    private static final String AUDIT_DIGEST_KEY = newKey();

    @DynamicPropertySource
    static void auditDigestKey(DynamicPropertyRegistry registry) {
        registry.add("bokrilog.operations.audit-digest-key", () -> AUDIT_DIGEST_KEY);
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
