package com.bokrilog.source.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 자격정보 암호화의 계약(Story 2.1).
 *
 * <p>Spring 컨텍스트도 데이터베이스도 쓰지 않는다. 여기서 확인하는 것은 실행 시점의 배선이 아니라
 * 암호문의 성질이며, 그래서 이 시험은 Docker 없이 돈다({@code ModuleBoundaryTest}와 같은 판단).
 *
 * <p><b>키를 실행 시점에 만든다.</b> 자격정보 모양 리터럴을 시험 fixture에 두지 않는다는 규율이
 * 여기에도 그대로 적용된다.
 */
class CredentialCipherTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ACTIVE = "v2";

    private static final String PREVIOUS = "v1";

    @Test
    void 같은_평문을_두_번_봉해도_nonce와_암호문이_다르다() {
        CredentialCipher cipher = cipher(ACTIVE);
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        CredentialCipher.Sealed first =
                cipher.seal(userId, connectionId, UUID.randomUUID(), "key", "secret").orElseThrow();
        CredentialCipher.Sealed second =
                cipher.seal(userId, connectionId, UUID.randomUUID(), "key", "secret").orElseThrow();

        assertThat(first.nonce()).hasSize(CredentialCipher.NONCE_BYTES);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void 암호문에_평문_조각이_남지_않는다() {
        CredentialCipher cipher = cipher(ACTIVE);
        String apiKey = "plain-api-key-" + UUID.randomUUID();
        String apiSecret = "plain-api-secret-" + UUID.randomUUID();

        CredentialCipher.Sealed sealed =
                cipher.seal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), apiKey, apiSecret)
                        .orElseThrow();

        String hex = HexFormat.of().formatHex(sealed.ciphertext());
        assertThat(hex).doesNotContain(hexOf(apiKey));
        assertThat(hex).doesNotContain(hexOf(apiSecret));
        // 봉인된 값의 문자열 표현에도 평문이 없다. 로그 한 줄이 그것을 밖으로 나르지 못한다.
        assertThat(sealed.toString()).doesNotContain(apiKey).doesNotContain(apiSecret);
    }

    @Test
    void 같은_AAD로만_열린다() {
        CredentialCipher cipher = cipher(ACTIVE);
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        CredentialCipher.Sealed sealed =
                cipher.seal(userId, connectionId, credentialId, "api-key", "api-secret").orElseThrow();

        CredentialCipher.Aad correct =
                new CredentialCipher.Aad(
                        userId, connectionId, credentialId, ACTIVE, CredentialCipher.ALGORITHM_VERSION);
        assertThat(cipher.open(correct, sealed.nonce(), sealed.ciphertext()))
                .get()
                .satisfies(plain -> assertThat(plain).containsExactly("api-key", "api-secret"));

        // 다섯 축 하나씩을 어긋나게 한다. 어느 하나라도 다르면 태그 검증이 깨진다.
        List<CredentialCipher.Aad> tampered = new ArrayList<>();
        tampered.add(
                new CredentialCipher.Aad(
                        UUID.randomUUID(),
                        connectionId,
                        credentialId,
                        ACTIVE,
                        CredentialCipher.ALGORITHM_VERSION));
        tampered.add(
                new CredentialCipher.Aad(
                        userId,
                        UUID.randomUUID(),
                        credentialId,
                        ACTIVE,
                        CredentialCipher.ALGORITHM_VERSION));
        tampered.add(
                new CredentialCipher.Aad(
                        userId,
                        connectionId,
                        UUID.randomUUID(),
                        ACTIVE,
                        CredentialCipher.ALGORITHM_VERSION));
        tampered.add(
                new CredentialCipher.Aad(
                        userId,
                        connectionId,
                        credentialId,
                        PREVIOUS,
                        CredentialCipher.ALGORITHM_VERSION));
        tampered.add(
                new CredentialCipher.Aad(userId, connectionId, credentialId, ACTIVE, "AES256GCM-0"));

        for (CredentialCipher.Aad aad : tampered) {
            assertThat(cipher.open(aad, sealed.nonce(), sealed.ciphertext()))
                    .withFailMessage("어긋난 AAD로 열렸다: %s", aad)
                    .isEmpty();
        }
    }

    @Test
    void 과거_키로_봉한_값이_회전_뒤에도_열린다() {
        String keyRing = PREVIOUS + ":" + newKey() + "," + ACTIVE + ":" + newKey();
        CredentialCipher before = new CredentialCipher(new CredentialKeyRing(keyRing, PREVIOUS));
        CredentialCipher after = new CredentialCipher(new CredentialKeyRing(keyRing, ACTIVE));

        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        CredentialCipher.Sealed sealed =
                before.seal(userId, connectionId, credentialId, "old-key", "old-secret").orElseThrow();
        assertThat(sealed.keyVersion()).isEqualTo(PREVIOUS);

        // 회전 뒤의 새 암호화는 active 키를 쓴다.
        CredentialCipher.Sealed fresh =
                after.seal(userId, connectionId, UUID.randomUUID(), "new-key", "new-secret")
                        .orElseThrow();
        assertThat(fresh.keyVersion()).isEqualTo(ACTIVE);

        // 그래도 과거 키로 봉한 값은 그대로 열린다. 회전이 전체 재암호화를 강제하지 않는다.
        CredentialCipher.Aad aad =
                new CredentialCipher.Aad(
                        userId, connectionId, credentialId, PREVIOUS, CredentialCipher.ALGORITHM_VERSION);
        assertThat(after.open(aad, sealed.nonce(), sealed.ciphertext()))
                .get()
                .satisfies(plain -> assertThat(plain).containsExactly("old-key", "old-secret"));
    }

    @Test
    void key_ring이_없으면_봉하지_못하고_기능이_닫힌다() {
        CredentialCipher cipher = new CredentialCipher(new CredentialKeyRing(null, null));

        assertThat(cipher.isAvailable()).isFalse();
        Optional<CredentialCipher.Sealed> sealed =
                cipher.seal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "k", "s");
        assertThat(sealed).isEmpty();
    }

    @Test
    void 받아들일_수_없는_키는_묶음에_들어가지_않는다() {
        // 32바이트가 아닌 키, base64가 아닌 값, 버전이 빈 항목, 구분자가 없는 항목.
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String keyRing =
                "short:" + shortKey + ",bad:!!!notbase64!!!,:" + newKey() + ",noseparator," + ACTIVE
                        + ":" + newKey();
        CredentialKeyRing ring = new CredentialKeyRing(keyRing, ACTIVE);

        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.activeVersion()).isEqualTo(ACTIVE);
        assertThat(ring.key("short")).isNull();
        assertThat(ring.key("bad")).isNull();
    }

    @Test
    void 같은_버전_이름이_두_번_나오면_먼저_적힌_키가_남는다() {
        /*
         * 덮어쓰면 그 버전으로 봉한 행이 영영 열리지 않는다 — 운영자가 새 키를 더하면서 버전
         * 이름을 실수로 재사용하는 것이 그 모양이고, 그때 잃는 것은 이미 저장된 자격정보다.
         */
        String first = newKey();
        String second = newKey();
        CredentialKeyRing ring =
                new CredentialKeyRing(ACTIVE + ":" + first + "," + ACTIVE + ":" + second, ACTIVE);

        assertThat(ring.size()).isEqualTo(1);

        // 먼저 적힌 키로 봉한 값이 그 묶음으로 열린다. 나중 것이 이겼다면 열리지 않는다.
        CredentialCipher expected = new CredentialCipher(new CredentialKeyRing(ACTIVE + ":" + first, ACTIVE));
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        CredentialCipher.Sealed sealed =
                expected.seal(userId, connectionId, credentialId, "key", "secret").orElseThrow();
        CredentialCipher.Aad aad =
                new CredentialCipher.Aad(
                        userId, connectionId, credentialId, ACTIVE, CredentialCipher.ALGORITHM_VERSION);

        assertThat(new CredentialCipher(ring).open(aad, sealed.nonce(), sealed.ciphertext()))
                .get()
                .satisfies(plain -> assertThat(plain).containsExactly("key", "secret"));
    }

    @Test
    void active_버전의_키가_없으면_기능이_닫힌다() {
        CredentialKeyRing ring = new CredentialKeyRing(PREVIOUS + ":" + newKey(), ACTIVE);

        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.activeVersion()).isNull();
        assertThat(new CredentialCipher(ring).isAvailable()).isFalse();
    }

    private static CredentialCipher cipher(String activeVersion) {
        String keyRing = PREVIOUS + ":" + newKey() + "," + ACTIVE + ":" + newKey();
        return new CredentialCipher(new CredentialKeyRing(keyRing, activeVersion));
    }

    private static String newKey() {
        byte[] material = new byte[CredentialCipher.KEY_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    private static String hexOf(String value) {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }
}
