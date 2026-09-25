package com.bokrilog.source.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.jspecify.annotations.Nullable;

/**
 * 자격정보 암호화. <b>이 저장소의 첫 암호화 자재다.</b>
 *
 * <p>AES-256-GCM 하나뿐이다. 매 암호화마다 새 96비트 CSPRNG nonce를 만들고, AAD로
 * {@code user_id}·{@code connection_id}·{@code credential_id}·{@code key_version}·
 * {@code algorithm_version} 다섯을 묶는다. 그 다섯이 암호문에 결합돼 있으므로 행을 통째로 옮겨
 * 붙이는 공격이 복호에서 실패한다 — 다른 연결의 암호문을 이 행에 적으면 AAD가 달라 태그 검증이
 * 깨진다.
 *
 * <p><b>nonce를 재사용하지 않는다.</b> GCM에서 같은 키로 같은 nonce를 두 번 쓰면 평문 복원과 태그
 * 위조가 동시에 열린다. 그래서 nonce는 {@link SecureRandom}이 매번 새로 만들고 저장소의 CHECK가
 * 길이를 고정한다({@code V4__source_connection.sql}).
 *
 * <p><b>key ring은 active 하나 + decrypt-only 과거 키다.</b> 회전이 전체 재암호화를 강제하지
 * 않으려면 과거 키가 남아 있어야 하고, 새 암호화가 과거 키를 쓰지 않으려면 active가 하나여야 한다.
 * 키는 DB·Git·로그 밖에서 주입한다({@link CredentialKeyRing}).
 *
 * <p><b>키가 없으면 기동이 아니라 이 기능이 fail-closed다.</b> 제공자 등록과 같은 관용이며
 * ({@code AuthSecurityConfig.ProviderClientRegistrations}) 근거도 같다 — 개발·CI 스택의 백엔드가
 * 자격정보 없이도 healthy가 되어야 나머지 계약을 검증할 수 있다. {@link #isAvailable()}이 거짓이면
 * 연결 시작이 공통 오류 계약으로 거절된다.
 *
 * <p>어떤 메서드도 평문을 예외 메시지·로그에 담지 않는다. 복호 실패는 값 하나로 접히며 그 값에서
 * "키가 없다"와 "태그가 맞지 않는다"가 구분되지 않는다.
 */
final class CredentialCipher {

    /**
     * 암호 스킴의 버전. 저장되고 AAD에도 든다.
     *
     * <p>AAD에 드는 것이 요점이다. 스킴이 바뀌면 같은 키로도 옛 암호문이 새 AAD로 복호되지
     * 않으므로, 버전 하나를 바꿔 끼워 downgrade시키는 경로가 닫힌다.
     */
    static final String ALGORITHM_VERSION = "AES256GCM-1";

    /** GCM 권장 nonce 길이(바이트). 스키마의 CHECK와 한 쌍이다. */
    static final int NONCE_BYTES = 12;

    /** 인증 태그 길이(비트). 잘라 쓰지 않는다. */
    private static final int TAG_BITS = 128;

    /** AES-256. 32바이트가 아닌 키는 key ring이 받지 않는다. */
    static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialKeyRing keyRing;

    CredentialCipher(CredentialKeyRing keyRing) {
        this.keyRing = keyRing;
    }

    /**
     * 암호문 한 벌.
     *
     * @param keyVersion 이 암호문을 만든 키의 버전. 복호가 같은 키를 고르는 값이다
     * @param algorithmVersion {@link #ALGORITHM_VERSION}
     * @param nonce 이 암호화에만 쓰인 96비트 값
     * @param ciphertext 인증 태그가 붙은 암호문
     */
    record Sealed(String keyVersion, String algorithmVersion, byte[] nonce, byte[] ciphertext) {

        /**
         * 배열을 복사해 든다.
         *
         * <p>record의 기본 접근자는 참조를 그대로 돌려주므로 호출자가 그 배열을 고치면 저장될 값이
         * 바뀐다. 암호문과 nonce는 값이며 공유 가변 상태가 아니다.
         */
        Sealed {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return this.nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return this.ciphertext.clone();
        }

        /** 배열 성분을 든 record의 기본 구현은 참조 비교다. 값 비교로 바꾼다. */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Sealed sealed
                    && this.keyVersion.equals(sealed.keyVersion)
                    && this.algorithmVersion.equals(sealed.algorithmVersion)
                    && Arrays.equals(this.nonce, sealed.nonce)
                    && Arrays.equals(this.ciphertext, sealed.ciphertext);
        }

        @Override
        public int hashCode() {
            return this.keyVersion.hashCode() * 31 + Arrays.hashCode(this.ciphertext);
        }

