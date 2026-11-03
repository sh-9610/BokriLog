package com.bokrilog.operations.internal;

import com.bokrilog.operations.AccountErasureState;
import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.OperationsDeletionApi;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * 시험이 operations 내부 부품을 다른 배선으로 조립하는 문(Epic 3 회고 B-2, {@code spec-erasure-audit-lock.md}). 운영 클래스의
 * {@code internal}을 루트 패키지 시험에 열지 않으려고 같은 패키지의 이 클래스 하나만 둔다({@code AdProviderTestHook}과 같은 관용).
 * 조립한 부품은 그 컨텍스트의 repository·트랜잭션 관리자를 그대로 쓰고 컨텍스트에 등록되지 않는다 — 컨텍스트가 갈라지지 않는다.
 */
public final class OperationsTestHook {

    private OperationsTestHook() {}

    /** 계정 삭제 시작 여부만 주어진 port로 묻는 신호 서비스. 나머지는 그 컨텍스트의 실제 부품이다. */
    public static OperationalSignalApi signalsAskingVia(ApplicationContext context, AccountErasureState accounts) {
        return signals(context, accounts, context.getBean(PlatformTransactionManager.class));
    }

    /**
     * 기록 트랜잭션({@code REQUIRES_NEW})을 연 직후 그 backend의 pid를 알려 주는 신호 서비스. 나머지는 그 컨텍스트의 실제
     * 부품이다 — 시험이 {@code pg_locks}에서 바로 그 신호의 잠금 대기를 pid로 찾는다(다른 backend의 대기와 섞이지 않는다).
     */
    public static OperationalSignalApi signalsReportingBackend(ApplicationContext context, IntConsumer backend) {
        PlatformTransactionManager real = context.getBean(PlatformTransactionManager.class);
        JdbcClient jdbc = JdbcClient.create(Objects.requireNonNull(
                ((DataSourceTransactionManager) real).getDataSource(), "트랜잭션 관리자의 DataSource"));
        PlatformTransactionManager reporting = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                TransactionStatus status = real.getTransaction(definition);
                if (definition != null
                        && definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                    // 새 트랜잭션의 연결이 이 스레드에 묶였다 — 같은 연결로 pid를 읽는다.
                    backend.accept(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                }
                return status;
            }

            @Override
            public void commit(TransactionStatus status) {
                real.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                real.rollback(status);
            }
        };
        return signals(context, context.getBean(AccountErasureState.class), reporting);
    }

    /** 감사 digest 키가 없는 역할의 삭제 port. 키 외에는 그 컨텍스트의 실제 부품이다. */
    public static OperationsDeletionApi keylessDeletion(ApplicationContext context) {
        return new OperationsDeletionService(context.getBean(OperatorAuditRepository.class),
                context.getBean(OperationalRestrictionRepository.class), context.getBean(SignalRepository.class),
                new StaticListableBeanFactory().getBeanProvider(ActorDigest.class),
                context.getBean(SupportInquiryRepository.class), context.getBean(DeletionResidueRepository.class),
                context.getBean(PlatformTransactionManager.class));
    }

    private static OperationalSignalApi signals(ApplicationContext context, AccountErasureState accounts,
            PlatformTransactionManager transactions) {
        return new OperationalSignalService(context.getBean(SignalRepository.class),
                context.getBean(OperationalRestrictionRepository.class), context.getBean(OperatorAuditRepository.class),
                context.getBeanProvider(ActorDigest.class), accounts, context.getBean(OperationsDeletionService.class),
                transactions);
    }
}
