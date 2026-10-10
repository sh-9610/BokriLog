package com.bokrilog.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * 보호된 운영 command의 표준 envelope와 그 원장 기록(Story 3.1 AC-3·AC-4·AC-6).
 *
 * <p><b>이 모듈은 HTTP·세션·허가를 모른다.</b> 재인증·일회성 허가·세션 무장·회전은 루트({@code OperatorCommandService})가
 * Epic 1의 부품으로 하고, 여기서는 envelope의 검증(빈 사유·미지원 command·미지원 scope·클라이언트 actor·길이), 서버가
 * 정하는 확인 문구, 감사 행의 추가, 그리고 command의 실행을 한다 — 3.1의 {@value #AUDIT_EXPORT}, 3.2의 제한 생성 넷
 * ({@code restrict-<기능>}, {@link #restrictionFeatureOf}), 3.4의 제한 해제 {@value #LIFT_RESTRICTION}, 3.5의 서비스 공지
 * 발행 {@value #PUBLISH_NOTICE}.
 *
 * <p><b>감사 없는 상태 변경이 없다.</b> 실행({@link #completeExport})은 호출자의 트랜잭션 안에서 결과 행을 쓰고 그
 * 트랜잭션이 허가 소비와 같다 — 하나가 실패하면 모두 롤백된다. 거절·실패·취소 행({@link #recordDecision})은 그 롤백
 * <b>뒤</b> 자기 짧은 트랜잭션으로 쓴다.
 *
 * <p>시각의 권위는 PostgreSQL이다. 요청·결정 시각은 원장 INSERT의 {@code statement_timestamp()}이고 애플리케이션 시계를
 * 쓰지 않는다.
 */
public interface OperatorCommandApi {

    /** 감사 원장 대량 추출. 3.1의 유일한 command이며 그 자체가 별도 감사 사건이다(AC-5). */
    String AUDIT_EXPORT = "audit-export";

    /** {@value #AUDIT_EXPORT}가 받는 유일한 scope. */
    String SCOPE_AUDIT_LEDGER = "AUDIT_LEDGER";

    /** 신규 연결 제한(Story 3.2). */
    String RESTRICT_NEW_CONNECTION = "restrict-new-connection";

    /** 동기화 제한(Story 3.2). */
    String RESTRICT_SYNC = "restrict-sync";

    /** 계산 표시 제한(Story 3.2). */
    String RESTRICT_CALCULATION_DISPLAY = "restrict-calculation-display";

    /** 광고 제한(Story 3.2). 소비자는 Epic 4이고 지금은 제한 행과 감사만 생긴다. */
    String RESTRICT_ADVERTISING = "restrict-advertising";

    /**
     * 제한 해제(Story 3.4). target은 제한 ID 하나다. 제한이 닫히는 운영 경로는 이 command 하나뿐이다 — 시간·신호·재시작으로
     * 닫히지 않는다.
     */
    String LIFT_RESTRICTION = "lift-restriction";

    /**
     * 복구 조건 네 code({@code OPS-SIGNAL-1.0} §6.2): 원인 제거, 영향 범위 재현 시험, 고정 회귀·소유권·pointer 검사 통과, 새
     * 배포·버전 증거. 해제 준비는 넷을 모두 확인해야 한다. 원장에는 이 순서로 남는다.
     */
    List<String> RECOVERY_CONDITIONS = List.of("CAUSE_REMOVED", "IMPACT_REPRODUCED",
            "REGRESSION_OWNERSHIP_POINTER_PASSED", "NEW_DEPLOY_EVIDENCE");

    /**
     * 서비스 공지 발행(Story 3.5). target은 새 사건이면 {@value #NEW_INCIDENT}, 기존 사건이면 그 사건 ID이고 원장 scope는
     * {@code NOTICE:<상태>}다. 사유 자리가 운영자 한 줄 메시지다 — 공지의 메시지 규칙(200자·이메일·숫자 6자리 이상 연속·
     * URL 모양 거절)을 지난 값만 원장에 남는다. 공지 행은 이 command만 쓴다.
     */
    String PUBLISH_NOTICE = "publish-notice";

    /** 새 사건을 여는 공지 발행의 target. */
    String NEW_INCIDENT = "NEW";

    /** 공지 발행의 원장 scope 접두사. 뒤에 상태가 붙는다({@code NOTICE:INVESTIGATING}). */
    String NOTICE_SCOPE_PREFIX = "NOTICE:";

    /** 배포 버전의 최대 길이(문자). 모양은 {@code [A-Za-z0-9._+-]}다. */
    int MAX_DEPLOY_VERSION_CHARS = 64;

    /** 제한 생성 command가 막는 기능. 제한 command가 아니면 {@code null}이다. */
    static @Nullable RestrictionFeature restrictionFeatureOf(String command) {
        return switch (command) {
            case RESTRICT_NEW_CONNECTION -> RestrictionFeature.NEW_CONNECTION;
            case RESTRICT_SYNC -> RestrictionFeature.SYNC;
            case RESTRICT_CALCULATION_DISPLAY -> RestrictionFeature.CALCULATION_DISPLAY;
            case RESTRICT_ADVERTISING -> RestrictionFeature.ADVERTISING;
            default -> null;
        };
    }

    /**
     * envelope·확인 문구·실행 규칙의 정책 버전. 규칙을 바꾸면 이 값도 바꾼다. 1.1(Story 3.2): 제한 command 넷, 원장 scope
     * {@code <기능>:<scope>}, 사용자 귀속 target의 digest, 대상 검사를 멱등 키·재인증 판정 뒤에 하는 준비 순서. 1.2(Story
     * 3.4): 해제 command {@value #LIFT_RESTRICTION}, 복구 조건 네 확인과 원인·검증·증거 참조·배포 버전, 확정 순간의 열림·신호
     * 계보 재검사, 커밋 뒤 evaluator smoke와 그 실패의 되돌림. 1.3(Story 3.5): 공지 발행 command {@value #PUBLISH_NOTICE},
     * 메시지 규칙(200자, 구분자로 이은 숫자 6자리 이상·IP·포트·통화 금액·URL·경로·이메일·화면 금지 표현 거절), 지원
     * 연락처가 비면 거절(멱등 키·재인증 판정 뒤), 복구 공지는 덮는 제한이 모두 닫혀 있어야 함, 사건·범위를 담은 확인 문구,
     * 확정 순간의 버전 연속·사건 닫힘·덮는 제한·다음 안내 시각 재검사와 지원 연락처의 버전 고정.
     */
    String POLICY_VERSION = "OPERATOR-COMMAND-1.3";

    /** 추출 한 번의 행 상한. 넘으면 앞쪽(오래된 순) 상한만큼이고 결과가 잘렸다고 말한다. */
    int MAX_EXPORT_ROWS = 10_000;

    /** 추출 기간의 최대 일수(양 끝 포함). */
    int MAX_EXPORT_DAYS = 366;

    /** 사유의 최대 길이(문자). */
    int MAX_REASON_CHARS = 500;

    // ---------------------------------------------------------------- 거절 code(안정 machine code)

    /** 사유가 없거나 공백뿐이다. */
    String REASON_REQUIRED = "REASON_REQUIRED";

    /** 사유가 너무 길거나 이메일 주소처럼 보인다 — 감사 원장에 이메일을 넣지 않는다. */
    String REASON_INVALID = "REASON_INVALID";

    /** 이 command가 받지 않는 scope다. */
    String UNSUPPORTED_SCOPE = "UNSUPPORTED_SCOPE";

    /** 대상이 이 command의 형식이 아니거나, 없거나, 상태가 맞지 않다. */
    String TARGET_INVALID = "TARGET_INVALID";

    /** 같은 기능·scope·target의 활성 제한이 이미 있다(Story 3.2). */
    String ALREADY_RESTRICTED = "ALREADY_RESTRICTED";

    /** 해제 대상 제한이 지금 효력 중이 아니다 — 이미 닫혔거나 아직 시작 전이다(Story 3.4). */
    String RESTRICTION_NOT_ACTIVE = "RESTRICTION_NOT_ACTIVE";

    /** 복구 조건 네 가지를 모두 확인하지 않았다(Story 3.4). */
    String RECOVERY_CONDITION_MISSING = "RECOVERY_CONDITION_MISSING";

    /** 수행한 검증·증거 참조·배포 버전 가운데 비어 있는 것이 있다(Story 3.4). 원인이 비면 {@link #REASON_REQUIRED}다. */
    String RECOVERY_EVIDENCE_REQUIRED = "RECOVERY_EVIDENCE_REQUIRED";

    /**
     * 수행한 검증·증거 참조가 사유 규칙(길이·제어 문자·이메일 모양)을 어겼거나, 배포 버전이 모양에 맞지 않거나, 모르는·겹친
     * 복구 조건 code가 있다(Story 3.4).
     */
    String RECOVERY_EVIDENCE_INVALID = "RECOVERY_EVIDENCE_INVALID";

    /** 준비 뒤 그 제한에 새 강한 신호가 연결됐다(Story 3.4). 다시 검증하고 새로 준비해야 한다. */
    String NEW_SIGNAL_SINCE_PREPARE = "NEW_SIGNAL_SINCE_PREPARE";

    /**
     * 원장 code: 해제 커밋 뒤 evaluator smoke가 기대와 어긋나 같은 범위를 새 제한으로 되돌렸다(Story 3.4). 그 해제 operation의
     * {@code FAILED} 행이다.
     */
    String SMOKE_FAILED = "SMOKE_FAILED";

    /** 원장 code: 해제 커밋 뒤 evaluator smoke가 통과했다(Story 3.4). 그 해제 operation의 {@code APPROVED} 행이다. */
    String SMOKE_PASSED = "SMOKE_PASSED";

    /** 지원 연락처 설정이 비어 있어 공지를 발행하지 않았다(Story 3.5). 허위·빈 지원 경로를 만들지 않는다. */
    String SUPPORT_CONTACT_UNCONFIGURED = "SUPPORT_CONTACT_UNCONFIGURED";

    /** 같은 사건의 다음 번호가 이미 발행됐다 — 준비가 본 최신 버전 뒤에 다른 버전이 끼었다(Story 3.5). */
    String NOTICE_VERSION_CONFLICT = "NOTICE_VERSION_CONFLICT";

    /** 복구로 닫힌 사건이라 새 버전을 받지 않는다(Story 3.5). */
    String INCIDENT_CLOSED = "INCIDENT_CLOSED";

    /**
     * 다음 안내 시각이 없거나, 읽을 수 없거나, 지금보다 미래·7일 이내가 아니거나, 복구 공지에 실렸다(Story 3.5). 판정은 DB
     * 시각이다.
     */
    String NEXT_UPDATE_INVALID = "NEXT_UPDATE_INVALID";

    /** 요청 본문이 수행자를 주장했다. 수행자는 언제나 서버 세션이 정한다. */
    String ACTOR_NOT_ACCEPTED = "ACTOR_NOT_ACCEPTED";

    /**
     * 원장 code: 권한 회수로 거절했다. 이 code로 맺은 operation은 끝난 것이다 — 같은 키의 재준비는 충돌이다
     * ({@link #keyUse}).
     */
    String ACCESS_REVOKED = "ACCESS_REVOKED";

    /**
     * 클라이언트가 보낸 command 제안. 검증 전 원문이다.
     *
     * @param recovery 해제 command의 증거 입력(Story 3.4). 그 밖은 {@code null}
     * @param notice 공지 발행 command의 입력(Story 3.5). 그 밖은 {@code null}. 상태는 {@code scope}, 사건은
     *     {@code target}, 메시지는 {@code reason} 자리다
     */
    record Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
            boolean actorClaimed, @Nullable RecoveryInput recovery, @Nullable NoticeInput notice) {

        public Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
                boolean actorClaimed) {
            this(command, scope, target, reason, actorClaimed, null, null);
        }

        public Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
                boolean actorClaimed, @Nullable RecoveryInput recovery) {
            this(command, scope, target, reason, actorClaimed, recovery, null);
        }
    }

    /**
     * 공지 발행의 입력 원문(Story 3.5). 상태·사건·메시지는 {@link Proposal}의 scope·target·reason 자리다.
     *
     * @param covers 덮을 제한 ID. 본문이 배열이 아니면 {@code null}
     * @param nextUpdateAt 다음 안내 시각(ISO-8601, 오프셋 포함). 없으면 {@code null}
     */
    record NoticeInput(@Nullable List<String> covers, @Nullable String nextUpdateAt) {}

    /**
     * 검증을 지난 공지 발행(Story 3.5). 세션 무장에 실린다. 영향·계속 가능 기능은 덮는 제한의 기능에서 서버가 계산했다.
     *
     * @param incidentId 기존 사건. 새 사건이면 {@code null}이고 사건 ID는 확정이 만든다
     * @param version 쓸 버전 번호. 준비가 본 최신 버전의 다음 번호다
     * @param covers 덮을 제한 ID(정렬, 중복 없음)
     * @param nextUpdateAt 진행 중인 공지의 다음 안내 시각. 복구 공지는 {@code null}
     * @param refusal 준비 시각에 본 상태 거절({@link #TARGET_INVALID}·{@link #INCIDENT_CLOSED}·
     *     {@link #NEXT_UPDATE_INVALID}). 루트가 멱등 키·재인증 판정 뒤에 적용한다. 무장되는 값은 언제나 {@code null}이다
     */
    record Notice(@Nullable UUID incidentId, int version, ServiceNoticeApi.NoticeStatus status, String message,
            List<UUID> covers, List<RestrictionFeature> affectedFeatures, List<RestrictionFeature> continuingFeatures,
            @Nullable Instant nextUpdateAt, @Nullable String refusal) implements java.io.Serializable {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public Notice {
            covers = List.copyOf(covers);
            affectedFeatures = List.copyOf(affectedFeatures);
            continuingFeatures = List.copyOf(continuingFeatures);
        }
    }

    /**
     * 해제 command의 증거 입력 원문(Story 3.4). 사유({@link Proposal#reason})가 원인이다.
     *
     * @param conditions 확인한 복구 조건 code. 본문이 배열이 아니면 {@code null}
     */
    record RecoveryInput(@Nullable List<String> conditions, @Nullable String verification,
            @Nullable String evidenceRef, @Nullable String deployVersion) {}

    /**
     * 해제 대상 제한의 준비 시각 사실(Story 3.4). 제한 행의 기능·scope·target은 바뀌지 않으므로(비식별만 있다) 확정이
     * 다시 읽지 않고 이 값을 쓴다. 연결 신호 수·최신 감지 시각은 확정 순간의 계보와 비교한다.
     *
     * @param target 원 target. 비식별됐으면 digest이고 {@code targetErased}가 참이다
     * @param open 지금 효력 중이고 끝이 정해지지 않았다
     */
    record LiftTarget(RestrictionFeature feature, RestrictionScope scope, String target, boolean targetErased,
            RestrictionOrigin origin, boolean open, int linkedSignals, @Nullable Instant latestSignalAt)
            implements java.io.Serializable {

        @java.io.Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 검증을 지난 해제 요청(Story 3.4). 세션 무장에 실린다.
     *
     * @param current 준비 시각의 제한. 그 ID의 제한이 없으면 {@code null}
     * @param conditions {@link #RECOVERY_CONDITIONS} 순서의 확인한 조건
     */
    record Lift(UUID restrictionId, @Nullable LiftTarget current, List<String> conditions, String verification,
            String evidenceRef, String deployVersion) implements java.io.Serializable {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public Lift {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 검증을 지난 제한 요청(Story 3.2). 대상의 존재·상태는 아직 보지 않았다 — 그것은 다른 모듈의 사실이라 루트가 각 모듈
     * 공개 API로 본다.
     *
     * <p>세션 무장에 실리므로 직렬화할 수 있다.
     *
     * @param target 정규화한 원 target. 전역이면 {@link RestrictionScope#GLOBAL_TARGET}이다
     */
    record Restriction(RestrictionFeature feature, RestrictionScope scope, String target)
            implements java.io.Serializable {

        @java.io.Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 검증을 지난 제안.
     *
     * @param scope 원장의 scope. 제한 command는 {@code <기능>:<scope>}다
     * @param target 정규화한 원 대상. 화면에 되돌려 주고 실행에 쓴다
     * @param ledgerTarget 원장·허가의 target. 사용자에게 귀속된 제한 target은 keyed digest이고 그 밖은 {@code target}과 같다
     * @param confirmationPhrase 서버가 정한 확인 문구. 사용자가 다시 입력해 확정에 실어 보낸다
     * @param requestDigest {@code command|scope|ledgerTarget|reason}의 SHA-256. 같은 멱등 키의 다른 입력을 가른다
     * @param restriction 제한 command의 요청. 그 밖은 {@code null}
     * @param lift 해제 command의 요청(Story 3.4). 그 밖은 {@code null}
     * @param notice 공지 발행 command의 요청(Story 3.5). 그 밖은 {@code null}
     */
    record Accepted(String command, String scope, String target, String ledgerTarget, String reason,
            String confirmationPhrase, String policyVersion, Set<OperatorPermission> permissions, String requestDigest,
            @Nullable Restriction restriction, @Nullable Lift lift, @Nullable Notice notice) {}

    sealed interface Validation {
        record Valid(Accepted accepted) implements Validation {}

        record Invalid(String code) implements Validation {}
    }

    /**
     * 표준 envelope(AC-3). 원장 행 하나하나가 이 값에서 나온다.
     *
     * @param operationId 서버가 만든 불투명 ID
     * @param actorUserId 세션이 정한 수행자. 클라이언트 값이 아니다
     * @param target 원장의 target({@link Accepted#ledgerTarget}). 사용자 귀속 원식별자가 아니다
     * @param confirmationHash 허가와 같은 정규 hash({@code ActionGrant.canonicalConfirmationHash}). 원문은 남기지 않는다
     * @param requestedAt 요청 시각(원장의 DB 시각). 첫 기록 전에는 {@code null}
     * @param idempotencyKeyDigest 멱등 키의 SHA-256. 키 원문은 남기지 않는다
     */
    record Envelope(String operationId, UUID actorUserId, String command, String scope, String target, String reason,
            String confirmationHash, String policyVersion, @Nullable Instant requestedAt, String idempotencyKeyDigest,
            String requestDigest) {}

    /** 이 command를 아는가. 모르는 type은 경로가 없는 것과 같다(404). */
    boolean isSupported(String command);

    /** 이 command가 요구하는 권한. 모르는 command는 빈 집합이 아니라 예외다(호출자가 {@link #isSupported}를 먼저 본다). */
    Set<OperatorPermission> permissionsOf(String command);

    /** envelope 검증. 수행자 확인(권한)은 호출자가 먼저 한다. */
    Validation validate(Proposal proposal);

    /** 새 불투명 operation ID. 난수뿐이라 수행자·대상·시각을 복원할 수 없다. */
    String newOperationId();

    /** 멱등 키의 digest(SHA-256 16진). 원장과 무장에는 이 값만 남는다. */
    String digestOf(String idempotencyKey);

    /** 준비 시점의 멱등 키 판정. */
    sealed interface KeyUse {
        /** 이 수행자가 이 키를 쓴 적이 없다. */
        record Fresh() implements KeyUse {}

        /** 같은 키·같은 입력의 열린 operation이 있다. 그 ID와 요청 시각을 다시 쓴다. */
        record Open(String operationId, Instant requestedAt) implements KeyUse {}

        /** 다른 입력에 쓰였거나 이미 끝난 operation의 키다. */
        record Conflict() implements KeyUse {}
    }

    KeyUse keyUse(UUID actorUserId, String idempotencyKeyDigest, String requestDigest);

    /** 준비의 확정적인 키 판정 결과. */
    sealed interface Claim {
        /** 이 operation으로 무장한다. 새 operation이면 {@code REQUESTED} 행을 방금 썼다. */
        record Claimed(String operationId, Instant requestedAt) implements Claim {}

        /** 다른 입력에 쓰였거나 이미 끝난 operation의 키다. 아무것도 쓰지 않았다. */
        record Conflict() implements Claim {}
    }

    /**
     * 키 판정과 {@code REQUESTED} 추가를 원장 추가 잠금 안의 <b>한 트랜잭션</b>으로 한다. 같은 새 키의 동시 준비 둘이
     * operation 둘을 만들지 않는다 — 뒤의 것은 잠금 뒤에 앞의 것이 쓴 행을 보고 같은 operation을 받는다.
     *
     * @param candidate 새 operation일 때 쓸 envelope. 그 {@code operationId}가 후보다
     */
    default Claim claim(Envelope candidate, String correlationId) {
        return claim(candidate, null, correlationId);
    }

    /**
     * {@link #claim(Envelope, String)}에 해제의 증거를 더해 {@code REQUESTED} 행의 {@code details}에 남긴다(Story 3.4).
     *
     * @param lift 해제 command의 요청. 그 밖은 {@code null}
     */
    Claim claim(Envelope candidate, @Nullable Lift lift, String correlationId);

    /**
     * {@code REQUESTED} 행을 쓴다. 자기 트랜잭션에서(또는 호출자의 것에 참여해) 쓴다.
     *
     * @return 원장이 정한 요청 시각
     */
    Instant recordRequested(Envelope envelope, String correlationId);

    /** 결정 행({@code DENIED}·{@code FAILED}·{@code CANCELLED}). {@code code}는 닫힌 거절 code다. */
    void recordDecision(Envelope envelope, AuditOutcome outcome, String code, String correlationId);

    /**
     * envelope 검증에서 거절된 제안의 {@code DENIED} 행. 새 operation ID로 쓴다.
     *
     * <p>거절된 시도도 감사 사건이다(AC-4). 다만 검증을 지나지 않은 값을 그대로 남기지 않는다 — scope·target은 형식이
     * 맞을 때만, 사유는 사유 규칙을 지날 때만 원문이고 나머지는 {@code -}다. 이메일이 실린 사유가 거절 행을 타고 원장에
     * 들어가는 길을 막는다.
     *
     * @return 그 행의 operation ID
     */
    String recordRefusedProposal(UUID actorUserId, Proposal proposal, String code, String idempotencyKeyDigest,
            String correlationId);

    /** 감사 행의 결과. 스키마 CHECK와 같은 집합이다. */
    enum AuditOutcome {
        REQUESTED,
        APPROVED,
        DENIED,
        FAILED,
        CANCELLED,
        COMPLETED
    }

    /** 실행된 command의 결과. 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. */
    sealed interface CommandResult permits ExportResult, RestrictionResult, LiftResult, NoticeResult {
        String operationId();
    }

    /**
     * 추출 실행의 결과. 허가에 저장되는 것은 이 값뿐이다(허가 결과 상한 4KiB) — 행 묶음은 원장을
     * {@code sequence <= upperSequence}로 다시 읽어 만든다. 원장이 추가 전용이라 결정적이다.
     */
    record ExportResult(String operationId, String target, long upperSequence, int rowCount, boolean truncated)
            implements CommandResult {}

    /**
     * 제한 생성의 결과(Story 3.2). target 원문을 담지 않는다 — 재생이 원장에서 오고 원장에는 원문이 없다.
     *
     * @param effectiveFrom 제한의 효력 시작(DB 시각)
     */
    record RestrictionResult(String operationId, UUID restrictionId, RestrictionFeature feature,
            RestrictionScope scope, Instant effectiveFrom) implements CommandResult {}

    /**
     * 제한 해제의 결과(Story 3.4). 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. smoke의 결말은 여기 없다 — 커밋 뒤의 일이라
     * 그 operation의 smoke 행({@value #SMOKE_PASSED}·{@value #SMOKE_FAILED})이 말한다({@link #liftSmoke}).
     *
     * @param liftedAt 제한이 닫힌 시각(DB)
     * @param reopenedStages 해제로 다시 열리는 단계 code
     * @param stillBlockedStages 해제한 제한의 단계 가운데 남는 제한이 여전히 막는 것
     */
    record LiftResult(String operationId, UUID restrictionId, RestrictionFeature feature, RestrictionScope scope,
            Instant liftedAt, List<String> reopenedStages, List<String> stillBlockedStages) implements CommandResult {

        public LiftResult {
            reopenedStages = List.copyOf(reopenedStages);
            stillBlockedStages = List.copyOf(stillBlockedStages);
        }
    }

    /**
     * 공지 발행의 결과(Story 3.5). 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. 메시지 원문을 담지 않는다.
     *
     * @param publishedAt 버전을 쓴 DB 시각
     */
    record NoticeResult(String operationId, UUID incidentId, int version, ServiceNoticeApi.NoticeStatus status,
            Instant publishedAt) implements CommandResult {}

    /**
     * {@value #AUDIT_EXPORT}를 실행하고 {@code COMPLETED} 행을 쓴다. <b>호출자의 트랜잭션 안에서만</b> 부른다 — 그
     * 트랜잭션이 허가 소비와 같아야 감사 없는 실행·실행 없는 감사가 생기지 않는다.
     *
     * <p>원장 추가 잠금을 쥔 <b>뒤에</b> {@code finalAuthority}로 최종 권한을 다시 본다(AC-6). 잠금 뒤라 그 사이 다른 감사
     * 행이 끼어들 수 없고, 거짓이면 아무것도 쓰지 않고 빈 값을 돌려준다 — 호출자가 트랜잭션을 되돌리고 거절 행을 밖에서
     * 쓴다.
     *
     * @param finalAuthority 지금 이 트랜잭션에서 본 수행자의 최종 권한
     * @return 실행 결과. 최종 권한이 없으면 빈 값
     * @throws IllegalStateException 열린 트랜잭션이 없을 때
     */
    Optional<ExportResult> completeExport(Envelope envelope, String correlationId, BooleanSupplier finalAuthority);

    /** 확정 순간 대상 재검사의 답(Story 3.2). */
    sealed interface TargetCheck {
        /**
         * 대상이 여전히 그 제한의 대상이다.
         *
         * @param ownerUserId 대상이 귀속된 사용자. 전역·계산 정책 버전은 {@code null}
         */
        record Valid(@Nullable UUID ownerUserId) implements TargetCheck {}

        /** 대상이 사라졌거나 상태가 맞지 않다. */
        record Invalid() implements TargetCheck {}
    }

    /** 제한 생성의 결말. */
    sealed interface RestrictionCompletion {
        record Completed(RestrictionResult result) implements RestrictionCompletion {}

        /**
         * 아무것도 쓰지 않았다. 호출자가 트랜잭션을 되돌리고 거절 행을 밖에서 쓴다.
         *
         * @param code 거절 code — {@link #ACCESS_REVOKED}·{@link #TARGET_INVALID}·{@link #ALREADY_RESTRICTED}
         */
        record Refused(String code) implements RestrictionCompletion {}
    }

    /**
     * 제한 생성 command를 실행하고 {@code COMPLETED} 행을 쓴다(Story 3.2 AC-3). <b>호출자의 트랜잭션 안에서만</b> 부른다 —
     * 그 트랜잭션이 허가 소비와 같다.
     *
     * <p>순서가 계약이다: 원장 추가 잠금 → 최종 권한({@code finalAuthority}) → 대상 재검사({@code targetCheck}) → 활성 중복
     * 판정 → 제한 INSERT → {@code COMPLETED}. 잠금 뒤라 같은 제한의 동시 확정 둘이 줄을 서고, 뒤의 것은 앞의 것이 커밋한
     * 행을 보고 {@link #ALREADY_RESTRICTED}로 거절된다(최후 권위는 부분 유일 색인이다).
     *
     * @throws IllegalStateException 열린 트랜잭션이 없을 때
     */
    RestrictionCompletion completeRestriction(Envelope envelope, Restriction restriction, String correlationId,
            BooleanSupplier finalAuthority, Supplier<TargetCheck> targetCheck);

    /** 공지 발행의 결말(Story 3.5). */
    sealed interface NoticeCompletion {
        record Completed(NoticeResult result) implements NoticeCompletion {}

        /**
         * 아무것도 쓰지 않았다. 호출자가 트랜잭션을 되돌리고 거절 행을 밖에서 쓴다.
         *
         * @param code {@link #ACCESS_REVOKED}·{@link #TARGET_INVALID}·{@link #NOTICE_VERSION_CONFLICT}·
         *     {@link #INCIDENT_CLOSED}·{@link #NEXT_UPDATE_INVALID}
         */
        record Refused(String code) implements NoticeCompletion {}
    }

    /**
     * 공지 발행 command를 실행하고 {@code COMPLETED} 행을 쓴다(Story 3.5 AC-3·AC-6). <b>호출자의 트랜잭션 안에서만</b>
     * 부른다 — 그 트랜잭션이 허가 소비와 같다.
     *
     * <p>순서가 계약이다: 원장 추가 잠금 → 최종 권한({@code finalAuthority}) → 사건의 최신 버전(없음·닫힘·번호 어긋남) →
     * 덮는 제한(진행 중이면 모두 지금 열려 있어야 하고, 복구면 모두 닫혀 있어야 한다) → 다음 안내 시각(DB 시각) → (새
     * 사건이면 사건 행) → 버전 행(확정 시점의 지원 연락처를 함께 고정한다) → {@code COMPLETED}. 모든 발행이 같은 잠금을
     * 쥐므로 같은 사건의 동시 발행 둘이 줄을 서고, 뒤의 것은 앞의 것이 쓴 버전을 보고 {@link #NOTICE_VERSION_CONFLICT}로
     * 거절된다. 최후 권위는 버전 기본키다 — 그 제약의 위반만 같은 code이고, 다른 무결성 위반은 실행 실패로 올라간다.
     *
     * @param supportContact 확정 시점의 지원 연락처. 버전 행에 그대로 남는다
     * @throws IllegalStateException 열린 트랜잭션이 없을 때
     */
    NoticeCompletion completeNotice(Envelope envelope, Notice notice, String supportContact, String correlationId,
            BooleanSupplier finalAuthority);

    /** 해제 뒤 기대하는 단계(Story 3.4). 남는 제한을 본 호출자가 원장 잠금 아래에서 만든다. */
    record LiftExpectation(List<String> reopenedStages, List<String> stillBlockedStages) {}

    /** 해제의 결말. */
    sealed interface LiftCompletion {
        record Completed(LiftResult result) implements LiftCompletion {}

        /**
         * 아무것도 바꾸지 않았다. 호출자가 트랜잭션을 되돌리고 거절 행을 밖에서 쓴다.
         *
         * @param code {@link #ACCESS_REVOKED}·{@link #TARGET_INVALID}·{@link #RESTRICTION_NOT_ACTIVE}·
         *     {@link #NEW_SIGNAL_SINCE_PREPARE}
         */
        record Refused(String code) implements LiftCompletion {}
    }

    /**
     * 해제 command를 실행하고 {@code COMPLETED} 행을 쓴다(Story 3.4 AC-1·AC-3). <b>호출자의 트랜잭션 안에서만</b> 부른다 — 그
     * 트랜잭션이 허가 소비와 같다.
     *
     * <p>순서가 계약이다: 원장 추가 잠금 → 최종 권한({@code finalAuthority}) → 제한 행 잠금 → 열림·신호 계보 재검사 → 조건부
     * UPDATE 한 문장 → {@code expectation} → {@code COMPLETED}. 신호({@code raise})도 원장 잠금을 먼저 쥐므로 잠금 뒤에는 새
     * 신호가 그 제한에 연결될 수 없다. 갱신 행이 0이면 아무것도 쓰지 않는다. 신호 행·원장 행은 고치지 않는다.
     *
     * @param expectation 해제 뒤 기대하는 단계. 제한 행을 닫은 뒤 잠금 안에서 한 번 부른다
     * @throws IllegalStateException 열린 트랜잭션이 없을 때
     */
    LiftCompletion completeLift(Envelope envelope, Lift lift, String correlationId, BooleanSupplier finalAuthority,
            Supplier<LiftExpectation> expectation);

    /**
     * 해제 커밋 뒤 smoke가 어긋났다(Story 3.4 AC-5). 해제를 되돌리지 않는다 — 같은 기능·scope·target의 새 제한(출처 운영자,
     * operation은 이 해제의 것)과 {@code FAILED}({@value #SMOKE_FAILED}) 행을 자기 트랜잭션 하나로 쓴다. 해제 사실과 되돌림
     * 사실이 둘 다 원장에 남는다. 같은 대상에 이미 활성 제한이 생겼으면 새 행 없이 거절 행만 쓴다(이미 막혀 있다).
     *
     * @return 새 제한의 ID. 이미 막혀 있어 만들지 않았으면 빈 값
     */
    Optional<UUID> revertLift(Envelope envelope, LiftResult result, String correlationId);

    /**
     * smoke 통과를 그 해제 operation의 {@code APPROVED}({@value #SMOKE_PASSED}) 행 하나로 남긴다(Story 3.4). 자기 트랜잭션이다.
     * 통과도 실패도 원장에 남아야 재전송이 smoke 결말을 지어내지 않는다.
     */
    void recordSmokePassed(Envelope envelope, LiftResult result, String correlationId);

    /** 원장이 말하는 해제의 smoke 결말. */
    enum LiftSmoke {
        PASSED,
        REVERTED,
        /** smoke 행이 아직 없다 — smoke가 도는 중이거나(동시 재전송) 결말 기록이 실패했다. 통과로 접지 않는다. */
        PENDING
    }

    /** 그 해제 operation의 smoke 결말. 재전송이 원장에서 읽는다. */
    LiftSmoke liftSmoke(String operationId);

    /** 같은 operation·키의 저장된 결과. */
    sealed interface Replay {
        record None() implements Replay {}

        record Stored(CommandResult result) implements Replay {}

        /** 그 operation이 이 키가 아닌 키로 끝났다. */
        record Conflict() implements Replay {}
    }

    Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest);

    /** 추출 결과의 행 묶음. 조회와 같은 allowlist 필드이며 오래된 순이다. */
    List<OperatorAuditApi.Entry> exportRows(ExportResult result);
}
