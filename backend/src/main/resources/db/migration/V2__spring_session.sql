-- Spring Session JDBC의 세션 저장소.
--
-- 세션은 브라우저가 아니라 PostgreSQL에 산다. 브라우저에 남는 것은 불투명한 세션 쿠키 하나뿐이고
-- (`application.yml`의 `server.servlet.session.cookie`), 인증 토큰·금융 원본은 브라우저 영속
-- 저장소에 두지 않는다. 업무 데이터·세션·작업 큐를 한 저장소에 두는 것이 이 프로젝트의 결정이라
-- Redis 세션을 쓰지 않는다.
--
-- 이 DDL은 `spring-session-jdbc` 4.1.1이 배포한 `schema-postgresql.sql`을 그대로 옮긴 것이다.
-- 컬럼 이름·타입·인덱스는 `JdbcIndexedSessionRepository`의 SQL과 한 쌍이라 손으로 바꾸지 않는다.
-- 값을 바꿔야 하면 새 마이그레이션을 더한다. PostgreSQL은 따옴표 없는 식별자를 소문자로 접으므로
-- 실제 테이블 이름은 `spring_session`·`spring_session_attributes`이고 저장소의 SQL도 같게 접힌다.
--
-- Boot의 `spring.session.jdbc.initialize-schema`는 `never`다. 기동 시점에 이 스키마를 만드는 경로를
-- 열어 두면 스키마를 바꾸는 프로세스가 둘이 되어 AD-12가 깨진다. 적용은 migrate 역할 하나뿐이고
-- API 기동은 `Flyway::validate`만 한다.
--
-- expand 우선이다. 이 마이그레이션은 만들기만 하고 아무것도 지우거나 좁히지 않는다.
-- V1은 이미 적용됐으므로 고치지 않는다.
--
-- PRINCIPAL_NAME에는 내부 `user_id` 문자열이 들어간다. 서버 안에만 있는 값이며 로그·지표·오류
-- 응답·HTTP 응답에는 남기지 않는다(AD-14). 세션이 유일한 `user_id` 권위라는 계약이 이 컬럼이다.

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
