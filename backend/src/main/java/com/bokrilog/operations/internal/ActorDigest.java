package com.bokrilog.operations.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * 감사 행 수행자의 keyed digest(HMAC-SHA256).
 *
 * <p>원장은 수행자를 내부 {@code user_id}와 이 digest 둘로 남긴다. 계정이 삭제되면 삭제 port가 {@code user_id}를 비우고
 * digest만 남는다 — 키를 가진 쪽만 같은 수행자의 행을 묶을 수 있고, 행 하나만으로는 사람을 되짚을 수 없다. 평문 SHA-256이
 * 아닌 이유가 그것이다: {@code user_id}는 UUID라 후보 공간이 작지 않지만, 다른 표에 남은 같은 값과 대조하는 데에는
 * 키가 필요 없어진다.
 *
 * <p><b>키가 없거나 짧으면 생성이 실패한다 — 그것이 API 기동 거부다.</b> 키 없이 뜨면 감사 행을 쓸 수 없고, 감사 없는
 * 운영 command는 허용되지 않는다(AC-4·AC-6). 조용히 "운영 command 비활성"으로 접지 않는 이유는 그 상태가 운영자에게
 * 404로만 보여 원인을 찾을 자리가 없기 때문이다. 메시지에 값을 담지 않는다.
 */
final class ActorDigest {

    /** 키의 최소 길이(바이트). HMAC-SHA256의 출력 길이와 같다. */
    static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    ActorDigest(@Nullable String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("bokrilog.operations.audit-digest-key가 비어 있다. 감사 원장을 쓸 수 없다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalStateException("bokrilog.operations.audit-digest-key가 base64가 아니다.");
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "bokrilog.operations.audit-digest-key가 " + MIN_KEY_BYTES + "바이트보다 짧다.");
        }
        this.key = new SecretKeySpec(decoded, ALGORITHM);
    }

    /** 수행자의 digest. 소문자 16진 64자. */
    String of(UUID userId) {
        return digest("operator:" + userId);
    }

    /**
     * 사용자에게 귀속된 제한 target의 digest(Story 3.2). 소문자 16진 64자.
     *
     * <p>수행자 digest와 접두사가 달라 같은 UUID가 두 자리에서 같은 값이 되지 않는다. 감사 원장의 target과 계정 삭제로
     * 비식별된 제한 행의 target이 같은 값이라, 키를 가진 쪽만 둘을 묶을 수 있다.
     */
    String ofTarget(String scope, String target) {
        return digest("restriction-target:" + scope + ":" + target);
    }

    /**
     * 사람이 아닌 SYSTEM 수행자의 digest(Story 3.3). 소문자 16진 64자. 감사 원장은 모든 행에 수행자 digest를 요구하므로
     * 자동 정지 경로의 이름을 묶는다 — 운영자·target digest와 접두사가 달라 겹치지 않는다.
     */
    String ofSystem(String name) {
        return digest("system:" + name);
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(this.key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // 모든 JDK가 HmacSHA256을 제공하고 키는 생성자에서 이미 검사했다.
            throw new IllegalStateException("HmacSHA256을 쓸 수 없다.", impossible);
        }
    }
}
