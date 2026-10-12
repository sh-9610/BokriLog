package com.bokrilog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * 로그에 나타난 <b>실제 구성 비밀값</b>의 감지(Story 3.7 AC-5, 강한 신호 3번 {@code SECRET_EXPOSED}).
 *
 * <p>패턴 탐지(토큰·이메일·지갑 모양)는 오탐이 있고 강한 신호는 전역 정지로 이어진다. 그래서 신호로 올리는 것은 이 프로세스가
 * 실제로 가진 비밀값이 로그 한 줄의 메시지·인자·구조 인자·예외 문자열에 그대로 나타난 경우 하나뿐이다 — 오탐이 없는 "DLP rule
 * 1건"이다. 패턴 탐지는 {@link RedactingJsonEncoder}가 가리기만 한다.
 *
 * <p><b>정적 등록부인 이유.</b> Logback encoder는 Spring 컨텍스트보다 먼저 생기고 컨텍스트를 모른다. API 프로세스의 {@link
 * ConfiguredSecrets}가 기동 끝에 자기 비밀값 집합과 신호 경로를 여기 등록하고, 컨텍스트가 닫히면 뗀다. 한 JVM에 컨텍스트가 여럿이면
 * (시험의 컨텍스트 캐시) 등록도 여럿이고, 비밀값이 맞은 등록의 경로로 올린다. 등록 전(기동 초기)의 줄은 패턴 가림만 받는다.
 *
 * <p><b>재귀·폭주를 막는다.</b> 신호 경로({@link Alarm})는 비동기로 1회 부른다. 신호를 기록하는 스레드가 남기는 로그는 다시
 * 신호로 올리지 않는다({@link #whileRaising}). 같은 규칙은 프로세스당 60초에 한 번만 올린다 — 같은 줄이 반복돼도 신호는 하나다.
 * 신호 기록이 실패하면 그 억제를 풀어 다음 등장이 다시 시도한다({@link #raiseFailed}).
 */
final class LogSecretGuard {

    /** 신호 증거 {@code ruleId}의 값. 판정과 scope는 3.3 판정표({@code OPS-SIGNAL-1.0})의 {@code SECRET_EXPOSED} 행이 정한다. */
    static final String RULE_ID = "CONFIGURED_SECRET_IN_LOG";

    /** 같은 규칙의 반복 억제 창. */
    static final long SUPPRESSION_NANOS = TimeUnit.SECONDS.toNanos(60);

    /**
     * 비밀값으로 보는 최소 길이. 이보다 짧은 값은 평범한 낱말과 구별되지 않아 오탐으로 전역 정지를 부른다 — 예를 들어 개발 DB의
     * 짧은 비밀번호. 8자인 이유는 네이버 client secret(10자)이 들어와야 하기 때문이다. 이보다 짧아 버린 값은 {@link
     * ConfiguredSecrets}가 기동 때 설정 이름만 WARN으로 알린다.
     */
    static final int MIN_SECRET_CHARS = 8;

    /** 감지를 신호로 넘기는 경로. 호출자 스레드를 막지 않는다(비동기 1회). */
    @FunctionalInterface
    interface Alarm {
        void raise(String ruleId);
    }

    /** 컨텍스트 하나의 등록. */
    record Registration(List<String> secrets, Alarm alarm) {}

    private static final List<Registration> REGISTRATIONS = new CopyOnWriteArrayList<>();

    private static final ThreadLocal<Boolean> RAISING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 규칙별 마지막 신호 시각(단조 시계). */
    private static final Map<String, Long> LAST_RAISED = new ConcurrentHashMap<>();

    private static volatile LongSupplier clock = System::nanoTime;

    private LogSecretGuard() {}

    /** 비밀값 집합(짧은 값은 버린다)과 신호 경로를 등록한다. 남는 값이 없으면 등록하지 않는다. */
    static @Nullable Registration register(Collection<String> secrets, Alarm alarm) {
        List<String> usable = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && secret.length() >= MIN_SECRET_CHARS && !usable.contains(secret)) {
                usable.add(secret);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        Registration registration = new Registration(List.copyOf(usable), alarm);
        REGISTRATIONS.add(registration);
        return registration;
    }

    static void unregister(@Nullable Registration registration) {
        if (registration != null) {
            REGISTRATIONS.remove(registration);
        }
    }

    /** 문자열들 가운데 어느 등록의 비밀값이 나타났는가. 맞은 등록 하나(없으면 {@code null}). */
    static @Nullable Registration exposedIn(Collection<String> texts) {
        if (REGISTRATIONS.isEmpty()) {
            return null;
        }
        for (Registration registration : REGISTRATIONS) {
            for (String secret : registration.secrets()) {
                for (String text : texts) {
                    if (text != null && text.contains(secret)) {
                        return registration;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 감지를 신호로 넘긴다. 신호를 기록하는 스레드 안이거나 억제 창 안이면 아무것도 하지 않는다.
     *
     * @return 이번에 신호 경로를 불렀는가
     */
    static boolean report(Registration registration) {
        if (RAISING.get()) {
            return false;
        }
        long now = clock.getAsLong();
        boolean[] fire = {false};
        LAST_RAISED.compute(RULE_ID, (rule, previous) -> {
            if (previous == null || now - previous >= SUPPRESSION_NANOS) {
                fire[0] = true;
                return now;
            }
            return previous;
        });
        if (fire[0]) {
            registration.alarm().raise(RULE_ID);
        }
        return fire[0];
    }

    /** 신호 기록이 실패했다. 억제를 풀어 다음 등장이 다시 올리게 한다. */
    static void raiseFailed(String ruleId) {
        LAST_RAISED.remove(ruleId);
    }

    /** 신호를 기록하는 동안의 작업. 그 사이 이 스레드가 남기는 로그는 신호로 올리지 않는다(재귀 가드). */
    static void whileRaising(Runnable work) {
        RAISING.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            RAISING.remove();
        }
    }

    /** 시험 전용: 억제 창을 비운다. */
    static void resetSuppression() {
        LAST_RAISED.clear();
    }

    /** 시험 전용: 단조 시계를 바꾼다({@code null}이면 시스템 시계). */
    static void useClock(@Nullable LongSupplier nanoClock) {
        clock = nanoClock == null ? System::nanoTime : nanoClock;
    }
}
