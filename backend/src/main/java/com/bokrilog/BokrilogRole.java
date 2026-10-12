package com.bokrilog;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 같은 jar·이미지를 나눠 실행하는 역할(Story 3.8 AC-3). 값은 {@code bokrilog.role}({@code BOKRILOG_ROLE})이고 기본은
 * {@link #ALL}이다.
 *
 * <ul>
 *   <li>{@link #API} — 공개 HTTP 표면만. 작업 dispatch·heartbeat·주기 측정을 하지 않는다(스케줄링 자체가 없다).
 *   <li>{@link #WORKER} — 같은 servlet 앱으로 뜨지만 공개 포트를 루프백({@code 127.0.0.1})에만 묶고 Nginx upstream·포트 게시
 *       대상이 아니다. 작업 dispatch를 하고, 종료 신호에 drain하며, PostgreSQL session advisory lock을 잡아야만 뜬다(단일
 *       Worker). 관리 포트의 Prometheus는 그대로다.
 *   <li>{@link #ALL} — 지금까지의 동작(한 프로세스가 둘 다). 개발·CI·시험의 기본값이다. drain·단일 잠금이 없다 — 시험 JVM
 *       하나에 컨텍스트가 여럿 뜰 수 있어 잠금을 두면 두 번째 컨텍스트가 뜨지 못한다.
 * </ul>
 *
 * <p>{@code BOKRILOG_ENV=production}에서 {@link #ALL}이면 API 기동을 거부한다({@link BokrilogRoleConfig}) — 운영에서 API와
 * Worker가 한 프로세스면 Worker만 drain할 수 없고 blue/green 두 슬롯이 함께 dispatch한다. 웹 서버를 띄우지 않는 일회성
 * 역할({@code migrate}·{@code deploy-audit})은 이 값을 보지 않는다.
 *
 * <p>메트릭 공통 tag {@code role}의 값이기도 하다({@link TelemetryAllowlist#ROLE_TAG}).
 */
enum BokrilogRole {

    API("api"),
    WORKER("worker"),
    ALL("all");

    /** 역할 속성 이름. */
    static final String PROPERTY = "bokrilog.role";

    /** 값이 없을 때의 역할 이름. */
    static final String DEFAULT = "all";

    private final String value;

    BokrilogRole(String value) {
        this.value = value;
    }

    /** 속성·tag 값(소문자). */
    String value() {
        return this.value;
    }

    /** 이 역할의 프로세스가 스케줄링(작업 dispatch·heartbeat·주기 측정)을 하는가. */
    boolean schedules() {
        return this != API;
    }

    /**
     * 속성 값을 역할로 푼다. 비면 {@link #ALL}이고, 셋({@code api·worker·all}) 밖이면 기동 거부다 — 오타 하나로 운영 API가
     * 조용히 dispatch를 시작하거나 Worker가 공개 포트를 여는 일을 두지 않는다. 대소문자를 접지 않는다.
     */
    static BokrilogRole parse(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return ALL;
        }
        for (BokrilogRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalStateException("bokrilog.role은 api·worker·all 중 하나여야 한다.");
    }

    /**
     * 스케줄링을 켜는 조건: 역할이 {@link #API}가 아니다. 읽을 수 없는 값이면 켜는 쪽으로 판정한다 — 그 값은 어차피
     * {@link BokrilogRoleConfig}가 기동을 거부한다. 모듈의 주기 측정({@code SyncQueueAgeMetrics}·{@code SupportPendingMetrics})은 같은
     * 판정을 식({@code '${bokrilog.role:all}' != 'api'})으로 둔다 — 모듈이 이 루트 타입을 참조하면 루트↔모듈 순환이 된다.
     */
    static final class Schedules implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(PROPERTY, DEFAULT);
            try {
                return parse(value).schedules();
            } catch (IllegalStateException unreadable) {
                return true;
            }
        }
    }
}
