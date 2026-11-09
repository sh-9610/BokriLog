package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.operations.BackupDestructionApi;
import com.bokrilog.operations.DeletionTombstoneApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 5.3: 삭제된 계정이 든 백업의 파기 판정({@link BackupDestructionJudge} → {@code BackupDestructionApi}) — 실제 DB에서 백업 기록
 * (V24)과 삭제 실행 기록(V31)을 손으로 쌓고 판정을 한 번씩 몬다(주기 실행은 시험 컨텍스트에서 꺼져 있다).
 *
 * <p>매트릭스: 운영 완료(V31)가 열린 집합을 닫고 보존을 늘린다 · 운영 완료 전에 dump를 시작한 저장된 generation만 영향 generation이다(요청
 * 전에 두 저장소에서 파기된 것·운영 완료 뒤의 것은 아니다) · 두 저장소 {@code PURGED}가 모두 있어야 {@code DESTROYED}이고 그 시각은 운영
 * 완료와 다르다 · 한쪽 {@code PURGE_FAILED}는 완료가 아니고 기한이 지나면 {@code OVERDUE}·감사 한 번 · 결과 없는 실행 중 generation은
 * 완료를 막는다 · 열린 집합은 완료가 아니다 · gauge(기한 임박·초과·내보내기 성공 시각) · 감사 행에 식별자가 없다 · 커밋 여유(운영 완료 +
 * 1시간) 경계 · 성공 기록보다 앞선 파기 기록 · 잠긴 tombstone 건너뛰기 · 완료 행은 그 상태로 성공한 사본에 실린 뒤에야 지운다.
 */
class BackupDestructionJudgeTest extends PlanSyncTestBase {

    private static final DateTimeFormatter GENERATION = DateTimeFormatter.ofPattern("'gen_'yyyyMMdd").withZone(ZoneOffset.UTC);

    @Autowired BackupDestructionJudge judge;

    @Autowired DeletionTombstoneApi tombstones;

    @Autowired BackupDestructionApi destruction;

    @Autowired AccountDeletionRunApi runs;

    /** 매트릭스 "운영 완료"·"파기 완료"·"한쪽 실패": 닫힌 집합의 모든 generation이 두 저장소에서 파기돼야 완료다. */
    @Test
    void 운영_완료가_집합을_닫고_두_저장소_파기가_모두_확인돼야_백업_파기_완료다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        Instant requested = now.minus(Duration.ofDays(3));
        Instant erasedAt = requested.plus(Duration.ofHours(1));
        String request = "judge-done-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, requested, null);
        // V31: 그 요청의 실행이 운영 데이터 삭제를 마쳤다(요청 행은 이미 지워졌다).
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        jdbc().sql("INSERT INTO identity_deletion_run (user_id, status, operational_erased_at, request_id) "
                        + "VALUES (:u, 'OPERATIONAL_DATA_ERASED', :t, :r)")
                .param("u", user).param("t", utc(erasedAt)).param("r", request).update();

        String old = generationOf(now.minus(Duration.ofDays(45)));
        String before = generationOf(now.minus(Duration.ofDays(5)));
        String during = generationOf(now.minus(Duration.ofDays(3)));
        String after = generationOf(now.minus(Duration.ofDays(2)));
        // 요청 전에 두 저장소에서 이미 파기된 generation — 이 삭제가 지울 것이 아니다.
        stored(old, now.minus(Duration.ofDays(45)));
        purged(old, "oci", "AGE", now.minus(Duration.ofDays(16)));
        purged(old, "offsite", "AGE", now.minus(Duration.ofDays(16)));
        // 요청 전 dump(계정이 든다)와 운영 완료 전 dump(계정이 든다), 운영 완료 뒤 dump(계정이 없다).
        stored(before, now.minus(Duration.ofDays(5)));
        stored(during, requested.plus(Duration.ofMinutes(30)));
        stored(after, erasedAt.plus(Duration.ofHours(2)));
        purged(before, "oci", "AGE", now);
        purged(before, "offsite", "AGE", now);
        purged(during, "oci", "AGE", now);
        event(during, "PURGE_FAILED", "offsite", "DELETE_FAILED", now);
        // incoming 정리·로컬 임시본은 generation 파기가 아니다.
        purged(during, "offsite", "INCOMING_CLEARED", now);

