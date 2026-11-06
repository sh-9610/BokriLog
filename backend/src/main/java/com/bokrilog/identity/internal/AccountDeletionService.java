package com.bokrilog.identity.internal;

import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.DeletionOutcome;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 삭제 전환과 payload 삭제.
 *
 * <p>두 연산 모두 생명주기 행을 먼저 잠근다. 그래서 같은 계정에 대한 매핑 연결·중복 전환과
 * 직렬화되고, 단조 전진 술어가 붙은 UPDATE가 되돌아가는 전이를 0행으로 만든다.
 *
 * <p>저장이 실패하면 예외가 그대로 밖으로 나간다. 잡아서 값으로 바꾸면 부분 실패가 완료와 같은
 * 모양이 되고, 상태는 이미 전진했는데 payload는 남은 채로 호출자가 성공을 본다.
 */
@Service
class AccountDeletionService implements AccountDeletionApi {

    private final IdentityAccountRepository repository;

    AccountDeletionService(IdentityAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public DeletionOutcome beginDeletion(@Nullable UUID userId) {
        if (userId == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        if (state == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        return switch (state) {
            case ACTIVE -> {
                repository.markDeletionInProgress(userId);
                yield DeletionOutcome.COMPLETED;
            }
            // 이미 그 지점이거나 그 너머다. 되돌리지 않고 멱등하게 성공으로 답한다.
            case DELETION_IN_PROGRESS, DELETED -> DeletionOutcome.COMPLETED;
        };
    }

    @Override
    @Transactional
    public DeletionOutcome erasePayload(@Nullable UUID userId) {
        if (userId == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        if (state == null) {
            return DeletionOutcome.NOT_APPLICABLE;
        }
        return switch (state) {
            // 전환을 건너뛴 삭제는 하지 않는다. 삭제가 시작된 순간부터 새 세션·쓰기가 막혀야
            // 하는데, 전환 없이 지우면 그 창이 통째로 사라진다.
            case ACTIVE -> DeletionOutcome.NOT_APPLICABLE;
            case DELETION_IN_PROGRESS, DELETED -> {
                // 0행은 실패가 아니다. 이미 payload가 없는 계정에 다시 불러도 같은 결과다.
                repository.deleteSocialLinks(userId);
                // 사용자의 삭제 요청 행(Story 5.1)도 payload와 같은 운명이다. 남기면 잔여 행 수가 0이 되지 않는다.
                repository.deleteDeletionRequest(userId);
                /*
                 * 인증 세션도 이 경로가 지운다.
                 *
                 * Epic 1은 "identity가 도입한 사용자·소셜 식별자·세션의 삭제 경로를 모듈 수준에서
                 * 시험한다"를 요구한다. 매핑만 지우고 세션을 남기면 로그인 수단이 사라진 계정의
                 * 세션이 그대로 살아 있고, 그 세션은 여전히 내부 `user_id`를 결정한다. 그것은
                 * "삭제가 시작된 순간부터 새 세션·쓰기가 막힌다"의 반대편 절반이다.
                 *
                 * 같은 트랜잭션이다. 매핑은 지웠는데 세션이 남는 부분 상태를 만들지 않는다.
                 */
                repository.deleteSessionsOf(userId);
                repository.deleteAccountPayload(userId);
                // 술어가 DELETION_IN_PROGRESS를 요구한다. 이미 DELETED면 0행이고 그것이 멱등이다.
                repository.markDeleted(userId);
                // 생명주기 guard 행은 지우지 않는다. 이 행이 남아야 신규 세션·매핑 생성 판정이
                // 계속 거부한다.
                yield DeletionOutcome.COMPLETED;
            }
        };
    }

    @Override
    @Transactional
    public int eraseSocialLinks(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        // 전환을 건너뛴 삭제는 하지 않는다({@link #erasePayload}와 같은 근거).
        if (state == null || state == AccountState.ACTIVE) {
            return 0;
        }
        return repository.deleteSocialLinks(userId);
    }

    @Override
    @Transactional
    public boolean lockDeletionStarted(@Nullable UUID userId) {
        if (userId == null) {
            return false;
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        return state == AccountState.DELETION_IN_PROGRESS || state == AccountState.DELETED;
    }

    @Override
    @Transactional
    public void lockRunIfPresent(@Nullable UUID userId) {
        if (userId != null) {
            repository.lockRunIfPresent(userId);
        }
    }

    @Override
    @Transactional
    public boolean finishRunBySingleErasure(@Nullable UUID userId) {
        return userId != null && repository.finishRunBySingleErasure(userId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public long remainingSocialLinksOf(UUID userId) {
        return repository.countSocialLinksOf(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> accounts() {
        return repository.findAllAccounts();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> deletedAccounts() {
        return repository.findAccountsIn(AccountState.DELETED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> deletionInProgressAccounts() {
        return repository.findAccountsIn(AccountState.DELETION_IN_PROGRESS);
    }

    @Override
    @Transactional
    public int revokeAllSessions() {
        return repository.deleteAllSessions();
    }

    @Override
    @Transactional(readOnly = true)
    public long remainingRowsOf(UUID userId) {
        return repository.countRemainingRowsOf(userId);
    }

    // ------------------------------------------------------------------ Story 5.1 삭제 요청

    /** 요청 ID의 난수 길이(바이트). base64url로 22자이고 접두사와 합쳐 tombstone 요청 ID 문자 집합 안이다. */
    private static final int REQUEST_ID_BYTES = 16;

    /** 요청 ID 접두사. 사람이 로그·운영 기록에서 종류를 알아보게 할 뿐 뜻을 담지 않는다. */
    private static final String REQUEST_ID_PREFIX = "del_";

    /** 정책 버전의 모양. V30의 CHECK와 한 쌍이다. */
    private static final Pattern POLICY_VERSION = Pattern.compile("ACCOUNT-DELETION-[0-9]+\\.[0-9]+");

    /** SHA-256 소문자 16진 64자. V30의 CHECK와 한 쌍이다. */
    private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 잠금 → 기존 요청 판정 → 상태 판정 → INSERT + 전환. 호출자의 트랜잭션에 참여한다(허가 소비와 같은 커밋). 값이 받아들일 수 없으면
     * 저장소에 닿기 전에 거부한다 — 스키마 CHECK 위반은 호출자의 트랜잭션을 abort시킨다.
     */
    @Override
    @Transactional
    public DeletionRequestOutcome requestDeletion(@Nullable UUID userId, @Nullable String policyVersion,
            @Nullable String noticeHash, @Nullable String idempotencyKeyDigest) {
        if (userId == null || policyVersion == null || noticeHash == null || idempotencyKeyDigest == null
                || !POLICY_VERSION.matcher(policyVersion).matches() || !HEX_DIGEST.matcher(noticeHash).matches()
                || !HEX_DIGEST.matcher(idempotencyKeyDigest).matches()) {
            return new DeletionRequestOutcome.Denied();
        }
        AccountState state = repository.lockLifecycleForUpdate(userId).orElse(null);
        if (state == null) {
            return new DeletionRequestOutcome.Denied();
        }
        // 잠금 아래에서 본다. 같은 계정의 동시 요청은 여기서 한 줄로 서고, 뒤의 것은 앞의 행을 본다.
        var existing = repository.findDeletionRequest(userId).orElse(null);
        if (existing != null) {
            return existing.idempotencyKeyDigest().equals(idempotencyKeyDigest)
                    ? new DeletionRequestOutcome.Replayed(requestOf(existing))
                    : new DeletionRequestOutcome.InProgress();
        }
        if (state != AccountState.ACTIVE) {
            // 요청 없이 삭제가 시작됐거나(리허설 표본·복원 재삭제) 이미 끝났다. 새 요청을 만들지 않는다.
            return new DeletionRequestOutcome.Denied();
        }
        var stored = repository.insertDeletionRequest(newRequestId(), userId, policyVersion, noticeHash,
                idempotencyKeyDigest);
        if (repository.markDeletionInProgress(userId) != 1) {
            // 잠금 아래에서 ACTIVE를 봤으므로 구조상 오지 않는다. 오면 요청 행만 남는 부분 상태를 커밋하지 않는다.
            throw new IllegalStateException("삭제 진행 전환이 한 행이 아니다.");
        }
        return new DeletionRequestOutcome.Created(requestOf(stored));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeletionRequest> deletionRequestOf(@Nullable UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return repository.findDeletionRequest(userId).map(AccountDeletionService::requestOf);
    }

    private static DeletionRequest requestOf(IdentityAccountRepository.StoredDeletionRequest stored) {
        return new DeletionRequest(stored.requestId(), stored.requestedAt().toInstant(), stored.policyVersion(),
                stored.noticeHash(), stored.idempotencyKeyDigest());
    }

    private static String newRequestId() {
        byte[] bytes = new byte[REQUEST_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return REQUEST_ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
