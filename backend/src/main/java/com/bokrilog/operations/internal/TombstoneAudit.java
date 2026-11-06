package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletionTombstoneApi;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * tombstone·백업 파기 추적의 감사 SYSTEM 행(Story 5.3). 행의 모양은 하나다 — {@code command=tombstone}, {@code scope=DELETION}, target
 * {@link #TARGET}, reason=사건 code({@link Event}), 정책 버전 {@code DELETION-TOMBSTONE-1.0}, 수행자는 {@code system:tombstone} digest다.
 * details는 닫힌 키의 <b>수</b>뿐이다 — digest·요청 ID·사용자 ID·generation ID는 어느 칸에도 없다.
 *
 * <p>호출자 트랜잭션 안에서만 쓴다(그 사실과 같은 커밋이다). 원장 추가 잠금을 그 트랜잭션 끝까지 쥐므로 호출자는 이것을 <b>마지막에</b>
 * 부른다 — 계정 삭제 단계(감사 잠금을 마지막에 쥔다)와 같은 순서다. 감사 digest 키가 없는 역할에서 부르면 실패한다.
 */
@Component
class TombstoneAudit {

    /** 감사 행의 target. 개별 tombstone을 가리키지 않는다(식별자를 남기지 않는다). */
    static final String TARGET = "deletion-tombstone";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 사건 code(감사 행의 reason). */
    enum Event {
        /** 삭제 실행의 첫 단계(또는 단일 모드 삭제)가 tombstone을 기록했다. */
        RECORDED("COMPLETED"),
        /** 운영 데이터 삭제 완료로 영향 generation 집합을 닫고 보존을 늘렸다. */
        RETENTION_EXTENDED("COMPLETED"),
        /** 영향 generation이 두 저장소에서 모두 파기됐다 — 백업 파기 완료. */
        BACKUP_DESTROYED("COMPLETED"),
        /** 백업 파기 기한(요청 + 30일)이 지났는데 완료가 아니다. */
        BACKUP_OVERDUE("FAILED"),
        /** 외부 사본을 올리고 대조했다. */
        EXPORTED("COMPLETED"),
        /** 보존 기한이 지난 tombstone을 지웠다. */
        EXPIRED_PURGED("COMPLETED"),
        /** 복원 적용이 통과했다. */
        RESTORE_APPLIED("COMPLETED"),
        /** 복원 적용이 통과하지 못했다(서비스를 열지 않는다). */
        RESTORE_REFUSED("FAILED");

        private final String outcome;

        Event(String outcome) {
            this.outcome = outcome;
        }
    }

    private final OperatorAuditRepository audit;

    private final ObjectProvider<ActorDigest> digest;

    TombstoneAudit(OperatorAuditRepository audit, ObjectProvider<ActorDigest> digest) {
        this.audit = audit;
        this.digest = digest;
    }

    /** 감사 digest 키가 이 역할에 있는가. */
    boolean available() {
        return this.digest.getIfAvailable() != null;
    }

    /**
     * 행 하나를 호출자 트랜잭션의 <b>커밋 직전</b>(그 트랜잭션의 마지막 일)에 남긴다. 계정 삭제 단계가 기록·보존 연장을 부른 뒤에도 생명주기·
     * 세션 행 잠금(payload 삭제)을 먼저 쥐고 원장 추가 잠금은 마지막에 쥐게 한다(3.10 잠금 순서). 감사 digest 키는 지금 확인한다 — 없으면
     * 그 트랜잭션을 시작부터 실패시킨다. 커밋 직전의 실패는 그 트랜잭션을 되돌린다.
     *
     * @throws IllegalStateException 트랜잭션 밖이거나 감사 digest 키가 없다
     */
    void appendAtCommit(Event event, Map<String, ? extends Number> counts) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("tombstone 감사 행은 그 사실과 같은 트랜잭션에서만 쓴다.");
        }
        if (!available()) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 tombstone 감사 행을 쓸 수 없다.");
        }
        Map<String, Number> copy = Map.copyOf(counts);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                append(event, copy);
            }
        });
    }

    /**
     * 행 하나를 남긴다. <b>호출자 트랜잭션 안에서</b> 마지막에 부른다.
     *
     * @param counts 닫힌 이름 → 수
     * @throws IllegalStateException 트랜잭션 밖이거나 감사 digest 키가 없다
     */
    void append(Event event, Map<String, ? extends Number> counts) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tombstone 감사 행은 그 사실과 같은 트랜잭션에서만 쓴다.");
        }
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("감사 digest 키가 이 역할에 없어 tombstone 감사 행을 쓸 수 없다.");
        }
        Map<String, Object> details = new LinkedHashMap<>(counts);
        String operationId = OperationIds.next();
        this.audit.lockAppends();
        this.audit.append(new OperatorAuditRepository.NewRow(operationId, null,
                keyed.ofSystem(DeletionTombstoneApi.AUDIT_COMMAND), OperatorAuditRepository.ACTOR_SYSTEM,
                DeletionTombstoneApi.AUDIT_COMMAND, DeletionTombstoneApi.AUDIT_SCOPE, TARGET, event.name(), event.outcome,
                DeletionTombstoneApi.POLICY_VERSION, null, true, operationId, JSON.writeValueAsString(details)));
    }
}
