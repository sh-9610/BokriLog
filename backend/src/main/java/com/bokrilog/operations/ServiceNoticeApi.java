package com.bokrilog.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 제한·장애·복구 상태의 서비스 내부 공지(Story 3.5).
 *
 * <p><b>incident와 공지 버전은 추가 전용이다.</b> 새 사건({@link NoticeStatus#INVESTIGATING})과 같은 사건의 새 버전
 * ({@link NoticeStatus#SCOPE_CHANGED}·{@link NoticeStatus#RECOVERED})은 운영 command {@code publish-notice}
 * ({@link OperatorCommandApi#PUBLISH_NOTICE})만 쓴다 — 감사 {@code COMPLETED} 행과 같은 트랜잭션이다. 이 인터페이스는
 * 읽기만 한다. 버전 행을 고치거나 지우는 경로가 없다.
 *
 * <p><b>공지는 제한을 풀지 않는다.</b> 사용자 조회({@link #forSubjects})는 그 사용자에게 적용되는 효력 중인 제한을
 * evaluator와 같은 술어로 먼저 읽고, 그 제한을 덮는 공지가 있으면 그 공지를, 없으면 안전한 기본 항목
 * ({@link Item.RestrictedWithoutNotice})을 싣는다. 공지가 없다고 제한을 숨기거나 허용으로 접지 않는다.
 *
 * <p>시각의 권위는 PostgreSQL이다. {@code asOf}·시작 시각·복구 시각·72시간 창은 모두 DB 시각이다.
 */
public interface ServiceNoticeApi {

    /** 복구 공지가 영향을 받았던 사용자에게 보이는 기간. 복구 시각부터다(Ask First로 고정). */
    Duration RECOVERED_VISIBLE_FOR = Duration.ofHours(72);

    /** 마지막으로 검증된 응답을 믿는 상한. 응답의 {@code expiresAt}은 이보다 늦지 않다(Ask First로 고정). */
    Duration RESPONSE_TRUST_LIMIT = Duration.ofMinutes(5);

    /** 다음 안내 시각의 상한. 발행 시점의 DB 시각부터다. */
    Duration MAX_NEXT_UPDATE = Duration.ofDays(7);

    /** 공지 메시지의 최대 길이(문자). */
    int MAX_MESSAGE_CHARS = 200;

    /** 한 버전이 덮는 제한의 최대 수. */
    int MAX_COVERS = 50;

    /** 운영자 화면 목록의 사건 상한(최근 순). */
    int MAX_LISTED_INCIDENTS = 50;

    /** 공지 상태. 스키마 CHECK와 같은 집합이다. */
    enum NoticeStatus {
        /** 새 사건. 첫 버전만 이 상태다. */
        INVESTIGATING,
        /** 같은 사건의 범위가 바뀌었다. */
        SCOPE_CHANGED,
        /** 복구됐다. 사건의 마지막 버전이고 그 뒤 버전은 없다. */
        RECOVERED
    }

    /**
     * 공지 버전 하나. 사용자 식별자·금융값·제한 target 원문이 없다.
     *
     * @param previousVersion 첫 버전이면 {@code null}
     * @param startedAt 사건의 첫 버전을 쓴 DB 시각
     * @param affectedFeatures 덮는 제한의 기능. 서버가 계산했다
     * @param continuingFeatures 닫힌 기능 넷 가운데 나머지
     * @param nextUpdateAt 진행 중인 버전의 다음 안내 시각. 복구 버전은 {@code null}
     * @param recoveredAt 복구 버전의 복구 시각(DB). 진행 중인 버전은 {@code null}
     * @param covers 이 버전이 덮는 제한 ID
     * @param operationId 이 버전을 발행한 운영 command
     * @param supportContact 발행 확정 시점의 지원 연락처(AC-1). 재배포로 바뀌지 않는다
     * @param createdAt 이 버전을 쓴 DB 시각
     */
    record NoticeVersion(UUID incidentId, int version, @Nullable Integer previousVersion, NoticeStatus status,
            Instant startedAt, List<RestrictionFeature> affectedFeatures, List<RestrictionFeature> continuingFeatures,
            String message, @Nullable Instant nextUpdateAt, @Nullable Instant recoveredAt, List<UUID> covers,
            String operationId, String supportContact, Instant createdAt) {

        public NoticeVersion {
            affectedFeatures = List.copyOf(affectedFeatures);
            continuingFeatures = List.copyOf(continuingFeatures);
            covers = List.copyOf(covers);
        }
    }

    /**
     * 사건 하나와 그 버전 이력(오래된 순).
     *
     * @param versions 비어 있지 않다
     * @param liveCovers 최신 버전이 덮는 제한의 되돌림 계보 가운데 지금 열린 제한(Epic 3 회고 AI-1, 2026-10-13 사용자 결정) —
     *     덮는 제한 자신이 열려 있으면 그것이고, 해제 smoke 되돌림으로 이어진 후손은 닫힌 중간 행을 지나도 따라간다(먼저 시작한
     *     순, 중복 없음). 계보가 모두 닫혔으면 비어 있다. 운영자 화면의 덮는 제한 기본값과 "아직 효력 중" 집계의 출처다
     */
    record Incident(UUID incidentId, Instant startedAt, List<NoticeVersion> versions, List<UUID> liveCovers) {

        public Incident {
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("버전이 없는 사건이다.");
            }
            versions = List.copyOf(versions);
            liveCovers = List.copyOf(liveCovers);
        }

        /** 최신 버전. */
        public NoticeVersion latest() {
            return this.versions.getLast();
        }

        /** 복구로 닫힌 사건인가. 닫힌 사건은 새 버전을 받지 않는다. */
        public boolean closed() {
            return latest().status() == NoticeStatus.RECOVERED;
        }
    }

    /** 사용자 공지 항목 하나. */
    sealed interface Item {
        /**
         * 그 사용자의 효력 제한을 덮는 공지, 또는 그 사용자가 영향을 받았던 사건의 72시간 안의 복구 공지.
         *
         * @param overdue 진행 중인 공지의 다음 안내 시각이 판정 시각({@code asOf})을 이미 지났다. 화면은 다음 안내가 늦어지고
         *     있다고 말하고, 그 시각은 응답의 만료 계산에서 빠진다
         */
        record Notice(NoticeVersion version, boolean overdue) implements Item {}

        /**
         * 효력 중인 제한을 덮는 공지가 없다. 무엇이 멈췄는지만 말한다 — 공지가 없다고 제한을 숨기지 않는다.
         *
         * @param affectedFeatures 덮이지 않은 제한의 기능
         * @param continuingFeatures 닫힌 기능 넷 가운데 나머지
         */
        record RestrictedWithoutNotice(List<RestrictionFeature> affectedFeatures,
                List<RestrictionFeature> continuingFeatures) implements Item {

            public RestrictedWithoutNotice {
                affectedFeatures = List.copyOf(affectedFeatures);
                continuingFeatures = List.copyOf(continuingFeatures);
            }
        }
    }

    /**
     * 한 사용자의 공지 묶음.
     *
     * @param asOf 판정의 DB 시각
     * @param expiresAt 이 묶음을 믿는 상한: 항목들의 다음 안내 시각 가운데 {@code asOf} 뒤의 가장 이른 것과 {@code asOf +
     *     5분} 중 이른 쪽. 이미 지난 다음 안내 시각은 빼므로 언제나 {@code asOf}보다 늦다
     */
    record UserNotices(Instant asOf, Instant expiresAt, List<Item> items) {

        public UserNotices {
            items = List.copyOf(items);
        }
    }

    /**
     * 그 대상 차원들에 적용되는 공지. 차원은 호출자(루트)가 사용자의 활성·최신 계획·기본 portfolio·현재 연결·계산 정책으로
     * 만든다. 판독 전체가 읽기 전용 {@code REPEATABLE READ} 트랜잭션 하나라 제한과 공지가 같은 스냅샷이다. 판독 오류는 예외로
     * 올라간다 — 조회 실패를 "공지 없음"으로 접지 않는다.
     *
     * @param subjects 비어 있으면 안 된다 — 전역 제한만 보려면 {@link RestrictionSubject#none()} 하나를 넘긴다
     */
    UserNotices forSubjects(List<RestrictionSubject> subjects);

    /**
     * 운영자 화면의 사건 목록. 최근에 시작한 사건이 먼저이고 {@value #MAX_LISTED_INCIDENTS}건까지다. 사건·버전과 최신 버전의
     * 열린 계보({@link Incident#liveCovers})를 읽기 전용 트랜잭션 하나에서 함께 읽는다.
     */
    List<Incident> incidents();

    /** 닫힌 기능 넷 가운데 {@code affected}에 없는 것, 기능 선언 순서다. */
    static List<RestrictionFeature> continuing(Collection<RestrictionFeature> affected) {
        List<RestrictionFeature> continuing = new ArrayList<>();
        for (RestrictionFeature feature : RestrictionFeature.values()) {
            if (!affected.contains(feature)) {
                continuing.add(feature);
            }
        }
        return List.copyOf(continuing);
    }
}
