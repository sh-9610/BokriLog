package com.bokrilog;

import com.bokrilog.sync.WorkerExit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * BokriLog 백엔드 진입점.
 *
 * <p>모듈 패키지를 만들지 않는다. 상태 확인 엔드포인트는 루트 패키지 {@code com.bokrilog}에 둔다.
 * Spring Modulith는 루트 패키지를 모듈로 보지 않으므로, Story 1.3이 {@code identity}를 첫 모듈로
 * 넣을 때 경계 검증이 "기존 모듈 하나가 이미 규칙을 어기고 있다"가 아니라 깨끗한 상태에서 시작한다.
 *
 * <p>데이터베이스·사용자 테이블·제공자 자격정보 없이 기동한다. 이 스토리가 요구하는 실행 조건은
 * JDK 25 하나뿐이다.
 */
@SpringBootApplication
public class BokriLogApplication {

    public static void main(String[] args) {
        // 컨텍스트가 뜨기 전 스레드의 예외도 스택 대신 한 줄로 남긴다(Story 3.7, UncaughtExceptionLogging).
        Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionLogging.HANDLER);
        ConfigurableApplicationContext context = SpringApplication.run(BokriLogApplication.class, args);
        // Worker 역할이면 종료 코드가 drain 결과다(Story 3.8, WorkerExit). 신호로 끝난 JVM은 143을 내므로 컨텍스트가 다 닫힌 뒤의
        // 마지막 종료 처리에서 그 값으로 끝낸다. 시험은 이 진입점을 지나지 않으므로 시험 JVM의 종료에는 닿지 않는다.
        context.getBeanProvider(WorkerExit.class).ifAvailable(exit ->
                SpringApplication.getShutdownHandlers().add(() -> Runtime.getRuntime().halt(exit.exitCode())));
    }
}
