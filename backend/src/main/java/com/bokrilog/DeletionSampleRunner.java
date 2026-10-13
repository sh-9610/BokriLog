package com.bokrilog;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 검증용 일회성 삭제 표본 역할(Story 3.10). {@code deletion-sample} 프로필에서만 있다.
 *
 * <p>복원 리허설·훈련이 표본 계정 하나({@code bokrilog.deletion.sample.user})를 요청 ID 하나({@code bokrilog.deletion.sample.request})로
 * {@link AccountErasure}에 넘겨 지운다 — tombstone outbox 행과 모듈 삭제가 한 트랜잭션이다. <b>사용자 삭제 경로가 아니다</b>(Epic 5).
 * 리허설 표지({@code bokrilog.deletion.sample.rehearsal=true})가 있어야 하고 운영 환경({@code BOKRILOG_ENV=production})이면 기동을
 * 거부한다 — 운영 데이터에 시험 쓰기를 하지 않는다. 계정이 없으면 실패다.
 * 로그에 식별자를 싣지 않는다.
 */
@Component
@Profile("deletion-sample")
class DeletionSampleRunner implements ApplicationRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeletionSampleRunner.class);

    private final AccountErasure erasure;

    private final String user;

    private final String request;

    private final String env;

    private final boolean rehearsal;

    DeletionSampleRunner(AccountErasure erasure, @Value("${bokrilog.deletion.sample.user:}") String user,
            @Value("${bokrilog.deletion.sample.request:}") String request,
            @Value("${bokrilog.telemetry.env:development}") String env,
            @Value("${bokrilog.deletion.sample.rehearsal:false}") boolean rehearsal) {
        this.erasure = erasure;
        this.user = user;
        this.request = request;
        this.env = env;
        this.rehearsal = rehearsal;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            requireRehearsal(this.env, this.rehearsal);
        } catch (IllegalStateException refused) {
            // 예외 메시지는 로그 encoder가 싣지 않는다 — 거부를 code 한 줄로 남겨 절차가 읽게 한다.
            LOG.atWarn().addKeyValue("code", BokrilogRoleConfig.PRODUCTION.equals(this.env) ? "DELETION_SAMPLE_PRODUCTION"
                    : "DELETION_SAMPLE_NOT_REHEARSAL").log("deletion.sample.refused");
            throw refused;
        }
        UUID userId;
        try {
            userId = UUID.fromString(this.user.strip());
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalArgumentException("표본 계정 ID가 UUID가 아니다.");
        }
        AccountErasure.Outcome outcome = this.erasure.erase(userId, this.request.strip());
        if (outcome != AccountErasure.Outcome.ERASED) {
            throw new IllegalStateException("표본 계정이 없다.");
        }
        LOG.atInfo().addKeyValue("outcome", outcome.name()).log("deletion.sample.erased");
    }

    /**
     * 리허설 표지와 비운영 환경. 둘 다 있어야 돈다 — 운영 환경 값만 막으면 환경 값을 빠뜨린 운영 호출이 지나간다. 메시지에 값을 담지 않는다.
     */
    static void requireRehearsal(String env, boolean rehearsal) {
        if (BokrilogRoleConfig.PRODUCTION.equals(env)) {
            throw new IllegalStateException("삭제 표본 역할은 운영 환경에서 돌지 않는다.");
        }
        if (!rehearsal) {
            throw new IllegalStateException("삭제 표본 역할은 리허설 표지(bokrilog.deletion.sample.rehearsal)가 있어야 돈다.");
        }
    }
}
