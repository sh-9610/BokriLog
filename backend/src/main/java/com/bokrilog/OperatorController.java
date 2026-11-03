package com.bokrilog;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.ServiceNoticeApi;
import com.bokrilog.operations.SupportInquiryApi;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호된 운영자 표면의 HTTP(Story 3.1). 모든 경로가 {@code /api/operator} 아래이고 같은 술어 하나
 * ({@code AuthSecurityConfig.isOperatorPath})로 인증·세션 귀속 CSRF 토큰·동일 출처 {@code Origin}을 지나며, 그 뒤
 * {@link OperatorGate}가 운영자·권한을 판정한다. 비운영자·권한 부족·미존재 대상은 모두 없는 경로와 같은 404다.
 *
 * <ul>
 *   <li>{@code GET /api/operator/audit?from&to&command&scope&outcome&cursor&limit}(감사 권한): 최신순 allowlist 필드,
 *       {@code (recordedAt, sequence)} keyset cursor, 쪽당 최대 200행. 날짜는 KST 달력 날짜(양 끝 포함). 읽을 수 없는
 *       값은 400.
 *   <li>{@code GET /api/operator/operations/{operationId}}: 그 운영자 자신의 operation 행, 오래된 순. 남의·미존재는 404.
 *   <li>{@code POST /api/operator/commands/{type}}(준비, command 권한 + type별 권한): {@code {scope, target, reason}}과
 *       {@code Idempotency-Key}. 200 {@code {operationId, command, scope, target, reason, confirmationPhrase,
 *       policyVersion, requestedAt}}. 허가 참조는 서버 밖으로 나가지 않는다.
 *   <li>{@code POST /api/operator/commands/{type}/confirm}: {@code {operationId, confirmation}}과 같은 키. 200
 *       {@code {operationId, outcome: "COMPLETED", command, target, upperSequence, rowCount, truncated, rows}} — 같은
 *       키의 재전송도 같은 묶음이다(회전 없음).
 *   <li>{@code POST /api/operator/commands/{type}/cancel}: 무장과 그 허가를 버리고 {@code CANCELLED} 행을 남긴다. 무장이
 *       없어도 204.
 *   <li>Story 3.2의 제한 생성 command 넷({@code restrict-new-connection}·{@code restrict-sync}·
 *       {@code restrict-calculation-display}·{@code restrict-advertising})도 같은 세 경로다. 준비 응답은
 *       {@code impact {blockedStages, affectedPlans, affectedConnections, remainingFeatures}}(수만, 식별자 없음)를 더
 *       싣고, 확정 응답은 {@code {operationId, outcome, command, feature, scope, restrictionId, effectiveFrom}}이다.
 *   <li>Story 3.4의 해제 command {@code lift-restriction}도 같은 세 경로다. 준비 본문은 {@code {target: <제한 ID>, reason:
 *       <원인>, verification, evidenceRef, deployVersion, conditions[4]}}이고 응답 {@code impact}는 {@code {reopenedStages,
 *       remainingRestrictions[{feature, scope, origin}], linkedSignals, latestSignalAt}}(식별자 없음)다. 확정 응답은
 *       {@code {operationId, outcome, command, feature, scope, restrictionId, liftedAt, smoke {status, reopenedStages,
 *       stillBlockedStages}}}이다.
 *   <li>{@code GET /api/operator/restrictions}(command 권한): 효력 중인 제한, 구체 우선. 출처(운영자·자동 정지, Story
 *       3.3)·기능·scope·target(비식별된 것은 {@code targetErased})·시작 시각·사유·수행자 digest.
 *   <li>Story 3.5의 공지 발행 command {@code publish-notice}도 같은 세 경로다. 준비 본문은 {@code {incident: "NEW"|<사건 ID>,
 *       status, covers[<제한 ID>], message, nextUpdateAt}}이고 응답 {@code impact}는 {@code {incident, version, status,
 *       affectedFeatures, continuingFeatures, covers, nextUpdateAt}}이다. 확정 응답은 {@code {operationId, outcome, command,
 *       incidentId, version, status, publishedAt}}이다.
 *   <li>{@code GET /api/operator/notices}(command 권한, Story 3.5): 최근 사건과 그 버전 이력(읽기 전용), 그리고 지원 연락처가
 *       설정됐는지({@code supportContactConfigured}). 사건의 최신 버전은 {@code liveCovers}(덮는 제한의 되돌림 계보 가운데
 *       지금 열린 제한)를 더 싣는다.
 *   <li>Story 3.6의 문의 처리 command {@code update-inquiry}도 같은 세 경로다. 준비 본문은 {@code {reference, status, reason,
 *       answer}}이고 응답 {@code impact}는 {@code {reference, currentStatus, nextStatus, targetAt, answerProvided}}다. 확정
 *       응답은 {@code {operationId, outcome, command, status, transitionedAt}}이다(reference·답변 원문 없음).
 *   <li>{@code GET /api/operator/inquiries?status}(command 권한, Story 3.6): 최소화한 문의 목록 — 범주·제목·상태·접수·목표·
 *       첫 답변·달성 여부와 마스킹한 회신 이메일. 미해결이 먼저이고 {@code status}로 거른다. 본문과 회신 이메일 원문은
 *       {@code GET /api/operator/inquiries/{reference}}에서만 보인다.
 *       {@code GET /api/operator/inquiries/metrics?from&to}는 그 기간(KST 날짜, 양 끝 포함)의 답변 목표 달성 수·처리 영업시간을
 *       돌려준다. 읽을 수 없는 기간은 400이다.
 * </ul>
 *
 * <p>거절은 공통 오류 모양이다: 400 본문 모양, 403 {@code REAUTHENTICATION_REQUIRED}(준비)·
 * {@code OPERATOR_COMMAND_NOT_CONFIRMED}(확정), 409 {@code CONFIRMATION_MISMATCH}·{@code IDEMPOTENCY_CONFLICT}, 422
 * {@code IDEMPOTENCY_KEY_REQUIRED}·{@code REASON_REQUIRED}·{@code REASON_INVALID}·{@code UNSUPPORTED_SCOPE}·
 * {@code TARGET_INVALID}·{@code ACTOR_NOT_ACCEPTED}, 409 {@code ALREADY_RESTRICTED}(Story 3.2), 409
 * {@code RESTRICTION_NOT_ACTIVE}·{@code NEW_SIGNAL_SINCE_PREPARE}과 422 {@code RECOVERY_CONDITION_MISSING}·
 * {@code RECOVERY_EVIDENCE_INVALID}(Story 3.4), 422 {@code SUPPORT_CONTACT_UNCONFIGURED}·{@code NEXT_UPDATE_INVALID}과 409
 * {@code NOTICE_VERSION_CONFLICT}·{@code INCIDENT_CLOSED}(Story 3.5), 실패는 500. 404 밖의 응답은 {@code no-store}다.
 *
 * <p>한 요청에 운영 로그 한 줄 — 단계·결과 code·상태·지연뿐이다. 수행자·대상·사유·operation ID·키·허가를 남기지 않는다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OperatorController {

    static final String OPERATIONS_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/operations";

    /** 효력 중인 제한의 목록(Story 3.2). command 권한이다. */
    static final String RESTRICTIONS_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/restrictions";

    /** 공지 사건 목록(Story 3.5). command 권한이다. */
    static final String NOTICES_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/notices";

    /** 문의 목록·상세·집계(Story 3.6). command 권한이다. */
    static final String INQUIRIES_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/inquiries";

    /** 문의 집계의 하위 경로. reference 모양({@code Q-…})과 겹치지 않는다. */
    static final String INQUIRY_METRICS_PATH = INQUIRIES_PATH + "/metrics";

    static final String CONFIRM_SEGMENT = "confirm";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperatorController.class);

    /** 요청 본문의 상한. 준비 본문은 사유 500자를 담아도 2KiB 안이다. */
    private static final int MAX_BODY_CHARS = 4096;

    /** 확인 문구의 상한. 서버 문구는 50자 안팎이고 허가 포트는 4KiB에서 자른다. */
    private static final int MAX_CONFIRMATION_CHARS = 256;

    /** 본문이 수행자를 주장하는 이름. 하나라도 있으면 거절한다 — 수행자는 세션이 정한다. */
    private static final Set<String> ACTOR_FIELDS =
            Set.of("actor", "actorId", "actorUserId", "actorDigest", "operator", "operatorId", "userId");

    private static final Pattern OPERATION_ID = Pattern.compile("op_[A-Za-z0-9_-]{1,60}");

    private static final Pattern COMMAND_FILTER = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** 감사 scope 필터. 제한 command의 원장 scope는 {@code <기능>:<scope>}다(Story 3.2). */
    private static final Pattern SCOPE_FILTER = Pattern.compile("[A-Z][A-Z_]{0,30}(:[A-Z][A-Z_]{0,30})?");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern LIMIT = Pattern.compile("\\d{1,4}");

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    record Refusal(HttpStatus status, String message) {}

    /** 감사 내보내기의 {@code TARGET_INVALID} 문장. 대상이 기간이다. */
    static final String EXPORT_TARGET_INVALID = "대상 기간이 올바르지 않습니다. 시작일과 종료일을 366일 이내로 지정해 주세요.";

    /** 거절 code → 상태와 안전한 문장. 닫힌 맵이며 machine 값은 code뿐이다. */
    static final Map<String, Refusal> REFUSALS = Map.ofEntries(
            Map.entry(OperatorCommandService.REAUTHENTICATION_REQUIRED, new Refusal(HttpStatus.FORBIDDEN,
                    "운영 명령을 준비하려면 먼저 본인 확인이 필요합니다.")),
            Map.entry(OperatorCommandService.NOT_CONFIRMED, new Refusal(HttpStatus.FORBIDDEN,
                    "확인된 운영 명령이 아니어서 실행하지 않았습니다. 명령을 다시 준비해 주세요.")),
            Map.entry(OperatorCommandService.CONFIRMATION_MISMATCH, new Refusal(HttpStatus.CONFLICT,
                    "확인 문구가 일치하지 않아 실행하지 않았습니다. 화면의 문구를 그대로 입력해 주세요.")),
            Map.entry(IdempotencyKey.CONFLICT_CODE, new Refusal(HttpStatus.CONFLICT,
                    "같은 요청 키가 다른 운영 명령에 이미 쓰였습니다. 명령을 새로 준비해 주세요.")),
            Map.entry(IdempotencyKey.REQUIRED_CODE, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED)),
            Map.entry(OperatorCommandApi.REASON_REQUIRED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "사유를 입력해 주세요.")),
            Map.entry(OperatorCommandApi.REASON_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "사유는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다.")),
            Map.entry(OperatorCommandApi.UNSUPPORTED_SCOPE, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "이 운영 명령이 지원하지 않는 범위입니다.")),
            Map.entry(OperatorCommandApi.TARGET_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "대상이 올바르지 않거나 지금 이 명령의 대상이 될 수 없습니다. 대상을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.ALREADY_RESTRICTED, new Refusal(HttpStatus.CONFLICT,
                    "같은 기능·범위·대상의 제한이 이미 효력 중입니다. 활성 제한 목록을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.ACTOR_NOT_ACCEPTED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "요청에 수행자를 담을 수 없습니다. 수행자는 로그인 세션이 정합니다.")),
            Map.entry(OperatorCommandApi.RESTRICTION_NOT_ACTIVE, new Refusal(HttpStatus.CONFLICT,
                    "이 제한은 지금 효력 중이 아니어서 복구하지 않았습니다. 활성 제한 목록을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.RECOVERY_CONDITION_MISSING, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "복구 조건 네 가지를 모두 확인해야 복구를 준비할 수 있습니다.")),
            Map.entry(OperatorCommandApi.RECOVERY_EVIDENCE_REQUIRED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "수행한 검증·증거 참조·배포 버전을 모두 입력해 주세요.")),
            Map.entry(OperatorCommandApi.RECOVERY_EVIDENCE_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "수행한 검증과 증거 참조는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다. "
                            + "배포 버전은 영문·숫자와 . _ + - 만으로 64자 이내이고, 복구 조건은 아는 조건을 한 번씩만 "
                            + "보냅니다.")),
            Map.entry(OperatorCommandApi.NEW_SIGNAL_SINCE_PREPARE, new Refusal(HttpStatus.CONFLICT,
                    "준비 뒤 이 제한에 새 자동 정지 신호가 연결되어 복구하지 않았습니다. 다시 검증한 뒤 새로 준비해 주세요.")),
            Map.entry(OperatorCommandApi.SUPPORT_CONTACT_UNCONFIGURED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "지원 연락처가 설정되지 않아 공지를 발행하지 않았습니다. 배포 설정을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.NOTICE_VERSION_CONFLICT, new Refusal(HttpStatus.CONFLICT,
                    "준비 뒤 같은 사건의 공지가 먼저 발행되어 발행하지 않았습니다. 사건 목록을 확인하고 새로 준비해 주세요.")),
            Map.entry(OperatorCommandApi.INCIDENT_CLOSED, new Refusal(HttpStatus.CONFLICT,
                    "복구로 닫힌 사건이라 새 공지를 발행하지 않았습니다. 새 사건으로 발행해 주세요.")),
            Map.entry(OperatorCommandApi.NEXT_UPDATE_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "다음 안내 시각은 지금보다 미래이고 7일 이내여야 합니다. 복구 공지에는 다음 안내 시각을 넣지 않습니다.")),
            Map.entry(OperatorCommandApi.INQUIRY_TRANSITION_INVALID, new Refusal(HttpStatus.CONFLICT,
                    "이 문의를 그 상태로 옮길 수 없습니다. 상태는 접수 → 처리 중 → 답변 완료 → 해결 순서로만 바뀌고 답변 완료를 "
                            + "건너뛸 수 없습니다. 문의 목록을 다시 조회해 주세요.")),
            Map.entry(OperatorCommandApi.INQUIRY_ANSWER_REQUIRED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "답변 완료로 옮기려면 답변을 입력해 주세요.")),
            Map.entry(OperatorCommandApi.INQUIRY_ANSWER_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "답변은 1000자 이내여야 하고 답변 완료로 옮길 때만 실을 수 있습니다.")),
            Map.entry(OperatorCommandApi.INQUIRY_SENSITIVE_CONTENT, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "답변에 자격정보·지갑 주소·금융값·이메일처럼 보이는 값이 있어 받지 않았습니다. 그 값을 빼고 다시 입력해 주세요.")));

    /** 문의 처리의 {@code TARGET_INVALID} 문장. 대상이 문의 reference다(Story 3.6). */
    static final String INQUIRY_TARGET_INVALID = "문의를 찾지 못했습니다. 보존 기간이 지났거나 삭제된 문의일 수 있습니다. 문의 "
            + "목록을 다시 조회해 주세요.";

    /** 공지 발행의 {@code REASON_INVALID} 문장. 메시지 규칙이 사유 규칙보다 좁다(Story 3.5). */
    static final String NOTICE_MESSAGE_INVALID = "공지 메시지는 200자 이내의 한 줄 문장이어야 하고 이메일 주소·숫자 6자리 이상 "
            + "연속·URL·경로를 담을 수 없습니다.";

    /** 공지 발행의 {@code TARGET_INVALID} 문장. 대상이 사건과 덮는 제한이다(Story 3.5). */
    static final String NOTICE_TARGET_INVALID = "사건이 없거나, 덮을 제한이 없거나 지금 효력 중이 아닙니다. 사건 목록과 활성 "
            + "제한 목록을 확인해 주세요.";

    /** 복구 공지의 {@code TARGET_INVALID} 문장. 덮는 제한이 아직 효력 중이거나 사건이 없다(Story 3.5 리뷰). */
    static final String NOTICE_RECOVERY_TARGET_INVALID = "사건이 없거나 아직 효력 중인 제한이 있어 복구 공지를 낼 수 "
            + "없습니다. 제한을 먼저 복구하고 사건 목록을 확인해 주세요.";

    /** 공지 발행의 {@code REASON_REQUIRED} 문장. */
    static final String NOTICE_MESSAGE_REQUIRED = "공지 메시지를 입력해 주세요.";

    /**
     * command별 거절 문장 — 같은 code의 다른 문장이며 machine 값은 code 하나다(상태는 {@link #REFUSALS}의 것 그대로).
     * 키는 {@code <command>|<code>}이고, 같은 code의 문장을 사실이 가르면 {@code <command>|<code>|<사실>}이 먼저다(공지
     * 발행은 공지 상태). 없으면 공통 문장이다. 감사 내보내기의 대상은 기간, 문의 처리의 대상은 reference, 공지 발행의 대상은
     * 사건과 덮는 제한이다.
     */
    static final Map<String, String> COMMAND_REFUSALS = Map.ofEntries(
            Map.entry(OperatorCommandApi.AUDIT_EXPORT + "|" + OperatorCommandApi.TARGET_INVALID, EXPORT_TARGET_INVALID),
            Map.entry(OperatorCommandApi.UPDATE_INQUIRY + "|" + OperatorCommandApi.TARGET_INVALID,
                    INQUIRY_TARGET_INVALID),
            Map.entry(OperatorCommandApi.PUBLISH_NOTICE + "|" + OperatorCommandApi.TARGET_INVALID + "|RECOVERED",
                    NOTICE_RECOVERY_TARGET_INVALID),
            Map.entry(OperatorCommandApi.PUBLISH_NOTICE + "|" + OperatorCommandApi.TARGET_INVALID,
                    NOTICE_TARGET_INVALID),
            Map.entry(OperatorCommandApi.PUBLISH_NOTICE + "|" + OperatorCommandApi.REASON_INVALID,
                    NOTICE_MESSAGE_INVALID),
            Map.entry(OperatorCommandApi.PUBLISH_NOTICE + "|" + OperatorCommandApi.REASON_REQUIRED,
                    NOTICE_MESSAGE_REQUIRED));

    /** 감사 행 묶음 한 쪽. 행은 allowlist 필드뿐이다({@link OperatorAuditApi.Entry#view}). */
    record PageView(List<Map<String, Object>> items, @Nullable String nextCursor) {}

    record OperationView(String operationId, List<Map<String, Object>> items) {}

    /**
     * 준비 응답. {@code impact}는 그 command의 미리보기이고(제한·해제·공지·문의, 모양은 그 command가 정한다) 감사
     * 내보내기에는 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PreparedView(String operationId, String command, String scope, String target, String reason,
            String confirmationPhrase, String policyVersion, String requestedAt,
            @Nullable Map<String, Object> impact) {}

    /** 운영자 목록의 문의 하나(Story 3.6). 본문·답변이 없고 회신 이메일은 마스킹했다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InquirySummaryView(String reference, String category, String title, String status, String receivedAt,
            @Nullable String targetAt, String targetState, @Nullable String firstAnsweredAt, @Nullable Boolean targetMet,
            String contact, @Nullable String replyEmail, @Nullable String resolvedAt) {

        static InquirySummaryView of(SupportInquiryApi.OperatorInquiry inquiry) {
            return new InquirySummaryView(inquiry.reference(), inquiry.category().name(), inquiry.title(),
                    inquiry.status().name(), inquiry.receivedAt().toString(), text(inquiry.targetAt()),
                    inquiry.targetState().name(), text(inquiry.firstAnsweredAt()), inquiry.targetMet(),
                    inquiry.contact().name(), inquiry.maskedReplyEmail(), text(inquiry.resolvedAt()));
        }
    }

    record InquiryListView(List<InquirySummaryView> items) {}

    /**
     * 운영자 상세(Story 3.6): 요약에 본문·답변과 회신 이메일 원문을 더한다. 원문은 운영자가 자기 메일로 회신하는 유일한 용도라
     * 상세에만 있다(요약·목록은 마스킹).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InquiryDetailView(InquirySummaryView summary, String description, @Nullable String answer,
            @Nullable String replyEmail) {}

    /** 문의 집계(Story 3.6). 수만 담는다 — 달성 선언 문장이 없다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record InquiryMetricsView(String from, String to, String asOf, String calendarVersion, long due, long metTarget,
            java.math.@Nullable BigDecimal ratePercent, long calendarPending, long measured, long totalBusinessMinutes,
            java.math.@Nullable BigDecimal medianBusinessMinutes, @Nullable String purgeHorizon) {}

    private static @Nullable String text(java.time.@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** 공지 버전 하나(Story 3.5, 운영자 목록). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoticeVersionView(int version, @Nullable Integer previousVersion, String status,
            List<String> affectedFeatures, List<String> continuingFeatures, String message,
            @Nullable String nextUpdateAt, @Nullable String recoveredAt, List<UUID> covers, String operationId,
            String supportContact, String createdAt, @Nullable List<UUID> liveCovers) {

        /**
         * @param liveCovers 사건의 최신 버전에만 싣는다 — 덮는 제한의 되돌림 계보 가운데 지금 열린 제한(Epic 3 회고 AI-1).
         *     운영자 화면이 다음 공지의 덮는 제한 기본값과 "아직 효력 중" 집계에 그대로 쓴다. 이전 버전은 {@code null}(생략)이다
         */
        static NoticeVersionView of(ServiceNoticeApi.NoticeVersion version, @Nullable List<UUID> liveCovers) {
            return new NoticeVersionView(version.version(), version.previousVersion(), version.status().name(),
                    names(version.affectedFeatures()), names(version.continuingFeatures()), version.message(),
                    version.nextUpdateAt() == null ? null : version.nextUpdateAt().toString(),
                    version.recoveredAt() == null ? null : version.recoveredAt().toString(), version.covers(),
                    version.operationId(), version.supportContact(), version.createdAt().toString(), liveCovers);
        }
    }

    /** 사건 하나의 응답. 최신 버전에 그 덮는 제한의 지금 열린 계보({@code ServiceNoticeApi.Incident#liveCovers})를 싣는다. */
    private static IncidentView incidentView(ServiceNoticeApi.Incident incident) {
        List<ServiceNoticeApi.NoticeVersion> versions = incident.versions();
        List<NoticeVersionView> views = new java.util.ArrayList<>();
        for (int index = 0; index < versions.size(); index++) {
            views.add(NoticeVersionView.of(versions.get(index),
                    index == versions.size() - 1 ? incident.liveCovers() : null));
        }
        return new IncidentView(incident.incidentId(), incident.startedAt().toString(), incident.closed(), views);
    }

    /** 사건 하나와 그 버전 이력(오래된 순). {@code closed}는 복구로 닫혀 새 버전을 받지 않는다는 뜻이다. */
    record IncidentView(UUID incidentId, String startedAt, boolean closed, List<NoticeVersionView> versions) {}

    record IncidentListView(boolean supportContactConfigured, List<IncidentView> items) {}

    static List<String> names(List<RestrictionFeature> features) {
        return features.stream().map(Enum::name).toList();
    }

    /**
     * 효력 중인 제한 하나(Story 3.2). 비식별된 target은 싣지 않고 {@code targetErased}로 말한다. {@code origin}은 그 제한을
     * 만든 출처다(Story 3.3) — {@code OPERATOR}이거나 강한 신호의 자동 정지 {@code SIGNAL}이다. {@code linkedSignals}는 그
     * 제한을 만들었거나 그것에 연결된 강한 신호 수이고, 하나라도 있으면 출처와 무관하게 {@code requiresVerification}이 참이다
     * — 화면은 {@code 수동 검증 필요}로 보이고 복구 미리보기(Story 3.4)는 신호 계보를 보인다. {@code reinstated}는 해제
     * smoke가 어긋나 같은 범위로 되돌린 행이다(Story 3.4) — 그 행의 사유는 해제 원인이 아니라 표지
     * {@code SMOKE_REVERTED}이고, 신호 수는 되돌린 원 제한의 계보까지 센다. {@code reinstatedFrom}은 그 행이 되돌린 제한의
     * ID다(되돌림 행에만 있다). 정보용이다 — 화면의 공지 덮는 제한 기본값은 이 직접 부모를 따라가지 않고 사건 목록의
     * {@code liveCovers}(서버가 계보 전체에서 계산한다)를 쓴다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RestrictionView(UUID restrictionId, String feature, String scope, @Nullable String target,
            boolean targetErased, String effectiveFrom, @Nullable String reason, @Nullable String actorDigest,
            String origin, int linkedSignals, boolean requiresVerification, boolean reinstated,
            @Nullable UUID reinstatedFrom) {

        static RestrictionView of(OperationalRestrictionApi.ActiveRestriction restriction) {
            return new RestrictionView(restriction.restrictionId(), restriction.feature().name(),
                    restriction.scope().name(), restriction.target(), restriction.targetErased(),
                    restriction.effectiveFrom().toString(), restriction.reason(), restriction.actorDigest(),
                    restriction.origin().name(), restriction.linkedSignals(), restriction.requiresVerification(),
                    restriction.reinstatedFrom() != null, restriction.reinstatedFrom());
        }
    }

    record RestrictionListView(List<RestrictionView> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OperatorError(String code, String message, String correlationId) {}

    private final OperatorCommandService commands;

    private final OperatorAuditApi audit;

    private final OperationalRestrictionApi restrictions;

    /** 공지 사건 목록(Story 3.5). */
    private final ServiceNoticeApi notices;

    /** 공지 발행의 지원 연락처(Story 3.5). 목록이 설정 여부만 알린다. */
    private final SupportContact supportContact;

    /** 문의 목록·상세·집계(Story 3.6). */
    private final SupportInquiryApi inquiries;

    /**
     * 핸들러 안의 이중 검사. 게이트({@link OperatorGate})가 먼저 판정하지만 경로 판정이 어긋나는 날(인코딩·매핑 변경)
     * 그 검사 하나에 기대지 않는다 — 여기서도 같은 권한을 보고 같은 404를 낸다.
     */
    private final OperatorAccess access;

    OperatorController(OperatorCommandService commands, OperatorAuditApi audit, OperationalRestrictionApi restrictions,
            ServiceNoticeApi notices, SupportContact supportContact, SupportInquiryApi inquiries,
            OperatorAccess access) {
        this.commands = commands;
        this.inquiries = inquiries;
        this.audit = audit;
        this.restrictions = restrictions;
        this.notices = notices;
        this.supportContact = supportContact;
        this.access = access;
    }

    // ------------------------------------------------------------------ 감사 조회

    @GetMapping(path = OperatorGate.AUDIT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> search(@RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String command,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam(required = false) @Nullable String outcome,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String limit, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.AUDIT))) {
            return notFound("audit", response, started);
        }
        OperatorAuditApi.Filter filter = filterOf(from, to, command, scope, outcome);
        Integer size = limitOf(limit);
        if (filter == null || size == null) {
            return answer("audit", badRequest(correlationId), "BAD_REQUEST", started);
        }
        var page = this.audit.search(filter, cursor, size);
        if (page.isEmpty()) {
            return answer("audit", badRequest(correlationId), "BAD_REQUEST", started);
        }
        return answer("audit", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PageView(page.get().entries().stream().map(OperatorAuditApi.Entry::view).toList(),
                        page.get().nextCursor())), "OK", started);
    }

    @GetMapping(path = OPERATIONS_PATH + "/{operationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> operation(@PathVariable String operationId, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        UUID userId = ControllerSupport.currentUserId();
        if (!this.access.permits(userId, Set.of())) {
            return notFound("operation", response, started);
        }
        List<OperatorAuditApi.Entry> rows = userId == null || !OPERATION_ID.matcher(operationId).matches()
                ? List.of() : this.audit.operation(userId, operationId);
        if (rows.isEmpty()) {
            return notFound("operation", response, started);
        }
        return answer("operation", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OperationView(operationId, rows.stream().map(OperatorAuditApi.Entry::view).toList())),
                "OK", started);
    }

    // ------------------------------------------------------------------ 활성 제한(Story 3.2)

    @GetMapping(path = RESTRICTIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> restrictions(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("restrictions", response, started);
        }
        List<RestrictionView> items;
        try {
            items = this.restrictions.active().stream().map(RestrictionView::of).toList();
        } catch (RuntimeException failure) {
            return answer("restrictions", internal(failure, correlationId), "ERROR", started);
        }
        return answer("restrictions", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RestrictionListView(items)), "OK", started);
    }

    // ------------------------------------------------------------------ 공지 사건(Story 3.5)

    @GetMapping(path = NOTICES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> notices(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("notices", response, started);
        }
        List<IncidentView> items;
        try {
            items = this.notices.incidents().stream().map(OperatorController::incidentView).toList();
        } catch (RuntimeException failure) {
            return answer("notices", internal(failure, correlationId), "ERROR", started);
        }
        return answer("notices", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new IncidentListView(this.supportContact.value() != null, items)), "OK", started);
    }

    // ------------------------------------------------------------------ 문의(Story 3.6)

    @GetMapping(path = INQUIRIES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> inquiries(@RequestParam(required = false) @Nullable String status,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("inquiries", response, started);
        }
        SupportInquiryApi.Status filter = null;
        if (status != null) {
            try {
                filter = SupportInquiryApi.Status.valueOf(status);
            } catch (IllegalArgumentException unknown) {
                return answer("inquiries", badRequest(correlationId), "BAD_REQUEST", started);
            }
        }
        List<InquirySummaryView> items;
        try {
            items = this.inquiries.recent(filter).stream().map(InquirySummaryView::of).toList();
        } catch (RuntimeException failure) {
            return answer("inquiries", internal(failure, correlationId), "ERROR", started);
        }
        return answer("inquiries", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InquiryListView(items)), "OK", started);
    }

    @GetMapping(path = INQUIRY_METRICS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> inquiryMetrics(@RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("inquiry-metrics", response, started);
        }
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)
                || java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1
                        > SupportInquiryApi.MAX_METRICS_DAYS) {
            return answer("inquiry-metrics", badRequest(correlationId), "BAD_REQUEST", started);
        }
        SupportInquiryApi.Metrics metrics;
        try {
            metrics = this.inquiries.metrics(fromDate, toDate);
        } catch (RuntimeException failure) {
            return answer("inquiry-metrics", internal(failure, correlationId), "ERROR", started);
        }
        return answer("inquiry-metrics", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InquiryMetricsView(metrics.from().toString(), metrics.to().toString(),
                        metrics.asOf().toString(), metrics.calendarVersion(), metrics.due(), metrics.metTarget(),
                        metrics.ratePercent(), metrics.calendarPending(), metrics.measured(),
                        metrics.totalBusinessMinutes(), metrics.medianBusinessMinutes(),
                        text(metrics.purgeHorizon()))), "OK", started);
    }

    @GetMapping(path = INQUIRIES_PATH + "/{reference}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> inquiry(@PathVariable String reference, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        if (!this.access.permits(ControllerSupport.currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("inquiry", response, started);
        }
        java.util.Optional<SupportInquiryApi.OperatorInquiryDetail> found;
        try {
            found = this.inquiries.detail(reference);
        } catch (RuntimeException failure) {
            return answer("inquiry", internal(failure, correlationId), "ERROR", started);
        }
        if (found.isEmpty()) {
            return notFound("inquiry", response, started);
        }
        SupportInquiryApi.OperatorInquiryDetail detail = found.get();
        return answer("inquiry", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new InquiryDetailView(InquirySummaryView.of(detail.summary()), detail.description(),
                        detail.answer(), detail.replyEmail())), "OK", started);
    }

    // ------------------------------------------------------------------ 운영 command

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> prepare(@PathVariable String type, @RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        UUID userId = ControllerSupport.currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null
                || !this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            return notFound("prepare", response, started);
        }
        OperatorCommandApi.Proposal proposal = proposalOf(type, body, this.commands);
        if (proposal == null) {
            return answer("prepare", badRequest(correlationId), "BAD_REQUEST", started);
        }
        if (!IdempotencyKey.isValid(key)) {
            return answer("prepare", refused(IdempotencyKey.REQUIRED_CODE, correlationId), IdempotencyKey.REQUIRED_CODE,
                    started);
        }
        OperatorCommandService.Preparation preparation;
        try {
            preparation = this.commands.prepare(session, userId, proposal, key, correlationId);
        } catch (RuntimeException failure) {
            return answer("prepare", internal(failure, correlationId), "ERROR", started);
        }
        return switch (preparation) {
            case OperatorCommandService.Preparation.Refused refused ->
                    OperatorCommandService.NOT_FOUND.equals(refused.code()) ? notFound("prepare", response, started)
                            : answer("prepare", refused(refused.code(), type,
                                    refused.detail() == null ? proposal.scope() : refused.detail(), correlationId),
                                    refused.code(), started);
            case OperatorCommandService.Preparation.Armed armed -> answer("prepare", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new PreparedView(armed.flow().operationId(), armed.flow().command(), armed.flow().scope(),
                            armed.flow().target(), armed.flow().reason(), armed.flow().confirmation(),
                            armed.flow().policyVersion(), armed.flow().requestedAt().toString(), armed.impact())),
                    "PREPARED", started);
        };
    }

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}/" + CONFIRM_SEGMENT,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> confirm(@PathVariable String type, @RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        UUID userId = ControllerSupport.currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            return notFound("confirm", response, started);
        }
        if (!this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            this.commands.abandonRevoked(session, userId, correlationId);
            return notFound("confirm", response, started);
        }
        Confirmation parsed = confirmationOf(body);
        if (parsed == null) {
            return answer("confirm", badRequest(correlationId), "BAD_REQUEST", started);
        }
        if (!IdempotencyKey.isValid(key)) {
            return answer("confirm", refused(IdempotencyKey.REQUIRED_CODE, correlationId), IdempotencyKey.REQUIRED_CODE,
                    started);
        }
        OperatorCommandService.Outcome outcome;
        try {
            outcome = this.commands.confirm(request, session, userId, type, parsed.operationId(),
                    parsed.confirmation(), key, correlationId);
        } catch (RuntimeException failure) {
            return answer("confirm", internal(failure, correlationId), "ERROR", started);
        }
        return switch (outcome) {
            case OperatorCommandService.Outcome.Refused refused ->
                    OperatorCommandService.NOT_FOUND.equals(refused.code()) ? notFound("confirm", response, started)
                            : answer("confirm", refused(refused.code(), type, refused.detail(), correlationId),
                                    refused.code(), started);
            case OperatorCommandService.Outcome.Completed completed -> answer("confirm", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(completed.view()), completed.ran() ? "COMPLETED" : "REPLAYED", started);
        };
    }

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}/" + AuthSecurityConfig.CANCEL_SEGMENT)
    void cancel(@PathVariable String type, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        UUID userId = ControllerSupport.currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null) {
            notFound("cancel", response, started);
            return;
        }
        if (!this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            if (session != null) {
                this.commands.abandonRevoked(session, userId, correlationId);
            }
            notFound("cancel", response, started);
            return;
        }
        if (!this.commands.supports(type)) {
            // 모르는 type은 경로가 없는 것과 같다. 무장을 건드리지 않는다.
            notFound("cancel", response, started);
            return;
        }
        String result = "CANCELLED";
        if (session != null) {
            try {
                if (!this.commands.cancel(session, userId, type, correlationId)) {
                    // 모르는 type은 경로가 없는 것과 같다. 무장을 건드리지 않았다.
                    notFound("cancel", response, started);
                    return;
                }
            } catch (RuntimeException failure) {
                // 무장은 이미 세션에서 빠졌다. 남은 허가는 5분 뒤 만료된다.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("operator cancel failed: failure={}", failure.getClass().getName());
                }
                result = "ERROR";
            }
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        log("cancel", result, HttpStatus.NO_CONTENT.value(), started);
    }

    // ------------------------------------------------------------------ 본문

    /** 확정 본문. */
    record Confirmation(String operationId, String confirmation) {}

    /**
     * 준비 본문의 제안. 본문이 JSON 객체가 아니거나 그 command가 문자열로 읽는 필드 가운데 문자열이 아닌 것이 있으면
     * {@code null}(400)이다. 필드가 없는 것은 검증이 거절한다(422). 수행자 이름이 하나라도 있으면 제안에 그 사실을 싣는다.
     * 어떤 필드를 읽는지는 그 command가 정한다({@link OperatorCommandApi#parse}).
     */
    static OperatorCommandApi.@Nullable Proposal proposalOf(String type, @Nullable String body,
            OperatorCommandService commands) {
        tools.jackson.databind.JsonNode node = objectOf(body);
        if (node == null) {
            return null;
        }
        boolean actorClaimed = false;
        for (String name : ACTOR_FIELDS) {
            if (node.has(name)) {
                actorClaimed = true;
                break;
            }
        }
        return commands.parse(type, node, actorClaimed);
    }

    static @Nullable Confirmation confirmationOf(@Nullable String body) {
        tools.jackson.databind.JsonNode node = objectOf(body);
        if (node == null) {
            return null;
        }
        tools.jackson.databind.JsonNode operation = node.get("operationId");
        tools.jackson.databind.JsonNode phrase = node.get("confirmation");
        if (operation == null || !operation.isString() || phrase == null || !phrase.isString()
                || phrase.asString().isBlank() || phrase.asString().length() > MAX_CONFIRMATION_CHARS
                || operation.asString().length() > 64) {
            return null;
        }
        return new Confirmation(operation.asString(), phrase.asString());
    }

    private static tools.jackson.databind.@Nullable JsonNode objectOf(@Nullable String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) {
            return null;
        }
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        return node != null && node.isObject() ? node : null;
    }

    /** 조회 필터. 읽을 수 없는 값이 하나라도 있으면 {@code null}(400)이다. */
    static OperatorAuditApi.@Nullable Filter filterOf(@Nullable String from, @Nullable String to,
            @Nullable String command, @Nullable String scope, @Nullable String outcome) {
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        if ((from != null && fromDate == null) || (to != null && toDate == null)
                || (fromDate != null && toDate != null && toDate.isBefore(fromDate))
                || (command != null && !COMMAND_FILTER.matcher(command).matches())
                || (scope != null && !SCOPE_FILTER.matcher(scope).matches())) {
            return null;
        }
        OperatorCommandApi.AuditOutcome parsedOutcome = null;
        if (outcome != null) {
            try {
                parsedOutcome = OperatorCommandApi.AuditOutcome.valueOf(outcome);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return new OperatorAuditApi.Filter(fromDate, toDate, command, scope, parsedOutcome);
    }

    private static @Nullable LocalDate dateOf(@Nullable String text) {
        if (text == null || !DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException impossible) {
            return null;
        }
    }

    /** 쪽 크기. 없으면 기본값, 0이나 숫자가 아니면 {@code null}(400), 상한을 넘으면 상한이다. */
    static @Nullable Integer limitOf(@Nullable String limit) {
        if (limit == null) {
            return OperatorAuditApi.DEFAULT_PAGE_SIZE;
        }
        if (!LIMIT.matcher(limit).matches()) {
            return null;
        }
        int parsed = Integer.parseInt(limit);
        return parsed < 1 ? null : Math.min(parsed, OperatorAuditApi.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------ 응답

    /**
     * 없는 경로와 같은 404. 오류 디스패치를 지나 {@code ApiErrorHandler}가 본문을 만들므로 게이트의 404·없는 경로의 404와
     * 글자까지 같다.
     */
    private static @Nullable ResponseEntity<?> notFound(String step, HttpServletResponse response, long started)
            throws IOException {
        response.sendError(HttpStatus.NOT_FOUND.value());
        log(step, "NOT_FOUND", HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    /**
     * command별 문장이 있는 거절({@link #COMMAND_REFUSALS}). 같은 code의 다른 문장이며 machine 값·상태는 code 하나다.
     *
     * @param detail 같은 code의 문장을 가르는 사실(공지 발행은 공지 상태 — 복구 공지의 {@code TARGET_INVALID}는 덮는 제한이
     *     아직 효력 중이라는 문장이다). 없으면 {@code null}
     */
    private static ResponseEntity<?> refused(String code, String command, @Nullable String detail,
            String correlationId) {
        String key = command + "|" + code;
        String message = detail == null ? null : COMMAND_REFUSALS.get(key + "|" + detail.strip());
        if (message == null) {
            message = COMMAND_REFUSALS.get(key);
        }
        Refusal refusal = REFUSALS.get(code);
        if (message != null && refusal != null) {
            return error(refusal.status(), code, message, correlationId);
        }
        return refused(code, correlationId);
    }

    private static ResponseEntity<?> refused(String code, String correlationId) {
        Refusal refusal = REFUSALS.get(code);
        if (refusal == null) {
            // 이 표면이 모르는 거절은 호출자 잘못이 아니라 깨진 계약이다.
            return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, correlationId);
        }
        return error(refusal.status(), code, refusal.message(), correlationId);
    }

    private static ResponseEntity<?> badRequest(String correlationId) {
        return error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST, ApiErrorHandler.MESSAGE_BAD_REQUEST,
                correlationId);
    }

    private static ResponseEntity<?> internal(RuntimeException failure, String correlationId) {
        // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 값이 실릴 수 있다.
        if (LOG.isDebugEnabled()) {
            LOG.debug("operator request failed: failure={}", failure.getClass().getName());
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                ApiErrorHandler.MESSAGE_INTERNAL_ERROR, correlationId);
    }

    private static ResponseEntity<OperatorError> error(HttpStatus status, String code, String message,
            String correlationId) {
        return ControllerSupport.noStoreJson(status, new OperatorError(code, message, correlationId));
    }

    private static ResponseEntity<?> answer(String step, ResponseEntity<?> response, String result, long started) {
        String logged = response.getStatusCode().value() == 500 ? "ERROR" : result;
        log(step, logged, response.getStatusCode().value(), started);
        return response;
    }

    private static void log(String step, String result, int status, long started) {
        LOG.info("operator request: step={} result={} status={} latencyMs={}", step, result, status,
                (System.nanoTime() - started) / 1_000_000);
    }
}
