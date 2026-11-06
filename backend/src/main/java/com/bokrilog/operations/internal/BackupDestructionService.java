package com.bokrilog.operations.internal;

import com.bokrilog.operations.BackupDestructionApi;
import com.bokrilog.operations.BackupRecordApi.Store;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.internal.BackupEventRepository.GenerationEvent;
import com.bokrilog.operations.internal.DeletionTombstoneRepository.Candidate;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BackupDestructionApi} 구현(Story 5.3). tombstone 쪽 SQL은 {@link DeletionTombstoneRepository}, 백업 기록 SQL은
 * {@link BackupEventRepository}, 감사 행은 {@link TombstoneAudit} 한 곳에 있다 — 이 클래스는 두 기록을 대조하는 규칙만 가진다.
 *
 * <p><b>tombstone 하나 = 짧은 트랜잭션 하나.</b> 판정 한 번은 (1) 운영 완료 시각을 받은 열린 집합마다 닫기 트랜잭션, (2) 읽기 트랜잭션
 * 하나에서 "지금"(DB 시각)·파기 완료가 아닌 요청 ID·백업 기록 전체를 generation별로 접기({@link Generation}), (3) 그 요청 ID마다 행을
 * {@code FOR UPDATE SKIP LOCKED}로 잠그고 영향 generation 기록·완료·초과 전환·감사 행을 쓰는 트랜잭션이다. 트랜잭션마다 잠금 대기 2초·문장
 * 5초 상한을 둔다({@link DeletionTombstoneRepository#limitJudgeTime}) — 판정이 계정 삭제 단계(같은 tombstone 행의 기록·닫기)나 감사 원장
 * 추가를 오래 막지도, 그것을 오래 기다리지도 않는다. 상한을 넘긴(또는 잠겨 건너뛴) tombstone은 그 트랜잭션만 되돌리고 다음 주기가 다시
 * 판정한다 — 다른 tombstone의 판정은 이어진다.
 *
 * <p>generation ID의 날짜는 판정에 쓰지 않는다 — V24 CHECK를 통과한 무효 날짜(예 {@code gen_20261399})도 다른 generation과 똑같이(보수적으로)
 * 다룬다.
 */
@Service
class BackupDestructionService implements BackupDestructionApi {

    /** 파기 기록으로 세는 code: {@code generations/}의 29일·수량·용량 규칙({@code backup.sh}). incoming 정리·로컬 임시본은 아니다. */
    static final Set<String> GENERATION_PURGE_CODES = Set.of("AGE", "COUNT", "BYTES");

    private static final Logger LOG = LoggerFactory.getLogger(BackupDestructionService.class);

    private final DeletionTombstoneRepository tombstones;

    private final BackupEventRepository events;

    private final TombstoneAudit audit;

    private final TransactionTemplate transaction;

    private final TransactionTemplate reads;

    BackupDestructionService(DeletionTombstoneRepository tombstones, BackupEventRepository events, TombstoneAudit audit,
            PlatformTransactionManager transactionManager) {
        this.tombstones = tombstones;
        this.events = events;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    @Override
    public List<String> openRequestIds() {
        List<String> ids = this.reads.execute(status -> this.tombstones.openRequestIds());
        return ids == null ? List.of() : List.copyOf(ids);
    }

    @Override
    public Judgement judge(Map<String, Instant> completions) {
        int skipped = 0;
        int closed = 0;
        for (var completion : completions.entrySet()) {
            if (completion.getValue() == null || !DeletionTombstoneApi.REQUEST_ID.matcher(completion.getKey()).matches()) {
                continue;
            }
            Optional<Integer> result = bounded(() -> closeOne(completion.getKey(), completion.getValue()));
            if (result.isEmpty()) {
                skipped++;
            } else {
                closed += result.get();
            }
        }
        Snapshot snapshot = this.reads.execute(status -> {
            this.tombstones.limitJudgeTime();
            Instant now = this.tombstones.now();
            List<String> ids = this.tombstones.undestroyed();
            // 판정할 행이 없으면 백업 기록을 접지 않는다.
            return new Snapshot(now, ids, ids.isEmpty() ? null : fold(this.events.generationEvents()));
        });
        if (snapshot == null) {
            throw new IllegalStateException("백업 파기 판정이 결과 없이 끝났다.");
        }
        int destroyed = 0;
        int overdue = 0;
        Folded folded = snapshot.folded();
        if (folded != null) {
            for (String requestId : snapshot.requestIds()) {
                Optional<Transition> result = bounded(() -> judgeOne(requestId, folded, snapshot.now()));
                if (result.isEmpty()) {
                    skipped++;
                } else if (result.get() == Transition.DESTROYED) {
                    destroyed++;
                } else if (result.get() == Transition.OVERDUE) {
                    overdue++;
                }
            }
        }
        if (skipped > 0) {
            // 수만 남긴다(요청 ID·digest 없음). 다음 주기가 다시 판정한다.
            LOG.atWarn().addKeyValue("count", skipped).log("backup destruction judge skipped");
        }
        return new Judgement(closed, destroyed, overdue);
    }

    /** 그 tombstone 하나의 짧은 트랜잭션을 돈다. 잠금 대기·문장 상한을 넘기면 그 트랜잭션만 되돌리고 비어 있는 결과를 낸다. */
    private <T> Optional<T> bounded(Supplier<T> work) {
        try {
            return Optional.ofNullable(this.transaction.execute(status -> {
                this.tombstones.limitJudgeTime();
                return work.get();
            }));
        } catch (TransientDataAccessException busy) {
            return Optional.empty();
        }
    }

    /** 열린 집합 하나를 운영 완료 시각으로 닫는다(보존 연장). 감사 행은 그 트랜잭션의 마지막이다. */
    private Integer closeOne(String requestId, Instant completedAt) {
        int closed = this.tombstones.closeAt(requestId, completedAt);
        if (closed > 0) {
            this.audit.append(TombstoneAudit.Event.RETENTION_EXTENDED, Map.of("count", closed));
        }
        return closed;
    }

    /** 판정 하나의 전환. */
    private enum Transition {
        NONE, DESTROYED, OVERDUE
    }

    /**
     * tombstone 하나를 잠그고 영향 generation을 기록하고 완료·초과를 판정한다. 잠겨 있거나(계정 삭제 단계가 쥐었다) 이미 완료면 아무것도 하지
     * 않는다.
     */
    private Transition judgeOne(String requestId, Folded folded, Instant now) {
        Optional<Candidate> locked = this.tombstones.lockCandidate(requestId);
        if (locked.isEmpty()) {
            return Transition.NONE;
        }
        Candidate candidate = locked.get();
        Transition transition = Transition.NONE;
        if (recordGenerations(candidate, folded, now)) {
            if (this.tombstones.markDestroyed(candidate.requestId()) > 0) {
                transition = Transition.DESTROYED;
            }
        } else if (candidate.status() == DeletionTombstoneApi.DestructionStatus.PENDING
                && !now.isBefore(candidate.backupDeadline())
                && this.tombstones.markOverdue(candidate.requestId()) > 0) {
            transition = Transition.OVERDUE;
        }
        // 감사 행은 그 트랜잭션의 마지막이다(원장 추가 잠금을 커밋까지 쥔다). 수만 싣는다.
        if (transition == Transition.DESTROYED) {
            this.audit.append(TombstoneAudit.Event.BACKUP_DESTROYED, Map.of("count", 1));
        } else if (transition == Transition.OVERDUE) {
            this.audit.append(TombstoneAudit.Event.BACKUP_OVERDUE, Map.of("count", 1));
        }
        return transition;
    }

    /**
     * tombstone 하나의 영향 generation을 기록하고 백업 파기 완료인지 판정한다.
     *
     * <p><b>커밋 여유({@link BackupDestructionApi#CLOSURE_SLACK}).</b> 운영 완료 시각은 마지막 단계의 문장 시각이고 그 트랜잭션의 커밋은
     * 조금 뒤다 — 그 사이에 시작한 dump는 삭제 전 snapshot을 담는다. 그래서 닫힌 집합의 경계는 운영 완료 + 여유이고, 그 경계가 지나기 전에는
     * 완료로 판정하지 않는다(그 사이에 시작할 dump가 아직 기록되지 않았을 수 있다).
     *
     * @return 집합이 닫히고 경계가 지났고, 결과 없는 실행 중 generation이 없고, 기록된 영향 generation이 모두 두 저장소에서 파기됐다
     */
    private boolean recordGenerations(Candidate candidate, Folded folded, Instant now) {
        Instant closedAt = candidate.closedAt();
        Instant cutoff = closedAt == null ? now : closedAt.plus(CLOSURE_SLACK);
        boolean inFlight = false;
        for (Generation generation : folded.generations().values()) {
            if (generation.storedStartedAt != null) {
                if (!generation.storedStartedAt.isBefore(cutoff)) {
                    continue;
                }
                Instant oci = generation.purgedAt.get(Store.OCI);
                Instant offsite = generation.purgedAt.get(Store.OFFSITE);
                if (oci != null && offsite != null && oci.isBefore(candidate.requestedAt())
                        && offsite.isBefore(candidate.requestedAt())) {
                    // 요청 전에 두 저장소에서 이미 파기됐다 — 이 삭제가 지울 것이 아니다.
                    continue;
                }
                this.tombstones.addGeneration(candidate.requestId(), generation.id, oci, offsite);
            } else if (generation.openStartedAt != null && generation.openStartedAt.isBefore(cutoff)
                    && generation.openStartedSequence >= folded.lastStartedSequence()) {
                // 결과가 아직 없고 그 뒤에 시작한 실행도 없다 — 저장될지 모른다. 기록하지 않지만 완료를 막는다. backup.sh는 대기열(앞선 실행의
                // 결과 행)을 먼저 기록한 뒤 다음 실행의 STARTED를 기록하므로, 뒤 STARTED가 있으면 그 결과는 이미 기록됐거나 영원히 없다.
                inFlight = true;
            }
        }
        return closedAt != null && !now.isBefore(cutoff) && !inFlight
                && this.tombstones.unpurgedGenerations(candidate.requestId()) == 0;
    }

    @Override
    public Set<String> destroyedAmong(Collection<String> requestIds) {
        List<String> valid = requestIds.stream()
                .filter(id -> id != null && DeletionTombstoneApi.REQUEST_ID.matcher(id).matches()).distinct().toList();
        if (valid.isEmpty()) {
            return Set.of();
        }
        List<String> destroyed = this.reads.execute(status -> this.tombstones.destroyedAmong(valid));
        return destroyed == null ? Set.of() : Set.copyOf(destroyed);
    }

    @Override
    public Optional<Destruction> destructionOf(String requestId) {
        if (requestId == null || !DeletionTombstoneApi.REQUEST_ID.matcher(requestId).matches()) {
            return Optional.empty();
        }
        Optional<DeletionTombstoneRepository.DestructionRow> row =
                this.reads.execute(status -> this.tombstones.destructionOf(requestId));
        return row == null ? Optional.empty() : row.map(found -> new Destruction(found.requestedAt(),
                found.backupDeadline(), found.status(), found.destroyedAt()));
    }

    @Override
    public Measurement measure() {
        Measurement measurement = this.reads.execute(status -> {
            this.tombstones.limitStatementTime();
            return new Measurement(this.tombstones.dueSoon(DUE_SOON.toHours()), this.tombstones.overdue(),
                    this.tombstones.lastExportEpochSeconds());
        });
        if (measurement == null) {
            throw new IllegalStateException("백업 파기 측정이 결과 없이 끝났다.");
        }
        return measurement;
    }

    /**
     * 판정 한 번이 읽은 것.
     *
     * @param folded 접은 백업 기록. 판정할 행이 없으면 {@code null}
     */
    private record Snapshot(Instant now, List<String> requestIds, @Nullable Folded folded) {}

    /** generation 하나의 기록을 접은 것. */
    static final class Generation {

        final String id;

        /** 저장된 시도의 시작 시각({@code SUCCEEDED} 바로 앞의 {@code STARTED}). 저장되지 않았으면 {@code null}. */
        @Nullable Instant storedStartedAt;

        /** 결과가 아직 없는 마지막 시작 시각(저장되지 않았을 때만 의미가 있다). */
        @Nullable Instant openStartedAt;

        /** 그 시작 기록의 순번. */
        long openStartedSequence = -1;

        /** 저장소별 첫 파기 기록 시각(29일·수량·용량 code). 저장된 generation에서만 의미가 있다. */
        final Map<Store, Instant> purgedAt = new EnumMap<>(Store.class);

        Generation(String id) {
            this.id = id;
        }
    }

    /**
     * 접은 기록.
     *
     * @param lastStartedSequence 모든 generation을 통틀어 가장 뒤의 {@code STARTED} 순번(없으면 -1)
     */
    record Folded(Map<String, Generation> generations, long lastStartedSequence) {}

    /**
     * 기록(추가 순)을 generation별로 접는다.
     *
     * <p>파기 기록은 순서와 무관하게 센다 — 성공 기록이 있는 generation이면 그 앞에 기록된 {@code PURGED}도 인정한다. 백업 절차는 기록하지
     * 못한 결과 행을 대기열에 두었다가 다음 실행에서 먼저 기록하므로, 같은 generation의 파기 행이 성공 행보다 앞 순번일 수 있다.
     * {@code generations/}에 오르지 않은(성공 기록이 없는) generation의 파기 행은 영향 generation을 만들지 않는다.
     */
    static Folded fold(List<GenerationEvent> rows) {
        Map<String, Generation> folded = new LinkedHashMap<>();
        Map<String, Instant> lastStarted = new LinkedHashMap<>();
        long lastStartedSequence = -1;
        for (GenerationEvent row : rows) {
            Generation generation = folded.computeIfAbsent(row.generationId(), Generation::new);
            switch (row.event()) {
                case STARTED -> {
                    lastStarted.put(row.generationId(), row.recordedAt());
                    lastStartedSequence = Math.max(lastStartedSequence, row.sequence());
                    if (generation.storedStartedAt == null) {
                        generation.openStartedAt = row.recordedAt();
                        generation.openStartedSequence = row.sequence();
                    }
                }
                case SUCCEEDED -> {
                    if (generation.storedStartedAt == null) {
                        Instant started = lastStarted.get(row.generationId());
                        generation.storedStartedAt = started != null ? started : row.recordedAt();
                        generation.openStartedAt = null;
                    }
                }
                case FAILED -> generation.openStartedAt = null;
                case PURGED -> {
                    if (row.store() != null && row.code() != null && GENERATION_PURGE_CODES.contains(row.code())) {
                        Store store = Store.valueOf(row.store().toUpperCase(Locale.ROOT));
                        generation.purgedAt.merge(store, row.recordedAt(), (a, b) -> a.isBefore(b) ? a : b);
                    }
                }
                default -> {
                    // 그 밖의 사건은 이 판정의 근거가 아니다.
                }
            }
        }
        return new Folded(folded, lastStartedSequence);
    }
}
