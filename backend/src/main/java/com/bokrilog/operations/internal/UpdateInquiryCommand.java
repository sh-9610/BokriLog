package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.CommandRefusal;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Refusal;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.SupportInquiryApi;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 문의 처리(Story 3.6 AC-5) — {@value OperatorCommandApi#UPDATE_INQUIRY}. 옮길 상태는 scope, reference는 target, 운영 사유는
 * 사유 자리이고 답변은 따로다. 원장의 target은 reference의 keyed digest다 — reference는 사용자 귀속 문의를 가리키는
 * 식별자다. 답변 원문은 무장(서버 세션)과 문의 행에만 있다.
 *
 * <p>확정의 순서: (엔진: 잠금 → 최종 권한) → 문의 행 잠금(없으면 {@code TARGET_INVALID}) → 문의 주인 계정의 상태(삭제가
 * 시작됐으면 {@code TARGET_INVALID}) → 지금 상태에서의 전이 재검사 → 조건부 UPDATE 한 문장(첫 {@code ANSWERED}가 첫 답변
 * 시각·목표 달성 여부를 쓴다) → (엔진: {@code COMPLETED} — 상태·reference digest·전이 시각뿐이다).
 */
final class UpdateInquiryCommand implements OperatorCommand<UpdateInquiryCommand.Inquiry,
        UpdateInquiryCommand.InquiryResult> {

    /**
     * 문의 처리의 입력 원문. 옮길 상태·reference·사유는 {@link Proposal}의 scope·target·reason 자리다.
     *
     * @param answer {@code ANSWERED} 전이의 답변. 그 밖의 전이에는 없어야 한다
     */
    record InquiryInput(@Nullable String answer) {}

    /**
     * 검증을 지난 문의 처리. 세션 무장에 실린다.
     *
     * @param from 준비 시각에 본 상태. 문의가 없으면 {@code null}
     * @param targetAt 준비 시각에 본 답변 목표 시각. 미정이거나 문의가 없으면 {@code null}
     * @param answer {@code ANSWERED} 전이의 답변. 그 밖은 {@code null}
     * @param refusal 준비 시각에 본 상태 거절({@code TARGET_INVALID}·{@code INQUIRY_TRANSITION_INVALID}). 준비 뒤 판정이
     *     멱등 키·재인증 판정 뒤에 적용한다. 무장되는 값은 언제나 {@code null}이다
     */
    record Inquiry(String reference, SupportInquiryApi.@Nullable Status from, SupportInquiryApi.Status to,
            @Nullable Instant targetAt, @Nullable String answer, @Nullable String refusal) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 문의 처리의 결과. 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. reference·답변 원문을 담지 않는다 — 원장에는
     * reference의 digest만 있다.
     *
     * @param transitionedAt 상태를 옮긴 DB 시각
     */
    record InquiryResult(String operationId, SupportInquiryApi.Status status, Instant transitionedAt) {}

    private final SupportInquiryRepository inquiries;

    private final ActorDigest digest;

    /** 문의 첫 답변 목표의 계측(Story 3.7). */
    private final SupportMetrics supportMetrics;

    private final OperatorCommandEnvironment environment;

    UpdateInquiryCommand(SupportInquiryRepository inquiries, ActorDigest digest, SupportMetrics supportMetrics,
            OperatorCommandEnvironment environment) {
        this.inquiries = inquiries;
        this.digest = digest;
        this.supportMetrics = supportMetrics;
        this.environment = environment;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.UPDATE_INQUIRY);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        return EnumSet.of(OperatorPermission.COMMAND);
    }

    @Override
    public Class<Inquiry> payloadType() {
        return Inquiry.class;
    }

    @Override
    public Class<InquiryResult> resultType() {
        return InquiryResult.class;
    }

    /**
     * 문의 처리는 자기 이름의 필드를 받는다 — reference({@code reference})가 target, 옮길 상태({@code status})가 scope, 운영
     * 사유({@code reason})가 사유, 답변({@code answer})은 따로다. 문자열이 아니면 400이고 비어 있는 것은 검증이 거절한다(422).
     */
    @Override
    public Proposal parse(String command, JsonNode body, Proposal common) {
        return new Proposal(command, CommandJson.stringOrNull(body, "status"),
                CommandJson.stringOrNull(body, "reference"), common.reason(), common.actorClaimed(),
                new InquiryInput(CommandJson.stringOrNull(body, "answer")));
    }

    /**
     * envelope 검증. 형식 — 옮길 상태·reference·사유·답변(필수 여부·길이·민감값) — 을 보고, 그다음 문의 표에서 지금 상태를
     * 읽어 전이를 판정한다. 상태 거절은 {@link Inquiry#refusal}에 싣고 준비 뒤 판정이 멱등 키·재인증 판정 <b>뒤</b>에 적용한다.
     *
     * <p>요청 digest는 답변의 SHA-256을 담는다(답변 원문은 원장에 없다). 같은 키로 다른 답변을 보내면 충돌이다.
     */
    @Override
    public Validation validate(Proposal proposal) {
        SupportInquiryApi.Status to = statusOf(proposal.scope());
        if (to == null) {
            return new Validation.Invalid(OperatorCommandApi.UNSUPPORTED_SCOPE);
        }
        String reference = proposal.target() == null ? "" : proposal.target().strip();
        if (!InquiryRules.REFERENCE.matcher(reference).matches()) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(reason)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        InquiryInput input = CommandJson.inputOf(proposal, InquiryInput.class);
        String given = input == null ? null : input.answer();
        String answer = null;
        if (to == SupportInquiryApi.Status.ANSWERED) {
            if (given == null || given.isBlank()) {
                return new Validation.Invalid(OperatorCommandApi.INQUIRY_ANSWER_REQUIRED);
            }
            answer = InquiryRules.answer(given);
            if (answer == null) {
                return new Validation.Invalid(OperatorCommandApi.INQUIRY_ANSWER_INVALID);
            }
            if (InquiryRules.sensitive(answer).isPresent()) {
                return new Validation.Invalid(OperatorCommandApi.INQUIRY_SENSITIVE_CONTENT);
            }
        } else if (given != null && !given.isBlank()) {
            // 답변은 `ANSWERED` 전이에만 싣는다. 다른 전이에 실린 답변을 조용히 버리지 않는다.
            return new Validation.Invalid(OperatorCommandApi.INQUIRY_ANSWER_INVALID);
        }
        var current = this.inquiries.current(reference).orElse(null);
        String refusal = null;
        if (current == null) {
            refusal = OperatorCommandApi.TARGET_INVALID;
        } else if (!current.status().canMoveTo(to)) {
            refusal = OperatorCommandApi.INQUIRY_TRANSITION_INVALID;
        }
        Inquiry inquiry = new Inquiry(reference, current == null ? null : current.status(), to,
                current == null ? null : current.targetAt(), answer, refusal);
        String ledgerScope = OperatorCommandApi.INQUIRY_SCOPE_PREFIX + to.name();
        String ledgerTarget = this.digest.ofInquiry(reference);
        String requestDigest = OperatorEnvelopeService.sha256(OperatorCommandApi.UPDATE_INQUIRY + "\n" + ledgerScope
                + "\n" + ledgerTarget + "\n" + reason + "\n"
                + (answer == null ? "-" : OperatorEnvelopeService.sha256("inquiry-answer\n" + answer)));
        return new Validation.Valid(new Accepted(OperatorCommandApi.UPDATE_INQUIRY, ledgerScope, reference,
                ledgerTarget, reason, OperatorEnvelopeService.inquiryPhrase(to, reference),
                OperatorCommandApi.POLICY_VERSION, permissions(), requestDigest, inquiry));
    }

    /**
     * 거절 행: scope는 옮길 수 있는 상태일 때만 {@code INQUIRY:<상태>}, target은 reference 모양일 때만 그 keyed digest로
     * 남긴다 — reference 원문과 답변은 거절 행에도 들어가지 않는다.
     */
    @Override
    public RefusedLedger refusedLedger(Proposal proposal, RefusedLedger common) {
        SupportInquiryApi.Status status = statusOf(proposal.scope());
        String target = proposal.target() == null ? "" : proposal.target().strip();
        return new RefusedLedger(
                status == null ? OperatorEnvelopeService.UNREADABLE
                        : OperatorCommandApi.INQUIRY_SCOPE_PREFIX + status.name(),
                InquiryRules.REFERENCE.matcher(target).matches() ? this.digest.ofInquiry(target)
                        : OperatorEnvelopeService.UNREADABLE,
                common.keepReason());
    }

    /**
     * 준비 뒤 판정 — 없는 문의는 {@code TARGET_INVALID}, 지금 상태에서 옮길 수 없는 전이(역행·같은 상태·답변 건너뛰기)는
     * {@code INQUIRY_TRANSITION_INVALID}다. 미리보기는 답변 원문을 되돌려 주지 않는다 — 운영자가 방금 쓴 값이고 화면이 들고 있다.
     */
    @Override
    public Inspection inspect(Accepted accepted, Inquiry inquiry) {
        if (inquiry.refusal() != null) {
            return new Inspection.Refused(new Refusal(inquiry.refusal(), null));
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("reference", inquiry.reference());
        if (inquiry.from() != null) {
            preview.put("currentStatus", inquiry.from().name());
        }
        preview.put("nextStatus", inquiry.to().name());
        if (inquiry.targetAt() != null) {
            preview.put("targetAt", inquiry.targetAt().toString());
        }
        preview.put("answerProvided", inquiry.answer() != null);
        return new Inspection.Ready(preview);
    }

    @Override
    public Done<InquiryResult> complete(OperatorEnvelopeService engine, Envelope envelope, Inquiry inquiry) {
        // 확정 순간의 문의. 준비 뒤 보존 삭제·계정 삭제로 사라졌거나 다른 처리가 상태를 옮겼으면 쓰지 않는다.
        var locked = this.inquiries.lockForTransition(inquiry.reference()).orElse(null);
        if (locked == null) {
            throw new CommandRefusal(OperatorCommandApi.TARGET_INVALID);
        }
        // 주인 계정의 삭제가 시작됐으면 그 문의는 곧 지워질 대상이다. 답변을 쓰지 않는다(없는 문의와 같은 거절).
        if (!this.environment.accountUsable(locked.ownerUserId())) {
            throw new CommandRefusal(OperatorCommandApi.TARGET_INVALID);
        }
        if (!locked.status().canMoveTo(inquiry.to())) {
            throw new CommandRefusal(OperatorCommandApi.INQUIRY_TRANSITION_INVALID);
        }
        Instant targetAt = locked.targetAt();
        if (inquiry.to() == SupportInquiryApi.Status.ANSWERED && targetAt == null) {
            // 미정 목표(달력에 그 연도가 없었다)를 답변 판정 전에 지금 달력으로 먼저 채운다(Story 3.7). 채우지 않으면 달력이 이미
            // 그 연도를 담게 된 뒤의 첫 답변이 목표 달성 여부 없이(`pending`) 기록·계측된다. 채울 수 없으면 미정 그대로다.
            BusinessDayCalendar calendar = this.inquiries.calendar(SupportInquiryApi.CALENDAR_VERSION);
            Instant filled = calendar.targetFor(locked.receivedAt()).orElse(null);
            if (filled != null && this.inquiries.fillTargetOf(inquiry.reference(), filled, calendar.version()) == 1) {
                targetAt = filled;
            }
        }
        Instant transitionedAt = this.inquiries.transition(inquiry.reference(), locked.status(), inquiry.to(),
                inquiry.answer(), envelope.operationId()).orElse(null);
        if (transitionedAt == null) {
            // 행 잠금 아래 같은 술어라 구조상 오지 않는 가지다. 그래도 영향 행 수가 곧 판정이다 — 0행을 성공으로 접지 않는다.
            throw new CommandRefusal(OperatorCommandApi.INQUIRY_TRANSITION_INVALID);
        }
        if (inquiry.to() == SupportInquiryApi.Status.ANSWERED) {
            // 상태는 단조 전진이라 ANSWERED 전이는 문의마다 한 번 — 곧 첫 답변이다. 이 트랜잭션이 커밋되면 센다.
            this.supportMetrics.firstAnswered(transitionedAt, targetAt);
        }
        // 상태·reference digest·전이 시각뿐이다. 제목·본문·이메일·답변 원문은 넣지 않는다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", inquiry.to().name());
        details.put("referenceDigest", envelope.target());
        details.put("transitionedAt", transitionedAt.toString());
        return new Done<>(new InquiryResult(envelope.operationId(), inquiry.to(), transitionedAt), details);
    }

    @Override
    public String grantRecord(InquiryResult result) {
        return result.operationId() + "|" + result.status() + "|" + result.transitionedAt();
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            InquiryResult result, String correlationId) {
        // 상태만 남긴다. reference·답변·사유·수행자는 남기지 않는다.
        return new Finished(view(requestedCommand, result), "status=" + result.status());
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        String status = CommandJson.text(details, "status");
        Instant transitionedAt = CommandJson.instant(details, "transitionedAt");
        if (status == null || transitionedAt == null) {
            throw new IllegalStateException("문의 처리의 완료 행을 읽을 수 없다.");
        }
        return view(requestedCommand, new InquiryResult(operationId, SupportInquiryApi.Status.valueOf(status),
                transitionedAt));
    }

    private static Map<String, Object> view(String command, InquiryResult result) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(result.operationId(), command);
        view.put("status", result.status().name());
        view.put("transitionedAt", result.transitionedAt().toString());
        return view;
    }

    /** 운영자가 옮길 수 있는 상태. 접수({@code RECEIVED})는 접수만 만든다. */
    private static SupportInquiryApi.@Nullable Status statusOf(@Nullable String text) {
        String status = text == null ? "" : text.strip();
        for (SupportInquiryApi.Status known : SupportInquiryApi.Status.values()) {
            if (known != SupportInquiryApi.Status.RECEIVED && known.name().equals(status)) {
                return known;
            }
        }
        return null;
    }
}
