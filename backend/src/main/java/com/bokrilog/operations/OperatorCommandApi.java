package com.bokrilog.operations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * 보호된 운영 command의 표준 envelope와 그 원장 기록(Story 3.1 AC-3·AC-4·AC-6).
 *
 * <p><b>이 모듈은 HTTP·세션·허가를 모른다.</b> 재인증·일회성 허가·세션 무장·회전은 루트({@code OperatorCommandService})가
 * Epic 1의 부품으로 하고, 여기서는 envelope의 검증(빈 사유·미지원 command·미지원 scope·클라이언트 actor·길이), 서버가
 * 정하는 확인 문구, 감사 행의 추가, 그리고 command의 실행(3.1에는 {@value #AUDIT_EXPORT} 하나)을 한다. 3.2는 같은
 * 자리에 command type을 더하기만 한다.
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

    /** envelope·확인 문구·실행 규칙의 정책 버전. 규칙을 바꾸면 이 값도 바꾼다. */
    String POLICY_VERSION = "OPERATOR-COMMAND-1.0";

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

    /** 대상이 이 command의 형식이 아니다. */
    String TARGET_INVALID = "TARGET_INVALID";

    /** 요청 본문이 수행자를 주장했다. 수행자는 언제나 서버 세션이 정한다. */
    String ACTOR_NOT_ACCEPTED = "ACTOR_NOT_ACCEPTED";

    /**
     * 원장 code: 권한 회수로 거절했다. 이 code로 맺은 operation은 끝난 것이다 — 같은 키의 재준비는 충돌이다
     * ({@link #keyUse}).
     */
    String ACCESS_REVOKED = "ACCESS_REVOKED";

    /** 클라이언트가 보낸 command 제안. 검증 전 원문이다. */
    record Proposal(String command, @Nullable String scope, @Nullable String target, @Nullable String reason,
            boolean actorClaimed) {}

    /**
     * 검증을 지난 제안.
     *
     * @param target 정규화한 대상. 허가의 target이자 원장의 target이다
     * @param confirmationPhrase 서버가 정한 확인 문구. 사용자가 다시 입력해 확정에 실어 보낸다
     * @param requestDigest {@code command|scope|target|reason}의 SHA-256. 같은 멱등 키의 다른 입력을 가른다
     */
    record Accepted(String command, String scope, String target, String reason, String confirmationPhrase,
            String policyVersion, Set<OperatorPermission> permissions, String requestDigest) {}

    sealed interface Validation {
        record Valid(Accepted accepted) implements Validation {}

        record Invalid(String code) implements Validation {}
    }

    /**
     * 표준 envelope(AC-3). 원장 행 하나하나가 이 값에서 나온다.
     *
     * @param operationId 서버가 만든 불투명 ID
     * @param actorUserId 세션이 정한 수행자. 클라이언트 값이 아니다
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
    Claim claim(Envelope candidate, String correlationId);

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

    /**
     * 추출 실행의 결과. 허가에 저장되는 것은 이 값뿐이다(허가 결과 상한 4KiB) — 행 묶음은 원장을
     * {@code sequence <= upperSequence}로 다시 읽어 만든다. 원장이 추가 전용이라 결정적이다.
     */
    record ExportResult(String operationId, String target, long upperSequence, int rowCount, boolean truncated) {}

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

    /** 같은 operation·키의 저장된 결과. */
    sealed interface Replay {
        record None() implements Replay {}

        record Stored(ExportResult result) implements Replay {}

        /** 그 operation이 이 키가 아닌 키로 끝났다. */
        record Conflict() implements Replay {}
    }

    Replay replay(UUID actorUserId, String operationId, String idempotencyKeyDigest);

    /** 추출 결과의 행 묶음. 조회와 같은 allowlist 필드이며 오래된 순이다. */
    List<OperatorAuditApi.Entry> exportRows(ExportResult result);
}