        /** 암호문의 바이트를 문자열로 만들지 않는다. 로그 한 줄이 그것을 밖으로 나르게 두지 않는다. */
        @Override
        public String toString() {
            return "Sealed[" + this.keyVersion + "/" + this.algorithmVersion + "]";
        }
    }

    /**
     * 이 암호문이 귀속하는 다섯.
     *
     * <p>{@code ActionGrant.Binding}과 같은 배치다 — 암호화와 복호가 같은 다섯을 본다는 것을
     * 시그니처로 강제한다. 축이 하나 빠지면 컴파일되지 않는다.
     */
    record Aad(UUID userId, UUID connectionId, UUID credentialId, String keyVersion,
            String algorithmVersion) {}

    /** 이 기능을 쓸 수 있는가. active 키가 없으면 거짓이며 연결은 fail-closed로 거절된다. */
    boolean isAvailable() {
        return this.keyRing.activeVersion() != null;
    }

    /**
     * 평문 두 값을 한 암호문으로 봉한다.
     *
     * <p>두 값을 길이 접두사로 감싸 잇는다. 구분자 없이 이으면 {@code ("ab","c")}와
     * {@code ("a","bc")}가 같은 바이트열이 되어 복호가 다른 쌍을 돌려줄 수 있다
     * ({@code ActionGrant.canonicalConfirmationHash}와 같은 근거).
     *
     * @param credentialId 이 암호문이 저장될 행의 식별자. 호출자가 먼저 만들어 AAD에 넣는다
     * @return 봉인된 값, active 키가 없으면 빈 값
     */
    Optional<Sealed> seal(UUID userId, UUID connectionId, UUID credentialId, String apiKey,
            String apiSecret) {
        String version = this.keyRing.activeVersion();
        SecretKey key = version == null ? null : this.keyRing.key(version);
        if (version == null || key == null) {
            return Optional.empty();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Aad aad = new Aad(userId, connectionId, credentialId, version, ALGORITHM_VERSION);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aadBytes(aad));
            byte[] ciphertext = cipher.doFinal(payload(apiKey, apiSecret));
            return Optional.of(new Sealed(version, ALGORITHM_VERSION, nonce, ciphertext));
        } catch (GeneralSecurityException failure) {
            /*
             * 배선이 깨졌다는 뜻이다(없는 변환, 길이가 맞지 않는 키). 평문을 든 채로 예외를
             * 올려보내지 않는다 — 메시지도 원인도 담지 않고 빈 값으로 접으면 호출자는 다른
             * fail-closed와 같은 하나를 본다.
             */
            return Optional.empty();
        }
    }

    /**
     * 봉인을 푼다. <b>과거 키로도 복호된다.</b>
     *
     * <p>이 스토리에는 복호를 요구하는 제품 경로가 없다(제공자 호출은 연결 시점의 평문으로 끝난다).
     * 그래도 구현과 시험이 함께 있는 이유는 두 가지다 — 저장된 값이 실제로 되돌려지는지 확인할
     * 자리가 없으면 "암호화했다"가 검증되지 않고, decrypt-only 과거 키 호환은 회전 정책의 일부라
     * 나중에 만들면 그때 저장된 행들이 이미 복호 불가일 수 있다.
     *
     * @return 평문 쌍, 키가 없거나 태그가 맞지 않으면 빈 값. 두 경우가 구분되지 않는다
     */
    Optional<String[]> open(Aad aad, byte[] nonce, byte[] ciphertext) {
        SecretKey key = this.keyRing.key(aad.keyVersion());
        if (key == null || !ALGORITHM_VERSION.equals(aad.algorithmVersion())) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aadBytes(aad));
            return Optional.of(split(cipher.doFinal(ciphertext)));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // 태그 불일치·잘린 암호문·잘못된 nonce 길이가 모두 같은 하나다.
            return Optional.empty();
        }
    }

    /** 길이 접두사로 감싼 평문 두 값. */
    private static byte[] payload(String apiKey, String apiSecret) {
        byte[] first = apiKey.getBytes(StandardCharsets.UTF_8);
        byte[] second = apiSecret.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES * 2 + first.length + second.length)
                .putInt(first.length)
                .put(first)
                .putInt(second.length)
                .put(second)
                .array();
    }

    private static String[] split(byte[] plaintext) {
        ByteBuffer buffer = ByteBuffer.wrap(plaintext);
        byte[] first = new byte[buffer.getInt()];
        buffer.get(first);
        byte[] second = new byte[buffer.getInt()];
        buffer.get(second);
        return new String[] {
            new String(first, StandardCharsets.UTF_8), new String(second, StandardCharsets.UTF_8)
        };
    }

    /**
     * AAD의 정본 바이트열.
     *
     * <p>다섯 값을 길이 접두사로 감싸 잇는다. 구분자 없이 이으면 서로 다른 다섯이 같은 AAD를
     * 만들어 결합이 무른다.
     */
    private static byte[] aadBytes(Aad aad) {
        List<String> parts =
                List.of(
                        aad.userId().toString(),
                        aad.connectionId().toString(),
                        aad.credentialId().toString(),
                        aad.keyVersion(),
                        aad.algorithmVersion());
        int size = 0;
        for (String part : parts) {
            size += Integer.BYTES + part.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length).put(bytes);
        }
        return buffer.array();
    }
}
