package com.bokrilog.operations;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * 보호된 운영 command의 표준 envelope와 그 원장 기록(Story 3.1 AC-3·AC-4·AC-6).
 *
 * <p><b>이 모듈은 HTTP·세션·허가를 모른다.</b> 재인증·일회성 허가·세션 무장·회전은 루트({@code OperatorCommandService})가
 * Epic 1의 부품으로 하고, 여기서는 envelope의 검증(빈 사유·미지원 command·미지원 scope·클라이언트 actor·길이), 서버가
 * 정하는 확인 문구, 감사 행의 추가, 그리고 command의 실행을 한다 — 3.1의 {@value #AUDIT_EXPORT}, 3.2의 제한 생성 넷
 * ({@code restrict-<기능>}, {@link #restrictionFeatureOf}), 3.4의 제한 해제 {@value #LIFT_RESTRICTION}, 3.5의 서비스 공지
 * 발행 {@value #PUBLISH_NOTICE}, 3.6의 문의 처리 {@value #UPDATE_INQUIRY}, Epic 5 회고 AI-1의 삭제 재대기열
 * {@value #REQUEUE_DELETION}.
 *
 * <p><b>command마다 전략 하나다(Epic 3 회고 AI-5).</b> 이 인터페이스의 메서드는 command 이름으로 등록표를 찾아 그 전략에
 * 맡긴다 — 루트는 command를 가르지 않는다. 준비 본문 해석·검증·준비 뒤 거절·미리보기·확정 본문·커밋 뒤 처리·재전송·응답
 * 모양은 전략의 몫이고, 확정의 prologue(트랜잭션 가드 → 원장 추가 잠금 → 최종 권한 → {@value #ACCESS_REVOKED})와
 * {@code COMPLETED} 행은 템플릿 한 곳({@link #complete})이 갖는다. 새 command는 전략 구현 하나와 등록 한 줄이다.
 *
 * <p><b>감사 없는 상태 변경이 없다.</b> 실행({@link #complete})은 호출자의 트랜잭션 안에서 결과 행을 쓰고 그 트랜잭션이
 * 허가 소비와 같다 — 하나가 실패하면 모두 롤백된다. 거절·실패·취소 행({@link #recordDecision})은 그 롤백 <b>뒤</b> 자기
 * 짧은 트랜잭션으로 쓴다.
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

    /**
     * 문의 처리(Story 3.6). target은 문의 reference이고 scope는 옮길 상태({@code IN_PROGRESS}·{@code ANSWERED}·
     * {@code RESOLVED})다 — 원장 scope는 {@code INQUIRY:<상태>}이고 원장 target은 reference의 keyed digest다. 답변 원문은
     * 문의 행에만 남고 원장·{@code details}·로그에 들어가지 않는다. 문의의 상태·답변은 이 command만 쓴다.
     */
    String UPDATE_INQUIRY = "update-inquiry";

    /** 문의 처리의 원장 scope 접두사. 뒤에 옮길 상태가 붙는다({@code INQUIRY:ANSWERED}). */
    String INQUIRY_SCOPE_PREFIX = "INQUIRY:";

    /**
     * 재시도 필요로 멈춘 계정 삭제 실행의 재대기열(Epic 5 회고 AI-1). target은 삭제 요청 ID({@code del_…}) 하나이고 scope는 받지
     * 않는다(원장 scope는 {@value #DELETION_SCOPE}). 그 실행 하나를 {@code RETRY_NEEDED → QUEUED}로 되돌릴 뿐 단계 상태를 바꾸지
     * 않는다 — 실패·미실행 단계만 다시 도는 사용자 재시도와 같은 의미다. 원장 target은 요청 ID의 keyed digest다(요청 ID는 사용자
     * 귀속 삭제 요청을 가리킨다). 요청 세션을 잃은 실행을 다시 대기열에 넣는 운영 경로는 이 command 하나다.
     */
    String REQUEUE_DELETION = "requeue-deletion";

    /** 재대기열의 원장 scope. */
    String DELETION_SCOPE = "ACCOUNT_DELETION";

    /**
     * 운영 command 이름 전부. 새 command는 이름 상수와 함께 여기 더하고 그 전략을 등록표에 한 줄 더한다 —
     * {@code OperatorCommandRegistryTest}가 등록표가 이 목록과 정확히 같은지 본다.
     */
    List<String> COMMANDS = List.of(AUDIT_EXPORT, RESTRICT_NEW_CONNECTION, RESTRICT_SYNC, RESTRICT_CALCULATION_DISPLAY,
            RESTRICT_ADVERTISING, LIFT_RESTRICTION, PUBLISH_NOTICE, UPDATE_INQUIRY, REQUEUE_DELETION);

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
     * 확정 순간의 버전 연속·사건 닫힘·덮는 제한·다음 안내 시각 재검사와 지원 연락처의 버전 고정. 1.4(Story 3.6): 문의 처리
     * command {@value #UPDATE_INQUIRY}, 상태 전이 단조({@code ANSWERED} 건너뛰기 금지)와 답변 필수·민감값 규칙, 원장 target·
     * {@code details}의 reference digest, 확정 순간의 행 잠금·전이 재검사. 1.5(Epic 5 회고 AI-1): 삭제 재대기열 command
     * {@value #REQUEUE_DELETION}, 원장 target·{@code details}의 요청 ID digest, production(G-5 전) 거절, 확정 순간 앞서 잠그지 않는
     * 조건부 UPDATE({@code SKIP LOCKED})와 그 0행의 거절, 운영자 전용 접두사의 재대기열 키 digest.
     */
    String POLICY_VERSION = "OPERATOR-COMMAND-1.5";

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

    /** 문의 상태를 그 상태로 옮길 수 없다 — 역행·같은 상태·{@code ANSWERED} 건너뛰기(Story 3.6). */
    String INQUIRY_TRANSITION_INVALID = "INQUIRY_TRANSITION_INVALID";

    /** {@code ANSWERED} 전이에 답변이 없다(Story 3.6). */
    String INQUIRY_ANSWER_REQUIRED = "INQUIRY_ANSWER_REQUIRED";

    /** 답변이 길이·제어 문자 규칙을 어겼거나 {@code ANSWERED}가 아닌 전이에 답변을 실었다(Story 3.6). */
    String INQUIRY_ANSWER_INVALID = "INQUIRY_ANSWER_INVALID";

    /** 답변에 민감값 모양이 있다(Story 3.6). 사용자 접수의 거절과 같은 code다. */
    String INQUIRY_SENSITIVE_CONTENT = "INQUIRY_SENSITIVE_CONTENT";

    /**
     * 그 삭제 실행이 재시도 필요가 아니다 — 대기·실행 중·완료이거나, 준비 뒤 사용자 재시도·Worker가 먼저 옮겼다(Epic 5 회고 AI-1).
     */
    String DELETION_NOT_RETRYABLE = "DELETION_NOT_RETRYABLE";

    /** 삭제 재대기열을 열지 않는 환경이다 — production(법정 분리 보존 게이트 G-5 승인 전)(Epic 5 회고 AI-1). */
    String DELETION_REQUEUE_UNAVAILABLE = "DELETION_REQUEUE_UNAVAILABLE";

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
     * @param input command별 추가 입력(해제의 증거·공지의 덮는 제한·문의의 답변). 그 command의 전략만 그 모양을 안다. 없으면
     *     {@code null}
     */
    record Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
            boolean actorClaimed, @Nullable Object input) {

        public Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
                boolean actorClaimed) {
            this(command, scope, target, reason, actorClaimed, null);
        }
    }

    /**
     * 해제 대상 제한의 준비 시각 사실(Story 3.4). 제한 행의 기능·scope·target은 바뀌지 않으므로(비식별만 있다) 확정이
     * 다시 읽지 않고 이 값을 쓴다. 연결 신호 수·최신 감지 시각은 확정 순간의 계보와 비교한다.
     *
     * @param target 원 target. 비식별됐으면 digest이고 {@code targetErased}가 참이다
     * @param open 지금 효력 중이고 끝이 정해지지 않았다
     */
    record LiftTarget(RestrictionFeature feature, RestrictionScope scope, String target, boolean targetErased,
            RestrictionOrigin origin, boolean open, int linkedSignals, @Nullable Instant latestSignalAt)
            implements Serializable {

        @Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 검증을 지난 제한 요청(Story 3.2). 대상의 존재·상태는 아직 보지 않았다 — 그것은 다른 모듈의 사실이라 루트가 각 모듈
     * 공개 API로 본다({@link OperatorCommandEnvironment#restrictionTarget}).
     *
     * <p>세션 무장에 실리므로 직렬화할 수 있다.
     *
     * @param target 정규화한 원 target. 전역이면 {@link RestrictionScope#GLOBAL_TARGET}이다
     */
    record Restriction(RestrictionFeature feature, RestrictionScope scope, String target) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;
    }

    /**
     * 검증을 지난 제안.
     *
     * @param scope 원장의 scope. 제한 command는 {@code <기능>:<scope>}다
     * @param target 정규화한 원 대상. 화면에 되돌려 주고 실행에 쓴다
     * @param ledgerTarget 원장·허가의 target. 사용자에게 귀속된 target은 keyed digest이고 그 밖은 {@code target}과 같다
     * @param confirmationPhrase 서버가 정한 확인 문구. 사용자가 다시 입력해 확정에 실어 보낸다
     * @param requestDigest {@code command|scope|ledgerTarget|reason}(과 command별 입력)의 SHA-256. 같은 멱등 키의 다른 입력을
     *     가른다
     * @param payload 그 command의 검증된 요청. 세션 무장에 그대로 실리고 확정이 그 command의 전략에 되돌려 준다. 모양은 그
     *     전략만 안다
     */
    record Accepted(String command, String scope, String target, String ledgerTarget, String reason,
            String confirmationPhrase, String policyVersion, Set<OperatorPermission> permissions, String requestDigest,
            Serializable payload) {}

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

    /**
     * 준비 본문(JSON 객체)을 그 command의 제안으로 읽는다. 공통 세 필드({@code scope}·{@code target}·{@code reason})와 그
     * command의 필드 가운데 문자열이어야 할 것이 문자열이 아니면 {@code null}(본문 모양 오류, 400)이다. 필드가 없는 것은
     * 검증이 거절한다(422). 모르는 command는 공통 세 필드의 제안이다 — 준비가 그것을 없는 경로로 답한다.
     *
     * @param actorClaimed 본문이 수행자를 주장했는가. 판정은 HTTP 표면의 것이고 검증이 거절한다
     */
    @Nullable Proposal parse(String command, JsonNode body, boolean actorClaimed);

    /** envelope 검증. 수행자 확인(권한)은 호출자가 먼저 한다. */
    Validation validate(Proposal proposal);

    /**
     * 준비의 상태 거절. 멱등 키·재인증 판정 <b>뒤</b>, 무장 앞에 적용한다(3.2의 준비 순서) — 대상이 없거나 상태가 맞지
     * 않으면 그 거절이다.
     *
     * @param code 원장·응답의 거절 code
     * @param detail 같은 code의 문장을 가르는 사실(공지 발행은 요청한 공지 상태). 없으면 {@code null}
     */
    record Refusal(String code, @Nullable String detail) {}

    /** 준비 시각의 상태 판정: 거절이거나, 무장해도 되고 그 미리보기를 준다. */
    sealed interface Inspection {
        record Refused(Refusal refusal) implements Inspection {}

        /**
         * @param preview 준비 응답의 미리보기({@code impact}). JSON 객체 하나로 그대로 직렬화된다. 그 command에 미리보기가
         *     없으면 {@code null}이다
         */
        record Ready(@Nullable Map<String, Object> preview) implements Inspection {}
    }

    /**
     * 준비 시각의 상태 판정과 미리보기. 대상의 사실(다른 모듈·제한 표·공지 표)을 한 번 읽어 거절과 미리보기를 함께 정한다.
     */
    Inspection inspect(Accepted accepted);

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
     * operation 둘을 만들지 않는다 — 뒤의 것은 잠금 뒤에 앞의 것이 쓴 행을 보고 같은 operation을 받는다. 그 command가
     * 요청 행에 남기는 증거(해제의 복구 조건·검증·증거 참조, Story 3.4)는 전략이 {@code payload}에서 만든다.
     *
     * @param candidate 새 operation일 때 쓸 envelope. 그 {@code operationId}가 후보다
     * @param payload 그 command의 검증된 요청({@link Accepted#payload})
     */
    Claim claim(Envelope candidate, Serializable payload, String correlationId);

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
     * 들어가는 길을 막는다. 무엇을 남겨도 되는지는 command의 전략이 정한다.
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

    /**
     * 확정 트랜잭션을 되돌리는 거절. 템플릿과 전략이 던지고 호출자(허가 소비의 트랜잭션)가 잡아 거절 행을 밖에서 쓴다.
     * 스택도 메시지도 담지 않는다.
     */
    final class CommandRefusal extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        private final String code;

        public CommandRefusal(String code) {
            super(null, null, false, false);
            this.code = code;
        }

        /** 원장 거절 code — {@value #ACCESS_REVOKED}면 호출자가 404로 옮긴다. */
        public String code() {
            return this.code;
        }
    }

    /**
     * 확정 본문이 실행됐다. 커밋 뒤의 일({@link #afterCommit})에 그대로 되돌려 준다.
     *
     * @param grantRecord 허가에 저장하는 결과 요약(상한 4KiB). 식별자 원문·사유를 담지 않는다
     * @param result 그 command의 실행 결과. 모양은 그 전략만 안다
     */
    record Executed(String command, String grantRecord, Object result) {}

    /**
     * 그 command를 실행하고 {@code COMPLETED} 행을 쓴다 — <b>확정 템플릿</b>. <b>호출자의 트랜잭션 안에서만</b> 부른다 — 그
     * 트랜잭션이 허가 소비와 같아야 감사 없는 실행·실행 없는 감사가 생기지 않는다.
     *
     * <p>순서가 계약이다: 열린 트랜잭션 확인 → 원장 추가 잠금 → 최종 권한({@code finalAuthority}, 거짓이면
     * {@value #ACCESS_REVOKED}) → 그 command의 확정 본문(대상 재검사·쓰기) → {@code COMPLETED}. 잠금 뒤라 그 사이 다른
     * 감사 행·같은 대상의 동시 확정이 끼어들 수 없다. 거절은 {@link CommandRefusal}로 던진다 — 이 트랜잭션은 아무것도
     * 커밋하지 않고 호출자가 거절 행을 밖에서 쓴다.
     *
     * @param payload 준비가 무장한 그 command의 요청({@link Accepted#payload})
     * @param finalAuthority 지금 이 트랜잭션에서 본 수행자의 최종 권한
     * @throws CommandRefusal 최종 권한이 없거나 확정 순간의 상태가 맞지 않을 때
     * @throws IllegalStateException 열린 트랜잭션이 없을 때
     */
    Executed complete(Envelope envelope, Serializable payload, String correlationId, BooleanSupplier finalAuthority);

    /**
     * 확정 트랜잭션이 거절로 끝났을 때 같은 code의 문장을 가르는 사실(공지 발행은 무장된 공지 상태). 없으면 {@code null}.
     */
    @Nullable String refusalDetail(String command, Serializable payload);

    /**
     * 확정 트랜잭션 밖으로 유일 색인 위반이 나왔을 때의 거절 code. 그 command가 색인을 권위로 쓰는 중복(제한의 활성 유일
     * 색인, Story 3.2)이면 그 code이고, 아니면 {@code null}(실행 실패)이다.
     */
    @Nullable String duplicateKeyRefusal(String command);

    /**
     * 커밋 뒤의 처리와 그 응답.
     *
     * @param view 확정 응답 본문(JSON 객체 하나). {@code operationId}·{@code outcome}·{@code command}로 시작한다
     * @param logSummary 실행 로그 한 줄의 command 뒤 부분 — 상태·기능·scope·건수 같은 비식별 사실뿐이다
     */
    record Finished(Map<String, Object> view, String logSummary) {}

    /**
     * 커밋 뒤의 일(해제의 smoke, 추출의 행 묶음)을 하고 응답을 만든다. 무장을 버리고 세션을 회전한 <b>뒤</b>에 부른다.
     * 실패는 예외로 올라간다 — 실행은 이미 커밋됐으므로 같은 키의 재전송이 원장에서 결말을 다시 읽는다.
     *
     * @param requestedCommand 요청 경로의 command 이름. 응답의 {@code command}다
     */
    Finished afterCommit(String requestedCommand, Envelope envelope, Executed executed, String correlationId);

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

    /**
     * 제한 해제의 결과(Story 3.4). 원장의 {@code COMPLETED} 행이 같은 값을 재생한다. smoke의 결말은 여기 없다 — 커밋 뒤의 일이라
     * 그 operation의 smoke 행({@value #SMOKE_PASSED}·{@value #SMOKE_FAILED})이 말한다({@link #liftSmoke}).
     *
     * @param liftedAt 제한이 닫힌 시각(DB)
     * @param reopenedStages 해제로 다시 열리는 단계 code
     * @param stillBlockedStages 해제한 제한의 단계 가운데 남는 제한이 여전히 막는 것
     */
    record LiftResult(String operationId, UUID restrictionId, RestrictionFeature feature, RestrictionScope scope,
            Instant liftedAt, List<String> reopenedStages, List<String> stillBlockedStages) {

        public LiftResult {
            reopenedStages = List.copyOf(reopenedStages);
            stillBlockedStages = List.copyOf(stillBlockedStages);
        }
    }

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

    /** 해제 뒤 기대하는 단계(Story 3.4). 남는 제한을 본 호출자가 원장 잠금 아래에서 만든다. */
    record LiftExpectation(List<String> reopenedStages, List<String> stillBlockedStages) {}

    /** 같은 operation·키의 저장된 결과. */
    sealed interface Replay {
        record None() implements Replay {}

        /** 원장의 {@code COMPLETED} 행에서 다시 만든 확정 응답 본문. */
        record Stored(Map<String, Object> view) implements Replay {}

        /** 그 operation이 이 키가 아닌 키로 끝났다. */
        record Conflict() implements Replay {}
    }

    /**
     * 같은 operation·키의 저장된 결과.
     *
     * @param requestedCommand 요청 경로의 command 이름. 응답의 {@code command}다
     */
    Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest, String requestedCommand);
}