        BackupDestructionApi.Judgement first = this.judge.judgeNow();
        assertThat(first).isEqualTo(new BackupDestructionApi.Judgement(1, 0, 0));
        assertThat(generationsOf(request)).containsExactly(before + ":true:true", during + ":true:false");
        var closed = row(request);
        assertThat(closed.status()).isEqualTo("PENDING");
        assertThat(closed.closedAt()).isEqualTo(erasedAt);
        assertThat(closed.destroyAfter()).isEqualTo(erasedAt.plus(DeletionTombstoneApi.RETENTION));

        // 한쪽 실패는 다음 판정에서도 완료가 아니다.
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(row(request).status()).isEqualTo("PENDING");

        // 다음 백업 실행의 파기가 OCI 밖도 지웠다.
        purged(during, "offsite", "AGE", now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        var destroyed = row(request);
        assertThat(destroyed.status()).isEqualTo("DESTROYED");
        assertThat(destroyed.destroyedAt()).isNotNull().isNotEqualTo(destroyed.closedAt()).isAfter(erasedAt);
        assertThat(generationsOf(request)).containsExactly(before + ":true:true", during + ":true:true");
        // 완료는 끝이다 — 다시 판정해도 전환이 없다.
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));

        assertThat(auditReasons()).containsExactly("RETENTION_EXTENDED:COMPLETED", "BACKUP_DESTROYED:COMPLETED");
        assertNoIdentifiersInAudit(request);
        // 외부 사본 줄에 상태와 영향 generation이 실린다.
        assertThat(this.tombstones.outbox()).singleElement().satisfies(tombstone -> {
            assertThat(tombstone.status()).isEqualTo(DeletionTombstoneApi.DestructionStatus.DESTROYED);
            assertThat(tombstone.set()).isEqualTo(DeletionTombstoneApi.GenerationSet.CLOSED);
            assertThat(tombstone.generations()).containsExactly(new DeletionTombstoneApi.AffectedGeneration(before, true, true),
                    new DeletionTombstoneApi.AffectedGeneration(during, true, true));
            assertThat(tombstone.line()).contains(" DESTROYED CLOSED " + before + ":PP," + during + ":PP");
        });
    }

    /** 매트릭스 "기한 초과": 요청 + 30일이 지났는데 한쪽 저장소만 파기됐으면 OVERDUE·감사 한 번·gauge이고 완료로 표시하지 않는다. */
    @Test
    void 기한이_지난_미완료는_초과이고_감사는_한_번이다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        Instant requested = now.minus(Duration.ofDays(31));
        String request = "judge-late-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, requested, requested.plus(Duration.ofHours(1)));
        String generation = generationOf(now.minus(Duration.ofDays(32)));
        stored(generation, now.minus(Duration.ofDays(32)));
        purged(generation, "oci", "AGE", now.minus(Duration.ofDays(3)));
        event(generation, "PURGE_FAILED", "offsite", "DELETE_FAILED", now.minus(Duration.ofDays(3)));

        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 1));
        var overdue = row(request);
        assertThat(overdue.status()).isEqualTo("OVERDUE");
        assertThat(overdue.overdueAt()).isNotNull();
        assertThat(overdue.destroyedAt()).isNull();
        assertThat(this.judge.judgeNow()).as("감사는 한 번").isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(auditReasons()).containsExactly("BACKUP_OVERDUE:FAILED");
        assertThat(gauge(BackupDestructionJudge.OVERDUE)).isEqualTo(1.0);
        assertThat(gauge(BackupDestructionJudge.DUE_SOON)).isZero();
        // 백업을 앞당겨 지우지 않는다 — 다음 백업 실행의 파기가 재시도이고, 그것이 끝나면 초과도 완료가 된다.
        purged(generation, "offsite", "AGE", now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(row(request).status()).isEqualTo("DESTROYED");
        assertThat(row(request).overdueAt()).as("초과 이력은 남는다").isNotNull();
        assertThat(gauge(BackupDestructionJudge.OVERDUE)).isZero();
        assertThat(auditReasons()).containsExactly("BACKUP_OVERDUE:FAILED", "BACKUP_DESTROYED:COMPLETED");
        assertNoIdentifiersInAudit(request);
    }

    /** 열린 집합(운영 완료 전)은 기록이 없어도 완료가 아니고, 기한 48시간 안이면 임박 gauge에 든다. */
    @Test
    void 열린_집합은_완료가_아니고_기한_임박에_든다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String request = "judge-open-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, now.minus(Duration.ofDays(29)), null);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(row(request).status()).isEqualTo("PENDING");
        assertThat(gauge(BackupDestructionJudge.DUE_SOON)).isEqualTo(1.0);
        assertThat(gauge(BackupDestructionJudge.OVERDUE)).isZero();
    }

    /** 결과가 아직 없는 실행 중 generation은 저장될지 몰라 완료를 막는다. 실패로 끝나면(저장되지 않았다) 막지 않는다. */
    @Test
    void 결과_없는_실행_중_generation은_완료를_막는다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String request = "judge-inflight-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1)));
        String running = generationOf(now);
        event(running, "STARTED", null, null, now.minus(Duration.ofHours(2)));
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(row(request).status()).isEqualTo("PENDING");
        assertThat(generationsOf(request)).isEmpty();

        event(running, "FAILED", "offsite", "UPLOAD_OFFSITE", now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(row(request).status()).isEqualTo("DESTROYED");
    }

    /**
     * 내보내기 성공 기록이 있어야 gauge가 생기고, 그 값은 기록의 DB 시각이다. 감사 행은 수뿐이다. 판정자는 전용 registry로 새로 만든다 —
     * 컨텍스트 공유 registry의 등록 상태(앞선 시험 순서)에 기대지 않는다.
     */
    @Test
    void 내보내기_성공_시각이_gauge가_된다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BackupDestructionJudge fresh = new BackupDestructionJudge(this.destruction, this.runs, accountDeletion(), registry, false);
        fresh.measureNow();
        assertThat(registry.find(BackupDestructionJudge.EXPORT_LAST_SUCCESS).gauges())
                .as("기록이 없으면 등록하지 않는다(NaN 없음)").isEmpty();
        this.tombstones.confirmExport(3, this.tombstones.export().exportId());
        fresh.measureNow();
        double seconds = registry.get(BackupDestructionJudge.EXPORT_LAST_SUCCESS).gauge().value();
        assertThat(Math.abs(seconds - databaseNow().getEpochSecond())).isLessThan(60.0);
        assertThat(auditReasons()).containsExactly("EXPORTED:COMPLETED");
        assertThat(jdbc().sql("SELECT CAST(details AS text) FROM operations_audit_event WHERE command = 'tombstone'")
                .query(String.class).single()).isEqualTo("{\"count\": 3}");
    }

    /**
     * P4: 요청보다 아주 오래전(90일 넘게)에 시작했는데 아직 두 저장소 파기 기록이 없는 저장된 generation도 영향 generation이다 — 기록에 하한이
     * 없어 거짓 완료가 나지 않는다. 요청 전에 두 저장소에서 이미 파기된 오래된 generation은 여전히 빠진다.
     */
    @Test
    void 아주_오래된_미파기_generation도_완료를_막는다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        Instant requested = now.minus(Duration.ofDays(2));
        String request = "judge-ancient-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, requested, requested.plus(Duration.ofHours(1)));
        String ancient = generationOf(now.minus(Duration.ofDays(200)));
        String purgedLongAgo = generationOf(now.minus(Duration.ofDays(150)));
        stored(ancient, now.minus(Duration.ofDays(200)));
        purged(ancient, "oci", "AGE", now.minus(Duration.ofDays(170)));
        stored(purgedLongAgo, now.minus(Duration.ofDays(150)));
        purged(purgedLongAgo, "oci", "AGE", now.minus(Duration.ofDays(120)));
        purged(purgedLongAgo, "offsite", "AGE", now.minus(Duration.ofDays(120)));

        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(generationsOf(request)).containsExactly(ancient + ":true:false");
        assertThat(row(request).status()).isEqualTo("PENDING");

        purged(ancient, "offsite", "AGE", now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
    }

    /**
     * P5: 결과가 없는 시작 기록은 시간이 오래 지나도 그보다 뒤의 {@code STARTED}가 기록될 때까지 완료를 막는다(백업 절차는 대기열의 결과 행을
     * 먼저 기록한 뒤 다음 실행을 시작한다). 뒤 시작 기록이 생기면 — 그것이 운영 완료 뒤라 영향 generation이 아니어도 — 더 막지 않는다.
     */
    @Test
    void 결과_없는_시작은_뒤_시작_기록이_생길_때까지_막는다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String request = "judge-order-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, now.minus(Duration.ofDays(12)), now.minus(Duration.ofDays(11)));
        String stuck = generationOf(now.minus(Duration.ofDays(11)));
        event(stuck, "STARTED", null, null, now.minus(Duration.ofDays(11)).minus(Duration.ofHours(1)));
        assertThat(this.judge.judgeNow()).as("열흘이 지나도 뒤 시작이 없으면 막는다")
                .isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(row(request).status()).isEqualTo("PENDING");

        // 다음 실행이 시작했다(운영 완료 뒤 dump — 이 삭제의 영향 generation이 아니다). 결과 없는 앞 시작은 더 막지 않는다.
        String next = generationOf(now);
        event(next, "STARTED", null, null, now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(generationsOf(request)).isEmpty();
    }

    /** P6: V24 CHECK를 통과한 무효 날짜의 generation ID도 판정을 멈추지 않고 다른 generation처럼(보수적으로) 다룬다. */
    @Test
    void 무효_날짜의_generation도_판정을_멈추지_않고_완료를_막는다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String blocked = "judge-baddate-" + UUID.randomUUID().toString().substring(0, 8);
        String other = "judge-other-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(blocked, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));
        stored("gen_20261399", now.minus(Duration.ofDays(4)));
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(generationsOf(blocked)).containsExactly("gen_20261399:false:false");
        // 같은 판정에서 다른 tombstone도 계속 판정된다(요청이 그 generation보다 뒤가 아니면 영향 없음).
        insertTombstone(other, now.minus(Duration.ofDays(5)), now.minus(Duration.ofDays(5)).plus(Duration.ofHours(1)));
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(row(other).status()).isEqualTo("DESTROYED");
        assertThat(row(blocked).status()).isEqualTo("PENDING");
    }

    /**
     * P1·Q1: 기한(+35일)이 지나도 백업 파기가 완료가 아닌 tombstone은 지워지지 않고 외부 사본에 실린다 — 오래 멈춘 실행의 첫 단계 재기록이
     * 이미 지난 보존 기한으로 들어가도 같다. 완료된 행은 그 상태로 성공한 사본에 한 번 실린 뒤에야 기한이 지나면 지워지고 그 수가 감사 행
     * ({@code EXPIRED_PURGED})에 남는다.
     */
    @Test
    void 기한이_지나도_미완료_tombstone은_남고_실리며_완료된_것만_지운다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        Instant longAgo = databaseNow().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(40));
        String stalled = "judge-stalled-" + UUID.randomUUID().toString().substring(0, 8);
        new TransactionTemplate(this.manager).executeWithoutResult(status -> this.tombstones.record(user, stalled, longAgo));
        String done = "judge-done-old-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc().sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin, "
                        + "status, closed_at, destroyed_at) VALUES (:r, :d, :a, :b, 'DELETION', 'DESTROYED', :a, :a)")
                .param("r", done).param("d", sha256("judge-digest-" + done)).param("a", utc(longAgo))
                .param("b", utc(longAgo.plus(DeletionTombstoneApi.RETENTION))).update();

        // 완료 행이 아직 그 상태로 성공한 사본에 실린 적이 없다 — 이번 사본에 DESTROYED로 싣고 지우지 않는다.
        DeletionTombstoneApi.Export carrying = this.tombstones.export();
        assertThat(carrying.purged()).isZero();
        assertThat(carrying.live()).extracting(DeletionTombstoneApi.Tombstone::requestId)
                .containsExactlyInAnyOrder(stalled, done);
        this.tombstones.confirmExport(carrying.live().size(), carrying.exportId());

        DeletionTombstoneApi.Export export = this.tombstones.export();
        assertThat(export.purged()).isEqualTo(1);
        assertThat(export.live()).extracting(DeletionTombstoneApi.Tombstone::requestId).containsExactly(stalled);
        assertThat(this.tombstones.outbox()).extracting(DeletionTombstoneApi.Tombstone::requestId).containsExactly(stalled);
        assertThat(row(stalled).destroyAfter()).isBefore(databaseNow());
        assertThat(jdbc().sql("SELECT reason || ':' || CAST(details AS text) FROM operations_audit_event "
                        + "WHERE command = 'tombstone' AND reason = 'EXPIRED_PURGED'").query(String.class).single())
                .isEqualTo("EXPIRED_PURGED:{\"count\": 1}");
        // 판정은 그 행을 초과로 둘 뿐 지우지 않는다. 다음 내보내기에도 남는다.
        this.judge.judgeNow();
        assertThat(row(stalled).status()).isEqualTo("OVERDUE");
        assertThat(this.tombstones.export().live()).extracting(DeletionTombstoneApi.Tombstone::requestId)
                .containsExactly(stalled);
    }

    /**
     * P13·P14(복원 carry, 빈 DB): 외부 사본의 DESTROYED·OVERDUE·OPEN tombstone을 상태·닫힌 시각·초과·완료 시각·영향 generation과 저장소별
     * 파기 확인 그대로 옮긴다. 같은 요청의 행이 이미 있으면 사본의 더 진전된 상태를 단조로 병합한다.
     */
    @Test
    void 외부_사본을_상태와_파기_확인까지_옮기고_같은_요청은_병합한다() {
        Instant at = databaseNow().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(40));
        var destroyed = new DeletionTombstoneApi.Tombstone(sha256("carry-a"), "carry-destroyed", at,
                at.plus(Duration.ofDays(36)), DeletionTombstoneApi.DestructionStatus.DESTROYED,
                DeletionTombstoneApi.GenerationSet.CLOSED,
                List.of(new DeletionTombstoneApi.AffectedGeneration("gen_20250101", true, true)));
        var overdue = new DeletionTombstoneApi.Tombstone(sha256("carry-b"), "carry-overdue", at,
                at.plus(DeletionTombstoneApi.RETENTION), DeletionTombstoneApi.DestructionStatus.OVERDUE,
                DeletionTombstoneApi.GenerationSet.CLOSED,
                List.of(new DeletionTombstoneApi.AffectedGeneration("gen_20250102", true, false)));
        var open = new DeletionTombstoneApi.Tombstone(sha256("carry-c"), "carry-open", at,
                at.plus(DeletionTombstoneApi.RETENTION), DeletionTombstoneApi.DestructionStatus.PENDING,
                DeletionTombstoneApi.GenerationSet.OPEN, List.of());
        var template = new TransactionTemplate(this.manager);
        for (var tombstone : List.of(destroyed, overdue, open)) {
            assertThat(template.<Boolean>execute(status -> this.tombstones.carry(tombstone))).isTrue();
        }
        var d = row("carry-destroyed");
        assertThat(d.status()).isEqualTo("DESTROYED");
        assertThat(d.closedAt()).isEqualTo(at.plus(Duration.ofDays(1)));
        assertThat(d.destroyedAt()).isNotNull();
        assertThat(generationsOf("carry-destroyed")).containsExactly("gen_20250101:true:true");
        var o = row("carry-overdue");
        assertThat(o.status()).isEqualTo("OVERDUE");
        assertThat(o.overdueAt()).isEqualTo(at.plus(DeletionTombstoneApi.BACKUP_DEADLINE));
        assertThat(o.closedAt()).isEqualTo(at);
        assertThat(generationsOf("carry-overdue")).containsExactly("gen_20250102:true:false");
        var c = row("carry-open");
        assertThat(c.status()).isEqualTo("PENDING");
        assertThat(c.closedAt()).isNull();
        assertThat(jdbc().sql("SELECT string_agg(origin, ',') FROM operations_deletion_tombstone").query(String.class)
                .single()).isEqualTo("RESTORE,RESTORE,RESTORE");

        // 병합: 같은 요청·같은 계정의 더 진전된 사본(OVERDUE → DESTROYED, 열린 집합 → 닫힘, offsite 파기 확인, 더 긴 보존).
        var overdueLater = new DeletionTombstoneApi.Tombstone(overdue.userDigest(), overdue.requestId(), at,
                at.plus(Duration.ofDays(50)), DeletionTombstoneApi.DestructionStatus.DESTROYED,
                DeletionTombstoneApi.GenerationSet.CLOSED,
                List.of(new DeletionTombstoneApi.AffectedGeneration("gen_20250102", true, true),
                        new DeletionTombstoneApi.AffectedGeneration("gen_20250103", true, true)));
        var openLater = new DeletionTombstoneApi.Tombstone(open.userDigest(), open.requestId(), at,
                at.plus(Duration.ofDays(36)), DeletionTombstoneApi.DestructionStatus.OVERDUE,
                DeletionTombstoneApi.GenerationSet.CLOSED, List.of());
        assertThat(template.<Boolean>execute(status -> this.tombstones.carry(overdueLater))).isFalse();
        assertThat(template.<Boolean>execute(status -> this.tombstones.carry(openLater))).isFalse();
        var merged = row("carry-overdue");
        assertThat(merged.status()).isEqualTo("DESTROYED");
        assertThat(merged.destroyedAt()).isNotNull();
        assertThat(merged.overdueAt()).as("앞선 초과 이력은 남는다").isEqualTo(o.overdueAt());
        assertThat(merged.closedAt()).as("닫힌 시각은 이미 있던 값").isEqualTo(at);
        assertThat(merged.destroyAfter()).isEqualTo(at.plus(Duration.ofDays(50)));
        assertThat(generationsOf("carry-overdue")).containsExactly("gen_20250102:true:true", "gen_20250103:true:true");
        var mergedOpen = row("carry-open");
        assertThat(mergedOpen.status()).isEqualTo("OVERDUE");
        assertThat(mergedOpen.closedAt()).isEqualTo(at.plus(Duration.ofDays(1)));
        assertThat(mergedOpen.overdueAt()).isEqualTo(at.plus(DeletionTombstoneApi.BACKUP_DEADLINE));
        // 단조: 덜 진전된 사본은 아무것도 되돌리지 않는다.
        assertThat(template.<Boolean>execute(status -> this.tombstones.carry(overdue))).isFalse();
        assertThat(row("carry-overdue").status()).isEqualTo("DESTROYED");
        assertThat(row("carry-overdue").destroyAfter()).isEqualTo(at.plus(Duration.ofDays(50)));
    }

    /**
     * Q1: 미완료로 사본에 실린 행이 기한이 지난 뒤 백업 파기 완료가 되면, 다음 사본은 그 행을 DESTROYED로 싣는다(앞선 사본의 미완료 줄이 새
     * 사본에서 빠지지 않는다 — 줄어듦 검사가 영원히 막히지 않게). 그 사본의 성공이 기록되기 전에는 다시 내보내도 지우지 않고, 성공 뒤의
     * 내보내기가 비로소 지우고 뺀다. 모르는 번호·아직 준비되지 않은 번호의 성공 기록은 거부한다.
     */
    @Test
    void 미완료로_실린_행은_완료_상태로_성공한_사본에_한_번_실린_뒤에야_지운다() {
        Instant longAgo = databaseNow().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(40));
        String request = "judge-transit-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, longAgo, longAgo.plus(Duration.ofHours(1)));

        DeletionTombstoneApi.Export first = this.tombstones.export();
        assertThat(first.live()).singleElement().satisfies(tombstone -> {
            assertThat(tombstone.requestId()).isEqualTo(request);
            assertThat(tombstone.status()).isEqualTo(DeletionTombstoneApi.DestructionStatus.PENDING);
        });
        this.tombstones.confirmExport(1, first.exportId());

        // 판정이 백업 파기 완료로 바꿨다(보존 기한은 이미 지났다).
        jdbc().sql("UPDATE operations_deletion_tombstone SET status = 'DESTROYED', destroyed_at = statement_timestamp() "
                + "WHERE request_id = :r").param("r", request).update();
        DeletionTombstoneApi.Export second = this.tombstones.export();
        assertThat(second.purged()).isZero();
        assertThat(second.live()).singleElement().satisfies(tombstone -> {
            assertThat(tombstone.requestId()).isEqualTo(request);
            assertThat(tombstone.status()).isEqualTo(DeletionTombstoneApi.DestructionStatus.DESTROYED);
        });
        // 그 사본의 성공이 기록되지 않았다(업로드 실패 등) — 다음 내보내기도 지우지 않고 다시 싣는다.
        DeletionTombstoneApi.Export retried = this.tombstones.export();
        assertThat(retried.purged()).isZero();
        assertThat(retried.live()).extracting(DeletionTombstoneApi.Tombstone::requestId).containsExactly(request);
        assertThat(retried.exportId()).isGreaterThan(second.exportId());
        assertThatThrownBy(() -> this.tombstones.confirmExport(1, retried.exportId() + 1000))
                .as("준비되지 않은 번호").isInstanceOf(IllegalArgumentException.class);
        this.tombstones.confirmExport(1, retried.exportId());

        DeletionTombstoneApi.Export after = this.tombstones.export();
        assertThat(after.purged()).isEqualTo(1);
        assertThat(after.live()).isEmpty();
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone WHERE request_id = :r").param("r", request)
                .query(Integer.class).single()).isZero();
    }

    /**
     * Q2: 닫힌 집합의 경계는 운영 완료 + 커밋 여유({@link BackupDestructionApi#CLOSURE_SLACK}, 1시간)다 — 운영 완료 문장과 그 커밋 사이에
     * 시작한 dump는 삭제 전 snapshot을 담는다. 여유 안에 시작한 저장된 generation은 영향 generation이고 그 밖은 아니다. 여유가 지나기 전에는
     * 완료로 판정하지 않는다.
     */
    @Test
    void 운영_완료_뒤_커밋_여유_안에_시작한_dump도_영향_generation이고_여유가_지나야_완료다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        Instant closed = now.minus(Duration.ofDays(2));
        String request = "judge-slack-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, closed.minus(Duration.ofHours(1)), closed);
        String inside = generationOf(now.minus(Duration.ofDays(2)));
        String outside = generationOf(now.minus(Duration.ofDays(1)));
        stored(inside, closed.plus(BackupDestructionApi.CLOSURE_SLACK).minus(Duration.ofSeconds(1)));
        stored(outside, closed.plus(BackupDestructionApi.CLOSURE_SLACK));
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(generationsOf(request)).as("여유 경계 1초 안은 들고 경계 위는 들지 않는다").containsExactly(inside + ":false:false");
        purged(inside, "oci", "AGE", now);
        purged(inside, "offsite", "AGE", now);
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));

        // 운영 완료가 30분 전이면(여유 안) 영향 generation이 다 파기돼도 아직 완료가 아니다 — 그 사이에 시작할 dump가 아직 기록되지 않았을 수 있다.
        purged(outside, "oci", "AGE", now);
        purged(outside, "offsite", "AGE", now);
        String recent = "judge-recent-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(recent, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(30)));
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        assertThat(row(recent).status()).isEqualTo("PENDING");
        assertThat(generationsOf(recent)).containsExactly(inside + ":true:true", outside + ":true:true");
    }

    /**
     * Q3: 성공 기록이 있는 generation의 파기 기록은 순서와 무관하게 센다 — 백업 절차가 대기열의 성공 행을 같은 generation의 파기 행보다 늦게
     * 기록해도 두 저장소 파기로 인정한다. 성공 기록이 없는 generation의 파기 행은 영향 generation을 만들지 않는다.
     */
    @Test
    void 성공_기록보다_앞선_파기_기록도_센다() {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String request = "judge-reorder-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(request, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        String late = generationOf(now.minus(Duration.ofDays(4)));
        String neverStored = generationOf(now.minus(Duration.ofDays(5)));
        event(late, "STARTED", null, null, now.minus(Duration.ofDays(4)));
        // 파기 행(COUNT·BYTES)이 먼저 기록되고 성공 행이 대기열에서 나중에 기록됐다.
        purged(late, "oci", "COUNT", now.minus(Duration.ofHours(2)));
        purged(late, "offsite", "BYTES", now.minus(Duration.ofHours(2)));
        jdbc().sql("INSERT INTO operations_backup_event (generation_id, event, bytes, sha256, key_id, recorded_at) "
                        + "VALUES (:g, 'SUCCEEDED', 1024, :s, 'k1', :t)")
                .param("g", late).param("s", sha256("judge-cipher-" + late)).param("t", utc(now.minus(Duration.ofHours(1))))
                .update();
        // 성공 기록이 없는 generation의 파기 행(incoming 실패분이 아니라도) — 영향 generation이 아니다.
        event(neverStored, "STARTED", null, null, now.minus(Duration.ofDays(5)));
        event(neverStored, "FAILED", "offsite", "UPLOAD_OFFSITE", now.minus(Duration.ofDays(5)));
        purged(neverStored, "oci", "AGE", now.minus(Duration.ofHours(3)));

        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(generationsOf(request)).containsExactly(late + ":true:true");
    }

    /**
     * Q5: 판정은 tombstone마다 짧은 트랜잭션이고 행을 {@code FOR UPDATE SKIP LOCKED}로 잡는다 — 다른 트랜잭션(계정 삭제 단계)이 쥔 행은
     * 기다리지 않고 건너뛰며 다른 tombstone의 판정은 이어진다. 잠금이 풀리면 다음 판정이 그 행을 판정한다.
     */
    @Test
    void 다른_트랜잭션이_쥔_tombstone은_건너뛰고_나머지를_판정한다() throws Exception {
        Instant now = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String held = "judge-held-" + UUID.randomUUID().toString().substring(0, 8);
        String free = "judge-free-" + UUID.randomUUID().toString().substring(0, 8);
        insertTombstone(held, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        insertTombstone(free, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(3)).plus(Duration.ofHours(1)));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(this.manager)
                .executeWithoutResult(status -> {
                    jdbc().sql("SELECT request_id FROM operations_deletion_tombstone WHERE request_id = :r FOR UPDATE")
                            .param("r", held).query(String.class).single();
                    locked.countDown();
                    try {
                        assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }));
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("잠긴 행을 기다리지 않는다")
                    .isLessThan(Duration.ofSeconds(2));
            assertThat(row(free).status()).isEqualTo("DESTROYED");
            assertThat(row(held).status()).isEqualTo("PENDING");
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(this.judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(0, 1, 0));
        assertThat(row(held).status()).isEqualTo("DESTROYED");
    }

    // ------------------------------------------------------------------------------------------ 도움

    private record Stored(String status, @Nullable Instant closedAt, Instant destroyAfter, @Nullable Instant overdueAt,
            @Nullable Instant destroyedAt) {}

    private void insertTombstone(String request, Instant requestedAt, @Nullable Instant closedAt) {
        Instant destroyAfter = (closedAt == null ? requestedAt : closedAt).plus(DeletionTombstoneApi.RETENTION);
        jdbc().sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin, "
                        + "closed_at) VALUES (:r, :d, :a, :b, 'DELETION', :c)")
                .param("r", request).param("d", sha256("judge-digest-" + request))
                .param("a", utc(requestedAt)).param("b", utc(destroyAfter))
                .param("c", closedAt == null ? null : utc(closedAt)).update();
    }

    private void stored(String generation, Instant startedAt) {
        event(generation, "STARTED", null, null, startedAt);
        jdbc().sql("INSERT INTO operations_backup_event (generation_id, event, bytes, sha256, key_id, recorded_at) "
                        + "VALUES (:g, 'SUCCEEDED', 1024, :s, 'k1', :t)")
                .param("g", generation).param("s", sha256("judge-cipher-" + generation))
                .param("t", utc(startedAt.plus(Duration.ofMinutes(5)))).update();
    }

    private void purged(String generation, String store, String code, Instant at) {
        event(generation, "PURGED", store, code, at);
    }

    private void event(String generation, String event, @Nullable String store, @Nullable String code, Instant at) {
        jdbc().sql("INSERT INTO operations_backup_event (generation_id, event, store, code, recorded_at) "
                        + "VALUES (:g, :e, :s, :c, :t)")
                .param("g", generation).param("e", event).param("s", store).param("c", code).param("t", utc(at)).update();
    }

    private Stored row(String request) {
        return jdbc().sql("SELECT status, closed_at, destroy_after, overdue_at, destroyed_at FROM "
                        + "operations_deletion_tombstone WHERE request_id = :r")
                .param("r", request)
                .query((rs, n) -> new Stored(rs.getString(1), instant(rs.getObject(2, OffsetDateTime.class)),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), instant(rs.getObject(4, OffsetDateTime.class)),
                        instant(rs.getObject(5, OffsetDateTime.class))))
                .single();
    }

    /** 영향 generation을 {@code <id>:<oci 파기>:<offsite 파기>}로(정렬). */
    private List<String> generationsOf(String request) {
        return jdbc().sql("SELECT generation_id || ':' || (oci_purged_at IS NOT NULL) || ':' || "
                        + "(offsite_purged_at IS NOT NULL) FROM operations_deletion_backup_generation WHERE request_id = :r "
                        + "ORDER BY generation_id")
                .param("r", request).query(String.class).list();
    }

    private List<String> auditReasons() {
        return jdbc().sql("SELECT reason || ':' || outcome FROM operations_audit_event WHERE command = 'tombstone' "
                        + "ORDER BY sequence")
                .query(String.class).list();
    }

    private void assertNoIdentifiersInAudit(String request) {
        for (String row : jdbc().sql("SELECT actor_kind || '|' || coalesce(CAST(actor_user_id AS text), '-') || '|' || target "
                        + "|| '|' || CAST(details AS text) FROM operations_audit_event WHERE command = 'tombstone'")
                .query(String.class).list()) {
            assertThat(row).startsWith("SYSTEM|-|deletion-tombstone|{\"count\": ").doesNotContain(request)
                    .doesNotContain(sha256("judge-digest-" + request)).doesNotContain("gen_");
        }
    }

    private double gauge(String name) {
        return this.meters.get(name).gauge().value();
    }

    private static String generationOf(Instant at) {
        return GENERATION.format(at);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
