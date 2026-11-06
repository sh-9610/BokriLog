package com.bokrilog;

import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import com.bokrilog.operations.DeletionTombstoneApi.TombstoneFile;
import com.bokrilog.source.SourceConnectionApi;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 복원된 DB에 외부 tombstone을 적용하고 서비스를 열 수 있는지 판정한다(Story 3.10 AC-3·AC-4). 일회성 {@code restore-apply} 역할
 * ({@link RestoreApplyRunner})이 부르고, 그 역할이 0으로 끝난 뒤에만 복원 절차({@code deploy/production/restore.sh})가 API·Worker를
 * 띄운다.
 *
 * <p>순서는 다음과 같다.
 * <ol>
 *   <li><b>대조(아무것도 쓰기 전).</b> 사본의 키 ID가 이 역할의 digest 키와 같아야 한다({@code TOMBSTONE_KEY_ID_MISMATCH}). 그리고 복원된
 *       outbox(백업 시점의 tombstone)와 사본·계정이 맞아야 한다({@code TOMBSTONE_KEY_MISMATCH}) — 사본이 만들어지기 전에 요청된 살아 있는
 *       행은 사본에 있어야 하고, 복원된 outbox의 행은 지금 키로 계산한 어느 복원된 계정의 digest와 같아야 한다. 키가 틀리면 아무 계정도 맞지
 *       않아 적용이 조용히 "0건 일치"로 통과하기 때문이다. 어긋나면 아무것도 바꾸지 않고 실패한다.
 *   <li>복원된 모든 계정의 {@code user_id}를 tombstone 키로 digest해 사본과 맞추고, 맞은 계정 가운데 아직 {@code DELETED}가 아닌 것을
 *       {@link AccountErasure}로 다시 지운다(계정마다 한 트랜잭션). 이미 {@code DELETED}면 그대로다.
 *   <li>사본의 tombstone 전부를 이 DB의 outbox로 옮긴다 — 복원된 DB의 다음 내보내기가 그 tombstone을 잃지 않게. 같은 요청의 행이 이미
 *       있으면 사본의 더 진전된 상태를 병합하고, 열린 집합 가운데 이 DB에서 닫힐 길이 없는 것은 지금 닫는다(Story 5.3).
 *   <li>인증 세션을 모두 지운다 — 백업 시점의 세션으로 아무도 들어오지 못하게.
 *   <li>불변식({@link RestoreInvariants})과 자격정보 표본 복호({@link SourceConnectionApi#probeCredentials})를 센다.
 *   <li>감사 원장에 수만 담은 SYSTEM 행 하나를 남긴다(Story 5.3 — 통과면 {@code RESTORE_APPLIED}, 아니면 {@code RESTORE_REFUSED}).
 *       대조가 실패해 적용하지 않았으면 남기지 않는다(아무것도 바꾸지 않는다).
 * </ol>
 *
 * <p><b>다시 돌려도 안전하다.</b> 모든 단계가 멈춘 자리에서 이어진다 — 다시 지우기는 이미 {@code DELETED}인 계정을 건너뛰고, 옮기기는 이미
 * 있는 행을 그대로 두고, 세션 폐기는 0행이어도 실패가 아니다. 그래서 중간에 멈춘 적용은 같은 사본으로 다시 돌리면 된다.
 *
 * <p>결과는 수만 담는다. 식별자·digest·평문은 보고에 없다.
 */
@Component
class RestoreApplication {

    /** 자격정보 복호 표본의 크기(key version마다의 최신 행에 더해). */
    static final int CREDENTIAL_SAMPLE = 20;

    /** 사본 시각 대조의 여유. 내보내기 역할과 복원 역할의 시계·트랜잭션 시각 차이를 덮는다. */
    static final Duration CLOCK_SLACK = Duration.ofHours(1);

    /** 실패 code. 보고 줄의 {@code codes=}에 실린다. */
    static final String KEY_ID_MISMATCH = "TOMBSTONE_KEY_ID_MISMATCH";
    static final String KEY_MISMATCH = "TOMBSTONE_KEY_MISMATCH";
    static final String INVARIANT_VIOLATED = "INVARIANT_VIOLATED";
    static final String CREDENTIALS_UNAVAILABLE = "CREDENTIALS_UNAVAILABLE";

    /**
     * 사본·복원된 outbox·계정의 대조.
     *
     * @param keyIdMatches 사본의 키 ID가 이 역할의 키와 같다
     * @param outboxMissingFromCopy 사본보다 먼저 요청된 살아 있는 outbox 행 가운데 사본에 없는 것
     * @param outboxWithoutAccount 지금 키로 계산한 어느 복원된 계정의 digest와도 맞지 않는 outbox 행
     */
    record Reconciliation(boolean keyIdMatches, int outboxMissingFromCopy, int outboxWithoutAccount) {

        boolean consistent() {
            return this.keyIdMatches && this.outboxMissingFromCopy == 0 && this.outboxWithoutAccount == 0;
        }
    }

    /**
     * 적용의 보고. 수만 담는다.
     *
     * @param tombstones 외부 사본의 tombstone 수
     * @param matched 그 가운데 복원된 계정과 맞은 수
     * @param erased 이번에 다시 지운 계정 수
     * @param alreadyDeleted 맞았지만 이미 {@code DELETED}였던 수
     * @param carried 사본의 tombstone 가운데 이번 적용이 이 DB의 outbox에 새로 넣은 수(다시 지운 계정의 것 포함, 이미 있던 것 제외)
     * @param sessions 지운 세션 수
     * @param reconciliation 사본·outbox·계정 대조
     * @param violations 불변식 위반. 대조가 실패해 적용하지 않았으면 {@code null}
     * @param credentials 자격정보 표본 복호. 적용하지 않았으면 {@code null}
     */
    record Report(int tombstones, int matched, int erased, int alreadyDeleted, int carried, int sessions,
            Reconciliation reconciliation, RestoreInvariants.@org.jspecify.annotations.Nullable Violations violations,
            SourceConnectionApi.@org.jspecify.annotations.Nullable CredentialProbe credentials) {

        /** 실패 code. 비면 통과다. */
        List<String> codes() {
            List<String> codes = new ArrayList<>();
            if (!this.reconciliation.keyIdMatches()) {
                codes.add(KEY_ID_MISMATCH);
            } else if (!this.reconciliation.consistent()) {
                codes.add(KEY_MISMATCH);
            }
            if (this.violations != null && this.violations.total() != 0) {
                codes.add(INVARIANT_VIOLATED);
            }
            if (this.credentials != null && !this.credentials.passed()) {
                codes.add(CREDENTIALS_UNAVAILABLE);
            }
            return codes;
        }

        /** 서비스를 열어도 되는가. 대조가 실패했으면(적용하지 않았으면) 아니다. */
        boolean passed() {
            return this.violations != null && this.credentials != null && codes().isEmpty();
        }

        /** 로그 한 줄. 수와 code만 있다. */
        String line() {
            StringBuilder line = new StringBuilder("restore.apply tombstones=").append(this.tombstones)
                    .append(" matched=").append(this.matched).append(" erased=").append(this.erased)
                    .append(" already_deleted=").append(this.alreadyDeleted).append(" carried=").append(this.carried)
                    .append(" sessions=").append(this.sessions)
                    .append(" key_id=").append(this.reconciliation.keyIdMatches() ? "MATCHED" : "MISMATCHED")
                    .append(" outbox_missing_from_copy=").append(this.reconciliation.outboxMissingFromCopy())
                    .append(" outbox_without_account=").append(this.reconciliation.outboxWithoutAccount());
            if (this.violations != null) {
                line.append(" active_plan_violations=").append(this.violations.activePlans())
                        .append(" official_bundle_violations=").append(this.violations.officialBundles())
                        .append(" portfolio_pointer_violations=").append(this.violations.portfolioPointers())
                        .append(" unfinished_deletions=").append(this.violations.unfinishedDeletions())
                        .append(" residual_rows=").append(this.violations.residualRows());
                // 모듈별 잔여(Story 5.4). 리허설이 각 값을 0으로 단정한다 — 합 하나로는 어느 모듈이 남겼는지 보이지 않는다.
                this.violations.residuals().forEach((module, count) ->
                        line.append(" residual_").append(module).append('=').append(count));
            }
            if (this.credentials != null) {
                line.append(" credentials_opened=").append(this.credentials.opened()).append('/')
                        .append(this.credentials.sampled()).append(" active_key=")
                        .append(this.credentials.activeKey() ? "PRESENT" : "MISSING");
            }
            List<String> codes = codes();
            line.append(" result=").append(passed() ? "PASSED" : "FAILED");
            if (!codes.isEmpty()) {
                line.append(" codes=").append(String.join(",", codes));
            }
            return line.toString();
        }
    }

    private final AccountDeletionApi accounts;

    private final DeletionTombstoneApi tombstones;

    private final AccountErasure erasure;

    private final RestoreInvariants invariants;

    private final SourceConnectionApi sources;

    private final TransactionTemplate transaction;

    RestoreApplication(AccountDeletionApi accounts, DeletionTombstoneApi tombstones, AccountErasure erasure,
            RestoreInvariants invariants, SourceConnectionApi sources, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tombstones = tombstones;
        this.erasure = erasure;
        this.invariants = invariants;
        this.sources = sources;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 외부 사본을 대조하고 맞으면 적용하고 판정한다. 적용 단계의 실패는 예외다(그 계정의 삭제는 롤백된다). */
    Report apply(TombstoneFile copy) {
        List<UUID> restored = this.accounts.accounts();
        List<Tombstone> external = copy.tombstones();
        Reconciliation reconciliation = reconcile(copy, restored);
        if (!reconciliation.consistent()) {
            // 키가 틀리거나 사본이 이 DB와 어긋난다. 어느 것도 바꾸지 않는다.
            return new Report(external.size(), 0, 0, 0, 0, 0, reconciliation, null, null);
        }
        Map<UUID, Tombstone> matched = this.tombstones.matching(restored, external);
        Set<UUID> deleted = new HashSet<>(this.accounts.deletedAccounts());
        int erased = 0;
        int alreadyDeleted = 0;
        int carried = 0;
        Set<String> before = requestIds(this.tombstones.outbox());
        Set<String> reapplied = new HashSet<>();
        for (Map.Entry<UUID, Tombstone> entry : matched.entrySet()) {
            if (deleted.contains(entry.getKey())) {
                alreadyDeleted++;
                continue;
            }
            if (this.erasure.reapply(entry.getKey(), entry.getValue()) != AccountErasure.Outcome.ERASED) {
                throw new IllegalStateException("tombstone이 가리키는 계정을 다시 지우지 못했다.");
            }
            reapplied.add(entry.getValue().requestId());
            erased++;
        }
        this.transaction.executeWithoutResult(status -> external.forEach(this.tombstones::carry));
        // Story 5.3: 열린 집합으로 옮긴 tombstone 가운데 이 DB에서 닫힐 길이 없는 것(맞는 계정이 없거나 이미 DELETED라 다시 지우지 않았다)은
        // 지금 닫는다 — 영향 generation은 지금까지의 상위 집합이다. 다시 지운 계정의 것은 reapply가 이미 닫았다.
        List<String> unreachable = new ArrayList<>();
        for (Tombstone tombstone : external) {
            if (tombstone.set() == DeletionTombstoneApi.GenerationSet.OPEN && !reapplied.contains(tombstone.requestId())) {
                unreachable.add(tombstone.requestId());
            }
        }
        if (!unreachable.isEmpty()) {
            this.tombstones.closeRestored(unreachable);
        }
        // 다시 지울 때 옮긴 행도 "이번 적용이 새로 넣은 것"이다 — 적용 전 outbox에 없던 요청 ID가 지금 있으면 센다.
        Set<String> after = requestIds(this.tombstones.outbox());
        for (Tombstone tombstone : external) {
            if (!before.contains(tombstone.requestId()) && after.contains(tombstone.requestId())) {
                carried++;
            }
        }
        int sessions = this.accounts.revokeAllSessions();
        Report report = new Report(external.size(), matched.size(), erased, alreadyDeleted, carried, sessions,
                reconciliation, this.invariants.check(), this.sources.probeCredentials(CREDENTIAL_SAMPLE));
        // 감사 SYSTEM 행(Story 5.3): 수와 통과 여부만. 식별자·digest는 없다.
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("tombstones", report.tombstones());
        counts.put("matched", report.matched());
        counts.put("erased", report.erased());
        counts.put("alreadyDeleted", report.alreadyDeleted());
        counts.put("carried", report.carried());
        counts.put("sessions", report.sessions());
        this.tombstones.recordRestoreApplied(counts, report.passed());
        return report;
    }

    /** 아무것도 쓰기 전의 대조. */
    private Reconciliation reconcile(TombstoneFile copy, List<UUID> restored) {
        if (!copy.keyId().equals(this.tombstones.keyId())) {
            return new Reconciliation(false, 0, 0);
        }
        // 이 DB 계보의 삭제가 쓴 행만 본다 — 앞선 복원 적용이 사본에서 옮긴 행(백업 뒤 생기고 지워진 계정)은 맞는 계정이 없는 것이 정상이다.
        List<Tombstone> outbox = this.tombstones.recordedOutbox();
        Set<String> inCopy = new HashSet<>();
        for (Tombstone tombstone : copy.tombstones()) {
            inCopy.add(tombstone.userDigest());
        }
        int missing = 0;
        for (Tombstone row : outbox) {
            // 사본이 만들어지기 전에 요청됐고, 그 사본이 실을 만큼 기한이 남았다 — 그런데 사본에 없다.
            boolean beforeCopy = row.requestedAt().isBefore(copy.exportedAt().minus(CLOCK_SLACK));
            // 백업 파기가 완료되지 않은 행은 기한과 무관하게 사본에 실린다(Story 5.3).
            boolean exportable = row.status() != DeletionTombstoneApi.DestructionStatus.DESTROYED || row.destroyAfter()
                    .isAfter(copy.exportedAt().plus(DeletionTombstoneApi.EXPORT_MARGIN).plus(CLOCK_SLACK));
            if (beforeCopy && exportable && !inCopy.contains(row.userDigest())) {
                missing++;
            }
        }
        Set<Tombstone> owned = new HashSet<>(this.tombstones.matching(restored, outbox).values());
        int orphaned = 0;
        for (Tombstone row : outbox) {
            if (!owned.contains(row)) {
                orphaned++;
            }
        }
        return new Reconciliation(true, missing, orphaned);
    }

    private static Set<String> requestIds(List<Tombstone> rows) {
        Set<String> ids = new HashSet<>();
        for (Tombstone row : rows) {
            ids.add(row.requestId());
        }
        return ids;
    }
}
