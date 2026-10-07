package com.bokrilog.operations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 감사 원장의 조회(Story 3.1 AC-5).
 *
 * <p><b>추가와 조회뿐이다.</b> 원 감사 행을 수정·삭제하는 API는 없다 — 추가는 {@link OperatorCommandApi}가 command
 * 흐름 안에서 하고, 계정 삭제의 비식별은 {@link OperationsDeletionApi} 하나가 한다.
 *
 * <p>응답은 allowlist 필드({@link Entry})만이다. {@code actor_user_id}·{@code details} 원문·멱등 키 digest·확인 hash는
 * 나가지 않는다 — 수행자는 keyed digest로만 보이고, 거절 사유는 닫힌 code 하나로만 보인다.
 */
public interface OperatorAuditApi {

    /** 한 쪽의 최대 행 수. 더 크게 달라고 해도 이 값으로 접는다. */
    int MAX_PAGE_SIZE = 200;

    int DEFAULT_PAGE_SIZE = 50;

    /**
     * 조회 필터. 날짜는 {@code Asia/Seoul}의 달력 날짜이고 양 끝을 포함한다.
     *
     * @param command 정확히 같은 command
     * @param scope 정확히 같은 scope
     * @param outcome 정확히 같은 결과
     */
    record Filter(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String command, @Nullable String scope,
            OperatorCommandApi.@Nullable AuditOutcome outcome) {}

    /**
     * 감사 행 하나의 allowlist 필드.
     *
     * @param actorDigest 수행자의 keyed digest. 원식별자가 아니다
     * @param code 결정 행의 닫힌 거절·실패 code. 없으면 {@code null}
     */
    record Entry(long sequence, String operationId, String actorKind, String actorDigest, String command,
            String scope, String target, String reason, String outcome, @Nullable String code, String policyVersion,
            Instant requestedAt, @Nullable Instant decidedAt, Instant recordedAt, String correlationId) {}

    /**
     * 한 쪽.
     *
     * @param nextCursor 다음 쪽의 불투명 cursor. 끝이면 {@code null}
     */
    record Page(List<Entry> entries, @Nullable String nextCursor) {}

    /**
     * 최신순 keyset 조회. {@code (recorded_at, sequence)}가 cursor이며 추가 전용이라 쪽 사이에 행이 빠지거나 겹치지
     * 않는다.
     *
     * @param cursor 앞 쪽이 준 값. {@code null}이면 첫 쪽
     * @param limit 1 이상. {@link #MAX_PAGE_SIZE}를 넘으면 그 값으로 접는다
     * @return 읽을 수 없는 cursor면 빈 값
     */
    Optional<Page> search(Filter filter, @Nullable String cursor, int limit);

    /** 그 수행자의 operation 행들, 오래된 순. 남의·미존재 operation은 빈 목록으로 같다. */
    List<Entry> operation(UUID actorUserId, String operationId);
}
