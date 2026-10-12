package com.bokrilog;

import com.bokrilog.operations.DeployAuditApi;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 일회성 배포 감사 역할의 진입(Story 3.8 AC-5). {@code deploy-audit} 프로필에서만 있다.
 *
 * <p>배포 스크립트가 단계마다 같은 이미지로 이 역할을 한 번 띄운다. 환경이 준 단계 하나({@code bokrilog.deploy.audit.*},
 * {@code application.yml})를 {@link DeployAuditApi}로 원장에 더하고, 프로세스는 웹 서버 없이 끝난다 — 종료 코드 0이 기록 성공이고,
 * 입력이 모양 밖이거나 쓰기가 실패하면 기동 실패(0이 아님)다. 이 역할은 Flyway를 검사하지 않는다(migration 전의 첫 행 때문).
 *
 * <p>로그에 입력 값을 싣지 않는다. 남는 것은 단계·결과 code뿐이다.
 */
@Component
@Profile("deploy-audit")
class DeployAuditRunner implements ApplicationRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeployAuditRunner.class);

    private final DeployAuditApi audit;

    private final String operation;

    private final String step;

    private final String outcome;

    private final String target;

    private final String actor;

    private final String code;

    private final String slot;

    private final String previous;

    private final boolean rehearsal;

    DeployAuditRunner(DeployAuditApi audit,
            @Value("${bokrilog.deploy.audit.operation:}") String operation,
            @Value("${bokrilog.deploy.audit.step:}") String step,
            @Value("${bokrilog.deploy.audit.outcome:}") String outcome,
            @Value("${bokrilog.deploy.audit.target:}") String target,
            @Value("${bokrilog.deploy.audit.actor:}") String actor,
            @Value("${bokrilog.deploy.audit.code:}") String code,
            @Value("${bokrilog.deploy.audit.slot:}") String slot,
            @Value("${bokrilog.deploy.audit.previous:}") String previous,
            @Value("${bokrilog.deploy.audit.rehearsal:false}") boolean rehearsal) {
        this.audit = audit;
        this.operation = operation;
        this.step = step;
        this.outcome = outcome;
        this.target = target;
        this.actor = actor;
        this.code = code;
        this.slot = slot;
        this.previous = previous;
        this.rehearsal = rehearsal;
    }

    @Override
    public void run(ApplicationArguments args) {
        DeployAuditApi.Event event = event();
        DeployAuditApi.Recorded recorded = this.audit.record(event);
        LOG.atInfo().addKeyValue("kind", event.step().name()).addKeyValue("outcome", event.outcome().name())
                .addKeyValue("count", recorded.activeRestrictions()).log("deploy.audit.recorded");
    }

    /** 환경 값을 단계 하나로 푼다. 모양 밖이면 {@link IllegalArgumentException}이다. */
    DeployAuditApi.Event event() {
        return new DeployAuditApi.Event(this.operation, parse(DeployAuditApi.Step.class, this.step, "단계"),
                parse(DeployAuditApi.Outcome.class, this.outcome, "결과"), blankToNull(this.target), this.actor,
                blankToNull(this.code),
                this.slot.isBlank() ? null : parse(DeployAuditApi.Slot.class, this.slot.toUpperCase(Locale.ROOT), "슬롯"),
                blankToNull(this.previous), this.rehearsal);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(name + " 값이 닫힌 목록 밖이다.");
        }
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
