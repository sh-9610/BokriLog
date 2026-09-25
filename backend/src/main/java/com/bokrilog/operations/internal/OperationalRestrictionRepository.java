package com.bokrilog.operations.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code operations_restriction}에 닿는 유일한 자리.
 *
 * <p>문장이 하나다. 유효 구간 판정을 술어에 넣는 것이 요점이며, 행을 읽어 자바가 시각을 비교하면
 * 판정과 조회 사이에 창이 생기고 그 창의 넓이는 인스턴스 시계의 차이만큼이다.
 */
@Repository
class OperationalRestrictionRepository {

    private final JdbcClient jdbc;

    OperationalRestrictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 그 command에 지금 유효한 제한이 있는가. */
    boolean isRestricted(String command) {
        Long count =
                this.jdbc
                        .sql(
                                "SELECT count(*) FROM operations_restriction "
                                        + "WHERE command = :command "
                                        + "AND now() >= effective_from "
                                        + "AND (effective_until IS NULL OR now() < effective_until)")
                        .param("command", command)
                        .query(Long.class)
                        .single();
        return count != null && count > 0;
    }
}
