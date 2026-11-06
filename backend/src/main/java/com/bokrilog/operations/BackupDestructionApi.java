package com.bokrilog.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 삭제된 계정이 든 백업의 파기 판정(Story 5.3, {@code DELETION-TOMBSTONE-1.0}).
 *
 * <p><b>판정의 근거는 기록이다.</b> 백업 기록(V24 {@code operations_backup_event})과 삭제 실행 기록(V31 — 운영 완료 시각은 루트가 identity
 * 공개 API에서 받아 넘긴다)을 대조한다. 저장소를 직접 조회하지 않는다.
 * <ul>
 *   <li><b>영향 generation</b>: 운영 완료 + {@link #CLOSURE_SLACK} 전에 dump를 시작한({@code STARTED}) 저장된({@code SUCCEEDED})
 *       generation 전부. 기록의 하한이
 *       없다(오래전에 시작했는데 아직 두 저장소 파기 기록이 없는 generation도 든다). 실행이 끝나기 전에는 열린 집합이다. 요청 전에 두
 *       저장소에서 이미 파기된 generation은 이 삭제가 지울 것이 아니라 넣지 않는다. 결과({@code SUCCEEDED}·{@code FAILED})가 아직 없는
 *       시작 기록은 그보다 뒤의 {@code STARTED}가 기록될 때까지(백업 절차는 대기열의 결과 행을 먼저 기록한 뒤 다음 실행을 시작한다) 넣지
 *       않지만 완료를 막는다. 실패한 generation은 {@code generations/}에 오르지 않으므로(부분 object는 {@code incoming/}에만 있고 백업
 *       절차의 incoming 정리·30일 규칙과 {@code BackupPurgeOverdue}가 소유한다) 영향 generation이 아니다.
 *   <li><b>저장소별 파기</b>: 그 generation(성공 기록이 있다)의 그 저장소 {@code PURGED} 기록(29일·수량·용량 규칙의 code)이 있다 —
 *       기록 순서와 무관하다(백업 절차가 대기열의 성공 행을 파기 행보다 늦게 기록할 수 있다).
 *       {@code PURGE_FAILED}·한쪽만·기록 없음은 파기가 아니다.
 *   <li><b>백업 파기 완료</b>({@code DESTROYED}): 집합이 닫혔고(운영 완료) 운영 완료 + {@link #CLOSURE_SLACK}이 지났고 실행 중
 *       generation이 없고 집합의 모든 generation에 두 저장소
 *       ({@code oci}·{@code offsite}) 파기 기록이 있다. 완료 시각은 판정 시각이다(운영 완료와 다른 시각).
 *   <li><b>초과</b>({@code OVERDUE}): 백업 파기 기한(요청 + 30일)이 지났는데 완료가 아니다. 감사 SYSTEM 행을 한 번 남기고 완료로 표시하지
 *       않는다. 백업을 앞당겨 지우지 않는다 — 재시도는 다음 백업 실행의 파기다.
 * </ul>
 *
 * <p>판정·측정은 Worker·{@code all}의 주기 작업(루트 {@code BackupDestructionJudge})이 부른다. production에서도 돈다 — 판정은 기록이지
 * 삭제가 아니다. 감사 행은 수와 code뿐이다(digest·요청 ID·사용자 ID 없음).
 */
public interface BackupDestructionApi {

    /** 경보·gauge의 기한 임박 창: 백업 파기 기한 48시간 안. */
    Duration DUE_SOON = Duration.ofHours(48);

    /**
     * 닫힌 집합의 커밋 여유(1시간). 운영 완료 시각은 삭제 마지막 단계의 문장 시각이고 그 커밋은 그 뒤다 — 그 사이에 dump가 시작하면 그
     * snapshot에는 아직 삭제되지 않은 계정이 든다. 그래서 운영 완료 + 여유 전에 시작한 저장된 generation을 영향 generation으로 넣고, 그
     * 시각이 지나기 전에는 완료로 판정하지 않는다. 단계 트랜잭션은 문장 상한·lease(30초) 안에서 끝나므로 1시간은 넉넉하고, 그 대가는 하루
     * 한 번인 백업의 generation 하나를 더 기다리는 것뿐이다(파기 기한 30일 안).
     */
    Duration CLOSURE_SLACK = Duration.ofHours(1);

    /** 집합이 아직 열린(운영 완료 전) tombstone의 요청 ID. 루트가 이 ID의 운영 완료 시각을 identity에서 읽어 {@link #judge}에 넘긴다. */
    List<String> openRequestIds();

    /**
     * 한 번 판정한다(tombstone마다 짧은 트랜잭션 — 잠금 대기 2초·문장 5초 상한). 넘긴 운영 완료 시각으로 열린 집합을 닫고(보존 연장),
     * 파기 완료가 아닌 tombstone마다 영향 generation과 저장소별 파기를 기록하고, 완료·초과를 전환한다. 전환마다 그 트랜잭션에 감사 SYSTEM
     * 행을 남긴다. 상한을 넘기거나 잠겨 있는 tombstone은 건너뛰고 다음 판정이 다시 본다.
     *
     * @param completions 요청 ID → 운영 데이터 삭제 완료 시각(identity V31)
     */
    Judgement judge(Map<String, Instant> completions);

    /**
     * 판정 한 번의 전환 수.
     *
     * @param closed 이번에 닫은 집합(보존 연장)
     * @param destroyed 이번에 백업 파기 완료가 된 tombstone
     * @param overdue 이번에 초과가 된 tombstone
     */
    record Judgement(int closed, int destroyed, int overdue) {}

    /** gauge의 값을 읽는다(읽기 트랜잭션 하나). */
    Measurement measure();

    /**
     * gauge의 값.
     *
     * @param dueSoon 백업 파기 기한까지 {@link #DUE_SOON} 안이고 완료가 아닌 tombstone 수(아직 초과가 아니다)
     * @param overdue 백업 파기 기한이 지났는데 완료가 아닌 tombstone 수
     * @param lastExportEpochSeconds 마지막 tombstone 외부 사본 성공 기록의 DB 시각(epoch 초). 기록이 없으면 비어 있다
     */
    record Measurement(long dueSoon, long overdue, Optional<Double> lastExportEpochSeconds) {}
}
