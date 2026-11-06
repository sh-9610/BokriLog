package com.bokrilog.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * identity가 소유한 데이터의 삭제 경로.
 *
 * <p>두 단계다. {@link #beginDeletion(UUID)}가 상태를 {@code DELETION_IN_PROGRESS}로 전진시켜
 * 새 세션·쓰기·민감 접근을 즉시 막고, {@link #erasePayload(UUID)}가 payload와 인증 매핑을 지운 뒤
 * 상태를 {@code DELETED}로 전진시킨다. 생명주기 guard 행은 두 단계 어디서도 지워지지 않는다.
 *
 * <p>사용자에게 노출되는 계정 삭제 화면과 여러 모듈에 걸친 삭제 조율은 Epic 5가 소유한다. 사용자의 삭제 요청
 * ({@link #requestDeletion})은 Story 5.1이 더했다 — 요청 행과 삭제 진행 전환을 원자로 만들 뿐 데이터를 지우지 않는다.
 * 이 API는 HTTP로 노출되지 않으며, 다른 모듈의 데이터를 지우지도 않는다.
 */
public interface AccountDeletionApi {

    /**
     * 삭제 진행 상태로 전환한다.
     *
     * <p>멱등하다. 이미 {@code DELETION_IN_PROGRESS}거나 {@code DELETED}인 계정에 다시 요청해도
     * 상태는 그대로이고 {@link DeletionOutcome#COMPLETED}를 돌려준다. 어떤 반복·실패·재시도도
     * 상태를 {@code ACTIVE}로 되돌리지 않는다.
     *
     * @param userId 삭제를 시작할 계정
     * @return 전진했거나 이미 그 너머면 {@link DeletionOutcome#COMPLETED},
     *     대상이 없으면 {@link DeletionOutcome#NOT_APPLICABLE}
     */
    DeletionOutcome beginDeletion(@Nullable UUID userId);

    /**
     * identity payload와 인증 매핑을 지우고 삭제를 완료한다.
     *
     * <p>{@link #beginDeletion(UUID)}를 거치지 않은 계정에는 아무것도 하지 않는다. 삭제는 전환을
     * 먼저 거쳐야 하며, 그래야 삭제가 시작된 순간부터 새 세션·쓰기가 막힌다.
     *
     * <p>멱등하다. payload가 이미 없는 계정에 다시 요청해도 실패가 아니다.
     *
     * <p>저장이 실패하면 이 호출은 예외로 끝난다. 트랜잭션이 통째로 롤백되어 상태는
     * {@code DELETION_IN_PROGRESS}로 남고, 부분 실패를 완료로 보고하지 않는다.
     *
     * @param userId 삭제할 계정
     * @return 삭제가 끝났으면 {@link DeletionOutcome#COMPLETED}, 대상이 없거나 아직 삭제 전환을
     *     거치지 않았으면 {@link DeletionOutcome#NOT_APPLICABLE}
     */
    DeletionOutcome erasePayload(@Nullable UUID userId);

    /**
     * 소셜 로그인 연결(인증 매핑)만 지운다 — 계정 삭제의 첫 단계({@link DeletionStep#CREDENTIALS}, Story 5.2). 생명주기 행을 잠근 뒤
     * {@code DELETION_IN_PROGRESS}·{@code DELETED} 계정에만 지운다. {@code ACTIVE}면 아무것도 하지 않는다(전환 없는 삭제를 하지 않는다).
     * 멱등이다. payload·세션·요청 행은 마지막 단계({@link #erasePayload})가 지운다.
     *
     * @return 이번에 지운 행 수. 0도 정상이다
     */
    int eraseSocialLinks(@Nullable UUID userId);

    /**
     * <b>호출자 트랜잭션 안에서</b> 생명주기 행을 잠그고 그 계정의 삭제가 시작됐는지 본다(Story 5.2) — 다른 모듈의 삭제 port가 "삭제 전환을
     * 지난 계정만 지운다"를 생명주기 상태로 직접 판정하는 자리다({@link #eraseSocialLinks}와 같은 규칙).
     *
     * @return 상태가 {@code DELETION_IN_PROGRESS}거나 {@code DELETED}면 {@code true}. {@code ACTIVE}거나 계정이 없으면 {@code false}
     */
    boolean lockDeletionStarted(@Nullable UUID userId);

    /**
     * 그 계정의 단계 모드 실행 행(Story 5.2)이 있으면 <b>호출자 트랜잭션 안에서</b> 잠근다. 단일 모드 삭제가 삭제 전환보다 먼저 부른다 —
     * Worker의 단계(실행 행 → 생명주기)와 같은 잠금 순서가 되게 한다. 행이 없으면 아무것도 하지 않는다.
     */
    void lockRunIfPresent(@Nullable UUID userId);

    /** 그 계정에 남은 소셜 로그인 연결 행 수({@link DeletionStep#CREDENTIALS} 단계의 잔여 검증). */
    long remainingSocialLinksOf(UUID userId);

    /**
     * 단일 모드 삭제(리허설 표본·복원 재삭제)가 그 계정을 끝까지 지웠을 때, 남아 있던 단계 모드 실행({@link AccountDeletionRunApi})을 운영
     * 데이터 삭제 완료로 마감한다(Story 5.2) — {@code QUEUED}·{@code RETRY_NEEDED}로 남지 않게. 끝나지 않은 단계는 성공(건수 0 — 그
     * 실행이 아니라 단일 모드가 지웠다)으로 바꾸고 fencing 세대를 올린다. <b>호출자 트랜잭션 안에서</b> payload 삭제와 같은 커밋으로 부른다.
     * 실행이 없거나 이미 완료면 아무것도 하지 않는다.
     *
     * @return 실행을 마감했는가
     */
    boolean finishRunBySingleErasure(@Nullable UUID userId);

    // ------------------------------------------------------------------ Story 3.10 복원 적용

    /**
     * 생명주기 행이 있는 모든 계정(상태 무관). 복원 적용이 외부 tombstone과 맞춰 볼 후보다. HTTP로 노출되지 않는다.
     */
    List<UUID> accounts();

    /** 삭제가 끝난({@code DELETED}) 계정. 복원 검증이 그 계정의 잔여 행을 센다. */
    List<UUID> deletedAccounts();

    /**
     * 삭제가 시작됐지만 끝나지 않은({@code DELETION_IN_PROGRESS}) 계정. 요청 없는 삭제는 한 트랜잭션이라 정상이면 없다 — 복원 검증은 외부
     * tombstone이 다시 지우지 않았고 사용자의 삭제 요청({@link #deletionRequestOf}, Story 5.1)도 없는 이 상태의 계정을 불변식 위반으로
     * 센다. 요청이 있는 계정은 모듈 삭제를 기다리는 정상 대기 상태다.
     */
    List<UUID> deletionInProgressAccounts();

    /**
     * 인증 세션을 <b>모두</b> 지운다(속성 행은 cascade). <b>복원 적용 전용이다</b> — 복원된 DB에는 백업 시점의 세션이 남아 있고, 서비스를
     * 열기 전에 그 전부를 폐기해야 한다(AC-3). 세션 저장소 빈이 없는 일회성 역할에서도 돌도록 SQL은 이 모듈의 저장소에 있다.
     *
     * @return 지운 세션 수
     */
    int revokeAllSessions();

    /**
     * 그 계정에 아직 남은 identity 행 수 — payload·인증 매핑·세션·삭제 요청(Story 5.1). 생명주기 guard 행은 세지 않는다(삭제 뒤에도
     * 남는 것이 계약이다). 삭제 실행·단계 상태(Story 5.2, {@link AccountDeletionRunApi})도 세지 않는다 — 생명주기 행처럼 삭제 기록이고
     * 완료 시각의 근거다. 삭제가 끝난 계정이면 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);

    // ------------------------------------------------------------------ Story 5.1 사용자의 삭제 요청

    /**
     * 사용자의 계정 삭제 요청 하나. 사용자당 하나이며 고치지 않는다.
     *
     * @param requestId 서버가 만든 불투명 요청 ID
     * @param requestedAt 요청 시각(DB 시각)
     * @param policyVersion 사용자가 확인한 삭제 정책 버전
     * @param noticeHash 사용자가 본 고지의 SHA-256
     * @param idempotencyKeyDigest 확정 요청 멱등 키의 SHA-256. 같은 키의 재전송을 가르는 데만 쓴다
     */
    record DeletionRequest(String requestId, Instant requestedAt, String policyVersion, String noticeHash,
            String idempotencyKeyDigest) {}

    /** 요청의 결과. 값이 넷이고 사유를 더 나누지 않는다. */
    sealed interface DeletionRequestOutcome {

        /** 이번 호출이 요청 행을 만들고 계정을 {@code DELETION_IN_PROGRESS}로 전진시켰다. */
        record Created(DeletionRequest request) implements DeletionRequestOutcome {}

        /** 같은 멱등 키의 요청이 이미 있다. 아무것도 쓰지 않았다. */
        record Replayed(DeletionRequest request) implements DeletionRequestOutcome {}

        /** 다른 키의 요청이 이미 있다. 아무것도 쓰지 않았다. */
        record InProgress() implements DeletionRequestOutcome {}

        /** 계정이 없거나 {@code ACTIVE}가 아니거나 값이 받아들일 수 없다. 아무것도 쓰지 않았다. */
        record Denied() implements DeletionRequestOutcome {}
    }

    /**
     * 계정 삭제 요청을 만들고 삭제 진행 상태로 전환한다(Story 5.1).
     *
     * <p>생명주기 행을 잠근 뒤 판정한다 — 이미 요청이 있으면 같은 키는 {@link DeletionRequestOutcome.Replayed}, 다른 키는
     * {@link DeletionRequestOutcome.InProgress}이고, {@code ACTIVE}가 아니면 {@link DeletionRequestOutcome.Denied}다. 그 밖이면 요청
     * 행 INSERT와 {@code ACTIVE → DELETION_IN_PROGRESS}가 <b>호출자의 트랜잭션</b>에서 함께 일어난다 — 허가 소비와 같은 커밋이 되도록
     * 호출자가 트랜잭션을 연다(트랜잭션이 없으면 이 메서드가 연다). 실제 모듈 데이터 삭제는 하지 않는다.
     *
     * <p>HTTP로 노출되지 않는다. 부르는 운영 클래스는 루트의 사용자 요청 표면 하나다({@code ModuleBoundaryTest}).
     *
     * @param policyVersion 사용자가 확인한 정책 버전
     * @param noticeHash 고지의 SHA-256(소문자 16진 64자)
     * @param idempotencyKeyDigest 멱등 키의 SHA-256(소문자 16진 64자)
     */
    DeletionRequestOutcome requestDeletion(@Nullable UUID userId, @Nullable String policyVersion,
            @Nullable String noticeHash, @Nullable String idempotencyKeyDigest);

    /**
     * 그 계정의 삭제 요청. 없거나 계정이 없으면 빈 값이다. 읽기만 한다 — 세션 확인 응답의 삭제 진행 표지와 요청 상태 조회, 그리고
     * 허가 경로보다 앞선 재전송 판정이 쓴다.
     */
    Optional<DeletionRequest> deletionRequestOf(@Nullable UUID userId);
}
