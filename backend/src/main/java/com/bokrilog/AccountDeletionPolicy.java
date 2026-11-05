package com.bokrilog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 계정 삭제 정책(Story 5.1, {@code ACCOUNT-DELETION-1.0}). 사용자가 삭제를 요청하기 전에 보는 고지의 <b>유일한 원본</b>이다.
 *
 * <p>담는 것은 넷이다 — 삭제 대상 code 목록({@link #TARGETS}), 백업 파기 기한({@link #BACKUP_DESTROY_DAYS}), 법정 분리 보존 항목
 * ({@link #LEGAL_RETENTION}, 지금 빈 목록), 확정에 다시 입력할 고정 문구({@link #CONFIRMATION}). 화면은 code를 자기 닫힌 맵으로
 * 카피에 잇고, 두 벌의 code 목록은 공유 표({@code frontend/src/content/accountDeletionPolicy.fixture.json})를 프런트·백엔드 시험이
 * 함께 읽어 묶는다.
 *
 * <p><b>법정 분리 보존은 G-5 전이다.</b> 빈 목록은 개발·CI 기본값("분리 보존 없음·전부 삭제")이며 production 법률 판단이 아니다 —
 * 그래서 production({@code BOKRILOG_ENV=production})에서는 삭제 요청 자체를 열지 않는다({@code AccountDeletionRequestService}).
 *
 * <p><b>정책을 바꾸면 버전을 올린다.</b> 고지 hash({@link #noticeHash()})가 바뀌므로 이미 본 화면의 확정은 409
 * {@code ACCOUNT_DELETION_POLICY_CHANGED}로 거절되고 사용자는 새 정책을 다시 읽는다.
 */
final class AccountDeletionPolicy {

    /** 정책 버전. V30의 CHECK 모양({@code ACCOUNT-DELETION-<major>.<minor>})이다. */
    static final String VERSION = "ACCOUNT-DELETION-1.0";

    /**
     * 확정에 다시 입력하는 고정 문구. 서비스는 이름·이메일을 저장하지 않으므로 "계정 표시명 재입력" 대신 이 문구다(accepted
     * deviation). 허가의 확인 hash와 확정 대조 둘 다에 들어간다.
     */
    static final String CONFIRMATION = "계정을 삭제합니다";

    /** 백업 사본 파기 기한(요청일부터, 일). 일일 백업의 30일 회전 파기(Story 3.9)와 같은 수다. */
    static final int BACKUP_DESTROY_DAYS = 30;

    /**
     * 삭제 대상의 code. 화면이 닫힌 맵으로 카피에 잇는다 — code를 더하면 화면 맵과 공유 표도 함께 바꾼다. 순서는 화면에 그리는
     * 순서다.
     */
    static final List<String> TARGETS = List.of(
            "ACCOUNT_AND_SIGN_IN",
            "SESSIONS",
            "BYBIT_CREDENTIALS",
            "BYBIT_CONNECTIONS",
            "SOURCE_EVENTS",
            "CASHFLOWS",
            "SNAPSHOTS",
            "PLANS",
            "CALCULATIONS",
            "SYNC_JOBS",
            "SUPPORT_INQUIRIES",
            "AD_RECORDS",
            "OPERATIONS_RECORDS");

    /**
     * 법정 분리 보존 항목 하나 — 보존하는 필드, 법적 근거, 목적, 기간. 화면이 네 값을 그대로 보여 준다(G-5 승인이 채운다).
     */
    record LegalRetention(String field, String basis, String purpose, String period) {

        /** 고지 hash의 정규 직렬화 한 항목. */
        String canonical() {
            return String.join("|", this.field, this.basis, this.purpose, this.period);
        }
    }

    /** 법정 분리 보존 항목. G-5 승인 전이라 비어 있다(개발·CI 기본값). */
    static final List<LegalRetention> LEGAL_RETENTION = List.of();

    private AccountDeletionPolicy() {}

    /**
     * 고지 hash — 정책의 정규 직렬화의 SHA-256(소문자 16진 64자). 줄 단위 {@code 이름=값}이고 목록은 쉼표로 잇는다. 요청 행에 남아
     * "사용자가 본 고지"를 가리킨다.
     */
    static String noticeHash() {
        String canonical = String.join("\n",
                "version=" + VERSION,
                "targets=" + String.join(",", TARGETS),
                "backupDestroyDays=" + BACKUP_DESTROY_DAYS,
                "legalRetention=" + String.join(",", LEGAL_RETENTION.stream().map(LegalRetention::canonical).toList()),
                "confirmation=" + CONFIRMATION);
        return sha256(canonical);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
