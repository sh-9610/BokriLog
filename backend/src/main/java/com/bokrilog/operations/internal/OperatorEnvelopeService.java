package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionOrigin;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.operations.ServiceNoticeApi;
import com.bokrilog.operations.ServiceNoticeApi.NoticeStatus;
import com.bokrilog.operations.ServiceNoticeApi.NoticeVersion;
import com.bokrilog.operations.SupportInquiryApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 운영 command의 envelope 검증과 원장 기록(Story 3.1).
 *
 * <p>servlet 역할에서만 배선한다 — 감사 digest 키가 그 역할의 것이고({@link ActorDigest}), 운영 command는 API 프로세스에만
 * 있다.
 *
 * <p>원장 추가는 언제나 트랜잭션 안에서 잠금 → INSERT 순서다({@link OperatorAuditRepository#lockAppends}). 호출자가 연
 * 트랜잭션이 있으면 거기 참여한다 — 추출의 완료 행은 허가 소비와 같은 트랜잭션이어야 한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class OperatorEnvelopeService implements OperatorCommandApi {

    /** 수행자 종류. 운영 command는 운영자만 낸다(SYSTEM 행은 Story 3.3 자동 정지가 {@code OperationalSignalService}로 쓴다). */
    static final String ACTOR_OPERATOR = "OPERATOR";

    /** 이메일 주소처럼 보이는 조각. 사유에 이메일을 남기지 않는다(AC-4). */
    private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    /** 제어 문자(개행 포함). 사유는 한 줄 문장이다 — 로그 위조와 화면 깨짐을 함께 막는다. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** 거절 행에 원문으로 남겨도 되는 scope·target의 모양. 그 밖은 {@link #UNREADABLE}이다. */
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Z][A-Z_]{0,63}");

    private static final Pattern SAFE_TARGET = Pattern.compile("[0-9A-Za-z~:._-]{1,128}");

    /** 정규 UUID(대소문자 무관). {@link UUID#fromString}만으로는 줄인 묶음도 받는다. */
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 계산 정책 버전의 모양(예: {@code CALC-1.0}). 실제로 아는 버전인지는 루트가 계산 모듈의 상수로 본다. */
    private static final Pattern POLICY_VERSION_TEXT = Pattern.compile("[A-Z][A-Z0-9]{0,15}-[0-9]{1,3}\\.[0-9]{1,3}");

    /** 배포 버전의 모양(Story 3.4). */
    private static final Pattern DEPLOY_VERSION = Pattern.compile("[A-Za-z0-9._+-]{1," + MAX_DEPLOY_VERSION_CHARS + "}");

    /** IPv4 주소와 {@code 호스트:포트} 모양(Story 3.5 리뷰). 내부 주소를 사용자 화면에 내보내지 않는다. */
    private static final Pattern NETWORK_LIKE = Pattern.compile(
            "\\p{Nd}{1,3}(?:\\.\\p{Nd}{1,3}){3}|[A-Za-z]:\\p{Nd}{2,5}(?!\\p{Nd})");

    /**
     * 화면 카피 금지 표현(Story 3.5 리뷰): 실시간·정확 보장·손실 공포·수익 축하·스트릭·배지. 프런트엔드
     * {@code content/copyRules.ts}의 {@code FORBIDDEN_COPY_WORDS}와 같은 목록이다 — 시험({@code ServiceNoticeTest})이 두 목록을
     * 대조한다. 운영자 한 줄도 사용자 화면에 그대로 나가므로 같은 규칙을 지난다.
     */
    static final List<String> FORBIDDEN_COPY_WORDS = List.of("실시간", "정확", "보장", "축하", "손실", "수익을 내",
            "스트릭", "배지");

    /**
     * URL·도메인·경로 모양(Story 3.5). 스킴({@code x://}), {@code www.}, {@code 이름.최상위} 도메인, 공백 뒤의 {@code /경로}다.
     * 공지 메시지가 외부 주소나 내부 경로를 사용자에게 내보내지 않게 한다.
     */
    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://|www\\.|[a-z0-9-]+\\.[a-z]{2,}\\b|(^|[\\s(\\[])/[A-Za-z0-9._~-])");

    /** 거절 행에서 형식이 맞지 않아 원문을 남기지 않은 자리. */
    private static final String UNREADABLE = "-";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OperatorAuditRepository repository;

    /** 제한 표(Story 3.2). 같은 모듈의 repository다 — 제한 행은 감사 행과 같은 트랜잭션에서만 생긴다. */
    private final OperationalRestrictionRepository restrictions;

    /** 공지 표(Story 3.5). 같은 모듈의 repository다 — 공지 행은 감사 행과 같은 트랜잭션에서만 생긴다. */
    private final ServiceNoticeRepository notices;

    /** 문의 표(Story 3.6). 같은 모듈의 repository다 — 운영 처리는 감사 행과 같은 트랜잭션에서만 문의 행을 바꾼다. */
    private final SupportInquiryRepository inquiries;

    private final ActorDigest digest;

    /** 문의 첫 답변 목표의 계측(Story 3.7). */
    private final SupportMetrics supportMetrics;

    private final TransactionTemplate transactions;

    OperatorEnvelopeService(OperatorAuditRepository repository, OperationalRestrictionRepository restrictions,
            ServiceNoticeRepository notices, SupportInquiryRepository inquiries, ActorDigest digest,
            SupportMetrics supportMetrics, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.supportMetrics = supportMetrics;
        this.restrictions = restrictions;
        this.notices = notices;
        this.inquiries = inquiries;
        this.digest = digest;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean isSupported(String command) {
        return AUDIT_EXPORT.equals(command) || LIFT_RESTRICTION.equals(command) || PUBLISH_NOTICE.equals(command)
                || UPDATE_INQUIRY.equals(command) || OperatorCommandApi.restrictionFeatureOf(command) != null;
    }

    @Override
    public Set<OperatorPermission> permissionsOf(String command) {
        if (AUDIT_EXPORT.equals(command)) {
            // command이면서 감사 데이터를 꺼낸다 — 두 권한을 함께 요구한다(AC-5).
            return EnumSet.of(OperatorPermission.COMMAND, OperatorPermission.AUDIT);
        }
        if (OperatorCommandApi.restrictionFeatureOf(command) != null || LIFT_RESTRICTION.equals(command)
                || PUBLISH_NOTICE.equals(command) || UPDATE_INQUIRY.equals(command)) {
            // 제한·해제·공지 발행·문의 처리는 일반 운영 명령이다. 감사 데이터를 꺼내지 않는다.
            return EnumSet.of(OperatorPermission.COMMAND);
        }
        throw new IllegalArgumentException("모르는 운영 command다.");
    }

    @Override
    public Validation validate(Proposal proposal) {
        if (proposal.actorClaimed()) {
            return new Validation.Invalid(ACTOR_NOT_ACCEPTED);
        }
        if (!isSupported(proposal.command())) {
            throw new IllegalArgumentException("모르는 운영 command다.");
        }
        RestrictionFeature feature = OperatorCommandApi.restrictionFeatureOf(proposal.command());
        if (feature != null) {
            return validateRestriction(proposal, feature);
        }
        if (LIFT_RESTRICTION.equals(proposal.command())) {
            return validateLift(proposal);
        }
        if (PUBLISH_NOTICE.equals(proposal.command())) {
            return validateNotice(proposal);
        }
        if (UPDATE_INQUIRY.equals(proposal.command())) {
            return validateInquiry(proposal);
        }
        String scope = proposal.scope() == null ? "" : proposal.scope().strip();
        if (!SCOPE_AUDIT_LEDGER.equals(scope)) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        AuditPeriod period = AuditPeriod.parseTarget(proposal.target());
        if (period == null || period.days() > MAX_EXPORT_DAYS) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String target = period.target();
        String phrase = "감사 내보내기 " + scope + " " + target;
        return new Validation.Valid(new Accepted(AUDIT_EXPORT, scope, target, target, reason, phrase, POLICY_VERSION,
                permissionsOf(AUDIT_EXPORT), sha256(AUDIT_EXPORT + "\n" + scope + "\n" + target + "\n" + reason),
                null, null, null, null));
    }

    /**
     * 제한 생성 command의 envelope 검증(Story 3.2 AC-1). 조합(기능 × scope)과 target의 <b>형식</b>만 본다 — 대상의
     * 존재·상태·중복은 다른 모듈과 제한 표의 사실이라 루트가 본다.
     *
     * <p>원장의 scope는 {@code <기능>:<scope>}이고, 사용자에게 귀속된 target은 원장·허가·요청 digest에 keyed digest로만
     * 들어간다(AC-6). 원문은 확인 문구와 제한 행에만 있다.
     */
    private Validation validateRestriction(Proposal proposal, RestrictionFeature feature) {
        RestrictionScope scope = scopeOf(proposal.scope());
        if (scope == null || !feature.admits(scope)) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        String target = restrictionTarget(scope, proposal.target());
        if (target == null) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String ledgerScope = ledgerScope(feature, scope);
        String ledgerTarget = scope.userAttributed() ? this.digest.ofTarget(scope.name(), target) : target;
        String command = proposal.command();
        return new Validation.Valid(new Accepted(command, ledgerScope, target, ledgerTarget, reason,
                restrictionPhrase(feature, scope, target), POLICY_VERSION, permissionsOf(command),
                sha256(command + "\n" + ledgerScope + "\n" + ledgerTarget + "\n" + reason),
                new Restriction(feature, scope, target), null, null, null));
    }

    /**
     * 해제 command의 envelope 검증(Story 3.4 AC-1·AC-2). target(제한 ID)의 형식, 복구 조건 네 확인, 원인(사유)·수행한 검증·
     * 증거 참조의 사유 규칙, 배포 버전의 모양을 본다. 그다음 같은 모듈의 제한 표에서 그 제한의 지금 사실을 읽어 원장 scope와
     * 확인 문구를 정한다 — 없거나 닫힌 제한의 거절은 루트가 멱등 키·재인증 판정 <b>뒤</b>에 한다(3.2와 같은 준비 순서).
     *
     * <p>원장의 target은 제한 ID다. 제한 ID는 사용자 귀속 원식별자가 아니다(난수 행 ID). 요청 digest는 제한 ID와 증거
     * 입력 전부로 만든다 — 같은 키로 다른 증거를 보내면 충돌이다.
     */
    private Validation validateLift(Proposal proposal) {
        String text = proposal.target() == null ? "" : proposal.target().strip();
        if (!UUID_TEXT.matcher(text).matches()) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        UUID restrictionId = UUID.fromString(text);
        RecoveryInput recovery = proposal.recovery();
        List<String> given = recovery == null || recovery.conditions() == null ? List.of() : recovery.conditions();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String condition : given) {
            // 모르는 code와 같은 code의 반복은 형식 오류다 — 넷을 확인했다는 사실을 개수로 꾸밀 수 없다.
            if (!RECOVERY_CONDITIONS.contains(condition) || !seen.add(condition)) {
                return new Validation.Invalid(RECOVERY_EVIDENCE_INVALID);
            }
        }
        if (!seen.containsAll(RECOVERY_CONDITIONS)) {
            return new Validation.Invalid(RECOVERY_CONDITION_MISSING);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String verification = recovery == null || recovery.verification() == null ? ""
                : recovery.verification().strip();
        String evidenceRef = recovery == null || recovery.evidenceRef() == null ? "" : recovery.evidenceRef().strip();
        String deployVersion = recovery == null || recovery.deployVersion() == null ? ""
                : recovery.deployVersion().strip();
        if (verification.isEmpty() || evidenceRef.isEmpty() || deployVersion.isEmpty()) {
            return new Validation.Invalid(RECOVERY_EVIDENCE_REQUIRED);
        }
        if (!isAcceptableReason(verification) || !isAcceptableReason(evidenceRef)
                || !DEPLOY_VERSION.matcher(deployVersion).matches()) {
            return new Validation.Invalid(RECOVERY_EVIDENCE_INVALID);
        }
        LiftTarget current = this.restrictions.liftFacts(restrictionId)
                .map(facts -> new LiftTarget(facts.feature(), facts.scope(), facts.target(), facts.targetErased(),
                        facts.origin(), facts.open(), facts.linkedSignals(), facts.latestSignalAt()))
                .orElse(null);
        Lift lift = new Lift(restrictionId, current, RECOVERY_CONDITIONS, verification, evidenceRef, deployVersion);
        String target = restrictionId.toString();
        String ledgerScope = current == null ? UNREADABLE : ledgerScope(current.feature(), current.scope());
        String phrase = current == null ? "복구 " + target
                : liftPhrase(current.feature(), current.scope(), current.target(), current.targetErased());
        String digest = sha256(LIFT_RESTRICTION + "\n" + target + "\n" + reason + "\n" + verification + "\n"
                + evidenceRef + "\n" + deployVersion + "\n" + String.join(",", RECOVERY_CONDITIONS));
        return new Validation.Valid(new Accepted(LIFT_RESTRICTION, ledgerScope, target, target, reason, phrase,
                POLICY_VERSION, permissionsOf(LIFT_RESTRICTION), digest, null, lift, null, null));
    }

    /**
     * 공지 발행의 envelope 검증(Story 3.5 AC-1·AC-6). 형식 — 상태·사건·메시지·덮는 제한 ID·다음 안내 시각 — 을 보고, 그다음
     * 같은 모듈의 공지·제한 표에서 지금 사실(사건의 최신 버전, 덮는 제한의 기능과 열림, DB 시각의 다음 안내 창)을 읽어 쓸
     * 버전 번호·영향 기능·확인 문구를 정한다. 상태 거절({@code TARGET_INVALID}·{@code INCIDENT_CLOSED}·
     * {@code NEXT_UPDATE_INVALID})은 {@link Notice#refusal}에 싣고 루트가 멱등 키·재인증 판정 <b>뒤</b>에 적용한다(3.2·3.4와
     * 같은 준비 순서).
     *
     * <p>새 사건은 조사 중으로만 열고, 기존 사건은 범위 변경·복구만 받는다 — 그 밖의 조합은 {@code UNSUPPORTED_SCOPE}다.
     * 복구 공지가 덮는 제한을 비워 보내면 직전 버전의 것을 잇는다(이미 닫힌 제한은 운영자 화면의 활성 목록에 없다).
     */
    private Validation validateNotice(Proposal proposal) {
        NoticeStatus status = noticeStatusOf(proposal.scope());
        if (status == null) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        String incidentText = proposal.target() == null ? "" : proposal.target().strip();
        UUID incidentId = null;
        if (!NEW_INCIDENT.equals(incidentText)) {
            if (!UUID_TEXT.matcher(incidentText).matches()) {
                return new Validation.Invalid(TARGET_INVALID);
            }
            incidentId = UUID.fromString(incidentText);
        }
        if ((incidentId == null) != (status == NoticeStatus.INVESTIGATING)) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        String message = proposal.reason() == null ? "" : proposal.reason().strip();
        if (message.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableMessage(message)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        NoticeInput input = proposal.notice();
        List<String> given = input == null || input.covers() == null ? List.of() : input.covers();
        if (given.size() > ServiceNoticeApi.MAX_COVERS) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        // 글자 순서로 정렬한다 — 저장·요청 digest·화면이 같은 순서를 본다(UUID의 비교 순서는 글자 순서가 아니다).
        Set<UUID> requested = new TreeSet<>(java.util.Comparator.comparing(UUID::toString));
        for (String text : given) {
            String cover = text == null ? "" : text.strip();
            // 모양이 아니거나 같은 제한을 두 번 담았다 — 덮는 범위를 개수로 꾸밀 수 없다.
            if (!UUID_TEXT.matcher(cover).matches() || !requested.add(UUID.fromString(cover))) {
                return new Validation.Invalid(TARGET_INVALID);
            }
        }
        if (requested.isEmpty() && status != NoticeStatus.RECOVERED) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String nextText = input == null || input.nextUpdateAt() == null ? "" : input.nextUpdateAt().strip();
        Instant nextUpdateAt = null;
        if (status == NoticeStatus.RECOVERED) {
            if (!nextText.isEmpty()) {
                return new Validation.Invalid(NEXT_UPDATE_INVALID);
            }
        } else {
            nextUpdateAt = instantOf(nextText);
            if (nextUpdateAt == null) {
                return new Validation.Invalid(NEXT_UPDATE_INVALID);
            }
        }

        // 지금 사실. 거절은 싣기만 하고 루트가 멱등 키·재인증 뒤에 적용한다.
        String refusal = null;
        int version = 1;
        List<UUID> covers = List.copyOf(requested);
        if (incidentId != null) {
            NoticeVersion latest = this.notices.latest(incidentId).orElse(null);
            if (latest == null) {
                refusal = TARGET_INVALID;
            } else {
                version = latest.version() + 1;
                if (latest.status() == NoticeStatus.RECOVERED) {
                    refusal = INCIDENT_CLOSED;
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
            refusal = NEXT_UPDATE_INVALID;
        }
        String target = incidentId == null ? NEW_INCIDENT : incidentId.toString();
        String ledgerScope = NOTICE_SCOPE_PREFIX + status.name();
        // 확인 문구가 사건·범위를 묶는다(item-7): 새 사건은 덮는 제한 수, 기존 사건은 사건 ID 앞 8자와 버전.
        String phrase = incidentId == null ? "공지 발행 새 사건 제한 " + covers.size() + "건"
                : "공지 " + noticeStatusWord(status) + " " + incidentId.toString().substring(0, 8) + " v" + version;
        String digest = sha256(PUBLISH_NOTICE + "\n" + ledgerScope + "\n" + target + "\n" + message + "\n"
                + requested.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","))
                + "\n" + (nextUpdateAt == null ? "-" : nextUpdateAt.toString()));
        Notice notice = new Notice(incidentId, version, status, message, covers, List.copyOf(affected),
                ServiceNoticeApi.continuing(affected), nextUpdateAt, refusal);
        return new Validation.Valid(new Accepted(PUBLISH_NOTICE, ledgerScope, target, target, message, phrase,
                POLICY_VERSION, permissionsOf(PUBLISH_NOTICE), digest, null, null, notice, null));
    }

    /**
     * 문의 처리의 envelope 검증(Story 3.6 AC-5). 형식 — 옮길 상태·reference·사유·답변(필수 여부·길이·민감값) — 을 보고, 그다음
     * 같은 모듈의 문의 표에서 지금 상태를 읽어 전이를 판정한다. 상태 거절({@code TARGET_INVALID}·
     * {@code INQUIRY_TRANSITION_INVALID})은 {@link Inquiry#refusal}에 싣고 루트가 멱등 키·재인증 판정 <b>뒤</b>에 적용한다
     * (3.2·3.4·3.5와 같은 준비 순서).
     *
     * <p>원장의 target은 reference의 keyed digest다 — reference는 사용자 귀속 문의를 가리키는 식별자다. 요청 digest는 답변의
     * SHA-256을 담는다(답변 원문은 원장에 없다). 같은 키로 다른 답변을 보내면 충돌이다.
     */
    private Validation validateInquiry(Proposal proposal) {
        SupportInquiryApi.Status to = inquiryStatusOf(proposal.scope());
        if (to == null) {
            return new Validation.Invalid(UNSUPPORTED_SCOPE);
        }
        String reference = proposal.target() == null ? "" : proposal.target().strip();
        if (!InquiryRules.REFERENCE.matcher(reference).matches()) {
            return new Validation.Invalid(TARGET_INVALID);
        }
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        if (reason.isEmpty()) {
            return new Validation.Invalid(REASON_REQUIRED);
        }
        if (!isAcceptableReason(reason)) {
            return new Validation.Invalid(REASON_INVALID);
        }
        String given = proposal.inquiry() == null ? null : proposal.inquiry().answer();
        String answer = null;
        if (to == SupportInquiryApi.Status.ANSWERED) {
            if (given == null || given.isBlank()) {
                return new Validation.Invalid(INQUIRY_ANSWER_REQUIRED);
            }
            answer = InquiryRules.answer(given);
            if (answer == null) {
                return new Validation.Invalid(INQUIRY_ANSWER_INVALID);
            }
            if (InquiryRules.sensitive(answer).isPresent()) {
                return new Validation.Invalid(INQUIRY_SENSITIVE_CONTENT);
            }
        } else if (given != null && !given.isBlank()) {
            // 답변은 `ANSWERED` 전이에만 싣는다. 다른 전이에 실린 답변을 조용히 버리지 않는다.
            return new Validation.Invalid(INQUIRY_ANSWER_INVALID);
        }
        var current = this.inquiries.current(reference).orElse(null);
        String refusal = null;
        if (current == null) {
            refusal = TARGET_INVALID;
        } else if (!current.status().canMoveTo(to)) {
            refusal = INQUIRY_TRANSITION_INVALID;
        }
        Inquiry inquiry = new Inquiry(reference, current == null ? null : current.status(), to,
                current == null ? null : current.targetAt(), answer, refusal);
        String ledgerScope = INQUIRY_SCOPE_PREFIX + to.name();
        String ledgerTarget = this.digest.ofInquiry(reference);
        String digest = sha256(UPDATE_INQUIRY + "\n" + ledgerScope + "\n" + ledgerTarget + "\n" + reason + "\n"
                + (answer == null ? "-" : sha256("inquiry-answer\n" + answer)));
        return new Validation.Valid(new Accepted(UPDATE_INQUIRY, ledgerScope, reference, ledgerTarget, reason,
                inquiryPhrase(to, reference), POLICY_VERSION, permissionsOf(UPDATE_INQUIRY), digest, null, null, null,
                inquiry));
    }

    /** 운영자가 옮길 수 있는 상태. 접수({@code RECEIVED})는 접수만 만든다. */
    private static SupportInquiryApi.@Nullable Status inquiryStatusOf(@Nullable String text) {
        String status = text == null ? "" : text.strip();
        for (SupportInquiryApi.Status known : SupportInquiryApi.Status.values()) {
            if (known != SupportInquiryApi.Status.RECEIVED && known.name().equals(status)) {
                return known;
            }
        }
        return null;
    }

    /**
     * 문의 처리의 확인 문구: {@code 문의 <상태 낱말> <reference>}. reference를 담아 문구가 그 문의 하나를 가리킨다(item-7).
     */
    static String inquiryPhrase(SupportInquiryApi.Status to, String reference) {
        return "문의 " + inquiryStatusWord(to) + " " + reference;
    }

    /**
     * 문의 상태의 확인 문구 낱말. 사용자 화면의 상태 표지({@code content/support.ts}의 상태 문장)와 글자까지 같은 말이다 —
     * 시험({@code SupportInquiryTest})이 둘을 대조한다.
     */
    static String inquiryStatusWord(SupportInquiryApi.Status status) {
        return switch (status) {
            case RECEIVED -> "접수됨";
            case IN_PROGRESS -> "처리 중";
            case ANSWERED -> "답변 완료";
            case RESOLVED -> "해결됨";
        };
    }

    /**
     * 덮는 제한의 판정과 영향 기능. 모두 있어야 하고, 진행 중인 공지는 지금 열린 제한만 덮는다(복구 공지는 닫힌 제한을
     * 가리킬 수 있다). 거절할 것이 없으면 {@code null}이고 {@code affected}에 그 제한들의 기능을 채운다.
     */
    private @Nullable String coverRefusal(NoticeStatus status, Collection<UUID> covers,
            Set<RestrictionFeature> affected) {
        if (covers.isEmpty()) {
            return TARGET_INVALID;
        }
        var facts = this.restrictions.coverFacts(covers);
        if (facts.size() != covers.size()) {
            return TARGET_INVALID;
        }
        for (var fact : facts) {
            // 진행 중인 공지는 지금 열린 제한만, 복구 공지는 모두 닫힌 제한만 덮는다 — 아직 막고 있는 제한을 "복구됨"으로
            // 알리지 않는다.
            if ((status == NoticeStatus.RECOVERED) == fact.open()) {
                return TARGET_INVALID;
            }
            affected.add(fact.feature());
        }
        return null;
    }

    private static @Nullable NoticeStatus noticeStatusOf(@Nullable String text) {
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

    /** 공지 상태의 확인 문구 낱말. 사용자 화면의 상태 표지와 같은 말이다. */
    static String noticeStatusWord(NoticeStatus status) {
        return switch (status) {
            case INVESTIGATING -> "조사 중";
            case SCOPE_CHANGED -> "범위 변경";
            case RECOVERED -> "복구됨";
        };
    }

    /**
     * 공지 메시지 규칙(Story 3.5 AC-1): 사유 규칙(제어 문자·이메일 모양 거절)에 더해 200자 이내이고, 숫자 6자리 이상
     * 연속과 URL·도메인·경로 모양이 없다 — 금융값·식별자·내부 경로가 사용자 화면에 실리지 않게 한다.
     */
    static boolean isAcceptableMessage(String message) {
        return !message.isEmpty() && message.length() <= ServiceNoticeApi.MAX_MESSAGE_CHARS
                && !CONTROL.matcher(message).find() && !EMAIL_LIKE.matcher(message).find()
                // 금융값 모양(숫자열·끊어 쓴 숫자·금액)은 문의 규칙(Story 3.6)과 같은 한 벌이다.
                && !InquiryRules.financialLike(message) && !URL_LIKE.matcher(message).find()
                && !NETWORK_LIKE.matcher(message).find()
                && FORBIDDEN_COPY_WORDS.stream().noneMatch(message::contains);
    }

    /**
     * 버전 기본키 위반인가 — 같은 사건·같은 번호를 다른 발행이 먼저 썼다. 원인 사슬에서 제약 이름을 찾는다. 다른 무결성
     * 위반(사건 행·복구 유일 색인·CHECK)은 번호 충돌이 아니다.
     */
    static boolean versionConflict(org.springframework.dao.DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("\"" + ServiceNoticeRepository.VERSION_PRIMARY_KEY + "\"")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 해제의 확인 문구(Story 3.4): {@code 복구 <기능> <scope> <target>}. 전역은 {@code 복구 <기능> 전역}이다 — 대상명 {@code 전역}을
     * 다시 입력해야 확정된다. 비식별된 target은 digest 대신 {@code 삭제된 대상}이다.
     */
    static String liftPhrase(RestrictionFeature feature, RestrictionScope scope, String target, boolean erased) {
        String what = featureWord(feature);
        String targetLabel = erased ? "삭제된 대상" : target;
        return switch (scope) {
            case GLOBAL -> "복구 " + what + " 전역";
            case PLAN -> "복구 " + what + " 계획 " + targetLabel;
            case PORTFOLIO -> "복구 " + what + " 포트폴리오 " + targetLabel;
            case PROVIDER_CONNECTION -> "복구 " + what + " 연결 " + targetLabel;
            case CALCULATION_POLICY_VERSION -> "복구 " + what + " 계산 정책 " + targetLabel;
        };
    }

    private static String featureWord(RestrictionFeature feature) {
        return switch (feature) {
            case NEW_CONNECTION -> "신규 연결";
            case SYNC -> "동기화";
            case CALCULATION_DISPLAY -> "계산 표시";
            case ADVERTISING -> "광고";
        };
    }

    /** 원장의 scope: {@code <기능>:<scope>}. */
    static String ledgerScope(RestrictionFeature feature, RestrictionScope scope) {
        return feature.name() + ":" + scope.name();
    }

    private static @Nullable RestrictionScope scopeOf(@Nullable String text) {
        String scope = text == null ? "" : text.strip();
        for (RestrictionScope known : RestrictionScope.values()) {
            if (known.name().equals(scope)) {
                return known;
            }
        }
        return null;
    }

    /**
     * target의 형식. 전역은 {@code ALL} 하나, 계획·portfolio·연결은 정규 UUID(소문자로 정규화), 계산 정책 버전은 버전 모양.
     * 맞지 않으면 {@code null}이다.
     */
    private static @Nullable String restrictionTarget(RestrictionScope scope, @Nullable String text) {
        String target = text == null ? "" : text.strip();
        return switch (scope) {
            case GLOBAL -> RestrictionScope.GLOBAL_TARGET.equals(target) ? target : null;
            case PLAN, PORTFOLIO, PROVIDER_CONNECTION ->
                    UUID_TEXT.matcher(target).matches() ? UUID.fromString(target).toString() : null;
            case CALCULATION_POLICY_VERSION -> POLICY_VERSION_TEXT.matcher(target).matches() ? target : null;
        };
    }

    /**
     * 서버가 정하는 확인 문구. 전역 제한은 대상명 {@code 전역}을 담는다(AC-2) — 다시 입력해야 확정된다. 나머지는 범위 이름과
     * target 원문이다.
     */
    static String restrictionPhrase(RestrictionFeature feature, RestrictionScope scope, String target) {
        String what = featureWord(feature);
        return switch (scope) {
            case GLOBAL -> what + " 제한 전역";
            case PLAN -> what + " 제한 계획 " + target;
            case PORTFOLIO -> what + " 제한 포트폴리오 " + target;
            case PROVIDER_CONNECTION -> what + " 제한 연결 " + target;
            case CALCULATION_POLICY_VERSION -> what + " 제한 계산 정책 " + target;
        };
    }

    @Override
    public String newOperationId() {
        return OperationIds.next();
    }

    @Override
    public String digestOf(String idempotencyKey) {
        return sha256("idempotency-key\n" + idempotencyKey);
    }

    @Override
    public KeyUse keyUse(UUID actorUserId, String idempotencyKeyDigest, String requestDigest) {
        var requested = this.repository.requestedByKey(actorUserId, idempotencyKeyDigest).orElse(null);
        if (requested == null) {
            return new KeyUse.Fresh();
        }
        if (requested.settled() || !requestDigest.equals(requested.requestDigest())) {
            return new KeyUse.Conflict();
        }
        return new KeyUse.Open(requested.operationId(), requested.requestedAt());
    }

    @Override
    public Claim claim(Envelope candidate, @Nullable Lift lift, String correlationId) {
        Claim claim = this.transactions.execute(status -> {
            this.repository.lockAppends();
            return switch (keyUse(candidate.actorUserId(), candidate.idempotencyKeyDigest(),
                    candidate.requestDigest())) {
                case KeyUse.Conflict conflict -> new Claim.Conflict();
                case KeyUse.Open open -> new Claim.Claimed(open.operationId(), open.requestedAt());
                case KeyUse.Fresh fresh ->
                        new Claim.Claimed(candidate.operationId(), recordRequested(candidate, lift, correlationId));
            };
        });
        if (claim == null) {
            throw new IllegalStateException("operation을 정하지 못했다.");
        }
        return claim;
    }

    @Override
    public Instant recordRequested(Envelope envelope, String correlationId) {
        return recordRequested(envelope, null, correlationId);
    }

    private Instant recordRequested(Envelope envelope, @Nullable Lift lift, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("requestDigest", envelope.requestDigest());
        details.put("confirmationHash", envelope.confirmationHash());
        if (lift != null) {
            details.putAll(liftEvidence(lift));
        }
        return append(envelope, AuditOutcome.REQUESTED, false, correlationId, details);
    }

    /**
     * 해제의 증거(Story 3.4 AC-2). 원인은 원장의 사유 열이고 여기에는 나머지가 든다. 금융값·이메일·사용자 원식별자가 없다 —
     * 텍스트는 사유 규칙을 지났고, 제한 ID는 난수 행 ID다.
     */
    private static Map<String, Object> liftEvidence(Lift lift) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("conditions", lift.conditions());
        details.put("verification", lift.verification());
        details.put("evidenceRef", lift.evidenceRef());
        details.put("deployVersion", lift.deployVersion());
        details.put("restrictionId", lift.restrictionId().toString());
        LiftTarget current = lift.current();
        details.put("linkedSignals", current == null ? 0 : current.linkedSignals());
        return details;
    }

    @Override
    public void recordDecision(Envelope envelope, AuditOutcome outcome, String code, String correlationId) {
        if (outcome != AuditOutcome.DENIED && outcome != AuditOutcome.FAILED && outcome != AuditOutcome.CANCELLED) {
            throw new IllegalArgumentException("결정 행은 거절·실패·취소뿐이다.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        append(envelope, outcome, true, correlationId, details);
    }

    @Override
    public String recordRefusedProposal(UUID actorUserId, Proposal proposal, String code, String idempotencyKeyDigest,
            String correlationId) {
        String operationId = newOperationId();
        String scope = proposal.scope() == null ? "" : proposal.scope().strip();
        String target = proposal.target() == null ? "" : proposal.target().strip();
        String reason = proposal.reason() == null ? "" : proposal.reason().strip();
        String ledgerScope = SAFE_SCOPE.matcher(scope).matches() ? scope : UNREADABLE;
        String ledgerTarget = SAFE_TARGET.matcher(target).matches() ? target : UNREADABLE;
        RestrictionFeature feature = OperatorCommandApi.restrictionFeatureOf(proposal.command());
        if (feature != null) {
            /*
             * 제한 command의 거절 행: scope는 아는 이름일 때만 `<기능>:<scope>`이고, target은 그 scope의 모양일 때만 남긴다 —
             * 사용자 귀속 자리(계획·portfolio·연결)는 digest로, 전역·계산 정책 버전은 원문으로. 모르는 scope이거나 모양이
             * 아니면 남기지 않는다 — 거절된 요청을 타고 원식별자가 원장에 들어가는 길을 막는다.
             */
            RestrictionScope known = scopeOf(scope);
            ledgerScope = known == null ? UNREADABLE : ledgerScope(feature, known);
            String normalized = known == null ? null : restrictionTarget(known, target);
            if (normalized == null) {
                // 모르는 scope이거나 그 scope의 모양이 아니다. 무엇인지 알 수 없는 값은 남기지 않는다.
                ledgerTarget = UNREADABLE;
            } else {
                ledgerTarget = known.userAttributed() ? this.digest.ofTarget(known.name(), normalized) : normalized;
            }
        }
        boolean keepReason = isAcceptableReason(reason);
        if (PUBLISH_NOTICE.equals(proposal.command())) {
            /*
             * 공지 발행의 거절 행(Story 3.5): scope는 아는 상태일 때만 `NOTICE:<상태>`, target은 `NEW`이거나 사건 ID 모양일
             * 때만, 메시지는 공지 메시지 규칙을 지날 때만 남긴다 — 숫자열·URL 때문에 거절된 메시지가 거절 행을 타고 원장에
             * 들어가는 길을 막는다.
             */
            NoticeStatus status = noticeStatusOf(scope);
            ledgerScope = status == null ? UNREADABLE : NOTICE_SCOPE_PREFIX + status.name();
            ledgerTarget = NEW_INCIDENT.equals(target) || UUID_TEXT.matcher(target).matches() ? target : UNREADABLE;
            keepReason = isAcceptableMessage(reason);
        }
        if (UPDATE_INQUIRY.equals(proposal.command())) {
            /*
             * 문의 처리의 거절 행(Story 3.6): scope는 옮길 수 있는 상태일 때만 `INQUIRY:<상태>`, target은 reference 모양일
             * 때만 그 keyed digest로 남긴다 — reference 원문과 답변은 거절 행에도 들어가지 않는다.
             */
            SupportInquiryApi.Status status = inquiryStatusOf(scope);
            ledgerScope = status == null ? UNREADABLE : INQUIRY_SCOPE_PREFIX + status.name();
            ledgerTarget = InquiryRules.REFERENCE.matcher(target).matches() ? this.digest.ofInquiry(target)
                    : UNREADABLE;
        }
        var envelope = new Envelope(operationId, actorUserId, proposal.command(), ledgerScope, ledgerTarget,
                keepReason ? reason : UNREADABLE, "", POLICY_VERSION, null, idempotencyKeyDigest, "");
        recordDecision(envelope, AuditOutcome.DENIED, code, correlationId);
        return operationId;
    }

    /** 사유 규칙: 비어 있지 않고, 길이 안이고, 제어 문자와 이메일처럼 보이는 조각이 없다. */
    private static boolean isAcceptableReason(String reason) {
        return !reason.isEmpty() && reason.length() <= MAX_REASON_CHARS && !CONTROL.matcher(reason).find()
                && !EMAIL_LIKE.matcher(reason).find();
    }

    @Override
    public Optional<ExportResult> completeExport(Envelope envelope, String correlationId,
            BooleanSupplier finalAuthority) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 감사 행과 허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("추출 완료는 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        AuditPeriod period = AuditPeriod.parseTarget(envelope.target());
        if (period == null) {
            throw new IllegalStateException("검증을 지난 추출 대상이 아니다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return Optional.empty();
        }
        long upper = this.repository.maxSequence();
        int counted = this.repository.countWithin(period.start(), period.end(), upper, MAX_EXPORT_ROWS + 1);
        boolean truncated = counted > MAX_EXPORT_ROWS;
        int rowCount = Math.min(counted, MAX_EXPORT_ROWS);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("upperSequence", upper);
        details.put("rowCount", rowCount);
        details.put("truncated", truncated);
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return Optional.of(new ExportResult(envelope.operationId(), envelope.target(), upper, rowCount, truncated));
    }

    @Override
    public RestrictionCompletion completeRestriction(Envelope envelope, Restriction restriction, String correlationId,
            BooleanSupplier finalAuthority, Supplier<TargetCheck> targetCheck) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 제한 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("제한 생성은 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return new RestrictionCompletion.Refused(ACCESS_REVOKED);
        }
        // 확정 순간의 대상. 준비 뒤 계획이 끝났거나 연결이 해제됐으면 제한을 만들지 않는다.
        if (!(targetCheck.get() instanceof TargetCheck.Valid valid)) {
            return new RestrictionCompletion.Refused(TARGET_INVALID);
        }
        if (this.restrictions.isActive(restriction.feature(), restriction.scope(), restriction.target())) {
            return new RestrictionCompletion.Refused(ALREADY_RESTRICTED);
        }
        UUID restrictionId = UUID.randomUUID();
        Instant effectiveFrom = this.restrictions.insert(restrictionId, restriction.feature(), restriction.scope(),
                restriction.target(), restriction.scope().userAttributed() ? valid.ownerUserId() : null,
                envelope.operationId(), RestrictionOrigin.OPERATOR);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("restrictionId", restrictionId.toString());
        details.put("feature", restriction.feature().name());
        details.put("restrictionScope", restriction.scope().name());
        details.put("effectiveFrom", effectiveFrom.toString());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new RestrictionCompletion.Completed(new RestrictionResult(envelope.operationId(), restrictionId,
                restriction.feature(), restriction.scope(), effectiveFrom));
    }

    @Override
    public NoticeCompletion completeNotice(Envelope envelope, Notice notice, String supportContact,
            String correlationId, BooleanSupplier finalAuthority) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 공지 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("공지 발행은 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return new NoticeCompletion.Refused(ACCESS_REVOKED);
        }
        UUID incidentId = notice.incidentId();
        if (incidentId != null) {
            // 같은 잠금 아래라 그 사이 다른 발행이 끼어들 수 없다. 준비가 본 최신 버전 뒤에 끼었으면 충돌이다.
            NoticeVersion latest = this.notices.latest(incidentId).orElse(null);
            if (latest == null) {
                return new NoticeCompletion.Refused(TARGET_INVALID);
            }
            if (latest.status() == NoticeStatus.RECOVERED) {
                return new NoticeCompletion.Refused(INCIDENT_CLOSED);
            }
            if (latest.version() != notice.version() - 1) {
                return new NoticeCompletion.Refused(NOTICE_VERSION_CONFLICT);
            }
        }
        // 확정 순간의 덮는 제한. 준비 뒤 해제된 제한을 진행 중인 공지가 덮지 않는다.
        Set<RestrictionFeature> affected = EnumSet.noneOf(RestrictionFeature.class);
        String refusal = coverRefusal(notice.status(), notice.covers(), affected);
        if (refusal != null) {
            return new NoticeCompletion.Refused(refusal);
        }
        Instant nextUpdateAt = notice.nextUpdateAt();
        if (nextUpdateAt != null
                && !this.notices.withinNextUpdateWindow(nextUpdateAt, ServiceNoticeApi.MAX_NEXT_UPDATE.toSeconds())) {
            return new NoticeCompletion.Refused(NEXT_UPDATE_INVALID);
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
                    envelope.operationId(), supportContact);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (!versionConflict(duplicate)) {
                throw duplicate;
            }
            // 같은 번호를 기본키가 거절했다. 이 트랜잭션은 더 쓰지 않고 호출자가 되돌린다.
            return new NoticeCompletion.Refused(NOTICE_VERSION_CONFLICT);
        }
        // 메시지 원문은 원장의 사유 열에만 있다. 부가 사실은 사건·버전·상태·덮는 제한 수다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("incidentId", incidentId.toString());
        details.put("version", notice.version());
        details.put("status", notice.status().name());
        details.put("covers", notice.covers().size());
        details.put("publishedAt", written.createdAt().toString());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new NoticeCompletion.Completed(new NoticeResult(envelope.operationId(), incidentId, notice.version(),
                notice.status(), written.createdAt()));
    }

    @Override
    public InquiryCompletion completeInquiry(Envelope envelope, Inquiry inquiry, String correlationId,
            BooleanSupplier finalAuthority, java.util.function.Predicate<UUID> ownerUsable) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 문의 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("문의 처리는 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return new InquiryCompletion.Refused(ACCESS_REVOKED);
        }
        // 확정 순간의 문의. 준비 뒤 보존 삭제·계정 삭제로 사라졌거나 다른 처리가 상태를 옮겼으면 쓰지 않는다.
        var locked = this.inquiries.lockForTransition(inquiry.reference()).orElse(null);
        if (locked == null) {
            return new InquiryCompletion.Refused(TARGET_INVALID);
        }
        // 주인 계정의 삭제가 시작됐으면 그 문의는 곧 지워질 대상이다. 답변을 쓰지 않는다(없는 문의와 같은 거절).
        if (!ownerUsable.test(locked.ownerUserId())) {
            return new InquiryCompletion.Refused(TARGET_INVALID);
        }
        if (!locked.status().canMoveTo(inquiry.to())) {
            return new InquiryCompletion.Refused(INQUIRY_TRANSITION_INVALID);
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
            return new InquiryCompletion.Refused(INQUIRY_TRANSITION_INVALID);
        }
        if (inquiry.to() == SupportInquiryApi.Status.ANSWERED) {
            // 상태는 단조 전진이라 ANSWERED 전이는 문의마다 한 번 — 곧 첫 답변이다. 이 트랜잭션이 커밋되면 센다.
            this.supportMetrics.firstAnswered(transitionedAt, targetAt);
        }
        // 상태·reference digest·전이 시각뿐이다. 제목·본문·이메일·답변 원문은 넣지 않는다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("status", inquiry.to().name());
        details.put("referenceDigest", envelope.target());
        details.put("transitionedAt", transitionedAt.toString());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new InquiryCompletion.Completed(new InquiryResult(envelope.operationId(), inquiry.to(),
                transitionedAt));
    }

    @Override
    public LiftCompletion completeLift(Envelope envelope, Lift lift, String correlationId,
            BooleanSupplier finalAuthority, Supplier<LiftExpectation> expectation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 제한 행·감사 행·허가 소비가 한 트랜잭션이어야 한다. 밖에서 부르는 것은 배선 실수다.
            throw new IllegalStateException("해제는 허가 소비의 트랜잭션 안에서만 쓴다.");
        }
        LiftTarget prepared = lift.current();
        if (prepared == null) {
            // 준비가 없는 제한을 무장하지 않는다. 여기 오는 것은 배선 실수다.
            throw new IllegalStateException("준비가 본 해제 대상이 없다.");
        }
        this.repository.lockAppends();
        // 잠금 뒤의 최종 권한. 거짓이면 이 트랜잭션은 아무것도 쓰지 않았다.
        if (!finalAuthority.getAsBoolean()) {
            return new LiftCompletion.Refused(ACCESS_REVOKED);
        }
        var locked = this.restrictions.lockForLift(lift.restrictionId()).orElse(null);
        if (locked == null) {
            return new LiftCompletion.Refused(TARGET_INVALID);
        }
        if (!locked.open()) {
            return new LiftCompletion.Refused(RESTRICTION_NOT_ACTIVE);
        }
        // 준비 뒤 같은 제한에 새 신호가 연결됐으면 운영자가 본 증거가 그 신호를 다루지 않았다. 다시 검증해야 한다.
        if (locked.linkedSignals() != prepared.linkedSignals()
                || !java.util.Objects.equals(locked.latestSignalAt(), prepared.latestSignalAt())) {
            return new LiftCompletion.Refused(NEW_SIGNAL_SINCE_PREPARE);
        }
        Instant liftedAt = this.restrictions.lift(lift.restrictionId(), envelope.operationId()).orElse(null);
        if (liftedAt == null) {
            /*
             * 열림 판정과 UPDATE가 같은 술어(`OPEN`)이고 그 사이 행 잠금을 쥐고 있어 구조상 오지 않는 가지다. 그래도 영향 행
             * 수가 곧 판정이다 — 0행을 성공으로 접지 않는다.
             */
            return new LiftCompletion.Refused(RESTRICTION_NOT_ACTIVE);
        }
        LiftExpectation expected = expectation.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.putAll(liftEvidence(lift));
        details.put("feature", locked.feature().name());
        details.put("restrictionScope", locked.scope().name());
        details.put("liftedAt", liftedAt.toString());
        details.put("reopenedStages", expected.reopenedStages());
        details.put("stillBlockedStages", expected.stillBlockedStages());
        append(envelope, AuditOutcome.COMPLETED, true, correlationId, details);
        return new LiftCompletion.Completed(new LiftResult(envelope.operationId(), lift.restrictionId(),
                locked.feature(), locked.scope(), liftedAt, expected.reopenedStages(), expected.stillBlockedStages()));
    }

    @Override
    public Optional<UUID> revertLift(Envelope envelope, LiftResult result, String correlationId) {
        Optional<UUID> reinstated = this.transactions.execute(status -> {
            this.repository.lockAppends();
            UUID created = null;
            var lifted = this.restrictions.liftFacts(result.restrictionId()).orElse(null);
            // 같은 대상에 이미 끝이 정해지지 않은 제한이 있으면(그 사이 신호·운영자가 만든 것) 이미 막혀 있다.
            if (lifted != null && this.restrictions.activeRow(lifted.feature(), lifted.scope(), lifted.target())
                    .isEmpty()) {
                UUID candidate = UUID.randomUUID();
                if (this.restrictions.reinstate(candidate, result.restrictionId(), envelope.operationId())
                        .isPresent()) {
                    created = candidate;
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", SMOKE_FAILED);
            details.put("keyDigest", envelope.idempotencyKeyDigest());
            details.put("restrictionId", result.restrictionId().toString());
            if (created != null) {
                details.put("reinstatedRestrictionId", created.toString());
            }
            // 새 제한 행과 실패 행이 한 커밋이다. 잠금은 이미 쥐었다(append가 다시 쥐어도 같은 트랜잭션이다).
            append(envelope, AuditOutcome.FAILED, true, correlationId, details);
            return Optional.ofNullable(created);
        });
        return reinstated == null ? Optional.empty() : reinstated;
    }

    @Override
    public void recordSmokePassed(Envelope envelope, LiftResult result, String correlationId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", SMOKE_PASSED);
        details.put("keyDigest", envelope.idempotencyKeyDigest());
        details.put("restrictionId", result.restrictionId().toString());
        append(envelope, AuditOutcome.APPROVED, true, correlationId, details);
    }

    @Override
    public LiftSmoke liftSmoke(String operationId) {
        String code = this.repository.firstCode(operationId, List.of(SMOKE_PASSED, SMOKE_FAILED)).orElse(null);
        if (SMOKE_PASSED.equals(code)) {
            return LiftSmoke.PASSED;
        }
        return SMOKE_FAILED.equals(code) ? LiftSmoke.REVERTED : LiftSmoke.PENDING;
    }

    @Override
    public Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest) {
        var completed = this.repository.completed(actorUserId, operationId).orElse(null);
        if (completed == null) {
            return new Replay.None();
        }
        if (!idempotencyKeyDigest.equals(completed.keyDigest())) {
            return new Replay.Conflict();
        }
        if (UPDATE_INQUIRY.equals(completed.command()) && completed.status() != null
                && completed.transitionedAt() != null) {
            return new Replay.Stored(new InquiryResult(operationId,
                    SupportInquiryApi.Status.valueOf(completed.status()), completed.transitionedAt()));
        }
        if (PUBLISH_NOTICE.equals(completed.command()) && completed.incidentId() != null
                && completed.noticeVersion() != null && completed.status() != null
                && completed.publishedAt() != null) {
            return new Replay.Stored(new NoticeResult(operationId, completed.incidentId(), completed.noticeVersion(),
                    NoticeStatus.valueOf(completed.status()), completed.publishedAt()));
        }
        if (LIFT_RESTRICTION.equals(completed.command()) && completed.restrictionId() != null
                && completed.feature() != null && completed.restrictionScope() != null
                && completed.liftedAt() != null) {
            return new Replay.Stored(new LiftResult(operationId, completed.restrictionId(),
                    RestrictionFeature.valueOf(completed.feature()),
                    RestrictionScope.valueOf(completed.restrictionScope()), completed.liftedAt(),
                    stagesOf(completed.reopenedStages()), stagesOf(completed.stillBlockedStages())));
        }
        if (completed.restrictionId() != null && completed.feature() != null && completed.restrictionScope() != null
                && completed.effectiveFrom() != null) {
            return new Replay.Stored(new RestrictionResult(operationId, completed.restrictionId(),
                    RestrictionFeature.valueOf(completed.feature()),
                    RestrictionScope.valueOf(completed.restrictionScope()), completed.effectiveFrom()));
        }
        return new Replay.Stored(new ExportResult(operationId, completed.target(), completed.upperSequence(),
                completed.rowCount(), completed.truncated()));
    }

    /** 원장 {@code details}의 단계 배열(JSON 원문). 읽을 수 없으면 빈 목록이다 — 재생은 사실을 지어내지 않는다. */
    private static List<String> stagesOf(@Nullable String json) {
        if (json == null) {
            return List.of();
        }
        try {
            var node = JSON.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> stages = new java.util.ArrayList<>();
            for (var item : node) {
                if (item.isString()) {
                    stages.add(item.asString());
                }
            }
            return List.copyOf(stages);
        } catch (tools.jackson.core.JacksonException unreadable) {
            return List.of();
        }
    }

    @Override
    public List<OperatorAuditApi.Entry> exportRows(ExportResult result) {
        AuditPeriod period = AuditPeriod.parseTarget(result.target());
        if (period == null) {
            throw new IllegalStateException("저장된 추출 대상을 읽을 수 없다.");
        }
        return this.repository.within(period.start(), period.end(), result.upperSequence(), MAX_EXPORT_ROWS);
    }

    /** 잠금 → INSERT를 한 트랜잭션에서. 열린 트랜잭션이 있으면 거기 참여한다. */
    private Instant append(Envelope envelope, AuditOutcome outcome, boolean decided, String correlationId,
            Map<String, Object> details) {
        var row = new OperatorAuditRepository.NewRow(envelope.operationId(), envelope.actorUserId(),
                this.digest.of(envelope.actorUserId()), ACTOR_OPERATOR, envelope.command(), envelope.scope(),
                envelope.target(), envelope.reason(), outcome.name(), envelope.policyVersion(),
                envelope.requestedAt(), decided, correlationId, JSON.writeValueAsString(details));
        Instant requestedAt = this.transactions.execute(status -> {
            this.repository.lockAppends();
            return this.repository.append(row);
        });
        if (requestedAt == null) {
            throw new IllegalStateException("감사 행을 쓰지 못했다.");
        }
        return requestedAt;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", impossible);
        }
    }
}
