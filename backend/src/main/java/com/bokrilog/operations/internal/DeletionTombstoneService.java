package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.DeletionTombstoneApi.AffectedGeneration;
import com.bokrilog.operations.DeletionTombstoneApi.DestructionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DeletionTombstoneApi} 구현(Story 3.10, Story 5.3). SQL은 {@link DeletionTombstoneRepository} 한 곳에, 감사 행은
 * {@link TombstoneAudit} 한 곳에 있다.
 *
 * <p>모든 역할에서 배선하지만 키가 필요한 일({@link #record}·{@link #matching}·{@link #keyId}, 그리고 파일 머리에 키 ID를 싣는
 * {@link #export})은 tombstone 키가 있는 역할에서만 된다 — 없으면 실패한다(조용히 건너뛰면 기록 없는 탈퇴, 가려내지 못한 복원이 된다).
 * 감사 행을 남기는 일은 감사 digest 키도 있어야 한다.
 */
@Service
class DeletionTombstoneService implements DeletionTombstoneApi {

    /** 복원 적용 감사 행이 받는 수의 이름. 그 밖의 이름은 싣지 않는다. */
    static final Set<String> RESTORE_COUNTS = Set.of("tombstones", "matched", "erased", "alreadyDeleted", "carried",
            "sessions");

    private final DeletionTombstoneRepository repository;

    private final ObjectProvider<TombstoneDigest> digest;

    private final TombstoneAudit audit;

    private final TransactionTemplate transaction;

    DeletionTombstoneService(DeletionTombstoneRepository repository, ObjectProvider<TombstoneDigest> digest,
            TombstoneAudit audit, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.digest = digest;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean record(UUID userId, String requestId) {
        requireCallerTransaction();
        requireRequestId(requestId);
        String userDigest = keyed().of(userId);
        return recorded(this.repository.insert(userDigest, requestId) == 1, userDigest);
    }

    @Override
    public boolean record(UUID userId, String requestId, Instant requestedAt) {
        requireCallerTransaction();
        requireRequestId(requestId);
        if (requestedAt == null) {
            throw new IllegalArgumentException("삭제 요청 시각이 없다.");
        }
        String userDigest = keyed().of(userId);
        return recorded(this.repository.insertRequested(userDigest, requestId, requestedAt) == 1, userDigest);
    }

    /**
     * 0행은 같은 계정의 행이 이미 있거나 같은 요청 ID가 다른 계정에 쓰였다는 뜻이다. 앞의 것만 멱등이다 — 뒤의 것으로 끝나면 이 삭제는
     * tombstone 없이 진행된다. 그 계정의 행이 실제로 있는지 다시 보고, 없으면 트랜잭션 전체를 되돌린다.
     */
    private boolean recorded(boolean inserted, String userDigest) {
        if (!inserted && !this.repository.exists(userDigest)) {
            throw new IllegalStateException("삭제 요청 ID가 다른 계정의 tombstone에 이미 쓰였다.");
        }
        if (inserted) {
            // 감사 행은 그 트랜잭션의 마지막(커밋 직전)이다 — 원장 추가 잠금을 다른 행 잠금 뒤에 쥔다.
            this.audit.appendAtCommit(TombstoneAudit.Event.RECORDED, Map.of("count", 1));
        }
        return inserted;
    }

    @Override
    public boolean close(String requestId) {
        requireCallerTransaction();
        requireRequestId(requestId);
        boolean closed = this.repository.close(requestId) == 1;
        if (closed) {
            // 마지막 단계에서 payload 삭제(생명주기·세션 행 잠금)보다 뒤에 원장 추가 잠금을 쥐도록 커밋 직전에 남긴다.
            this.audit.appendAtCommit(TombstoneAudit.Event.RETENTION_EXTENDED, Map.of("count", 1));
        }
        return closed;
    }

    @Override
    public boolean carry(Tombstone tombstone) {
        requireCallerTransaction();
        if (this.repository.insertCarried(tombstone) == 1) {
            carryGenerations(tombstone);
            return true;
        }
        // 0행: 같은 요청 ID의 행이 있거나 같은 계정(digest)의 행이 있다. 같은 요청 ID가 다른 digest를 가리키면 사본과 DB가 어긋난 것이다
        // — 조용히 넘기면 그 계정의 tombstone이 outbox에 없다. 같은 계정의 다른 요청은 앞선 tombstone이 이미 그 계정을 지킨다.
        String existing = this.repository.digestOf(tombstone.requestId()).orElse(null);
        if (existing != null && !existing.equals(tombstone.userDigest())) {
            throw new IllegalStateException("같은 삭제 요청 ID의 tombstone이 다른 계정을 가리킨다.");
        }
        if (existing == null && !this.repository.exists(tombstone.userDigest())) {
            throw new IllegalStateException("tombstone을 outbox로 옮기지 못했다.");
        }
        if (existing != null) {
            // 같은 요청의 행이 이미 있다(복원된 outbox) — 사본의 더 진전된 상태를 단조로 병합한다(Story 5.3).
            this.repository.mergeCarried(tombstone);
            carryGenerations(tombstone);
        }
        return false;
    }

    /**
     * 사본의 영향 generation과 저장소별 파기 확인을 옮긴다(이미 있으면 빈 확인만 채운다). 옮긴(병합한) 행이 백업 파기 완료면 그 행의 모든
     * generation을 두 저장소 파기로 맞춘다 — 완료는 그 사실이다(덜 진전된 사본이 더한 generation이 완료 행에 미파기로 남지 않게).
     */
    private void carryGenerations(Tombstone tombstone) {
        for (AffectedGeneration generation : tombstone.generations()) {
            this.repository.carryGeneration(tombstone.requestId(), generation.id(), generation.ociPurged(),
                    generation.offsitePurged());
        }
        if (this.repository.statusOf(tombstone.requestId()).orElse(null) == DestructionStatus.DESTROYED) {
            this.repository.markGenerationsPurged(tombstone.requestId());
        }
    }

    @Override
    public int closeRestored(Collection<String> requestIds) {
        Integer closed = this.transaction.execute(status -> {
            int count = 0;
            for (String requestId : requestIds) {
                if (requestId != null && REQUEST_ID.matcher(requestId).matches()) {
                    count += this.repository.close(requestId);
                }
            }
            if (count > 0) {
                this.audit.append(TombstoneAudit.Event.RETENTION_EXTENDED, Map.of("count", count));
            }
            return count;
        });
        return closed == null ? 0 : closed;
    }

    @Override
    public Export export() {
        String keyId = keyed().keyId();
        Export export = this.transaction.execute(status -> {
            // 지우기는 앞선 성공 내보내기가 DESTROYED로 실은 행만 — 그다음에 이 내보내기의 번호를 지금 DESTROYED인 행에 붙인다.
            int purged = this.repository.purgeExpired();
            this.repository.purgeExports();
            long exportId = this.repository.prepareExport();
            this.repository.markDestroyedExport(exportId);
            List<Tombstone> live = List.copyOf(this.repository.exportable());
            if (purged > 0) {
                this.audit.append(TombstoneAudit.Event.EXPIRED_PURGED, Map.of("count", purged));
            }
            return new Export(keyId, live, purged, exportId);
        });
        if (export == null) {
            throw new IllegalStateException("tombstone을 읽지 못했다.");
        }
        return export;
    }

    @Override
    public void confirmExport(int tombstones, long exportId) {
        if (tombstones < 0) {
            throw new IllegalArgumentException("실린 tombstone 수는 음수일 수 없다.");
        }
        if (!this.audit.available()) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 내보내기를 기록할 수 없다.");
        }
        this.transaction.executeWithoutResult(status -> {
            if (this.repository.confirmExport(exportId, tombstones) == 1) {
                this.audit.append(TombstoneAudit.Event.EXPORTED, Map.of("count", tombstones));
            } else if (!this.repository.exportConfirmed(exportId)) {
                throw new IllegalArgumentException("그 내보내기가 없다.");
            }
        });
    }

    @Override
    public void recordRestoreApplied(Map<String, Integer> counts, boolean passed) {
        Map<String, Integer> details = new LinkedHashMap<>();
        for (var entry : counts.entrySet()) {
            if (!RESTORE_COUNTS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("복원 적용 감사 행은 닫힌 이름의 수만 싣는다.");
            }
            details.put(entry.getKey(), entry.getValue());
        }
        this.transaction.executeWithoutResult(status -> this.audit.append(
                passed ? TombstoneAudit.Event.RESTORE_APPLIED : TombstoneAudit.Event.RESTORE_REFUSED, details));
    }

    @Override
    public List<Tombstone> outbox() {
        return List.copyOf(this.repository.live());
    }

    @Override
    public List<Tombstone> recordedOutbox() {
        return List.copyOf(this.repository.liveRecorded());
    }

    @Override
    public String keyId() {
        return keyed().keyId();
    }

    @Override
    public boolean keyAvailable() {
        return this.digest.getIfAvailable() != null;
    }

    @Override
    public Map<UUID, Tombstone> matching(Collection<UUID> userIds, Collection<Tombstone> tombstones) {
        TombstoneDigest keyed = keyed();
        Map<String, Tombstone> byDigest = new HashMap<>();
        for (Tombstone tombstone : tombstones) {
            byDigest.putIfAbsent(tombstone.userDigest(), tombstone);
        }
        Map<UUID, Tombstone> matched = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            Tombstone tombstone = byDigest.get(keyed.of(userId));
            if (tombstone != null) {
                matched.put(userId, tombstone);
            }
        }
        return matched;
    }

    private TombstoneDigest keyed() {
        TombstoneDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("tombstone digest 키가 이 역할에 없다.");
        }
        return keyed;
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("삭제 요청 ID의 모양이 맞지 않는다.");
        }
    }

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tombstone 기록은 삭제와 같은 트랜잭션에서만 쓴다.");
        }
    }
}
