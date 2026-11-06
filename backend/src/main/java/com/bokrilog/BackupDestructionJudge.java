package com.bokrilog;

import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.operations.BackupDestructionApi;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 삭제된 계정이 든 백업의 파기 판정과 그 gauge(Story 5.3). 스케줄링 역할({@code worker}·{@code all})에만 있다
 * ({@link BokrilogRole.Schedules}). <b>production에서도 돈다</b> — 판정은 기록이지 삭제가 아니다(계정 삭제 실행의 production 차단과 별개다).
 *
 * <p><b>한 번의 판정.</b> (1) 집합이 열린 tombstone의 요청 ID를 operations에서 읽고, (2) 그 요청의 운영 데이터 삭제 완료 시각을 identity 공개
 * API({@link AccountDeletionRunApi#operationalCompletions})에서 읽어, (3) operations의 {@link BackupDestructionApi#judge}에 넘긴다 — 열린
 * 집합 닫기·영향 generation 기록·완료·초과 전환·감사 행은 tombstone마다 짧은 트랜잭션 하나다(잠금 대기·문장 상한, 잠긴 행은 다음 주기). operations는 identity에 닿지 않고 루트가 잇는다. (4) 백업
 * 파기가 완료된 요청을 identity의 전체 삭제 완료로 옮기고({@link #completeNow}) 완료 확인 세션을 정리한다({@link #scrubNow}, Story 5.4).
 * (5) gauge 값을 다시 읽는다.
 *
 * <p><b>gauge.</b> 이름·tag는 {@link TelemetryAllowlist}의 표와 같다.
 * <ul>
 *   <li>{@link #DUE_SOON} {@code bokrilog_deletion_backup_destruction_due_soon} — 백업 파기 기한 48시간 안의 미완료 수. 경보
 *       {@code DeletionBackupDestructionDueSoon}.
 *   <li>{@link #OVERDUE} {@code bokrilog_deletion_backup_destruction_overdue} — 기한이 지난 미완료 수. 경보
 *       {@code DeletionBackupDestructionOverdue}.
 *   <li>{@link #EXPORT_LAST_SUCCESS} {@code bokrilog_tombstone_export_last_success_timestamp_seconds} — 마지막 tombstone 외부 사본 성공
 *       기록의 DB 시각(epoch 초). 경보 {@code TombstoneExportStale}(1시간).
 * </ul>
 * 처음 읽기에 성공한 뒤에 등록한다 — 읽지 못한 값을 NaN으로 내지 않는다(그러면 {@code absent()} 미측정 경보가 울린다). 내보내기 성공 시각은
 * 기록이 있어야 등록한다. 한 번 낸 값은 다음 읽기가 실패해도 그대로 둔다(성공 시각은 늘 "적어도 그때 성공했다"는 참인 값이고 나이가 계속
 * 자라 경보가 제때 울린다 — {@code BackupMetrics}와 같은 관용).
 *
 * <p>판정은 가상 스레드에서 돌고 겹치지 않는다. 실패는 WARN 한 줄(예외의 단순 이름만)이고 다음 주기가 다시 판정한다.
 *
 * <p><b>스케줄 스위치.</b> {@code bokrilog.backup-destruction.judge.scheduled}(기본 참)가 거짓이면 주기 판정만 쉰다 — 백엔드 시험이 다른
 * 시험의 tombstone·감사 행을 바꾸지 않게 끄고, 이 판정의 시험은 {@link #judgeNow}로 한 번씩 몬다. 운영 값은 바꾸지 않는다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(BokrilogRole.Schedules.class)
class BackupDestructionJudge {

    static final String DUE_SOON = "bokrilog.deletion.backup.destruction.due.soon";

    static final String OVERDUE = "bokrilog.deletion.backup.destruction.overdue";

    static final String EXPORT_LAST_SUCCESS = "bokrilog.tombstone.export.last.success.timestamp";

    /**
     * 판정 주기({@code bokrilog.backup-destruction.judge.period}, 기본 5분). 백업은 하루 한 번이고 내보내기는 15분이다 — 정지 경보(1시간)보다
     * 충분히 잦다. 리허설만 짧게 준다(판정을 기다리는 시간을 줄인다).
     */
    static final String PERIOD = "${bokrilog.backup-destruction.judge.period:PT5M}";

    private static final Logger LOG = LoggerFactory.getLogger(BackupDestructionJudge.class);

    private final BackupDestructionApi destruction;

    private final AccountDeletionRunApi runs;

    /** 완료 확인 세션의 정리(Story 5.4). */
    private final AccountDeletionApi accounts;

    private final MeterRegistry registry;

    private final boolean scheduled;

    private final AtomicBoolean judging = new AtomicBoolean();

    private final AtomicLong dueSoon = new AtomicLong();

    private final AtomicLong overdue = new AtomicLong();

    private final AtomicBoolean countsRegistered = new AtomicBoolean();

    /** 마지막 내보내기 성공 시각의 double 비트. 등록 전에는 쓰이지 않는다. */
    private final AtomicLong lastExport = new AtomicLong();

    private final AtomicBoolean lastExportRegistered = new AtomicBoolean();

    /** 법정 분리 보존 항목(Story 5.4). 서버 정책 상수이고 시험만 {@link #reinterpretLegalRetention}으로 바꾼다. */
    private volatile List<AccountDeletionPolicy.LegalRetention> legalRetention = AccountDeletionPolicy.LEGAL_RETENTION;

    BackupDestructionJudge(BackupDestructionApi destruction, AccountDeletionRunApi runs,
            AccountDeletionApi accounts, MeterRegistry registry,
            @Value("${bokrilog.backup-destruction.judge.scheduled:true}") boolean scheduled) {
        this.accounts = accounts;
        this.destruction = destruction;
        this.runs = runs;
        this.registry = registry;
        this.scheduled = scheduled;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = PERIOD)
    void judge() {
        if (!this.scheduled || !this.judging.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("backup-destruction-judge").start(() -> {
            try {
                judgeNow();
            } catch (RuntimeException failed) {
                // 다음 주기가 다시 판정한다. 메시지·원인은 남기지 않는다 — SQL이 실린다. 종류만.
                LOG.atWarn().addKeyValue("exception", failed.getClass().getSimpleName())
                        .log("backup destruction judge failed");
            } finally {
                this.judging.set(false);
            }
        });
    }

    /**
     * 지금 한 번 판정하고 gauge를 다시 읽는다. 판정이 실패하면 예외로 올라가지만 그 전에 gauge는 다시 읽어 본다.
     *
     * @return 판정의 전환 수
     */
    BackupDestructionApi.Judgement judgeNow() {
        try {
            List<String> open = this.destruction.openRequestIds();
            Map<String, Instant> completions = open.isEmpty() ? Map.of() : this.runs.operationalCompletions(open);
            BackupDestructionApi.Judgement judgement = this.destruction.judge(completions);
            // 전환 종류마다 한 줄(수와 code만, 식별자 없음). 전환이 없으면 남기지 않는다.
            logTransition("RETENTION_EXTENDED", judgement.closed());
            logTransition("BACKUP_DESTROYED", judgement.destroyed());
            logTransition("BACKUP_OVERDUE", judgement.overdue());
            logTransition("DELETION_COMPLETED", completeSafely());
            return judgement;
        } finally {
            // 완료 세션 정리는 파기 판정·전체 완료 기록과 무관하다 — 앞 단계가 실패해도 돈다(자기 실패는 스스로 접는다).
            logTransition("COMPLETION_SESSION_SCRUBBED", scrubNow());
            measureNow();
        }
    }

    /** {@link #completeNow}의 실패를 접는다 — WARN 한 줄(예외의 단순 이름, 식별자 없음)이고 다음 주기가 다시 맞춰 본다. */
    private int completeSafely() {
        try {
            return completeNow();
        } catch (RuntimeException failed) {
            LOG.atWarn().addKeyValue("exception", failed.getClass().getSimpleName())
                    .log("deletion completion record failed");
            return 0;
        }
    }

    /**
     * 전체 삭제 완료의 기록(Story 5.4). identity의 실행 가운데 운영 데이터 삭제가 끝났고 아직 전체 완료가 아닌 요청을 읽어, 그 요청의 백업
     * 파기가 완료({@code DESTROYED})됐고 법정 분리 보존이 처리됐을 때만 identity에 완료를 넘긴다. 이번 판정이 막 {@code DESTROYED}로 바꾼
     * 요청과 앞선 판정·복원이 이미 {@code DESTROYED}로 둔 요청을 함께 잡는다(매번 다시 맞춰 보는 sweep). 단조이고 멱등이다 — identity가 운영
     * 완료인 실행만 바꾼다.
     *
     * <p><b>법정 분리 보존.</b> 승인된 보존 항목이 없으면(지금 — G-5 전 기본값) 처리할 것이 없으므로 충족이다. 항목이 하나라도 있으면 그
     * 분리 이동의 증거를 기록하는 곳이 아직 없으므로 충족이 아니고 완료를 기록하지 않는다 — 증거 없이 전체 완료로 쓰지 않는다.
     *
     * @return 이번에 전체 삭제 완료로 바꾼 실행 수
     */
    int completeNow() {
        if (!this.legalRetention.isEmpty()) {
            return 0;
        }
        List<String> awaiting = this.runs.awaitingDeletionCompletion();
        if (awaiting.isEmpty()) {
            return 0;
        }
        Set<String> destroyed = this.destruction.destroyedAmong(awaiting);
        return destroyed.isEmpty() ? 0 : this.runs.recordDeletionCompleted(destroyed);
    }

    /**
     * 완료 확인 세션의 정리(Story 5.4). 삭제 커밋과 겹친 같은 세션의 요청이 그 뒤 저장하면 옛 주체 이름이 한 번 되살아날 수 있다 — 판정
     * 주기마다 완료 연결이 있는 세션의 되살아난 주체 이름과 남기는 속성(CSRF 토큰·진행 중인 OAuth2 인가 요청) 밖의 속성을 비운다 — 원래
     * 사용자의 것만이고, 다른 사용자로 인증된 세션은 완료 연결만 지운다(identity 공개 API, 멱등, 짧은 트랜잭션, 잠긴 세션은 건너뛴다).
     * 실패는 판정을 막지 않는다 — WARN 한 줄(예외의 단순 이름)이고 다음 주기가 다시 한다.
     *
     * @return 이번에 비운 세션 수
     */
    int scrubNow() {
        try {
            return this.accounts.scrubCompletionSessions();
        } catch (RuntimeException failed) {
            LOG.atWarn().addKeyValue("exception", failed.getClass().getSimpleName())
                    .log("completion session scrub failed");
            return 0;
        }
    }

    /**
     * 법정 분리 보존 항목을 다시 해석한다 — <b>시험 훅</b>이다({@code SupportContact.reinterpret}와 같은 관용). 운영 값은 서버 정책 상수
     * ({@link AccountDeletionPolicy#LEGAL_RETENTION}) 하나이고 제품 경로에서 부르는 곳이 없다. 시험이 "보존 항목이 있으면 전체 완료를
     * 기록하지 않는다"를 같은 컨텍스트에서 본다.
     */
    void reinterpretLegalRetention(List<AccountDeletionPolicy.LegalRetention> items) {
        this.legalRetention = List.copyOf(items);
    }

    private static void logTransition(String outcome, int count) {
        if (count > 0) {
            LOG.atInfo().addKeyValue("count", count).addKeyValue("outcome", outcome).log("backup destruction judged");
        }
    }

    /** gauge 값을 다시 읽는다. 읽지 못하면 이미 낸 값을 그대로 두고 등록도 하지 않는다. */
    synchronized void measureNow() {
        BackupDestructionApi.Measurement measurement;
        try {
            measurement = this.destruction.measure();
        } catch (RuntimeException unreadable) {
            LOG.atWarn().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("backup destruction unreadable");
            return;
        }
        this.dueSoon.set(Double.doubleToLongBits(measurement.dueSoon()));
        this.overdue.set(Double.doubleToLongBits(measurement.overdue()));
        if (this.countsRegistered.compareAndSet(false, true)) {
            Gauge.builder(DUE_SOON, this.dueSoon, held -> Double.longBitsToDouble(held.get()))
                    .description("백업 파기 기한(요청 + 30일)까지 48시간 안이고 백업 파기가 완료되지 않은 삭제 수")
                    .register(this.registry);
            Gauge.builder(OVERDUE, this.overdue, held -> Double.longBitsToDouble(held.get()))
                    .description("백업 파기 기한(요청 + 30일)이 지났는데 백업 파기가 완료되지 않은 삭제 수")
                    .register(this.registry);
        }
        measurement.lastExportEpochSeconds().ifPresent(seconds -> {
            this.lastExport.set(Double.doubleToLongBits(seconds));
            if (this.lastExportRegistered.compareAndSet(false, true)) {
                Gauge.builder(EXPORT_LAST_SUCCESS, this.lastExport, held -> Double.longBitsToDouble(held.get()))
                        .baseUnit("seconds")
                        .description("마지막 tombstone 외부 사본 성공 기록의 DB 시각(epoch 초)")
                        .register(this.registry);
            }
        });
    }
}
