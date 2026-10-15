package com.bokrilog.engagement.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 삭제된 계정의 잔여 행 세기(Story 3.10 복원 검증, Story 4.1·4.2). 모듈 수준의 판독이라 표 하나의 리포지토리가 아니라 deletion
 * port({@link EngagementDeletionService})가 소유한다.
 *
 * <p>이 모듈이 소유한 표({@code engagement_*})의 {@code user_id} 열을 카탈로그로 찾아 그 사용자 값의 행을 센다 — 표를 이름으로
 * 나열하지 않으므로 Epic 4의 다음 표가 생겨도 빠지지 않는다(operations의 {@code DeletionResidueRepository}와 같은 모양). 읽기만
 * 한다.
 */
@Repository
class EngagementResidueRepository {

    private static final String RESIDUE = "SELECT CAST(COALESCE(sum(CAST(CAST((xpath('/row/n/text()', query_to_xml(format("
            + "'SELECT count(*) AS n FROM %I WHERE %I = %L', c.table_name, c.column_name, CAST(:user AS text)), "
            + "false, true, '')))[1] AS text) AS bigint)), 0) AS bigint) FROM information_schema.columns c "
            + "WHERE c.table_schema = current_schema() AND c.column_name = 'user_id' "
            + "AND c.table_name LIKE 'engagement\\_%'";

    private final JdbcClient jdbc;

    EngagementResidueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 사용자 값이 남은 이 모듈 표의 행 수. */
    long remainingRowsOf(UUID userId) {
        Long count = this.jdbc.sql(RESIDUE).param("user", userId).query(Long.class).single();
        return count == null ? 0L : count;
    }
}
