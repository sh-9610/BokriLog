package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 재가입 차단 digest({@link ActorDigest#ofDeletedIdentity}, Story 5.4)의 정답 대조. 기대값은 시험이 {@code javax.crypto}로 따로 계산한다 —
 * 입력 직렬화({@code deleted-identity:<issuer 길이>:<issuer>:<subject>})와 키(감사 digest 키의 base64 해독 바이트)가 정확히 그 모양인지
 * 고정한다. 키는 실행 시점에 바이트로 만든다(base64 리터럴은 gitleaks가 잡는다).
 */
class ActorDigestTest {

    private static final byte[] KEY = keyBytes(7);

    private final ActorDigest digest = new ActorDigest(Base64.getEncoder().encodeToString(KEY));

    @Test
    void 삭제_identity_digest는_정해진_입력의_HMAC_SHA256이다() throws Exception {
        String issuer = "https://accounts.example";
        String subject = "subject-1";
        assertThat(this.digest.ofDeletedIdentity(issuer, subject))
                .isEqualTo(hmac(KEY, "deleted-identity:24:https://accounts.example:subject-1"))
                .matches("[0-9a-f]{64}");
        // 비ASCII issuer: 길이는 문자 수이고 바이트는 UTF-8이다.
        assertThat(this.digest.ofDeletedIdentity("이슈어", "s")).isEqualTo(hmac(KEY, "deleted-identity:3:이슈어:s"));
    }

    @Test
    void 경계가_모호한_쌍과_다른_digest와_다른_키는_서로_다른_값이다() {
        assertThat(this.digest.ofDeletedIdentity("a:b", "c")).isNotEqualTo(this.digest.ofDeletedIdentity("a", "b:c"));
        assertThat(this.digest.ofDeletedIdentity("ab", "c")).isNotEqualTo(this.digest.ofDeletedIdentity("a", "bc"));
        assertThat(this.digest.ofDeletedIdentity("", "a:b")).isNotEqualTo(this.digest.ofDeletedIdentity("a", "b"));
        // 같은 문자열이 다른 접두사의 digest와 겹치지 않는다.
        UUID user = UUID.randomUUID();
        assertThat(this.digest.ofDeletedIdentity("operator", user.toString())).isNotEqualTo(this.digest.of(user));
        // 키가 다르면 값이 다르다.
        ActorDigest other = new ActorDigest(Base64.getEncoder().encodeToString(keyBytes(11)));
        assertThat(other.ofDeletedIdentity("i", "s")).isNotEqualTo(this.digest.ofDeletedIdentity("i", "s"));
    }

    private static byte[] keyBytes(int seed) {
        byte[] bytes = new byte[ActorDigest.MIN_KEY_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return bytes;
    }

    private static String hmac(byte[] key, String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
