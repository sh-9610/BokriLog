package com.bokrilog.operations;

import java.time.Instant;
import java.util.UUID;

/**
 * 광고 성능 kill switch(Story 4.5). 광고를 띄운 문서의 Web Vitals가 목표(NFR-14 — p75 LCP 2.5초·INP 200ms·CLS 0.1)를 넘었다는
 * 판정 하나를 받아 <b>광고만</b> 끈다 — 전역 {@code ADVERTISING} 제한 하나이고 다른 기능은 제한하지 않는다.
 *
 * <p><b>판정은 부르는 쪽(engagement)이 한다.</b> 표본·창·최소 표본 수·p75는 engagement의 Worker 판정이 소유하고, 이 API는 그 결론과
 * 증거(metric·표본 수·p75)를 받아 기록할 뿐이다.
 *
 * <p><b>자기 트랜잭션 하나다</b>({@code REQUIRES_NEW}). 원장 잠금 → 열린(끝이 정해지지 않은) 전역 {@code ADVERTISING} 제한이 있으면
 * 아무것도 쓰지 않고 {@link Stop.AlreadyRestricted} → 없으면 제한 INSERT({@link RestrictionOrigin#PERFORMANCE}) → 감사 SYSTEM 행
 * ({@link #AUDIT_COMMAND}, 정책 {@link #POLICY_VERSION}, {@code details}에 metric·표본 수·p75) 순서로 한 커밋이다. 원장 잠금이 운영
 * command·강한 신호와 함께 직렬화하므로 동시 판정도 활성 제한 1행으로 모인다.
 *
 * <p><b>자동 해제는 없다.</b> 만든 제한은 운영 command {@code lift-restriction}(Story 3.4)으로만 닫히고 출처가 운영자가 아니므로
 * 수동 검증이 필요하다({@link OperationalRestrictionApi.ActiveRestriction#requiresVerification}).
 *
 * <p>실패는 예외로 올라간다. 감사 digest 키가 없는 역할에서 부르면 실패한다(수행자 digest 없이 행을 쓰지 않는다).
 */
public interface AdvertisingStopApi {

    /** 판정 정책 버전. 감사 행의 {@code policy_version}이다. */
    String POLICY_VERSION = "AD-PERF-1.0";

    /** 감사 원장의 command. */
    String AUDIT_COMMAND = "performance-stop";

    /** 감사 사유의 접두사. 사유는 {@code PERFORMANCE_<metric>}이다(예: {@code PERFORMANCE_LCP}). */
    String REASON_PREFIX = "PERFORMANCE_";

    /** 판정한 Web Vitals metric. */
    enum Metric {
        LCP,
        INP,
        CLS
    }

    /**
     * 목표를 넘은 판정 하나.
     *
     * @param samples 판정 창의 표본 수(1 이상)
     * @param p75 창 표본의 75번째 백분위(0 이상 유한수, LCP·INP는 ms)
     */
    record Breach(Metric metric, long samples, double p75) {

        public Breach {
            if (samples < 1 || !Double.isFinite(p75) || p75 < 0) {
                throw new IllegalArgumentException("판정 증거의 모양이 맞지 않는다.");
            }
        }
    }

    /** 정지의 결말. */
    sealed interface Stop {

        /**
         * 새 전역 {@code ADVERTISING} 제한을 만들었다.
         *
         * @param effectiveFrom 효력 시작(DB 시각). 부르는 쪽은 이 시각까지의 표본을 지운다
         */
        record Created(UUID restrictionId, Instant effectiveFrom) implements Stop {}

        /** 열린 전역 {@code ADVERTISING} 제한이 이미 있다. 아무것도 쓰지 않았다. */
        record AlreadyRestricted(UUID restrictionId) implements Stop {}
    }

    /** 광고를 끈다. 돌아오면 커밋돼 있다. */
    Stop stop(Breach breach);
}
