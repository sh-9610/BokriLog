package com.bokrilog.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영자 allowlist와 감사 digest 키(Story 3.1).
 *
 * <p><b>운영자는 배포 환경이 정한다.</b> {@code bokrilog.operations.operators}(env {@code BOKRILOG_OPERATORS}) 하나가
 * 유일한 입력이고 형식은 {@code issuer|subject|permissions;…}다 — permissions는 {@code command}·{@code audit}을 쉼표로
 * 잇는다. 사용자 UI로 승격하는 경로는 없다. 값이 비면 운영자가 없고 모든 운영 요청이 404다(fail-closed, ERD §11).
 * 형식이 틀리면 이 record의 생성이 실패하고 그것이 곧 기동 거부다 — 틀린 allowlist를 "운영자 없음"으로 조용히 접으면
 * 운영자는 회수된 줄 모르고, 일부만 읽으면 의도하지 않은 사람이 남는다.
 *
 * <p>여기에는 issuer·subject만 있다. 그 쌍을 내부 {@code user_id}로 푸는 것은 요청마다 루트의 {@code OperatorAccess}가
 * {@code identity} 공개 API로 한다(세션에 역할을 심지 않는다 — 회수가 다음 요청에 바로 반영된다).
 *
 * <p>{@code audit-digest-key}는 감사 행의 actor digest(HMAC-SHA256) 키다. base64 32바이트 이상이어야 하고, 비거나 짧으면
 * API 프로세스가 기동을 거부한다(검사는 servlet 역할의 digest 빈이 한다 — 스키마만 바꾸는 migrate 역할은 이 키를
 * 받지 않는다). 값은 응답·로그 어디에도 나가지 않는다.
 *
 * <p>{@code support-contact}(env {@code BOKRILOG_SUPPORT_CONTACT})는 서비스 공지(Story 3.5)가 싣는 지원 연락처 이메일이다.
 * 비어 있으면 공지 발행이 {@code SUPPORT_CONTACT_UNCONFIGURED}로 거절되고 사용자 공지에 연락처가 없다 — 허위·빈 지원
 * 경로를 만들지 않는다. 이메일 모양이 아니면 allowlist와 같은 이유로 기동을 거부한다.
 *
 * @param operators allowlist 원문
 * @param auditDigestKey actor digest 키(base64)
 * @param supportContact 지원 연락처 이메일. 비어 있으면 없다
 */
@ConfigurationProperties("bokrilog.operations")
public record OperatorProperties(@Nullable String operators, @Nullable String auditDigestKey,
        @Nullable String supportContact) {

    /** 지원 연락처의 모양. 한 주소이고 공백·꺾쇠·따옴표가 없다(mailto 링크에 그대로 싣는다). */
    private static final java.util.regex.Pattern SUPPORT_CONTACT =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** 지원 연락처의 최대 길이(RFC 5321 경로 상한). */
    private static final int MAX_SUPPORT_CONTACT_CHARS = 254;

    /** allowlist 한 줄. {@code issuer}·{@code subject}는 설정 그대로이며 응답·로그·감사 행에 나가지 않는다. */
    public record Operator(String issuer, String subject, Set<OperatorPermission> permissions) {

        public Operator {
            permissions = Collections.unmodifiableSet(EnumSet.copyOf(permissions));
        }
    }

    public OperatorProperties {
        // 형식 검사가 곧 기동 거부다. 메시지에 값을 담지 않는다 — 기동 로그에 allowlist가 남는다.
        parseOperators(operators);
        supportContact = parseSupportContact(supportContact);
    }

    /**
     * 지원 연락처를 푼다. 비었으면 {@code null}이고, 양 끝 공백을 걷은 값이 이메일 모양이 아니면 형식 오류다. 시험이 연락처를
     * 바꿀 때도 같은 함수를 지난다(루트 {@code SupportContact}의 재해석 훅).
     *
     * @throws IllegalArgumentException 형식 오류. 메시지에 값을 담지 않는다
     */
    public static @Nullable String parseSupportContact(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String contact = text.strip();
        if (contact.length() > MAX_SUPPORT_CONTACT_CHARS || !SUPPORT_CONTACT.matcher(contact).matches()) {
            throw new IllegalArgumentException("bokrilog.operations.support-contact가 이메일 주소 형식이 아니다.");
        }
        return contact;
    }

    /** 파싱한 allowlist. 비면 운영자가 없다. */
    public List<Operator> allowlist() {
        return parseOperators(this.operators);
    }

    /**
     * allowlist 원문을 푼다. 시험이 회수를 흉내 낼 때도 같은 함수를 지난다(루트 {@code OperatorAccess}의 재해석 훅).
     *
     * <p>빈 줄·양 끝 공백은 무시한다. 줄마다 세 칸이고 issuer·subject는 비어 있지 않으며 권한은 하나 이상의 아는
     * 값이다. 같은 {@code (issuer, subject)}가 두 번 나오면 형식 오류다 — 둘의 권한 중 어느 것이 이기는지 정할 근거가
     * 없다.
     *
     * @throws IllegalArgumentException 형식 오류. 메시지에 값을 담지 않는다
     */
    public static List<Operator> parseOperators(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Operator> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (String line : text.split(";", -1)) {
            index++;
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 3) {
                throw malformed(index);
            }
            String issuer = parts[0].strip();
            String subject = parts[1].strip();
            if (issuer.isEmpty() || subject.isEmpty() || issuer.length() > 512 || subject.length() > 255) {
                throw malformed(index);
            }
            Set<OperatorPermission> permissions = EnumSet.noneOf(OperatorPermission.class);
            for (String permission : parts[2].split(",", -1)) {
                OperatorPermission known = OperatorPermission.parse(permission);
                if (known == null) {
                    throw malformed(index);
                }
                permissions.add(known);
            }
            if (!seen.add(issuer + "\n" + subject)) {
                throw malformed(index);
            }
            parsed.add(new Operator(issuer, subject, permissions));
        }
        return List.copyOf(parsed);
    }

    private static IllegalArgumentException malformed(int entry) {
        return new IllegalArgumentException(
                "bokrilog.operations.operators의 " + entry + "번째 항목이 issuer|subject|permissions 형식이 아니다.");
    }
}
