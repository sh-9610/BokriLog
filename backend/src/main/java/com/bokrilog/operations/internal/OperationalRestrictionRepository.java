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

    /**
     * 그 command에 지금 유효한 제한이 있는가.
     *
     * <p>"지금"은 {@code now()}가 아니라 {@code statement_timestamp()}다. {@code now()}는 트랜잭션이
     * 시작된 시각이라, 잠금을 기다리며 오래 열려 있던 트랜잭션(Story 2.7의 발행)은 그 뒤에 커밋된
     * 제한의 {@code effective_from}보다 이른 시각으로 판정해 제한을 보지 못한다. 제한은 판정하는 문장의
     * 시각에 유효한 것이 기준이다.
     */
    boolean isRestricted(String command) {
        Long count =
                this.jdbc
                        .sql(
                                "SELECT count(*) FROM operations_restriction "
                                        + "WHERE command = :command "
                                        + "AND statement_timestamp() >= effective_from "
                                        + "AND (effective_until IS NULL OR statement_timestamp() < effective_until)")
                        .param("command", command)
                        .query(Long.class)
                        .single();
        return count != null && count > 0;
    }
}
