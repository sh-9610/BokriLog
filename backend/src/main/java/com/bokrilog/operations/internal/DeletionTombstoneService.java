package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletionTombstoneApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DeletionTombstoneApi} 구현(Story 3.10). SQL은 {@link DeletionTombstoneRepository} 한 곳에 있다.
 *
 * <p>모든 역할에서 배선하지만 키가 필요한 일({@link #record}·{@link #matching}·{@link #keyId}, 그리고 파일 머리에 키 ID를 싣는
 * {@link #export})은 tombstone 키가 있는 역할에서만 된다 — 없으면 실패한다(조용히 건너뛰면 기록 없는 탈퇴, 가려내지 못한 복원이 된다).
 */
@Service
class DeletionTombstoneService implements DeletionTombstoneApi {

    private final DeletionTombstoneRepository repository;

    private final ObjectProvider<TombstoneDigest> digest;

    private final TransactionTemplate transaction;

    DeletionTombstoneService(DeletionTombstoneRepository repository, ObjectProvider<TombstoneDigest> digest,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.digest = digest;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean record(UUID userId, String requestId) {
        requireCallerTransaction();
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("삭제 요청 ID의 모양이 맞지 않는다.");
        }
        String userDigest = keyed().of(userId);
        boolean inserted = this.repository.insert(userDigest, requestId) == 1;
        // 0행은 같은 계정의 행이 이미 있거나 같은 요청 ID가 다른 계정에 쓰였다는 뜻이다. 앞의 것만 멱등이다 — 뒤의 것으로 끝나면 이
        // 삭제는 tombstone 없이 커밋된다. 그 계정의 행이 실제로 있는지 다시 보고, 없으면 트랜잭션 전체를 되돌린다.
        if (!inserted && !this.repository.exists(userDigest)) {
            throw new IllegalStateException("삭제 요청 ID가 다른 계정의 tombstone에 이미 쓰였다.");
        }
        return inserted;
    }

    @Override
    public boolean carry(Tombstone tombstone) {
        requireCallerTransaction();
        if (this.repository.insertCarried(tombstone) == 1) {
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
        return false;
    }

    @Override
    public Export export() {
        Export export = this.transaction.execute(status -> {
            int purged = this.repository.purgeExpired();
            return new Export(keyed().keyId(), List.copyOf(this.repository.exportable()), purged);
        });
        if (export == null) {
            throw new IllegalStateException("tombstone을 읽지 못했다.");
        }
        return export;
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

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tombstone 기록은 삭제와 같은 트랜잭션에서만 쓴다.");
        }
    }
}
