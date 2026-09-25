package com.bokrilog.source.internal;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * 자격정보 암호화 키 묶음. <b>active 하나 + decrypt-only 과거 키.</b>
 *
 * <p>값은 환경이 준다. 데이터베이스에도 Git에도 로그에도 두지 않으며, 이 클래스가 하는 일은 그
 * 문자열을 키로 바꾸고 <b>받아들일 수 없는 값을 조용히 버리는</b> 것뿐이다. production 비밀 공급은
 * Story 3.8이 소유하므로 이 스토리는 읽는 쪽 계약만 정한다.
 *
 * <p>형식은 {@code <버전>:<base64 32바이트>}를 쉼표로 이은 목록이고 active 버전은 별도 이름이다.
 * 목록 형태인 이유는 회전이다 — 새 키를 앞에 더하고 active 버전만 바꾸면 이미 저장된 행이 과거
 * 키로 계속 복호된다.
 *
 * <p><b>fail-closed가 조용하다.</b> 값이 없거나 형식이 아니거나 길이가 32바이트가 아니면 그 키는
 * 묶음에 들어가지 않고, active 버전에 대응하는 키가 없으면 {@link #activeVersion()}이 {@code null}
 * 이다. 그때 연결 기능이 공통 오류 계약으로 거절되며 기동은 성공한다 — 제공자 등록과 같은 관용
 * ({@code AuthSecurityConfig.ProviderClientRegistrations.put}). 어느 값이 문제인지는 예외에도
 * 로그에도 남기지 않는다.
 */
final class CredentialKeyRing {

    /** 버전과 키를 가르는 구분자. base64 알파벳에 없는 문자여야 한다. */
    private static final char VERSION_SEPARATOR = ':';

    private final Map<String, SecretKey> keys;

    private final @Nullable String activeVersion;

    /**
     * @param keyList {@code <버전>:<base64>} 쉼표 목록. {@code null}·빈 값이면 빈 묶음이다
     * @param activeVersion 새 암호화가 쓸 버전. 그 버전의 키가 묶음에 없으면 기능이 fail-closed다
     */
    CredentialKeyRing(@Nullable String keyList, @Nullable String activeVersion) {
        Map<String, SecretKey> parsed = parse(keyList);
        this.keys = Map.copyOf(parsed);
        String requested = activeVersion == null ? null : activeVersion.trim();
        this.activeVersion =
                requested != null && !requested.isEmpty() && parsed.containsKey(requested)
                        ? requested
                        : null;
    }

    /** 새 암호화가 쓸 키의 버전. 구성되지 않았으면 {@code null}이며 그것이 fail-closed의 신호다. */
    @Nullable String activeVersion() {
        return this.activeVersion;
    }

    /** 그 버전의 키. 과거 키도 여기 있다(decrypt-only). 없으면 {@code null}이다. */
    @Nullable SecretKey key(@Nullable String version) {
        return version == null ? null : this.keys.get(version);
    }

    /** 묶음에 든 키의 수. 값이 아니라 개수만 노출한다 — 시험이 구성을 확인하는 유일한 창이다. */
    int size() {
        return this.keys.size();
    }

    private static Map<String, SecretKey> parse(@Nullable String keyList) {
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        if (keyList == null || keyList.isBlank()) {
            return keys;
        }
        for (String entry : keyList.split(",", -1)) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf(VERSION_SEPARATOR);
            if (separator <= 0 || separator == trimmed.length() - 1) {
                // 버전도 키도 비어 있지 않아야 한다. 아니면 그 항목은 없는 것과 같다.
                continue;
            }
            String version = trimmed.substring(0, separator).trim();
            byte[] material = decode(trimmed.substring(separator + 1).trim());
            if (version.isEmpty() || material == null || material.length != CredentialCipher.KEY_BYTES) {
                // AES-256이 아니면 받지 않는다. 짧은 키를 조용히 받아들이면 그 완화가 남는다.
                continue;
            }
            /*
             * 같은 버전 이름이 두 번 나오면 **먼저 적힌 것을 남긴다.**
             *
             * 덮어쓰면 그 버전으로 봉한 행이 영영 열리지 않는다 — 운영자가 새 키를 더하면서 버전
             * 이름을 실수로 재사용하는 것이 그 모양이고, 그때 잃는 것은 이미 저장된 자격정보다.
             * 먼저 적힌 것을 남기면 기존 행은 계속 열리고 새 키는 묶음에 들어가지 못해 관측된다
             * (아래 {@link #size()}가 목록 항목 수보다 작다).
             *
             * 기동을 죽이지 않는 것은 이 클래스의 다른 거절과 같은 규율이다. 구성 하나가 프로세스를
             * 멈추는 자리를 만들지 않는다.
             */
            keys.putIfAbsent(version, new SecretKeySpec(material, "AES"));
        }
        return keys;
    }

    private static byte @Nullable [] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException notBase64) {
            // 값을 예외에 담지 않는다. 형식이 아니면 그 항목이 없는 것과 같다.
            return null;
        }
    }
}
