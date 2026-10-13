package com.bokrilog.operations.internal;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 삭제된 계정의 잔여 행 세기(Story 3.10 복원 검증). 이 모듈이 소유한 표({@code operations_*}·{@code support_*})의 사용자 열을
 * 카탈로그({@code information_schema.columns})로 찾아 그 사용자 값의 행을 센다 — 표를 이름으로 나열하지 않으므로 새 표가 생겨도
 * 빠지지 않는다. 문장 하나이고 읽기만 한다.
 */
@Repository
class DeletionResidueRepository {

    /** 다른 모듈도 같은 모양의 문장을 자기 표 접두사로 둔다(모듈이 남의 표를 세지 않는다). */
    private static final String RESIDUE = "SELECT CAST(COALESCE(sum(CAST(CAST((xpath('/row/n/text()', query_to_xml(format("
            + "'SELECT count(*) AS n FROM %I WHERE %I = %L', c.table_name, c.column_name, CAST(:user AS text)), "
            + "false, true, '')))[1] AS text) AS bigint)), 0) AS bigint) FROM information_schema.columns c "
            + "WHERE c.table_schema = current_schema() "
            + "AND c.column_name IN ('user_id', 'actor_user_id', 'target_user_id') "
            + "AND (c.table_name LIKE 'operations\\_%' OR c.table_name LIKE 'support\\_%')";

    private final JdbcClient jdbc;

    DeletionResidueRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long remainingRowsOf(UUID userId) {
        Long count = this.jdbc.sql(RESIDUE).param("user", userId).query(Long.class).single();
        return count == null ? 0L : count;
    }
}
