package com.bokrilog.operations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 사용자 문의 접수와 영업일 3일 답변 목표(Story 3.6, {@code SUPPORT-1.0}).
 *
 * <p><b>접수.</b> 인증 사용자의 문의 하나를 민감값 규칙({@link SensitiveRule})으로 거르고, 문의·접수 시각(DB 시각)·불투명
 * reference·답변 목표 시각·달력 버전·시간대·상태를 <b>한 INSERT</b>로 쓴다. 같은 사용자·같은 멱등 키는 같은 접수 결과이고,
 * 같은 키의 다른 입력은 충돌이다 — 권위는 {@code (user_id, idempotency_key_digest)} 유일 색인이다. 사용자당 접수는 DB 시각
 * 기준 최근 24시간 {@value #DAILY_LIMIT}건이다.
 *
 * <p><b>답변 목표.</b> 버전 seed 달력({@value #CALENDAR_VERSION})으로 "접수 다음 영업일부터 세 번째 영업일 18:00 KST"를
 * 계산한다. 계산에 필요한 연도가 달력에 없으면 목표를 정하지 않고({@link TargetState#CALENDAR_PENDING}) 접수는 막지 않는다.
 * 지금 달력이 그 연도를 담게 되면 다음 조회 트랜잭션이 미정 목표를 채운다(이미 정해진 목표는 소급 재계산하지 않는다).
 * 이 값은 "답변 목표"다 — 보장 약속이 아니다.
 *
 * <p><b>운영 처리.</b> 상태 전이와 답변은 운영 command {@code update-inquiry}({@link OperatorCommandApi#UPDATE_INQUIRY})만
 * 쓴다 — 감사 {@code COMPLETED} 행과 같은 트랜잭션이다. 이 인터페이스는 접수·조회·집계만 한다.
 *
 * <p><b>보존.</b> 해결 뒤 {@link #RETENTION_AFTER_RESOLVED}가 지난 문의는 모든 조회({@link #mine}·{@link #recent}·
 * {@link #detail}·{@link #metrics})의 트랜잭션 시작에서 지운다(백그라운드 없음, DB 시각). 같은 자리에서 미정 목표를 채운다. 계정 삭제는 deletion port
 * ({@link OperationsDeletionApi#eraseInquiries})가 지운다.
 *
 * <p>이 모듈은 HTTP·세션을 모른다. 인증·계정 접근·멱등 키의 모양은 루트가 본다.
 */
public interface SupportInquiryApi {

    /** 지금 쓰는 영업일 달력 버전. 새 연도의 달력은 새 버전의 새 마이그레이션과 이 값의 변경으로 더한다. */
    String CALENDAR_VERSION = "KR-HOLIDAY-2026.1";

    /** 영업일 판정의 시간대. */
    String TIMEZONE = "Asia/Seoul";

    int MAX_TITLE_CHARS = 80;

    int MAX_DESCRIPTION_CHARS = 2000;

    /** 운영자 답변의 최대 길이(문자). */
    int MAX_ANSWER_CHARS = 1000;

    /** 회신 이메일의 최대 길이(RFC 5321 경로 상한). */
    int MAX_EMAIL_CHARS = 254;

    /** 사용자당 최근 24시간 접수 상한(Ask First로 고정). */
    int DAILY_LIMIT = 5;

    /** 접수 상한의 창. */
    Duration DAILY_WINDOW = Duration.ofHours(24);

    /** 해결 뒤 보존 기간(Ask First로 고정). 이보다 오래된 해결 문의는 다음 조회에서 사라진다. */
    Duration RETENTION_AFTER_RESOLVED = Duration.ofDays(90);

    /** 사용자 목록의 상한(최근 순). */
    int MAX_LISTED = 50;

    /** 운영자 목록의 상한(최근 순). */
    int MAX_OPERATOR_LISTED = 200;

    /** 집계 기간의 최대 일수(양 끝 포함). */
    int MAX_METRICS_DAYS = 366;

    /** 문의 범주. 스키마 CHECK와 같은 집합이다. */
    enum Category {
        ACCOUNT,
        CONNECTION,
        SYNC_PLAN,
        SERVICE_NOTICE,
        OTHER
    }

    /** 회신 경로. 기본은 서비스 안(설정 화면의 상태·답변)이고, 이메일은 사용자가 직접 적은 주소다(실제 발송은 없다). */
    enum Contact {
        IN_APP,
        EMAIL
    }

    /** 문의 상태. 단조 전진이고 {@link #ANSWERED}를 건너뛸 수 없다. 스키마 CHECK와 같은 집합이다. */
    enum Status {
        RECEIVED,
        IN_PROGRESS,
        ANSWERED,
        RESOLVED;

        /**
         * 이 상태에서 {@code next}로 옮길 수 있는가. 앞으로만 가고, {@link #RESOLVED}는 {@link #ANSWERED}에서만 간다(답변
         * 없이 해결로 닫지 않는다).
         */
        public boolean canMoveTo(Status next) {
            if (next.ordinal() <= ordinal()) {
                return false;
            }
            return next != RESOLVED || this == ANSWERED;
        }
    }

    /** 답변 목표의 상태. */
    enum TargetState {
        /** 목표 시각이 정해졌다. */
        SET,
        /** 계산에 필요한 연도가 달력에 없어 목표를 아직 정하지 못했다. 접수는 막지 않는다. */
        CALENDAR_PENDING
    }

    /** 민감값 규칙. 거절 응답·로그에는 이 code만 싣고 원문을 싣지 않는다. */
    enum SensitiveRule {
        /** API Key·Secret·토큰 모양(16자 이상 영숫자·{@code -_} 연속, {@code Bearer }, {@code -----BEGIN}). */
        SECRET,
        /** 지갑 주소 모양({@code 0x}+40 hex, 26~62자 base58·bech32). */
        WALLET,
        /** 금융값 모양(구분자 포함 숫자 6자리 이상, 통화 기호·{@code USD}·{@code USDT}·{@code 원}과 붙은 숫자). */
        FINANCIAL,
        /** 이메일 모양. */
        EMAIL
    }

    /** 입력 필드. 필드 오류·민감값 거절이 어느 필드인지 말한다. */
    enum Field {
        CATEGORY,
        TITLE,
        DESCRIPTION,
        CONTACT,
        REPLY_EMAIL
    }

    /** 사용자가 보낸 입력 원문. 검증 전이라 모두 비어 있을 수 있다. */
    record Draft(@Nullable String category, @Nullable String title, @Nullable String description,
            @Nullable String contact, @Nullable String replyEmail) {}

    /**
     * 접수 결과.
     *
     * @param targetAt 답변 목표 시각. 목표 미정이면 {@code null}
     */
    record Receipt(String reference, Instant receivedAt, @Nullable Instant targetAt, TargetState targetState,
            Status status) {}

    /** 접수의 결말. */
    sealed interface Submission {
        /**
         * 접수됐다.
         *
         * @param replayed 같은 키의 앞선 접수를 돌려줬다(새 행이 없다)
         */
        record Received(Receipt receipt, boolean replayed) implements Submission {}

        /** 필드가 비었거나 모양·길이가 맞지 않다. */
        record Invalid(Field field) implements Submission {}

        /** 제목·본문에 민감값 모양이 있다. 원문을 담지 않는다. */
        record Sensitive(Field field, SensitiveRule rule) implements Submission {}

        /** 최근 24시간 접수 상한을 넘는다. 아무것도 쓰지 않았다. */
        record RateLimited() implements Submission {}

        /** 같은 키가 다른 입력에 이미 쓰였다. */
        record Conflict() implements Submission {}

        /**
         * 접수 트랜잭션의 생명주기 잠금 아래에서 본 계정이 쓰기 불가다(Story 5.2) — 삭제가 시작됐거나 끝났다. 아무것도 쓰지 않았다.
         * 호출자는 없는 경로와 같은 404로 답한다.
         */
        record NotPermitted() implements Submission {}
    }

    /**
     * 접수한다. 멱등 키의 모양은 호출자(루트)가 먼저 본다 — 여기에는 digest만 남는다.
     *
     * @param idempotencyKey 규칙을 지난 멱등 키 원문
     */
    Submission submit(UUID userId, String idempotencyKey, Draft draft);

    /**
     * 사용자의 문의 하나(사용자 목록).
     *
     * @param answeredAt 첫 답변 시각. 답변 전이면 {@code null}
     */
    record UserInquiry(String reference, Category category, String title, String description, Instant receivedAt,
            @Nullable Instant targetAt, TargetState targetState, Status status, @Nullable String answer,
            @Nullable Instant answeredAt) {}

    /** 그 사용자의 문의, 최근 순 {@value #MAX_LISTED}건까지. 보존 기간이 지난 해결 문의를 먼저 지운다. */
    List<UserInquiry> mine(UUID userId);

    /**
     * 운영자 목록의 문의 하나. 최소화했다 — 본문·답변이 없고 회신 이메일은 마스킹한 값이다. 사용자 식별자가 없다.
     *
     * @param maskedReplyEmail 마스킹한 회신 이메일({@code a***@d***}). 서비스 안 회신이면 {@code null}
     * @param targetMet 첫 답변이 목표 안이었는가. 답변 전이거나 목표 미정이면 {@code null}
     */
    record OperatorInquiry(String reference, Category category, String title, Status status, Instant receivedAt,
            @Nullable Instant targetAt, TargetState targetState, @Nullable Instant firstAnsweredAt,
            @Nullable Boolean targetMet, Contact contact, @Nullable String maskedReplyEmail,
            @Nullable Instant resolvedAt) {}

    /**
     * 운영자 목록. 미해결({@code RECEIVED}·{@code IN_PROGRESS}·{@code ANSWERED})이 먼저이고 그 안에서 최근 접수 순,
     * {@value #MAX_OPERATOR_LISTED}건까지다. 상한 밖의 문의는 상태 필터나 reference 상세 조회로 연다. 보존 기간이 지난 해결
     * 문의를 먼저 지운다.
     *
     * @param status 그 상태만. {@code null}이면 전부
     */
    List<OperatorInquiry> recent(@Nullable Status status);

    /**
     * 운영자 상세: 목록의 값에 본문·답변과 회신 이메일 원문을 더한다. 원문은 운영자가 자기 메일로 회신하는 유일한 용도라
     * 상세에만 있다(목록은 마스킹).
     */
    record OperatorInquiryDetail(OperatorInquiry summary, String description, @Nullable String answer,
            @Nullable String replyEmail) {}

    /** 운영자 상세. 없거나(보존 삭제·계정 삭제 포함) reference 모양이 아니면 빈 값이다. */
    Optional<OperatorInquiryDetail> detail(String reference);

    /**
     * 기간 집계(AC-5). 기간은 KST 달력 날짜이고 양 끝을 포함한다.
     *
     * <p>분모({@code due})는 목표 시각이 그 기간 안이고 이미 지난 문의, 분자({@code metTarget})는 그 가운데 첫 답변이 목표
     * 시각 이전인 것이다. 목표 미정 문의는 분모에서 빼고 접수 시각이 그 기간 안인 것을 따로 센다({@code calendarPending}).
     * 처리 영업시간은 분모의 문의 가운데 답변한 것의 접수 → 첫 답변 사이 영업시간(영업일 09~18 KST)이고, 그 문의의 목표를
     * 정한 달력 버전({@code calendar_version})으로 센다 — 달력이 담지 않은 날을 지나는 문의는 세지 않는다({@code measured}가
     * 실제로 센 수).
     *
     * @param ratePercent 달성률(%) 소수 첫째 자리 HALF_UP. 분모가 0이면 {@code null}
     * @param totalBusinessMinutes 처리 영업시간 합계(분, 문의마다 초 단위 합을 분으로 내림)
     * @param medianBusinessMinutes 처리 영업시간 중앙값(분). 짝수 개면 가운데 둘의 평균(소수 첫째 자리). 없으면 {@code null}
     * @param purgeHorizon 기간 시작이 보존 경계(지금 − 90일)보다 앞서면 그 경계. 그 이전에 해결된 문의는 이미 지워져 집계에
     *     없다. 기간이 경계 안이면 {@code null}
     */
    record Metrics(LocalDate from, LocalDate to, Instant asOf, String calendarVersion, long due, long metTarget,
            @Nullable BigDecimal ratePercent, long calendarPending, long measured, long totalBusinessMinutes,
            @Nullable BigDecimal medianBusinessMinutes, @Nullable Instant purgeHorizon) {}

    /**
     * 기간 집계. 기간이 거꾸로이거나 {@value #MAX_METRICS_DAYS}일보다 길면 호출자가 먼저 거절한다.
     *
     * @throws IllegalArgumentException 기간이 거꾸로이거나 너무 길 때
     */
    Metrics metrics(LocalDate from, LocalDate to);
}
