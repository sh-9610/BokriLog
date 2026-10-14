package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Inspection;
import com.bokrilog.operations.OperatorCommandApi.LiftResult;
import com.bokrilog.operations.OperatorCommandApi.LiftTarget;
import com.bokrilog.operations.OperatorCommandApi.Restriction;
import com.bokrilog.operations.OperatorCommandApi.TargetCheck;
import com.bokrilog.operations.OperatorCommandEnvironment;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.ServiceNoticeApi.NoticeStatus;
import com.bokrilog.operations.SupportInquiryApi;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 command 응답의 JSON 계약(운영 command 공통화 리뷰 patch). 전략이 만드는 준비 미리보기({@code impact})와 확정 응답 본문은
 * 리팩터 전의 view record(필드 순서, {@code @JsonInclude(NON_NULL)}이면 빈 값 생략, 아니면 {@code null} 포함)와 글자까지
 * 같아야 한다 — 맵의 키 오타·순서 바뀜·null 정책 바뀜을 여기서 잡는다. 기대 문자열은 기준 {@code b318c21}의
 * {@code OperatorController} record 정의를 그대로 옮긴 것이다. 데이터베이스 없이 전략만 본다.
 */
class OperatorCommandContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final Instant AT = Instant.parse("2026-10-13T01:02:03Z");

    private static final Envelope ENVELOPE = new Envelope("op_contract", ID, "x", "SCOPE", "T", "사유", "h", "P", AT,
            "k", "r");

    private final OperatorEnvelopeService engine = mock(OperatorEnvelopeService.class);

    private final OperatorCommandEnvironment environment = mock(OperatorCommandEnvironment.class);

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private static Accepted accepted(String command, String target, Serializable payload) {
        return new Accepted(command, "S", target, target, "사유", "문구", OperatorCommandApi.POLICY_VERSION,
                Set.of(OperatorPermission.COMMAND), "d", payload);
    }

    private static Map<String, Object> preview(Inspection inspection) {
        assertThat(inspection).isInstanceOf(Inspection.Ready.class);
        Map<String, Object> preview = ((Inspection.Ready) inspection).preview();
        assertThat(preview).isNotNull();
        return preview;
    }

    // ------------------------------------------------------------------ 감사 내보내기

    @Test
    void 감사_내보내기는_미리보기가_없고_확정_본문은_CompletedView다() {
        OperatorAuditRepository repository = mock(OperatorAuditRepository.class);
        when(repository.within(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new OperatorAuditApi.Entry(7, "op_contract", "OPERATOR", "dg", "audit-export", "AUDIT_LEDGER",
                        "2026-10-12~2026-10-14", "사유", "REQUESTED", null, "P", AT, null, AT, "c"),
                new OperatorAuditApi.Entry(8, "op_contract", "OPERATOR", "dg", "audit-export", "AUDIT_LEDGER",
                        "2026-10-12~2026-10-14", "사유", "DENIED", "CANCELLED", "P", AT, AT, AT, "c")));
        AuditExportCommand command = new AuditExportCommand(repository);
        var export = new AuditExportCommand.Export("2026-10-12~2026-10-14");
        assertThat(((Inspection.Ready) command.inspect(accepted("audit-export", export.target(), export), export))
                .preview()).isNull();
        var finished = command.afterCommit(engine, "audit-export", ENVELOPE,
                new AuditExportCommand.ExportResult("op_contract", "2026-10-12~2026-10-14", 9, 2, false), "c");
        assertThat(json(finished.view())).isEqualTo("{\"operationId\":\"op_contract\",\"outcome\":\"COMPLETED\","
                + "\"command\":\"audit-export\",\"target\":\"2026-10-12~2026-10-14\",\"upperSequence\":9,"
                + "\"rowCount\":2,\"truncated\":false,\"rows\":["
                + "{\"sequence\":7,\"operationId\":\"op_contract\",\"actorKind\":\"OPERATOR\",\"actorDigest\":\"dg\","
                + "\"command\":\"audit-export\",\"scope\":\"AUDIT_LEDGER\",\"target\":\"2026-10-12~2026-10-14\","
                + "\"reason\":\"사유\",\"outcome\":\"REQUESTED\",\"policyVersion\":\"P\","
                + "\"requestedAt\":\"2026-10-13T01:02:03Z\",\"recordedAt\":\"2026-10-13T01:02:03Z\","
                + "\"correlationId\":\"c\"},"
                + "{\"sequence\":8,\"operationId\":\"op_contract\",\"actorKind\":\"OPERATOR\",\"actorDigest\":\"dg\","
                + "\"command\":\"audit-export\",\"scope\":\"AUDIT_LEDGER\",\"target\":\"2026-10-12~2026-10-14\","
                + "\"reason\":\"사유\",\"outcome\":\"DENIED\",\"code\":\"CANCELLED\",\"policyVersion\":\"P\","
                + "\"requestedAt\":\"2026-10-13T01:02:03Z\",\"decidedAt\":\"2026-10-13T01:02:03Z\","
                + "\"recordedAt\":\"2026-10-13T01:02:03Z\",\"correlationId\":\"c\"}]}");
    }

    // ------------------------------------------------------------------ 제한 생성 넷

    @Test
    void 제한_생성의_미리보기는_ImpactView이고_확정_본문은_command_넷_모두_RestrictionCompletedView다() {
        OperationalRestrictionRepository restrictions = mock(OperationalRestrictionRepository.class);
        RestrictCommand command = new RestrictCommand(restrictions, mock(ActorDigest.class), this.environment);
        Restriction restriction = new Restriction(RestrictionFeature.SYNC, RestrictionScope.GLOBAL, "ALL");
        when(this.environment.restrictionTarget(restriction)).thenReturn(new TargetCheck.Valid(null));
        when(this.environment.restrictionImpact(any(), any())).thenReturn(new OperatorCommandEnvironment
                .RestrictionImpact(List.of("FIRST_SNAPSHOT", "PLAN_END"), 3, 2, List.of("ADVERTISING")));
        assertThat(json(preview(command.inspect(accepted("restrict-sync", "ALL", restriction), restriction))))
                .isEqualTo("{\"blockedStages\":[\"FIRST_SNAPSHOT\",\"PLAN_END\"],\"affectedPlans\":3,"
                        + "\"affectedConnections\":2,\"remainingFeatures\":[\"ADVERTISING\"]}");
        for (String name : List.of(OperatorCommandApi.RESTRICT_NEW_CONNECTION, OperatorCommandApi.RESTRICT_SYNC,
                OperatorCommandApi.RESTRICT_CALCULATION_DISPLAY, OperatorCommandApi.RESTRICT_ADVERTISING)) {
            var finished = command.afterCommit(engine, name, ENVELOPE, new RestrictCommand.RestrictionResult(
                    "op_contract", ID, RestrictionFeature.SYNC, RestrictionScope.GLOBAL, AT), "c");
            assertThat(json(finished.view())).as(name).isEqualTo("{\"operationId\":\"op_contract\","
                    + "\"outcome\":\"COMPLETED\",\"command\":\"" + name + "\",\"feature\":\"SYNC\",\"scope\":\"GLOBAL\","
                    + "\"restrictionId\":\"" + ID + "\",\"effectiveFrom\":\"2026-10-13T01:02:03Z\"}");
        }
    }

    // ------------------------------------------------------------------ 해제

    @Test
    void 해제의_미리보기는_LiftImpactView이고_null_시각을_싣고_확정_본문은_LiftCompletedView다() {
        OperationalRestrictionApi evaluator = mock(OperationalRestrictionApi.class);
        LiftRestrictionCommand command = new LiftRestrictionCommand(mock(OperationalRestrictionRepository.class),
                evaluator, this.environment);
        LiftTarget target = new LiftTarget(RestrictionFeature.SYNC, RestrictionScope.GLOBAL, "ALL", false,
                RestrictionOrigin.OPERATOR, true, 0, null);
        var lift = new LiftRestrictionCommand.Lift(ID, target, OperatorCommandApi.RECOVERY_CONDITIONS, "검증", "증거",
                "1.0");
        when(this.environment.liftImpact(ID, target)).thenReturn(new OperatorCommandEnvironment.LiftImpact(
                List.of("PLAN_END"), List.of(new OperatorCommandEnvironment.Remaining("CALCULATION_DISPLAY", "GLOBAL",
                "SIGNAL")), 0, null));
        assertThat(json(preview(command.inspect(accepted("lift-restriction", ID.toString(), lift), lift))))
                .isEqualTo("{\"reopenedStages\":[\"PLAN_END\"],\"remainingRestrictions\":[{\"feature\":"
                        + "\"CALCULATION_DISPLAY\",\"scope\":\"GLOBAL\",\"origin\":\"SIGNAL\"}],\"linkedSignals\":0,"
                        + "\"latestSignalAt\":null}");

        when(this.environment.subjectOf(target)).thenReturn(RestrictionSubject.none());
        when(evaluator.matching(any(), any(), any())).thenReturn(List.of());
        LiftResult result = new LiftResult("op_contract", ID, RestrictionFeature.SYNC, RestrictionScope.GLOBAL, AT,
                List.of("PLAN_END"), List.of());
        var finished = command.afterCommit(engine, "lift-restriction", ENVELOPE,
                new LiftRestrictionCommand.LiftCompletion(result, target, List.of(), Map.of()), "c");
        assertThat(json(finished.view())).isEqualTo("{\"operationId\":\"op_contract\",\"outcome\":\"COMPLETED\","
                + "\"command\":\"lift-restriction\",\"feature\":\"SYNC\",\"scope\":\"GLOBAL\",\"restrictionId\":\""
                + ID + "\",\"liftedAt\":\"2026-10-13T01:02:03Z\",\"smoke\":{\"status\":\"PASSED\","
                + "\"reopenedStages\":[\"PLAN_END\"],\"stillBlockedStages\":[]}}");
    }

    // ------------------------------------------------------------------ 공지 발행

    @Test
    void 공지_발행의_미리보기는_NoticeImpactView이고_빈_다음_안내는_생략하며_확정_본문은_NoticeCompletedView다() {
        PublishNoticeCommand command = new PublishNoticeCommand(mock(ServiceNoticeRepository.class),
                mock(OperationalRestrictionRepository.class), this.environment);
        when(this.environment.supportContact()).thenReturn("help@bokrilog.test");
        var investigating = new PublishNoticeCommand.Notice(null, 1, NoticeStatus.INVESTIGATING, "조사 중",
                List.of(ID), List.of(RestrictionFeature.SYNC), List.of(RestrictionFeature.ADVERTISING), AT, null);
        assertThat(json(preview(command.inspect(accepted("publish-notice", "NEW", investigating), investigating))))
                .isEqualTo("{\"incident\":\"NEW\",\"version\":1,\"status\":\"INVESTIGATING\","
                        + "\"affectedFeatures\":[\"SYNC\"],\"continuingFeatures\":[\"ADVERTISING\"],\"covers\":[\"" + ID
                        + "\"],\"nextUpdateAt\":\"2026-10-13T01:02:03Z\"}");
        var recovered = new PublishNoticeCommand.Notice(ID, 3, NoticeStatus.RECOVERED, "복구", List.of(ID),
                List.of(RestrictionFeature.SYNC), List.of(), null, null);
        assertThat(json(preview(command.inspect(accepted("publish-notice", ID.toString(), recovered), recovered))))
                .isEqualTo("{\"incident\":\"" + ID + "\",\"version\":3,\"status\":\"RECOVERED\","
                        + "\"affectedFeatures\":[\"SYNC\"],\"continuingFeatures\":[],\"covers\":[\"" + ID + "\"]}");
        var finished = command.afterCommit(engine, "publish-notice", ENVELOPE,
                new PublishNoticeCommand.NoticeResult("op_contract", ID, 2, NoticeStatus.SCOPE_CHANGED, AT), "c");
        assertThat(json(finished.view())).isEqualTo("{\"operationId\":\"op_contract\",\"outcome\":\"COMPLETED\","
                + "\"command\":\"publish-notice\",\"incidentId\":\"" + ID + "\",\"version\":2,"
                + "\"status\":\"SCOPE_CHANGED\",\"publishedAt\":\"2026-10-13T01:02:03Z\"}");
    }

    // ------------------------------------------------------------------ 문의 처리

    @Test
    void 문의_처리의_미리보기는_InquiryImpactView이고_빈_값은_생략하며_확정_본문은_InquiryCompletedView다() {
        UpdateInquiryCommand command = new UpdateInquiryCommand(mock(SupportInquiryRepository.class),
                mock(ActorDigest.class), mock(SupportMetrics.class), this.environment);
        var answered = new UpdateInquiryCommand.Inquiry("Q-AAAAAAAAAA", SupportInquiryApi.Status.IN_PROGRESS,
                SupportInquiryApi.Status.ANSWERED, AT, "답변", null);
        assertThat(json(preview(command.inspect(accepted("update-inquiry", "Q-AAAAAAAAAA", answered), answered))))
                .isEqualTo("{\"reference\":\"Q-AAAAAAAAAA\",\"currentStatus\":\"IN_PROGRESS\","
                        + "\"nextStatus\":\"ANSWERED\",\"targetAt\":\"2026-10-13T01:02:03Z\",\"answerProvided\":true}");
        var pending = new UpdateInquiryCommand.Inquiry("Q-AAAAAAAAAA", null, SupportInquiryApi.Status.IN_PROGRESS,
                null, null, null);
        assertThat(json(preview(command.inspect(accepted("update-inquiry", "Q-AAAAAAAAAA", pending), pending))))
                .isEqualTo("{\"reference\":\"Q-AAAAAAAAAA\",\"nextStatus\":\"IN_PROGRESS\",\"answerProvided\":false}");
        var finished = command.afterCommit(engine, "update-inquiry", ENVELOPE,
                new UpdateInquiryCommand.InquiryResult("op_contract", SupportInquiryApi.Status.RESOLVED, AT), "c");
        assertThat(json(finished.view())).isEqualTo("{\"operationId\":\"op_contract\",\"outcome\":\"COMPLETED\","
                + "\"command\":\"update-inquiry\",\"status\":\"RESOLVED\",\"transitionedAt\":\"2026-10-13T01:02:03Z\"}");
    }
}
