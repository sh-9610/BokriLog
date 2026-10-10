package com.bokrilog.operations.internal;

import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.ServiceNoticeApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ServiceNoticeApi} 구현(Story 3.5). {@code internal}의 package-private 클래스다.
 *
 * <p><b>사용자 조회는 제한이 먼저다.</b> 그 사용자의 대상 차원마다 evaluator와 같은 술어({@link
 * OperationalRestrictionRepository#matching})로 효력 중인 제한을 읽고, 사건마다의 최신 버전 가운데 그 제한을 덮는 진행
 * 중인 공지를 고른다. 어느 공지도 덮지 않는 제한은 기본 항목 하나로 모은다 — 사건의 최신 버전이 복구여도 그 제한이 아직
 * 효력 중이면 덮이지 않은 것이다. 공지는 제한을 풀거나 숨기지 않는다.
 *
 * <p>복구 공지는 복구 시각부터 72시간 동안, 그 공지가 덮는 제한의 대상이 사용자의 차원과 맞을 때만 싣는다(효력 구간은
 * 보지 않는다 — 이미 닫혔다).
 *
 * <p>판독 전체가 읽기 전용 {@code REPEATABLE READ} 트랜잭션 하나다 — 효력 제한·공지 버전·복구 대상 판정이 같은 스냅샷을
 * 본다. 다음 안내 시각이 이미 지난 공지는 {@code overdue}로 싣고 만료 계산에서 뺀다. 판독 오류는 예외로 올라간다.
 */
@Service
class ServiceNoticeService implements ServiceNoticeApi {

    private static final Set<RestrictionFeature> ALL_FEATURES = EnumSet.allOf(RestrictionFeature.class);

    private final ServiceNoticeRepository notices;

    private final OperationalRestrictionRepository restrictions;

    private final TransactionTemplate snapshot;

    ServiceNoticeService(ServiceNoticeRepository notices, OperationalRestrictionRepository restrictions,
            PlatformTransactionManager transactionManager) {
        this.notices = notices;
        this.restrictions = restrictions;
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public UserNotices forSubjects(List<RestrictionSubject> subjects) {
        if (subjects.isEmpty()) {
            // 아무 차원도 묻지 않는 판독은 배선 실수다. "공지 없음"으로 접지 않는다.
            throw new IllegalArgumentException("대상 차원이 없다.");
        }
        UserNotices read = this.snapshot.execute(status -> read(subjects));
        if (read == null) {
            throw new IllegalStateException("공지를 읽지 못했다.");
        }
        return read;
    }

    private UserNotices read(List<RestrictionSubject> subjects) {
        Instant asOf = this.notices.now();
        // 효력 중인 제한 ID → 기능. 차원이 여럿이어도 한 제한은 한 번만 센다(첫 등장 순서).
        Map<UUID, RestrictionFeature> effective = new LinkedHashMap<>();
        for (RestrictionSubject subject : subjects) {
            for (var row : this.restrictions.matching(ALL_FEATURES, subject, Set.of())) {
                effective.putIfAbsent(row.restriction().restrictionId(), row.restriction().feature());
            }
        }
        List<Item> open = new ArrayList<>();
        List<Item> recovered = new ArrayList<>();
        Set<UUID> covered = new LinkedHashSet<>();
        Instant expiresAt = asOf.plus(RESPONSE_TRUST_LIMIT);
        List<NoticeVersion> candidates = this.notices.latestRelevant(effective.keySet(),
                RECOVERED_VISIBLE_FOR.toSeconds());
        // 복구 공지의 대상 판정은 그 공지들이 덮는 제한 전체를 한 문장으로 묻는다(공지마다 묻지 않는다).
        Set<UUID> recoveredCovers = new LinkedHashSet<>();
        for (NoticeVersion version : candidates) {
            if (version.status() == NoticeStatus.RECOVERED) {
                recoveredCovers.addAll(version.covers());
            }
        }
        Set<UUID> mine = this.restrictions.matchingAmong(recoveredCovers, subjects);
        for (NoticeVersion version : candidates) {
            if (version.status() == NoticeStatus.RECOVERED) {
                if (version.covers().stream().anyMatch(mine::contains)) {
                    recovered.add(new Item.Notice(version, false));
                }
                continue;
            }
            boolean coversMine = false;
            for (UUID cover : version.covers()) {
                if (effective.containsKey(cover)) {
                    covered.add(cover);
                    coversMine = true;
                }
            }
            if (!coversMine) {
                continue;
            }
            Instant next = version.nextUpdateAt();
            // 다음 안내 시각이 이미 지났다: 늦어지고 있다고 싣고 만료 계산에서는 뺀다(지난 시각으로 만료를 당기면
            // 화면이 바닥 간격으로 다시 묻기만 되풀이한다).
            boolean overdue = next != null && !next.isAfter(asOf);
            open.add(new Item.Notice(version, overdue));
            if (next != null && !overdue && next.isBefore(expiresAt)) {
                expiresAt = next;
            }
        }
        List<Item> items = new ArrayList<>(open);
        Set<RestrictionFeature> uncovered = EnumSet.noneOf(RestrictionFeature.class);
        effective.forEach((id, feature) -> {
            if (!covered.contains(id)) {
                uncovered.add(feature);
            }
        });
        if (!uncovered.isEmpty()) {
            items.add(new Item.RestrictedWithoutNotice(List.copyOf(uncovered), ServiceNoticeApi.continuing(uncovered)));
        }
        // 복구 공지는 최근 복구가 먼저다.
        recovered.sort((left, right) -> recoveredAt(right).compareTo(recoveredAt(left)));
        items.addAll(recovered);
        return new UserNotices(asOf, expiresAt, items);
    }


    private static Instant recoveredAt(Item item) {
        if (item instanceof Item.Notice notice && notice.version().recoveredAt() != null) {
            return notice.version().recoveredAt();
        }
        return Instant.EPOCH;
    }

    @Override
    public List<Incident> incidents() {
        Map<UUID, List<NoticeVersion>> grouped = new LinkedHashMap<>();
        for (NoticeVersion version : this.notices.recentIncidentVersions(MAX_LISTED_INCIDENTS)) {
            grouped.computeIfAbsent(version.incidentId(), id -> new ArrayList<>()).add(version);
        }
        List<Incident> incidents = new ArrayList<>();
        grouped.forEach((id, versions) -> incidents.add(new Incident(id, versions.getFirst().startedAt(), versions)));
        return List.copyOf(incidents);
    }
}
