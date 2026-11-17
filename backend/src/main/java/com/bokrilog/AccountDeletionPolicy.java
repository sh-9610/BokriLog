package com.bokrilog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 계정 삭제 정책(Story 5.1, 지금 {@code ACCOUNT-DELETION-1.1}). 사용자가 삭제를 요청하기 전에 보는 고지의 서버 쪽 원본이다.
 *
 * <p>담는 것은 다섯이다 — 삭제 대상 code 목록({@link #TARGETS}), 백업 파기 기한({@link #BACKUP_DESTROY_DAYS}), 법정 분리 보존 항목
 * ({@link #LEGAL_RETENTION}, 지금 빈 목록), 확정에 다시 입력할 고정 문구({@link #CONFIRMATION}), 화면 고지 문장의 digest
 * ({@link #NOTICE_DIGEST}). 화면은 code를 자기 닫힌 맵으로 카피에 잇고, 두 벌의 code 목록은 공유 표
 * ({@code frontend/src/content/accountDeletionPolicy.fixture.json})를 프런트·백엔드 시험이 함께 읽어 묶는다.
 *
 * <p><b>고지 문장의 원본은 공유 표의 {@code notice} 하나다</b>(G-5 전 결정 D-3). 삭제 대상 문장·남기는 것·백업·법정 보존 없음 문장을 화면이
 * 그 표에서 읽고, 서버는 그 정규 직렬화의 SHA-256({@link #NOTICE_DIGEST})을 고지 hash 입력에 넣는다 — 그래서 고지 hash가 사용자가 읽은
 * 문장까지 가리킨다. 표는 버전마다 고지 digest 한 행({@code noticeDigestByVersion})을 남기고, 백엔드 시험({@code AccountDeletionRequestTest})이
 * (a) 지금 {@link #VERSION}의 행 = 이 상수 = 표 {@code notice}에서 다시 계산한 값과 (b) 행의 digest가 서로 모두 다름을 단정한다. 그래서
 * 문장을 바꾸면 새 버전 행을 더하고 {@link #VERSION}과 이 상수를 올려야 시험이 통과한다 — 버전을 그대로 두고 지금 행을 고쳐 쓰는 길은 기계가
 * 막지 못하고, 리뷰에 이력 수정으로 보이는 것이 그 장치다.
 *
 * <p><b>법정 분리 보존은 G-5 전이다.</b> 빈 목록은 개발·CI 기본값("분리 보존 없음·전부 삭제")이며 production 법률 판단이 아니다 —
 * 그래서 production({@code BOKRILOG_ENV=production})에서는 삭제 요청 자체를 열지 않는다({@code AccountDeletionRequestService}).
 *
 * <p><b>정책을 바꾸면 버전을 올린다.</b> 고지 hash({@link #noticeHash()})가 바뀌므로 이미 본 화면의 확정은 409
 * {@code ACCOUNT_DELETION_POLICY_CHANGED}로 거절되고 사용자는 새 정책을 다시 읽는다.
 */
final class AccountDeletionPolicy {

    /** 정책 버전. V30의 CHECK 모양({@code ACCOUNT-DELETION-<major>.<minor>})이다. */
    static final String VERSION = "ACCOUNT-DELETION-1.1";

    /**
     * 확정에 다시 입력하는 고정 문구. 서비스는 이름·이메일을 저장하지 않으므로 "계정 표시명 재입력" 대신 이 문구다(accepted
     * deviation). 허가의 확인 hash와 확정 대조 둘 다에 들어간다.
     */
    static final String CONFIRMATION = "계정을 삭제합니다";

    /**
     * 백업 사본 파기 기한(일). 사용자 고지는 "운영 데이터 삭제를 마친 날부터 최대 이 일 수"다(D-1) — 마지막으로 사용자 데이터를 담을 수 있는
     * generation은 운영 완료 직후(커밋 여유 1시간 안)에 시작한 것이고 일일 백업의 30일 회전 파기(Story 3.9)가 그것을 지운다. 내부 경보 기한
     * (요청 + 이 일 수 → {@code OVERDUE})은 그보다 엄격한 목표로 그대로 둔다.
     */
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

    /**
     * 화면 고지 문장의 digest — 공유 표 {@code notice}의 정규 직렬화(줄 단위 {@code target.<CODE>=<문장>}을 {@link #TARGETS} 순서로, 이어
     * {@code kept=<문장>}을 배열 순서로, {@code backup=<틀>}, {@code legalNone=<문장>}을 {@code \n}으로 이은 UTF-8)의 SHA-256(소문자 16진
     * 64자). 서버는 문장을 갖지 않고 이 값만 갖는다 — 대조는 백엔드 시험이 표에서 다시 계산해서 한다(JSON 재직렬화에 기대지 않는다).
     */
    static final String NOTICE_DIGEST = "b10f460fe193e1c4e2ee576ed6f1f05a6a4019182963d62ab9135a66d36f5427";

    private AccountDeletionPolicy() {}

    /**
     * 고지 hash — 정책의 정규 직렬화의 SHA-256(소문자 16진 64자). 줄 단위 {@code 이름=값}이고 목록은 쉼표로 잇는다. 마지막 줄이 화면 고지
     * 문장의 digest({@code notice=}{@link #NOTICE_DIGEST})다. 요청 행에 남아 "사용자가 본 고지"를 가리킨다.
     */
    static String noticeHash() {
        String canonical = String.join("\n",
                "version=" + VERSION,
                "targets=" + String.join(",", TARGETS),
                "backupDestroyDays=" + BACKUP_DESTROY_DAYS,
                "legalRetention=" + String.join(",", LEGAL_RETENTION.stream().map(LegalRetention::canonical).toList()),
                "confirmation=" + CONFIRMATION,
                "notice=" + NOTICE_DIGEST);
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
