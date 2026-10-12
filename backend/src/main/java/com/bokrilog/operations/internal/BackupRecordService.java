package com.bokrilog.operations.internal;

import com.bokrilog.operations.BackupRecordApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link BackupRecordApi} 구현(Story 3.9). {@code internal}의 package-private 클래스다.
 *
 * <p>모든 행의 모양을 먼저 검사하고(하나라도 밖이면 아무것도 쓰지 않는다), 트랜잭션 하나에서 기록 잠금 → 행 INSERT → (파기 실패·
 * 기한 초과가 있으면) 원장 추가 잠금 → 감사 SYSTEM 행 INSERT를 한다. 표 SQL은 {@link BackupEventRepository}, 원장 SQL은 {@link
 * OperatorAuditRepository} 한 곳에 있다. 같은 generation의 두 번째 성공은 거절한다 — 같은 날 재실행은 절차가 먼저 no-op으로 끝내고,
 * 그 판정을 지나쳐 온 성공은 둘 중 하나가 잘못된 것이다.
 *
 * <p>모든 역할에서 배선하지만 부르는 것은 일회성 {@code backup-record} 역할뿐이다. 감사 digest 키가 없는 역할에서 부르면 실패한다.
 */
@Service
class BackupRecordService implements BackupRecordApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BackupEventRepository events;

    private final OperatorAuditRepository audit;

    private final ObjectProvider<ActorDigest> digest;

    private final TransactionTemplate transaction;

    BackupRecordService(BackupEventRepository events, OperatorAuditRepository audit, ObjectProvider<ActorDigest> digest,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.audit = audit;
        this.digest = digest;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public Recorded record(Batch batch) {
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 백업을 기록할 수 없다.");
        }
        require(batch.operationId(), OPERATION_ID, "operation ID");
        require(batch.actor(), ACTOR, "수행자");
        if (batch.rows().isEmpty() || batch.rows().size() > MAX_ROWS) {
            throw new IllegalArgumentException("기록할 행이 없거나 " + MAX_ROWS + "개를 넘는다.");
        }
        List<Row> audited = new ArrayList<>();
        for (Row row : batch.rows()) {
            validate(row);
            if (row.event() == Event.PURGE_FAILED || row.event() == Event.PURGE_OVERDUE) {
                audited.add(row);
            }
        }
        String actorDigest = keyed.ofBackup(batch.actor());
        Recorded recorded = this.transaction.execute(status -> {
            this.events.lockAppends();
            for (Row row : batch.rows()) {
                if (row.event() == Event.SUCCEEDED && this.events.succeeded(row.generationId())) {
                    throw new IllegalArgumentException("그 generation은 이미 성공으로 기록됐다.");
                }
                this.events.append(row);
            }
            if (!audited.isEmpty()) {
                this.audit.lockAppends();
                for (Row row : audited) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    if (row.store() != null) {
                        details.put("store", row.store().tag());
                    }
                    if (row.code() != null) {
                        details.put("code", row.code());
                    }
                    if (batch.rehearsal()) {
                        details.put("rehearsal", true);
                    }
                    this.audit.append(new OperatorAuditRepository.NewRow(batch.operationId(), null, actorDigest,
                            OperatorAuditRepository.ACTOR_SYSTEM, COMMAND, SCOPE, row.generationId(),
                            row.event().name(), "FAILED", POLICY_VERSION, null, true, batch.operationId(),
                            JSON.writeValueAsString(details)));
                }
            }
            return new Recorded(batch.rows().size(), audited.size());
        });
        if (recorded == null) {
            throw new IllegalStateException("백업을 기록하지 못했다.");
        }
        return recorded;
    }

    /** 사건마다 채울 수 있는 칸({@code V24} CHECK와 같은 규칙). DB까지 가기 전에 같은 판정으로 거절한다. */
    private static void validate(Row row) {
        require(row.generationId(), GENERATION_ID, "generation ID");
        if (row.code() != null) {
            require(row.code(), CODE, "code");
        }
        if (row.sha256() != null) {
            require(row.sha256(), SHA256, "sha256");
        }
        if (row.keyId() != null) {
            require(row.keyId(), KEY_ID, "key ID");
        }
        nonNegative(row.bytes(), "bytes");
        if (row.count() != null && row.count() < 0) {
            throw new IllegalArgumentException("count가 음수다.");
        }
        if (row.quotaBytes() != null && row.quotaBytes() <= 0) {
            throw new IllegalArgumentException("quota가 양수가 아니다.");
        }
        if (row.event() != Event.MEASURED && row.quotaBytes() != null) {
            throw new IllegalArgumentException("quota는 측정에만 쓴다.");
        }
        switch (row.event()) {
            case STARTED -> {
                if (row.store() != null || row.code() != null || row.bytes() != null || row.count() != null
                        || row.sha256() != null || row.keyId() != null) {
                    throw new IllegalArgumentException("시작 행은 사실을 담지 않는다.");
                }
            }
            case SUCCEEDED -> {
                if (row.store() != null || row.code() != null || row.count() != null) {
                    throw new IllegalArgumentException("성공은 저장소 하나·code·개수를 담지 않는다.");
                }
                if (row.bytes() == null || row.bytes() <= 0 || row.sha256() == null || row.keyId() == null) {
                    throw new IllegalArgumentException("성공은 암호문 크기·SHA-256·key ID를 담는다.");
                }
            }
            case FAILED, PURGED, PURGE_FAILED -> {
                if (row.code() == null) {
                    throw new IllegalArgumentException(row.event() + "는 code를 담는다.");
                }
                if (row.sha256() != null) {
                    throw new IllegalArgumentException(row.event() + "는 SHA-256을 담지 않는다.");
                }
            }
            case PURGE_OVERDUE -> {
                if (row.store() == null || row.sha256() != null) {
                    throw new IllegalArgumentException("기한 초과는 저장소를 담고 SHA-256을 담지 않는다.");
                }
            }
            case MEASURED -> {
                if (row.store() == null || row.bytes() == null || row.count() == null || row.quotaBytes() == null
                        || row.sha256() != null) {
                    throw new IllegalArgumentException("측정은 저장소·bytes·개수·quota를 담는다.");
                }
                if (row.code() != null && !MEASURED_OVERDUE.equals(row.code())) {
                    throw new IllegalArgumentException("측정의 code는 " + MEASURED_OVERDUE + "뿐이다.");
                }
            }
        }
    }

    private static void nonNegative(@Nullable Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + "가 음수다.");
        }
    }

    private static String require(@Nullable String value, Pattern shape, String name) {
        if (value == null || !shape.matcher(value).matches()) {
            throw new IllegalArgumentException(name + "의 모양이 맞지 않는다.");
        }
        return value;
    }
}
