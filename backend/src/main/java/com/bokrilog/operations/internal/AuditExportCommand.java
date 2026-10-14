package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorCommandApi.Accepted;
import com.bokrilog.operations.OperatorCommandApi.Envelope;
import com.bokrilog.operations.OperatorCommandApi.Finished;
import com.bokrilog.operations.OperatorCommandApi.Proposal;
import com.bokrilog.operations.OperatorCommandApi.Validation;
import com.bokrilog.operations.OperatorPermission;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 감사 원장 대량 추출(Story 3.1 AC-5) — {@value OperatorCommandApi#AUDIT_EXPORT}. command이면서 감사 데이터를 꺼내므로
 * {@code command}·{@code audit} 두 권한을 함께 요구하고, 추출 자체가 별도 감사 사건이다.
 *
 * <p>허가에 저장되는 것은 상한·행 수·잘림뿐이다(허가 결과 상한 4KiB) — 행 묶음은 원장을 {@code sequence <= 상한}으로 다시
 * 읽어 만든다. 원장이 추가 전용이라 재전송도 같은 묶음이다.
 */
final class AuditExportCommand implements OperatorCommand<AuditExportCommand.Export, AuditExportCommand.ExportResult> {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuditExportCommand.class);

    /** 검증된 추출 요청. 기간 원문이 envelope의 target이기도 하다. */
    record Export(String target) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;
    }

    /** 추출 실행의 결과. 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. */
    record ExportResult(String operationId, String target, long upperSequence, int rowCount, boolean truncated) {}

    private final OperatorAuditRepository repository;

    AuditExportCommand(OperatorAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.AUDIT_EXPORT);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        // command이면서 감사 데이터를 꺼낸다 — 두 권한을 함께 요구한다(AC-5).
        return EnumSet.of(OperatorPermission.COMMAND, OperatorPermission.AUDIT);
    }

    @Override
    public Class<Export> payloadType() {
        return Export.class;
    }

    @Override
    public Class<ExportResult> resultType() {
        return ExportResult.class;
    }

    @Override
    public Validation validate(Proposal proposal) {
        String scope = proposal.scope() == null ? "" : proposal.scope().strip();
        if (!OperatorCommandApi.SCOPE_AUDIT_LEDGER.equals(scope)) {
            return new Validation.Invalid(OperatorCommandApi.UNSUPPORTED_SCOPE);
        }
        AuditPeriod period = AuditPeriod.parseTarget(proposal.target());
        if (period == null || period.days() > OperatorCommandApi.MAX_EXPORT_DAYS) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableReason(reason)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        String target = period.target();
        String command = OperatorCommandApi.AUDIT_EXPORT;
        return new Validation.Valid(new Accepted(command, scope, target, target, reason,
                "감사 내보내기 " + scope + " " + target, OperatorCommandApi.POLICY_VERSION, permissions(),
                OperatorEnvelopeService.sha256(command + "\n" + scope + "\n" + target + "\n" + reason),
                new Export(target)));
    }

    @Override
    public Done<ExportResult> complete(OperatorEnvelopeService engine, Envelope envelope, Export payload) {
        AuditPeriod period = AuditPeriod.parseTarget(envelope.target());
        if (period == null) {
            throw new IllegalStateException("검증을 지난 추출 대상이 아니다.");
        }
        long upper = this.repository.maxSequence();
        int counted = this.repository.countWithin(period.start(), period.end(), upper,
                OperatorCommandApi.MAX_EXPORT_ROWS + 1);
        boolean truncated = counted > OperatorCommandApi.MAX_EXPORT_ROWS;
        int rowCount = Math.min(counted, OperatorCommandApi.MAX_EXPORT_ROWS);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("upperSequence", upper);
        details.put("rowCount", rowCount);
        details.put("truncated", truncated);
        return new Done<>(new ExportResult(envelope.operationId(), envelope.target(), upper, rowCount, truncated),
                details);
    }

    @Override
    public String grantRecord(ExportResult result) {
        return result.operationId() + "|" + result.upperSequence() + "|" + result.rowCount() + "|"
                + result.truncated();
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            ExportResult result, String correlationId) {
        List<OperatorAuditApi.Entry> rows;
        try {
            rows = rowsOf(result);
        } catch (RuntimeException unreadable) {
            /*
             * 실행은 이미 커밋됐다. 행을 다시 읽지 못한 사실만 남기고 실패로 답한다 — 클라이언트는 응답을 불확실로 다루고 같은
             * 키로 다시 확정하면 원장이 같은 묶음을 재생한다. 실패 행을 쓰지 않는다(실패가 아니다).
             */
            LOG.atWarn().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("operator command completed but its rows were not read back: command={}", requestedCommand);
            throw unreadable;
        }
        return new Finished(view(requestedCommand, result, rows),
                "rows=" + result.rowCount() + " truncated=" + result.truncated());
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        // 원장에 없는 값은 0·거짓이다(잘린 적 없는 빈 추출). 상한은 언제나 쓰여 있다.
        Long upper = CommandJson.number(details, "upperSequence");
        Long rowCount = CommandJson.number(details, "rowCount");
        Boolean truncated = CommandJson.bool(details, "truncated");
        ExportResult result = new ExportResult(operationId, ledgerTarget, upper == null ? 0 : upper,
                rowCount == null ? 0 : rowCount.intValue(), Boolean.TRUE.equals(truncated));
        return view(requestedCommand, result, rowsOf(result));
    }

    /** 추출 결과의 행 묶음. 조회와 같은 allowlist 필드이며 오래된 순이다. */
    private List<OperatorAuditApi.Entry> rowsOf(ExportResult result) {
        AuditPeriod period = AuditPeriod.parseTarget(result.target());
        if (period == null) {
            throw new IllegalStateException("저장된 추출 대상을 읽을 수 없다.");
        }
        return this.repository.within(period.start(), period.end(), result.upperSequence(),
                OperatorCommandApi.MAX_EXPORT_ROWS);
    }

    private static Map<String, Object> view(String command, ExportResult result, List<OperatorAuditApi.Entry> rows) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(result.operationId(), command);
        view.put("target", result.target());
        view.put("upperSequence", result.upperSequence());
        view.put("rowCount", result.rowCount());
        view.put("truncated", result.truncated());
        view.put("rows", rows.stream().map(OperatorAuditApi.Entry::view).toList());
        return view;
    }
}
