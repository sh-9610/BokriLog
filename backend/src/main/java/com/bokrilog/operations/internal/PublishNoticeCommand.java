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
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.ServiceNoticeApi;
import com.bokrilog.operations.ServiceNoticeApi.NoticeStatus;
import com.bokrilog.operations.ServiceNoticeApi.NoticeVersion;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 서비스 공지 발행(Story 3.5) — {@value OperatorCommandApi#PUBLISH_NOTICE}. 새 사건은 조사 중으로만 열고, 기존 사건은 범위
 * 변경·복구만 받는다. 상태는 scope, 사건은 target, 메시지는 사유 자리다.
 *
 * <p><b>덮는 범위는 제한의 되돌림 계보다(Epic 3 회고 AI-1).</b> 덮는 제한 R은 R과 R에서 {@code reinstated_from}으로 이어진
 * 모든 후손을 덮는다. 진행 중인 공지는 그 계보 안에 지금 열린 제한이 하나 이상 있어야 하고, 복구 공지는 그 계보 전체가
 * 닫혀 있어야 한다 — 해제 smoke가 같은 범위를 새 제한으로 되돌렸는데 원 제한만 보고 "복구됨"을 알리지 않는다. 준비와
 * 확정이 같은 판정({@link #coverRefusal})을 한다.
 *
 * <p>확정의 순서: (엔진: 잠금 → 최종 권한) → 지원 연락처 → 사건의 최신 버전(없음·닫힘·번호 어긋남) → 덮는 제한 → 다음 안내
 * 시각(DB 시각) → (새 사건이면 사건 행) → 버전 행(확정 시점의 지원 연락처를 함께 고정한다) → (엔진: {@code COMPLETED}). 모든
 * 발행이 같은 잠금을 쥐므로 같은 사건의 동시 발행 둘이 줄을 서고, 뒤의 것은 앞의 것이 쓴 버전을 보고
 * {@code NOTICE_VERSION_CONFLICT}로 거절된다. 최후 권위는 버전 기본키다 — 그 제약의 위반만 같은 code이고, 다른 무결성 위반은
 * 실행 실패로 올라간다.
 */
final class PublishNoticeCommand implements OperatorCommand<PublishNoticeCommand.Notice,
        PublishNoticeCommand.NoticeResult> {

    /**
     * 공지 발행의 입력 원문. 상태·사건·메시지는 {@link Proposal}의 scope·target·reason 자리다.
     *
     * @param covers 덮을 제한 ID. 본문에 없으면 {@code null}
     * @param nextUpdateAt 다음 안내 시각(ISO-8601, 오프셋 포함). 없으면 {@code null}
     */
    record NoticeInput(@Nullable List<String> covers, @Nullable String nextUpdateAt) {}

    /**
     * 검증을 지난 공지 발행. 세션 무장에 실린다. 영향·계속 가능 기능은 덮는 제한의 기능에서 서버가 계산했다.
     *
     * @param incidentId 기존 사건. 새 사건이면 {@code null}이고 사건 ID는 확정이 만든다
     * @param version 쓸 버전 번호. 준비가 본 최신 버전의 다음 번호다
     * @param covers 덮을 제한 ID(글자 순서, 중복 없음)
     * @param nextUpdateAt 진행 중인 공지의 다음 안내 시각. 복구 공지는 {@code null}
     * @param refusal 준비 시각에 본 상태 거절({@code TARGET_INVALID}·{@code INCIDENT_CLOSED}·{@code NEXT_UPDATE_INVALID}).
     *     준비 뒤 판정({@link #inspect})이 멱등 키·재인증 판정 뒤에 적용한다. 무장되는 값은 언제나 {@code null}이다
     */
    record Notice(@Nullable UUID incidentId, int version, NoticeStatus status, String message, List<UUID> covers,
            List<RestrictionFeature> affectedFeatures, List<RestrictionFeature> continuingFeatures,
            @Nullable Instant nextUpdateAt, @Nullable String refusal) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        Notice {
            covers = List.copyOf(covers);
            affectedFeatures = List.copyOf(affectedFeatures);
            continuingFeatures = List.copyOf(continuingFeatures);
        }
    }

    /**
     * 공지 발행의 결과. 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. 메시지 원문을 담지 않는다.
     *
     * @param publishedAt 버전을 쓴 DB 시각
     */
    record NoticeResult(String operationId, UUID incidentId, int version, NoticeStatus status, Instant publishedAt) {}

    private final ServiceNoticeRepository notices;

    private final OperationalRestrictionRepository restrictions;

    private final OperatorCommandEnvironment environment;

    PublishNoticeCommand(ServiceNoticeRepository notices, OperationalRestrictionRepository restrictions,
            OperatorCommandEnvironment environment) {
        this.notices = notices;
        this.restrictions = restrictions;
        this.environment = environment;
    }

    @Override
    public Set<String> names() {
        return Set.of(OperatorCommandApi.PUBLISH_NOTICE);
    }

    @Override
    public Set<OperatorPermission> permissions() {
        return EnumSet.of(OperatorPermission.COMMAND);
    }

    @Override
    public Class<Notice> payloadType() {
        return Notice.class;
    }

    @Override
    public Class<NoticeResult> resultType() {
        return NoticeResult.class;
    }

    /**
     * 공지 발행은 자기 이름의 필드를 받는다 — 사건({@code incident})이 target, 상태({@code status})가 scope, 메시지
     * ({@code message})가 사유 자리다. 문자열이 아니거나 덮는 제한이 문자열 배열이 아니면 400이다. 비어 있는 것은 검증이
     * 거절한다(422).
     */
    @Override
    public Proposal parse(String command, JsonNode body, Proposal common) {
        return new Proposal(command, CommandJson.stringOrNull(body, "status"),
                CommandJson.stringOrNull(body, "incident"), CommandJson.stringOrNull(body, "message"),
                common.actorClaimed(), new NoticeInput(CommandJson.stringsOrNull(body, "covers",
                        ServiceNoticeApi.MAX_COVERS), CommandJson.stringOrNull(body, "nextUpdateAt")));
    }

    /**
     * envelope 검증(AC-1·AC-6). 형식 — 상태·사건·메시지·덮는 제한 ID·다음 안내 시각 — 을 보고, 그다음 공지·제한 표에서 지금
     * 사실(사건의 최신 버전, 덮는 제한의 기능과 계보의 열림, DB 시각의 다음 안내 창)을 읽어 쓸 버전 번호·영향 기능·확인 문구를
     * 정한다. 상태 거절은 {@link Notice#refusal}에 싣고 준비 뒤 판정이 멱등 키·재인증 판정 <b>뒤</b>에 적용한다.
     *
     * <p>새 사건은 조사 중으로만 열고, 기존 사건은 범위 변경·복구만 받는다 — 그 밖의 조합은 {@code UNSUPPORTED_SCOPE}다.
     * 복구 공지가 덮는 제한을 비워 보내면 직전 버전의 것을 잇는다(이미 닫힌 제한은 운영자 화면의 활성 목록에 없다).
     */
    @Override
    public Validation validate(Proposal proposal) {
        NoticeStatus status = statusOf(proposal.scope());
        if (status == null) {
            return new Validation.Invalid(OperatorCommandApi.UNSUPPORTED_SCOPE);
        }
        String incidentText = proposal.target() == null ? "" : proposal.target().strip();
        UUID incidentId = null;
        if (!OperatorCommandApi.NEW_INCIDENT.equals(incidentText)) {
            if (!OperatorEnvelopeService.UUID_TEXT.matcher(incidentText).matches()) {
                return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
            }
            incidentId = UUID.fromString(incidentText);
        }
        if ((incidentId == null) != (status == NoticeStatus.INVESTIGATING)) {
            return new Validation.Invalid(OperatorCommandApi.UNSUPPORTED_SCOPE);
        }
        String message = proposal.reason() == null ? "" : proposal.reason().strip();
        if (message.isEmpty()) {
            return new Validation.Invalid(OperatorCommandApi.REASON_REQUIRED);
        }
        if (!OperatorEnvelopeService.isAcceptableMessage(message)) {
            return new Validation.Invalid(OperatorCommandApi.REASON_INVALID);
        }
        NoticeInput input = CommandJson.inputOf(proposal, NoticeInput.class);
        List<String> given = input == null || input.covers() == null ? List.of() : input.covers();
        if (given.size() > ServiceNoticeApi.MAX_COVERS) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        // 글자 순서로 정렬한다 — 저장·요청 digest·화면이 같은 순서를 본다(UUID의 비교 순서는 글자 순서가 아니다).
        Set<UUID> requested = new TreeSet<>(Comparator.comparing(UUID::toString));
        for (String text : given) {
            String cover = text == null ? "" : text.strip();
            // 모양이 아니거나 같은 제한을 두 번 담았다 — 덮는 범위를 개수로 꾸밀 수 없다.
            if (!OperatorEnvelopeService.UUID_TEXT.matcher(cover).matches() || !requested.add(UUID.fromString(cover))) {
                return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
            }
        }
        if (requested.isEmpty() && status != NoticeStatus.RECOVERED) {
            return new Validation.Invalid(OperatorCommandApi.TARGET_INVALID);
        }
        String nextText = input == null || input.nextUpdateAt() == null ? "" : input.nextUpdateAt().strip();
        Instant nextUpdateAt = null;
        if (status == NoticeStatus.RECOVERED) {
            if (!nextText.isEmpty()) {
                return new Validation.Invalid(OperatorCommandApi.NEXT_UPDATE_INVALID);
            }
        } else {
            nextUpdateAt = instantOf(nextText);
            if (nextUpdateAt == null) {
                return new Validation.Invalid(OperatorCommandApi.NEXT_UPDATE_INVALID);
            }
        }

        // 지금 사실. 거절은 싣기만 하고 준비 뒤 판정이 멱등 키·재인증 뒤에 적용한다.
        String refusal = null;
        int version = 1;
        List<UUID> covers = List.copyOf(requested);
        if (incidentId != null) {
            NoticeVersion latest = this.notices.latest(incidentId).orElse(null);
            if (latest == null) {
                refusal = OperatorCommandApi.TARGET_INVALID;
            } else {
                version = latest.version() + 1;
                if (latest.status() == NoticeStatus.RECOVERED) {
                    refusal = OperatorCommandApi.INCIDENT_CLOSED;
                } else if (covers.isEmpty()) {
                    covers = latest.covers();
                }
            }
        }
        Set<RestrictionFeature> affected = EnumSet.noneOf(RestrictionFeature.class);
        if (refusal == null) {
            refusal = coverRefusal(status, covers, affected);
        }
        if (refusal == null && nextUpdateAt != null
                && !this.notices.withinNextUpdateWindow(nextUpdateAt, ServiceNoticeApi.MAX_NEXT_UPDATE.toSeconds())) {
            refusal = OperatorCommandApi.NEXT_UPDATE_INVALID;
        }
        String target = incidentId == null ? OperatorCommandApi.NEW_INCIDENT : incidentId.toString();
        String ledgerScope = OperatorCommandApi.NOTICE_SCOPE_PREFIX + status.name();
        // 확인 문구가 사건·범위를 묶는다(item-7): 새 사건은 덮는 제한 수, 기존 사건은 사건 ID 앞 8자와 버전.
        String phrase = incidentId == null ? "공지 발행 새 사건 제한 " + covers.size() + "건"
                : "공지 " + OperatorEnvelopeService.noticeStatusWord(status) + " " + incidentId.toString().substring(0, 8)
                        + " v" + version;
        String digest = OperatorEnvelopeService.sha256(OperatorCommandApi.PUBLISH_NOTICE + "\n" + ledgerScope + "\n"
                + target + "\n" + message + "\n"
                + requested.stream().map(UUID::toString).collect(Collectors.joining(","))
                + "\n" + (nextUpdateAt == null ? "-" : nextUpdateAt.toString()));
        Notice notice = new Notice(incidentId, version, status, message, covers, List.copyOf(affected),
                ServiceNoticeApi.continuing(affected), nextUpdateAt, refusal);
        return new Validation.Valid(new Accepted(OperatorCommandApi.PUBLISH_NOTICE, ledgerScope, target, target,
                message, phrase, OperatorCommandApi.POLICY_VERSION, permissions(), digest, notice));
    }

    /**
     * 거절 행: scope는 아는 상태일 때만 {@code NOTICE:<상태>}, target은 {@code NEW}이거나 사건 ID 모양일 때만, 메시지는 공지
     * 메시지 규칙을 지날 때만 남긴다 — 숫자열·URL 때문에 거절된 메시지가 거절 행을 타고 원장에 들어가는 길을 막는다.
     */
    @Override
    public RefusedLedger refusedLedger(Proposal proposal, RefusedLedger common) {
        NoticeStatus status = statusOf(proposal.scope());
        String target = proposal.target() == null ? "" : proposal.target().strip();
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        return new RefusedLedger(
                status == null ? OperatorEnvelopeService.UNREADABLE
                        : OperatorCommandApi.NOTICE_SCOPE_PREFIX + status.name(),
                OperatorCommandApi.NEW_INCIDENT.equals(target) || OperatorEnvelopeService.UUID_TEXT.matcher(target)
                        .matches() ? target : OperatorEnvelopeService.UNREADABLE,
                OperatorEnvelopeService.isAcceptableMessage(reason));
    }

    /**
     * 준비 뒤 판정. 연락처가 비었으면 어떤 공지도 발행하지 않는다 — 멱등 키·재인증 판정 뒤, 대상 판정 앞의 자리다(3.2·3.4와
     * 같은 키 → 재인증 → 대상 순서). 그다음 검증이 본 상태 거절(없는 사건·덮을 수 없는 제한은 {@code TARGET_INVALID}, 복구로
     * 닫힌 사건은 {@code INCIDENT_CLOSED}, 범위 밖 다음 안내 시각은 {@code NEXT_UPDATE_INVALID})이다. 거절의 문장은 요청한 공지
     * 상태로 갈린다.
     */
    @Override
    public Inspection inspect(Accepted accepted, Notice notice) {
        String status = notice.status().name();
        if (this.environment.supportContact() == null) {
            return new Inspection.Refused(new Refusal(OperatorCommandApi.SUPPORT_CONTACT_UNCONFIGURED, status));
        }
        if (notice.refusal() != null) {
            return new Inspection.Refused(new Refusal(notice.refusal(), status));
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("incident", accepted.target());
        preview.put("version", notice.version());
        preview.put("status", status);
        preview.put("affectedFeatures", names(notice.affectedFeatures()));
        preview.put("continuingFeatures", names(notice.continuingFeatures()));
        preview.put("covers", notice.covers());
        if (notice.nextUpdateAt() != null) {
            preview.put("nextUpdateAt", notice.nextUpdateAt().toString());
        }
        return new Inspection.Ready(preview);
    }

    @Override
    public Done<NoticeResult> complete(OperatorEnvelopeService engine, Envelope envelope, Notice notice) {
        // 연락처가 그사이 비었으면 아무것도 쓰지 않는다.
        String contact = this.environment.supportContact();
        if (contact == null) {
            throw new CommandRefusal(OperatorCommandApi.SUPPORT_CONTACT_UNCONFIGURED);
        }
        UUID incidentId = notice.incidentId();
        if (incidentId != null) {
            // 같은 잠금 아래라 그 사이 다른 발행이 끼어들 수 없다. 준비가 본 최신 버전 뒤에 끼었으면 충돌이다.
            NoticeVersion latest = this.notices.latest(incidentId).orElse(null);
            if (latest == null) {
                throw new CommandRefusal(OperatorCommandApi.TARGET_INVALID);
            }
            if (latest.status() == NoticeStatus.RECOVERED) {
                throw new CommandRefusal(OperatorCommandApi.INCIDENT_CLOSED);
            }
            if (latest.version() != notice.version() - 1) {
                throw new CommandRefusal(OperatorCommandApi.NOTICE_VERSION_CONFLICT);
            }
        }
        // 확정 순간의 덮는 제한. 준비 뒤 계보 전체가 닫힌 제한을 진행 중인 공지가 덮지 않고, 준비 뒤 되돌림으로 다시 열린
        // 계보를 복구 공지가 덮지 않는다.
        Set<RestrictionFeature> affected = EnumSet.noneOf(RestrictionFeature.class);
        String refusal = coverRefusal(notice.status(), notice.covers(), affected);
        if (refusal != null) {
            throw new CommandRefusal(refusal);
        }
        Instant nextUpdateAt = notice.nextUpdateAt();
        if (nextUpdateAt != null
                && !this.notices.withinNextUpdateWindow(nextUpdateAt, ServiceNoticeApi.MAX_NEXT_UPDATE.toSeconds())) {
            throw new CommandRefusal(OperatorCommandApi.NEXT_UPDATE_INVALID);
        }
        if (incidentId == null) {
            incidentId = UUID.randomUUID();
            this.notices.insertIncident(incidentId, envelope.operationId());
        }
        List<RestrictionFeature> affectedFeatures = List.copyOf(affected);
        ServiceNoticeRepository.Written written;
        try {
            written = this.notices.insertVersion(incidentId, notice.version(), notice.status(), notice.message(),
                    affectedFeatures, ServiceNoticeApi.continuing(affectedFeatures), nextUpdateAt, notice.covers(),
                    envelope.operationId(), contact);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (!OperatorEnvelopeService.versionConflict(duplicate)) {
                throw duplicate;
            }
            // 같은 번호를 기본키가 거절했다. 이 트랜잭션은 더 쓰지 않고 호출자가 되돌린다.
            throw new CommandRefusal(OperatorCommandApi.NOTICE_VERSION_CONFLICT);
        }
        // 메시지 원문은 원장의 사유 열에만 있다. 부가 사실은 사건·버전·상태·덮는 제한 수다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incidentId", incidentId.toString());
        details.put("version", notice.version());
        details.put("status", notice.status().name());
        details.put("covers", notice.covers().size());
        details.put("publishedAt", written.createdAt().toString());
        return new Done<>(new NoticeResult(envelope.operationId(), incidentId, notice.version(), notice.status(),
                written.createdAt()), details);
    }

    @Override
    public String grantRecord(NoticeResult result) {
        return result.operationId() + "|" + result.incidentId() + "|" + result.version();
    }

    /** 같은 거절 code의 문장을 무장된 공지 상태가 가른다(복구 공지의 대상 거절). */
    @Override
    public String refusalDetail(Notice notice) {
        return notice.status().name();
    }

    @Override
    public Finished afterCommit(OperatorEnvelopeService engine, String requestedCommand, Envelope envelope,
            NoticeResult result, String correlationId) {
        // 상태·버전만 남긴다. 사건 ID·메시지·수행자는 남기지 않는다.
        return new Finished(view(requestedCommand, result),
                "status=" + result.status() + " version=" + result.version());
    }

    @Override
    public Map<String, Object> replay(OperatorEnvelopeService engine, String requestedCommand, String operationId,
            String ledgerTarget, JsonNode details) {
        UUID incidentId = CommandJson.uuid(details, "incidentId");
        Long version = CommandJson.number(details, "version");
        String status = CommandJson.text(details, "status");
        Instant publishedAt = CommandJson.instant(details, "publishedAt");
        if (incidentId == null || version == null || status == null || publishedAt == null) {
            throw new IllegalStateException("공지 발행의 완료 행을 읽을 수 없다.");
        }
        return view(requestedCommand, new NoticeResult(operationId, incidentId, version.intValue(),
                NoticeStatus.valueOf(status), publishedAt));
    }

    private static Map<String, Object> view(String command, NoticeResult result) {
        Map<String, Object> view = OperatorEnvelopeService.completedView(result.operationId(), command);
        view.put("incidentId", result.incidentId());
        view.put("version", result.version());
        view.put("status", result.status().name());
        view.put("publishedAt", result.publishedAt().toString());
        return view;
    }

    /**
     * 덮는 제한의 판정과 영향 기능. 모두 있어야 하고, 진행 중인 공지는 계보 안에 지금 열린 제한이 있는 것만, 복구 공지는 계보
     * 전체가 닫힌 것만 덮는다 — 아직 막고 있는 제한(되돌림으로 새 ID가 된 것 포함)을 "복구됨"으로 알리지 않는다. 거절할 것이
     * 없으면 {@code null}이고 {@code affected}에 그 제한들의 기능을 채운다.
     */
    private @Nullable String coverRefusal(NoticeStatus status, Collection<UUID> covers,
            Set<RestrictionFeature> affected) {
        if (covers.isEmpty()) {
            return OperatorCommandApi.TARGET_INVALID;
        }
        var facts = this.restrictions.coverFacts(covers);
        if (facts.size() != covers.size()) {
            return OperatorCommandApi.TARGET_INVALID;
        }
        for (var fact : facts) {
            if ((status == NoticeStatus.RECOVERED) == fact.open()) {
                return OperatorCommandApi.TARGET_INVALID;
            }
            affected.add(fact.feature());
        }
        return null;
    }

    private static List<String> names(List<RestrictionFeature> features) {
        return features.stream().map(Enum::name).toList();
    }

    private static @Nullable NoticeStatus statusOf(@Nullable String text) {
        String status = text == null ? "" : text.strip();
        for (NoticeStatus known : NoticeStatus.values()) {
            if (known.name().equals(status)) {
                return known;
            }
        }
        return null;
    }

    /** 다음 안내 시각. 오프셋을 가진 ISO-8601만 읽는다 — 시간대를 추측하지 않는다. */
    private static @Nullable Instant instantOf(String text) {
        if (text.isEmpty() || text.length() > 64) {
            return null;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(text);
            // 데이터베이스 시각 범위 밖(예: `+300000-…`)은 비교 전에 거절한다 — 판정 문장이 실패해 500이 되지 않게.
            if (parsed.getYear() < 1970 || parsed.getYear() > 9999) {
                return null;
            }
            return parsed.toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }
}
