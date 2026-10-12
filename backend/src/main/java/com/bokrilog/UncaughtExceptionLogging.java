package com.bokrilog;

import org.springframework.stereotype.Component;

/**
 * 처리하지 못한 스레드 예외를 로그 한 줄로(Story 3.7 AC-1).
 *
 * <p>JVM 기본 동작은 처리하지 못한 예외의 스택을 stderr로 그대로 찍는다 — 로그 encoder({@link RedactingJsonEncoder})를 지나지
 * 않으므로 스택·메시지에 실린 내부 경로·SQL·값이 가림 없이 나간다. 작업자 가상 스레드({@code FirstSnapshotWorker})와 별도
 * 스레드가 그 경로에 있다. 그래서 JVM 기본 {@link Thread.UncaughtExceptionHandler}를 SLF4J로 돌린다: 예외 클래스의 단순
 * 이름({@code exception})과 스레드 이름 없는 한 줄이다. 스레드 이름에 작업 ID가 들어간다({@code plan-sync-<jobId>}).
 *
 * <p>스케줄러의 예외는 Spring이 자기 오류 처리기로 로그에 넘기고, 그 줄도 encoder가 클래스 이름만 남긴다. 기본 처리기를
 * 바꾸는 것은 프로세스 전역이라 모든 역할(API·migrate)에서 같다. 같은 JVM에서 여러 번 설치해도 결과가 같다.
 */
@Component
class UncaughtExceptionLogging {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(UncaughtExceptionLogging.class);

    /** 설치하는 처리기. 같은 인스턴스라 여러 컨텍스트가 설치해도 바뀌지 않는다. */
    static final Thread.UncaughtExceptionHandler HANDLER = (thread, failure) -> {
        try {
            LOG.atError().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("처리하지 못한 스레드 예외");
        } catch (RuntimeException ignored) {
            // 로깅 실패로 다시 던지지 않는다. 기본 처리기로 넘기면 스택이 stderr로 나간다.
        }
    };

    UncaughtExceptionLogging() {
        Thread.setDefaultUncaughtExceptionHandler(HANDLER);
    }
}
