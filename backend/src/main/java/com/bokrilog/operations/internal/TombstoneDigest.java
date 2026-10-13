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
 * 삭제 tombstone의 사용자 keyed digest(HMAC-SHA256, Story 3.10).
 *
 * <p>{@link ActorDigest}와 같은 모양이지만 <b>키가 다르다</b>({@code BOKRILOG_TOMBSTONE_DIGEST_KEY}). tombstone은 서비스 DB·백업 밖에
 * 35일 남고 복원마다 읽힌다 — 감사 digest 키와 같은 키면 그 사본 하나로 원장의 수행자·제한 target digest와 대조할 길이 생긴다. 입력
 * 접두사는 {@code tombstone:}이다.
 *
 * <p>키가 없거나 짧으면 생성이 실패하고, 그것이 그 역할({@code deletion-sample}·{@code restore-apply})의 기동 거부다. 키 없이 뜬 삭제는
 * 기록 없는 탈퇴가 되고, 키 없이 뜬 복원은 삭제 계정을 가려낼 수 없다. 메시지에 값을 담지 않는다.
 */
final class TombstoneDigest {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    TombstoneDigest(@Nullable String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("bokrilog.tombstone.digest-key가 비어 있다. 삭제 tombstone을 쓸 수 없다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalStateException("bokrilog.tombstone.digest-key가 base64가 아니다.");
        }
        if (decoded.length < ActorDigest.MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "bokrilog.tombstone.digest-key가 " + ActorDigest.MIN_KEY_BYTES + "바이트보다 짧다.");
        }
        this.key = new SecretKeySpec(decoded, ALGORITHM);
    }

    /** 사용자의 digest. 소문자 16진 64자. */
    String of(UUID userId) {
        return hmac("tombstone:" + userId);
    }

    /**
     * 이 키의 ID. 소문자 16진 16자({@code tombstone-key-id} 입력의 HMAC 앞 8바이트 — 키를 되짚을 수 없다). 내보내기 파일 머리에 실리고,
     * 복원은 사본의 키 ID가 자기 키와 다르면 적용하지 않는다(다른 키의 digest는 어떤 계정과도 맞지 않아 조용히 통과하기 때문이다).
     */
    String keyId() {
        return hmac("tombstone-key-id").substring(0, 16);
    }

    /** 이 키의 원 바이트와 같은가(감사 digest 키와의 분리 판정 — base64 표기가 달라도 같은 키를 잡는다). */
    boolean sameKeyAs(byte[] other) {
        return java.security.MessageDigest.isEqual(this.key.getEncoded(), other);
    }

    private String hmac(String value) {
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
