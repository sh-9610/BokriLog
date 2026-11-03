package com.bokrilog;

import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.operations.SupportInquiryApi;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 사용자의 문의(Story 3.6 AC-1·AC-2·AC-4).
 *
 * <ul>
 *   <li>{@code POST /api/support/inquiries}: {@code {category, title, description, contact, replyEmail?}}과
 *       {@code Idempotency-Key}. 201 {@code {reference, receivedAt, targetAt, targetState, status}} — 같은 키·같은 입력의
 *       재전송은 같은 묶음의 200이다. 거절은 공통 오류 모양이다: 422 {@code IDEMPOTENCY_KEY_REQUIRED}·{@code INQUIRY_INVALID}
 *       ({@code field})·{@code INQUIRY_SENSITIVE_CONTENT}({@code field}·{@code rule} — 맞은 조각은 싣지 않는다), 429
 *       {@code INQUIRY_RATE_LIMITED}, 409 {@code IDEMPOTENCY_CONFLICT}, 400 본문 모양, 실패 500.
 *   <li>{@code GET /api/support/inquiries}: 내 문의 최근 순({@code reference·category·title·description·receivedAt·
 *       targetAt·targetState·status·answer·answeredAt})과 공개 지원 이메일({@code supportContact}, 설정이 있을 때만).
 * </ul>
 *
 * <p>인증·CSRF·{@code Origin}은 {@code AuthSecurityConfig.isSupportPath} 하나가 건다. 계정 접근은
 * {@code AccountAccessApi.decide(SENSITIVE)}로 본다 — 삭제가 시작된 계정은 없는 경로와 같은 404다. 모든 응답이
 * {@code no-store}다.
 *
 * <p><b>로그에 제목·본문·회신 이메일·답변이 없다.</b> 한 요청에 한 줄 — 단계·결과 code·거절 규칙 code·상태·지연뿐이다. 사용자·
 * reference도 남기지 않는다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SupportInquiryController {

    static final String INQUIRIES_PATH = AuthSecurityConfig.SUPPORT_PATH + "/inquiries";

    /** 필드가 비었거나 모양·길이가 맞지 않다. */
    static final String INVALID = "INQUIRY_INVALID";

    /** 제목·본문에 민감값 모양이 있다. */
    static final String SENSITIVE = "INQUIRY_SENSITIVE_CONTENT";

    /** 최근 24시간 접수 상한을 넘었다. */
    static final String RATE_LIMITED = "INQUIRY_RATE_LIMITED";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SupportInquiryController.class);

    /**
     * 요청 본문의 상한(문자). 모든 글자가 {@code \\uXXXX}로 이스케이프돼도(6배) 제목 80·본문 2000·이메일 254자와 키·따옴표를
     * 담는다. 필드 길이는 이 상한이 아니라 파싱한 값으로 판정한다(검증이 422로 답한다).
     */
    private static final int MAX_BODY_CHARS = 6 * (SupportInquiryApi.MAX_TITLE_CHARS
            + SupportInquiryApi.MAX_DESCRIPTION_CHARS + SupportInquiryApi.MAX_EMAIL_CHARS) + 2048;

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** 필드 → 안전한 문장. 값을 담지 않는다. */
    private static final Map<SupportInquiryApi.Field, String> FIELD_MESSAGES = Map.of(
            SupportInquiryApi.Field.CATEGORY, "문의 종류를 골라 주세요.",
            SupportInquiryApi.Field.TITLE, "제목은 80자 이내의 한 줄로 입력해 주세요.",
            SupportInquiryApi.Field.DESCRIPTION, "내용은 2000자 이내로 입력해 주세요.",
            SupportInquiryApi.Field.CONTACT, "답변 받을 방법을 골라 주세요.",
            SupportInquiryApi.Field.REPLY_EMAIL, "이메일 답변을 고른 경우에만 254자 이내의 이메일 주소 하나를 입력해 주세요.");

    /** 규칙 → 안전한 문장. 맞은 조각을 담지 않는다. */
    private static final Map<SupportInquiryApi.SensitiveRule, String> RULE_MESSAGES = Map.of(
            SupportInquiryApi.SensitiveRule.SECRET,
            "API 키·비밀키·토큰처럼 보이는 값이 있어 받지 않았습니다. 그 값을 빼고 다시 보내 주세요.",
            SupportInquiryApi.SensitiveRule.WALLET,
            "지갑 주소처럼 보이는 값이 있어 받지 않았습니다. 그 값을 빼고 다시 보내 주세요.",
            SupportInquiryApi.SensitiveRule.FINANCIAL,
            "금액·계좌번호처럼 보이는 숫자가 있어 받지 않았습니다. 금액과 6자리 이상 숫자를 빼고 다시 보내 주세요.",
            SupportInquiryApi.SensitiveRule.EMAIL,
            "제목·내용에 이메일 주소가 있어 받지 않았습니다. 이메일 답변이 필요하면 답변 방법에서 이메일을 골라 주세요.");

    /**
     * 공통 오류 모양. 문의 거절은 어느 필드인지({@code field})와 어느 민감값 규칙인지({@code rule})를 code로 더 싣는다 — 둘 다
     * 닫힌 code이고 입력 원문이 아니다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record InquiryError(String code, String message, String correlationId, @Nullable String field,
            @Nullable String rule) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ReceiptView(String reference, String receivedAt, @Nullable String targetAt, String targetState,
            String status) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ItemView(String reference, String category, String title, String description, String receivedAt,
            @Nullable String targetAt, String targetState, String status, @Nullable String answer,
            @Nullable String answeredAt) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ListView(@Nullable String supportContact, List<ItemView> items) {}

    private final SupportInquiryApi inquiries;

    private final AccountAccessApi accounts;

    private final SupportContact supportContact;

    SupportInquiryController(SupportInquiryApi inquiries, AccountAccessApi accounts, SupportContact supportContact) {
        this.inquiries = inquiries;
        this.accounts = accounts;
        this.supportContact = supportContact;
    }

    @GetMapping(path = INQUIRIES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> list(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = ControllerSupport.allowedUser(this.accounts);
        if (userId == null) {
            return notFound("list", response, started);
        }
        List<ItemView> items;
        try {
            items = this.inquiries.mine(userId).stream().map(SupportInquiryController::view).toList();
        } catch (RuntimeException failure) {
            return internal("list", failure, request, started);
        }
        log("list", "OK", null, HttpStatus.OK.value(), started);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ListView(this.supportContact.value(), items));
    }

    @PostMapping(path = INQUIRIES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> submit(@RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = ControllerSupport.correlationIdOf(request);
        UUID userId = ControllerSupport.allowedUser(this.accounts);
        if (userId == null) {
            return notFound("submit", response, started);
        }
        SupportInquiryApi.Draft draft = draftOf(body);
        if (draft == null) {
            log("submit", ApiErrorHandler.CODE_BAD_REQUEST, null, HttpStatus.BAD_REQUEST.value(), started);
            return error(HttpStatus.BAD_REQUEST, new InquiryError(ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, correlationId, null, null));
        }
        if (!IdempotencyKey.isValid(key)) {
            log("submit", IdempotencyKey.REQUIRED_CODE, null, HttpStatus.UNPROCESSABLE_CONTENT.value(), started);
            return error(HttpStatus.UNPROCESSABLE_CONTENT, new InquiryError(IdempotencyKey.REQUIRED_CODE,
                    ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED, correlationId, null, null));
        }
        SupportInquiryApi.Submission submission;
        try {
            submission = this.inquiries.submit(userId, key, draft);
        } catch (RuntimeException failure) {
            return internal("submit", failure, request, started);
        }
        return switch (submission) {
            case SupportInquiryApi.Submission.Received received -> {
                HttpStatus status = received.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
                log("submit", received.replayed() ? "REPLAYED" : "RECEIVED", null, status.value(), started);
                SupportInquiryApi.Receipt receipt = received.receipt();
                yield ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                        .body(new ReceiptView(receipt.reference(), receipt.receivedAt().toString(),
                                text(receipt.targetAt()), receipt.targetState().name(), receipt.status().name()));
            }
            case SupportInquiryApi.Submission.Invalid invalid -> {
                log("submit", INVALID, null, HttpStatus.UNPROCESSABLE_CONTENT.value(), started);
                yield error(HttpStatus.UNPROCESSABLE_CONTENT, new InquiryError(INVALID,
                        FIELD_MESSAGES.get(invalid.field()), correlationId, fieldCode(invalid.field()), null));
            }
            case SupportInquiryApi.Submission.Sensitive sensitive -> {
                // 규칙 code만 남긴다. 맞은 조각·필드 값은 응답에도 로그에도 없다.
                log("submit", SENSITIVE, sensitive.rule().name(), HttpStatus.UNPROCESSABLE_CONTENT.value(), started);
                yield error(HttpStatus.UNPROCESSABLE_CONTENT, new InquiryError(SENSITIVE,
                        RULE_MESSAGES.get(sensitive.rule()), correlationId, fieldCode(sensitive.field()),
                        sensitive.rule().name()));
            }
            case SupportInquiryApi.Submission.RateLimited limited -> {
                log("submit", RATE_LIMITED, null, HttpStatus.TOO_MANY_REQUESTS.value(), started);
                yield error(HttpStatus.TOO_MANY_REQUESTS, new InquiryError(RATE_LIMITED,
                        "최근 24시간 동안 문의를 " + SupportInquiryApi.DAILY_LIMIT + "건 보냈습니다. 시간이 지난 뒤 다시 "
                                + "보내 주세요. 보낸 문의의 상태는 아래 목록에서 볼 수 있습니다.",
                        correlationId, null, null));
            }
            case SupportInquiryApi.Submission.Conflict conflict -> {
                log("submit", IdempotencyKey.CONFLICT_CODE, null, HttpStatus.CONFLICT.value(), started);
                yield error(HttpStatus.CONFLICT, new InquiryError(IdempotencyKey.CONFLICT_CODE,
                        "같은 요청 키가 다른 문의에 이미 쓰였습니다. 화면을 새로 열어 다시 보내 주세요.", correlationId,
                        null, null));
            }
        };
    }

    /** 필드 code. 화면이 그 필드에 안내를 붙인다. */
    static String fieldCode(SupportInquiryApi.Field field) {
        return switch (field) {
            case CATEGORY -> "category";
            case TITLE -> "title";
            case DESCRIPTION -> "description";
            case CONTACT -> "contact";
            case REPLY_EMAIL -> "replyEmail";
        };
    }

    /**
     * 본문의 입력. JSON 객체가 아니거나 다섯 필드 중 문자열이 아닌 것이 있으면 {@code null}(400)이다. 필드가 없는 것은 검증이
     * 거절한다(422).
     */
    static SupportInquiryApi.@Nullable Draft draftOf(@Nullable String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) {
            return null;
        }
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return new SupportInquiryApi.Draft(stringOrNull(node, "category"), stringOrNull(node, "title"),
                    stringOrNull(node, "description"), stringOrNull(node, "contact"),
                    stringOrNull(node, "replyEmail"));
        } catch (IllegalArgumentException notText) {
            return null;
        }
    }

    private static @Nullable String stringOrNull(tools.jackson.databind.JsonNode node, String name) {
        tools.jackson.databind.JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(name);
        }
        return value.asString();
    }

    private static ItemView view(SupportInquiryApi.UserInquiry inquiry) {
        return new ItemView(inquiry.reference(), inquiry.category().name(), inquiry.title(), inquiry.description(),
                inquiry.receivedAt().toString(), text(inquiry.targetAt()), inquiry.targetState().name(),
                inquiry.status().name(), inquiry.answer(), text(inquiry.answeredAt()));
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static @Nullable ResponseEntity<?> notFound(String step, HttpServletResponse response, long started)
            throws IOException {
        // 없는 경로와 같은 404. 오류 디스패치를 지나 공통 본문이 된다. 사용자별 항목을 만들지 않는다.
        response.sendError(HttpStatus.NOT_FOUND.value());
        log(step, "NOT_FOUND", null, HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    private static ResponseEntity<?> internal(String step, RuntimeException failure, HttpServletRequest request,
            long started) {
        // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 입력 값이 실릴 수 있다.
        if (LOG.isDebugEnabled()) {
            LOG.debug("support inquiry request failed: failure={}", failure.getClass().getName());
        }
        log(step, "ERROR", null, HttpStatus.INTERNAL_SERVER_ERROR.value(), started);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, new InquiryError(ApiErrorHandler.CODE_INTERNAL_ERROR,
                ApiErrorHandler.MESSAGE_INTERNAL_ERROR, ControllerSupport.correlationIdOf(request), null, null));
    }

    private static ResponseEntity<InquiryError> error(HttpStatus status, InquiryError body) {
        return ControllerSupport.noStoreJson(status, body);
    }

    private static void log(String step, String result, @Nullable String rule, int status, long started) {
        LOG.info("support inquiry request: step={} result={} rule={} status={} latencyMs={}", step, result,
                rule == null ? "-" : rule, status, (System.nanoTime() - started) / 1_000_000);
    }
}
